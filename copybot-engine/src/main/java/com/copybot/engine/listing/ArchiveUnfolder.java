package com.copybot.engine.listing;

import com.copybot.engine.pipeline.ArchivesConfig;
import com.copybot.exception.CopybotException;
import com.copybot.logger.CopybotLogger;
import com.copybot.plugin.api.action.ListingSink;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import com.copybot.resources.ResourcesEngine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.PatternSyntaxException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Unfolds the ZIP archives a listing emits into ordinary items (spec grouping/archives, "Dépliage des archives"):
 * each file entry becomes a {@link WorkItem#remote} listed in {@code <archive>!/<inner folder>}, the other items pass
 * through. An archive is checked whole before anything is emitted (readable, not encrypted, no overlapping entries,
 * enough space for its local copy): on failure, it goes to {@link ListingFailures} and nothing is emitted for it.
 * <p>
 * The inner folders are emitted one after the other, each followed by its {@code folderDone}, then the archive root.
 * With {@code mergeFolder}, the archives of a listed folder form one tree {@code <folder>!/…} whose folders are done
 * after the {@code folderDone} of the real folder (or at {@link #finish()}). With {@code nested}, a ZIP entry is
 * unfolded in turn, up to the depth {@value #MAX_DEPTH} (the listed archive is at depth 1). Called by one listing
 * thread: no lock.
 */
public final class ArchiveUnfolder implements ListingSink {

    private static final CopybotLogger LOG = CopybotLogger.getLogger(ArchiveUnfolder.class);

    static final int MAX_DEPTH = 5;

    /** An inner folder: its listed folder, its entries and its sub-folders, in the order of the archive. */
    private static final class Folder {
        final String path;
        final List<WorkItem> items = new ArrayList<>();
        final Map<String, Folder> children = new LinkedHashMap<>();

        Folder(String path) {
            this.path = path;
        }

        Folder child(String name) {
            return children.computeIfAbsent(name, n -> new Folder(path + "/" + n));
        }
    }

    private final ArchivesConfig config;
    private final ArchiveWorkspace ws;
    private final ArchiveSources sources;
    private final ListingSink downstream;
    private final ListingFailures failures;
    private final int depth;
    private final ArchiveProgress progress;
    private final List<PathMatcher> includes;
    private final List<PathMatcher> excludes;
    /** With mergeFolder: the inner folders of the archives of each real folder, done after its folderDone. */
    private final Map<String, Folder> merged = new LinkedHashMap<>();
    private ArchiveUnfolder nested;

    public ArchiveUnfolder(ArchivesConfig config, ArchiveWorkspace ws, ArchiveSources sources, ListingSink downstream,
                           ListingFailures failures, int depth) {
        this(config, ws, sources, downstream, failures, depth, null);
    }

    /**
     * @param depth    the depth of the archives this unfolder unfolds: 1 for the archives of the listing
     * @param progress reports the archives unfolded and copied, null: no report
     * @throws CopybotException archive.config.invalid-glob
     */
    public ArchiveUnfolder(ArchivesConfig config, ArchiveWorkspace ws, ArchiveSources sources, ListingSink downstream,
                           ListingFailures failures, int depth, ArchiveProgress progress) {
        this.config = config;
        this.ws = ws;
        this.sources = sources;
        this.downstream = downstream;
        this.failures = failures;
        this.depth = depth;
        this.progress = progress;
        this.includes = matchers(config.include());
        this.excludes = matchers(config.exclude());
    }

    @Override
    public void item(WorkItem item) {
        String name = item.getNameDisplay();
        if (config.isUnfold() && isZip(name)) {
            unfold(item);
        } else {
            downstream.item(item);
        }
    }

    @Override
    public void folderDone(String folder) {
        downstream.folderDone(folder);
        Folder virtual = merged.remove(folder);
        if (virtual != null) {
            done(virtual);
        }
    }

    /** Ends the listing: the merged folders whose real folder was not signalled are done. */
    public void finish() {
        List<Folder> left = new ArrayList<>(merged.values());
        merged.clear();
        left.forEach(this::done);
    }

    private void unfold(WorkItem archive) {
        String location = ArchiveSources.location(archive);
        Path local = null;
        ZipFile zip = null;
        Folder root;
        int entries;
        List<Object[]> warnings = new ArrayList<>(); // {key, args...}: given once the archive is unfolded
        try {
            if (depth == 1 && archive.getLocalLocation() == null && progress != null) {
                Long size = archive.getMetadatas().getSize();
                progress.toDownload(size == null ? 0 : size);
            }
            local = ws.localCopy(archive);
            ZipLayout.checkNoOverlap(local);
            zip = openZip(local);
            String listed = config.isMergeFolder() ? archive.getListedFolder() + "!" : location + "!";
            root = new Folder(listed);
            entries = plan(zip, archive, location, displayLocation(archive) + "!", root, warnings);
        } catch (IOException | RuntimeException e) {
            discard(archive, zip, local);
            failures.failed(archive, failure(location, e));
            return;
        }
        sources.register(archive, entries, zip);
        for (Object[] warning : warnings) {
            Object[] args = Arrays.copyOfRange(warning, 1, warning.length);
            LOG.warn((String) warning[0], args);
            sources.warn(ResourcesEngine.getString((String) warning[0], args));
        }
        ws.keep(zip);
        if (progress != null) {
            progress.unfolded();
        }
        if (config.isMergeFolder()) {
            emitItems(root);
            mergeInto(merged.computeIfAbsent(archive.getListedFolder(), f -> new Folder(root.path)), root);
        } else {
            emit(root);
        }
    }

    /**
     * Reads the entries into the tree of {@code root}, every item created, nothing emitted.
     *
     * @return the number of file entries of the archive, the ones left out included
     */
    private int plan(ZipFile zip, WorkItem archive, String location, String display, Folder root,
                     List<Object[]> warnings) {
        Instant archiveTime = archive.getMetadatas().getTime(WorkItemMetadata.LAST_MODIFIED).orElse(Instant.EPOCH);
        int count = 0;
        for (ZipEntry entry : Collections.list(zip.entries())) {
            if (entry.isDirectory()) {
                continue;
            }
            count++;
            String[] inner = innerPath(entry.getName());
            if (inner == null) {
                warnings.add(new Object[] {"archive.entry.refused", location, entry.getName()});
                continue;
            }
            String name = inner[inner.length - 1];
            boolean zipEntry = isZip(name);
            boolean unfolded = zipEntry && unfoldsNested();
            if (!unfolded && !included(inner)) { // an archive to unfold is not filtered: its entries are
                continue;
            }
            Folder folder = root;
            String shown = display;
            for (int i = 0; i < inner.length - 1; i++) {
                folder = folder.child(inner[i]);
                shown = shown + "/" + inner[i];
            }
            if (zipEntry && config.isNested() && !unfolded) {
                warnings.add(new Object[] {"archive.too-deep", shown + "/" + name, MAX_DEPTH});
            }
            // a nested archive copied as it is keeps its parent: no deleter, never released
            FileTime time = entry.getLastModifiedTime();
            folder.items.add(WorkItem.remote(name, folder.path, shown, entry.getSize(),
                    time == null ? archiveTime : time.toInstant(), () -> open(zip, entry),
                    zipEntry && !unfolded ? null : sources.entryDeleter(archive)));
        }
        return count;
    }

    private boolean unfoldsNested() {
        return config.isNested() && depth < MAX_DEPTH;
    }

    private static boolean isZip(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".zip");
    }

    /**
     * Opens the archive, its names in UTF-8, else (an entry name that is not UTF-8) in CP437, the ZIP default for
     * the entries without the UTF-8 flag. Refuses an encrypted entry ("invalid CEN header (encrypted entry)").
     */
    private static ZipFile openZip(Path local) throws IOException {
        try {
            return new ZipFile(local.toFile());
        } catch (ZipException e) {
            try {
                return new ZipFile(local.toFile(), Charset.forName("IBM437"));
            } catch (IOException retry) {
                e.addSuppressed(retry);
                throw e;
            }
        }
    }

    private void emit(Folder folder) {
        folder.items.forEach(this::emitItem);
        folder.children.values().forEach(this::emit);
        downstream.folderDone(folder.path);
    }

    private void emitItems(Folder folder) {
        folder.items.forEach(this::emitItem);
        folder.children.values().forEach(this::emitItems);
    }

    private void done(Folder folder) {
        folder.children.values().forEach(this::done);
        downstream.folderDone(folder.path);
    }

    private static void mergeInto(Folder target, Folder source) {
        source.children.forEach((name, child) -> mergeInto(target.child(name), child));
    }

    private void emitItem(WorkItem entry) {
        if (isZip(entry.getNameDisplay()) && unfoldsNested()) {
            if (nested == null) {
                ArchivesConfig child = new ArchivesConfig(true, true, false, config.include(), config.exclude());
                nested = new ArchiveUnfolder(child, ws, sources, downstream, failures, depth + 1, progress);
            }
            nested.item(entry);
        } else {
            downstream.item(entry);
        }
    }

    /** The content of an entry, reopened at each read, its compression ratio guarded. */
    private static InputStream open(ZipFile zip, ZipEntry entry) {
        try {
            return new RatioGuardInputStream(zip.getInputStream(entry), entry.getCompressedSize(), entry.getName());
        } catch (IOException | RuntimeException e) { // a closed archive throws IllegalStateException
            IOException failure = e instanceof IOException io ? io : new IOException(e.getMessage(), e);
            return new InputStream() {
                @Override
                public int read() throws IOException {
                    throw failure;
                }
            };
        }
    }

    /**
     * The inner folders and the name of an entry, '/' between them; a name that is empty, absolute or holds "..",
     * '\' or ':' keeps its last segment alone (no escape out of the folder); null: refused (no name left).
     */
    static String[] innerPath(String raw) {
        List<String> segments = new ArrayList<>();
        boolean unsafe = raw.isEmpty() || raw.startsWith("/");
        for (String segment : raw.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..") || segment.indexOf('\\') >= 0 || segment.indexOf(':') >= 0) {
                unsafe = true;
            }
            segments.add(segment);
        }
        if (!unsafe && !segments.isEmpty()) {
            return segments.toArray(String[]::new);
        }
        int cut = Math.max(raw.lastIndexOf('/'), Math.max(raw.lastIndexOf('\\'), raw.lastIndexOf(':')));
        String last = raw.substring(cut + 1);
        return last.isEmpty() || last.equals(".") || last.equals("..") ? null : new String[] {last};
    }

    private boolean included(String[] inner) {
        Path path;
        try {
            path = Path.of(String.join("/", inner).toLowerCase(Locale.ROOT));
        } catch (InvalidPathException e) {
            return includes.isEmpty(); // a name this system cannot hold matches no glob
        }
        return (includes.isEmpty() || includes.stream().anyMatch(m -> m.matches(path)))
                && excludes.stream().noneMatch(m -> m.matches(path));
    }

    /** Case-insensitive globs, as file.read's: "**&#47;x" also matches x at the first level. */
    private static List<PathMatcher> matchers(List<String> globs) {
        if (globs == null) {
            return List.of();
        }
        List<PathMatcher> matchers = new ArrayList<>();
        for (String glob : globs) {
            String pattern = glob.toLowerCase(Locale.ROOT);
            try {
                matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + pattern));
                if (pattern.startsWith("**/")) {
                    matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + pattern.substring(3)));
                }
            } catch (PatternSyntaxException e) {
                throw CopybotException.ofResource(e, "archive.config.invalid-glob", glob);
            }
        }
        return List.copyOf(matchers);
    }

    /** Where the entries are shown: the real archive, also with mergeFolder. */
    private static String displayLocation(WorkItem archive) {
        return archive.getLocalLocation() != null
                ? archive.getLocalLocation().toString()
                : archive.getSourceLocationDisplay() + "/" + archive.getNameDisplay();
    }

    private void discard(WorkItem archive, ZipFile zip, Path local) {
        try {
            if (zip != null) {
                zip.close();
            }
            if (local != null && archive.getLocalLocation() == null) {
                Files.deleteIfExists(local);
            }
        } catch (IOException e) {
            LOG.warn(e, "archive.workspace.cleanup-failed", local, e.getLocalizedMessage());
        }
    }

    private static CopybotException failure(String location, Exception e) {
        if (e instanceof CopybotException copybot) {
            return copybot;
        }
        String key = e instanceof ZipBombException ? "archive.zip-bomb" : "archive.unreadable";
        return CopybotException.ofResource(e, key, location, e.getLocalizedMessage());
    }
}

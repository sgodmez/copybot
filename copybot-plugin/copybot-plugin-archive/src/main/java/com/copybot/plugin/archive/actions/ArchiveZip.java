package com.copybot.plugin.archive.actions;

import com.copybot.plugin.api.action.AbstractActionWithConfig;
import com.copybot.plugin.api.action.IRunEndAction;
import com.copybot.plugin.api.action.ItemOutcome;
import com.copybot.plugin.api.action.RunEndContext;
import com.copybot.plugin.api.action.RunItem;
import com.copybot.plugin.api.action.RunReport;
import com.copybot.plugin.api.action.WrittenFile;
import com.copybot.plugin.api.definition.IPlugin;
import com.copybot.resources.CombinedResourceBundle;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.channels.ClosedByInterruptException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * archive.zip: at the end of the run, a ZIP of the files it wrote with a SHA256SUMS manifest (sha256sum format), or
 * the manifest alone (spec run-end, archive). The hashes are the ones computed during the copy; a file without one
 * (a skipped target) is hashed here.
 * <p>
 * The archive is written to a temporary file next to the target, then renamed: an interruption (time limit,
 * cancellation) or a failure leaves nothing under the final name and removes the temporary file. An existing file is
 * never overwritten: the archive takes the next free name "name (1).zip". The names of the entries are relative to
 * the base folder with "/" separators and never hold ".." or an absolute part: a file outside the base folder is left
 * out with a warning.
 */
public class ArchiveZip extends AbstractActionWithConfig<ArchiveConfig> implements IRunEndAction {

    public static final String CODE = "archive.zip";
    public static final String MANIFEST = "SHA256SUMS";
    public static final String OUTPUT_PATH = "archive.path";

    private static final Pattern VARIABLE = Pattern.compile("\\{(date|time|pipeline)}");
    private static final Pattern FORBIDDEN_IN_NAME = Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]");
    private static final Pattern RESERVED_NAME = Pattern.compile("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?");
    private static final int BUFFER = 64 * 1024;

    /** Formats that are already compressed: written without compression, since deflating them costs time for nothing. */
    private static final Set<String> MEDIA = Set.of(
            "jpg", "jpeg", "heic", "heif", "png", "gif", "tif", "tiff", "webp",
            "cr2", "cr3", "nef", "arw", "raf", "orf", "rw2", "dng", "pef", "srw",
            "mp4", "mov", "m4v", "avi", "mkv", "mts", "m2ts", "3gp",
            "mp3", "m4a", "aac", "flac", "ogg");

    /** A file to archive: where it is, its name in the archive and its hash. */
    private record Entry(Path file, String name, String sha256) {
    }

    @Override
    protected Class<ArchiveConfig> getConfigClass() {
        return ArchiveConfig.class;
    }

    /** No limit: a ZIP of several GB to a NAS takes the time it takes. */
    @Override
    public Duration defaultTimeout() {
        return Duration.ZERO;
    }

    /** Opens a file to read; the single door to the files, so that a test can interrupt a read. */
    InputStream open(Path file) throws IOException {
        return Files.newInputStream(file);
    }

    @Override
    public void runEnded(RunReport report, RunEndContext context) {
        ArchiveConfig config = getConfig();
        if (config == null || config.target() == null || config.target().isBlank()) {
            throw new IllegalArgumentException(message("archive.error.target-missing"));
        }
        boolean zip = isZip(config.content());
        Path root = root(report);
        Path target = target(config.target(), report, root);
        if (Thread.currentThread().isInterrupted()) {
            throw interrupted();
        }

        Map<Path, WrittenFile> files = files(report, Boolean.TRUE.equals(config.includeSkipped()));
        List<Entry> entries;
        try {
            entries = files.isEmpty() ? List.of()
                    : entries(files, base(config.baseFolder(), files.keySet(), root), zip, context);
        } catch (InterruptedIOException e) {
            throw interrupted();
        }
        if (entries.isEmpty()) {
            context.warn(message("archive.info.no-file"));
            return;
        }

        Path finalPath = null;
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = createTemp(parent == null ? Path.of("").toAbsolutePath() : parent);
            try {
                try (OutputStream out = Files.newOutputStream(temp)) {
                    if (zip) {
                        writeZip(entries, out);
                    } else {
                        out.write(manifest(entries));
                    }
                }
                checkInterrupted();
                finalPath = reserve(target);
                move(temp, finalPath);
                temp = null;
            } finally {
                if (temp != null) {
                    Files.deleteIfExists(temp);
                    if (finalPath != null) {
                        // the empty file that reserved the name
                        Files.deleteIfExists(finalPath);
                    }
                }
            }
        } catch (IOException e) {
            if (isInterruption(e)) {
                throw interrupted();
            }
            throw new IllegalStateException(message("archive.error.io", target, e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage())));
        }
        if (!finalPath.equals(target)) {
            context.warn(message("archive.info.renamed", target, finalPath));
        }
        context.publish(OUTPUT_PATH, finalPath.toString());
    }

    private boolean isZip(String content) {
        if (content == null || content.isBlank() || content.strip().equalsIgnoreCase("zip")) {
            return true;
        }
        if (content.strip().equalsIgnoreCase("manifest")) {
            return false;
        }
        throw new IllegalArgumentException(message("archive.error.content-unknown", content));
    }

    // --- interruption ---

    /** The engine interrupts the thread to stop the action (time limit, cancellation). */
    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException();
        }
    }

    /** An interruption surfaces as an InterruptedIOException, a ClosedByInterruptException or a flag left set. */
    private static boolean isInterruption(IOException e) {
        return e instanceof InterruptedIOException || e instanceof ClosedByInterruptException
                || Thread.currentThread().isInterrupted();
    }

    private IllegalStateException interrupted() {
        Thread.currentThread().interrupt();
        return new IllegalStateException(message("archive.error.interrupted"));
    }

    // --- what to archive ---

    /** The files of the run, each once (a target written twice counts for the last write), in absolute form. */
    private static Map<Path, WrittenFile> files(RunReport report, boolean includeSkipped) {
        Map<Path, WrittenFile> files = new LinkedHashMap<>();
        for (WrittenFile written : report.writtenFiles()) {
            if (written.target() != null) {
                files.put(written.target().toAbsolutePath().normalize(), written);
            }
        }
        if (includeSkipped) {
            for (RunItem item : report.items()) {
                if (item.outcome() == ItemOutcome.SKIPPED && item.target() != null) {
                    files.putIfAbsent(item.target().toAbsolutePath().normalize(), null);
                }
            }
        }
        return files;
    }

    /** What a relative target or base folder is relative to: the pipeline file's folder, else the working directory. */
    private static Path root(RunReport report) {
        Path pipeline = report.pipelinePath();
        Path parent = pipeline == null ? null : pipeline.toAbsolutePath().getParent();
        return parent == null ? Path.of("").toAbsolutePath() : parent;
    }

    /** The base folder: the configured one, or the deepest directory holding all the files (of the first one's root). */
    private static Path base(String configured, Set<Path> files, Path root) {
        if (configured != null && !configured.isBlank()) {
            return root.resolve(configured.strip()).normalize();
        }
        Path common = null;
        for (Path file : files) {
            Path dir = file.getParent();
            if (dir == null) {
                continue;
            }
            if (common == null) {
                common = dir;
            } else if (dir.getRoot() != null && dir.getRoot().equals(common.getRoot())) {
                while (!dir.startsWith(common)) {
                    common = common.getParent();
                }
            }
        }
        return common;
    }

    private List<Entry> entries(Map<Path, WrittenFile> files, Path base, boolean zip, RunEndContext context)
            throws InterruptedIOException {
        TreeMap<String, Entry> byName = new TreeMap<>();
        for (Map.Entry<Path, WrittenFile> candidate : files.entrySet()) {
            checkInterrupted();
            Path file = candidate.getKey();
            if (base == null || !file.startsWith(base) || file.equals(base)) {
                context.warn(message("archive.warn.outside", file, base == null ? "" : base));
                continue;
            }
            String name = name(base.relativize(file));
            if (zip && name.equals(MANIFEST)) {
                // the manifest of the archive takes this name
                context.warn(message("archive.warn.reserved", file, MANIFEST));
                continue;
            }
            String hash = candidate.getValue() == null ? null : candidate.getValue().sha256();
            try {
                if (!Files.isRegularFile(file)) {
                    throw new NoSuchFileException(file.toString());
                }
                if (hash == null || hash.isBlank()) {
                    hash = sha256(file);
                }
            } catch (IOException e) {
                if (isInterruption(e)) {
                    throw new InterruptedIOException();
                }
                context.warn(message("archive.warn.unreadable", file));
                continue;
            }
            byName.put(name, new Entry(file, name, hash.toLowerCase(Locale.ROOT)));
        }
        return new ArrayList<>(byName.values());
    }

    /** The relative path with "/" separators (the elements of a path under the base never hold ".."). */
    private static String name(Path relative) {
        List<String> parts = new ArrayList<>();
        for (Path part : relative) {
            parts.add(part.toString());
        }
        return String.join("/", parts);
    }

    // --- writing ---

    private void writeZip(List<Entry> entries, OutputStream out) throws IOException {
        byte[] buffer = new byte[BUFFER];
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (Entry entry : entries) {
                checkInterrupted();
                ZipEntry zipEntry = new ZipEntry(entry.name());
                zipEntry.setLastModifiedTime(Files.getLastModifiedTime(entry.file()));
                // a media file is not deflated: level 0 writes it as it is, in a single pass
                zip.setLevel(isMedia(entry.name()) ? Deflater.NO_COMPRESSION : Deflater.DEFAULT_COMPRESSION);
                zip.putNextEntry(zipEntry);
                try (InputStream in = open(entry.file())) {
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        checkInterrupted();
                        zip.write(buffer, 0, read);
                    }
                }
                zip.closeEntry();
            }
            zip.setLevel(Deflater.DEFAULT_COMPRESSION);
            zip.putNextEntry(new ZipEntry(MANIFEST));
            zip.write(manifest(entries));
            zip.closeEntry();
        }
    }

    /** sha256sum format: "hash", two spaces, the path; one line each, LF. */
    private static byte[] manifest(List<Entry> entries) {
        StringBuilder text = new StringBuilder();
        for (Entry entry : entries) {
            text.append(entry.sha256()).append("  ").append(entry.name()).append('\n');
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static boolean isMedia(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 && MEDIA.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        byte[] buffer = new byte[BUFFER];
        try (InputStream in = open(file)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                checkInterrupted();
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** The temporary file, with the default permissions (createTempFile would make it readable by its owner only). */
    private static Path createTemp(Path directory) throws IOException {
        while (true) {
            try {
                return Files.createFile(directory.resolve(".archive-" + UUID.randomUUID() + ".tmp"));
            } catch (FileAlreadyExistsException e) {
                // a collision of random names: another one
            }
        }
    }

    /** Creates the final file empty, under the target's name or "name (1).ext", "name (2).ext"...: never overwrites. */
    private static Path reserve(Path target) throws IOException {
        String name = target.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        Path candidate = target;
        for (int n = 1; ; n++) {
            try {
                return Files.createFile(candidate);
            } catch (FileAlreadyExistsException e) {
                candidate = target.resolveSibling(stem + " (" + n + ")" + extension);
            }
        }
    }

    /** Onto the reserved (empty) file, which only this action holds. */
    private static void move(Path temp, Path reserved) throws IOException {
        try {
            Files.move(temp, reserved, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, reserved, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // --- target ---

    /** {date} yyyy-MM-dd and {time} HHmmss of the start of the run, {pipeline} made safe for a file name. */
    private Path target(String template, RunReport report, Path root) {
        ZonedDateTime start = report.started().atZone(ZoneId.systemDefault());
        Matcher matcher = VARIABLE.matcher(template.strip());
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = switch (matcher.group(1)) {
                case "date" -> start.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                case "time" -> start.format(DateTimeFormatter.ofPattern("HHmmss"));
                default -> safeName(report.pipelineName());
            };
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        try {
            return root.resolve(out.toString()).normalize();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException(message("archive.error.target-invalid"));
        }
    }

    /** A name that is one path element on every system: no separator, no trailing dot or space, no reserved name. */
    static String safeName(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        String safe = FORBIDDEN_IN_NAME.matcher(name).replaceAll("_");
        safe = safe.replaceAll("[. ]+$", "_");
        if (RESERVED_NAME.matcher(safe).matches()) {
            safe = "_" + safe;
        }
        return safe;
    }

    /** A localised message of the plugin; its key and arguments when the action has no plugin (built by hand). */
    private String message(String key, Object... args) {
        IPlugin plugin = getPlugin();
        CombinedResourceBundle bundle = plugin == null ? null : plugin.getResourceBundle();
        if (bundle == null) {
            return key + Arrays.toString(args);
        }
        return bundle.getString("plugin.archive." + key, args);
    }
}

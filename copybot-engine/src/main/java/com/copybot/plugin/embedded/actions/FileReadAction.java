package com.copybot.plugin.embedded.actions;

import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.AbstractActionWithConfig;
import com.copybot.plugin.api.action.IInAction;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import com.google.gson.JsonElement;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.PatternSyntaxException;

/** Lists the files under path (spec safe-write §7): recursive or not, include / exclude globs, hidden files skipped. */
public class FileReadAction extends AbstractActionWithConfig<FileReadConfig> implements IInAction {

    private volatile List<PathMatcher> includes = List.of();
    private volatile List<PathMatcher> excludes = List.of();
    /** The folders whose whole content an exclude "x/**" matches: not walked. */
    private volatile List<PathMatcher> excludedFolders = List.of();

    @Override
    protected Class<FileReadConfig> getConfigClass() {
        return FileReadConfig.class;
    }

    /** @throws CopybotException read.config.no-path (null config or missing path), read.config.invalid-glob */
    @Override
    public void loadConfig(JsonElement config) {
        super.loadConfig(config);
        if (getConfig() == null || getConfig().path() == null || getConfig().path().isBlank()) {
            throw CopybotException.ofResource("read.config.no-path");
        }
        includes = matchers(getConfig().include());
        excludes = matchers(getConfig().exclude());
        excludedFolders = folderMatchers(getConfig().exclude());
    }

    @Override
    public Set<Path> touchedPaths(WorkItem item) {
        return Set.of(Path.of(getConfig().path()));
    }

    @Override
    public void listFiles(Consumer<WorkItem> workItemConsumer) {
        FileReadConfig config = getConfig();
        Path root = Path.of(config.path());
        try {
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class),
                    config.isRecursive() ? Integer.MAX_VALUE : 1, visitor(root, config.isIncludeHidden(), workItemConsumer));
        } catch (IOException | UncheckedIOException e) {
            throw CopybotException.ofResource(e, "plugin.embedded.file.read.error.io", e.getLocalizedMessage());
        }
    }

    SimpleFileVisitor<Path> visitor(Path root, boolean includeHidden, Consumer<WorkItem> workItemConsumer) {
        return new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                // the listed directory itself is never filtered (a drive root is hidden and system on Windows)
                boolean skipped = !dir.equals(root)
                        && (!includeHidden && isHidden(dir, attrs) || excludedFolder(root.relativize(dir)));
                return skipped ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                // with recursive=false the sub-directories arrive here: not regular files
                if (attrs.isRegularFile() && (includeHidden || !isHidden(file, attrs))
                        && selected(root.relativize(file))) {
                    workItemConsumer.accept(workItemOf(file));
                }
                return FileVisitResult.CONTINUE;
            }

            /**
             * walkFileTree opens a directory before preVisitDirectory runs, so an unreadable hidden or system
             * folder (System Volume Information) fails here: skipped when hidden or excluded, rethrown otherwise.
             */
            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                if (!file.equals(root) && (excludedFolder(root.relativize(file))
                        || !includeHidden && isHidden(file, readAttributes(file)))) {
                    return FileVisitResult.CONTINUE;
                }
                return super.visitFileFailed(file, exc);
            }
        };
    }

    /** The attributes of a path that may be unreadable: null when they cannot be read. */
    private static BasicFileAttributes readAttributes(Path path) {
        try {
            return Files.readAttributes(path, DosFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | UnsupportedOperationException e) {
            // not a DOS file system, or unreadable: the name alone decides
            return null;
        }
    }

    /**
     * A leading dot (Unix, macOS "._" files), the Windows hidden or system attribute, the Windows
     * "System Volume Information" and "$RECYCLE.BIN" folders. attrs may be null.
     */
    static boolean isHidden(Path path, BasicFileAttributes attrs) {
        Path fileName = path.getFileName();
        if (fileName == null) {
            return false;
        }
        String name = fileName.toString();
        if (name.startsWith(".") || name.equalsIgnoreCase("System Volume Information") || name.equalsIgnoreCase("$RECYCLE.BIN")) {
            return true;
        }
        return attrs instanceof DosFileAttributes dos && (dos.isHidden() || dos.isSystem());
    }

    private boolean selected(Path relative) {
        Path lowerCase = lowerCase(relative);
        boolean included = includes.isEmpty() || includes.stream().anyMatch(m -> m.matches(lowerCase));
        return included && excludes.stream().noneMatch(m -> m.matches(lowerCase));
    }

    private boolean excludedFolder(Path relative) {
        Path lowerCase = lowerCase(relative);
        return excludedFolders.stream().anyMatch(m -> m.matches(lowerCase));
    }

    private static Path lowerCase(Path relative) {
        return Path.of(relative.toString().toLowerCase(Locale.ROOT));
    }

    /**
     * For each exclude "x/**", a matcher of x: every path under a folder matching x matches "x/**" (the glob
     * "**" matches anything), so the folder can be left unwalked without changing what is listed. An
     * exclude naming the folder alone ("x") matches no file under it: the folder is walked.
     */
    private static List<PathMatcher> folderMatchers(List<String> globs) {
        if (globs == null) {
            return List.of();
        }
        List<PathMatcher> folders = new ArrayList<>();
        for (String glob : globs) {
            if (glob.endsWith("/**") && glob.length() > 3) {
                try {
                    folders.addAll(matchers(List.of(glob.substring(0, glob.length() - 3))));
                } catch (CopybotException e) {
                    // a valid glob whose part before "/**" is not one (e.g. "x\/**"): no shortcut, walked
                }
            }
        }
        return List.copyOf(folders);
    }

    /**
     * Case-insensitive globs: patterns and paths are lower-cased. "**&#47;x" also matches x at the first
     * level ("**&#47;" alone requires a directory).
     */
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
                throw CopybotException.ofResource(e, "read.config.invalid-glob", glob);
            }
        }
        return List.copyOf(matchers);
    }

    /**
     * The item file.read lists for this file, with its base metadata (name, size, times): also used by the
     * pipeline editor's sample to test a file chosen by the user.
     *
     * @throws IOException the attributes of the file cannot be read (missing file...)
     */
    public static WorkItem workItemOf(Path file) throws IOException {
        WorkItem item = new WorkItem(file);
        extractMetadata(file, item.getMetadatas());
        return item;
    }

    private static void extractMetadata(Path path, WorkItemMetadata metadatas) throws IOException {
        // file metadatas
        metadatas.display().put("name", path.getFileName().toString());

        BasicFileAttributes attr = Files.readAttributes(path, BasicFileAttributes.class);
        metadatas.setSize(attr.size());
        metadatas.setTime("creation", attr.creationTime().toInstant());
        metadatas.setTime(WorkItemMetadata.LAST_MODIFIED, attr.lastModifiedTime().toInstant());
        metadatas.setTime("lastAccess", attr.lastAccessTime().toInstant());
    }
}

package com.copybot.engine.listing;

import com.copybot.exception.CopybotException;
import com.copybot.logger.CopybotLogger;
import com.copybot.plugin.api.action.WorkItem;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * The work folder of one run for the archives (spec grouping/archives, "Archives distantes ou dans une archive"):
 * {@code <base>/run-<id>/}, holding the local copies of the archives that are not local files and the archives
 * opened while unfolding. The folder holds a file lock ({@code run.lock}) while the run lives; at the opening, the
 * {@code run-*} folders that are not locked (left by a crash) are removed, whatever their age, so that a plan still
 * open in another instance is never touched. {@link #close()} closes the archives and removes the folder.
 */
public final class ArchiveWorkspace implements AutoCloseable {

    private static final CopybotLogger LOG = CopybotLogger.getLogger(ArchiveWorkspace.class);

    private static final String RUN_PREFIX = "run-";
    private static final String LOCK = "run.lock";

    /** The usable space of the disk of a folder: injectable for the tests. */
    @FunctionalInterface
    public interface FreeSpace {
        long usable(Path folder) throws IOException;
    }

    private final Path folder;
    private final FileChannel lockChannel;
    private final FreeSpace freeSpace;
    private final List<Closeable> opened = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong next = new AtomicLong();

    private ArchiveWorkspace(Path folder, FileChannel lockChannel, FreeSpace freeSpace) {
        this.folder = folder;
        this.lockChannel = lockChannel;
        this.freeSpace = freeSpace;
    }

    /** The default base: {@code java.io.tmpdir/copybot-archives}. */
    public static Path defaultBase() {
        return Path.of(System.getProperty("java.io.tmpdir"), "copybot-archives");
    }

    public static ArchiveWorkspace open(Path base) throws IOException {
        return open(base, f -> Files.getFileStore(f).getUsableSpace());
    }

    /** @param freeSpace the probe of the free space checked before each local copy */
    public static ArchiveWorkspace open(Path base, FreeSpace freeSpace) throws IOException {
        Path root = Files.createDirectories(base.toAbsolutePath());
        removeUnlockedRuns(root);
        Path folder = Files.createDirectory(root.resolve(RUN_PREFIX + UUID.randomUUID()));
        FileChannel channel = FileChannel.open(folder.resolve(LOCK), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        try {
            channel.lock();
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
        return new ArchiveWorkspace(folder, channel, freeSpace);
    }

    /** The run folder. */
    Path folder() {
        return folder;
    }

    /**
     * The local file of the archive: its own file when it is local, else a copy made in the run folder, once the
     * free space is checked (the size of the archive).
     *
     * @throws CopybotException archive.no-space, not enough free space for the copy
     * @throws IOException      the archive cannot be read or copied (no partial copy is left behind)
     */
    public Path localCopy(WorkItem archive) throws IOException {
        if (archive.getLocalLocation() != null) {
            return archive.getLocalLocation();
        }
        Long size = archive.getMetadatas().getSize();
        long needed = size == null ? 0 : size;
        long free = freeSpace.usable(folder);
        if (free < needed) {
            throw CopybotException.ofResource("archive.no-space", ArchiveSources.location(archive), needed, free);
        }
        String name = archive.getNameDisplay() == null ? "archive.zip" : archive.getNameDisplay();
        Path copy = folder.resolve(next.incrementAndGet() + "-" + name);
        try (InputStream in = archive.openInputStream()) {
            Files.copy(in, copy);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(copy);
            throw e;
        }
        return copy;
    }

    /** Keeps {@code resource} (an opened archive) open until {@link #close()}. */
    void keep(Closeable resource) {
        opened.add(resource);
    }

    /** Closes the archives kept open, releases the lock and removes the run folder. */
    @Override
    public void close() {
        synchronized (opened) {
            for (Closeable resource : opened) {
                try {
                    resource.close();
                } catch (IOException e) {
                    LOG.warn(e, "archive.workspace.cleanup-failed", folder, e.getLocalizedMessage());
                }
            }
            opened.clear();
        }
        try {
            lockChannel.close(); // releases the lock
        } catch (IOException e) {
            LOG.warn(e, "archive.workspace.cleanup-failed", folder, e.getLocalizedMessage());
        }
        deleteTree(folder);
    }

    private static void removeUnlockedRuns(Path root) {
        try (DirectoryStream<Path> runs = Files.newDirectoryStream(root, RUN_PREFIX + "*")) {
            for (Path run : runs) {
                if (Files.isDirectory(run) && isUnlocked(run)) {
                    deleteTree(run);
                }
            }
        } catch (IOException e) {
            LOG.warn(e, "archive.workspace.cleanup-failed", root, e.getLocalizedMessage());
        }
    }

    /**
     * True when the lock file of the run exists and nobody holds its lock. A run folder without its lock file is
     * left alone: it may be being created by another instance.
     */
    private static boolean isUnlocked(Path run) {
        Path lock = run.resolve(LOCK);
        if (!Files.exists(lock)) {
            return false;
        }
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.WRITE)) {
            FileLock held = channel.tryLock();
            if (held == null) {
                return false; // held by another process
            }
            held.release();
            return true;
        } catch (OverlappingFileLockException e) {
            return false; // held by another workspace of this process
        } catch (IOException e) {
            return false; // in doubt, kept
        }
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Collections.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            LOG.warn(e, "archive.workspace.cleanup-failed", root, e.getLocalizedMessage());
        }
    }
}

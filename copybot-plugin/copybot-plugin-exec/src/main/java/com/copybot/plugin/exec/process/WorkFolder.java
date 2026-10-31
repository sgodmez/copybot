package com.copybot.plugin.exec.process;

import com.copybot.plugin.api.action.WorkItem;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * The working files of one step (as file.buffer's copies): each run writes in its own folder "run-&lt;id&gt;" under
 * {@link #folder}, one sub-folder per item. The sub-folder of an item is deleted once the item is finished
 * ({@code afterItem}), the run folder after the run ({@code afterAll}), and the run folders an interrupted run left
 * more than a day ago at the start of the next one ({@code beforeAll}). Never the item's own file: only what this
 * step made.
 */
public final class WorkFolder {

    private static final System.Logger LOGGER = System.getLogger(WorkFolder.class.getName());

    private static final String RUN_PREFIX = "run-";
    private static final Duration STALE = Duration.ofDays(1);

    private final Path folder;
    private volatile Path runFolder;
    /** The sub-folder made for each item handed to the step, by identity: what {@link #release} deletes. */
    private final Map<WorkItem, Path> kept = Collections.synchronizedMap(new IdentityHashMap<>());
    private final AtomicLong next = new AtomicLong();

    /** @param folder where the run folders go, created when needed */
    public WorkFolder(Path folder) {
        this.folder = folder.toAbsolutePath();
    }

    /** The folder of the run folders, absolute. */
    public Path folder() {
        return folder;
    }

    /** A new, empty folder for one item, in the folder of the run (made on the first call). */
    public Path newItemFolder() throws IOException {
        return Files.createDirectory(runFolder().resolve(String.valueOf(next.incrementAndGet())));
    }

    /** Keeps the item's folder until {@link #release} for this item (the one handed to the step). */
    public void keep(WorkItem item, Path itemFolder) {
        kept.put(item, itemFolder);
    }

    /** Deletes the folder kept for the item, if any. */
    public void release(WorkItem item) {
        Path itemFolder = kept.remove(item);
        if (itemFolder != null) {
            deleteTree(itemFolder);
        }
    }

    /** Deletes the folder of the run and what is left in it (items left out of the run). */
    public void close() {
        Path run;
        synchronized (this) {
            run = runFolder;
            runFolder = null;
        }
        kept.clear();
        if (run != null) {
            deleteTree(run);
        }
    }

    /** Deletes the run folders left more than a day ago. */
    public void removeStaleRuns() {
        if (!Files.isDirectory(folder)) {
            return;
        }
        Instant limit = Instant.now().minus(STALE);
        try (DirectoryStream<Path> runs = Files.newDirectoryStream(folder, RUN_PREFIX + "*")) {
            for (Path run : runs) {
                if (Files.isDirectory(run) && Files.getLastModifiedTime(run).toInstant().isBefore(limit)) {
                    deleteTree(run);
                }
            }
        } catch (IOException e) {
            LOGGER.log(System.Logger.Level.WARNING, "Cleaning " + folder + " failed", e);
        }
    }

    private synchronized Path runFolder() throws IOException {
        if (runFolder == null) {
            runFolder = Files.createDirectories(folder.resolve(RUN_PREFIX + UUID.randomUUID()));
        }
        return runFolder;
    }

    /** Deletes a folder and its content; a failure is logged. */
    public static void deleteTree(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Collections.reverseOrder()).forEach(WorkFolder::delete);
        } catch (IOException e) {
            LOGGER.log(System.Logger.Level.WARNING, "Deleting " + root + " failed", e);
        }
    }

    private static void delete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOGGER.log(System.Logger.Level.WARNING, "Deleting " + path + " failed", e);
        }
    }
}

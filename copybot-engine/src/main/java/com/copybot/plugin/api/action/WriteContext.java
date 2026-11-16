package com.copybot.plugin.api.action;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * What the engine tells an out action about the current execution and item (spec safe-write §6).
 *
 * @param runId              unique per execution, usable as a file name fragment (no dot, no path separator): e.g. to
 *                           name temporary files and recognise the ones an earlier, crashed execution left behind
 * @param progress           where the write reports the progress of this item ({@link ItemProgress}); {@link ItemProgress#NONE}
 *                           outside the engine
 * @param createdDirectories receives each directory the write creates (spec post-process): the engine hands them to
 *                           the post-processing; ignored outside the engine
 * @param fileWritten        receives each file the write has written and verified, main or companion, with its
 *                           SHA-256 and size: the engine builds the run report of the end-of-run actions from them;
 *                           no effect outside the engine
 */
public record WriteContext(String runId, ItemProgress progress, Consumer<Path> createdDirectories,
                           FileWritten fileWritten) {

    /** The callback of {@link WriteContext#fileWritten()}. */
    @FunctionalInterface
    public interface FileWritten {
        void fileWritten(Path target, String sha256, long size);
    }

    public WriteContext {
        Objects.requireNonNull(runId, "runId");
        if (runId.isBlank() || runId.chars().anyMatch(c -> c == '.' || c == '/' || c == '\\')) {
            throw new IllegalArgumentException("invalid runId: " + runId);
        }
        progress = progress == null ? ItemProgress.NONE : progress;
        createdDirectories = createdDirectories == null ? dir -> {
        } : createdDirectories;
        fileWritten = fileWritten == null ? (target, sha256, size) -> {
        } : fileWritten;
    }

    /** A context nobody tells the written files. */
    public WriteContext(String runId, ItemProgress progress, Consumer<Path> createdDirectories) {
        this(runId, progress, createdDirectories, null);
    }

    /** A context nobody tells the created directories. */
    public WriteContext(String runId, ItemProgress progress) {
        this(runId, progress, null);
    }

    /** A context without progress (reported into the void). */
    public WriteContext(String runId) {
        this(runId, ItemProgress.NONE);
    }

    /** A context with a new random run id. */
    public static WriteContext newRun() {
        return newRun(null);
    }

    /** A context with a new random run id, telling createdDirectories the directories the writes create. */
    public static WriteContext newRun(Consumer<Path> createdDirectories) {
        return new WriteContext(UUID.randomUUID().toString(), null, createdDirectories);
    }

    /** The same run, reporting the progress of one item there. */
    public WriteContext withProgress(ItemProgress itemProgress) {
        return new WriteContext(runId, itemProgress, createdDirectories, fileWritten);
    }

    /** Tells the run that the write created this directory (it did not exist before). */
    public void directoryCreated(Path dir) {
        createdDirectories.accept(dir);
    }

    /** Tells the run that the write has written and verified this file (main or companion). */
    public void fileWritten(Path target, String sha256, long size) {
        fileWritten.fileWritten(target, sha256, size);
    }
}

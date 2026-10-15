package com.copybot.plugin.api.action;

import java.util.Objects;
import java.util.UUID;

/**
 * What the engine tells an out action about the current execution and item (spec safe-write §6).
 *
 * @param runId    unique per execution, usable as a file name fragment (no dot, no path separator): e.g. to
 *                 name temporary files and recognise the ones an earlier, crashed execution left behind
 * @param progress where the write reports the progress of this item ({@link ItemProgress}); {@link ItemProgress#NONE}
 *                 outside the engine
 */
public record WriteContext(String runId, ItemProgress progress) {

    public WriteContext {
        Objects.requireNonNull(runId, "runId");
        if (runId.isBlank() || runId.chars().anyMatch(c -> c == '.' || c == '/' || c == '\\')) {
            throw new IllegalArgumentException("invalid runId: " + runId);
        }
        progress = progress == null ? ItemProgress.NONE : progress;
    }

    /** A context without progress (reported into the void). */
    public WriteContext(String runId) {
        this(runId, ItemProgress.NONE);
    }

    /** A context with a new random run id. */
    public static WriteContext newRun() {
        return new WriteContext(UUID.randomUUID().toString());
    }

    /** The same run, reporting the progress of one item there. */
    public WriteContext withProgress(ItemProgress itemProgress) {
        return new WriteContext(runId, itemProgress);
    }
}

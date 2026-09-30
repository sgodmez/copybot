package com.copybot.plugin.api.action;

import java.util.Objects;
import java.util.UUID;

/**
 * What the engine tells an out action about the current execution (spec safe-write §6).
 *
 * @param runId unique per execution, usable as a file name fragment (no dot, no path separator): e.g. to
 *              name temporary files and recognise the ones an earlier, crashed execution left behind
 */
public record WriteContext(String runId) {

    public WriteContext {
        Objects.requireNonNull(runId, "runId");
        if (runId.isBlank() || runId.chars().anyMatch(c -> c == '.' || c == '/' || c == '\\')) {
            throw new IllegalArgumentException("invalid runId: " + runId);
        }
    }

    /** A context with a new random run id. */
    public static WriteContext newRun() {
        return new WriteContext(UUID.randomUUID().toString());
    }
}

package com.copybot.plugin.api.action;

import java.nio.file.Path;
import java.util.Objects;

/**
 * What {@link IOutAction#write} did with one item (spec safe-write §6).
 *
 * @param target where the item was written, or the existing file that made it skipped; null when the
 *               action cannot tell
 * @param reason why the item was skipped, shown to the user; null when it was written
 */
public record WriteResult(Outcome outcome, Path target, String reason) {

    public enum Outcome {
        /** The item was written: it ends DONE. */
        WRITTEN,
        /** Nothing was written (e.g. already at the destination): the item ends SKIPPED with the reason. */
        SKIPPED
    }

    public WriteResult {
        Objects.requireNonNull(outcome, "outcome");
        if (outcome == Outcome.SKIPPED && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("a skipped item needs a reason");
        }
    }

    public static WriteResult written(Path target) {
        return new WriteResult(Outcome.WRITTEN, target, null);
    }

    public static WriteResult skipped(Path target, String reason) {
        return new WriteResult(Outcome.SKIPPED, target, reason);
    }

    public boolean isSkipped() {
        return outcome == Outcome.SKIPPED;
    }
}

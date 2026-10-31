package com.copybot.plugin.api.action;

import java.nio.file.Path;

/**
 * One item processed in a run.
 *
 * @param name    the item's name
 * @param source  where it came from (as the input shows it)
 * @param outcome how it ended
 * @param target  where it was written, or the existing target that made the output skip it; null for the other
 *                outcomes (or when the output cannot tell)
 * @param reason  why it was skipped, or the error message; null otherwise
 */
public record RunItem(String name, String source, ItemOutcome outcome, Path target, String reason) {
}

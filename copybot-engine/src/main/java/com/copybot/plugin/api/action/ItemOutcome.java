package com.copybot.plugin.api.action;

/** How an item processed in a run ended. */
public enum ItemOutcome {
    /** written to its target */
    WRITTEN,
    /** skipped by the output (e.g. already at the destination) */
    SKIPPED,
    /** failed */
    ERROR,
    /** filtered out by a processing: done without any write */
    FILTERED
}

package com.copybot.plugin.api.action;

/** The number of items of a run per outcome. */
public record RunCounts(int written, int skipped, int errors, int filtered) {
}

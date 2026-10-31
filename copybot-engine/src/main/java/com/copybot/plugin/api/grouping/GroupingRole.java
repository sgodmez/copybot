package com.copybot.plugin.api.grouping;

/** What a grouping rule does with a companion file: the {@code role} of a rule written in a pipeline. */
public enum GroupingRole {
    /** Copied to the destination next to its main file (e.g. an XMP sidecar). */
    COPY,
    /** Only read, to feed the steps (e.g. the JSON of a photo); never copied. */
    READ,
    /** Claimed by its main file and left out of the run: neither copied nor read, nor handled on its own. */
    IGNORE
}

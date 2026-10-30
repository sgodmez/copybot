package com.copybot.plugin.api.action;

/** What the pipeline does with a companion file of a grouped item. */
public enum CompanionRole {
    /** Copied to the destination next to the main item (e.g. an XMP sidecar). */
    COPY,
    /** Only read, to feed the steps (e.g. the audio of a video analysis); never copied. */
    READ
}

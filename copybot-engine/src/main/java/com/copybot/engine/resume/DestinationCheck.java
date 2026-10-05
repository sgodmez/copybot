package com.copybot.engine.resume;

import com.google.gson.annotations.SerializedName;

/**
 * How the destination-based resume modes go through the destination (the {@code "destinationCheck"} of the resume
 * block, spec execution-mode §2).
 */
public enum DestinationCheck {
    /**
     * the destination is assumed filled up to a point, found by a dichotomy: only the files it probes are analysed
     * (the default)
     */
    @SerializedName("dichotomy") DICHOTOMY("dichotomy"),
    /** every file is analysed and skipped when its own target is at the destination (a missing day is imported) */
    @SerializedName("everyFile") EVERY_FILE("everyFile");

    private final String jsonName;

    DestinationCheck(String jsonName) {
        this.jsonName = jsonName;
    }

    /** The name in the pipeline file. */
    public String jsonName() {
        return jsonName;
    }
}

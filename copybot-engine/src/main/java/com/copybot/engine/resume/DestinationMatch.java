package com.copybot.engine.resume;

import com.google.gson.annotations.SerializedName;

/**
 * What counts as imported for the destination-based resume modes (the {@code "destinationMatch"} of the resume
 * block, spec execution-mode §2).
 */
public enum DestinationMatch {
    /** the target directory exists: deleting files inside it does not import them again (the default) */
    @SerializedName("directory") DIRECTORY("directory"),
    /** the target file exists */
    @SerializedName("file") FILE("file");

    private final String jsonName;

    DestinationMatch(String jsonName) {
        this.jsonName = jsonName;
    }

    /** The name in the pipeline file. */
    public String jsonName() {
        return jsonName;
    }
}

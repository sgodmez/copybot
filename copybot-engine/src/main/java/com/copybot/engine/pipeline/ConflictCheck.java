package com.copybot.engine.pipeline;

import com.google.gson.annotations.SerializedName;

/** How far the plan checks the targets that already exist (the {@code "conflictCheck"} of a pipeline, spec conflict-check §1). */
public enum ConflictCheck {
    /** no check: the conflicts are only met at the copy */
    @SerializedName("none") NONE("none"),
    /** existence and size of each target, one access, no content read (the default) */
    @SerializedName("quick") QUICK("quick"),
    /** the out step's own comparison: the plan tells what the copy will do */
    @SerializedName("full") FULL("full");

    private final String jsonName;

    ConflictCheck(String jsonName) {
        this.jsonName = jsonName;
    }

    /** The name in the pipeline file. */
    public String jsonName() {
        return jsonName;
    }
}

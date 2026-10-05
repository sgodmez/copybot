package com.copybot.engine.pipeline;

import com.google.gson.annotations.SerializedName;

/** How a pipeline is run (the {@code "execution"} of its file, spec execution-mode §1). */
public enum ExecutionMode {
    /** the plan is prepared, then the user confirms the copy (the default) */
    @SerializedName("plan") PLAN("plan"),
    /** the plan is prepared, then copied at once: one step */
    @SerializedName("auto") AUTO("auto"),
    /**
     * each file is processed as soon as it is listed: one phase, for slow sources (e.g. a crawl). Prepared then
     * copied instead when the resume point is only known after the listing (destination probe)
     */
    @SerializedName("streaming") STREAMING("streaming");

    private final String jsonName;

    ExecutionMode(String jsonName) {
        this.jsonName = jsonName;
    }

    /** The name in the pipeline file. */
    public String jsonName() {
        return jsonName;
    }
}

package com.copybot.engine.resume;

import com.google.gson.annotations.SerializedName;

/** How a pipeline finds where its previous runs stopped (the {@code "mode"} of its resume block). */
public enum ResumeMode {
    /** no resume: every listed file is processed, nothing is persisted */
    @SerializedName("none") NONE,
    /** after the cursor of the state file next to the pipeline; everything without one */
    @SerializedName("state") STATE,
    /** after the last item whose target directory exists; requires an out step able to resolve target paths */
    @SerializedName("destination") DESTINATION,
    /** the state file cursor when there is one, the destination otherwise (default of a resume block) */
    @SerializedName("stateThenDestination") STATE_THEN_DESTINATION
}

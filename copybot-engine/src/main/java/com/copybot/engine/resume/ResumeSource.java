package com.copybot.engine.resume;

/** Where a resume point comes from. */
public enum ResumeSource {
    /** nothing detected: everything is selected */
    NONE,
    /** the cursor of the state file */
    STATE,
    /** the last item whose target directory exists */
    DESTINATION,
    /** chosen by the user */
    MANUAL
}

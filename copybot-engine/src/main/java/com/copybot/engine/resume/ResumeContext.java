package com.copybot.engine.resume;

/**
 * What the executor needs to resume a pipeline.
 *
 * @param check the destination check, the dichotomy when null
 * @param match what counts as imported at the destination, the target directory when null
 */
public record ResumeContext(ResumeMode mode, ResumeStateStore store, DestinationCheck check, DestinationMatch match) {

    public ResumeContext {
        check = check == null ? DestinationCheck.DICHOTOMY : check;
        match = match == null ? DestinationMatch.DIRECTORY : match;
    }

    /** The dichotomy on the target directories. */
    public ResumeContext(ResumeMode mode, ResumeStateStore store) {
        this(mode, store, null, null);
    }
}

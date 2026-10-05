package com.copybot.engine.resume;

/**
 * The "resume" block of a pipeline.
 *
 * @param destinationCheck how the destination-based modes go through the destination, null for the dichotomy
 * @param destinationMatch what counts as imported for them, null for the target directory
 */
public record ResumeConfig(ResumeMode mode, DestinationCheck destinationCheck, DestinationMatch destinationMatch) {

    /** A resume block without mode opts in the recommended default. */
    public ResumeMode effectiveMode() {
        return mode == null ? ResumeMode.STATE_THEN_DESTINATION : mode;
    }

    public DestinationCheck effectiveDestinationCheck() {
        return destinationCheck == null ? DestinationCheck.DICHOTOMY : destinationCheck;
    }

    public DestinationMatch effectiveDestinationMatch() {
        return destinationMatch == null ? DestinationMatch.DIRECTORY : destinationMatch;
    }
}

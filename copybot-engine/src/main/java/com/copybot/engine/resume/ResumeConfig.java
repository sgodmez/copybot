package com.copybot.engine.resume;

/** The "resume" block of a pipeline. */
public record ResumeConfig(ResumeMode mode) {

    /** A resume block without mode opts in the recommended default. */
    public ResumeMode effectiveMode() {
        return mode == null ? ResumeMode.STATE_THEN_DESTINATION : mode;
    }
}

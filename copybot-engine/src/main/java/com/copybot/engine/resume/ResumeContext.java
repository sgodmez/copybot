package com.copybot.engine.resume;

/** What the executor needs to resume a pipeline. */
public record ResumeContext(ResumeMode mode, ResumeStateStore store) {
}

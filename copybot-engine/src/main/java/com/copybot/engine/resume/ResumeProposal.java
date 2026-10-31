package com.copybot.engine.resume;

import java.util.List;

/** The resume point the engine proposes after preparation, with its origin and the warnings to show. */
public record ResumeProposal(ResumePoint point, ResumeSource source, List<String> warnings) {
    public ResumeProposal {
        warnings = List.copyOf(warnings);
    }
}

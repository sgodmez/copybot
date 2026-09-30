package com.copybot.engine.pipeline;

import com.copybot.engine.resume.ResumeConfig;
import com.copybot.engine.resume.ResumeMode;
import java.util.List;

public record PipelineConfig(

        List<PipelineStepConfig> inSteps,
        List<PipelineStepConfig> analyseSteps,
        List<PipelineStepConfig> actionSteps,
        PipelineStepConfig outStep,

        /**
         * true: items are processed while listing is still running (pipelining).
         * false or absent: all listings complete before any processing starts (two phases).
         */
        Boolean startProcessingWhileListing,

        /**
         * Resume detection. Absent: no resume (every listed file is processed, no state file).
         */
        ResumeConfig resume

        ) {

    public ResumeMode resumeMode() {
        return resume == null ? ResumeMode.NONE : resume.effectiveMode();
    }
}

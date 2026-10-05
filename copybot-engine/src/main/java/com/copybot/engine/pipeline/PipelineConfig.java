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
         * How the pipeline is run (spec execution-mode §1): plan then confirmation, plan then copy, or each file
         * processed as soon as it is listed. Absent: plan.
         */
        ExecutionMode execution,

        /**
         * Resume detection. Absent: no resume (every listed file is processed, no state file).
         */
        ResumeConfig resume

        ) {

    public ResumeMode resumeMode() {
        return resume == null ? ResumeMode.NONE : resume.effectiveMode();
    }

    public ExecutionMode executionMode() {
        return execution == null ? ExecutionMode.PLAN : execution;
    }
}

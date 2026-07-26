package com.copybot.engine.pipeline;

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
        Boolean startProcessingWhileListing

        ) {
}

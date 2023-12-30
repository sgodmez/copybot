package com.copybot.engine.pipeline;

import java.util.List;

public record PipelineConfig(

        List<PipelineStepConfig> inSteps,
        List<PipelineStepConfig> analyseSteps,
        List<PipelineStepConfig> actionSteps,
        PipelineStepConfig outStep

        ) {
}

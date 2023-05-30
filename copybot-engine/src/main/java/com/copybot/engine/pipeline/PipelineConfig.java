package com.copybot.engine.pipeline;

import java.util.List;

public record PipelineConfig(

        List<PipelineStep> inSteps,
        List<PipelineStep> analyseSteps,
        List<PipelineStep> actionSteps,
        List<PipelineStep> outSteps

        ) {
}

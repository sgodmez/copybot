package com.copybot.engine.pipeline;

public record PipelineStepState(
        PipelineStepConfig step,
        String status,

        int currentItem,
        int totalItems
) {
}

package com.copybot.engine.pipeline;

import com.copybot.exception.CopybotException;
import com.copybot.resources.ResourcesEngine;

import java.util.List;
import java.util.Optional;

/**
 * What a pipeline needs to be prepared (an input step: nothing to list otherwise) and to be executed
 * (an input step, and an output or a process step: analyse steps never do anything with the files).
 * A pipeline without output step but with a process step is valid: the process steps act themselves.
 * Messages are localized.
 */
public final class PipelineChecks {

    private PipelineChecks() {
    }

    /** The reason when the pipeline has no input step (absent or empty), empty otherwise. */
    public static Optional<String> missingInput(PipelineConfig config) {
        return isEmpty(config.inSteps())
                ? Optional.of(ResourcesEngine.getString("pipeline.no-input"))
                : Optional.empty();
    }

    /** The reason when the pipeline has neither output step nor process step, empty otherwise. */
    public static Optional<String> doesNothing(PipelineConfig config) {
        return config.outStep() == null && isEmpty(config.actionSteps())
                ? Optional.of(ResourcesEngine.getString("pipeline.does-nothing"))
                : Optional.empty();
    }

    /** The first reason why the pipeline cannot be executed (no input, then does nothing), empty when it can. */
    public static Optional<String> executionRefusal(PipelineConfig config) {
        return missingInput(config).or(() -> doesNothing(config));
    }

    /** @throws CopybotException pipeline.no-input when the pipeline has no input step */
    public static void requirePreparable(PipelineConfig config) {
        missingInput(config).ifPresent(message -> {
            throw CopybotException.of(message);
        });
    }

    /** @throws CopybotException pipeline.no-input or pipeline.does-nothing */
    public static void requireExecutable(PipelineConfig config) {
        executionRefusal(config).ifPresent(message -> {
            throw CopybotException.of(message);
        });
    }

    private static boolean isEmpty(List<?> steps) {
        return steps == null || steps.isEmpty();
    }
}

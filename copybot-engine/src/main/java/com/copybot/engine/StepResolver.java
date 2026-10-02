package com.copybot.engine;

import com.copybot.engine.pipeline.PipelineConfig;
import com.copybot.engine.pipeline.PipelineStep;
import com.copybot.engine.pipeline.PipelineStepConfig;
import com.copybot.engine.plugin.PluginEngine;
import com.copybot.plugin.api.action.IAction;
import com.copybot.plugin.api.action.IAnalyzeAction;
import com.copybot.plugin.api.action.IInAction;
import com.copybot.plugin.api.action.IOutAction;
import com.copybot.plugin.api.action.IProcessAction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Resolves the steps of a pipeline to configured action instances (a new instance per call, through
 * {@link PluginEngine#resolve}). Shared by the executor and the sampler, so that both instantiate the plugins
 * the same way.
 *
 * @throws RuntimeException from every method: a plugin not found, an action not found, an invalid configuration
 */
public final class StepResolver {

    private StepResolver() {
    }

    public static List<PipelineStep<IInAction>> in(PipelineConfig config) {
        return resolve(config.inSteps(), IInAction.class);
    }

    public static List<PipelineStep<IAnalyzeAction>> analyse(PipelineConfig config) {
        return resolve(config.analyseSteps(), IAnalyzeAction.class);
    }

    public static List<PipelineStep<IProcessAction>> process(PipelineConfig config) {
        return resolve(config.actionSteps(), IProcessAction.class);
    }

    /** The steps every item goes through: analyses, processes, then the out step if any. */
    public static List<PipelineStep<?>> itemSteps(PipelineConfig config) {
        List<PipelineStep<?>> steps = new ArrayList<>();
        steps.addAll(analyse(config));
        steps.addAll(process(config));
        if (config.outStep() != null) {
            steps.add(PluginEngine.resolve(config.outStep(), IOutAction.class));
        }
        return Collections.unmodifiableList(steps);
    }

    private static <A extends IAction> List<PipelineStep<A>> resolve(List<PipelineStepConfig> stepConfigs, Class<A> actionClass) {
        if (stepConfigs == null || stepConfigs.isEmpty()) {
            return List.of();
        }
        List<PipelineStep<A>> steps = new ArrayList<>(stepConfigs.size());
        for (PipelineStepConfig stepConfig : stepConfigs) {
            steps.add(PluginEngine.resolve(stepConfig, actionClass));
        }
        return Collections.unmodifiableList(steps);
    }
}

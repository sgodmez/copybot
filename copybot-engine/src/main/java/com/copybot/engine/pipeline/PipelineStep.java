package com.copybot.engine.pipeline;

import com.copybot.plugin.api.action.IAction;
import com.copybot.plugin.api.definition.IPlugin;

public class PipelineStep<A extends IAction> {
    private final IPlugin plugin;
    private final A action;
    private final PipelineStepConfig config;

    public PipelineStep(IPlugin plugin, A action, PipelineStepConfig config) {
        this.plugin = plugin;
        this.action = action;
        this.config = config;
    }

    public IPlugin getPlugin() {
        return plugin;
    }

    public A getAction() {
        return action;
    }

    public PipelineStepConfig getConfig() {
        return config;
    }
}

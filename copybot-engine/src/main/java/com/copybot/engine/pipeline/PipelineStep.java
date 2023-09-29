package com.copybot.engine.pipeline;

import com.copybot.plugin.api.action.IAction;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.definition.IPlugin;

import java.util.concurrent.ConcurrentLinkedQueue;

public class PipelineStep<A extends IAction> {
    private final IPlugin plugin;
    private final A action;
    private PipelineStepConfig config;

    private final ConcurrentLinkedQueue<WorkItem> queue;

    public PipelineStep(IPlugin plugin, A action, PipelineStepConfig config) {
        this.plugin = plugin;
        this.action = action;
        this.config = config;

        this.queue = new ConcurrentLinkedQueue<>();
    }

    public IPlugin getPlugin() {
        return plugin;
    }

    public A getAction() {
        return action;
    }

}

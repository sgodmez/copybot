package com.copybot.engine.pipeline;

import com.copybot.config.SecretReferences;
import com.copybot.plugin.api.action.IAction;
import com.copybot.plugin.api.definition.IPlugin;

public class PipelineStep<A extends IAction> {
    private final IPlugin plugin;
    private final A action;
    private final PipelineStepConfig config;
    private final SecretReferences.Redactor redactor;

    public PipelineStep(IPlugin plugin, A action, PipelineStepConfig config) {
        this(plugin, action, config, SecretReferences.Redactor.NONE);
    }

    public PipelineStep(IPlugin plugin, A action, PipelineStepConfig config, SecretReferences.Redactor redactor) {
        this.plugin = plugin;
        this.action = action;
        this.config = config;
        this.redactor = redactor;
    }

    public IPlugin getPlugin() {
        return plugin;
    }

    public A getAction() {
        return action;
    }

    /** The configuration of the pipeline: its secret references, never their values. */
    public PipelineStepConfig getConfig() {
        return config;
    }

    /** Hides the values the action was configured with in what it says (errors, warnings). */
    public SecretReferences.Redactor getRedactor() {
        return redactor;
    }
}

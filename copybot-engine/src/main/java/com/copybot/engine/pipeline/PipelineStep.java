package com.copybot.engine.pipeline;

import com.copybot.plugin.action.IAction;
import com.copybot.plugin.definition.IPlugin;

public record PipelineStep <A extends IAction> (
        IPlugin plugin,
        A action
) {
}

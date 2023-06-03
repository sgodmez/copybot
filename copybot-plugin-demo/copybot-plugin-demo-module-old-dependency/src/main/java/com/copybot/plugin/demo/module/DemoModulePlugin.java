package com.copybot.plugin.demo.module;

import com.copybot.plugin.action.ActionDefinition;
import com.copybot.plugin.action.IAnalyzeAction;
import com.copybot.plugin.definition.AbstractPlugin;
import com.copybot.plugin.demo.module.actions.DemoModuleAction;

import java.util.List;

public class DemoModulePlugin extends AbstractPlugin {

    @Override
    public String getPluginCode() {
        return "demo-module";
    }

    @Override
    public List<ActionDefinition<? extends IAnalyzeAction>> getAnalyzeActions() {
        return List.of(
                new ActionDefinition("demo", DemoModuleAction.class, true)
        );
    }
}
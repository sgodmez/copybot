package com.copybot.plugin.demo.module2;

import com.copybot.plugin.api.action.ActionDefinition;
import com.copybot.plugin.api.action.IAnalyzeAction;
import com.copybot.plugin.api.definition.AbstractPlugin;
import com.copybot.plugin.demo.module2.actions.DemoModule2Action;

import java.util.List;

public class DemoModule2Plugin extends AbstractPlugin {

    @Override
    public String getPluginCode() {
        return "demo-module2";
    }

    @Override
    public List<ActionDefinition<? extends IAnalyzeAction>> getAnalyzeActions() {
        return List.of(
                new ActionDefinition("demo", DemoModule2Action.class, true)
        );
    }
}
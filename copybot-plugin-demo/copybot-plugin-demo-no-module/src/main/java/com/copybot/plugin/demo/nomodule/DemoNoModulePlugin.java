package com.copybot.plugin.demo.nomodule;

import com.copybot.plugin.api.action.ActionDefinition;
import com.copybot.plugin.api.action.IAnalyzeAction;
import com.copybot.plugin.api.definition.AbstractPlugin;
import com.copybot.plugin.api.definition.IPlugin;
import com.copybot.plugin.demo.nomodule.actions.DemoNoModuleAction;
import com.google.auto.service.AutoService;

import java.util.List;

@AutoService(IPlugin.class)
public class DemoNoModulePlugin extends AbstractPlugin {

    @Override
    public String getPluginCode() {
        return "Demo-no-module";
    }

    @Override
    public List<ActionDefinition<? extends IAnalyzeAction>> getAnalyzeActions() {
        return List.of(
                new ActionDefinition("demo", DemoNoModuleAction.class, true)
        );
    }
}
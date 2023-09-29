package com.copybot.plugin.demo.inheritance;

import com.copybot.plugin.api.action.ActionDefinition;
import com.copybot.plugin.api.action.IAnalyzeAction;
import com.copybot.plugin.api.definition.AbstractPlugin;
import com.copybot.plugin.demo.inheritance.actions.DemoInheritanceAction;

import java.util.List;

public class DemoInheritancePlugin extends AbstractPlugin {

    @Override
    public String getPluginCode() {
        return "demo-inheritance";
    }
/*
    @Override
    public void doManyThings(File file) {
        DemoTransformModuleModule.extract();
        DemoTransformModuleModuleOlder.extract();
        System.out.println("Resolving metadata with : " + Metadata.class.getProtectionDomain().getCodeSource().getLocation());
    }
 */

    @Override
    public List<ActionDefinition<? extends IAnalyzeAction>> getAnalyzeActions() {
        return List.of(
                new ActionDefinition("demo", DemoInheritanceAction.class, true)
        );
    }

}
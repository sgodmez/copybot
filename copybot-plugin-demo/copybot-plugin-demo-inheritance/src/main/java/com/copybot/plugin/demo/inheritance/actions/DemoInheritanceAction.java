package com.copybot.plugin.demo.inheritance.actions;

import com.copybot.plugin.api.action.AbstractAction;
import com.copybot.plugin.api.action.IAnalyzeAction;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.demo.module.actions.DemoModuleAction;
import com.copybot.plugin.demo.module2.actions.DemoModule2Action;
import com.drew.metadata.Metadata;

public class DemoInheritanceAction extends AbstractAction implements IAnalyzeAction {

    @Override
    public void doAnalyze(WorkItem item) {
        System.out.println("Plugin module : " + DemoModuleAction.getClassUsed());
        System.out.println("Plugin no-module : " +  DemoModule2Action.getClassUsed());
        // order in module-info matters to resolve Metadata from parent dependencies
        System.out.println("Myself : resolving metadata with : " + Metadata.class.getProtectionDomain().getCodeSource().getLocation());
    }
}

package com.copybot.plugin.demo.inheritance.actions;

import com.copybot.plugin.api.action.AbstractAction;
import com.copybot.plugin.api.action.IAnalyzeAction;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.demo.module.actions.DemoModuleAction;
import com.copybot.plugin.demo.module2.actions.DemoModule2Action;
import com.copybot.plugin.demo.lib.DemoLib;

public class DemoInheritanceAction extends AbstractAction implements IAnalyzeAction {

    @Override
    public void doAnalyze(WorkItem item) {
        System.out.println("Plugin module : " + DemoModuleAction.getClassUsed());
        System.out.println("Plugin module-2 : " +  DemoModule2Action.getClassUsed());
        // the library comes from a parent plugin (order in module-info matters)
        System.out.println("Myself : " + DemoLib.describe());
    }
}

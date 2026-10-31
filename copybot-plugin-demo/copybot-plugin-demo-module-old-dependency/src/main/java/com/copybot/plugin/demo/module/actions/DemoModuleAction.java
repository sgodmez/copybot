package com.copybot.plugin.demo.module.actions;

import com.copybot.plugin.api.action.AbstractAction;
import com.copybot.plugin.api.action.IAnalyzeAction;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.demo.lib.DemoLib;

public class DemoModuleAction extends AbstractAction implements IAnalyzeAction {

    public static String getClassUsed() {
        return DemoLib.legacyDescribe();
    }

    @Override
    public void doAnalyze(WorkItem item) {
        System.out.println(getClassUsed());
    }
}

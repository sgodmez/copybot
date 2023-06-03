package com.copybot.plugin.demo.nomodule.actions;

import com.copybot.plugin.action.AbstractAction;
import com.copybot.plugin.action.IAnalyzeAction;
import com.copybot.plugin.action.WorkItem;
import com.drew.metadata.Metadata;

public class DemoNoModuleAction extends AbstractAction implements IAnalyzeAction {

    public static String getClassUsed() {
        return "Resolving metadata with : " + Metadata.class.getProtectionDomain().getCodeSource().getLocation();
    }

    @Override
    public void doAnalyze(WorkItem item) {
        System.out.println(getClassUsed());
    }
}

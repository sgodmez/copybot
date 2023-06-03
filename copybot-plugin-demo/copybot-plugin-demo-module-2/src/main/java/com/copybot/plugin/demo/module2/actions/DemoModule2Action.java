package com.copybot.plugin.demo.module2.actions;

import com.copybot.plugin.action.AbstractAction;
import com.copybot.plugin.action.IAnalyzeAction;
import com.copybot.plugin.action.WorkItem;
import com.drew.metadata.Metadata;

public class DemoModule2Action extends AbstractAction implements IAnalyzeAction {

    public static String getClassUsed() {
        return "Resolving metadata with : " + Metadata.class.getProtectionDomain().getCodeSource().getLocation();
    }

    @Override
    public void doAnalyze(WorkItem item) {
        System.out.println(getClassUsed());
    }
}

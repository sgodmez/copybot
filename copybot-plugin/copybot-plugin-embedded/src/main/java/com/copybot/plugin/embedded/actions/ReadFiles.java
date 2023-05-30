package com.copybot.plugin.embedded.actions;

import com.copybot.plugin.action.AbstractActionWithConfig;
import com.copybot.plugin.action.IInAction;
import com.copybot.plugin.action.WorkItem;

import java.util.List;

public class ReadFiles extends AbstractActionWithConfig<ReadFileConfig> implements IInAction {

    @Override
    protected Class<ReadFileConfig> getConfigClass() {
        return ReadFileConfig.class;
    }


    @Override
    public List<WorkItem> listFiles() {
        String path = getConfig().path();
        return null;
    }
}

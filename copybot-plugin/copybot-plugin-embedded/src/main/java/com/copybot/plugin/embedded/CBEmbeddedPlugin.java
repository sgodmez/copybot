package com.copybot.plugin.embedded;

import com.copybot.plugin.action.IInAction;
import com.copybot.plugin.definition.IPlugin;
import com.copybot.plugin.embedded.actions.ReadFiles;

import java.io.File;
import java.util.List;

public class CBEmbeddedPlugin implements IPlugin {

    @Override
    public String getName() {
        return "dummy";
    }

    @Override
    public void doManyThings(File file) {

    }

    @Override
    public List<Class<? extends IInAction>> getInActions() {
        return List.of(ReadFiles.class);
    }
}
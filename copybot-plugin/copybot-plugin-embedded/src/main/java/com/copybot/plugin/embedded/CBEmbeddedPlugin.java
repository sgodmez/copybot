package com.copybot.plugin.embedded;

import com.copybot.plugin.action.ActionDefinition;
import com.copybot.plugin.action.IInAction;
import com.copybot.plugin.definition.AbstractPlugin;
import com.copybot.plugin.embedded.actions.ReadFiles;

import java.util.List;

public class CBEmbeddedPlugin extends AbstractPlugin {

    public static final String EMBEDDED_PLUGN_NAME = "embedded";

    @Override
    public String getPluginCode() {
        return EMBEDDED_PLUGN_NAME;
    }

    @Override
    public List<ActionDefinition<? extends IInAction>> getInActions() {
        return List.of(
                new ActionDefinition("read.file", ReadFiles.class, false)
        );
    }
}
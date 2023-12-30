package com.copybot.plugin.embedded;

import com.copybot.plugin.api.action.ActionDefinition;
import com.copybot.plugin.api.action.IInAction;
import com.copybot.plugin.api.definition.AbstractPlugin;
import com.copybot.plugin.embedded.actions.FileReadAction;
import com.copybot.plugin.embedded.actions.FileWriteAction;

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
                new ActionDefinition("file.read", FileReadAction.class, false),
                new ActionDefinition("file.write", FileWriteAction.class, false)
        );
    }
}
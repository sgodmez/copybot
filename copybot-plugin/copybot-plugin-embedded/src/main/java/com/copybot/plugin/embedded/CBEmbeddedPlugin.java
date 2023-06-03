package com.copybot.plugin.embedded;

import com.copybot.plugin.action.ActionDefinition;
import com.copybot.plugin.action.IInAction;
import com.copybot.plugin.definition.AbstractPlugin;
import com.copybot.plugin.embedded.actions.ReadFiles;

import java.util.List;

public class CBEmbeddedPlugin extends AbstractPlugin {

    @Override
    public String getPluginCode() {
        return "embedded";
    }

    @Override
    public List<ActionDefinition<? extends IInAction>> getInActions() {
        return List.of(
                new ActionDefinition("read-file", ReadFiles.class, false)
        );
    }
}
package com.copybot.plugin.archive;

import com.copybot.plugin.api.action.ActionDefinition;
import com.copybot.plugin.api.action.IRunEndAction;
import com.copybot.plugin.api.definition.AbstractPlugin;
import com.copybot.plugin.archive.actions.ArchiveZip;

import java.util.List;

public class ArchivePlugin extends AbstractPlugin {

    @Override
    public String getPluginCode() {
        return "archive";
    }

    @Override
    public List<ActionDefinition<? extends IRunEndAction>> getRunEndActions() {
        return List.of(
                new ActionDefinition<>("archive.zip", ArchiveZip.class)
        );
    }
}

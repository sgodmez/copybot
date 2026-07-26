package com.copybot.plugin.embedded;

import com.copybot.plugin.api.action.ActionDefinition;
import com.copybot.plugin.api.action.IInAction;
import com.copybot.plugin.api.action.IOutAction;
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

    /**
     * Actions are registered per step type: {@code PluginDefinition.findAction} only looks into the
     * list matching the step being resolved, so an OUT action listed here would never be found by an
     * outStep (and would be wrongly offered to inSteps). The type arguments are explicit so the
     * compiler — not the runtime — enforces the action/step-type bound.
     */
    @Override
    public List<ActionDefinition<? extends IInAction>> getInActions() {
        return List.of(
                new ActionDefinition<FileReadAction>("file.read", FileReadAction.class, false)
        );
    }

    @Override
    public List<ActionDefinition<? extends IOutAction>> getOutActions() {
        return List.of(
                new ActionDefinition<FileWriteAction>("file.write", FileWriteAction.class, false)
        );
    }
}

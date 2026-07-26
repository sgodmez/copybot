package com.copybot.plugin.embedded;

import com.copybot.engine.pipeline.StepType;
import com.copybot.engine.plugin.PluginDefinition;
import com.copybot.exception.ActionNotFoundException;
import com.copybot.plugin.api.action.ActionDefinition;
import com.copybot.plugin.embedded.actions.FileReadAction;
import com.copybot.plugin.embedded.actions.FileWriteAction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class CBEmbeddedPluginTest {

    private final CBEmbeddedPlugin plugin = new CBEmbeddedPlugin();

    @Test
    public void fileReadIsTheOnlyInAction() {
        assertEquals(List.of("file.read"),
                plugin.getInActions().stream().map(ActionDefinition::actionCode).toList());
    }

    @Test
    public void fileWriteIsRegisteredAsAnOutAction() {
        assertEquals(List.of("file.write"),
                plugin.getOutActions().stream().map(ActionDefinition::actionCode).toList());
    }

    /** This is the exact lookup {@code PluginEngine.resolve} performs for each step of a pipeline. */
    @Test
    public void bothEmbeddedActionsResolveUnderTheirOwnStepType() {
        PluginDefinition definition = PluginDefinition.ofEmbedded(plugin);

        assertEquals(FileReadAction.class, definition.findAction("file.read", StepType.IN).actionClass());
        assertEquals(FileWriteAction.class, definition.findAction("file.write", StepType.OUT).actionClass());
    }

    @Test
    public void anOutActionIsNotOfferedToAnInStep() {
        PluginDefinition definition = PluginDefinition.ofEmbedded(plugin);

        assertThrows(ActionNotFoundException.class, () -> definition.findAction("file.write", StepType.IN));
        assertThrows(ActionNotFoundException.class, () -> definition.findAction("file.read", StepType.OUT));
    }
}

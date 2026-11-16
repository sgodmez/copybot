package com.copybot.plugin.exec;

import com.copybot.plugin.api.action.ActionDefinition;
import com.copybot.plugin.api.action.IProcessAction;
import com.copybot.plugin.api.definition.AbstractPlugin;
import com.copybot.plugin.exec.actions.ExecProcess;

import java.util.List;

/** External programs run on the files of a pipeline: any program (exec.process). */
public class ExecPlugin extends AbstractPlugin {

    @Override
    public String getPluginCode() {
        return "exec";
    }

    @Override
    public List<ActionDefinition<? extends IProcessAction>> getProcessActions() {
        return List.of(
                new ActionDefinition<>(ExecProcess.CODE, ExecProcess.class)
        );
    }
}

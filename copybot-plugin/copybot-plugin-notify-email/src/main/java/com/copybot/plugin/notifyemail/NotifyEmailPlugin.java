package com.copybot.plugin.notifyemail;

import com.copybot.plugin.api.action.ActionDefinition;
import com.copybot.plugin.api.action.IRunEndAction;
import com.copybot.plugin.api.definition.AbstractPlugin;
import com.copybot.plugin.notifyemail.actions.EmailSend;

import java.util.List;

public class NotifyEmailPlugin extends AbstractPlugin {

    @Override
    public String getPluginCode() {
        return "notify-email";
    }

    @Override
    public List<ActionDefinition<? extends IRunEndAction>> getRunEndActions() {
        return List.of(
                new ActionDefinition<>("email.send", EmailSend.class)
        );
    }
}

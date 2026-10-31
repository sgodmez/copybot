package com.copybot.plugin.notifywebhook;

import com.copybot.plugin.api.action.ActionDefinition;
import com.copybot.plugin.api.action.IRunEndAction;
import com.copybot.plugin.api.definition.AbstractPlugin;
import com.copybot.plugin.notifywebhook.actions.WebhookSend;

import java.util.List;

public class NotifyWebhookPlugin extends AbstractPlugin {

    @Override
    public String getPluginCode() {
        return "notify-webhook";
    }

    @Override
    public List<ActionDefinition<? extends IRunEndAction>> getRunEndActions() {
        return List.of(
                new ActionDefinition<>("webhook.send", WebhookSend.class)
        );
    }
}

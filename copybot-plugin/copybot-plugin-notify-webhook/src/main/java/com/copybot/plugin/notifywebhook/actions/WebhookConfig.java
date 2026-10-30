package com.copybot.plugin.notifywebhook.actions;

import com.copybot.plugin.api.config.AllowedValues;
import com.copybot.plugin.api.config.DefaultValue;
import com.copybot.plugin.api.config.Required;

import java.util.List;
import java.util.Objects;

/**
 * The "actionConfig" of webhook.send.
 *
 * @param url     where to post; it may hold a secret (a Telegram URL embeds the bot token), so it is a plain text
 *                that is never put in a message or a log
 * @param preset  the shape of the message: json, ntfy, discord, slack or telegram (json when absent)
 * @param headers "Name: value" lines added to the request, replacing the preset's header of the same name; their
 *                values may hold secrets and are never put in a message or a log
 * @param body    a template for the message; absent or blank: the preset's
 */
public record WebhookConfig(
        @Required
        String url,

        @AllowedValues({"json", "ntfy", "discord", "slack", "telegram"}) @DefaultValue("json")
        String preset,

        List<String> headers,

        String body
) {

    public WebhookConfig {
        headers = headers == null ? List.of() : headers.stream().filter(Objects::nonNull).toList();
    }

    /** Neither the url nor the headers (they may hold secrets): the preset and the number of headers only. */
    @Override
    public String toString() {
        return "WebhookConfig[preset=" + preset + ", headers=" + headers.size() + "]";
    }
}

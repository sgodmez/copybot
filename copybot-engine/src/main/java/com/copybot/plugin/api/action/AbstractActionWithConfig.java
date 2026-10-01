package com.copybot.plugin.api.action;

import com.copybot.plugin.api.config.ConfigSchema;
import com.google.gson.Gson;
import com.google.gson.JsonElement;

import java.util.Optional;

public abstract class AbstractActionWithConfig<C> extends AbstractAction {
    private C config;

    protected abstract Class<C> getConfigClass();

    @Override
    public void loadConfig(JsonElement config) {
        this.config = new Gson().fromJson(config, getConfigClass());
    }

    protected C getConfig() {
        return config;
    }

    /** Introspects the configuration class when it is a record (desktop-ui spec, part 4), empty otherwise. */
    @Override
    public Optional<ConfigSchema> configSchema() {
        Class<C> configClass = getConfigClass();
        return configClass.isRecord()
                ? Optional.of(ConfigSchema.of(configClass.asSubclass(Record.class)))
                : Optional.empty();
    }
}

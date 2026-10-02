package com.copybot.plugin.api.action;

import com.copybot.plugin.api.config.ConfigSchema;
import com.copybot.utils.GsonUtil;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;

import java.util.Optional;

public abstract class AbstractActionWithConfig<C> extends AbstractAction {

    /** Integers read exactly ({@link GsonUtil#EXACT_INTEGERS}): an out-of-range int is a config error. */
    private static final Gson GSON = new GsonBuilder().registerTypeAdapterFactory(GsonUtil.EXACT_INTEGERS).create();

    private C config;

    protected abstract Class<C> getConfigClass();

    @Override
    public void loadConfig(JsonElement config) {
        this.config = GSON.fromJson(config, getConfigClass());
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

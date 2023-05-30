package com.copybot.plugin.action;

import com.google.gson.Gson;
import com.google.gson.JsonElement;

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
}

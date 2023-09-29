package com.copybot.plugin.api.action;

import com.copybot.plugin.api.definition.IPlugin;
import com.google.gson.JsonElement;

import java.util.function.Consumer;

public interface IAction {

    default void loadConfig(JsonElement config) {
        // nothing by default
    }

    default void beforeAll() {
        // nothing by default
    }

    default void beforeEach(WorkItem item) {
        // nothing by default
    }

    default void afterEach(WorkItem item) {
        // nothing by default
    }

    default void afterAll() {
        // nothing by default
    }

    void setStatusWatcher(Consumer<WorkStatus> watcher);

    /**
     * Called at start to chain action with its plugin.
     */
    void setPlugin(IPlugin plugin);

}

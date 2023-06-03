package com.copybot.plugin.action;

import com.copybot.plugin.definition.IPlugin;
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

    void setPlugin(IPlugin plugin);

}

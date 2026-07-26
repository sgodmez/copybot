package com.copybot.plugin.api.action;

import com.copybot.plugin.api.definition.IPlugin;
import com.google.gson.JsonElement;

import java.nio.file.Path;
import java.util.Set;
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

    /**
     * Named resources this action consumes for one item (e.g. "cpu", "gpu", "net:flickr").
     * Used by the engine to bound concurrency. Empty by default.
     */
    default Set<String> requiredResources(WorkItem item) {
        return Set.of();
    }

    /**
     * Filesystem paths this action will touch for one item (the engine maps them to
     * disk resources). For IN actions this is called once with a null item before listing.
     * Empty by default.
     */
    default Set<Path> touchedPaths(WorkItem item) {
        return Set.of();
    }

    void setStatusWatcher(Consumer<WorkStatus> watcher);

    /**
     * Called at start to chain action with its plugin.
     */
    void setPlugin(IPlugin plugin);

}

package com.copybot.plugin.api.action;

import com.copybot.plugin.api.config.ConfigSchema;
import com.copybot.plugin.api.definition.IPlugin;
import com.google.gson.JsonElement;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
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

    /**
     * Warnings about this action's configuration, valid but risky (e.g. sources deleted after a light
     * verification). Called once the configuration is loaded; the engine shows them before the run
     * (dry-run output, CLI start, UI banner). Empty by default; a null result means none. It must not throw.
     */
    default List<String> configWarnings() {
        return List.of();
    }

    /**
     * The fields of this action's configuration, for the pipeline editor (desktop-ui spec, part 4). Empty by
     * default: the editor then keeps the configuration JSON as is. {@link AbstractActionWithConfig}
     * introspects its configuration record; override it when introspection is not enough.
     */
    default Optional<ConfigSchema> configSchema() {
        return Optional.empty();
    }

    void setStatusWatcher(Consumer<WorkStatus> watcher);

    /**
     * Called at start to chain action with its plugin.
     */
    void setPlugin(IPlugin plugin);

}

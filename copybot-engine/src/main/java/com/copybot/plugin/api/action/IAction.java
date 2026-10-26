package com.copybot.plugin.api.action;

import com.copybot.plugin.api.config.ConfigSchema;
import com.copybot.plugin.api.definition.IPlugin;
import com.google.gson.JsonElement;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * An action of a plugin, one step of a pipeline. The engine creates one instance per step and per pipeline: it
 * calls {@link #setPlugin}, then {@link #loadConfig}, then uses the instance for every item of the step,
 * <em>concurrently</em> (one virtual thread per item): the per-item methods must be thread-safe.
 *
 * <h2>Lifecycle</h2>
 * A pipeline runs in phases: the preparation lists the files and runs the analyse steps, the execution runs the
 * steps after them (process and out); a run without preparation (the CLI without resume, the streaming run) is a
 * single phase running every step; the analysis of files deferred by a resume point is a phase of its own. In
 * each phase:
 * <ul>
 *     <li>{@link #beforeAll} is called once, on the thread running the phase, before the first item, for every
 *     action the phase may run (an IN action before it lists);</li>
 *     <li>{@link #beforeEach} and {@link #afterEach} are called around each step of each item, on the item's
 *     thread ({@link IInAction}: around nothing, it has no item);</li>
 *     <li>{@link #afterItem} is called once an item is finished, for every step that began for it;</li>
 *     <li>{@link #afterAll} is called once, after the last item of the phase, whatever its outcome (cancelled or
 *     failed included), when {@link #beforeAll} returned.</li>
 * </ul>
 * A failure of {@link #beforeAll} fails the phase: the pipeline ends ERROR with the cause and nothing more runs (the
 * analysis of deferred files: the files to analyse end ERROR with it, the plan stays prepared). A failure of
 * {@link #beforeEach} or {@link #afterEach} fails the item, like a failure of the step. A failure of
 * {@link #afterAll} after a normal phase fails the pipeline like a failure of {@link #beforeAll} (after a stopped or
 * failed phase it is logged and kept as the cause when there is none yet; after the analysis of deferred files it
 * is only logged). The dry runs, the target checks and the
 * editor's sample call none of them.
 */
public interface IAction {

    default void loadConfig(JsonElement config) {
        // nothing by default
    }

    /** Once per phase, before its first item (see the lifecycle above). */
    default void beforeAll() {
        // nothing by default
    }

    /** Before each step of each item, on the item's thread (see the lifecycle above). */
    default void beforeEach(WorkItem item) {
        // nothing by default
    }

    /**
     * After each step of each item whose {@link #beforeEach} returned, failed or not, on the item's thread; the
     * item is the one handed to {@link #beforeEach} (a process step may replace it).
     */
    default void afterEach(WorkItem item) {
        // nothing by default
    }

    /**
     * Once the item is finished in this run (written, skipped, filtered out, failed, or stopped by a cancel), for
     * every step that began for it ({@link #beforeEach} called), the last step first: the time to delete the
     * working files the step made for it (e.g. a buffer copy, see {@link WorkItem#withContentFile}). The item is
     * the one handed to {@link #beforeEach}; when the step forked it, the call waits for every item made from it.
     * Called on the thread of the item finishing last, possibly in a later phase than the step (the analysis of
     * a plan); an item the run leaves out (not selected, run stopped before it) gets none: {@link #afterAll}
     * cleans up what is left. A failure is logged, it changes nothing to the item.
     */
    default void afterItem(WorkItem item) {
        // nothing by default
    }

    /** Once per phase, after its last item, when {@link #beforeAll} returned (see the lifecycle above). */
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

    /**
     * Called by the engine right after it created the action, before {@link #loadConfig}: chains the action with
     * its plugin (e.g. for its resource bundle, see {@link AbstractAction#getResourceBundle()}). Nothing by default.
     */
    default void setPlugin(IPlugin plugin) {
        // nothing by default
    }

}

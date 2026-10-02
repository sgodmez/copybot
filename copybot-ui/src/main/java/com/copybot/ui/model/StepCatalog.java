package com.copybot.ui.model;

import com.copybot.engine.plugin.CatalogAction;
import com.copybot.engine.plugin.PluginCatalog;
import com.copybot.engine.plugin.PluginCatalog.FailedPlugin;
import com.copybot.utils.VersionUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The loaded actions as the pipeline editor uses them (spec desktop-ui §3): what a section can add, and
 * which action a step of the pipeline resolves to, with the engine's rules.
 */
public final class StepCatalog {

    private final List<CatalogAction> actions;
    private final List<FailedPlugin> failedPlugins;

    /** @param catalog of {@code PluginEngine.catalog()}: most recent plugin version first */
    public StepCatalog(PluginCatalog catalog) {
        this.actions = catalog.actions();
        this.failedPlugins = catalog.failedPlugins();
    }

    /** A loaded plugin version: listed (its actions are in the catalog) or failed. */
    private record Version(String pluginName, String version, FailedPlugin failed) {
    }

    /**
     * The actions a step of this section can use: those of the most recent version of each plugin (an action
     * only an older version has is not offered: a new step names no version, so the engine would pick the
     * most recent one and not find it; nothing is offered of a plugin whose most recent version failed).
     */
    public List<CatalogAction> forSection(PipelineDocument.Section section) {
        List<CatalogAction> offered = new ArrayList<>();
        for (CatalogAction action : actions) {
            if (action.stepType() == section.stepType()
                    && resolve(action.pluginName(), null)
                    .filter(v -> v.failed() == null && Objects.equals(v.version(), action.pluginVersion()))
                    .isPresent()) {
                offered.add(action);
            }
        }
        return List.copyOf(offered);
    }

    /**
     * The action this step resolves to, like {@code PluginEngine.resolve}: first the plugin ("embedded" when
     * absent or blank), its most recent version compatible with the step's "version", then the action code
     * in that version, for the section's step type. Empty when the plugin, the version or the action in
     * that version is not loaded, or when that version failed ({@link #failedPlugin}): the step is read-only.
     */
    public Optional<CatalogAction> find(PipelineDocument.Section section, JsonObject step) {
        String action = text(step, "action");
        return resolve(pluginName(step), text(step, "version"))
                .filter(v -> v.failed() == null)
                .flatMap(v -> actions.stream()
                        .filter(a -> a.pluginName().equals(v.pluginName()) && Objects.equals(a.pluginVersion(), v.version()))
                        .filter(a -> a.stepType() == section.stepType() && a.actionCode().equals(action))
                        .findFirst());
    }

    /**
     * The failed plugin version this step resolves to (its actions cannot be listed: the engine would fail
     * to run it), empty when the step resolves to a listed version or to no loaded one.
     */
    public Optional<FailedPlugin> failedPlugin(JsonObject step) {
        return resolve(pluginName(step), text(step, "version")).map(Version::failed);
    }

    /** The most recent loaded version of the plugin compatible with the required one, failed or not. */
    private Optional<Version> resolve(String pluginName, String required) {
        List<Version> versions = new ArrayList<>();
        actions.stream().filter(a -> a.pluginName().equals(pluginName))
                .forEach(a -> versions.add(new Version(pluginName, a.pluginVersion(), null)));
        failedPlugins.stream().filter(f -> f.pluginName().equals(pluginName))
                .forEach(f -> versions.add(new Version(pluginName, f.pluginVersion(), f)));
        return versions.stream()
                .filter(v -> v.version() == null || VersionUtil.isCompatible(v.version(), required, true))
                .max(Comparator.comparing(Version::version, VersionUtil.VERSION_ORDER));
    }

    private static String pluginName(JsonObject step) {
        String plugin = text(step, "plugin");
        return plugin == null || plugin.isBlank() ? CatalogAction.EMBEDDED_PLUGIN : plugin;
    }

    private static String text(JsonObject object, String member) {
        JsonElement element = object.get(member);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }
}

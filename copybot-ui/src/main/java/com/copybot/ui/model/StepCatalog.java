package com.copybot.ui.model;

import com.copybot.engine.listing.GroupingPresetRegistry;
import com.copybot.engine.listing.GroupingPresets;
import com.copybot.engine.pipeline.GroupingRuleConfig;
import com.copybot.engine.plugin.CatalogAction;
import com.copybot.engine.plugin.CatalogPreset;
import com.copybot.engine.plugin.PluginCatalog;
import com.copybot.engine.plugin.PluginCatalog.FailedPlugin;
import com.copybot.utils.VersionUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The loaded actions as the pipeline editor uses them (spec desktop-ui §3): what a section can add, and
 * which action a step of the pipeline resolves to, with the engine's rules. Also the grouping presets of the
 * loaded plugins (spec plugin grouping presets §5).
 */
public final class StepCatalog {

    private final List<CatalogAction> actions;
    private final List<FailedPlugin> failedPlugins;
    private final List<CatalogPreset> groupingPresets;
    private final Set<String> presetPlugins;

    /** @param catalog of {@code PluginEngine.catalog()}: most recent plugin version first */
    public StepCatalog(PluginCatalog catalog) {
        this.actions = catalog.actions();
        this.failedPlugins = catalog.failedPlugins();
        this.groupingPresets = catalog.groupingPresets();
        this.presetPlugins = catalog.presetPlugins();
    }

    /** The grouping presets of the loaded plugins, what "Add a preset" offers after those of the core. */
    public List<CatalogPreset> groupingPresets() {
        return groupingPresets;
    }

    /** The grouping preset of this plugin (module name) and code, empty when it is not loaded. */
    public Optional<CatalogPreset> groupingPreset(String plugin, String code) {
        return groupingPresets.stream().filter(p -> p.pluginName().equals(plugin) && p.code().equals(code))
                .findFirst();
    }

    /**
     * The rules with each preset replaced by its rules: a core one, or one of a loaded plugin. Lenient, for the
     * editor's warnings: a preset that is not available (unknown, its plugin missing) and a null rule are left out;
     * a run refuses such a preset before any listing.
     */
    public List<GroupingRuleConfig> expandGroupingRules(List<GroupingRuleConfig> rules) {
        List<GroupingRuleConfig> expanded = new ArrayList<>();
        for (GroupingRuleConfig rule : rules) {
            if (rule == null) {
                continue;
            }
            if (rule.preset() == null) {
                expanded.add(rule);
            } else if (GroupingPresetRegistry.isCore(rule)) {
                GroupingPresets.rules(rule.preset()).ifPresent(expanded::addAll);
            } else {
                groupingPreset(rule.plugin(), rule.preset()).ifPresent(p -> expanded.addAll(p.rules()));
            }
        }
        return expanded;
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

    /**
     * True when a version of this plugin was loaded, failed or not: it has actions, failed, or its grouping presets
     * were read (even if all of them were discarded as invalid).
     */
    public boolean isLoaded(String pluginName) {
        return actions.stream().anyMatch(a -> a.pluginName().equals(pluginName))
                || failedPlugins.stream().anyMatch(f -> f.pluginName().equals(pluginName))
                || groupingPresets.stream().anyMatch(p -> p.pluginName().equals(pluginName))
                || pluginName != null && presetPlugins.contains(pluginName); // an immutable set refuses null
    }

    /**
     * The plugins the steps of the pipeline (secondary outputs included), then its grouping presets, name that are not
     * loaded at all, in that order without duplicates (a plugin loaded in another version is not counted). Not loaded
     * is not always not installed: a plugin that failed to load (refused for its API, a missing dependency) is here
     * too; "Install" is offered only for what the catalogue rows say leads somewhere ({@code PluginRow.offersInstall}).
     */
    public List<String> missingPlugins(PipelineDocument document) {
        Set<String> missing = new LinkedHashSet<>();
        for (PipelineDocument.StepAddress address : document.stepAddresses()) {
            String plugin = pluginName(document.step(address));
            if (!plugin.equals(CatalogAction.EMBEDDED_PLUGIN) && !isLoaded(plugin)) {
                missing.add(plugin);
            }
        }
        for (JsonObject rule : document.groupingRules()) {
            presetPlugin(rule).filter(plugin -> !isLoaded(plugin)).ifPresent(missing::add);
        }
        return List.copyOf(missing);
    }

    /** The plugin (module name) of the grouping preset the rule cites, empty for a core preset or an own rule. */
    public static Optional<String> presetPlugin(JsonObject rule) {
        String preset = text(rule, "preset");
        String plugin = text(rule, "plugin");
        return preset == null || plugin == null || plugin.isBlank() ? Optional.empty() : Optional.of(plugin);
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

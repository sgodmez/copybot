package com.copybot.ui.model;

import com.copybot.engine.plugin.CatalogAction;
import com.copybot.utils.VersionUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The loaded actions as the pipeline editor uses them (spec desktop-ui §3): what a section can add, and
 * which action a step of the pipeline resolves to, with the engine's rules.
 */
public final class StepCatalog {

    private final List<CatalogAction> actions;

    /** @param actions in the order of {@code PluginEngine.catalog()}: most recent plugin version first */
    public StepCatalog(List<CatalogAction> actions) {
        this.actions = List.copyOf(actions);
    }

    /**
     * The actions a step of this section can use: those of the most recent version of each plugin (an action
     * only an older version has is not offered: a new step names no version, so the engine would pick the
     * most recent one and not find it).
     */
    public List<CatalogAction> forSection(PipelineDocument.Section section) {
        List<CatalogAction> offered = new ArrayList<>();
        for (CatalogAction action : actions) {
            if (action.stepType() == section.stepType()
                    && Objects.equals(action.pluginVersion(), newestVersion(action.pluginName()))) {
                offered.add(action);
            }
        }
        return List.copyOf(offered);
    }

    /** The version of the first (most recent) entry of this plugin. */
    private String newestVersion(String pluginName) {
        return actions.stream().filter(a -> a.pluginName().equals(pluginName))
                .findFirst().map(CatalogAction::pluginVersion).orElse(null); // embedded: no version
    }

    /**
     * The action this step resolves to, like {@code PluginEngine.resolve}: first the plugin ("embedded" when
     * absent or blank), its most recent version compatible with the step's "version", then the action code
     * in that version, for the section's step type. Empty when the plugin, the version or the action in
     * that version is not loaded: the step is read-only.
     */
    public Optional<CatalogAction> find(PipelineDocument.Section section, JsonObject step) {
        String plugin = text(step, "plugin");
        String pluginName = plugin == null || plugin.isBlank() ? CatalogAction.EMBEDDED_PLUGIN : plugin;
        String action = text(step, "action");
        String version = text(step, "version");
        Optional<CatalogAction> pluginEntry = actions.stream()
                .filter(a -> a.pluginName().equals(pluginName))
                .filter(a -> a.pluginVersion() == null || VersionUtil.isCompatible(a.pluginVersion(), version, true))
                .findFirst();
        return pluginEntry.flatMap(entry -> actions.stream()
                .filter(a -> a.pluginName().equals(pluginName) && Objects.equals(a.pluginVersion(), entry.pluginVersion()))
                .filter(a -> a.stepType() == section.stepType() && a.actionCode().equals(action))
                .findFirst());
    }

    private static String text(JsonObject object, String member) {
        JsonElement element = object.get(member);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }
}

package com.copybot.engine.plugin;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The loaded plugins as the pipeline editor sees them (spec desktop-ui §4), from {@link PluginEngine#catalog()}.
 *
 * @param actions       every action of the loaded plugins, in plugin order (name, then most recent version
 *                      first) then step type
 * @param failedPlugins   the loaded plugin versions whose actions or grouping presets cannot be listed (e.g. a
 *                        missing class), in the same plugin order. Actions: {@link PluginEngine#resolve} still picks
 *                        such a version for a step it matches, then fails. Presets: the version's actions are not
 *                        listed either, so the editor shows it as failed, while {@link PluginEngine#resolve} still
 *                        resolves its actions at run time (deliberate, spec plugin grouping presets §5). A plugin that
 *                        failed to load is not here: it is not loaded, a step never resolves to it
 * @param groupingPresets the valid grouping presets of the most recent version of each loaded plugin (spec plugin
 *                        grouping presets §5), in plugin order then declaration order, even when that version's
 *                        actions cannot be listed
 * @param presetPlugins   the module names of the loaded plugins whose grouping presets were read, valid or not: a
 *                        plugin whose presets were all discarded is loaded, not missing
 */
public record PluginCatalog(List<CatalogAction> actions, List<FailedPlugin> failedPlugins,
                            List<CatalogPreset> groupingPresets, Set<String> presetPlugins) {

    public PluginCatalog {
        actions = List.copyOf(actions);
        failedPlugins = List.copyOf(failedPlugins);
        groupingPresets = List.copyOf(groupingPresets);
        presetPlugins = Set.copyOf(presetPlugins);
    }

    /** A catalog whose preset plugins are those of its presets. */
    public PluginCatalog(List<CatalogAction> actions, List<FailedPlugin> failedPlugins,
                         List<CatalogPreset> groupingPresets) {
        this(actions, failedPlugins, groupingPresets,
                groupingPresets.stream().map(CatalogPreset::pluginName).collect(Collectors.toSet()));
    }

    /** A catalog without grouping preset. */
    public PluginCatalog(List<CatalogAction> actions, List<FailedPlugin> failedPlugins) {
        this(actions, failedPlugins, List.of());
    }

    /**
     * A loaded plugin version whose actions or grouping presets cannot be listed.
     *
     * @param pluginName    as {@link CatalogAction#pluginName()}
     * @param pluginVersion null for the embedded plugin
     */
    public record FailedPlugin(String pluginName, String pluginVersion) {

        public FailedPlugin {
            Objects.requireNonNull(pluginName, "pluginName");
        }
    }
}

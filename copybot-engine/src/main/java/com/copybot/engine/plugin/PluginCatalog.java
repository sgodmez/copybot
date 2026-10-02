package com.copybot.engine.plugin;

import java.util.List;
import java.util.Objects;

/**
 * The loaded plugins as the pipeline editor sees them (spec desktop-ui §4), from {@link PluginEngine#catalog()}.
 *
 * @param actions       every action of the loaded plugins, in plugin order (name, then most recent version
 *                      first) then step type
 * @param failedPlugins the loaded plugin versions whose actions cannot be listed (e.g. a missing class), in
 *                      the same plugin order: {@link PluginEngine#resolve} still picks such a version for a
 *                      step it matches, then fails. A plugin that failed to load is not here: it is not
 *                      loaded, a step never resolves to it
 */
public record PluginCatalog(List<CatalogAction> actions, List<FailedPlugin> failedPlugins) {

    public PluginCatalog {
        actions = List.copyOf(actions);
        failedPlugins = List.copyOf(failedPlugins);
    }

    /**
     * A loaded plugin version whose actions cannot be listed.
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

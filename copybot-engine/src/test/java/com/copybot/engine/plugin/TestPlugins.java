package com.copybot.engine.plugin;

import com.copybot.plugin.api.definition.IPlugin;
import com.copybot.plugin.api.grouping.GroupingPreset;
import com.copybot.resources.CombinedResourceBundle;

import java.util.List;
import java.util.function.Supplier;

/** Loaded plugins without module layer, for the tests of the other packages. */
public final class TestPlugins {

    private TestPlugins() {
    }

    /** A loaded plugin of this module name and version. */
    public static PluginDefinition loaded(String name, String version, IPlugin plugin) {
        return PluginDefinition.ofLoaded(name, version, plugin);
    }

    /** A plugin of this code that brings the presets of the supplier (which may throw, like a broken plugin). */
    public static IPlugin withPresets(String code, Supplier<List<GroupingPreset>> presets) {
        return new IPlugin() {
            private CombinedResourceBundle bundle;

            @Override
            public String getPluginCode() {
                return code;
            }

            @Override
            public Iterable<String> getI18nBundleNames() {
                return List.of();
            }

            @Override
            public void setResourceBundle(CombinedResourceBundle resourceBundle) {
                this.bundle = resourceBundle;
            }

            @Override
            public CombinedResourceBundle getResourceBundle() {
                return bundle;
            }

            @Override
            public List<GroupingPreset> getGroupingPresets() {
                return presets.get();
            }
        };
    }
}

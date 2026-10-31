package com.copybot.engine.plugin;

import com.copybot.engine.pipeline.GroupingRuleConfig;

import java.util.List;
import java.util.Objects;

/**
 * A grouping preset of a loaded plugin, as offered by the pipeline editor (spec plugin grouping presets §5), texts
 * resolved in the language of the call to {@link PluginEngine#catalog()}. A pipeline cites it as
 * {@code {"preset": code, "plugin": pluginName}}.
 *
 * @param pluginName    the module name of the plugin, what the "plugin" of the citation names
 * @param pluginCode    the plugin code of the i18n keys ("plugin.&lt;code&gt;.preset.&lt;preset&gt;.name")
 * @param pluginVersion the loaded version that brings the preset (the most recent one)
 * @param code          the preset code
 * @param name          the localized name, the code when the plugin has none
 * @param description   the localized description, empty when the plugin has none
 * @param rules         the rules of the preset in their pipeline form
 */
public record CatalogPreset(
        String pluginName,
        String pluginCode,
        String pluginVersion,
        String code,
        String name,
        String description,
        List<GroupingRuleConfig> rules) {

    public CatalogPreset {
        Objects.requireNonNull(pluginName, "pluginName");
        Objects.requireNonNull(code, "code");
        rules = List.copyOf(rules);
    }
}

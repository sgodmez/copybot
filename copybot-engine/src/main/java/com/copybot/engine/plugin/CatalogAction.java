package com.copybot.engine.plugin;

import com.copybot.engine.pipeline.StepType;
import com.copybot.plugin.api.config.ConfigField;
import com.copybot.plugin.api.config.ConfigSchema;
import com.copybot.plugin.embedded.CBEmbeddedPlugin;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One action of a loaded plugin, as offered by the pipeline editor (spec desktop-ui §4), texts resolved in
 * the language of the call to {@link PluginEngine#catalog()}.
 *
 * @param pluginName    what the "plugin" of a pipeline step names ("embedded" for the embedded actions,
 *                      which a step may leave out)
 * @param pluginCode    the plugin code of the i18n keys ("plugin.&lt;code&gt;.&lt;action&gt;.name")
 * @param pluginVersion null for the embedded plugin
 * @param name          the localized name, the action code when the plugin has none
 * @param description   the localized description, empty when the plugin has none
 * @param schema        the configuration schema, keys prefixed with "plugin.&lt;code&gt;.&lt;action&gt;";
 *                      null when the action describes none
 * @param texts         the localized label and description of every schema field that has one, by key
 */
public record CatalogAction(
        String pluginName,
        String pluginCode,
        String pluginVersion,
        String actionCode,
        StepType stepType,
        String name,
        String description,
        ConfigSchema schema,
        Map<String, String> texts) {

    /** The plugin name of the embedded actions: a pipeline step may leave its "plugin" out. */
    public static final String EMBEDDED_PLUGIN = CBEmbeddedPlugin.EMBEDDED_PLUGN_NAME;

    public CatalogAction {
        Objects.requireNonNull(pluginName, "pluginName");
        Objects.requireNonNull(actionCode, "actionCode");
        Objects.requireNonNull(stepType, "stepType");
        texts = Map.copyOf(texts);
    }

    public boolean isEmbedded() {
        return EMBEDDED_PLUGIN.equals(pluginName);
    }

    public Optional<ConfigSchema> configSchema() {
        return Optional.ofNullable(schema);
    }

    /** The localized label of the field, its name when the plugin has none (spec desktop-ui §4). */
    public String label(ConfigField field) {
        return texts.getOrDefault(field.labelKey(), field.name());
    }

    /** The localized label of one value of an enum field, the value as written when the plugin has none. */
    public String valueLabel(ConfigField field, String value) {
        return texts.getOrDefault(field.valueKey(value), value);
    }

    /** The localized description of the field, empty when the plugin has none. */
    public String description(ConfigField field) {
        return texts.getOrDefault(field.descriptionKey(), "");
    }
}

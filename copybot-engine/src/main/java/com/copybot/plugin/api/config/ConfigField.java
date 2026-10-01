package com.copybot.plugin.api.config;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One field of an action configuration (spec desktop-ui §4).
 *
 * @param name           the record component name, i.e. the JSON member name
 * @param path           the dotted path from the configuration root (e.g. "onConflict.compare")
 * @param defaultValue   {@link DefaultValue}, null when none
 * @param labelKey       i18n key of the label: "config.&lt;path&gt;.name", prefixed by
 *                       {@link ConfigSchema#withKeyPrefix} with "plugin.&lt;plugin&gt;.&lt;action&gt;"
 * @param descriptionKey i18n key of the description: "config.&lt;path&gt;.description", prefixed alike
 * @param children       the fields of a {@link FieldKind#RECORD}, empty otherwise
 * @param enumValues     the values of an {@link FieldKind#ENUM}, empty otherwise
 * @param elementSchema  the element of a {@link FieldKind#LIST} (same name, path and keys), null otherwise
 */
public record ConfigField(
        String name,
        String path,
        FieldKind kind,
        boolean required,
        String defaultValue,
        Set<FieldHint> hints,
        String labelKey,
        String descriptionKey,
        List<ConfigField> children,
        List<String> enumValues,
        ConfigField elementSchema) {

    public ConfigField {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(kind, "kind");
        hints = Set.copyOf(hints);
        children = List.copyOf(children);
        enumValues = List.copyOf(enumValues);
    }

    public boolean hasHint(FieldHint hint) {
        return hints.contains(hint);
    }

    /** This field, its children and its element schema with "prefix." in front of every i18n key. */
    public ConfigField withKeyPrefix(String prefix) {
        return new ConfigField(name, path, kind, required, defaultValue, hints,
                prefix + "." + labelKey, prefix + "." + descriptionKey,
                children.stream().map(child -> child.withKeyPrefix(prefix)).toList(),
                enumValues,
                elementSchema == null ? null : elementSchema.withKeyPrefix(prefix));
    }
}

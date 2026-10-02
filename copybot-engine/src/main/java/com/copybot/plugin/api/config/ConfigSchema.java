package com.copybot.plugin.api.config;

import com.copybot.plugin.api.action.WorkItemMetadata;

import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The configuration fields of an action, for the generic pipeline editor (spec desktop-ui §4). Built by
 * introspection of the record components of the configuration class ({@link #of}); an action may also
 * build one by hand when introspection is not enough.
 */
public record ConfigSchema(List<ConfigField> fields) {

    /** The variables of an output pattern known to the embedded actions, without braces. */
    public static final List<String> PATTERN_VARIABLES = patternVariables();

    private static final Set<Class<?>> INTEGER_TYPES = Set.of(int.class, Integer.class, long.class, Long.class,
            short.class, Short.class, byte.class, Byte.class, BigInteger.class);
    private static final Set<Class<?>> DECIMAL_TYPES = Set.of(double.class, Double.class, float.class, Float.class,
            BigDecimal.class);

    public ConfigSchema {
        fields = List.copyOf(fields);
    }

    /**
     * The fields of this record, in component order: String and Path (PATH with {@link DirectoryPath} /
     * {@link FilePath}, ENUM with {@link AllowedValues}), booleans, integers, decimals, enums (constant
     * names), records (RECORD, recursively) and List / Set / Collection (LIST, of String when raw). A
     * component of another type (Map, JsonElement...) or of a record type being introspected (a cycle) is
     * left out: the editor keeps its JSON value untouched. The annotations of a list component describe the
     * list itself and are not propagated to its element schema (an element of a {@code List<Path>} is a
     * plain PATH, without {@link DirectoryPath} / {@link FilePath} hint).
     */
    public static ConfigSchema of(Class<? extends Record> configClass) {
        return new ConfigSchema(recordFields(configClass, "", new HashSet<>()));
    }

    /** The same schema with "prefix." in front of every i18n key (e.g. "plugin.embedded.file.write"). */
    public ConfigSchema withKeyPrefix(String prefix) {
        return new ConfigSchema(fields.stream().map(field -> field.withKeyPrefix(prefix)).toList());
    }

    /** The field at this dotted path (e.g. "onConflict.compare"), looking into the records. */
    public Optional<ConfigField> field(String path) {
        return allFields().stream().filter(field -> field.path().equals(path)).findFirst();
    }

    /** Every field, depth first: a record field is followed by its children (list elements excluded). */
    public List<ConfigField> allFields() {
        List<ConfigField> all = new ArrayList<>();
        addAll(fields, all);
        return List.copyOf(all);
    }

    private static void addAll(List<ConfigField> fields, List<ConfigField> all) {
        for (ConfigField field : fields) {
            all.add(field);
            addAll(field.children(), all);
        }
    }

    private static List<ConfigField> recordFields(Class<?> recordClass, String parentPath, Set<Class<?>> ancestors) {
        ancestors.add(recordClass);
        List<ConfigField> fields = new ArrayList<>();
        for (RecordComponent component : recordClass.getRecordComponents()) {
            String path = parentPath.isEmpty() ? component.getName() : parentPath + "." + component.getName();
            fieldOf(component.getName(), path, component.getType(), component.getGenericType(), component, ancestors)
                    .ifPresent(fields::add);
        }
        ancestors.remove(recordClass);
        return fields;
    }

    /** @param component the annotated record component, null for a list element */
    private static Optional<ConfigField> fieldOf(String name, String path, Class<?> type, Type genericType,
                                                 RecordComponent component, Set<Class<?>> ancestors) {
        Set<FieldHint> hints = EnumSet.noneOf(FieldHint.class);
        List<ConfigField> children = List.of();
        List<String> enumValues = List.of();
        ConfigField element = null;
        FieldKind kind;
        if (type == String.class || type == Path.class) {
            if (annotated(component, DirectoryPath.class)) {
                hints.add(FieldHint.DIRECTORY);
            }
            if (annotated(component, FilePath.class)) {
                hints.add(FieldHint.FILE);
            }
            if (annotated(component, PatternField.class)) {
                hints.add(FieldHint.PATTERN);
            }
            AllowedValues allowed = component == null ? null : component.getAnnotation(AllowedValues.class);
            if (allowed != null) {
                kind = FieldKind.ENUM;
                enumValues = List.of(allowed.value());
            } else if (type == Path.class || hints.contains(FieldHint.DIRECTORY) || hints.contains(FieldHint.FILE)) {
                kind = FieldKind.PATH;
            } else {
                kind = FieldKind.STRING;
            }
        } else if (type == boolean.class || type == Boolean.class) {
            kind = FieldKind.BOOLEAN;
        } else if (INTEGER_TYPES.contains(type)) {
            kind = FieldKind.INTEGER;
        } else if (DECIMAL_TYPES.contains(type)) {
            kind = FieldKind.DECIMAL;
        } else if (type.isEnum()) {
            kind = FieldKind.ENUM;
            enumValues = Arrays.stream(type.getEnumConstants()).map(constant -> ((Enum<?>) constant).name()).toList();
        } else if (type.isRecord()) {
            if (ancestors.contains(type)) {
                return Optional.empty(); // a cycle: left out
            }
            kind = FieldKind.RECORD;
            children = recordFields(type, path, ancestors);
        } else if (type == List.class || type == Set.class || type == Collection.class) {
            kind = FieldKind.LIST;
            Type elementType = genericType instanceof ParameterizedType parameterized
                    ? parameterized.getActualTypeArguments()[0]
                    : String.class;
            Class<?> elementClass = elementType instanceof Class<?> c ? c
                    : elementType instanceof ParameterizedType p && p.getRawType() instanceof Class<?> raw ? raw
                    : null;
            if (elementClass == null) {
                return Optional.empty(); // a wildcard or type variable element: left out
            }
            Optional<ConfigField> elementField = fieldOf(name, path, elementClass, elementType, null, ancestors);
            if (elementField.isEmpty()) {
                return Optional.empty();
            }
            element = elementField.get();
        } else {
            return Optional.empty(); // Map, JsonElement, Object...: left out
        }
        DefaultValue defaultValue = component == null ? null : component.getAnnotation(DefaultValue.class);
        return Optional.of(new ConfigField(name, path, kind, annotated(component, Required.class),
                defaultValue == null ? null : defaultValue.value(), hints,
                "config." + path + ".name", "config." + path + ".description",
                children, enumValues, element));
    }

    private static boolean annotated(RecordComponent component, Class<? extends Annotation> annotation) {
        return component != null && component.isAnnotationPresent(annotation);
    }

    private static List<String> patternVariables() {
        List<String> variables = new ArrayList<>(List.of("name", "size"));
        for (String date : List.of("creation", WorkItemMetadata.LAST_MODIFIED, WorkItemMetadata.CAPTURE_DATE)) {
            for (String part : List.of("Y", "y", "m", "D")) {
                variables.add(date + "." + part);
            }
        }
        return List.copyOf(variables);
    }
}

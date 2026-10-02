package com.copybot.plugin.api.config;

import com.google.gson.JsonElement;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The configuration schema built by introspection of a record (spec desktop-ui §4). */
public class ConfigSchemaTest {

    enum Mode { FAST, SAFE }

    record Nested(@Required String inner, @DefaultValue("3") Integer count) {
    }

    record Sample(
            @Required @DirectoryPath String source,
            @FilePath Path file,
            Path anyPath,
            @PatternField String pattern,
            boolean flag,
            @DefaultValue("true") Boolean boxedFlag,
            int small,
            Long big,
            double ratio,
            BigDecimal price,
            Mode mode,
            @AllowedValues({"a", "b"}) @DefaultValue("a") String choice,
            Nested nested,
            List<String> globs,
            List raw,
            Set<Integer> numbers,
            List<Nested> items,
            Map<String, String> map,
            JsonElement json) {
    }

    record Loop(String name, Loop next) {
    }

    private static ConfigField field(ConfigSchema schema, String path) {
        return schema.field(path).orElseThrow(() -> new AssertionError("no field " + path));
    }

    @Test
    public void everyComponentGetsItsKindInComponentOrder() {
        ConfigSchema schema = ConfigSchema.of(Sample.class);

        assertEquals(List.of("source", "file", "anyPath", "pattern", "flag", "boxedFlag", "small", "big", "ratio",
                        "price", "mode", "choice", "nested", "globs", "raw", "numbers", "items"),
                schema.fields().stream().map(ConfigField::name).toList(), "map and json are left out");
        assertEquals(FieldKind.PATH, field(schema, "source").kind());
        assertEquals(FieldKind.PATH, field(schema, "file").kind());
        assertEquals(FieldKind.PATH, field(schema, "anyPath").kind());
        assertEquals(FieldKind.STRING, field(schema, "pattern").kind());
        assertEquals(FieldKind.BOOLEAN, field(schema, "flag").kind());
        assertEquals(FieldKind.BOOLEAN, field(schema, "boxedFlag").kind());
        assertEquals(FieldKind.INTEGER, field(schema, "small").kind());
        assertEquals(FieldKind.INTEGER, field(schema, "big").kind());
        assertEquals(FieldKind.DECIMAL, field(schema, "ratio").kind());
        assertEquals(FieldKind.DECIMAL, field(schema, "price").kind());
        assertEquals(FieldKind.RECORD, field(schema, "nested").kind());
        assertEquals(FieldKind.LIST, field(schema, "globs").kind());
    }

    @Test
    public void annotationsGiveHintsRequiredAndDefaults() {
        ConfigSchema schema = ConfigSchema.of(Sample.class);

        assertEquals(Set.of(FieldHint.DIRECTORY), field(schema, "source").hints());
        assertEquals(Set.of(FieldHint.FILE), field(schema, "file").hints());
        assertEquals(Set.of(), field(schema, "anyPath").hints());
        assertTrue(field(schema, "pattern").hasHint(FieldHint.PATTERN));
        assertTrue(field(schema, "source").required());
        assertFalse(field(schema, "file").required());
        assertEquals("true", field(schema, "boxedFlag").defaultValue());
        assertNull(field(schema, "flag").defaultValue());
    }

    @Test
    public void enumsAndAllowedValuesListTheirValues() {
        ConfigSchema schema = ConfigSchema.of(Sample.class);

        assertEquals(FieldKind.ENUM, field(schema, "mode").kind());
        assertEquals(List.of("FAST", "SAFE"), field(schema, "mode").enumValues());
        assertEquals(FieldKind.ENUM, field(schema, "choice").kind());
        assertEquals(List.of("a", "b"), field(schema, "choice").enumValues());
        assertEquals("a", field(schema, "choice").defaultValue());
        assertEquals(List.of(), field(schema, "pattern").enumValues());
    }

    @Test
    public void recordsHaveChildrenWithDottedPaths() {
        ConfigSchema schema = ConfigSchema.of(Sample.class);

        ConfigField nested = field(schema, "nested");
        assertEquals(List.of("nested.inner", "nested.count"), nested.children().stream().map(ConfigField::path).toList());
        assertTrue(field(schema, "nested.inner").required());
        assertEquals(FieldKind.INTEGER, field(schema, "nested.count").kind());
        assertEquals("3", field(schema, "nested.count").defaultValue());
        assertTrue(schema.allFields().indexOf(nested) < schema.allFields().indexOf(field(schema, "nested.inner")),
                "a record comes before its children");
    }

    @Test
    public void listsDescribeTheirElement() {
        ConfigSchema schema = ConfigSchema.of(Sample.class);

        assertEquals(FieldKind.STRING, field(schema, "globs").elementSchema().kind());
        assertEquals(FieldKind.STRING, field(schema, "raw").elementSchema().kind(), "a raw list holds strings");
        assertEquals(FieldKind.INTEGER, field(schema, "numbers").elementSchema().kind());
        ConfigField items = field(schema, "items");
        assertEquals(FieldKind.RECORD, items.elementSchema().kind());
        assertEquals(List.of("items.inner", "items.count"),
                items.elementSchema().children().stream().map(ConfigField::path).toList());
        assertNull(field(schema, "source").elementSchema());
    }

    @Test
    public void aCycleIsLeftOut() {
        assertEquals(List.of("name"), ConfigSchema.of(Loop.class).fields().stream().map(ConfigField::name).toList());
    }

    @Test
    public void i18nKeysFollowThePathAndTakeThePluginPrefix() {
        ConfigSchema schema = ConfigSchema.of(Sample.class);
        assertEquals("config.nested.inner.name", field(schema, "nested.inner").labelKey());
        assertEquals("config.nested.inner.description", field(schema, "nested.inner").descriptionKey());

        ConfigSchema prefixed = schema.withKeyPrefix("plugin.demo.do.it");

        assertEquals("plugin.demo.do.it.config.source.name", field(prefixed, "source").labelKey());
        assertEquals("plugin.demo.do.it.config.nested.count.description", field(prefixed, "nested.count").descriptionKey());
        assertEquals("plugin.demo.do.it.config.globs.name", field(prefixed, "globs").elementSchema().labelKey());
        assertEquals("plugin.demo.do.it.config.items.inner.name",
                field(prefixed, "items").elementSchema().children().getFirst().labelKey());
    }

    @Test
    public void thePatternVariablesAreTheOnesOfTheEmbeddedActions() {
        assertTrue(ConfigSchema.PATTERN_VARIABLES.containsAll(List.of("name", "size",
                "creation.Y", "lastModified.m", "captureDate.D", "captureDate.y")));
        assertEquals(14, ConfigSchema.PATTERN_VARIABLES.size());
        assertFalse(ConfigSchema.PATTERN_VARIABLES.contains("sizeHr"), "a size for the display, not for a file name");
    }
}

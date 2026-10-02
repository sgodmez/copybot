package com.copybot.plugin.api.config;

import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import com.copybot.plugin.embedded.actions.FileReadAction;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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

    record Ping(String name, Pong pong) {
    }

    record Pong(String label, Ping ping) {
    }

    record Tree(String name, List<Tree> children) {
    }

    record Unknowns(String kept, List<?> wildcard, List<? extends Number> bounded, List<Map<String, String>> maps) {
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
    public void anIndirectCycleIsLeftOutWhereItLoopsBack() {
        ConfigSchema schema = ConfigSchema.of(Ping.class);

        assertEquals(List.of("name", "pong", "pong.label"), schema.allFields().stream().map(ConfigField::path).toList());
    }

    @Test
    public void aListOfTheRecordBeingIntrospectedIsACycle() {
        assertEquals(List.of("name"), ConfigSchema.of(Tree.class).fields().stream().map(ConfigField::name).toList());
    }

    @Test
    public void wildcardAndMapElementsAreLeftOut() {
        assertEquals(List.of("kept"), ConfigSchema.of(Unknowns.class).fields().stream().map(ConfigField::name).toList());
    }

    @Test
    public void aSchemaCannotBeChangedOnceBuilt() {
        List<ConfigField> fields = new ArrayList<>(ConfigSchema.of(Sample.class).fields());
        ConfigSchema schema = new ConfigSchema(fields);
        fields.clear();

        assertEquals(17, schema.fields().size(), "the schema keeps its own copy");
        assertThrows(UnsupportedOperationException.class, () -> schema.fields().clear());
        assertThrows(UnsupportedOperationException.class, () -> schema.allFields().clear());
        assertThrows(UnsupportedOperationException.class, () -> field(schema, "nested").children().clear());
        assertThrows(UnsupportedOperationException.class, () -> field(schema, "mode").enumValues().clear());
        assertThrows(UnsupportedOperationException.class, () -> field(schema, "source").hints().clear());
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
    public void everyPatternVariableHasAValueForAReadFile(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("a.jpg"), "a");
        JsonObject config = new JsonObject();
        config.addProperty("path", tempDir.toString());
        FileReadAction read = new FileReadAction();
        read.loadConfig(config);
        List<WorkItem> items = new ArrayList<>();
        read.listFiles(items::add);
        Map<String, String> values = new HashMap<>(items.getFirst().getMetadatas().display());
        // the capture date is set by a plugin (metadata extractor), like any date
        WorkItemMetadata captured = new WorkItemMetadata();
        captured.setTime(WorkItemMetadata.CAPTURE_DATE, Instant.now());
        values.putAll(captured.display());

        for (String variable : ConfigSchema.PATTERN_VARIABLES) {
            assertTrue(values.containsKey(variable), "{" + variable + "} has no value: " + values.keySet());
        }
        assertTrue(ConfigSchema.PATTERN_VARIABLES.containsAll(List.of("name", "size", "creation.Y",
                "lastModified.m", "captureDate.D")));
    }
}

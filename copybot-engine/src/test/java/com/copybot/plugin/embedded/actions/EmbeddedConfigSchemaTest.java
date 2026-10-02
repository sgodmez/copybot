package com.copybot.plugin.embedded.actions;

import com.copybot.plugin.api.config.ConfigField;
import com.copybot.plugin.api.config.ConfigSchema;
import com.copybot.plugin.api.config.FieldHint;
import com.copybot.plugin.api.config.FieldKind;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/** The schemas of the embedded actions and their labels (spec desktop-ui §4). */
public class EmbeddedConfigSchemaTest {

    private static ConfigSchema writeSchema() {
        return new FileWriteAction().configSchema().orElseThrow().withKeyPrefix("plugin.embedded.file.write");
    }

    private static ConfigSchema readSchema() {
        return new FileReadAction().configSchema().orElseThrow().withKeyPrefix("plugin.embedded.file.read");
    }

    private static ConfigField field(ConfigSchema schema, String path) {
        return schema.field(path).orElseThrow(() -> new AssertionError("no field " + path));
    }

    private static List<String> jsonNames(FileWriteSettings.Named[] values) {
        return Arrays.stream(values).map(FileWriteSettings.Named::jsonName).toList();
    }

    @Test
    public void fileReadDescribesItsFilters() {
        ConfigSchema schema = readSchema();

        ConfigField path = field(schema, "path");
        assertEquals(FieldKind.PATH, path.kind());
        assertTrue(path.required());
        assertTrue(path.hasHint(FieldHint.DIRECTORY));
        assertEquals(FieldKind.BOOLEAN, field(schema, "recursive").kind());
        assertEquals("true", field(schema, "recursive").defaultValue());
        assertEquals(FieldKind.LIST, field(schema, "include").kind());
        assertEquals(FieldKind.STRING, field(schema, "include").elementSchema().kind());
        assertEquals(FieldKind.LIST, field(schema, "exclude").kind());
        assertEquals("false", field(schema, "includeHidden").defaultValue());
    }

    @Test
    public void fileWriteOffersTheValuesItAccepts() {
        ConfigSchema schema = writeSchema();

        assertTrue(field(schema, "outPattern").required());
        assertTrue(field(schema, "outPattern").hasHint(FieldHint.PATTERN));
        assertEquals(FieldKind.RECORD, field(schema, "onConflict").kind());
        assertEquals(jsonNames(FileWriteSettings.Compare.values()), field(schema, "onConflict.compare").enumValues());
        assertEquals(jsonNames(FileWriteSettings.Policy.values()), field(schema, "onConflict.ifIdentical").enumValues());
        assertEquals(jsonNames(FileWriteSettings.Policy.values()), field(schema, "onConflict.ifDifferent").enumValues());
        assertEquals(jsonNames(FileWriteSettings.WriteMode.values()), field(schema, "writeMode").enumValues());
        assertEquals(jsonNames(FileWriteSettings.Verify.values()), field(schema, "verify").enumValues());
        assertEquals(FieldKind.BOOLEAN, field(schema, "deleteSource").kind());
    }

    @Test
    public void theDefaultsShownAreTheOnesApplied() {
        ConfigSchema schema = writeSchema();
        FileWriteSettings defaults = FileWriteSettings.of(new Gson().fromJson("{\"outPattern\":\"x\"}", FileWriteConfig.class));

        assertEquals(defaults.compare().jsonName(), field(schema, "onConflict.compare").defaultValue());
        assertEquals(defaults.ifIdentical().jsonName(), field(schema, "onConflict.ifIdentical").defaultValue());
        assertEquals(defaults.ifDifferent().jsonName(), field(schema, "onConflict.ifDifferent").defaultValue());
        assertEquals(defaults.writeMode().jsonName(), field(schema, "writeMode").defaultValue());
        assertEquals(defaults.verify().jsonName(), field(schema, "verify").defaultValue());
        assertEquals(String.valueOf(defaults.deleteSource()), field(schema, "deleteSource").defaultValue());
    }

    /** Properties.load(InputStream) reads ISO-8859-1 and the backslash-u escapes, like ResourceBundle. */
    private static Properties bundle(String name) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = EmbeddedConfigSchemaTest.class.getResourceAsStream("/com/copybot/plugin/embedded/i18n/" + name)) {
            assertNotNull(in, name);
            properties.load(in);
        }
        return properties;
    }

    @Test
    public void everyFieldAndActionHasALabelAndADescriptionInBothBundles() throws IOException {
        for (String file : List.of("pluginBundle.properties", "pluginBundle_fr.properties")) {
            Properties properties = bundle(file);
            for (ConfigSchema schema : List.of(readSchema(), writeSchema())) {
                for (ConfigField field : schema.allFields()) {
                    assertNotNull(properties.getProperty(field.labelKey()), field.labelKey() + " in " + file);
                    assertNotNull(properties.getProperty(field.descriptionKey()), field.descriptionKey() + " in " + file);
                }
            }
            for (String action : List.of("file.read", "file.write")) {
                for (String suffix : List.of(".name", ".description")) {
                    String key = "plugin.embedded." + action + suffix;
                    assertNotNull(properties.getProperty(key), key + " in " + file);
                    assertFalse(properties.getProperty(key).contains("�"), key + " in " + file + " was re-encoded");
                }
            }
        }
    }
}

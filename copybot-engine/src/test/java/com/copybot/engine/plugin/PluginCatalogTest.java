package com.copybot.engine.plugin;

import com.copybot.engine.pipeline.StepType;
import com.copybot.plugin.api.action.ActionDefinition;
import com.copybot.plugin.api.action.IInAction;
import com.copybot.plugin.api.definition.IPlugin;
import com.copybot.resources.CombinedResourceBundle;
import com.copybot.plugin.api.config.ConfigField;
import com.copybot.plugin.api.config.ConfigSchema;
import com.copybot.plugin.api.config.FieldKind;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The catalog of the loaded actions (spec desktop-ui §4). PluginEngine.load runs once per JVM: whichever
 * load came first, the embedded plugin is loaded.
 */
public class PluginCatalogTest {

    @BeforeAll
    public static void loadPlugins(@TempDir Path tempDir) throws IOException {
        PluginEngine.load(Files.createDirectories(tempDir.resolve("plugins")), List.of());
    }

    private static CatalogAction embedded(String actionCode) {
        return PluginEngine.catalog().stream()
                .filter(a -> a.isEmbedded() && a.actionCode().equals(actionCode))
                .findFirst().orElseThrow(() -> new AssertionError("no embedded " + actionCode));
    }

    @Test
    public void theEmbeddedActionsAreListedUnderTheirStepType() {
        CatalogAction read = embedded("file.read");
        CatalogAction write = embedded("file.write");

        assertEquals(StepType.IN, read.stepType());
        assertEquals(StepType.OUT, write.stepType());
        assertEquals("embedded", read.pluginName());
        assertEquals("embedded", read.pluginCode());
        assertNull(read.pluginVersion(), "the embedded plugin has no version");
    }

    @Test
    public void namesAndDescriptionsAreLocalized() {
        for (CatalogAction action : List.of(embedded("file.read"), embedded("file.write"))) {
            assertNotEquals(action.actionCode(), action.name(), "a translated name");
            assertFalse(action.name().startsWith("%"), action.name());
            assertFalse(action.description().isEmpty(), action.actionCode());
        }
    }

    @Test
    public void theSchemaKeysArePrefixedAndTheirTextsResolved() {
        CatalogAction write = embedded("file.write");
        ConfigSchema schema = write.configSchema().orElseThrow();
        ConfigField compare = schema.field("onConflict.compare").orElseThrow();

        assertEquals("plugin.embedded.file.write.config.onConflict.compare.name", compare.labelKey());
        assertNotEquals("compare", write.label(compare), "a translated label");
        assertFalse(write.description(compare).isEmpty());
        for (ConfigField field : schema.allFields()) {
            assertTrue(write.texts().containsKey(field.labelKey()), field.labelKey());
        }
    }

    @Test
    public void aFieldWithoutLabelShowsItsName() {
        CatalogAction write = embedded("file.write");
        ConfigField unknown = new ConfigField("mystery", "mystery", FieldKind.STRING,
                false, null, Set.of(), "plugin.embedded.file.write.config.mystery.name",
                "plugin.embedded.file.write.config.mystery.description", List.of(), List.of(), null);

        assertEquals("mystery", write.label(unknown));
        assertEquals("", write.description(unknown));
    }

    @Test
    public void aPluginWhoseActionsCannotBeListedNeverHidesTheOthers() {
        IPlugin broken = new IPlugin() {
            @Override
            public String getPluginCode() {
                return "broken";
            }

            @Override
            public Iterable<String> getI18nBundleNames() {
                return List.of();
            }

            @Override
            public void setResourceBundle(CombinedResourceBundle resourceBundle) {
            }

            @Override
            public CombinedResourceBundle getResourceBundle() {
                return null;
            }

            @Override
            public List<ActionDefinition<? extends IInAction>> getInActions() {
                throw new NoClassDefFoundError("com/example/Missing");
            }
        };
        List<PluginDefinition> plugins = new ArrayList<>();
        plugins.add(PluginDefinition.ofEmbedded(broken));
        plugins.addAll(PluginEngine.getLoadedPlugins());

        List<CatalogAction> catalog = PluginEngine.catalogOf(plugins);

        assertTrue(catalog.stream().anyMatch(a -> a.isEmbedded() && a.actionCode().equals("file.read")),
                "the other plugins are still listed");
        assertTrue(catalog.stream().noneMatch(a -> "broken".equals(a.pluginCode())));
    }
}

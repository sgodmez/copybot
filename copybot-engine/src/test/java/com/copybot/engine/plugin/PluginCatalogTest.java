package com.copybot.engine.plugin;

import com.copybot.engine.pipeline.StepType;
import com.copybot.plugin.api.action.AbstractAction;
import com.copybot.plugin.api.action.AbstractActionWithConfig;
import com.copybot.plugin.api.action.ActionDefinition;
import com.copybot.plugin.api.action.IInAction;
import com.copybot.plugin.api.action.IOutAction;
import com.copybot.plugin.api.action.WorkItem;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

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
        return PluginEngine.catalog().actions().stream()
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
    public void theValuesOfAChoiceAreLocalizedTheUnknownOnesShownAsWritten() {
        CatalogAction write = embedded("file.write");
        ConfigField onMissingKey = write.configSchema().orElseThrow().field("onMissingKey").orElseThrow();

        String error = write.valueLabel(onMissingKey, "error");
        assertNotEquals("error", error, "a translated value");
        assertFalse(error.startsWith("%"), error);
        assertEquals("mystery", write.valueLabel(onMissingKey, "mystery"), "a value without label: as written");
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

    public record DemoConfig(String target) {
    }

    /** Describes no configuration. */
    public static final class Lister extends AbstractAction implements IInAction {
        @Override
        public void listFiles(Consumer<WorkItem> workItemConsumer) {
        }
    }

    public static final class Writer extends AbstractActionWithConfig<DemoConfig> implements IOutAction {
        @Override
        protected Class<DemoConfig> getConfigClass() {
            return DemoConfig.class;
        }

        @Override
        public void writeItem(WorkItem item) {
        }
    }

    public static final class Unbuildable extends AbstractAction implements IInAction {
        public Unbuildable() {
            throw new IllegalStateException("cannot be built");
        }

        @Override
        public void listFiles(Consumer<WorkItem> workItemConsumer) {
        }
    }

    /** A plugin with these out and in actions (declared in that order) and these texts. */
    private static IPlugin plugin(String code, Map<String, String> texts, List<ActionDefinition<? extends IOutAction>> out,
                                  List<ActionDefinition<? extends IInAction>> in) {
        CombinedResourceBundle bundle = new CombinedResourceBundle() {
            @Override
            protected Object handleGetObject(String key) {
                return texts.getOrDefault(key, "%" + key);
            }
        };
        return new IPlugin() {
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
            }

            @Override
            public CombinedResourceBundle getResourceBundle() {
                return bundle;
            }

            @Override
            public List<ActionDefinition<? extends IOutAction>> getOutActions() {
                return out;
            }

            @Override
            public List<ActionDefinition<? extends IInAction>> getInActions() {
                return in;
            }
        };
    }

    @Test
    public void aLoadedPluginIsListedInPluginThenStepTypeOrder() {
        IPlugin v2 = plugin("demo", Map.of(), List.of(new ActionDefinition<>("demo.write", Writer.class, false)),
                List.of(new ActionDefinition<>("demo.list", Lister.class, false)));
        IPlugin v1 = plugin("demo", Map.of(), List.of(),
                List.of(new ActionDefinition<>("demo.list", Lister.class, false)));

        List<CatalogAction> catalog = PluginEngine.catalogOf(List.of(
                PluginDefinition.ofLoaded("com.acme.demo", "2.0.0", v2),
                PluginDefinition.ofLoaded("com.acme.demo", "1.0.0", v1))).actions();

        assertEquals(List.of("2.0.0 IN demo.list", "2.0.0 OUT demo.write", "1.0.0 IN demo.list"),
                catalog.stream().map(a -> a.pluginVersion() + " " + a.stepType() + " " + a.actionCode()).toList());
        CatalogAction write = catalog.get(1);
        assertEquals("com.acme.demo", write.pluginName());
        assertEquals("demo", write.pluginCode());
        assertFalse(write.isEmbedded());
        assertEquals("plugin.demo.demo.write.config.target.name",
                write.configSchema().orElseThrow().field("target").orElseThrow().labelKey());
    }

    @Test
    public void anActionWithoutSchemaOrTextsShowsItsCode() {
        IPlugin demo = plugin("demo", Map.of(), List.of(), List.of(new ActionDefinition<>("demo.list", Lister.class, false)));

        CatalogAction list = PluginEngine.catalogOf(List.of(PluginDefinition.ofLoaded("com.acme.demo", "1.0.0", demo)))
                .actions().getFirst();

        assertEquals(Optional.empty(), list.configSchema(), "the editor keeps its JSON as is");
        assertEquals("demo.list", list.name());
        assertEquals("", list.description());
        assertEquals(Map.of(), list.texts());
    }

    @Test
    public void aTextStartingWithPercentIsATranslation() {
        IPlugin demo = plugin("demo", Map.of(
                        "plugin.demo.demo.write.name", "%Write",
                        "plugin.demo.demo.write.config.target.description", "% of the free space"),
                List.of(new ActionDefinition<>("demo.write", Writer.class, false)), List.of());

        CatalogAction write = PluginEngine.catalogOf(List.of(PluginDefinition.ofLoaded("com.acme.demo", "1.0.0", demo)))
                .actions().getFirst();

        assertEquals("%Write", write.name());
        assertEquals("% of the free space",
                write.description(write.configSchema().orElseThrow().field("target").orElseThrow()));
    }

    @Test
    public void aBrokenPluginOrActionIsWarnedOnce() {
        IPlugin broken = plugin("broken", Map.of(), List.of(), null); // getInActions() fails
        IPlugin unbuildable = plugin("unbuildable", Map.of(), List.of(),
                List.of(new ActionDefinition<>("demo.list", Unbuildable.class, false)));
        List<PluginDefinition> plugins = List.of(PluginDefinition.ofLoaded("com.acme.broken.once", "1.0.0", broken),
                PluginDefinition.ofLoaded("com.acme.unbuildable.once", "1.0.0", unbuildable));
        Logger jul = Logger.getLogger(PluginEngine.class.getCanonicalName());
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        jul.addHandler(handler);
        try {
            PluginEngine.catalogOf(plugins);
            PluginEngine.catalogOf(plugins); // e.g. the editor opened again
        } finally {
            jul.removeHandler(handler);
        }

        assertEquals(2, records.stream().filter(r -> r.getLevel() == Level.WARNING).count(),
                "one warning for the plugin, one for the action: " + records.stream().map(LogRecord::getMessage).toList());
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

        List<CatalogAction> catalog = PluginEngine.catalogOf(plugins).actions();

        assertTrue(catalog.stream().anyMatch(a -> a.isEmbedded() && a.actionCode().equals("file.read")),
                "the other plugins are still listed");
        assertTrue(catalog.stream().noneMatch(a -> "broken".equals(a.pluginCode())));
    }

    @Test
    public void aLoadedVersionWhoseActionsCannotBeListedIsAFailedPlugin() {
        IPlugin v2 = plugin("demo", Map.of(), List.of(), null); // getInActions() fails
        IPlugin v1 = plugin("demo", Map.of(), List.of(), List.of(new ActionDefinition<>("demo.list", Lister.class, false)));

        PluginCatalog catalog = PluginEngine.catalogOf(List.of(
                PluginDefinition.ofLoaded("com.acme.failed", "2.0.0", v2),
                PluginDefinition.ofLoaded("com.acme.failed", "1.0.0", v1)));

        // PluginEngine.resolve still picks 2.0.0 for a step without version: the editor must know it
        assertEquals(List.of(new PluginCatalog.FailedPlugin("com.acme.failed", "2.0.0")), catalog.failedPlugins());
        assertEquals(List.of("1.0.0 demo.list"),
                catalog.actions().stream().map(a -> a.pluginVersion() + " " + a.actionCode()).toList());
    }
}

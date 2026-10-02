package com.copybot.engine.plugin.loader;

import com.copybot.engine.plugin.PluginDefinition;
import com.copybot.engine.plugin.report.ModuleEntry;
import com.copybot.engine.plugin.report.PluginSource;
import com.copybot.plugin.embedded.CBEmbeddedPlugin;
import com.copybot.resources.ResourcesEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * One broken plugin folder must never prevent the others from loading: it is reported as an error plugin, with a
 * message saying why. The plugins are real module jars, compiled against the engine ({@link TestPluginJar}).
 */
public class PluginLoaderTest {

    // not a @TempDir: the JVM keeps the jars of a module layer open, which Windows cannot delete (see newRoot)
    Path plugins;

    @BeforeEach
    public void createPluginsDir() {
        plugins = TestPluginJar.newRoot("loader");
    }

    private List<PluginDefinition> load(Path... folders) {
        PluginLoader loader = new PluginLoader();
        loader.resolve(List.of(folders), false);
        return loader.load();
    }

    private List<PluginDefinition> loadDev(Path... devDirs) {
        PluginLoader loader = new PluginLoader();
        loader.resolve(List.of(devDirs), true);
        return loader.load();
    }

    private static Optional<PluginDefinition> named(List<PluginDefinition> definitions, String name) {
        return definitions.stream().filter(d -> name.equals(d.getName())).findFirst();
    }

    private static PluginDefinition loaded(List<PluginDefinition> definitions, String name) {
        PluginDefinition definition = named(definitions, name).orElseThrow(() -> new AssertionError(name + " not reported: " + definitions));
        assertNull(definition.getErrorMessage(), name + " should be loaded");
        assertNotNull(definition.getPluginInstance());
        return definition;
    }

    private static PluginDefinition error(List<PluginDefinition> definitions, String name) {
        PluginDefinition definition = named(definitions, name).orElseThrow(() -> new AssertionError(name + " not reported: " + definitions));
        assertNotNull(definition.getErrorMessage(), name + " should be in error");
        assertFalse(definition.getErrorMessage().isBlank());
        assertFalse(definition.isActive());
        assertFalse(definition.isIgnored(), name + " is a failure, not a plugin set aside");
        return definition;
    }

    @Test
    public void aWellFormedPluginIsLoaded() {
        Path good = plugins.resolve("good");
        Path jar = TestPluginJar.plugin("test.good").version("1.0").writeTo(good);

        PluginDefinition definition = loaded(load(good), "test.good");

        assertEquals("1.0", definition.getVersion());
        assertEquals(PluginSource.PLUGIN_PATH, definition.getSource());
        assertFalse(definition.isIgnored());
        assertEquals(List.of(new ModuleEntry("test.good", "1.0", jar.toAbsolutePath().normalize(), true, false)),
                definition.getModules());
        assertEquals(List.of(), definition.getPluginDependencies());
        assertEquals(List.of(), definition.getMissingRequires());
    }

    @Test
    public void aDevPluginIsMarkedDev() {
        Path dev = TestPluginJar.plugin("test.dev").version("2.0").writeDevDir(plugins.resolve("dev-target"));

        PluginDefinition definition = loaded(loadDev(dev), "test.dev");

        assertEquals(PluginSource.DEV, definition.getSource());
        assertEquals(dev.resolve("classes").toAbsolutePath().normalize(), definition.getModules().getFirst().location());
    }

    @Test
    public void theEmbeddedPluginIsEmbedded() {
        PluginDefinition embedded = loaded(load(), CBEmbeddedPlugin.EMBEDDED_PLUGN_NAME);

        assertEquals(PluginSource.EMBEDDED, embedded.getSource());
        assertEquals(List.of(), embedded.getModules());
    }

    @Test
    public void aFolderWithoutPluginIsAnErrorNamedAfterTheFolder() throws Exception {
        Path empty = Files.createDirectories(plugins.resolve("empty"));
        Path good = plugins.resolve("good");
        TestPluginJar.plugin("test.good").version("1.0").writeTo(good);

        List<PluginDefinition> definitions = load(empty, good);

        error(definitions, "empty");
        loaded(definitions, "test.good");
    }

    @Test
    public void aFolderWithTwoPluginsIsAnErrorNamedAfterTheFolder() {
        Path two = plugins.resolve("two");
        TestPluginJar.plugin("test.one").version("1.0").writeTo(two);
        TestPluginJar.plugin("test.other").version("1.0").writeTo(two);
        Path good = plugins.resolve("good");
        TestPluginJar.plugin("test.good").version("1.0").writeTo(good);

        List<PluginDefinition> definitions = load(two, good);

        error(definitions, "two");
        loaded(definitions, "test.good");
    }

    @Test
    public void aPluginWithoutVersionIsLoaded() {
        Path unversioned = plugins.resolve("unversioned");
        TestPluginJar.plugin("test.unversioned").writeTo(unversioned);

        PluginDefinition definition = loaded(load(unversioned), "test.unversioned");

        assertNull(definition.getVersion());
    }

    @Test
    public void aVersionedPluginWinsOverTheSameWithoutVersionInEitherOrder() {
        Path unversioned = plugins.resolve("unversioned");
        TestPluginJar.plugin("test.same").writeTo(unversioned);
        Path versioned = plugins.resolve("versioned");
        TestPluginJar.plugin("test.same").version("1.0").writeTo(versioned);

        for (List<PluginDefinition> definitions : List.of(load(unversioned, versioned), load(versioned, unversioned))) {
            List<PluginDefinition> same = definitions.stream().filter(d -> d.getName().equals("test.same")).toList();
            assertEquals(2, same.size(), "both are reported: " + same);
            PluginDefinition active = same.stream().filter(PluginDefinition::isActive).findFirst().orElseThrow();
            assertEquals("1.0", active.getVersion());
            PluginDefinition rejected = same.stream().filter(d -> !d.isActive()).findFirst().orElseThrow();
            assertNull(rejected.getVersion());
            assertNotNull(rejected.getErrorMessage());
            assertTrue(rejected.isIgnored(), "set aside for the versioned one, not a failure");
        }
    }

    @Test
    public void anOlderRevisionAndADuplicateAreIgnored() {
        TestPluginJar.plugin("test.same").version("1.0.0").writeTo(plugins.resolve("a-old"));
        TestPluginJar.plugin("test.same").version("1.0.1").writeTo(plugins.resolve("b-new"));
        TestPluginJar.plugin("test.same").version("1.0.1").writeTo(plugins.resolve("c-dup"));

        List<PluginDefinition> same = load(plugins.resolve("a-old"), plugins.resolve("b-new"), plugins.resolve("c-dup")).stream()
                .filter(p -> p.getName().equals("test.same")).toList();

        assertEquals(1, same.stream().filter(PluginDefinition::isActive).count());
        assertEquals(2, same.stream().filter(PluginDefinition::isIgnored).count());
        assertTrue(same.stream().filter(PluginDefinition::isIgnored).allMatch(p -> p.getErrorMessage() != null));
    }

    @Test
    public void aMissingDependencyIsNamedButNotThePresentOnes() {
        Path elsewhere = plugins.resolve("not-installed");
        Path missingJar = TestPluginJar.library("test.missing", "test.missing").writeTo(elsewhere); // unversioned
        Path present = plugins.resolve("present");
        Path presentJar = TestPluginJar.plugin("test.present").version("1.0").writeTo(present);
        Path needy = plugins.resolve("needy");
        TestPluginJar.plugin("test.needy").version("1.0")
                .requires("test.missing", missingJar)
                .requires("test.present", presentJar)
                .writeTo(needy);

        List<PluginDefinition> definitions = load(present, needy);

        loaded(definitions, "test.present");
        PluginDefinition needyDefinition = error(definitions, "test.needy");
        String message = needyDefinition.getErrorMessage();
        assertTrue(message.contains("test.missing"), message);
        assertFalse(message.contains("test.present"), message);
        assertEquals(List.of("test.missing"), needyDefinition.getMissingRequires());
    }

    @Test
    public void aPluginRequiringAnotherIsLoadedOnTopOfIt() {
        Path base = plugins.resolve("base");
        Path baseJar = TestPluginJar.plugin("test.base").version("1.2").writeTo(base);
        Path child = plugins.resolve("child");
        TestPluginJar.plugin("test.child").version("1.0").requires("test.base", baseJar).writeTo(child);

        List<PluginDefinition> definitions = load(child, base);

        loaded(definitions, "test.base");
        assertEquals(List.of("test.base 1.2"), loaded(definitions, "test.child").getPluginDependencies());
    }

    @Test
    public void aCorruptJarIsAnErrorOfItsFolderOnly() throws Exception {
        Path corrupt = Files.createDirectories(plugins.resolve("corrupt"));
        Files.writeString(corrupt.resolve("broken.jar"), "not a zip");
        Path good = plugins.resolve("good");
        TestPluginJar.plugin("test.good").version("1.0").writeTo(good);

        List<PluginDefinition> definitions = load(corrupt, good);

        error(definitions, "corrupt");
        loaded(definitions, "test.good");
    }

    @Test
    public void aPluginWhoseLayerCannotBeDefinedIsAnErrorOfItsOwn() {
        Path split = plugins.resolve("split");
        TestPluginJar.plugin("test.split").version("1.0").writeTo(split);
        TestPluginJar.library("test.lib.a", "test.shared").writeTo(split.resolve("lib"));
        TestPluginJar.library("test.lib.b", "test.shared").writeTo(split.resolve("lib"));
        Path good = plugins.resolve("good");
        TestPluginJar.plugin("test.good").version("1.0").writeTo(good);

        List<PluginDefinition> definitions = load(split, good);

        error(definitions, "test.split");
        loaded(definitions, "test.good");
    }

    @Test
    public void aPluginWhoseConstructorThrowsIsAnErrorOfItsOwn() {
        Path failing = plugins.resolve("failing");
        TestPluginJar.plugin("test.failing").version("1.0").failingConstructor().writeTo(failing);
        Path good = plugins.resolve("good");
        TestPluginJar.plugin("test.good").version("1.0").writeTo(good);

        List<PluginDefinition> definitions = load(failing, good);

        assertTrue(error(definitions, "test.failing").getErrorMessage().contains("boom"));
        loaded(definitions, "test.good");
    }

    @Test
    public void aPluginWithAMissingI18nBundleIsAnErrorOfItsOwn() {
        Path noBundle = plugins.resolve("no-bundle");
        TestPluginJar.plugin("test.nobundle").version("1.0").missingI18nBundle().writeTo(noBundle);
        Path good = plugins.resolve("good");
        TestPluginJar.plugin("test.good").version("1.0").writeTo(good);

        List<PluginDefinition> definitions = load(noBundle, good);

        String message = error(definitions, "test.nobundle").getErrorMessage();
        assertTrue(message.contains("test.nobundle.missing"), message);
        loaded(definitions, "test.good");
    }

    @Test
    public void aPluginWhoseConstructorThrowsStillListsThePluginsItWasLoadedOver() {
        Path base = plugins.resolve("base");
        Path baseJar = TestPluginJar.plugin("test.base").version("1.0").writeTo(base);
        Path top = plugins.resolve("top");
        TestPluginJar.plugin("test.top").version("1.0").requires("test.base", baseJar).failingConstructor().writeTo(top);

        PluginDefinition definition = error(load(base, top), "test.top");

        assertEquals(List.of("test.base 1.0"), definition.getPluginDependencies());
    }

    @Test
    public void aPluginWhoseLayerCannotBeDefinedStillListsThePluginsItWasLoadedOver() {
        Path base = plugins.resolve("base");
        Path baseJar = TestPluginJar.plugin("test.base").version("1.0").writeTo(base);
        Path clash = plugins.resolve("clash");
        TestPluginJar.plugin("test.clash").version("1.0").requires("test.base", baseJar).writeTo(clash);
        TestPluginJar.library("test.clashlib", "test.clash").writeTo(clash); // the package of the plugin, in another module

        List<PluginDefinition> definitions = load(base, clash);

        loaded(definitions, "test.base");
        PluginDefinition definition = error(definitions, "test.clash");
        String prefix = ResourcesEngine.getString("plugin.load.layer", "").strip();
        assertTrue(definition.getErrorMessage().startsWith(prefix), definition.getErrorMessage());
        assertEquals(List.of("test.base 1.0"), definition.getPluginDependencies());
    }

    private static ModuleDescriptor.Requires requires(String name, String version) {
        ModuleDescriptor.Builder builder = ModuleDescriptor.newModule("requirer");
        if (version == null) {
            builder.requires(name);
        } else {
            builder.requires(Set.of(), name, ModuleDescriptor.Version.parse(version));
        }
        return builder.build().requires().stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
    }

    private static ModuleDescriptor module(String name, String version) {
        return ModuleDescriptor.newModule(name).version(version).build();
    }

    @Test
    public void theMissingDependenciesAreTheOnesNoLoadedModuleProvides() {
        List<ModuleDescriptor.Requires> requires = List.of(
                requires("com.example.present", "1.2"),
                requires("com.example.absent", "2.0"),
                requires("com.example.tooOld", "1.5"),
                requires("com.example.noVersion", null));
        List<ModuleDescriptor> loaded = List.of(module("com.example.present", "1.3"), module("com.example.tooOld", "1.4"));

        assertEquals("com.example.absent:2.0, com.example.tooOld:1.5, com.example.noVersion",
                PluginLoader.missingDependencies(requires, loaded));
    }

    @Test
    public void aDependencyMissedBySeveralModulesOfThePluginIsNamedOnce() {
        // the requirements are those of every module of the plugin folder: two of them may require the same module
        List<ModuleDescriptor.Requires> requires = List.of(requires("com.example.absent", "2.0"), requires("com.example.absent", "2.0"));

        assertEquals("com.example.absent:2.0", PluginLoader.missingDependencies(requires, List.of()));
    }

    @Test
    public void theLoadMessagesExistInBothEngineBundles() throws IOException {
        List<String> keys = List.of("plugin.load.no-module", "plugin.load.many-modules", "plugin.load.newer-revision",
                "plugin.load.duplicate", "plugin.load.missing-dependencies", "plugin.load.unreadable",
                "plugin.load.layer", "plugin.load.instantiation", "plugin.load.not-loaded",
                "plugin.report.path-missing", "plugin.report.dev-path-missing", "plugin.report.actions-failed");
        for (String file : List.of("engineBundle.properties", "engineBundle_fr.properties")) {
            Properties properties = new Properties();
            try (InputStream in = PluginLoaderTest.class.getResourceAsStream("/com/copybot/engine/i18n/" + file)) {
                assertNotNull(in, file);
                properties.load(in); // ISO-8859-1 and backslash-u escapes, like ResourceBundle
            }
            for (String key : keys) {
                String value = properties.getProperty(key);
                assertNotNull(value, key + " in " + file);
                assertFalse(value.contains("�"), key + " in " + file + " was re-encoded");
            }
        }
        for (String key : keys) {
            assertFalse(ResourcesEngine.getString(key, "a", "b", "c").startsWith("%"), key);
        }
    }

    @Test
    public void theEngineBundlesAreAsciiWithUnicodeEscapes() throws IOException {
        for (String file : List.of("engineBundle.properties", "engineBundle_fr.properties")) {
            try (InputStream in = PluginLoaderTest.class.getResourceAsStream("/com/copybot/engine/i18n/" + file)) {
                assertNotNull(in, file);
                byte[] bytes = in.readAllBytes();
                for (int i = 0; i < bytes.length; i++) {
                    assertTrue(bytes[i] >= 0, file + ": non-ASCII byte at offset " + i);
                }
            }
        }
    }
}

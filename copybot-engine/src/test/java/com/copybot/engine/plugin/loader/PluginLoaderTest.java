package com.copybot.engine.plugin.loader;

import com.copybot.resources.ResourcesEngine;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleDescriptor;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class PluginLoaderTest {

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
    public void theLoadMessagesExistInBothEngineBundles() throws IOException {
        List<String> keys = List.of("plugin.load.no-module", "plugin.load.many-modules", "plugin.load.newer-revision",
                "plugin.load.duplicate", "plugin.load.missing-dependencies");
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
            assertFalse(ResourcesEngine.getString(key, "a", "b").startsWith("%"), key);
        }
    }
}

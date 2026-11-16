package com.copybot.plugin.metadataextractor;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/** The texts of the plugin exist in every language (en, fr, it) with the same keys. */
public class PluginBundleTest {

    private static Properties bundle(String name) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = PluginBundleTest.class.getResourceAsStream("/com/copybot/plugin/metadataextractor/i18n/" + name)) {
            assertNotNull(in, name);
            properties.load(in);
        }
        return properties;
    }

    @Test
    public void everyBundleHasTheSameKeys() throws IOException {
        TreeSet<String> english = new TreeSet<>(bundle("pluginBundle.properties").stringPropertyNames());
        for (String file : new String[]{"pluginBundle_fr.properties", "pluginBundle_it.properties"}) {
            assertEquals(english, new TreeSet<>(bundle(file).stringPropertyNames()), file);
        }
    }
}

package com.copybot.resources;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The engine messages and the texts of the embedded plugin exist in every language (en, fr, it) with the same keys and
 * the same placeholders.
 */
public class EngineBundleParityTest {

    private static final String BASE = "com.copybot.engine.i18n.engineBundle";
    private static final String ENGINE_DIR = "/com/copybot/engine/i18n/";
    private static final String EMBEDDED_DIR = "/com/copybot/plugin/embedded/i18n/";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)}");
    private static final Pattern LONE_APOSTROPHE = Pattern.compile("(?<!')'(?!')");

    private record Family(String dir, String prefix) {
        List<String> files() {
            return List.of(prefix + ".properties", prefix + "_fr.properties", prefix + "_it.properties");
        }
    }

    private static final List<Family> FAMILIES = List.of(
            new Family(ENGINE_DIR, "engineBundle"), new Family(EMBEDDED_DIR, "pluginBundle"));

    /** The raw bytes, to check the file is ASCII (accents as backslash-u escapes), as the bundle rules ask. */
    private static byte[] bytes(String dir, String name) throws IOException {
        try (InputStream in = EngineBundleParityTest.class.getResourceAsStream(dir + name)) {
            assertNotNull(in, dir + name);
            return in.readAllBytes();
        }
    }

    private static Properties bundle(String dir, String name) throws IOException {
        Properties properties = new Properties();
        properties.load(new ByteArrayInputStream(bytes(dir, name)));
        return properties;
    }

    @Test
    public void everyBundleHasTheSameKeys() throws IOException {
        for (Family family : FAMILIES) {
            TreeSet<String> english = new TreeSet<>(bundle(family.dir(), family.files().get(0)).stringPropertyNames());
            for (String file : family.files()) {
                assertEquals(english, new TreeSet<>(bundle(family.dir(), file).stringPropertyNames()), "keys of " + file);
            }
        }
    }

    @Test
    public void everyBundleIsAscii() throws IOException {
        for (Family family : FAMILIES) {
            for (String file : family.files()) {
                String text = new String(bytes(family.dir(), file), StandardCharsets.ISO_8859_1);
                assertTrue(text.chars().allMatch(c -> c < 0x80), file + " must be ASCII (use backslash-u escapes)");
            }
        }
    }

    @Test
    public void everyValueIsAValidMessageFormatWithoutALoneApostrophe() throws IOException {
        // the embedded plugin texts are read as they are (name, description) except the ones with a placeholder
        for (Family family : FAMILIES) {
            for (String file : family.files()) {
                Properties properties = bundle(family.dir(), file);
                for (String key : properties.stringPropertyNames()) {
                    String value = properties.getProperty(key);
                    assertFalse(value.isBlank(), key + " in " + file);
                    if (family.dir().equals(ENGINE_DIR) || hasPlaceholder(value)) {
                        assertDoesNotThrow(() -> new MessageFormat(value), key + " in " + file);
                        assertFalse(LONE_APOSTROPHE.matcher(value).find(),
                                "lone apostrophe (write '') in " + key + " of " + file);
                    }
                }
            }
        }
    }

    /**
     * A text asked without argument is returned as it is by the resource bundle (no MessageFormat): a doubled apostrophe
     * would show twice. A value without placeholder holds none in the engine bundles (typographic one), and none doubled
     * in the embedded plugin texts.
     */
    @Test
    public void aValueWithoutPlaceholderShowsNoDoubledApostrophe() throws IOException {
        for (Family family : FAMILIES) {
            for (String file : family.files()) {
                Properties properties = bundle(family.dir(), file);
                for (String key : properties.stringPropertyNames()) {
                    String value = properties.getProperty(key);
                    if (hasPlaceholder(value)) {
                        continue;
                    }
                    if (family.dir().equals(ENGINE_DIR)) {
                        assertFalse(value.contains("'"), "apostrophe in " + key + " of " + file + " (use the typographic one)");
                    } else {
                        assertFalse(value.contains("''"), "doubled apostrophe in " + key + " of " + file);
                    }
                }
            }
        }
    }

    @Test
    public void everyTranslationKeepsThePlaceholdersOfTheEnglishText() throws IOException {
        for (Family family : FAMILIES) {
            Properties english = bundle(family.dir(), family.files().get(0));
            for (String file : family.files()) {
                Properties properties = bundle(family.dir(), file);
                for (String key : english.stringPropertyNames()) {
                    assertEquals(placeholders(english.getProperty(key)), placeholders(properties.getProperty(key)),
                            key + " in " + file);
                }
            }
        }
    }

    /** The languages resolve through ResourceBundle, so the italian file is really picked for the italian locale. */
    @Test
    public void theItalianBundleIsLoadedForTheItalianLocale() {
        ResourceBundle italian = ResourceBundle.getBundle(BASE, Locale.ITALIAN);
        assertTrue(italian.getString("engine.closed").startsWith("Il motore "), italian.getString("engine.closed"));
        assertTrue(ResourceBundle.getBundle(BASE, Locale.FRENCH).getString("engine.closed").startsWith("Le moteur "));
        ResourceBundle embedded = ResourceBundle.getBundle("com.copybot.plugin.embedded.i18n.pluginBundle", Locale.ITALIAN);
        assertTrue(embedded.getString("plugin.embedded.file.buffer.name").startsWith("Buffer"));
    }

    private static boolean hasPlaceholder(String value) {
        return PLACEHOLDER.matcher(value).find();
    }

    private static Map<String, Integer> placeholders(String value) {
        Map<String, Integer> counts = new TreeMap<>();
        Matcher matcher = PLACEHOLDER.matcher(value);
        while (matcher.find()) {
            counts.merge(matcher.group(1), 1, Integer::sum);
        }
        return counts;
    }
}

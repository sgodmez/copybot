package com.copybot.resources;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.text.MessageFormat;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/** The messages of the safe write and the read filters exist in both engine bundles (spec safe-write §8). */
public class SafeWriteBundleTest {

    static final List<String> KEYS = List.of(
            "write.skip.identical",
            "write.pattern.missing-key",
            "write.skip.missing-key",
            "write.skip.exists",
            "write.conflict.error",
            "write.verify.size",
            "write.verify.hash",
            "write.delete-source.failed",
            "write.config.unknown-value",
            "write.config.no-out-pattern",
            "write.warn.delete-without-read-back",
            "read.config.invalid-glob",
            "read.config.no-path",
            "write.skip.same-file",
            "write.error.io-detail",
            "write.warn.direct-mode",
            "pattern.syntax",
            "dryrun.filtered",
            "dryrun.unsupported",
            "dryrun.failed");

    /** Properties.load(InputStream) reads ISO-8859-1 and the backslash-u escapes, like ResourceBundle. */
    private static Properties bundle(String name) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = SafeWriteBundleTest.class.getResourceAsStream("/com/copybot/engine/i18n/" + name)) {
            assertNotNull(in, name);
            properties.load(in);
        }
        return properties;
    }

    /** Guards the escapes: raw UTF-8 bytes in the ISO-8859-1 French bundle would read as "Ã©". */
    @Test
    public void thePatternSyntaxReasonsAreReadWithTheirAccentsInFrench() throws IOException {
        Properties french = bundle("engineBundle_fr.properties");
        assertEquals("accolade non fermée", french.getProperty("pattern.syntax.unclosed-brace"));
        assertEquals("texte après une valeur fixe", french.getProperty("pattern.syntax.text-after-quote"));
        assertTrue(french.getProperty("pattern.syntax").contains("invalide à la position"));
    }

    @Test
    public void everyKeyIsInBothBundlesAndIsAValidMessageFormat() throws IOException {
        for (String file : List.of("engineBundle.properties", "engineBundle_fr.properties")) {
            Properties properties = bundle(file);
            for (String key : KEYS) {
                String value = properties.getProperty(key);
                assertNotNull(value, key + " in " + file);
                assertDoesNotThrow(() -> new MessageFormat(value), key + " in " + file);
                assertFalse(value.contains("�"), key + " in " + file + " was re-encoded");
            }
        }
    }

    @Test
    public void everyKeyIsResolvedByTheEngine() {
        for (String key : KEYS) {
            assertFalse(ResourcesEngine.getString(key, "a", "b", "c").startsWith("%"), key);
        }
    }
}

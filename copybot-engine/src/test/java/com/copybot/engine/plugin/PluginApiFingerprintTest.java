package com.copybot.engine.plugin;

import com.copybot.engine.plugin.PluginApiFingerprint.Verdict;
import com.copybot.utils.VersionUtil;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The guard of the plugin API version: the API the engine exports now against the committed fingerprint
 * ({@value PluginApiFingerprint#FILE}). With {@code -Dplugin.api.update=true}, rewrites the fingerprint when the
 * version fits the changes.
 */
public class PluginApiFingerprintTest {

    private static String apiVersion() {
        String version = System.getProperty("copybot.plugin.api.version");
        assertNotNull(version, "the build passes copybot.plugin.api.version (pom of copybot-engine)");
        return version;
    }

    private static Path file() {
        String file = System.getProperty("plugin.api.fingerprint");
        assertNotNull(file, "the build passes plugin.api.fingerprint (pom of copybot-engine)");
        return Path.of(file);
    }

    @Test
    public void theApiVersionIsTheVersionOfTheEngineModule() {
        assertEquals(Optional.of(apiVersion()), VersionUtil.engineVersion(),
                "the engine module must carry copybot.plugin.api.version: rebuild with mvn clean after changing it");
    }

    @Test
    public void theFingerprintIsTheExportedApiOnly() throws IOException {
        PluginApiFingerprint current = PluginApiFingerprint.of(VersionUtil.class, apiVersion());

        assertTrue(current.lines().stream().anyMatch(l -> l.startsWith("com.copybot.plugin.api.definition.IPlugin type ")));
        assertTrue(current.lines().contains("com.copybot.utils.VersionUtil method public static "
                + "java.util.Optional<java.lang.String> engineVersion()"), "a method, without its declaring type");
        assertFalse(current.lines().stream().anyMatch(l -> l.startsWith("com.copybot.engine.")),
                "packages exported to some modules only are not plugin API");
        assertFalse(current.lines().stream().anyMatch(l -> l.contains("Test ") || l.contains("Test$")),
                "the test classes patched into the module are not API");
        assertFalse(current.lines().stream().anyMatch(l -> l.contains(" private ")));
    }

    @Test
    public void theApiMatchesItsFingerprint() throws IOException {
        PluginApiFingerprint current = PluginApiFingerprint.of(VersionUtil.class, apiVersion());
        Path file = file();
        PluginApiFingerprint.Update update = PluginApiFingerprint.Update.of(System.getProperty("plugin.api.update"));

        if (!Files.exists(file)) {
            if (update.allows(null)) {
                write(file, current, "(no previous file)\n");
                return;
            }
            fail(file + " is missing: restore it from git; only `" + PluginApiFingerprint.FORCE_COMMAND
                    + "` creates it anew");
        }
        PluginApiFingerprint committed = PluginApiFingerprint.parse(Files.readString(file, StandardCharsets.UTF_8));
        Verdict verdict = PluginApiFingerprint.check(committed, current);
        if (update.allows(verdict.status())) {
            write(file, current, PluginApiFingerprint.diff(committed, current));
            return;
        }
        assertEquals(Verdict.Status.UP_TO_DATE, verdict.status(), verdict.message());
    }

    /** Writes the fingerprint and prints what changed, to read before committing (above all when forced). */
    private static void write(Path file, PluginApiFingerprint fingerprint, String diff) throws IOException {
        Files.writeString(file, fingerprint.text(), StandardCharsets.UTF_8);
        System.out.println("Plugin API " + fingerprint.version() + " written to " + file + ", changes:\n"
                + (diff.isEmpty() ? "(none)\n" : diff));
    }
}

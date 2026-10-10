package com.copybot.config;

import com.copybot.exception.CopybotException;
import com.copybot.resources.ResourcesEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class ConfigFilesTest {

    @TempDir
    Path tempDir;

    @Test
    public void otherKeysArePreserved() throws Exception {
        Path config = tempDir.resolve("config.json");
        Files.writeString(config, """
                {
                  "devPluginPaths": "dev/target",
                  "resources": {"cpu": 4, "disk:*": 2},
                  "resourceGroups": [["disk:D", "disk:E"]]
                }""");

        ConfigFiles.writePluginPath(config, Path.of("D:/copybot/plugins"));

        String text = Files.readString(config);
        assertEquals(Optional.of(Path.of("D:/copybot/plugins")), ConfigFiles.readPluginPath(config));
        assertTrue(text.contains("\"devPluginPaths\": \"dev/target\""), text);
        assertTrue(text.contains("\"cpu\": 4"), text);
        assertTrue(text.contains("\"disk:E\""), text);
    }

    @Test
    public void aNullPathRemovesTheKey() throws Exception {
        Path config = tempDir.resolve("config.json");
        Files.writeString(config, "{\"pluginPath\": \"somewhere\", \"devPluginPaths\": \"dev\"}");

        ConfigFiles.writePluginPath(config, null);

        assertEquals(Optional.empty(), ConfigFiles.readPluginPath(config));
        assertFalse(Files.readString(config).contains("pluginPath\""));
        assertTrue(Files.readString(config).contains("devPluginPaths"));
    }

    @Test
    public void crlfIsKept() throws Exception {
        Path config = tempDir.resolve("config.json");
        Files.writeString(config, "{\r\n  \"devPluginPaths\": \"dev\"\r\n}");

        ConfigFiles.writePluginPath(config, Path.of("p"));

        String text = Files.readString(config);
        assertTrue(text.contains("\r\n"), text);
        assertFalse(text.replace("\r\n", "").contains("\n"), "every line ends with CRLF: " + text);
    }

    @Test
    public void lfIsKept() throws Exception {
        Path config = tempDir.resolve("config.json");
        Files.writeString(config, "{\n  \"devPluginPaths\": \"dev\"\n}");

        ConfigFiles.writePluginPath(config, Path.of("p"));

        assertFalse(Files.readString(config).contains("\r"));
    }

    @Test
    public void anEmptyObjectGetsTheKey() throws Exception {
        Path config = tempDir.resolve("config.json");
        Files.writeString(config, "{}");

        ConfigFiles.writePluginPath(config, Path.of("p"));

        assertEquals(Optional.of(Path.of("p")), ConfigFiles.readPluginPath(config));
    }

    @Test
    public void aReadOnlyFileIsRefusedAndLeftUntouched() throws Exception {
        Path config = tempDir.resolve("config.json");
        String original = "{\"devPluginPaths\": \"dev\"}";
        Files.writeString(config, original);
        assertTrue(config.toFile().setWritable(false));
        try {
            assertThrows(CopybotException.class, () -> ConfigFiles.writePluginPath(config, Path.of("p")));
            assertEquals(original, Files.readString(config));
        } finally {
            config.toFile().setWritable(true);
        }
    }

    @Test
    public void aCommentedFileLosesContentOnRewrite() throws Exception {
        Path config = tempDir.resolve("config.json");
        Files.writeString(config, "{\n  // where the plugins are\n  \"pluginPath\": \"p\"\n}");

        assertTrue(ConfigFiles.rewriteLosesContent(config));
    }

    @Test
    public void aDuplicateKeyLosesContentOnRewrite() throws Exception {
        Path config = tempDir.resolve("config.json");
        Files.writeString(config, "{\"resources\": {\"cpu\": 4, \"cpu\": 2}}");

        assertTrue(ConfigFiles.rewriteLosesContent(config));
    }

    @Test
    public void aStrictFileLosesNothingOnRewrite() throws Exception {
        Path config = tempDir.resolve("config.json");
        Files.writeString(config, "{\"pluginPath\": \"p\", \"resources\": {\"cpu\": 4}}");

        assertFalse(ConfigFiles.rewriteLosesContent(config));
    }

    @Test
    public void anAmpersandIsWrittenLiterally() throws Exception {
        Path config = tempDir.resolve("config.json");
        Files.writeString(config, "{}");

        ConfigFiles.writePluginPath(config, Path.of("D:/R&D/plugins"));

        assertTrue(Files.readString(config).contains("R&D"), Files.readString(config));
    }

    @Test
    public void aNonStringPluginPathIsRefused() throws Exception {
        Path config = tempDir.resolve("config.json");
        for (String value : new String[] {"{\"a\": 1}", "[\"p\"]", "12"}) {
            Files.writeString(config, "{\"pluginPath\": " + value + "}");

            assertThrows(CopybotException.class, () -> ConfigFiles.readPluginPath(config), value);
        }
    }

    @Test
    public void aNonUtf8FileIsNotJson() throws Exception {
        Path config = tempDir.resolve("config.json");
        Files.write(config, new byte[] {'{', '"', 'a', '"', ':', '"', (byte) 0xE9, '"', '}'});

        CopybotException e = assertThrows(CopybotException.class, () -> ConfigFiles.readPluginPath(config));
        assertEquals(ResourcesEngine.getString("config.not-json", config), e.getMessage());
    }

    @Test
    public void invalidJsonIsRefused() throws Exception {
        Path config = tempDir.resolve("config.json");
        Files.writeString(config, "not json");

        assertThrows(CopybotException.class, () -> ConfigFiles.readPluginPath(config));
        assertThrows(CopybotException.class, () -> ConfigFiles.writePluginPath(config, Path.of("p")));
        assertEquals("not json", Files.readString(config));
    }

    @Test
    public void resourcesAreReadBack() throws Exception {
        Path config = tempDir.resolve("config.json");
        Files.writeString(config, "{\"pluginPath\": \"p\"}");

        ConfigFiles.writeResources(config, new ConfigFiles.Resources(
                Map.of("cpu", 4, "disk:D:\\", 1), List.of(List.of("disk:D:\\", "disk:\\\\nas\\photos\\"))));

        ConfigFiles.Resources read = ConfigFiles.readResources(config);
        assertEquals(Map.of("cpu", 4, "disk:D:\\", 1), read.capacities());
        assertEquals(List.of(List.of("disk:D:\\", "disk:\\\\nas\\photos\\")), read.groups());
        assertEquals(Optional.of(Path.of("p")), ConfigFiles.readPluginPath(config));
    }

    @Test
    public void emptyResourcesRemoveTheirKeys() throws Exception {
        Path config = tempDir.resolve("config.json");
        Files.writeString(config, "{\"pluginPath\": \"p\", \"resources\": {\"cpu\": 2}, \"resourceGroups\": [[\"a\", \"b\"]]}");

        ConfigFiles.writeResources(config, new ConfigFiles.Resources(Map.of(), List.of()));

        String text = Files.readString(config);
        assertFalse(text.contains("resources"), text);
        assertFalse(text.contains("resourceGroups"), text);
        assertTrue(text.contains("pluginPath"), text);
        assertEquals(new ConfigFiles.Resources(Map.of(), List.of()), ConfigFiles.readResources(config));
    }

    @Test
    public void aCapacityThatIsNoNumberIsRefused() throws Exception {
        Path config = tempDir.resolve("config.json");
        Files.writeString(config, "{\"resources\": {\"cpu\": \"many\"}}");

        CopybotException e = assertThrows(CopybotException.class, () -> ConfigFiles.readResources(config));
        assertEquals(ResourcesEngine.getString("config.not-json", config), e.getMessage());
    }
}

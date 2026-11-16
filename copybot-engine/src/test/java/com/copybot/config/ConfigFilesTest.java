package com.copybot.config;

import com.copybot.exception.CopybotException;
import com.copybot.resources.ResourcesEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ConfigFilesTest {

    @TempDir
    Path tempDir;

    @Test
    public void resourcesOfAMissingFileAreEmptyAndWritingCreatesIt() throws Exception {
        Path config = tempDir.resolve("data").resolve("config.json");

        assertEquals(new ConfigFiles.Resources(Map.of(), List.of()), ConfigFiles.readResources(config));
        assertFalse(ConfigFiles.rewriteLosesContent(config));

        ConfigFiles.writeResources(config, new ConfigFiles.Resources(Map.of("disk:*", 4), List.of()));

        assertTrue(Files.exists(config));
        assertEquals("{\"resources\":{\"disk:*\":4}}", Files.readString(config).replaceAll("\\s", ""));
    }

    @Test
    public void otherKeysArePreserved() throws Exception {
        Path config = tempDir.resolve("config.json");
        Files.writeString(config, """
                {
                  "other": "kept",
                  "resources": {"cpu": 4, "disk:*": 2},
                  "resourceGroups": [["disk:D", "disk:E"]]
                }""");

        ConfigFiles.writeResources(config, new ConfigFiles.Resources(Map.of("cpu", 8), List.of(List.of("disk:D", "disk:E"))));

        String text = Files.readString(config);
        assertTrue(text.contains("\"other\": \"kept\""), text);
        assertTrue(text.contains("\"cpu\": 8"), text);
        assertTrue(text.contains("\"disk:E\""), text);
    }

    @Test
    public void crlfIsKept() throws Exception {
        Path config = tempDir.resolve("config.json");
        Files.writeString(config, "{\r\n  \"other\": \"x\"\r\n}");

        ConfigFiles.writeResources(config, new ConfigFiles.Resources(Map.of("cpu", 1), List.of()));

        String text = Files.readString(config);
        assertTrue(text.contains("\r\n"), text);
        assertFalse(text.replace("\r\n", "").contains("\n"), "every line ends with CRLF: " + text);
    }

    @Test
    public void lfIsKept() throws Exception {
        Path config = tempDir.resolve("config.json");
        Files.writeString(config, "{\n  \"other\": \"x\"\n}");

        ConfigFiles.writeResources(config, new ConfigFiles.Resources(Map.of("cpu", 1), List.of()));

        assertFalse(Files.readString(config).contains("\r"));
    }

    @Test
    public void aReadOnlyFileIsRefusedAndLeftUntouched() throws Exception {
        Path config = tempDir.resolve("config.json");
        String original = "{\"other\": \"x\"}";
        Files.writeString(config, original);
        assertTrue(config.toFile().setWritable(false));
        try {
            assertThrows(CopybotException.class,
                    () -> ConfigFiles.writeResources(config, new ConfigFiles.Resources(Map.of("cpu", 1), List.of())));
            assertEquals(original, Files.readString(config));
        } finally {
            config.toFile().setWritable(true);
        }
    }

    @Test
    public void aCommentedFileLosesContentOnRewrite() throws Exception {
        Path config = tempDir.resolve("config.json");
        Files.writeString(config, "{\n  // the disks\n  \"other\": \"p\"\n}");

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
        Files.writeString(config, "{\"other\": \"p\", \"resources\": {\"cpu\": 4}}");

        assertFalse(ConfigFiles.rewriteLosesContent(config));
    }

    @Test
    public void aNonUtf8FileIsNotJson() throws Exception {
        Path config = tempDir.resolve("config.json");
        Files.write(config, new byte[] {'{', '"', 'a', '"', ':', '"', (byte) 0xE9, '"', '}'});

        CopybotException e = assertThrows(CopybotException.class, () -> ConfigFiles.readResources(config));
        assertEquals(ResourcesEngine.getString("config.not-json", config), e.getMessage());
    }

    @Test
    public void invalidJsonIsRefused() throws Exception {
        Path config = tempDir.resolve("config.json");
        Files.writeString(config, "not json");

        assertThrows(CopybotException.class, () -> ConfigFiles.readResources(config));
        assertThrows(CopybotException.class,
                () -> ConfigFiles.writeResources(config, new ConfigFiles.Resources(Map.of("cpu", 1), List.of())));
        assertEquals("not json", Files.readString(config));
    }

    @Test
    public void resourcesAreReadBack() throws Exception {
        Path config = tempDir.resolve("config.json");
        Files.writeString(config, "{\"other\": \"p\"}");

        ConfigFiles.writeResources(config, new ConfigFiles.Resources(
                Map.of("cpu", 4, "disk:D:\\", 1), List.of(List.of("disk:D:\\", "disk:\\\\nas\\photos\\"))));

        ConfigFiles.Resources read = ConfigFiles.readResources(config);
        assertEquals(Map.of("cpu", 4, "disk:D:\\", 1), read.capacities());
        assertEquals(List.of(List.of("disk:D:\\", "disk:\\\\nas\\photos\\")), read.groups());
        assertTrue(Files.readString(config).contains("\"other\""));
    }

    @Test
    public void emptyResourcesRemoveTheirKeys() throws Exception {
        Path config = tempDir.resolve("config.json");
        Files.writeString(config, "{\"other\": \"p\", \"resources\": {\"cpu\": 2}, \"resourceGroups\": [[\"a\", \"b\"]]}");

        ConfigFiles.writeResources(config, new ConfigFiles.Resources(Map.of(), List.of()));

        String text = Files.readString(config);
        assertFalse(text.contains("resources"), text);
        assertFalse(text.contains("resourceGroups"), text);
        assertTrue(text.contains("other"), text);
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

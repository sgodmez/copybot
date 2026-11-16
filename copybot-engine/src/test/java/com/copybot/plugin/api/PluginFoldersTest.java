package com.copybot.plugin.api;

import com.copybot.config.AppFolders;
import com.copybot.engine.CopybotEngine;
import com.copybot.engine.plugin.PluginDataRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class PluginFoldersTest {

    @TempDir
    Path tempDir;

    @AfterEach
    public void forgetTheRoot() {
        PluginDataRoot.set(null);
    }

    @Test
    public void theFolderOfAPluginIsUnderTheRootTheEngineSetAndIsCreatedOnDemand() throws IOException {
        Path root = tempDir.resolve("data").resolve("plugin-data");
        PluginDataRoot.set(root);

        Path folder = PluginFolders.dataDirectory("exec");

        assertEquals(root.resolve("exec").toAbsolutePath(), folder);
        assertTrue(Files.isDirectory(folder));
        assertEquals(folder, PluginFolders.dataDirectory("exec"), "asking again gives the same folder");
    }

    @Test
    public void outsideTheEngineTheFolderIsInTheTemporaryFolder() throws IOException {
        PluginDataRoot.set(null);

        Path folder = PluginFolders.dataDirectory("plugin-folders-test");

        assertEquals(Path.of(System.getProperty("java.io.tmpdir"), "copybot-plugin-data", "plugin-folders-test")
                .toAbsolutePath(), folder);
        assertTrue(Files.isDirectory(folder));
        Files.delete(folder);
    }

    @Test
    public void aCodeThatIsNotLowerCaseLettersDigitsAndDashesIsRefused() {
        PluginDataRoot.set(tempDir);
        for (String code : new String[]{null, "", "Exec", "../exec", "a/b", "a b", "ex.ec", "EXEC"}) {
            assertThrows(IllegalArgumentException.class, () -> PluginFolders.dataDirectory(code), String.valueOf(code));
        }
        assertDoesNotThrow(() -> PluginFolders.dataDirectory("gps-zone2"));
    }

    @Test
    public void theEngineSetsTheRootUnderItsDataFolder() throws IOException {
        Path data = Files.createDirectories(tempDir.resolve("copybot"));
        AppFolders folders = new AppFolders(tempDir, data, data.resolve("config.json"), false);

        try (CopybotEngine ignored = CopybotEngine.create(folders)) {
            assertEquals(data.resolve("plugin-data").resolve("exec").toAbsolutePath(),
                    PluginFolders.dataDirectory("exec"));
        }
    }
}

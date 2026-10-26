package com.copybot.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public class AppFoldersTest {

    @TempDir
    Path tempDir;

    private Path abs(String name) {
        return tempDir.resolve(name).toAbsolutePath().normalize();
    }

    @Test
    public void thePackagedAppKeepsItsDataBesideItWhenWritable() {
        Path app = abs("app");
        AppFolders folders = AppFolders.resolve(Optional.empty(), app.resolve("Copybot.exe").toString(),
                abs("work"), "X", "home", dir -> true);

        assertEquals(app, folders.appDir());
        assertEquals(app, folders.dataDir());
        assertEquals(app.resolve("config.json"), folders.configFile());
        assertEquals(app.resolve("plugins"), folders.pluginsDir());
        assertEquals(app.resolve("prefs.json"), folders.prefsFile());
        assertEquals(app.resolve("logs"), folders.logsDir());
        assertFalse(folders.configChosen());
    }

    @Test
    public void aProtectedAppFolderSendsTheDataToAppData() {
        Path app = abs("app");
        Path appData = abs("roaming");
        AppFolders folders = AppFolders.resolve(Optional.empty(), app.resolve("Copybot.exe").toString(),
                abs("work"), appData.toString(), "home", dir -> false);

        assertEquals(appData.resolve("Copybot"), folders.dataDir());
        assertEquals(appData.resolve("Copybot").resolve("config.json"), folders.configFile());
        assertEquals(app.resolve("plugins"), folders.pluginsDir());
    }

    @Test
    public void withoutAppDataTheDataGoesToTheHomeFolder() {
        Path home = abs("home");
        AppFolders folders = AppFolders.resolve(Optional.empty(), abs("app").resolve("Copybot.exe").toString(),
                abs("work"), null, home.toString(), dir -> false);

        assertEquals(home.resolve(".copybot"), folders.dataDir());
    }

    @Test
    public void aChosenConfigFileDecidesTheDataFolder() {
        Path dev = abs("dev");
        Path work = abs("work");
        AppFolders folders = AppFolders.resolve(Optional.of(dev.resolve("config.json")), null,
                work, "X", "home", dir -> false);

        assertEquals(dev, folders.dataDir());
        assertEquals(dev.resolve("config.json"), folders.configFile());
        assertTrue(folders.configChosen());
        assertEquals(work, folders.appDir());
    }

    @Test
    public void outsideThePackagedAppTheAppFolderIsTheWorkingDirectory() {
        Path work = abs("work");
        AppFolders folders = AppFolders.resolve(Optional.empty(), null, work, "X", "home", dir -> true);

        assertEquals(work, folders.appDir());
    }

    @Test
    public void theWritableProbeLeavesNothingBehind() throws Exception {
        assertTrue(AppFolders.isWritable(tempDir));
        try (Stream<Path> content = Files.list(tempDir)) {
            assertEquals(List.of(), content.toList());
        }
        assertFalse(AppFolders.isWritable(tempDir.resolve("absent")));
    }

    @Test
    public void theDevPluginPathsComeFromTheProperty() {
        assertEquals(List.of(Path.of("a"), Path.of("b")),
                AppFolders.devPluginPaths("a" + File.pathSeparator + "b", null));
        assertEquals(List.of(), AppFolders.devPluginPaths(null, null));
        assertEquals(List.of(), AppFolders.devPluginPaths("  ", null));
    }

    @Test
    public void theDevPluginPathsAreIgnoredInThePackagedApp() {
        assertEquals(List.of(), AppFolders.devPluginPaths("a", "C:/x/Copybot.exe"));
    }
}

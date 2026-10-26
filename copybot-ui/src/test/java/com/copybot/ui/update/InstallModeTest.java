package com.copybot.ui.update;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstallModeTest {

    @TempDir
    Path dir;

    @Test
    void aFolderWithTheUninstallerIsInstalled() throws IOException {
        Files.writeString(dir.resolve("unins000.exe"), "x");
        assertEquals(InstallMode.INSTALLED, InstallMode.of(dir));
    }

    @Test
    void aFolderWithoutTheUninstallerIsPortable() {
        assertEquals(InstallMode.PORTABLE, InstallMode.of(dir));
    }
}

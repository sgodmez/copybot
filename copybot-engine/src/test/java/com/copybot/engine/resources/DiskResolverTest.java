package com.copybot.engine.resources;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class DiskResolverTest {

    @TempDir
    Path tempDir;

    @Test
    public void samePathsOnSameVolumeGetSameName(@TempDir Path other) {
        assertEquals(DiskResolver.diskResource(tempDir), DiskResolver.diskResource(other));
    }

    @Test
    public void nonExistingPathFallsBackToExistingAncestor() {
        Path notCreatedYet = tempDir.resolve("sub").resolve("dir").resolve("file.txt");
        assertEquals(DiskResolver.diskResource(tempDir), DiskResolver.diskResource(notCreatedYet));
    }

    @Test
    public void nameIsPrefixedWithDisk() {
        assertTrue(DiskResolver.diskResource(tempDir).startsWith("disk:"));
    }

    @Test
    public void aFolderOfTheConfigurationStandsForItsDisk() {
        assertEquals(DiskResolver.diskResource(tempDir),
                DiskResolver.resourceForConfigName("disk:" + tempDir));
    }

    @Test
    public void otherConfigurationNamesAreKeptAsWritten() {
        assertEquals("disk:*", DiskResolver.resourceForConfigName("disk:*"));
        assertEquals("disk:sda", DiskResolver.resourceForConfigName("disk:sda"));
        assertEquals("disk:card", DiskResolver.resourceForConfigName("disk:card"));
        assertEquals("cpu", DiskResolver.resourceForConfigName("cpu"));
    }

    @Test
    public void aDriveLetterWithoutBackslashIsTheDrive() {
        assumeTrue(windows(), "drive letters");
        assertEquals(DiskResolver.diskResource(Path.of("C:\\")), DiskResolver.resourceForConfigName("disk:C:"));
        assertEquals(DiskResolver.diskResource(Path.of("C:\\")), DiskResolver.resourceForConfigName("disk:C:\\"));
    }

    @Test
    public void aDriveThatIsNotThereIsKeptAsWritten() {
        assumeTrue(windows(), "drive letters");
        String absent = null;
        for (char letter = 'Z'; letter > 'D' && absent == null; letter--) {
            if (!Files.exists(Path.of(letter + ":\\"))) {
                absent = "disk:" + letter + ":\\";
            }
        }
        assumeTrue(absent != null, "a free drive letter");
        assertEquals(absent, DiskResolver.resourceForConfigName(absent));
    }

    @Test
    public void onWindowsALocalDriveIsNamedAfterItsPhysicalDisk() {
        assumeTrue(windows(), "Windows");
        String name = DiskResolver.diskResource(Path.of(System.getenv().getOrDefault("SystemDrive", "C:") + "\\"));
        assertTrue(name.matches("disk:PhysicalDrive\\d+"), name);
        assertNotEquals(DiskKind.UNKNOWN, DiskResolver.kindOf(name), "the system disk tells its kind");
    }

    @Test
    public void onWindowsTheNameDoesNotDependOnTheVolumeLabel() {
        assumeTrue(windows(), "Windows");
        assertFalse(DiskResolver.diskResource(tempDir).contains("("), "no \"Label (D:)\" any more");
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }
}

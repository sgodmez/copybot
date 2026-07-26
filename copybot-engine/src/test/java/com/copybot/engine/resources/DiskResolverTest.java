package com.copybot.engine.resources;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

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
}

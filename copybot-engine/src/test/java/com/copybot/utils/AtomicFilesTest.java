package com.copybot.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public class AtomicFilesTest {

    @TempDir
    Path dir;

    private List<Path> files(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.sorted().toList();
        }
    }

    @Test
    public void writesANewFileWithoutLeavingATemporaryOne() throws IOException {
        Path target = dir.resolve("state.json");

        AtomicFiles.write(target, "{\"a\":1}");

        assertEquals("{\"a\":1}", Files.readString(target));
        assertEquals(List.of(target), files(dir));
    }

    @Test
    public void replacesAnExistingFile() throws IOException {
        Path target = Files.writeString(dir.resolve("config.json"), "old content, longer than the new one");

        AtomicFiles.write(target, "new");

        assertEquals("new", Files.readString(target));
        assertEquals(List.of(target), files(dir));
    }

    @Test
    public void writesTheTextInUtf8() throws IOException {
        Path target = dir.resolve("photo.json");

        AtomicFiles.write(target, "été");

        assertArrayEquals("été".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(target));
    }

    @Test
    public void aRelativeTargetIsWrittenInItsOwnDirectory() throws IOException {
        Path target = Path.of("target", "atomic-files-test-" + System.nanoTime() + ".json");
        try {
            AtomicFiles.write(target, "x");
            assertEquals("x", Files.readString(target));
        } finally {
            Files.deleteIfExists(target);
        }
    }

    @Test
    public void aFailedMoveLeavesTheTargetAsItWasAndNoTemporaryFile() throws IOException {
        // a non-empty directory cannot be replaced by a file, on every platform
        Path target = Files.createDirectories(dir.resolve("state.json"));
        Path child = Files.writeString(target.resolve("child"), "kept");

        assertThrows(IOException.class, () -> AtomicFiles.write(target, "new"));

        assertEquals(List.of(target), files(dir), "the temporary file is deleted");
        assertEquals("kept", Files.readString(child));
    }

    @Test
    public void aMissingDirectoryFailsWithoutCreatingAnything() {
        Path target = dir.resolve("missing").resolve("state.json");

        assertThrows(IOException.class, () -> AtomicFiles.write(target, "x"));

        assertFalse(Files.exists(target.getParent()));
    }
}

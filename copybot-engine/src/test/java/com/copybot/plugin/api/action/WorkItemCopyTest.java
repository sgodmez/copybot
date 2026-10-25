package com.copybot.plugin.api.action;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class WorkItemCopyTest {

    @TempDir
    Path tempDir;

    @Test
    public void copyKeepsTheOwnPattern() throws IOException {
        WorkItem item = new WorkItem(Files.writeString(tempDir.resolve("a.jpg"), "x"));
        assertEquals(Optional.empty(), item.getOutPattern());

        item.setOutPattern("x/{name}");
        assertEquals(Optional.of("x/{name}"), item.copyForDryRun().getOutPattern());

        item.setOutPattern(" ");
        assertEquals(Optional.empty(), item.getOutPattern());
        assertEquals(Optional.empty(), item.copyForDryRun().getOutPattern());
    }

    @Test
    public void aCopyHasTheSameSourceAndIndependentMetadata() throws IOException {
        Path file = Files.writeString(tempDir.resolve("DSC_1.NEF"), "x");
        WorkItem item = new WorkItem(file);
        item.getMetadatas().display().put("name", "DSC_1.NEF");
        item.getMetadatas().setTime(WorkItemMetadata.CAPTURE_DATE, Instant.parse("2026-09-28T10:00:00Z"));

        WorkItem copy = item.copyForDryRun();
        copy.getMetadatas().display().put("name", "DSC_1.jpg");
        copy.getMetadatas().raw().put("extra", 1L);

        assertEquals(item.getLocalLocation(), copy.getLocalLocation());
        assertEquals(item.getNameDisplay(), copy.getNameDisplay());
        assertEquals("DSC_1.NEF", item.getMetadatas().display().get("name"));
        assertFalse(item.getMetadatas().raw().containsKey("extra"));
        assertEquals(item.getMetadatas().getTime(WorkItemMetadata.CAPTURE_DATE), copy.getMetadatas().getTime(WorkItemMetadata.CAPTURE_DATE));
    }

    @Test
    public void withContentKeepsTheSourceAndReplacesTheContent() throws IOException {
        Path file = Files.writeString(tempDir.resolve("a.jpg"), "old");
        WorkItem item = new WorkItem(file);
        item.getMetadatas().display().put("name", "a.jpg");
        item.getMetadatas().setSize(3);
        item.setDeleted(true);
        item.setOutPattern("own/{name}");

        WorkItem replaced = item.withContent(() -> new java.io.ByteArrayInputStream("brand new".getBytes()), 9);

        assertEquals(Optional.of("own/{name}"), replaced.getOutPattern(), "the rewritten copy keeps its own pattern");
        assertEquals(item.getNameDisplay(), replaced.getNameDisplay());
        assertEquals(file, replaced.getLocalLocation());
        assertTrue(replaced.isLocal());
        assertTrue(replaced.isDeleted());
        assertEquals("a.jpg", replaced.getMetadatas().display().get("name"));
        try (var in = replaced.openInputStream()) {
            assertEquals("brand new", new String(in.readAllBytes()));
        }
        assertEquals(9L, replaced.getMetadatas().getSize());
        assertEquals(3L, item.getMetadatas().getSize());
        try (var in = item.openInputStream()) {
            assertEquals("old", new String(in.readAllBytes()));
        }
    }
}

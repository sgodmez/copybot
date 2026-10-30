package com.copybot.plugin.api.action;

import com.copybot.engine.resources.DiskResolver;
import com.copybot.engine.resources.DiskTraffic;
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

    @Test
    public void readingALocalItemIsCountedOnItsDisk() throws IOException {
        Path file = Files.writeString(tempDir.resolve("b.jpg"), "12345");
        String disk = DiskResolver.diskResource(file);
        long before = DiskTraffic.global().snapshot().getOrDefault(disk, new DiskTraffic.Bytes(0, 0)).read();

        try (var in = new WorkItem(file).openInputStream()) {
            assertEquals(5, in.readAllBytes().length);
        }

        assertTrue(DiskTraffic.global().snapshot().get(disk).read() - before >= 5);
    }

    @Test
    public void withContentFileReadsTheFileAndKeepsTheSource() throws IOException {
        Path source = Files.writeString(tempDir.resolve("a.jpg"), "card");
        Path buffer = Files.writeString(tempDir.resolve("buffer.bin"), "buffered");
        WorkItem item = new WorkItem(source);
        item.setOutPattern("own/{name}");

        WorkItem buffered = item.withContentFile(buffer);

        try (var in = buffered.openInputStream()) {
            assertEquals("buffered", new String(in.readAllBytes()));
        }
        assertEquals(source, buffered.getLocalLocation(), "the source stays the original: deleteSource deletes it");
        assertEquals("a.jpg", buffered.getNameDisplay());
        assertEquals(Optional.of("own/{name}"), buffered.getOutPattern());
        assertEquals(buffer, buffered.getContentLocation());
        assertEquals(source, item.getContentLocation(), "a plain local item is read from its source");
    }

    @Test
    public void aCopyOfABufferedItemKeepsItsContentFile() throws IOException {
        Path buffer = Files.writeString(tempDir.resolve("buffer.bin"), "buffered");
        WorkItem buffered = new WorkItem(Files.writeString(tempDir.resolve("a.jpg"), "card")).withContentFile(buffer);

        WorkItem rewritten = buffered.withContent(() -> new java.io.ByteArrayInputStream("new".getBytes()), 3);

        assertEquals(buffer, buffered.copyForDryRun().getContentLocation());
        assertEquals(buffer, rewritten.getContentLocation(), "content made from the buffer: its disk is still read");
        try (var in = rewritten.openInputStream()) {
            assertEquals("new", new String(in.readAllBytes()));
        }
    }

    @Test
    public void theSourceKeptMarkFollowsEveryCopy() throws IOException {
        WorkItem item = new WorkItem(Files.writeString(tempDir.resolve("a.jpg"), "x"));
        assertFalse(item.isSourceKept(), "not marked by default");

        WorkItem kept = item.withSourceKept();

        assertTrue(kept.isSourceKept());
        assertFalse(item.isSourceKept(), "the original is unchanged");
        assertTrue(kept.copyForDryRun().isSourceKept());
        assertTrue(kept.withContent(() -> new java.io.ByteArrayInputStream("y".getBytes()), 1).isSourceKept());
        assertTrue(kept.withContentFile(Files.writeString(tempDir.resolve("b.bin"), "x")).isSourceKept());
        assertTrue(kept.withCompanions(java.util.List.of()).isSourceKept());
        assertTrue(kept.withoutCompanions().isSourceKept());
        assertFalse(item.withoutCompanions().isSourceKept());
    }

    @Test
    public void anItemFromAUrlHasNoContentLocationUntilBuffered() throws IOException {
        WorkItem remote = new WorkItem(new java.net.URL("http://example.invalid/a.jpg"),
                () -> new java.io.ByteArrayInputStream("remote".getBytes()));
        Path buffer = Files.writeString(tempDir.resolve("buffer.bin"), "remote");

        assertNull(remote.getContentLocation());
        WorkItem buffered = remote.withContentFile(buffer);
        assertEquals(buffer, buffered.getContentLocation());
        assertFalse(buffered.isLocal(), "not an original local file Copybot could delete");
        try (var in = buffered.openInputStream()) {
            assertEquals("remote", new String(in.readAllBytes()));
        }
    }
}

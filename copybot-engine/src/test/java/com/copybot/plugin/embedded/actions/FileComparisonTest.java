package com.copybot.plugin.embedded.actions;

import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import com.copybot.plugin.embedded.actions.FileWriteSettings.Compare;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** Conflict comparison and hashes (spec safe-write §2, §4). */
public class FileComparisonTest {

    private static final Instant SHOT = Instant.parse("2026-09-01T10:00:00Z");

    @TempDir
    Path tempDir;

    private WorkItem source(String name, byte[] content) throws IOException {
        Path file = Files.write(tempDir.resolve(name), content);
        WorkItem item = new WorkItem(file);
        item.getMetadatas().setSize(content.length);
        item.getMetadatas().setTime(WorkItemMetadata.LAST_MODIFIED, SHOT);
        return item;
    }

    private Path target(String name, byte[] content, Instant lastModified) throws IOException {
        Path file = Files.write(tempDir.resolve(name), content);
        Files.setLastModifiedTime(file, FileTime.from(lastModified));
        return file;
    }

    private static byte[] bytes(int size, int seed) {
        byte[] content = new byte[size];
        for (int i = 0; i < size; i++) {
            content[i] = (byte) (i * 31 + seed);
        }
        return content;
    }

    private static byte[] alteredAt(byte[] content, int index) {
        byte[] copy = Arrays.copyOf(content, content.length);
        copy[index] ^= 0x55;
        return copy;
    }

    @Test
    public void aDifferentSizeIsNeverIdenticalAndTheSourceIsNotRead() throws IOException {
        WorkItem neverRead = new WorkItem(tempDir.toUri().toURL(), () -> {
            throw new AssertionError("different sizes: nothing is read");
        });
        neverRead.getMetadatas().setSize(10);
        Path target = target("t.bin", bytes(11, 0), SHOT);

        for (Compare compare : Compare.values()) {
            assertFalse(FileComparison.identical(neverRead, target, compare), compare.name());
        }
    }

    @Test
    public void sizeOnlyComparesTheSizes() throws IOException {
        WorkItem item = source("s.bin", bytes(100, 1));
        Path target = target("t.bin", bytes(100, 2), SHOT.plusSeconds(3600));

        assertTrue(FileComparison.identical(item, target, Compare.SIZE));
        assertFalse(FileComparison.identical(item, target, Compare.PARTIAL_HASH));
    }

    @Test
    public void sizeAndDateToleratesTwoSecondsOnly() throws IOException {
        WorkItem item = source("s.bin", bytes(100, 1));

        assertTrue(FileComparison.identical(item, target("a.bin", bytes(100, 2), SHOT.plusSeconds(2)), Compare.SIZE_AND_DATE));
        assertTrue(FileComparison.identical(item, target("b.bin", bytes(100, 2), SHOT.minusSeconds(2)), Compare.SIZE_AND_DATE));
        assertFalse(FileComparison.identical(item, target("c.bin", bytes(100, 2), SHOT.plusSeconds(3)), Compare.SIZE_AND_DATE));
        assertFalse(FileComparison.identical(item, target("d.bin", bytes(100, 2), SHOT.plusMillis(2001)), Compare.SIZE_AND_DATE));
        assertFalse(FileComparison.identical(item, target("e.bin", bytes(100, 2), SHOT.minusMillis(2001)), Compare.SIZE_AND_DATE));
    }

    @Test
    public void sizeAndDateNeverRecognisesASourceWithoutDate() throws IOException {
        byte[] content = bytes(100, 1);
        WorkItem fromUrl = new WorkItem(tempDir.toUri().toURL(), () -> new ByteArrayInputStream(content));
        fromUrl.getMetadatas().setSize(content.length);

        assertFalse(FileComparison.identical(fromUrl, target("t.bin", content, SHOT), Compare.SIZE_AND_DATE));
    }

    @Test
    public void partialHashReadsTheStartAndTheEndOfALargeFile() throws IOException {
        byte[] content = bytes(300 * 1024, 7);
        WorkItem item = source("s.bin", content);

        Path middleChanged = target("m.bin", alteredAt(content, 150 * 1024), SHOT);
        assertTrue(FileComparison.identical(item, middleChanged, Compare.PARTIAL_HASH), "the middle is not read");
        assertFalse(FileComparison.identical(item, middleChanged, Compare.FULL_HASH));

        assertFalse(FileComparison.identical(item, target("e.bin", alteredAt(content, content.length - 1), SHOT), Compare.PARTIAL_HASH));
        assertFalse(FileComparison.identical(item, target("b.bin", alteredAt(content, 0), SHOT), Compare.PARTIAL_HASH));
    }

    @Test
    public void partialHashReadsEverythingUpTo128KiB() throws IOException {
        byte[] content = bytes(128 * 1024, 3);
        WorkItem item = source("s.bin", content);

        assertFalse(FileComparison.identical(item, target("m.bin", alteredAt(content, 64 * 1024), SHOT), Compare.PARTIAL_HASH));
        assertTrue(FileComparison.identical(item, target("same.bin", content, SHOT), Compare.PARTIAL_HASH));
    }

    @Test
    public void partialHashSkipsOnlyTheMiddleAbove128KiB() throws IOException {
        int chunk = FileComparison.PARTIAL_CHUNK;
        byte[] content = bytes(2 * chunk + 1, 9); // one byte skipped, at index chunk
        WorkItem item = source("s.bin", content);

        assertTrue(FileComparison.identical(item, target("m.bin", alteredAt(content, chunk), SHOT), Compare.PARTIAL_HASH));
        assertFalse(FileComparison.identical(item, target("a.bin", alteredAt(content, chunk - 1), SHOT), Compare.PARTIAL_HASH));
        assertFalse(FileComparison.identical(item, target("b.bin", alteredAt(content, chunk + 1), SHOT), Compare.PARTIAL_HASH));
    }

    @Test
    public void aStaleSourceSizeFailsInsteadOfMatching() throws IOException {
        WorkItem item = source("s.bin", bytes(200 * 1024, 4));
        item.getMetadatas().setSize(300 * 1024); // the source shrank since it was read
        Path target = target("t.bin", bytes(300 * 1024, 4), SHOT);

        assertThrows(EOFException.class, () -> FileComparison.identical(item, target, Compare.PARTIAL_HASH));
    }

    @Test
    public void fullHashRecognisesTheSameContent() throws IOException {
        byte[] content = bytes(200 * 1024, 5);

        assertTrue(FileComparison.identical(source("s.bin", content), target("t.bin", content, SHOT.plusSeconds(3600)), Compare.FULL_HASH));
    }

    @Test
    public void aSourceWithoutMetadataUsesItsLocalFile() throws IOException {
        Path file = Files.write(tempDir.resolve("s.bin"), bytes(100, 1));
        Files.setLastModifiedTime(file, FileTime.from(SHOT));
        WorkItem item = new WorkItem(file);

        assertEquals(100, FileComparison.sourceSize(item));
        assertEquals(SHOT, FileComparison.sourceDate(item).orElseThrow());
    }

    @Test
    public void sha256IsHexEncoded() throws IOException {
        byte[] digest = FileComparison.sha256(new ByteArrayInputStream("abc".getBytes(StandardCharsets.US_ASCII)));

        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", FileComparison.hex(digest));
    }
}

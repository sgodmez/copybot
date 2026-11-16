package com.copybot.plugin.embedded.actions;

import com.copybot.engine.resources.DiskResolver;
import com.copybot.engine.resources.DiskTraffic;
import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.WorkItem;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** file.buffer: a copy of each item on a fast local disk, deleted once the item is finished. */
public class FileBufferActionTest {

    @TempDir
    Path tempDir;

    private Path card;
    private Path buffer;

    private FileBufferAction action(String extraConfig) throws IOException {
        card = Files.createDirectories(tempDir.resolve("card"));
        buffer = tempDir.resolve("buffer");
        FileBufferAction action = new FileBufferAction();
        action.loadConfig(JsonParser.parseString("{\"folder\":\"" + json(buffer) + "\"" + extraConfig + "}"));
        action.beforeAll();
        return action;
    }

    private static String json(Path path) {
        return path.toAbsolutePath().toString().replace("\\", "\\\\");
    }

    private WorkItem photo(String name, String content) throws IOException {
        return FileReadAction.workItemOf(Files.writeString(card.resolve(name), content));
    }

    private static String read(WorkItem item) throws IOException {
        try (InputStream in = item.openInputStream()) {
            return new String(in.readAllBytes());
        }
    }

    private List<Path> filesUnder(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile).toList();
        }
    }

    @Test
    public void theNextStepsReadTheBufferedCopyAndTheSourceStaysTheOriginal() throws IOException {
        FileBufferAction action = action("");
        WorkItem item = photo("IMG_0001.JPG", "photo");

        List<WorkItem> produced = action.doProcess(item);

        assertEquals(1, produced.size());
        WorkItem buffered = produced.getFirst();
        assertEquals("photo", read(buffered));
        assertTrue(buffered.getContentLocation().startsWith(buffer), buffered.getContentLocation().toString());
        assertEquals(item.getLocalLocation(), buffered.getLocalLocation(), "deleteSource deletes the card's file");
        assertEquals("IMG_0001.JPG", buffered.getNameDisplay());
        assertEquals(item.getMetadatas().getSize(), buffered.getMetadatas().getSize());
        assertEquals("photo", Files.readString(item.getLocalLocation()), "the source is untouched");
    }

    @Test
    public void theBufferedCopyIsDeletedOnceTheItemIsFinishedAndTheRunFolderAfterAll() throws IOException {
        FileBufferAction action = action("");
        WorkItem a = photo("a.jpg", "a");
        WorkItem b = photo("b.jpg", "b");
        Path aCopy = action.doProcess(a).getFirst().getContentLocation();
        Path bCopy = action.doProcess(b).getFirst().getContentLocation();

        action.afterItem(a);

        assertFalse(Files.exists(aCopy));
        assertTrue(Files.exists(bCopy), "b is not finished yet");

        action.afterAll(); // b left out of the run: what is left goes too

        assertFalse(Files.exists(bCopy));
        assertEquals(List.of(), filesUnder(buffer));
        try (Stream<Path> left = Files.list(buffer)) {
            assertEquals(0, left.count(), "the run folder is removed");
        }
    }

    @Test
    public void theCopyIsCountedOnTheDiskOfTheBuffer() throws IOException {
        FileBufferAction action = action("");
        WorkItem item = photo("a.jpg", "12345");
        String disk = DiskResolver.diskResource(tempDir);
        long before = DiskTraffic.global().snapshot().getOrDefault(disk, new DiskTraffic.Bytes(0, 0)).written();

        action.doProcess(item);

        assertTrue(DiskTraffic.global().snapshot().get(disk).written() - before >= 5);
    }

    @Test
    public void twoItemsOfTheSameNameGetTwoCopies() throws IOException {
        FileBufferAction action = action("");
        Files.createDirectories(card.resolve("100"));
        Files.createDirectories(card.resolve("101"));
        WorkItem first = FileReadAction.workItemOf(Files.writeString(card.resolve("100").resolve("IMG.JPG"), "first"));
        WorkItem second = FileReadAction.workItemOf(Files.writeString(card.resolve("101").resolve("IMG.JPG"), "second"));

        WorkItem firstBuffered = action.doProcess(first).getFirst();
        WorkItem secondBuffered = action.doProcess(second).getFirst();

        assertEquals("first", read(firstBuffered));
        assertEquals("second", read(secondBuffered));
    }

    @Test
    public void withoutEnoughFreeSpaceTheItemGoesOnUnbuffered() throws IOException {
        FileBufferAction action = action(",\"keepFreeGb\":1000000000");
        WorkItem item = photo("a.jpg", "photo");

        List<WorkItem> produced = action.doProcess(item);

        assertEquals(List.of(item), produced, "the item itself: read from the card by the next steps");
        assertEquals(List.of(), filesUnder(buffer));
    }

    @Test
    public void itHoldsTheDiskOfTheBufferBesidesTheDiskOfTheItem() throws IOException {
        FileBufferAction action = action("");

        assertEquals(Set.of(buffer.toAbsolutePath()), action.touchedPaths(null));
    }

    @Test
    public void withoutFolderTheBufferIsInTheTemporaryFolderOfTheSystem() {
        FileBufferAction action = new FileBufferAction();
        action.loadConfig(JsonParser.parseString("{}"));

        Path expected = Path.of(System.getProperty("java.io.tmpdir"), "copybot-buffer").toAbsolutePath();
        assertEquals(Set.of(expected), action.touchedPaths(null));
    }

    @Test
    public void theDryRunChangesNothing() throws IOException {
        FileBufferAction action = action("");
        WorkItem item = photo("a.jpg", "photo");

        Optional<List<WorkItem>> dryRun = action.dryRun(item);

        assertEquals(Optional.of(List.of(item)), dryRun);
        assertEquals(List.of(), filesUnder(buffer));
    }

    @Test
    public void aFailedReadLeavesNoPartialCopy() throws IOException {
        FileBufferAction action = action("");
        WorkItem item = photo("a.jpg", "photo");
        Files.delete(item.getLocalLocation()); // the card was pulled out

        assertThrows(CopybotException.class, () -> action.doProcess(item));
        assertEquals(List.of(), filesUnder(buffer));
    }

    @Test
    public void theRunFoldersLeftByAnInterruptedRunAreRemovedTheRecentOnesAndOtherFilesKept() throws IOException {
        buffer = Files.createDirectories(tempDir.resolve("buffer"));
        Path stale = Files.createDirectories(buffer.resolve("run-old"));
        Files.writeString(stale.resolve("1-a.jpg"), "left over");
        Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minus(Duration.ofDays(2))));
        Path recent = Files.createDirectories(buffer.resolve("run-other-copybot"));
        Path mine = Files.writeString(buffer.resolve("notes.txt"), "not Copybot's");

        action("");

        assertFalse(Files.exists(stale));
        assertTrue(Files.exists(recent), "another Copybot may be running");
        assertTrue(Files.exists(mine), "only run folders are removed");
    }
}

package com.copybot.plugin.remote.core;

import com.copybot.plugin.api.action.TargetCheck;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WriteContext;
import com.copybot.plugin.api.action.WriteResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

public class RemoteWriteTest {

    private static final Instant DATE = Instant.parse("2024-05-01T10:15:30Z");
    private static final WriteContext CONTEXT = new WriteContext("run1");
    private static final String TEMP = "/out/.a.jpg.run1-1.copybot-tmp";

    private final InMemoryServer server = new InMemoryServer();
    private final AtomicInteger deletions = new AtomicInteger();

    @TempDir
    Path tempDir;

    private MemoryActions.Write action(String extra) {
        return MemoryActions.write(server, "{host: 'nas', outPattern: '/out/{name}'" + extra + "}");
    }

    private WorkItem item(String name, String content) {
        return item(name, content, DATE);
    }

    private WorkItem item(String name, String content, Instant date) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return WorkItem.remote(name, "/src", "mem://src/" + name, bytes.length, date,
                () -> new ByteArrayInputStream(bytes), deletions::incrementAndGet);
    }

    private static WorkItem withContent(WorkItem item, Supplier<InputStream> content) {
        return item.withContent(content, item.getMetadatas().getSize());
    }

    @Test
    public void writesToATemporaryNameThenRenames() {
        MemoryActions.Write write = MemoryActions.write(server,
                "{host: 'nas', outPattern: '/out/{lastModified.Y}/{name}'}");
        WriteResult result = write.write(item("a.jpg", "hello"), CONTEXT);
        assertFalse(result.isSkipped());
        assertNull(result.target());
        assertEquals(Optional.of("hello"), server.content("/out/2024/a.jpg"));
        assertEquals(List.of("/out/2024/a.jpg"), server.paths());
        assertEquals(List.of(
                "stat /out/2024/a.jpg",
                "mkdirs /out/2024",
                "upload /out/2024/.a.jpg.run1-1.copybot-tmp",
                "stat /out/2024/.a.jpg.run1-1.copybot-tmp",
                "rename /out/2024/.a.jpg.run1-1.copybot-tmp -> /out/2024/a.jpg"), server.operations);
        assertEquals(0, deletions.get(), "deleteSource is false by default");
    }

    @Test
    public void anAtomicUploadGoesToTheFinalName() {
        server.atomic = true;
        action("").write(item("a.jpg", "hello"), CONTEXT);
        assertEquals(Optional.of("hello"), server.content("/out/a.jpg"));
        assertTrue(server.operations.contains("upload /out/a.jpg"), server.operations.toString());
        assertTrue(server.operations.stream().noneMatch(o -> o.startsWith("rename")));
    }

    @Test
    public void theSameSizeIsSkippedByDefault() {
        server.put("/out/a.jpg", "olleh", DATE);
        WriteResult result = action("").write(item("a.jpg", "hello"), CONTEXT);
        assertTrue(result.isSkipped());
        assertNull(result.target());
        assertEquals("Already at the destination: mem://nas/out/a.jpg", result.reason());
        assertTrue(server.operations.stream().noneMatch(o -> o.startsWith("upload")));
    }

    @Test
    public void anotherSizeIsRenamedByDefaultAnIdenticalRenamedCopyFoundAgain() {
        server.put("/out/a.jpg", "other content", DATE);
        MemoryActions.Write write = action("");
        assertFalse(write.write(item("a.jpg", "hello"), CONTEXT).isSkipped());
        assertEquals(Optional.of("hello"), server.content("/out/a (1).jpg"));
        WriteResult again = write.write(item("a.jpg", "hello"), CONTEXT);
        assertTrue(again.isSkipped());
        assertEquals("Already at the destination: mem://nas/out/a (1).jpg", again.reason());
        assertFalse(write.write(item("a.jpg", "hello again"), CONTEXT).isSkipped());
        assertEquals(Optional.of("hello again"), server.content("/out/a (2).jpg"));
    }

    @Test
    public void anItemWithoutExtensionIsNumberedAtTheEnd() {
        server.put("/out/README", "other content", DATE);
        action("").write(item("README", "hello"), CONTEXT);
        assertEquals(Optional.of("hello"), server.content("/out/README (1)"));
    }

    @Test
    public void overwriteReplacesTheTargetByTheRename() {
        server.put("/out/a.jpg", "other content", DATE);
        action(", onConflict: {ifDifferent: 'overwrite'}").write(item("a.jpg", "hello"), CONTEXT);
        assertEquals(Optional.of("hello"), server.content("/out/a.jpg"));
        assertTrue(server.operations.contains("rename " + TEMP + " -> /out/a.jpg (replace)"), server.operations.toString());
    }

    @Test
    public void errorFailsTheItem() {
        server.put("/out/a.jpg", "hello", DATE);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> action(", onConflict: {ifIdentical: 'error'}").write(item("a.jpg", "hello"), CONTEXT));
        assertEquals("A file is already at the destination: mem://nas/out/a.jpg", e.getMessage());
    }

    @Test
    public void sizeAndDateComparesTheDatesWithinTwoSeconds() {
        server.put("/out/a.jpg", "hello", DATE.plusSeconds(2));
        MemoryActions.Write write = action(", onConflict: {compare: 'sizeAndDate'}");
        assertTrue(write.write(item("a.jpg", "hello"), CONTEXT).isSkipped());
        assertFalse(write.write(item("a.jpg", "hello", DATE.minusSeconds(1)), CONTEXT).isSkipped());
        assertEquals(Optional.of("hello"), server.content("/out/a (1).jpg"));
    }

    @Test
    public void theItemsDateIsWrittenSoSizeAndDateFindsTheCopyAgain() {
        MemoryActions.Write write = action(", onConflict: {compare: 'sizeAndDate'}");
        assertFalse(write.write(item("a.jpg", "hello"), CONTEXT).isSkipped());
        assertTrue(write.write(item("a.jpg", "hello"), CONTEXT).isSkipped(), "the second run finds it");
        assertEquals(List.of("/out/a.jpg"), server.paths());
    }

    @Test
    public void sizeAndDateComparesSizesOnAServerThatDoesNotKeepDates() {
        server.keepsDates = false;
        MemoryActions.Write write = action(", onConflict: {compare: 'sizeAndDate'}");
        write.write(item("a.jpg", "hello"), CONTEXT);
        assertTrue(write.write(item("a.jpg", "hello"), CONTEXT).isSkipped());
        assertEquals(List.of("/out/a.jpg"), server.paths());
    }

    @Test
    public void eachUploadHasItsOwnTemporaryName() {
        MemoryActions.Write write = action("");
        write.write(item("a.jpg", "hello"), CONTEXT);
        write.write(item("a.jpg", "hello again"), CONTEXT);
        assertEquals(List.of("upload /out/.a.jpg.run1-1.copybot-tmp", "upload /out/.a (1).jpg.run1-2.copybot-tmp"),
                server.operations.stream().filter(o -> o.startsWith("upload")).toList());
    }

    @Test
    public void aSourceThatCannotBeReadKeepsTheConnection() {
        WorkItem item = withContent(item("a.jpg", "hello"), () -> {
            throw new java.io.UncheckedIOException(new IOException("source gone"));
        });
        MemoryActions.Write write = action("");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> write.write(item, CONTEXT));
        assertEquals("Cannot write mem://nas/out/a.jpg (source gone)", e.getMessage());
        write.write(item("b.jpg", "hello"), CONTEXT);
        assertEquals(1, server.connects.get(), "the write's connection was not closed");
    }

    @Test
    public void aFileGoneAfterItsUploadFails() {
        server.vanished = path -> path.equals(TEMP);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> action("").write(item("a.jpg", "hello"), CONTEXT));
        assertEquals("Cannot write mem://nas/out/a.jpg: the file is not on the server after its upload", e.getMessage());
    }

    @Test
    public void aFolderAtTheTargetIsNeverOverwritten() {
        server.folder("/out/a.jpg");
        MemoryActions.Write overwrite = action(", onConflict: {ifDifferent: 'overwrite'}");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> overwrite.write(item("a.jpg", "hello"), CONTEXT));
        assertEquals("A folder is at the destination: mem://nas/out/a.jpg", e.getMessage());
        action("").write(item("a.jpg", "hello"), CONTEXT);
        assertEquals(Optional.of("hello"), server.content("/out/a (1).jpg"), "rename goes around it");
    }

    @Test
    public void twoItemsToOneTargetAreSerialized() throws Exception {
        MemoryActions.Write write = action("");
        CountDownLatch firstUploading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        server.beforeStore = () -> {
            if (first.compareAndSet(true, false)) {
                firstUploading.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread a = Thread.ofVirtual().start(() -> run(failure, () -> write.write(item("a.jpg", "first"), CONTEXT)));
        assertTrue(firstUploading.await(5, TimeUnit.SECONDS));
        Thread b = Thread.ofVirtual().start(() -> run(failure, () -> write.write(item("a.jpg", "second!"), CONTEXT)));
        assertFalse(b.join(java.time.Duration.ofMillis(200)), "the second waits for the first");
        release.countDown();
        a.join();
        b.join();
        assertNull(failure.get());
        assertEquals(Optional.of("first"), server.content("/out/a.jpg"));
        assertEquals(Optional.of("second!"), server.content("/out/a (1).jpg"));
        assertEquals(0, write.lockedTargets());
    }

    private static void run(AtomicReference<Throwable> failure, Runnable runnable) {
        try {
            runnable.run();
        } catch (Throwable e) {
            failure.set(e);
        }
    }

    @Test
    public void aFailedUploadDeletesTheTemporaryFileAndClosesTheConnection() {
        server.failUpload = path -> path.equals(TEMP);
        MemoryActions.Write write = action("");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> write.write(item("a.jpg", "hello"), CONTEXT));
        assertEquals("Cannot write mem://nas/out/a.jpg (connection reset)", e.getMessage());
        assertEquals(List.of(), server.paths());
        assertTrue(server.operations.contains("delete " + TEMP));
        assertEquals(0, server.open(), "the failed connection is not reused");
    }

    @Test
    public void aCancelledUploadDeletesTheTemporaryFileAndKeepsTheInterrupt() throws Exception {
        WorkItem item = withContent(item("a.jpg", "hello"), () -> new FilterInputStream(
                new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8))) {
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                Thread.currentThread().interrupt(); // the cancel, while the content is sent
                return super.read(b, off, Math.min(len, 2));
            }
        });
        MemoryActions.Write write = action(", deleteSource: true");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread thread = Thread.ofVirtual().start(() -> {
            run(failure, () -> write.write(item, CONTEXT));
            interrupted.set(Thread.currentThread().isInterrupted());
        });
        thread.join();
        assertInstanceOf(IllegalStateException.class, failure.get());
        assertTrue(interrupted.get(), "the interrupt is kept");
        assertEquals(List.of(), server.paths(), "the temporary file deleted on the interrupted thread too");
        assertEquals(0, deletions.get());
    }

    @Test
    public void aWrongRemoteSizeFailsAndDeletesTheTemporaryFile() {
        server.wrongSize = path -> path.equals(TEMP);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> action(", deleteSource: true").write(item("a.jpg", "hello"), CONTEXT));
        assertEquals("Cannot write mem://nas/out/a.jpg: the server holds 6 bytes, 5 were sent", e.getMessage());
        assertEquals(List.of(), server.paths());
        assertEquals(0, deletions.get(), "the source is kept");
    }

    @Test
    public void aSourceOfAnotherSizeFails() {
        WorkItem item = withContent(item("a.jpg", "hello"),
                () -> new ByteArrayInputStream("hell".getBytes(StandardCharsets.UTF_8)));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> action("").write(item, CONTEXT));
        assertEquals("Cannot write mem://nas/out/a.jpg: 4 bytes sent, the source has 5", e.getMessage());
        assertEquals(List.of(), server.paths());
    }

    @Test
    public void aFailedRenameDeletesTheTemporaryFile() {
        server.failRename = true;
        assertThrows(IllegalStateException.class, () -> action("").write(item("a.jpg", "hello"), CONTEXT));
        assertEquals(List.of(), server.paths());
    }

    @Test
    public void aWrongSizeAfterAnAtomicUploadDeletesTheNewFile() {
        server.atomic = true;
        server.wrongSize = path -> true;
        assertThrows(IllegalStateException.class, () -> action("").write(item("a.jpg", "hello"), CONTEXT));
        assertEquals(List.of(), server.paths());
    }

    @Test
    public void deleteSourceDeletesTheSourceOnceWritten() {
        MemoryActions.Write write = action(", deleteSource: true");
        WorkItem item = item("a.jpg", "hello");
        write.write(item, CONTEXT);
        assertEquals(1, deletions.get());
        assertTrue(item.isDeleted());
        // skipped as identical: no content comparison, the source is kept
        write.write(item("a.jpg", "hello"), CONTEXT);
        assertEquals(1, deletions.get());
    }

    @Test
    public void aKeptSourceIsNeverDeleted() {
        action(", deleteSource: true").write(item("a.jpg", "hello").withSourceKept(), CONTEXT);
        assertEquals(Optional.of("hello"), server.content("/out/a.jpg"));
        assertEquals(0, deletions.get());
    }

    @Test
    public void aLocalSourceIsDeleted() throws IOException {
        Path source = Files.writeString(tempDir.resolve("a.jpg"), "hello");
        WorkItem item = new WorkItem(source);
        item.getMetadatas().display().put("name", "a.jpg");
        item.getMetadatas().setSize(5);
        action(", deleteSource: true").write(item, CONTEXT);
        assertEquals(Optional.of("hello"), server.content("/out/a.jpg"));
        assertFalse(Files.exists(source));
    }

    @Test
    public void aSourceThatCannotBeDeletedFailsTheItemNamingBoth() {
        WorkItem item = WorkItem.remote("a.jpg", "/src", "mem://src/a.jpg", 5, DATE,
                () -> new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)),
                () -> {
                    throw new IOException("permission denied");
                });
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> action(", deleteSource: true").write(item, CONTEXT));
        assertEquals("The source mem://src/a.jpg is copied to mem://nas/out/a.jpg but not deleted (permission denied)",
                e.getMessage());
        assertEquals(Optional.of("hello"), server.content("/out/a.jpg"));
    }

    @Test
    public void aMissingKeyFailsSkipsOrStaysAsWritten() {
        String pattern = "{host: 'nas', outPattern: '/out/{captureDate.Y}/{name}'";
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> MemoryActions.write(server, pattern + "}").write(item("a.jpg", "hello"), CONTEXT));
        assertEquals("mem.write: no value for {captureDate.Y} for a.jpg", e.getMessage());
        WriteResult skipped = MemoryActions.write(server, pattern + ", onMissingKey: 'skip'}")
                .write(item("a.jpg", "hello"), CONTEXT);
        assertEquals("No value for {captureDate.Y}", skipped.reason());
        MemoryActions.write(server, pattern + ", onMissingKey: 'literal'}").write(item("a.jpg", "hello"), CONTEXT);
        assertEquals(Optional.of("hello"), server.content("/out/{captureDate.Y}/a.jpg"));
    }

    @Test
    public void aTargetOutsideThePatternFolderIsRefused() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> MemoryActions.write(server,
                "{host: 'nas', outPattern: '/out/{folder}/{name}'}").write(withFolder(item("a.jpg", "x"), ".."), CONTEXT));
        assertEquals("mem.write: \"/out/../a.jpg\" is not a valid remote path", e.getMessage());
        assertEquals(List.of(), server.paths());
    }

    private static WorkItem withFolder(WorkItem item, String folder) {
        item.getMetadatas().display().put("folder", folder);
        return item;
    }

    @Test
    public void backslashesAreSeparators() {
        MemoryActions.write(server, "{host: 'nas', outPattern: 'out\\\\{name}'}").write(item("a.jpg", "hello"), CONTEXT);
        assertEquals(Optional.of("hello"), server.content("out/a.jpg"));
    }

    @Test
    public void theConnectionsAreBoundedAndClosedAfterAll() throws Exception {
        MemoryActions.Write write = action(", maxConnections: 2");
        write.beforeAll();
        AtomicInteger busy = new AtomicInteger();
        AtomicInteger maxBusy = new AtomicInteger();
        server.beforeStore = () -> {
            maxBusy.accumulateAndGet(busy.incrementAndGet(), Math::max);
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            busy.decrementAndGet();
        };
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> threads = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String name = "f" + i + ".jpg";
            threads.add(Thread.ofVirtual().start(() -> run(failure, () -> write.write(item(name, "x"), CONTEXT))));
        }
        for (Thread thread : threads) {
            thread.join();
        }
        assertNull(failure.get());
        assertEquals(8, server.paths().size());
        assertTrue(maxBusy.get() <= 2, "at most 2 uploads at once: " + maxBusy.get());
        assertTrue(server.connects.get() <= 2, "at most 2 connections: " + server.connects.get());
        write.afterAll();
        assertEquals(0, server.open());
    }

    @Test
    public void theTargetIsRemoteTheEngineIsToldNoLocalPath() {
        MemoryActions.Write write = MemoryActions.write(server, "{host: 'nas', outPattern: '/out/{lastModified.Y}/{name}'}");
        WorkItem item = item("a.jpg", "hello");
        assertEquals(Optional.empty(), write.resolveTarget(item));
        assertEquals(TargetCheck.UNKNOWN, write.checkTarget(item, true));
        assertEquals(Optional.of(true), write.targetDirectoryVaries());
        assertEquals(Optional.of("/out/{lastModified.Y}/{name}"), write.defaultOutPattern());
        assertFalse(write.writesCompanions());
        assertEquals(Set.of("net:mem:nas"), write.requiredResources(item));
        assertEquals(Set.of(), write.touchedPaths(item));
    }

    @Test
    public void anItemsOwnPatternWins() {
        WorkItem item = item("a.jpg", "hello");
        item.setOutPattern("/mine/{name}");
        action("").write(item, CONTEXT);
        assertEquals(Optional.of("hello"), server.content("/mine/a.jpg"));
    }

    @Test
    public void aWrongConfigurationIsRefused() {
        IllegalArgumentException noPattern = assertThrows(IllegalArgumentException.class,
                () -> MemoryActions.write(server, "{host: 'nas'}"));
        assertEquals("mem.write: the output pattern is missing", noPattern.getMessage());
        IllegalArgumentException value = assertThrows(IllegalArgumentException.class,
                () -> action(", onConflict: {compare: 'fullHash'}"));
        assertEquals("mem.write: onConflict.compare cannot be \"fullHash\" (expected: size, sizeAndDate)",
                value.getMessage());
        assertThrows(IllegalArgumentException.class, () -> action(", onMissingKey: 'maybe'"));
        assertThrows(IllegalArgumentException.class, () -> action(", maxConnections: 0"));
    }
}

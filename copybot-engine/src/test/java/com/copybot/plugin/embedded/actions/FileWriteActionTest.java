package com.copybot.plugin.embedded.actions;

import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import com.copybot.plugin.api.action.WorkStatus;
import com.copybot.plugin.api.action.WriteContext;
import com.copybot.plugin.api.action.WriteResult;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class FileWriteActionTest {

    @TempDir
    Path tempDir;

    private FileWriteAction action(Path outFile, boolean overwrite) {
        FileWriteAction action = new FileWriteAction();
        action.loadConfig(JsonParser.parseString(
                "{\"outPattern\":\"" + outFile.toString().replace("\\", "\\\\") + "\",\"overwrite\":" + overwrite + "}"));
        return action;
    }

    private WorkItem itemWithContent(String name, byte[] content) throws IOException {
        Path source = tempDir.resolve(name);
        Files.write(source, content);
        WorkItem item = new WorkItem(source);
        item.getMetadatas().setSize((long) content.length);
        return item;
    }

    @Test
    public void overwriteTrueCreatesAMissingTargetFile() throws IOException {
        Path target = tempDir.resolve("out").resolve("created.bin");
        Files.createDirectories(target.getParent());
        WorkItem item = itemWithContent("src1.bin", "hello".getBytes());

        action(target, true).writeItem(item);

        assertTrue(Files.exists(target), "overwrite:true must be able to create the target");
        assertArrayEquals("hello".getBytes(), Files.readAllBytes(target));
    }

    @Test
    public void overwriteTrueReplacesAnExistingTargetFile() throws IOException {
        Path target = tempDir.resolve("existing.bin");
        Files.write(target, "old content that is longer".getBytes());
        WorkItem item = itemWithContent("src2.bin", "new".getBytes());

        action(target, true).writeItem(item);

        assertArrayEquals("new".getBytes(), Files.readAllBytes(target), "target must be truncated then replaced");
    }

    @Test
    public void overwriteFalseFailsOnAnExistingTargetFile() throws IOException {
        Path target = tempDir.resolve("protected.bin");
        Files.write(target, "keep me".getBytes());
        WorkItem item = itemWithContent("src3.bin", "intruder".getBytes());

        assertThrows(RuntimeException.class, () -> action(target, false).writeItem(item));
        assertArrayEquals("keep me".getBytes(), Files.readAllBytes(target));
    }

    @Test
    public void missingParentDirectoriesAreCreated() throws IOException {
        Path target = tempDir.resolve("out").resolve("nested").resolve("deep.bin");
        WorkItem item = itemWithContent("src5.bin", "payload".getBytes());

        action(target, false).writeItem(item);

        assertArrayEquals("payload".getBytes(), Files.readAllBytes(target));
    }

    @Test
    public void progressPercentsAreProportionalNotZero() throws IOException {
        // 2 buffers of 8192: first update must report 50, not 0 (integer-division regression)
        byte[] content = new byte[16384];
        WorkItem item = itemWithContent("src4.bin", content);
        Path target = tempDir.resolve("progress.bin");

        FileWriteAction action = action(target, true);
        List<WorkStatus> statuses = new CopyOnWriteArrayList<>();
        action.setStatusWatcher(statuses::add);

        action.writeItem(item);

        List<Integer> percents = statuses.stream().map(WorkStatus::actionPercent).filter(p -> p >= 0).toList();
        assertTrue(percents.contains(50), "mid-copy percent must be 50, got " + percents);
        assertEquals(100, percents.getLast().intValue());
    }

    // ---- safe write (spec safe-write) ----

    /** file.write to outFile; extra is appended to the actionConfig object (e.g. ",\"deleteSource\":true"). */
    private FileWriteAction action(Path outFile, String extra) {
        FileWriteAction action = new FileWriteAction();
        action.loadConfig(JsonParser.parseString(
                "{\"outPattern\":\"" + outFile.toString().replace("\\", "\\\\") + "\"" + extra + "}"));
        return action;
    }

    private Path nasFile(String name, String content) throws IOException {
        return Files.writeString(Files.createDirectories(tempDir.resolve("nas")).resolve(name), content);
    }

    @Test
    public void overwriteFalseSkipsAnIdenticalTarget() throws IOException {
        Path target = nasFile("same.bin", "same");
        WorkItem item = itemWithContent("src6.bin", "same".getBytes());

        WriteResult result = action(target, false).write(item, WriteContext.newRun());

        assertTrue(result.isSkipped(), "legacy overwrite=false: identical means skip, not error");
        assertEquals(target, result.target());
        assertTrue(result.reason().contains("same.bin"), result.reason());
    }

    @Test
    public void aDifferentTargetIsRenamedByDefaultAndTheResultTellsWhere() throws IOException {
        Path target = nasFile("photo.jpg", "other content");
        WorkItem item = itemWithContent("src7.bin", "abc".getBytes());

        WriteResult result = action(target, "").write(item, WriteContext.newRun());

        Path renamed = target.resolveSibling("photo (1).jpg");
        assertEquals(WriteResult.written(renamed), result);
        assertEquals("abc", Files.readString(renamed));
        assertEquals("other content", Files.readString(target));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                item.getMetadatas().raw().get(WorkItemMetadata.SHA256), "hash computed during the copy");
    }

    @Test
    public void deleteSourceRemovesTheSourceOnceWrittenAndVerified() throws IOException {
        Path target = tempDir.resolve("nas").resolve("photo.jpg");
        WorkItem item = itemWithContent("src8.bin", "abc".getBytes());

        action(target, ",\"deleteSource\":true,\"verify\":\"readBack\"").write(item, WriteContext.newRun());

        assertEquals("abc", Files.readString(target));
        assertFalse(Files.exists(tempDir.resolve("src8.bin")));
        assertTrue(item.isDeleted());
    }

    @Test
    public void deleteSourceKeepsTheSourceWhenTheWriteFails() throws IOException {
        Path target = nasFile("photo.jpg", "other content");
        WorkItem item = itemWithContent("src9.bin", "abc".getBytes());
        FileWriteAction action = action(target, ",\"deleteSource\":true,\"onConflict\":{\"ifDifferent\":\"error\"}");

        assertThrows(CopybotException.class, () -> action.write(item, WriteContext.newRun()));

        assertTrue(Files.exists(tempDir.resolve("src9.bin")));
        assertFalse(item.isDeleted());
    }

    @Test
    public void anIdenticalSkipDeletesTheSourceOnlyWhenComparedWithFullHash() throws IOException {
        Path target = nasFile("photo.jpg", "abc");
        WorkItem fullHash = itemWithContent("src10.bin", "abc".getBytes());
        WorkItem partialHash = itemWithContent("src11.bin", "abc".getBytes());

        assertTrue(action(target, ",\"deleteSource\":true,\"onConflict\":{\"compare\":\"fullHash\"}")
                .write(fullHash, WriteContext.newRun()).isSkipped());
        assertTrue(action(target, ",\"deleteSource\":true").write(partialHash, WriteContext.newRun()).isSkipped());

        assertFalse(Files.exists(tempDir.resolve("src10.bin")), "fullHash proved the destination holds everything");
        assertTrue(Files.exists(tempDir.resolve("src11.bin")), "partialHash did not read the whole content");
    }

    @Test
    public void aSkippedDifferentTargetNeverDeletesTheSource() throws IOException {
        Path target = nasFile("photo.jpg", "other content");
        WorkItem item = itemWithContent("src12.bin", "abc".getBytes());

        WriteResult result = action(target, ",\"deleteSource\":true,\"onConflict\":{\"compare\":\"fullHash\",\"ifDifferent\":\"skip\"}")
                .write(item, WriteContext.newRun());

        assertTrue(result.isSkipped());
        assertTrue(Files.exists(tempDir.resolve("src12.bin")));
    }

    @Test
    public void theEngineRunIdNamesTheTemporaryFile() throws IOException {
        Path target = tempDir.resolve("nas").resolve("photo.jpg");
        WorkItem item = itemWithContent("src13.bin", "abc".getBytes());
        FileWriteAction action = action(target, ",\"verify\":\"readBack\"");
        List<Path> temps = new CopyOnWriteArrayList<>();
        action.setStatusWatcher(status -> {
            if (!Files.isDirectory(target.getParent())) {
                return; // the first status comes before the target directory is created
            }
            try (var files = Files.list(target.getParent())) {
                files.filter(p -> p.getFileName().toString().endsWith(SafeFileWriter.TEMP_SUFFIX)).forEach(temps::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });

        action.write(item, new WriteContext("engine-run-42"));

        assertEquals(List.of(target.resolveSibling(".photo.jpg.engine-run-42" + SafeFileWriter.TEMP_SUFFIX)),
                temps.stream().distinct().toList(), "the copy went through a temporary file named after the run");
        assertFalse(Files.exists(temps.getFirst()));
    }

    @Test
    public void configWarningsIsEmptyBeforeTheConfigurationIsLoaded() {
        assertEquals(List.of(), new FileWriteAction().configWarnings());
    }

    @Test
    public void deleteSourceKeepsAUrlSourceWithoutError() throws IOException {
        Path file = Files.writeString(tempDir.resolve("remote.bin"), "abc");
        WorkItem item = new WorkItem(file.toUri().toURL(), () -> {
            try {
                return Files.newInputStream(file);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        Path target = tempDir.resolve("nas").resolve("remote.bin");

        WriteResult result = action(target, ",\"deleteSource\":true,\"verify\":\"readBack\"").write(item, WriteContext.newRun());

        assertEquals(WriteResult.written(target), result);
        assertEquals("abc", Files.readString(target));
        assertTrue(Files.exists(file), "a URL source is never deleted");
        assertFalse(item.isDeleted());
    }

    @Test
    public void aSourceThatCannotBeDeletedFailsTheItemOnceCopied() throws IOException {
        // the local location is a non-empty directory: deleteIfExists fails, on every platform
        Path undeletable = Files.createDirectories(tempDir.resolve("undeletable"));
        Files.writeString(undeletable.resolve("child"), "x");
        WorkItem item = new WorkItem(Files.writeString(tempDir.resolve("src14.bin"), "abc")) {
            @Override
            public Path getLocalLocation() {
                return undeletable;
            }
        };
        item.getMetadatas().setSize(3L);
        Path target = tempDir.resolve("nas").resolve("photo.jpg");

        CopybotException e = assertThrows(CopybotException.class,
                () -> action(target, ",\"deleteSource\":true").write(item, WriteContext.newRun()));

        assertInstanceOf(DirectoryNotEmptyException.class, e.getCause());
        assertTrue(e.getMessage().contains("photo.jpg"), e.getMessage());
        assertEquals("abc", Files.readString(target), "the copy is kept");
        assertFalse(item.isDeleted());
    }

    @Test
    public void twoItemsWritingTheSameTargetAtOnceAreSerializedAndTheSecondIsRenamed() throws Exception {
        // 100CANON/IMG_01.JPG and 101CANON/IMG_01.JPG, concurrency 2: the second waits for the first, then
        // finds "exists, different" and goes to "photo (1).jpg"
        Path target = tempDir.resolve("nas").resolve("photo.jpg");
        FileWriteAction action = action(target, "");
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        WorkItem slow = new WorkItem(Files.writeString(tempDir.resolve("src15.bin"), "abc")) {
            @Override
            public InputStream openInputStream() {
                firstStarted.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new ByteArrayInputStream("abc".getBytes());
            }
        };
        WorkItem second = itemWithContent("src16.bin", "xyz".getBytes());

        CompletableFuture<WriteResult> first = CompletableFuture.supplyAsync(() -> action.write(slow, WriteContext.newRun()));
        CompletableFuture<WriteResult> other;
        try {
            assertTrue(firstStarted.await(10, TimeUnit.SECONDS));
            other = CompletableFuture.supplyAsync(() -> action.write(second, WriteContext.newRun()));
        } finally {
            release.countDown();
        }

        assertEquals(WriteResult.written(target), first.get(10, TimeUnit.SECONDS));
        assertEquals(WriteResult.written(target.resolveSibling("photo (1).jpg")), other.get(10, TimeUnit.SECONDS));
        assertEquals("abc", Files.readString(target));
        assertEquals("xyz", Files.readString(target.resolveSibling("photo (1).jpg")));
    }

    /** Bounded: the thread is parked (on the target lock). */
    private static void awaitParked(Thread thread) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (thread.getState() != Thread.State.WAITING && thread.getState() != Thread.State.TIMED_WAITING) {
            assertTrue(System.currentTimeMillis() < deadline, "the thread did not wait within 10s");
            Thread.sleep(5);
        }
    }

    @Test
    public void anItemWaitingForItsTargetIsCancelledByAnInterrupt() throws Exception {
        Path target = tempDir.resolve("nas").resolve("photo.jpg");
        FileWriteAction action = action(target, "");
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        WorkItem slow = new WorkItem(Files.writeString(tempDir.resolve("src17.bin"), "abc")) {
            @Override
            public InputStream openInputStream() {
                firstStarted.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new ByteArrayInputStream("abc".getBytes());
            }
        };
        WorkItem waiting = itemWithContent("src18.bin", "xyz".getBytes());
        CompletableFuture<WriteResult> first = CompletableFuture.supplyAsync(() -> action.write(slow, WriteContext.newRun()));
        CompletableFuture<Throwable> failure = new CompletableFuture<>();
        Thread waiter;
        try {
            assertTrue(firstStarted.await(10, TimeUnit.SECONDS));
            waiter = Thread.ofVirtual().start(() -> {
                try {
                    action.write(waiting, WriteContext.newRun());
                    failure.complete(null);
                } catch (Throwable t) {
                    failure.complete(t);
                }
            });
            awaitParked(waiter);
            waiter.interrupt();

            Throwable t = failure.get(10, TimeUnit.SECONDS);
            assertInstanceOf(CopybotException.class, t, "the waiting item stops without writing");
            assertInstanceOf(InterruptedException.class, t.getCause());
        } finally {
            release.countDown();
        }
        assertEquals(WriteResult.written(target), first.get(10, TimeUnit.SECONDS));
        assertFalse(Files.exists(target.resolveSibling("photo (1).jpg")));
        assertEquals(0, action.lockedTargets(), "the target locks are removed once unused");
    }

    @Test
    public void aTargetCreatedByAnotherProcessDuringTheWriteIsResolvedAgain() throws IOException {
        Path target = tempDir.resolve("nas").resolve("photo.jpg");
        WorkItem item = itemWithContent("src19.bin", "abc".getBytes());
        FileWriteAction action = action(target, "");
        action.writer().setBeforeVerify(temp -> {
            try {
                if (!Files.exists(target)) {
                    Files.writeString(target, "another process"); // appears between the resolution and the move
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });

        WriteResult result = action.write(item, WriteContext.newRun());

        assertEquals(WriteResult.written(target.resolveSibling("photo (1).jpg")), result);
        assertEquals("another process", Files.readString(target), "never replaced");
        assertEquals("abc", Files.readString(target.resolveSibling("photo (1).jpg")));
    }

    @Test
    public void aTemporaryNameAlreadyTakenIsNeverRetriedAndTheErrorSaysWhy() throws IOException {
        Path target = tempDir.resolve("nas").resolve("photo.jpg");
        Path otherTemp = nasFile(".photo.jpg.run-7" + SafeFileWriter.TEMP_SUFFIX, "someone else's");
        WorkItem item = itemWithContent("src20.bin", "abc".getBytes());

        CopybotException e = assertThrows(CopybotException.class,
                () -> action(target, "").write(item, new WriteContext("run-7")));

        assertInstanceOf(FileAlreadyExistsException.class, e.getCause());
        assertTrue(e.getMessage().contains("photo.jpg"), e.getMessage());
        assertTrue(e.getMessage().contains(SafeFileWriter.TEMP_SUFFIX), "the detail names the colliding file: " + e.getMessage());
        assertEquals("someone else's", Files.readString(otherTemp));
        assertFalse(Files.exists(target));
        assertFalse(Files.exists(target.resolveSibling("photo (1).jpg")), "a temp collision is not a target conflict");
    }

    @Test
    public void overwriteNeverReplacesADirectoryAtTheTarget() throws IOException {
        for (String writeMode : new String[]{"tempAndRename", "direct"}) {
            Path target = Files.createDirectories(tempDir.resolve(writeMode).resolve("photo.jpg"));
            WorkItem item = itemWithContent("src-" + writeMode + ".jpg", "new".getBytes());
            FileWriteAction action = action(target,
                    ",\"onConflict\":{\"ifDifferent\":\"overwrite\"},\"writeMode\":\"" + writeMode + "\"");

            assertThrows(CopybotException.class, () -> action.writeItem(item), writeMode);
            assertTrue(Files.isDirectory(target), writeMode);
            try (var left = Files.list(target.getParent())) {
                assertEquals(List.of(target), left.toList(), writeMode + ": no temporary file left");
            }
        }
    }

    @Test
    public void theIoErrorDetailFallsBackToTheExceptionClass() {
        assertEquals("java.io.IOException", FileWriteAction.detail(new IOException()));
        assertEquals("boom", FileWriteAction.detail(new IOException("boom")));
    }

    @Test
    public void directModeIsWarned() {
        assertEquals(1, action(tempDir.resolve("x"), ",\"writeMode\":\"direct\"").configWarnings().size());
        assertEquals(List.of(), action(tempDir.resolve("x"), "").configWarnings());
    }

    // ---- sort in place: the target resolves to the source itself (final review, critical) ----

    /** The only copy of the photo, read from the destination tree itself. */
    private WorkItem onlyCopy(Path file) throws IOException {
        WorkItem item = new WorkItem(file);
        item.getMetadatas().setSize(Files.size(file));
        return item;
    }

    private void assertOnlyCopyKept(Path source, WorkItem item, WriteResult result) throws IOException {
        assertTrue(result.isSkipped(), "already at the destination");
        assertEquals(source, result.target());
        assertTrue(result.reason().contains("photo.jpg"), result.reason());
        assertTrue(Files.exists(source), "the only copy must never be deleted");
        assertEquals("the only copy", Files.readString(source), "the only copy must never be truncated");
        assertFalse(item.isDeleted());
        try (var files = Files.list(source.getParent())) {
            assertEquals(List.of(source), files.toList(), "no temporary file, no renamed copy");
        }
    }

    @Test
    public void sortInPlaceWithDeleteSourceAndFullHashNeverDeletesTheOnlyCopy() throws IOException {
        Path source = nasFile("photo.jpg", "the only copy");
        WorkItem item = onlyCopy(source);

        WriteResult result = action(source, ",\"deleteSource\":true,\"verify\":\"readBack\","
                + "\"onConflict\":{\"compare\":\"fullHash\",\"ifIdentical\":\"skip\"}").write(item, WriteContext.newRun());

        assertOnlyCopyKept(source, item, result);
    }

    @Test
    public void sortInPlaceDirectOverwriteNeverTruncatesTheOnlyCopy() throws IOException {
        Path source = nasFile("photo.jpg", "the only copy");
        WorkItem item = onlyCopy(source);

        WriteResult result = action(source, ",\"writeMode\":\"direct\",\"onConflict\":{\"ifIdentical\":\"overwrite\"}")
                .write(item, WriteContext.newRun());

        assertOnlyCopyKept(source, item, result);
    }

    @Test
    public void sortInPlaceTempAndRenameOverwriteWithDeleteSourceNeverDeletesTheOnlyCopy() throws IOException {
        Path source = nasFile("photo.jpg", "the only copy");
        WorkItem item = onlyCopy(source);

        WriteResult result = action(source, ",\"deleteSource\":true,\"verify\":\"readBack\","
                + "\"onConflict\":{\"ifIdentical\":\"overwrite\"}").write(item, WriteContext.newRun());

        assertOnlyCopyKept(source, item, result);
    }

    @Test
    public void resolveTargetAppliesTheOutPatternWithoutWriting(@TempDir Path dir) throws Exception {
        Path source = Files.writeString(dir.resolve("DSC_1.NEF"), "x");
        WorkItem item = new WorkItem(source);
        item.getMetadatas().display().put("name", "DSC_1.NEF");
        item.getMetadatas().setTime(WorkItemMetadata.CAPTURE_DATE, Instant.parse("2026-09-28T10:00:00Z"));
        FileWriteAction action = new FileWriteAction();
        String outPattern = dir.resolve("out").toString().replace('\\', '/') + "/{captureDate.Y}/{name}";
        action.loadConfig(JsonParser.parseString("{\"outPattern\":\"" + outPattern + "\",\"overwrite\":false}"));

        Path target = action.resolveTarget(item).orElseThrow();

        assertEquals(dir.resolve("out").resolve("2026").resolve("DSC_1.NEF"), target.toAbsolutePath().normalize());
        assertFalse(Files.exists(dir.resolve("out")), "resolving a target must not create anything");
    }
}

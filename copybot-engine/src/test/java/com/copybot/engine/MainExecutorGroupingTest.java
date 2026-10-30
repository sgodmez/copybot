package com.copybot.engine;

import com.copybot.engine.ControlFakes.FakeAction;
import com.copybot.engine.pipeline.ArchivesConfig;
import com.copybot.engine.pipeline.ExecutionMode;
import com.copybot.engine.pipeline.GroupingConfig;
import com.copybot.engine.pipeline.GroupingRuleConfig;
import com.copybot.engine.pipeline.ItemStatus;
import com.copybot.engine.pipeline.PipelineConfig;
import com.copybot.engine.pipeline.PipelineStatus;
import com.copybot.engine.pipeline.PipelineStep;
import com.copybot.engine.pipeline.PipelineStepConfig;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.engine.resume.ItemKey;
import com.copybot.engine.resume.ResumeStateStore;
import com.copybot.plugin.api.action.IInAction;
import com.copybot.plugin.api.action.IOutAction;
import com.copybot.plugin.api.action.ListingSink;
import com.copybot.plugin.api.action.RunOutcome;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import com.copybot.plugin.api.action.WriteContext;
import com.copybot.plugin.api.action.WriteResult;
import com.copybot.resources.ResourcesEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.copybot.engine.ControlFakes.awaitTrue;
import static com.copybot.engine.ControlFakes.registry;
import static com.copybot.engine.ControlFakes.singlePhase;
import static com.copybot.engine.ControlFakes.withResume;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The listing pipe of MainExecutor (spec grouping/archives, "Où ça se place"): archives unfolded, then grouped. */
class MainExecutorGroupingTest {

    private static final Instant DAY_1 = Instant.parse("2026-09-01T10:00:00Z");
    private static final Instant DAY_9 = Instant.parse("2026-09-09T10:00:00Z");

    @TempDir
    Path dir;

    /** Lists given items in one folder, then signals it (or not). Records which listFiles was called. */
    static final class FolderIn extends FakeAction implements IInAction {
        final String folder;
        final List<WorkItem> items;
        final boolean signals;
        Runnable beforeEmit;
        Runnable afterEmit;
        String calledWith;

        FolderIn(String folder, List<WorkItem> items, boolean signals) {
            this.folder = folder;
            this.items = items;
            this.signals = signals;
        }

        @Override
        public void listFiles(Consumer<WorkItem> consumer) {
            calledWith = "consumer";
            items.forEach(consumer);
        }

        @Override
        public void listFiles(ListingSink sink) {
            calledWith = "sink";
            if (beforeEmit != null) {
                beforeEmit.run();
            }
            items.forEach(sink::item);
            if (afterEmit != null) {
                afterEmit.run();
            }
            if (signals) {
                sink.folderDone(folder);
            }
        }

        @Override
        public boolean signalsFolders() {
            return signals;
        }
    }

    /** An out step that writes the companions (the grouping rules copy some), counting its writes. */
    static final class CompanionOut extends FakeAction implements IOutAction {
        final AtomicInteger writes = new AtomicInteger();
        volatile Runnable onWrite;

        @Override
        public WriteResult write(WorkItem item, WriteContext context) {
            if (onWrite != null) {
                onWrite.run();
            }
            writes.incrementAndGet();
            return WriteResult.written(null);
        }

        @Override
        public boolean writesCompanions() {
            return true;
        }
    }

    private WorkItem file(String name, Instant date) {
        try {
            Path path = Files.writeString(dir.resolve(name), name);
            WorkItem item = new WorkItem(path);
            item.getMetadatas().setTime(WorkItemMetadata.LAST_MODIFIED, date);
            return item;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path zip(String name, int entries) throws IOException {
        Path zip = dir.resolve(name);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (int i = 1; i <= entries; i++) {
                ZipEntry entry = new ZipEntry("photos/IMG_" + i + ".JPG");
                entry.setLastModifiedTime(FileTime.from(DAY_1));
                out.putNextEntry(entry);
                out.write(("photo " + i).getBytes());
                out.closeEntry();
            }
        }
        return zip;
    }

    private static GroupingConfig rawXmp() {
        return new GroupingConfig(List.of(new GroupingRuleConfig("raw-jpeg-xmp", null, null, null, null, null, null,
                null)));
    }

    private static ArchivesConfig unfold() {
        return new ArchivesConfig(true, null, null, null, null);
    }

    private static PipelineConfig listing(GroupingConfig grouping, ArchivesConfig archives) {
        return new PipelineConfig(null, null, null, null, null, null, null, null, grouping, archives);
    }

    /** One run-end action for every outcome, logging its calls. */
    private static List<String> runEndLog(MainExecutor exec) {
        List<String> log = new CopyOnWriteArrayList<>();
        MainExecutorRunEndTest.Act action = new MainExecutorRunEndTest.Act("end", log, (report, context) -> { });
        exec.setRunEnd(List.of(new RunEnd.Entry("end", action, EnumSet.allOf(RunOutcome.class), Duration.ofSeconds(30))));
        return log;
    }

    private static PipelineConfig streaming(GroupingConfig grouping) {
        return new PipelineConfig(null, null, null, null, ExecutionMode.STREAMING, null, null, null, grouping, null);
    }

    /** Whether the out step wrote something within this time. */
    private static boolean awaitWrite(CompanionOut out, long millis) {
        long deadline = System.currentTimeMillis() + millis;
        while (out.writes.get() == 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return out.writes.get() > 0;
    }

    private WorkItem fileIn(String folder, String name, Instant date) {
        try {
            Files.createDirectories(dir.resolve(folder));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file(folder + "/" + name, date);
    }

    /** Lists one file in each of two folders, each signalled once listed; runs a hook between the two. */
    static final class TwoFolders extends FakeAction implements IInAction {
        final WorkItem first;
        final WorkItem second;
        final Runnable between;

        TwoFolders(WorkItem first, WorkItem second, Runnable between) {
            this.first = first;
            this.second = second;
            this.between = between;
        }

        @Override
        public void listFiles(Consumer<WorkItem> consumer) {
            throw new UnsupportedOperationException("listed through the sink");
        }

        @Override
        public void listFiles(ListingSink sink) {
            sink.item(first);
            sink.folderDone(first.getListedFolder());
            between.run();
            sink.item(second);
            sink.folderDone(second.getListedFolder());
        }

        @Override
        public boolean signalsFolders() {
            return true;
        }
    }

    private ResumeStateStore store() {
        return new ResumeStateStore(dir.resolve("p.state.json"));
    }

    private Path workspaces() {
        return dir.resolve("ws");
    }

    private List<Path> runFolders() throws IOException {
        if (!Files.isDirectory(workspaces())) {
            return List.of();
        }
        try (Stream<Path> runs = Files.list(workspaces())) {
            return runs.filter(p -> p.getFileName().toString().startsWith("run-")).toList();
        }
    }

    private MainExecutor prepared(IInAction in, GroupingConfig grouping, ArchivesConfig archives) {
        MainExecutor exec = withResume(in, new CompanionOut(), registry(Map.of("disk:*", 1000)), store());
        exec.setListingConfig(listing(grouping, archives));
        exec.setArchiveBase(workspaces());
        exec.prepare();
        return exec;
    }

    private static WorkItemExecution named(MainExecutor exec, String name) {
        return exec.getState().getWorkItems().stream()
                .filter(e -> e.getWorkItem().getNameDisplay().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void theCompanionIsAttachedToItsMainAtThePreparation() {
        FolderIn in = new FolderIn(dir.toString(),
                List.of(file("IMG_1.CR3", DAY_1), file("IMG_1.xmp", DAY_1)), true);
        MainExecutor exec = prepared(in, rawXmp(), null);

        assertEquals(PipelineStatus.PREPARED, exec.getState().getStatus());
        assertEquals(1, exec.getState().getWorkItems().size(), "one execution for the group");
        WorkItem main = named(exec, "IMG_1.CR3").getWorkItem();
        assertEquals(1, main.getCompanions().size());
        assertEquals("IMG_1.xmp", main.getCompanions().get(0).item().getNameDisplay());
        assertEquals("sink", in.calledWith);
    }

    @Test
    void theEntriesOfAZipAreItemsKeyedByTheirDateAndName() throws IOException {
        FolderIn in = new FolderIn(dir.toString(), List.of(new WorkItem(zip("photos.zip", 2))), true);
        MainExecutor exec = prepared(in, null, unfold());

        assertEquals(PipelineStatus.PREPARED, exec.getState().getStatus());
        assertEquals(2, exec.getState().getWorkItems().size());
        for (String name : List.of("IMG_1.JPG", "IMG_2.JPG")) {
            WorkItemExecution entry = named(exec, name);
            assertFalse(entry.getWorkItem().isLocal());
            assertEquals(new ItemKey(DAY_1, name), entry.getResumeKey().orElseThrow());
        }
        assertEquals(1, exec.getState().getArchivesUnfolded());
        assertEquals(0, exec.getState().getArchivesToDownload(), "a local archive is not downloaded");
        assertEquals(1, runFolders().size(), "the work folder lives until the copy");

        exec.execute(null);

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
        assertEquals(List.of(), runFolders(), "removed at the end of the copy");
    }

    @Test
    void aCorruptZipIsAnItemInErrorAndTheOthersAreProcessed() throws IOException {
        Path bad = Files.writeString(dir.resolve("bad.zip"), "not a zip at all");
        FolderIn in = new FolderIn(dir.toString(), List.of(new WorkItem(bad), file("ok.jpg", DAY_1)), true);
        MainExecutor exec = singlePhase(in, new CompanionOut(), registry(Map.of("disk:*", 1000)));
        exec.setListingConfig(listing(null, unfold()));
        exec.setArchiveBase(workspaces());

        exec.run();

        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus());
        assertEquals(ItemStatus.ERROR, named(exec, "bad.zip").getStatus());
        assertNotNull(named(exec, "bad.zip").getError());
        assertEquals(ItemStatus.DONE, named(exec, "ok.jpg").getStatus());
        assertEquals(List.of(), runFolders());
    }

    @Test
    void aGroupWhoseMainIsBeforeTheCursorIsSkippedWhole() {
        store().writeCursor(new ItemKey(Instant.parse("2026-09-05T10:00:00Z"), "IMG_5.JPG"));
        FolderIn in = new FolderIn(dir.toString(),
                List.of(file("IMG_1.CR3", DAY_1), file("IMG_1.xmp", DAY_9), file("IMG_9.JPG", DAY_9)), true);
        MainExecutor exec = prepared(in, rawXmp(), null);

        assertEquals(PipelineStatus.PREPARED, exec.getState().getStatus());
        assertEquals(2, exec.getState().getWorkItems().size(), "the companion has no execution of its own");
        WorkItemExecution group = named(exec, "IMG_1.CR3");
        assertEquals(ItemStatus.SKIPPED, group.getStatus());
        assertEquals(1, group.getWorkItem().getCompanions().size());
        assertEquals(ItemStatus.PENDING, named(exec, "IMG_9.JPG").getStatus());
    }

    @Test
    void cancellingWhileUnfoldingABigZipRemovesTheWorkFolder() throws Exception {
        Path zip = zip("big.zip", 300);
        long size = Files.size(zip);
        // not a local file: copied into the work folder first
        WorkItem remote = WorkItem.remote("big.zip", "ftp://host/in", "ftp://host/in", size, DAY_1,
                () -> {
                    try {
                        return Files.newInputStream(zip);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }, null);
        FolderIn in = new FolderIn("ftp://host/in", List.of(remote), true);
        MainExecutor exec = singlePhase(in, new CompanionOut(), registry(Map.of("disk:*", 1000)));
        exec.setListingConfig(listing(null, unfold()));
        exec.setArchiveBase(workspaces());
        in.beforeEmit = exec::pause; // the listing then waits at the first entry it emits
        Thread run = Thread.ofVirtual().start(exec::run);

        awaitTrue(() -> {
            try {
                return runFolders().stream().anyMatch(this::holdsACopy);
            } catch (IOException e) {
                return false;
            }
        });
        assertEquals(1, exec.getState().getArchivesToDownload());
        assertEquals(size, exec.getState().getBytesToDownload());
        exec.cancel();
        run.join(10_000);

        assertFalse(run.isAlive());
        assertEquals(PipelineStatus.CANCELLED, exec.getState().getStatus());
        assertEquals(List.of(), runFolders(), "the work folder is removed on cancel");
    }

    private boolean holdsACopy(Path runFolder) {
        try (Stream<Path> files = Files.list(runFolder)) {
            return files.anyMatch(f -> f.getFileName().toString().endsWith("big.zip"));
        } catch (IOException e) {
            return false;
        }
    }

    @Test
    void withoutTheBlocksTheListingIsCalledAsBefore() throws IOException {
        FolderIn in = new FolderIn(dir.toString(),
                List.of(file("IMG_1.CR3", DAY_1), file("IMG_1.xmp", DAY_1)), true);
        MainExecutor exec = prepared(in, null, new ArchivesConfig(false, null, null, null, null));

        assertEquals("consumer", in.calledWith, "no pipe: listFiles(Consumer) as before");
        assertEquals(2, exec.getState().getWorkItems().size());
        assertEquals(List.of(), runFolders(), "no work folder without unfolding");
    }

    @Test
    void anInvalidGroupingRefusesThePreparationBeforeAnyListing() {
        FolderIn in = new FolderIn(dir.toString(), List.of(file("IMG_1.CR3", DAY_1)), true);
        GroupingConfig unknown = new GroupingConfig(List.of(new GroupingRuleConfig("nope", null, null, null, null,
                null, null, null)));
        MainExecutor exec = prepared(in, unknown, null);

        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus());
        assertTrue(exec.getState().isPreparationFailed());
        assertEquals(ResourcesEngine.getString("grouping.preset.unknown", 1, "nope"),
                exec.getState().getFailure().getMessage());
        assertEquals(null, in.calledWith, "nothing listed");
    }

    @Test
    void copiedCompanionsWithAnOutputThatCannotWriteThemAreRefused() {
        FolderIn in = new FolderIn(dir.toString(), List.of(file("IMG_1.CR3", DAY_1)), true);
        MainExecutor exec = withResume(in, new ControlFakes.GatedOut(Set.of(), null), registry(Map.of("disk:*", 1000)),
                store());
        exec.setListingConfig(listing(rawXmp(), null));

        exec.prepare();

        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus());
        assertEquals(ResourcesEngine.getString("grouping.companions-unsupported", "GatedOut"),
                exec.getState().getFailure().getMessage());
        assertEquals(null, in.calledWith, "nothing listed");
    }

    @Test
    void streamingWithInputsThatSignalTheirFoldersProcessesFolderByFolder() {
        CompanionOut out = new CompanionOut();
        AtomicBoolean copiedWhileListing = new AtomicBoolean();
        WorkItem first = fileIn("a", "IMG_1.CR3", DAY_1);
        WorkItem second = fileIn("b", "IMG_2.CR3", DAY_9);
        IInAction in = new TwoFolders(first, second, () -> copiedWhileListing.set(awaitWrite(out, 5000)));
        MainExecutor exec = singlePhase(in, out, registry(Map.of("disk:*", 1000)));
        exec.setListingConfig(streaming(rawXmp()));

        exec.stream();

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
        assertTrue(copiedWhileListing.get(), "the first folder copied before the second one is listed");
        assertEquals(List.of(ResourcesEngine.getString("grouping.streaming-by-folder")),
                exec.getState().getWarnings());
        assertEquals(2, out.writes.get());
    }

    @Test
    void streamingWithAnInputThatSignalsNoFolderFallsBackToTheAutomaticMode() {
        AtomicInteger listingsDone = new AtomicInteger();
        List<Integer> doneAtWrites = new CopyOnWriteArrayList<>();
        CompanionOut out = new CompanionOut();
        out.onWrite = () -> doneAtWrites.add(listingsDone.get());
        FolderIn signalling = new FolderIn(dir.resolve("a").toString(),
                List.of(fileIn("a", "IMG_1.CR3", DAY_1)), true);
        FolderIn silent = new FolderIn(dir.resolve("b").toString(), List.of(fileIn("b", "IMG_2.CR3", DAY_9)), false);
        silent.beforeEmit = () -> awaitWrite(out, 300); // a streaming run would copy the other input's file meanwhile
        signalling.afterEmit = listingsDone::incrementAndGet;
        silent.afterEmit = listingsDone::incrementAndGet;
        MainExecutor exec = new MainExecutor(
                List.of(new PipelineStep<>(null, signalling, ControlFakes.emptyConfig()),
                        new PipelineStep<>(null, silent, ControlFakes.emptyConfig())),
                List.of(new PipelineStep<>(null, out, ControlFakes.emptyConfig())),
                true, null, registry(Map.of("disk:*", 1000)));
        exec.setListingConfig(streaming(rawXmp()));
        List<String> runEnd = runEndLog(exec);

        exec.stream();

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
        assertEquals(List.of("end"), runEnd, "the run-end actions run once, through the fallback");
        assertEquals(List.of(2, 2), doneAtWrites, "every input listed before the first copy");
        assertEquals(List.of(ResourcesEngine.getString("grouping.streaming-fallback", "FolderIn")),
                exec.getState().getWarnings());
    }

    @Test
    void theFallbackWithResumePreparesThenCopies() {
        FolderIn in = new FolderIn(dir.toString(),
                List.of(file("IMG_1.CR3", DAY_1), file("IMG_1.xmp", DAY_1)), false);
        CompanionOut out = new CompanionOut();
        MainExecutor exec = withResume(in, out, registry(Map.of("disk:*", 1000)), store());
        exec.setListingConfig(streaming(rawXmp()));
        List<String> runEnd = runEndLog(exec);

        exec.stream();

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
        assertEquals(List.of("end"), runEnd, "the run-end actions run once, after the execution");
        assertEquals(List.of(ResourcesEngine.getString("grouping.streaming-fallback", "FolderIn")),
                exec.getState().getWarnings(), "told once, through the preparation and the execution");
        assertEquals(1, exec.getState().getWorkItems().size());
        assertEquals(1, out.writes.get());
        assertTrue(Files.exists(dir.resolve("p.state.json")), "the cursor written as in the automatic mode");
    }

    @Test
    void aPreparationOfAStreamingPipelineThatFallsBackTellsIt() {
        FolderIn in = new FolderIn(dir.toString(), List.of(file("IMG_1.CR3", DAY_1)), false);
        MainExecutor exec = withResume(in, new CompanionOut(), registry(Map.of("disk:*", 1000)), store());
        exec.setListingConfig(streaming(rawXmp()));

        exec.prepare();

        assertEquals(PipelineStatus.PREPARED, exec.getState().getStatus());
        assertEquals(List.of(ResourcesEngine.getString("grouping.streaming-fallback", "FolderIn")),
                exec.getState().getWarnings());
    }

    @Test
    void aStreamingRunWhoseStepsCannotBeResolvedFailsItsPreparationWithOrWithoutRules() {
        for (GroupingConfig grouping : Arrays.asList(null, rawXmp())) {
            PipelineConfig config = new PipelineConfig(
                    List.of(new PipelineStepConfig(null, "no.such.input", null, null, null, null, null, null)),
                    null, null, null, ExecutionMode.STREAMING, null, null, null, grouping, null);
            MainExecutor exec = new MainExecutor(config, null, registry(Map.of("disk:*", 1000)), null);

            exec.stream();

            assertEquals(PipelineStatus.ERROR, exec.getState().getStatus(), String.valueOf(grouping));
            assertTrue(exec.getState().isPreparationFailed(), String.valueOf(grouping));
            assertNotNull(exec.getState().getFailure());
        }
    }

    @Test
    void theInputsWithoutFolderSignalOnlyMatterWithGroupingRules() {
        List<PipelineStep<IInAction>> steps = List.of(
                new PipelineStep<>(null, new FolderIn(dir.toString(), List.of(), true), ControlFakes.emptyConfig()),
                new PipelineStep<>(null, new FolderIn(dir.toString(), List.of(), false), ControlFakes.emptyConfig()));

        assertEquals(List.of("FolderIn"), ListingPipe.inputsWithoutFolderSignal(streaming(rawXmp()), steps));
        assertEquals(List.of(), ListingPipe.inputsWithoutFolderSignal(streaming(null), steps));
        assertEquals(List.of(), ListingPipe.inputsWithoutFolderSignal(streaming(rawXmp()), steps.subList(0, 1)));
    }

    @Test
    void aNewPreparationOfTheEngineRemovesTheWorkFolderOfThePreviousPlan() throws IOException {
        try (CopybotEngine engine = new CopybotEngine(ControlFakes.config())) {
            MainExecutor first = withResume(
                    new FolderIn(dir.toString(), List.of(new WorkItem(zip("a.zip", 1))), true),
                    new CompanionOut(), registry(Map.of("disk:*", 1000)), store());
            first.setListingConfig(listing(null, unfold()));
            first.setArchiveBase(workspaces());
            engine.prepare(first);
            List<Path> before = runFolders();
            assertEquals(1, before.size());

            MainExecutor second = withResume(new FolderIn(dir.toString(), List.of(), true),
                    new CompanionOut(), registry(Map.of("disk:*", 1000)), store());
            engine.prepare(second);

            assertEquals(List.of(), runFolders(), "the plan replaced, its work folder removed");
        }
    }

    @Test
    void closingTheEngineRemovesTheWorkFolderOfAPlanNeverExecuted() throws IOException {
        CopybotEngine engine = new CopybotEngine(ControlFakes.config());
        MainExecutor plan = withResume(
                new FolderIn(dir.toString(), List.of(new WorkItem(zip("a.zip", 1))), true),
                new CompanionOut(), registry(Map.of("disk:*", 1000)), store());
        plan.setListingConfig(listing(null, unfold()));
        plan.setArchiveBase(workspaces());
        engine.prepare(plan);
        assertEquals(1, runFolders().size());

        engine.close();

        assertEquals(List.of(), runFolders());
    }

    @Test
    void aListingThatMeetsNoArchiveOpensNoWorkFolder() throws IOException {
        FolderIn in = new FolderIn(dir.toString(), List.of(file("IMG_1.JPG", DAY_1)), true);
        MainExecutor exec = prepared(in, null, unfold());

        assertEquals(PipelineStatus.PREPARED, exec.getState().getStatus());
        assertEquals("sink", in.calledWith, "the pipe is there");
        assertEquals(List.of(), runFolders(), "opened at the first archive met, none here");
    }

    /** Waits until stopped; then, still unwinding, reads its entry once more (e.g. a stream being closed). */
    static final class UnwindingReader extends FakeAction implements IOutAction {
        final CountDownLatch entered = new CountDownLatch(1);
        final AtomicBoolean readWhileUnwinding = new AtomicBoolean();

        @Override
        public WriteResult write(WorkItem item, WriteContext context) {
            entered.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300);
                while (System.nanoTime() < until) {
                    Thread.onSpinWait(); // still busy after the stop
                }
                try (InputStream entry = item.openInputStream()) {
                    readWhileUnwinding.set(entry.read() >= 0);
                } catch (IOException | RuntimeException failed) {
                    readWhileUnwinding.set(false);
                }
                Thread.currentThread().interrupt();
            }
            return WriteResult.written(null);
        }
    }

    @Test
    void theWorkFolderIsRemovedOnceTheTasksOfTheStoppedPhaseAreOver() throws Exception {
        FolderIn in = new FolderIn(dir.toString(), List.of(new WorkItem(zip("a.zip", 1))), true);
        UnwindingReader out = new UnwindingReader();
        MainExecutor exec = singlePhase(in, out, registry(Map.of("disk:*", 1000)));
        exec.setListingConfig(listing(null, unfold()));
        exec.setArchiveBase(workspaces());
        Thread run = Thread.ofVirtual().start(exec::run);
        assertTrue(out.entered.await(5, TimeUnit.SECONDS));

        exec.cancel();
        run.join(20_000);

        assertFalse(run.isAlive());
        assertEquals(PipelineStatus.CANCELLED, exec.getState().getStatus());
        assertTrue(out.readWhileUnwinding.get(), "the entry is still readable while its task unwinds");
        assertEquals(List.of(), runFolders(), "removed at the end of the phase");
    }

    @Test
    void closingTheEngineKeepsTheWorkFolderOfAPreparationStillRunning() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FolderIn in = new FolderIn(dir.toString(), List.of(new WorkItem(zip("a.zip", 1))), true);
        in.afterEmit = () -> { // a listing deaf to the stop: still running when close() gives up waiting
            entered.countDown();
            boolean interrupted = false;
            while (release.getCount() > 0) {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        };
        MainExecutor plan = withResume(in, new CompanionOut(), registry(Map.of("disk:*", 1000)), store());
        plan.setListingConfig(listing(null, unfold()));
        plan.setArchiveBase(workspaces());
        CopybotEngine engine = new CopybotEngine(ControlFakes.config());
        Thread preparing = Thread.ofVirtual().start(() -> engine.prepare(plan));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(1, runFolders().size());

            engine.close(); // gives up after its grace period: the preparation is still running

            assertEquals(1, runFolders().size(), "the operation still running keeps its work folder");
        } finally {
            release.countDown();
            preparing.join(30_000);
        }
        assertFalse(preparing.isAlive());
        assertEquals(List.of(), runFolders(), "removed once the preparation is over");
    }

    // ---- a plan abandoned (spec grouping/archives: the work folder lives until the plan is executed or abandoned) ----

    /** A plan of the given listing with unfolding on, its work folder made in {@link #workspaces()}. */
    private MainExecutor archivePlan(IInAction in, IOutAction out) {
        MainExecutor plan = withResume(in, out, registry(Map.of("disk:*", 1000)), store());
        plan.setListingConfig(listing(null, unfold()));
        plan.setArchiveBase(workspaces());
        return plan;
    }

    private FolderIn oneArchive() throws IOException {
        return new FolderIn(dir.toString(), List.of(new WorkItem(zip("a.zip", 1))), true);
    }

    @Test
    void aDiscardedPlanRemovesItsWorkFolderAndIsNotExecuted() throws IOException {
        try (CopybotEngine engine = new CopybotEngine(ControlFakes.config())) {
            Plan plan = engine.prepare(archivePlan(oneArchive(), new CompanionOut()));
            assertEquals(1, runFolders().size());

            plan.discard();
            plan.discard(); // idempotent

            assertEquals(List.of(), runFolders(), "the work folder of the abandoned plan is removed");
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> engine.execute(plan, null));
            assertEquals(ResourcesEngine.getString("engine.plan.discarded"), refused.getMessage());
            assertEquals(ResourcesEngine.getString("engine.plan.discarded"),
                    assertThrows(IllegalStateException.class, () -> engine.analyse(plan)).getMessage());
            // the refusals left the engine free
            engine.prepare(archivePlan(new FolderIn(dir.toString(), List.of(), true), new CompanionOut()));
        }
    }

    @Test
    void aPlanReplacedByANewerPreparationIsNotExecuted() throws Exception {
        try (CopybotEngine engine = new CopybotEngine(ControlFakes.config())) {
            Plan first = engine.prepare(archivePlan(oneArchive(), new CompanionOut()));
            Plan second = engine.prepare(archivePlan(new FolderIn(dir.toString(), List.of(), true),
                    new CompanionOut()));

            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> engine.execute(first, null));
            assertEquals(ResourcesEngine.getString("engine.plan.replaced"), refused.getMessage());
            assertEquals(PipelineStatus.SUCCESS, engine.execute(second, null).await(), "the newer one is executed");
        }
    }

    @Test
    void aPlanDiscardedWhileBeingPreparedKeepsItsWorkFolderUntilThePreparationEnds() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FolderIn in = oneArchive();
        in.afterEmit = () -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        try (CopybotEngine engine = new CopybotEngine(ControlFakes.config())) {
            AtomicReference<Plan> handedOut = new AtomicReference<>();
            MainExecutor plan = archivePlan(in, new CompanionOut());
            Thread preparing = Thread.ofVirtual().start(() -> engine.prepare(plan, handedOut::set));
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            handedOut.get().discard();
            assertEquals(1, runFolders().size(), "the listing still reads its archives");

            release.countDown();
            preparing.join(20_000);
            assertFalse(preparing.isAlive());
            assertEquals(List.of(), runFolders(), "removed once the preparation is over");
            assertEquals(ResourcesEngine.getString("engine.plan.discarded"),
                    assertThrows(IllegalStateException.class, () -> engine.execute(handedOut.get(), null))
                            .getMessage());
        }
    }

    /** Writes once let go by the test. */
    static final class GatedWrite extends FakeAction implements IOutAction {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public WriteResult write(WorkItem item, WriteContext context) {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return WriteResult.written(null);
        }
    }

    @Test
    void discardingAPlanBeingExecutedHasNoEffect() throws Exception {
        GatedWrite out = new GatedWrite();
        try (CopybotEngine engine = new CopybotEngine(ControlFakes.config())) {
            Plan plan = engine.prepare(archivePlan(oneArchive(), out));
            Execution run = engine.execute(plan, null);
            assertTrue(out.entered.await(5, TimeUnit.SECONDS));

            plan.discard();
            assertEquals(1, runFolders().size(), "the copy still reads its archives");

            out.release.countDown();
            assertEquals(PipelineStatus.SUCCESS, run.await());
            assertEquals(List.of(), runFolders(), "removed at the end of the copy");
        }
    }
}

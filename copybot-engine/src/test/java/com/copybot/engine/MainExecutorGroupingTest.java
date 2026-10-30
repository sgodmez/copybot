package com.copybot.engine;

import com.copybot.engine.ControlFakes.FakeAction;
import com.copybot.engine.pipeline.ArchivesConfig;
import com.copybot.engine.pipeline.GroupingConfig;
import com.copybot.engine.pipeline.GroupingRuleConfig;
import com.copybot.engine.pipeline.ItemStatus;
import com.copybot.engine.pipeline.PipelineConfig;
import com.copybot.engine.pipeline.PipelineStatus;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.engine.resume.ItemKey;
import com.copybot.engine.resume.ResumeStateStore;
import com.copybot.plugin.api.action.IInAction;
import com.copybot.plugin.api.action.IOutAction;
import com.copybot.plugin.api.action.ListingSink;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import com.copybot.plugin.api.action.WriteContext;
import com.copybot.plugin.api.action.WriteResult;
import com.copybot.resources.ResourcesEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

        @Override
        public WriteResult write(WorkItem item, WriteContext context) {
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
    void streamingWithAnInputThatSignalsNoFolderWarnsAndGroupsAtTheEnd() {
        FolderIn in = new FolderIn(dir.toString(),
                List.of(file("IMG_1.CR3", DAY_1), file("IMG_1.xmp", DAY_1)), false);
        CompanionOut out = new CompanionOut();
        MainExecutor exec = singlePhase(in, out, registry(Map.of("disk:*", 1000)));
        exec.setListingConfig(listing(rawXmp(), null));

        exec.stream();

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
        assertTrue(exec.getState().getWarnings().stream()
                        .anyMatch(w -> w.equals(ResourcesEngine.getString("grouping.no-folder-signal", "FolderIn"))),
                exec.getState().getWarnings().toString());
        assertEquals(1, exec.getState().getWorkItems().size());
        assertEquals(1, out.writes.get());
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
}

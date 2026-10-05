package com.copybot.engine;

import com.copybot.engine.MainExecutorResumeTest.RecordingAnalyze;
import com.copybot.engine.MainExecutorResumeTest.RecordingOut;
import com.copybot.engine.pipeline.ItemStatus;
import com.copybot.engine.pipeline.PipelineState;
import com.copybot.engine.pipeline.PipelineStatus;
import com.copybot.engine.pipeline.PipelineStep;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.engine.resume.DestinationCheck;
import com.copybot.engine.resume.DestinationMatch;
import com.copybot.engine.resume.ItemKey;
import com.copybot.engine.resume.ResumeContext;
import com.copybot.engine.resume.ResumeMode;
import com.copybot.engine.resume.ResumePoint;
import com.copybot.engine.resume.ResumeSource;
import com.copybot.engine.resume.ResumeStateStore;
import com.copybot.plugin.api.action.IInAction;
import com.copybot.plugin.api.action.IOutAction;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import com.copybot.resources.ResourcesEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.copybot.engine.MainExecutorResumeTest.emptyConfig;
import static com.copybot.engine.MainExecutorResumeTest.registry;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The streaming phase (spec execution-mode §2): every file processed as soon as it is listed, the resume point known
 * before the listing applied at the listing, the cursor written at the end like execute() does.
 */
public class MainExecutorStreamingTest {

    @TempDir
    Path tempDir;

    /**
     * One file per day of September, dated through lastModified. After each file from {@code waitFrom} on, the
     * listing waits (5 s at most) until the out step has written it: it records whether it was processed while the
     * listing was still running. A day in {@code undated} has no date; {@code failAfter} &gt; 0 fails the listing
     * after that many files.
     */
    final class DatedIn extends MainExecutorResumeTest.FakeAction implements IInAction {
        final int days;
        final int waitFrom;
        final Set<Integer> undated;
        final int failAfter;
        Set<String> written = Set.of();
        /** The days after which the listing waits, instead of {@code waitFrom} on (null). */
        Set<Integer> waitDays;
        /** How long the listing waits for each file to be written. */
        long waitMillis = 5000;
        final List<String> processedWhileListing = new CopyOnWriteArrayList<>();

        DatedIn(int days, int waitFrom, Set<Integer> undated, int failAfter) {
            this.days = days;
            this.waitFrom = waitFrom;
            this.undated = undated;
            this.failAfter = failAfter;
        }

        @Override
        public void listFiles(Consumer<WorkItem> consumer) {
            for (int day = 1; day <= days; day++) {
                if (failAfter > 0 && day > failAfter) {
                    throw new IllegalStateException("listing failed");
                }
                String name = String.format("IMG_%02d.JPG", day);
                try {
                    WorkItem wi = new WorkItem(Files.createFile(tempDir.resolve(name)));
                    if (!undated.contains(day)) {
                        wi.getMetadatas().setTime(WorkItemMetadata.LAST_MODIFIED,
                                Instant.parse(String.format("2026-09-%02dT10:00:00Z", day)));
                    }
                    consumer.accept(wi);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                boolean waits = waitDays != null ? waitDays.contains(day) : waitFrom > 0 && day >= waitFrom;
                if (waits && awaitWritten(name)) {
                    processedWhileListing.add(name);
                }
            }
        }

        private boolean awaitWritten(String name) {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis);
            while (System.nanoTime() < deadline) {
                if (written.contains(name)) {
                    return true;
                }
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return false;
        }
    }

    /** Writes nothing; its target is nas/&lt;day of the file name&gt;/&lt;name&gt; (for the destination probe). */
    final class DayOut extends MainExecutorResumeTest.FakeAction implements IOutAction {
        final Set<String> written = ConcurrentHashMap.newKeySet();
        Optional<Boolean> varies = Optional.of(true);

        @Override
        public void writeItem(WorkItem item) {
            written.add(item.getNameDisplay());
        }

        @Override
        public Optional<Path> resolveTarget(WorkItem item) {
            String name = item.getNameDisplay();
            return Optional.of(tempDir.resolve("nas").resolve(name.substring(4, 6)).resolve(name));
        }

        @Override
        public Optional<Boolean> targetDirectoryVaries() {
            return varies;
        }
    }

    private ResumeStateStore store() {
        return new ResumeStateStore(tempDir.resolve("p.state.json"));
    }

    private static ItemKey day(int d) {
        return new ItemKey(Instant.parse(String.format("2026-09-%02dT10:00:00Z", d)), String.format("IMG_%02d.JPG", d));
    }

    private MainExecutor executor(DatedIn in, RecordingAnalyze analyze, IOutAction out, ResumeMode mode,
                                  Consumer<PipelineState> watcher) {
        return executorWith(in, analyze, out, mode == null ? null : new ResumeContext(mode, store()), watcher);
    }

    private MainExecutor executorWith(DatedIn in, RecordingAnalyze analyze, IOutAction out, ResumeContext resume,
                                  Consumer<PipelineState> watcher) {
        return new MainExecutor(
                List.of(new PipelineStep<>(null, in, emptyConfig())),
                List.of(new PipelineStep<>(null, analyze, emptyConfig()), new PipelineStep<>(null, out, emptyConfig())),
                1, false, watcher, registry(), resume);
    }

    private ResumeContext destination(ResumeMode mode, DestinationCheck check, DestinationMatch match) {
        return new ResumeContext(mode, store(), check, match);
    }

    private void nasDay(int day) throws IOException {
        Files.createDirectories(tempDir.resolve("nas").resolve(String.format("%02d", day)));
    }

    private DatedIn in(int days, int waitFrom) {
        return new DatedIn(days, waitFrom, Set.of(), 0);
    }

    private static List<String> names(Set<String> names) {
        return names.stream().sorted().toList();
    }

    @Test
    public void withoutResumeEveryFileIsCopiedWhileTheListingRuns() {
        DatedIn in = in(3, 1);
        RecordingOut out = new RecordingOut(null);
        in.written = out.written;
        MainExecutor exec = executor(in, new RecordingAnalyze(), out, (ResumeMode) null, null);

        exec.stream();

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
        assertEquals(List.of("IMG_01.JPG", "IMG_02.JPG", "IMG_03.JPG"), in.processedWhileListing,
                "each file is copied as soon as it is listed");
        assertFalse(Files.exists(store().getPath()), "no resume: no state file");
    }

    @Test
    public void runWithoutResumeStillWaitsForTheEndOfTheListing() {
        DatedIn in = in(2, 1);
        RecordingOut out = new RecordingOut(null);
        in.written = out.written;
        in.waitMillis = 300; // the files are never written while listing: do not wait long
        MainExecutor exec = executor(in, new RecordingAnalyze(), out, (ResumeMode) null, null);

        exec.run();

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
        assertTrue(in.processedWhileListing.isEmpty(), "only the streaming phase processes while listing");
        assertEquals(Set.of("IMG_01.JPG", "IMG_02.JPG"), out.written);
    }

    @Test
    public void stateModeSkipsTheFilesBeforeTheCursorAtTheListingAndCopiesTheOthersWhileListing() {
        store().writeCursor(day(2));
        DatedIn in = in(4, 3);
        RecordingAnalyze analyze = new RecordingAnalyze();
        RecordingOut out = new RecordingOut(null);
        in.written = out.written;
        MainExecutor exec = executor(in, analyze, out, ResumeMode.STATE, null);

        exec.stream();

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
        assertEquals(Set.of("IMG_03.JPG", "IMG_04.JPG"), analyze.seen, "the old files are never read");
        assertEquals(Set.of("IMG_03.JPG", "IMG_04.JPG"), out.written);
        assertEquals(List.of("IMG_03.JPG", "IMG_04.JPG"), in.processedWhileListing);
        List<WorkItemExecution> items = exec.getOrderedItems();
        assertEquals(List.of(ItemStatus.SKIPPED, ItemStatus.SKIPPED, ItemStatus.DONE, ItemStatus.DONE),
                items.stream().map(WorkItemExecution::getStatus).toList());
        assertTrue(items.get(0).isSkippedByResumePoint());
        String cursorDate = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
                .withZone(java.time.ZoneId.systemDefault()).format(day(2).date());
        assertEquals(ResourcesEngine.getString("resume.skip.state", "IMG_02.JPG", cursorDate), items.get(0).getSkipReason());
        assertEquals(ResumePoint.after(day(2)), exec.getState().getResumeProposal().point(),
                "the resume point is published for the view");
        assertEquals(ResumeSource.STATE, exec.getState().getResumeProposal().source());
        assertEquals(day(4), store().readCursor().orElseThrow(), "the cursor advances like after execute()");
    }

    @Test
    public void stateModeWithoutCursorCopiesEverythingAndWritesTheCursor() {
        DatedIn in = in(3, 1);
        RecordingOut out = new RecordingOut(null);
        in.written = out.written;
        MainExecutor exec = executor(in, new RecordingAnalyze(), out, ResumeMode.STATE, null);

        exec.stream();

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
        assertEquals(3, in.processedWhileListing.size());
        assertEquals(ResumeSource.NONE, exec.getState().getResumeProposal().source());
        assertEquals(day(3), store().readCursor().orElseThrow());
    }

    @Test
    public void stateThenDestinationWithACursorStreams() {
        store().writeCursor(day(1));
        DatedIn in = in(3, 2);
        DayOut out = new DayOut();
        in.written = out.written;
        MainExecutor exec = executor(in, new RecordingAnalyze(), out, ResumeMode.STATE_THEN_DESTINATION, null);

        exec.stream();

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
        assertEquals(List.of("IMG_02.JPG", "IMG_03.JPG"), in.processedWhileListing);
        assertTrue(exec.getState().getWarnings().isEmpty(), "no fallback");
        assertEquals(day(3), store().readCursor().orElseThrow());
    }

    @Test
    public void aFailureInTheMiddleStopsTheCursorBeforeIt() {
        DatedIn in = in(3, 0);
        RecordingOut out = new RecordingOut("IMG_02.JPG");
        MainExecutor exec = executor(in, new RecordingAnalyze(), out, ResumeMode.STATE, null);

        exec.stream();

        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus());
        assertEquals(day(1), store().readCursor().orElseThrow());
    }

    @Test
    public void aListingFailureNeverWritesTheCursor() {
        DatedIn in = new DatedIn(3, 0, Set.of(), 2);
        RecordingOut out = new RecordingOut(null);
        MainExecutor exec = executor(in, new RecordingAnalyze(), out, ResumeMode.STATE, null);

        exec.stream();

        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus());
        assertEquals(Set.of("IMG_01.JPG", "IMG_02.JPG"), out.written, "the files listed are processed");
        assertFalse(Files.exists(store().getPath()), "a partial listing would skip the files it did not list");
    }

    @Test
    public void aFileWithoutDateIsAnErrorWithAResumeMode() {
        DatedIn in = new DatedIn(2, 0, Set.of(2), 0);
        RecordingOut out = new RecordingOut(null);
        MainExecutor exec = executor(in, new RecordingAnalyze(), out, ResumeMode.STATE, null);

        exec.stream();

        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus());
        assertEquals(Set.of("IMG_01.JPG"), out.written, "never processed: it has no place in the resume order");
        WorkItemExecution undated = exec.getState().getWorkItems().stream()
                .filter(w -> w.getWorkItem().getNameDisplay().equals("IMG_02.JPG")).findFirst().orElseThrow();
        assertEquals(ItemStatus.ERROR, undated.getStatus());
        assertEquals(day(1), store().readCursor().orElseThrow());
    }

    @Test
    public void everyFileSkipsTheFilesAtTheDestinationAndCopiesTheOthersWhileListing() throws IOException {
        nasDay(1);
        nasDay(3); // day 2 missing in the middle: imported again
        DatedIn in = in(4, 0);
        in.waitDays = Set.of(2, 4);
        RecordingAnalyze analyze = new RecordingAnalyze();
        DayOut out = new DayOut();
        in.written = out.written;
        MainExecutor exec = executorWith(in, analyze, out,
                destination(ResumeMode.DESTINATION, DestinationCheck.EVERY_FILE, DestinationMatch.DIRECTORY), null);

        exec.stream();

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
        assertEquals(Set.of("IMG_02.JPG", "IMG_04.JPG"), out.written);
        assertEquals(List.of("IMG_02.JPG", "IMG_04.JPG"), in.processedWhileListing);
        assertEquals(4, analyze.seen.size(), "every file is analysed to know its target");
        List<WorkItemExecution> items = exec.getOrderedItems();
        assertEquals(List.of(ItemStatus.SKIPPED, ItemStatus.DONE, ItemStatus.SKIPPED, ItemStatus.DONE),
                items.stream().map(WorkItemExecution::getStatus).toList());
        assertTrue(items.get(0).isSkippedByResumePoint());
        assertEquals(ResourcesEngine.getString("resume.skip.at-destination",
                tempDir.resolve("nas").resolve("01").toAbsolutePath()), items.get(0).getSkipReason());
        assertEquals(ResumePoint.notAtDestination(), exec.getState().getResumeProposal().point());
        assertTrue(exec.getState().getWarnings().isEmpty());
        assertEquals(day(4), store().readCursor().orElseThrow());
    }

    /** Not streaming: the two phases (plan, auto) check every file too (spec execution-mode §2). */
    @Test
    public void everyFileInTwoPhasesPreparesEveryFileThenCopiesTheMissingOnes() throws IOException {
        nasDay(1);
        nasDay(3);
        DatedIn in = in(4, 0);
        RecordingAnalyze analyze = new RecordingAnalyze();
        DayOut out = new DayOut();
        MainExecutor exec = executorWith(in, analyze, out,
                destination(ResumeMode.DESTINATION, DestinationCheck.EVERY_FILE, DestinationMatch.DIRECTORY), null);

        exec.prepare();

        assertEquals(PipelineStatus.PREPARED, exec.getState().getStatus());
        assertEquals(4, analyze.seen.size());
        assertEquals(List.of(ItemStatus.SKIPPED, ItemStatus.PENDING, ItemStatus.SKIPPED, ItemStatus.PENDING),
                exec.getOrderedItems().stream().map(WorkItemExecution::getStatus).toList());
        assertTrue(out.written.isEmpty());

        exec.execute(null);

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
        assertEquals(Set.of("IMG_02.JPG", "IMG_04.JPG"), out.written);
        assertEquals(day(4), store().readCursor().orElseThrow());
    }

    @Test
    public void theDichotomyCannotStreamEveryFileIsCheckedWithAWarning() throws IOException {
        nasDay(1);
        DatedIn in = in(3, 2);
        DayOut out = new DayOut();
        in.written = out.written;
        MainExecutor exec = executor(in, new RecordingAnalyze(), out, ResumeMode.DESTINATION, null);

        exec.stream();

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
        assertEquals(List.of("IMG_02.JPG", "IMG_03.JPG"), in.processedWhileListing, "never prepared first");
        assertEquals(Set.of("IMG_02.JPG", "IMG_03.JPG"), out.written);
        assertEquals(List.of(ResourcesEngine.getString("execution.streaming.dichotomy")), exec.getState().getWarnings());
    }

    @Test
    public void stateThenDestinationWithoutCursorStreamsCheckingEveryFile() throws IOException {
        nasDay(2);
        DatedIn in = in(3, 0);
        in.waitDays = Set.of(1, 3);
        DayOut out = new DayOut();
        in.written = out.written;
        MainExecutor exec = executorWith(in, new RecordingAnalyze(), out,
                destination(ResumeMode.STATE_THEN_DESTINATION, DestinationCheck.EVERY_FILE, DestinationMatch.DIRECTORY), null);

        exec.stream();

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
        assertEquals(List.of("IMG_01.JPG", "IMG_03.JPG"), in.processedWhileListing);
        assertEquals(day(3), store().readCursor().orElseThrow());
    }

    @Test
    public void inFileModeAFileIsSkippedWhenItsTargetFileExists() throws IOException {
        nasDay(1); // the directory of day 1 only: its file was deleted, it is copied again
        nasDay(2);
        Files.writeString(tempDir.resolve("nas").resolve("02").resolve("IMG_02.JPG"), "there");
        DatedIn in = in(2, 0);
        in.waitDays = Set.of(1);
        DayOut out = new DayOut();
        in.written = out.written;
        MainExecutor exec = executorWith(in, new RecordingAnalyze(), out,
                destination(ResumeMode.DESTINATION, DestinationCheck.EVERY_FILE, DestinationMatch.FILE), null);

        exec.stream();

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
        assertEquals(Set.of("IMG_01.JPG"), out.written);
        assertEquals(List.of("IMG_01.JPG"), in.processedWhileListing);
    }

    @Test
    public void aDeclaredFixedDirectoryCopiesEverythingWithTheWarning() throws IOException {
        nasDay(1);
        nasDay(2);
        DatedIn in = in(2, 1);
        DayOut out = new DayOut();
        out.varies = Optional.of(false);
        in.written = out.written;
        MainExecutor exec = executorWith(in, new RecordingAnalyze(), out,
                destination(ResumeMode.DESTINATION, DestinationCheck.EVERY_FILE, DestinationMatch.DIRECTORY), null);

        exec.stream();

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
        assertEquals(Set.of("IMG_01.JPG", "IMG_02.JPG"), out.written, "nothing is skipped");
        assertEquals(List.of(ResourcesEngine.getString("resume.warn.single-directory",
                tempDir.resolve("nas").resolve("01").toAbsolutePath())), exec.getState().getWarnings());
    }

    @Test
    public void anInvalidStateFileFailsTheRunLikeAPreparation() throws IOException {
        Files.writeString(store().getPath(), "{ not json");
        RecordingOut out = new RecordingOut(null);
        MainExecutor exec = executor(in(2, 0), new RecordingAnalyze(), out, ResumeMode.STATE, null);

        exec.stream();

        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus());
        assertTrue(exec.getState().isPreparationFailed());
        assertTrue(out.written.isEmpty());
        assertTrue(exec.getState().getWarnings().isEmpty(), "not a fallback");
    }

    @Test
    public void aCancelledStreamingRunNeverWritesTheCursor() throws Exception {
        DatedIn in = in(3, 0);
        MainExecutorResumeTest.BlockingOut out = new MainExecutorResumeTest.BlockingOut();
        MainExecutor exec = executor(in, new RecordingAnalyze(), out, ResumeMode.STATE, null);
        Thread runner = Thread.ofVirtual().start(exec::stream);
        assertTrue(out.entered.await(5, TimeUnit.SECONDS));

        exec.cancel();
        runner.join(TimeUnit.SECONDS.toMillis(10));

        assertEquals(PipelineStatus.CANCELLED, exec.getState().getStatus());
        assertFalse(Files.exists(store().getPath()));
    }
}

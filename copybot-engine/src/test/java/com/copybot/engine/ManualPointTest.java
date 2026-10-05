package com.copybot.engine;

import com.copybot.config.CopybotConfig;
import com.copybot.engine.pipeline.*;
import com.copybot.engine.resources.ResourceRegistry;
import com.copybot.engine.resources.ResourceSettings;
import com.copybot.engine.resume.*;
import com.copybot.plugin.api.action.*;
import com.copybot.plugin.api.definition.IPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** A chosen resume point acts like a cursor at that point (spec manual-point). */
public class ManualPointTest {

    @TempDir
    Path tempDir;

    abstract static class FakeAction implements IAction {
        @Override
        public void setStatusWatcher(Consumer<WorkStatus> watcher) {
        }

        @Override
        public void setPlugin(IPlugin plugin) {
        }
    }

    /** One file per day of September, dated through lastModified; optionally hangs after a given day. */
    final class DatedIn extends FakeAction implements IInAction {
        final int days;
        final int hangAfter;
        final CountDownLatch hanging = new CountDownLatch(1);

        DatedIn(int days, int hangAfter) {
            this.days = days;
            this.hangAfter = hangAfter;
        }

        @Override
        public void listFiles(Consumer<WorkItem> consumer) {
            for (int day = 1; day <= days; day++) {
                try {
                    Path file = tempDir.resolve(String.format("IMG_%02d.JPG", day));
                    if (!Files.exists(file)) {
                        Files.createFile(file);
                    }
                    WorkItem wi = new WorkItem(file);
                    wi.getMetadatas().setTime(WorkItemMetadata.LAST_MODIFIED,
                            Instant.parse(String.format("2026-09-%02dT10:00:00Z", day)));
                    consumer.accept(wi);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                if (day == hangAfter) {
                    hanging.countDown();
                    try {
                        new CountDownLatch(1).await(); // a slow card: stopped while listing
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return; // like a plugin that ends its listing early on an interrupt
                    }
                }
            }
        }
    }

    /** Counts the analyses per file; the files in {@code blocking} hang (once per entry) until interrupted. */
    static final class CountingAnalyze extends FakeAction implements IAnalyzeAction {
        final Map<String, AtomicInteger> count = new ConcurrentHashMap<>();
        final Map<String, AtomicInteger> blocking = new ConcurrentHashMap<>();
        final CountDownLatch blocked = new CountDownLatch(1);
        volatile CountDownLatch blockedAgain = new CountDownLatch(1);

        CountingAnalyze block(String name, int times) {
            blocking.put(name, new AtomicInteger(times));
            return this;
        }

        @Override
        public void doAnalyze(WorkItem item) {
            String name = item.getNameDisplay();
            AtomicInteger left = blocking.get(name);
            if (left != null && left.getAndDecrement() > 0) {
                blocked.countDown();
                blockedAgain.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted");
                }
            }
            count.computeIfAbsent(name, n -> new AtomicInteger()).incrementAndGet();
        }

        Set<String> seen() {
            return count.keySet();
        }

        int times(String name) {
            AtomicInteger n = count.get(name);
            return n == null ? 0 : n.get();
        }
    }

    static final class RecordingOut extends FakeAction implements IOutAction {
        final Set<String> written = ConcurrentHashMap.newKeySet();
        final String failOn;
        final Path nas;

        RecordingOut(String failOn, Path nas) {
            this.failOn = failOn;
            this.nas = nas;
        }

        @Override
        public void writeItem(WorkItem item) {
            if (item.getNameDisplay().equals(failOn)) {
                throw new IllegalStateException("write failed");
            }
            written.add(item.getNameDisplay());
        }

        /** One directory per day, for the destination probe. */
        @Override
        public Optional<Path> resolveTarget(WorkItem item) {
            String name = item.getNameDisplay();
            return Optional.of(nas.resolve(name.substring(4, 6)).resolve(name));
        }
    }

    static PipelineStepConfig emptyConfig() {
        return new PipelineStepConfig(null, null, null, null, null, null, null, null);
    }

    static ResourceRegistry registry() {
        return new ResourceRegistry(ResourceSettings.from(new CopybotConfig(null, null, Map.of("disk:*", 1000), null)));
    }

    private ResumeStateStore store() {
        return new ResumeStateStore(tempDir.resolve("p.state.json"));
    }

    private Path nas() {
        return tempDir.resolve("nas");
    }

    private MainExecutor executor(IInAction in, IAnalyzeAction analyze, RecordingOut out, ResumeMode mode) {
        return new MainExecutor(
                List.of(new PipelineStep<>(null, in, emptyConfig())),
                List.of(new PipelineStep<>(null, analyze, emptyConfig()), new PipelineStep<>(null, out, emptyConfig())),
                1, false, null, registry(), new ResumeContext(mode, store()));
    }

    private MainExecutor executor(int days, IAnalyzeAction analyze, RecordingOut out, ResumeMode mode) {
        return executor(new DatedIn(days, 0), analyze, out, mode);
    }

    static ItemKey day(int d) {
        return new ItemKey(Instant.parse(String.format("2026-09-%02dT10:00:00Z", d)), String.format("IMG_%02d.JPG", d));
    }

    static Set<String> days(int from, int to) {
        Set<String> names = new HashSet<>();
        for (int d = from; d <= to; d++) {
            names.add(String.format("IMG_%02d.JPG", d));
        }
        return names;
    }

    static WorkItemExecution named(MainExecutor exec, String name) {
        return exec.getState().getWorkItems().stream()
                .filter(i -> i.getWorkItem().getNameDisplay().equals(name)).findFirst().orElseThrow();
    }

    // ---- preparing with a chosen point (spec §1) ----

    @Test
    public void aChosenPointSkipsTheFilesBeforeItAtTheListingWithoutAnalysingThem() {
        CountingAnalyze analyze = new CountingAnalyze();
        MainExecutor exec = executor(8, analyze, new RecordingOut(null, nas()), ResumeMode.STATE);
        ResumePoint point = ResumePoint.from(day(5));
        exec.chooseResumePoint(point);

        exec.prepare();

        assertEquals(PipelineStatus.PREPARED, exec.getState().getStatus());
        assertEquals(days(5, 8), analyze.seen(), "the files before the point are not analysed");
        assertEquals(new ResumeProposal(point, ResumeSource.MANUAL, List.of()), exec.getState().getResumeProposal());
        for (String name : days(1, 4)) {
            WorkItemExecution skipped = named(exec, name);
            assertEquals(ItemStatus.SKIPPED, skipped.getStatus());
            assertTrue(skipped.isSkippedByResumePoint());
            assertEquals(ResumeResolver.skipReason(point, ResumeSource.MANUAL), skipped.getSkipReason());
        }
        days(5, 8).forEach(name -> assertEquals(ItemStatus.PENDING, named(exec, name).getStatus()));
    }

    @Test
    public void withAChosenPointTheDestinationIsNotProbed() throws IOException {
        for (int d = 1; d <= 6; d++) {
            Files.createDirectories(nas().resolve(String.format("%02d", d)));
        }
        CountingAnalyze analyze = new CountingAnalyze();
        MainExecutor exec = executor(8, analyze, new RecordingOut(null, nas()), ResumeMode.DESTINATION);
        exec.chooseResumePoint(ResumePoint.from(day(3)));

        exec.prepare();

        assertEquals(PipelineStatus.PREPARED, exec.getState().getStatus());
        assertEquals(days(3, 8), analyze.seen(), "no probe: nothing before the point is analysed");
        assertEquals(ResumeSource.MANUAL, exec.getState().getResumeProposal().source());
        assertEquals(ItemStatus.PENDING, named(exec, "IMG_04.JPG").getStatus(), "the chosen point wins over the destination");
    }

    @Test
    public void aChosenPointAllAnalysesEverythingDespiteTheCursor() {
        store().writeCursor(day(5));
        CountingAnalyze analyze = new CountingAnalyze();
        MainExecutor exec = executor(8, analyze, new RecordingOut(null, nas()), ResumeMode.STATE);
        exec.chooseResumePoint(ResumePoint.all());

        exec.prepare();

        assertEquals(days(1, 8), analyze.seen());
        assertTrue(exec.getState().getWorkItems().stream().allMatch(i -> i.getStatus() == ItemStatus.PENDING));
        assertEquals(new ResumeProposal(ResumePoint.all(), ResumeSource.MANUAL, List.of()), exec.getState().getResumeProposal());
    }

    @Test
    public void theCursorNeverMovesBackAfterAChosenPointEarlierThanIt() {
        store().writeCursor(day(6));
        MainExecutor exec = executor(8, new CountingAnalyze(), new RecordingOut("IMG_03.JPG", nas()), ResumeMode.STATE);
        exec.chooseResumePoint(ResumePoint.from(day(2)));
        exec.prepare();

        exec.execute(null);

        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus(), "IMG_03 failed");
        assertEquals(Optional.of(day(6)), store().readCursor(), "the run of successes stops at IMG_02: the cursor stays");
    }

    @Test
    public void streamingWithAChosenPointSkipsTheFilesBeforeIt() {
        CountingAnalyze analyze = new CountingAnalyze();
        RecordingOut out = new RecordingOut(null, nas());
        MainExecutor exec = executor(8, analyze, out, ResumeMode.STATE);
        exec.chooseResumePoint(ResumePoint.from(day(4)));

        exec.stream();

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
        assertEquals(days(4, 8), analyze.seen());
        assertEquals(days(4, 8), out.written);
        assertEquals(Optional.of(day(8)), store().readCursor());
    }

    // ---- the listing complete flag (spec §2) ----

    @Test
    public void aPreparationThatListedEverythingSaysSo() {
        MainExecutor exec = executor(3, new CountingAnalyze(), new RecordingOut(null, nas()), ResumeMode.STATE);

        exec.prepare();

        assertTrue(exec.getState().isListingComplete());
    }

    @Test
    public void aPreparationStoppedWhileListingIsNotComplete() throws Exception {
        DatedIn in = new DatedIn(8, 3);
        MainExecutor exec = executor(in, new CountingAnalyze(), new RecordingOut(null, nas()), ResumeMode.STATE);
        Thread caller = Thread.ofVirtual().start(exec::prepare);
        assertTrue(in.hanging.await(5, TimeUnit.SECONDS));

        exec.cancelPreparation();
        caller.join(TimeUnit.SECONDS.toMillis(20));

        assertEquals(PipelineStatus.CANCELLED, exec.getState().getStatus());
        assertFalse(exec.getState().isListingComplete(), "the files after IMG_03 were never listed");
        assertFalse(exec.canContinue());
        assertThrows(IllegalStateException.class, () -> exec.continuePreparation(ResumePoint.from(day(2))));
        assertEquals(PipelineStatus.CANCELLED, exec.getState().getStatus());
    }

    @Test
    public void aFailedListingIsNotComplete() {
        MainExecutor exec = executor(new FailingIn(), new CountingAnalyze(), new RecordingOut(null, nas()), ResumeMode.STATE);

        exec.prepare();

        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus());
        assertFalse(exec.getState().isListingComplete());
        assertFalse(exec.canContinue());
    }

    static final class FailingIn extends FakeAction implements IInAction {
        @Override
        public void listFiles(Consumer<WorkItem> consumer) {
            throw new IllegalStateException("card removed");
        }
    }

    // ---- continuing a stopped preparation (spec §2) ----

    /** Prepares 8 files, the analysis of IMG_05 hanging, then stops: listed everything, analysed all but IMG_05. */
    private MainExecutor stoppedAfterTheListing(CountingAnalyze analyze, RecordingOut out) throws Exception {
        MainExecutor exec = executor(8, analyze, out, ResumeMode.STATE);
        Thread caller = Thread.ofVirtual().start(exec::prepare);
        assertTrue(analyze.blocked.await(5, TimeUnit.SECONDS));
        awaitAnalysed(analyze, 7);
        exec.cancelPreparation();
        caller.join(TimeUnit.SECONDS.toMillis(20));
        assertFalse(caller.isAlive());
        assertEquals(PipelineStatus.CANCELLED, exec.getState().getStatus());
        return exec;
    }

    private static void awaitAnalysed(CountingAnalyze analyze, int count) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (analyze.seen().size() < count) {
            assertTrue(System.currentTimeMillis() < deadline, "analysed " + analyze.seen());
            Thread.sleep(5);
        }
    }

    @Test
    public void aStoppedPreparationContinuesFromAChosenPointWithoutListingAgain() throws Exception {
        CountingAnalyze analyze = new CountingAnalyze().block("IMG_05.JPG", 1);
        RecordingOut out = new RecordingOut(null, nas());
        MainExecutor exec = stoppedAfterTheListing(analyze, out);
        assertTrue(exec.getState().isListingComplete());
        assertTrue(exec.canContinue());
        assertEquals(ItemStatus.PENDING, named(exec, "IMG_05.JPG").getStatus());
        assertFalse(named(exec, "IMG_05.JPG").isPrepared(), "interrupted: not analysed");

        ResumePoint point = ResumePoint.from(day(3));
        exec.continuePreparation(point);

        assertEquals(PipelineStatus.PREPARED, exec.getState().getStatus());
        assertEquals(new ResumeProposal(point, ResumeSource.MANUAL, List.of()), exec.getState().getResumeProposal());
        assertEquals(8, exec.getState().getWorkItems().size(), "not listed again");
        assertEquals(1, analyze.times("IMG_05.JPG"), "the interrupted file is analysed now");
        assertEquals(1, analyze.times("IMG_04.JPG"), "an analysed file keeps its analysis");
        assertNotNull(named(exec, "IMG_04.JPG").getProjection());
        assertNotNull(named(exec, "IMG_05.JPG").getProjection());
        assertEquals(ItemStatus.SKIPPED, named(exec, "IMG_02.JPG").getStatus());
        assertEquals(ResumeResolver.skipReason(point, ResumeSource.MANUAL), named(exec, "IMG_02.JPG").getSkipReason());
        days(3, 8).forEach(name -> assertEquals(ItemStatus.PENDING, named(exec, name).getStatus(), name));
        assertFalse(exec.canContinue(), "prepared: nothing to continue");

        exec.execute(null);

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
        assertEquals(days(3, 8), out.written);
    }

    @Test
    public void aContinuationStoppedAgainCanBeContinuedAgain() throws Exception {
        CountingAnalyze analyze = new CountingAnalyze().block("IMG_05.JPG", 2);
        MainExecutor exec = stoppedAfterTheListing(analyze, new RecordingOut(null, nas()));
        analyze.blockedAgain = new CountDownLatch(1);

        Thread continuing = Thread.ofVirtual().start(() -> exec.continuePreparation(ResumePoint.from(day(4))));
        assertTrue(analyze.blockedAgain.await(5, TimeUnit.SECONDS));
        exec.cancelPreparation();
        continuing.join(TimeUnit.SECONDS.toMillis(20));

        assertFalse(continuing.isAlive());
        assertEquals(PipelineStatus.CANCELLED, exec.getState().getStatus());
        assertTrue(exec.canContinue(), "the listing is still complete");

        exec.continuePreparation(ResumePoint.from(day(4)));

        assertEquals(PipelineStatus.PREPARED, exec.getState().getStatus());
        assertEquals(1, analyze.times("IMG_05.JPG"));
    }

    @Test
    public void aCancelBeforeTheContinuationHasNoEffectAndALateStopDoesNotCancelThePreparedPlan() throws Exception {
        CountingAnalyze analyze = new CountingAnalyze().block("IMG_05.JPG", 1);
        MainExecutor exec = stoppedAfterTheListing(analyze, new RecordingOut(null, nas()));

        exec.cancel(); // the plan is already stopped: nothing more
        exec.cancelPreparation();
        exec.continuePreparation(ResumePoint.all());
        exec.cancelPreparation(); // too late: the plan is prepared

        assertEquals(PipelineStatus.PREPARED, exec.getState().getStatus());
        exec.execute(null);
        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
    }

    @Test
    public void onlyAStoppedPreparationCanBeContinued() {
        MainExecutor exec = executor(3, new CountingAnalyze(), new RecordingOut(null, nas()), ResumeMode.STATE);
        assertThrows(IllegalStateException.class, () -> exec.continuePreparation(ResumePoint.all()), "never prepared");
        exec.prepare();

        assertFalse(exec.canContinue());
        assertThrows(IllegalStateException.class, () -> exec.continuePreparation(ResumePoint.all()), "prepared");
        assertEquals(PipelineStatus.PREPARED, exec.getState().getStatus());
    }

    @Test
    public void anExecutionStoppedIsNotAPreparationToContinue() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        IOutAction hanging = new BlockingOut(gate);
        MainExecutor exec = new MainExecutor(
                List.of(new PipelineStep<>(null, new DatedIn(3, 0), emptyConfig())),
                List.of(new PipelineStep<>(null, new CountingAnalyze(), emptyConfig()), new PipelineStep<>(null, hanging, emptyConfig())),
                1, false, null, registry(), new ResumeContext(ResumeMode.STATE, store()));
        exec.prepare();
        Thread running = Thread.ofVirtual().start(() -> exec.execute(null));
        assertTrue(((BlockingOut) hanging).entered.await(5, TimeUnit.SECONDS));
        exec.cancel();
        running.join(TimeUnit.SECONDS.toMillis(20));

        assertEquals(PipelineStatus.CANCELLED, exec.getState().getStatus());
        assertFalse(exec.canContinue());
        assertThrows(IllegalStateException.class, () -> exec.continuePreparation(ResumePoint.all()));
    }

    static final class BlockingOut extends FakeAction implements IOutAction {
        final CountDownLatch gate;
        final CountDownLatch entered = new CountDownLatch(1);

        BlockingOut(CountDownLatch gate) {
            this.gate = gate;
        }

        @Override
        public void writeItem(WorkItem item) {
            entered.countDown();
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted");
            }
        }
    }

    // ---- "Analyse" on a stopped preparation (spec §2) ----

    @Test
    public void analysingFilesOfAStoppedPreparationLeavesItStopped() throws Exception {
        CountingAnalyze analyze = new CountingAnalyze().block("IMG_05.JPG", 1);
        MainExecutor exec = stoppedAfterTheListing(analyze, new RecordingOut(null, nas()));
        WorkItemExecution file = named(exec, "IMG_05.JPG");

        exec.requestAnalysis(List.of(file));
        assertEquals(List.of(file), exec.toAnalyse());
        exec.analyseDeferred();

        assertEquals(PipelineStatus.CANCELLED, exec.getState().getStatus(), "still a stopped preparation");
        assertTrue(exec.canContinue());
        assertTrue(file.isPrepared());
        assertNotNull(file.getProjection());
        assertEquals(1, analyze.times("IMG_05.JPG"));

        exec.continuePreparation(ResumePoint.all());
        assertEquals(PipelineStatus.PREPARED, exec.getState().getStatus());
        assertEquals(1, analyze.times("IMG_05.JPG"), "not analysed twice");
    }

    // ---- the engine (spec §2) ----

    @Test
    public void theEngineContinuesAStoppedPlanAsItsActiveOperationAndKeepsItPrepared() throws Exception {
        CountingAnalyze analyze = new CountingAnalyze().block("IMG_05.JPG", 1);
        RecordingOut out = new RecordingOut(null, nas());
        try (CopybotEngine engine = new CopybotEngine(ControlFakes.config())) {
            MainExecutor exec = executor(8, analyze, out, ResumeMode.STATE);
            AtomicReference<Plan> plan = new AtomicReference<>();
            Thread caller = Thread.ofVirtual().start(() -> plan.set(engine.prepare(exec, null)));
            assertTrue(analyze.blocked.await(5, TimeUnit.SECONDS));
            awaitAnalysed(analyze, 7);
            exec.cancelPreparation();
            caller.join(TimeUnit.SECONDS.toMillis(20));
            assertTrue(plan.get().canContinue());

            engine.continuePreparation(plan.get(), ResumePoint.from(day(6)));

            assertEquals(PipelineStatus.PREPARED, plan.get().getState().getStatus());
            assertEquals(PipelineStatus.SUCCESS, ControlFakes.awaitStatus(engine.execute(plan.get(), null)));
            assertEquals(days(6, 8), out.written);
        }
    }

    @Test
    public void theEngineRefusesToContinueAPlanThatCannotBe() {
        try (CopybotEngine engine = new CopybotEngine(ControlFakes.config())) {
            Plan plan = engine.prepare(executor(3, new CountingAnalyze(), new RecordingOut(null, nas()), ResumeMode.STATE));

            assertThrows(IllegalStateException.class, () -> engine.continuePreparation(plan, ResumePoint.all()));
            assertEquals(PipelineStatus.PREPARED, plan.getState().getStatus());
            assertDoesNotThrow(() -> engine.execute(plan, null).await(), "the engine is free again");
        }
    }

    @Test
    public void closingTheEngineStopsAContinuation() throws Exception {
        CountingAnalyze analyze = new CountingAnalyze().block("IMG_05.JPG", 2);
        CopybotEngine engine = new CopybotEngine(ControlFakes.config());
        try {
            MainExecutor exec = executor(8, analyze, new RecordingOut(null, nas()), ResumeMode.STATE);
            AtomicReference<Plan> plan = new AtomicReference<>();
            Thread caller = Thread.ofVirtual().start(() -> plan.set(engine.prepare(exec, null)));
            assertTrue(analyze.blocked.await(5, TimeUnit.SECONDS));
            awaitAnalysed(analyze, 7);
            exec.cancelPreparation();
            caller.join(TimeUnit.SECONDS.toMillis(20));
            analyze.blockedAgain = new CountDownLatch(1);
            Thread continuing = Thread.ofVirtual().start(() -> engine.continuePreparation(plan.get(), ResumePoint.all()));
            assertTrue(analyze.blockedAgain.await(5, TimeUnit.SECONDS));

            assertThrows(IllegalStateException.class, () -> engine.run(tempDir.resolve("any.json"), null),
                    "the continuation is the active operation");
            engine.close();
            continuing.join(TimeUnit.SECONDS.toMillis(20));

            assertFalse(continuing.isAlive());
            assertEquals(PipelineStatus.CANCELLED, plan.get().getState().getStatus());
        } finally {
            engine.close();
        }
    }

    // ---- a file chosen on disk (spec §2) ----

    @Test
    public void aFileChosenOnDiskGivesTheKeyTheListingWillGiveIt() throws IOException {
        Path file = Files.createFile(tempDir.resolve("DSC01694.JPG"));
        Instant modified = Instant.parse("2024-06-16T13:36:42.517Z");
        Files.setLastModifiedTime(file, FileTime.from(modified));

        assertEquals(ResumePoint.from(new ItemKey(modified, "DSC01694.JPG")), Plan.fromFile(file));
    }
}

package com.copybot.engine;

import com.copybot.engine.ControlFakes.FakeAction;
import com.copybot.engine.pipeline.ItemStatus;
import com.copybot.engine.pipeline.PipelineStatus;
import com.copybot.engine.pipeline.PipelineStep;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.engine.resume.DestinationCheck;
import com.copybot.engine.resume.DestinationMatch;
import com.copybot.engine.resume.ItemKey;
import com.copybot.engine.resume.ResumeContext;
import com.copybot.engine.resume.ResumeMode;
import com.copybot.engine.resume.ResumePoint;
import com.copybot.engine.resume.ResumeStateStore;
import com.copybot.engine.resume.ZoneCorrection;
import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.IAnalyzeAction;
import com.copybot.plugin.api.action.IInAction;
import com.copybot.plugin.api.action.IOutAction;
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
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static com.copybot.engine.ControlFakes.emptyConfig;
import static com.copybot.engine.ControlFakes.registry;
import static org.junit.jupiter.api.Assertions.*;

/** Correcting the time zone of files of a prepared plan: analysed again, their destination checked again (spec timezone-correction §3). */
public class ZoneCorrectionTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final ZoneId MEXICO = ZoneId.of("America/Mexico_City");

    @TempDir
    Path tempDir;

    /**
     * IMG_01.JPG... dated 2026-09-&lt;day&gt; 23:30 UTC (also their resume key date): the next day in Paris, the same
     * day in Mexico.
     */
    final class LateIn extends FakeAction implements IInAction {
        final int days;

        LateIn(int days) {
            this.days = days;
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
                            Instant.parse(String.format("2026-09-%02dT23:30:00Z", day)));
                    consumer.accept(wi);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
    }

    /**
     * Like the metadata plugin: the capture date is the file date shown in the capture zone (UTC without one).
     * Counts the analyses per file; a file in {@code blocking} hangs (once per entry) until interrupted.
     */
    static final class ZoneAnalyze extends FakeAction implements IAnalyzeAction {
        final Map<String, AtomicInteger> count = new ConcurrentHashMap<>();
        final Map<String, AtomicInteger> blocking = new ConcurrentHashMap<>();
        final CountDownLatch blocked = new CountDownLatch(1);

        ZoneAnalyze block(String name, int times) {
            blocking.put(name, new AtomicInteger(times));
            return this;
        }

        @Override
        public void doAnalyze(WorkItem item) {
            String name = item.getNameDisplay();
            AtomicInteger left = blocking.get(name);
            if (left != null && left.getAndDecrement() > 0) {
                blocked.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted");
                }
            }
            WorkItemMetadata metadata = item.getMetadatas();
            ZoneId zone = metadata.getZone(WorkItemMetadata.CAPTURE_ZONE).orElse(ZoneOffset.UTC);
            metadata.setTime(WorkItemMetadata.CAPTURE_DATE,
                    metadata.getTime(WorkItemMetadata.LAST_MODIFIED).orElseThrow().atZone(zone));
            count.computeIfAbsent(name, n -> new AtomicInteger()).incrementAndGet();
        }

        int times(String name) {
            AtomicInteger n = count.get(name);
            return n == null ? 0 : n.get();
        }
    }

    /** Its target is nas/&lt;day of the capture date&gt;/&lt;name&gt;; records the day each file is written to. */
    final class CaptureDayOut extends FakeAction implements IOutAction {
        final Map<String, String> written = new ConcurrentHashMap<>();
        /** Fails to resolve the target of a corrected file (a plugin bug: not a CopybotException). */
        volatile boolean failWhenCorrected;

        @Override
        public WriteResult write(WorkItem item, WriteContext context) {
            written.put(item.getNameDisplay(), captureDay(item));
            return WriteResult.written(null);
        }

        @Override
        public Optional<Path> resolveTarget(WorkItem item) {
            if (failWhenCorrected && item.getMetadatas().raw().containsKey(WorkItemMetadata.CAPTURE_ZONE)) {
                throw new IllegalStateException("plugin bug");
            }
            return Optional.of(nas().resolve(captureDay(item)).resolve(item.getNameDisplay()));
        }

        private static String captureDay(WorkItem item) {
            return item.getMetadatas().display().get(WorkItemMetadata.CAPTURE_DATE + ".D");
        }
    }

    private Path nas() {
        return tempDir.resolve("nas");
    }

    private ResumeStateStore store() {
        return new ResumeStateStore(tempDir.resolve("p.state.json"));
    }

    private MainExecutor executor(int days, ZoneAnalyze analyze, CaptureDayOut out, ResumeContext resume) {
        return new MainExecutor(
                List.of(new PipelineStep<>(null, new LateIn(days), emptyConfig())),
                List.of(new PipelineStep<>(null, analyze, emptyConfig()), new PipelineStep<>(null, out, emptyConfig())),
                1, false, null, registry(Map.of("disk:*", 1000)), resume);
    }

    /** Prepared in mode none: every file analysed and to copy. */
    private Plan prepared(int days, ZoneAnalyze analyze, CaptureDayOut out) {
        return prepared(executor(days, analyze, out, new ResumeContext(ResumeMode.NONE, store())));
    }

    private static Plan prepared(MainExecutor exec) {
        exec.prepare();
        assertEquals(PipelineStatus.PREPARED, exec.getState().getStatus());
        return new Plan(exec);
    }

    /** Every file checked at the destination, by its target file. */
    private Plan preparedEveryFile(int days, ZoneAnalyze analyze, CaptureDayOut out) {
        return prepared(executor(days, analyze, out,
                new ResumeContext(ResumeMode.DESTINATION, store(), DestinationCheck.EVERY_FILE, DestinationMatch.FILE)));
    }

    private void atDestination(String day, String name) throws IOException {
        Path dir = Files.createDirectories(nas().resolve(day));
        Files.createFile(dir.resolve(name));
    }

    /** The resume key of the file LateIn emits for this day. */
    private static ItemKey day(int d) {
        return new ItemKey(Instant.parse(String.format("2026-09-%02dT23:30:00Z", d)), String.format("IMG_%02d.JPG", d));
    }

    private static WorkItemExecution named(Plan plan, String name) {
        return plan.getOrderedItems().stream()
                .filter(i -> i.getWorkItem().getNameDisplay().equals(name)).findFirst().orElseThrow();
    }

    /** The day directory of the planned target of this item. */
    private static String targetDay(Plan plan, WorkItemExecution item) {
        TargetProjection projection = plan.projectionOf(item);
        assertInstanceOf(TargetProjection.Targets.class, projection);
        return ((TargetProjection.Targets) projection).directories().getFirst().getFileName().toString();
    }

    private String atDestinationReason(String day, String name) {
        return ResourcesEngine.getString("resume.skip.at-destination", nas().resolve(day).resolve(name).toAbsolutePath().normalize());
    }

    // ---- correcting, analysing again ----

    @Test
    public void correctZoneAsksForTheAnalysisOfTheseFilesAgain() {
        ZoneAnalyze analyze = new ZoneAnalyze();
        Plan plan = prepared(3, analyze, new CaptureDayOut());
        WorkItemExecution first = named(plan, "IMG_01.JPG");
        assertEquals("01", targetDay(plan, first));
        assertTrue(plan.toAnalyse().isEmpty());

        plan.correctZone(List.of(first), PARIS, null, null);

        assertEquals(List.of(first), plan.toAnalyse());
        assertTrue(first.isAnalysisDeferred());
        assertEquals(TargetProjection.NONE, plan.projectionOf(first), "its target is no longer known");

        plan.getExecutor().analyseDeferred();

        assertEquals(PipelineStatus.PREPARED, plan.getState().getStatus());
        assertEquals(2, analyze.times("IMG_01.JPG"), "analysed again");
        assertEquals(1, analyze.times("IMG_02.JPG"), "the others are not");
        assertFalse(first.isAnalysisDeferred());
        assertEquals(ItemStatus.PENDING, first.getStatus());
        assertEquals("02", targetDay(plan, first), "its target follows the corrected zone");
        assertTrue(plan.toAnalyse().isEmpty());
    }

    @Test
    public void correctZoneSetsTheZoneKeysAndClearZoneRemovesThem() {
        Plan plan = prepared(2, new ZoneAnalyze(), new CaptureDayOut());
        WorkItemExecution first = named(plan, "IMG_01.JPG");
        WorkItemMetadata metadata = first.getWorkItem().getMetadatas();

        plan.correctZone(List.of(first), PARIS, ZoneOffset.ofHours(9), null);

        assertEquals(Optional.of(PARIS), metadata.getZone(WorkItemMetadata.CAPTURE_ZONE));
        assertEquals(Optional.of(ZoneOffset.ofHours(9)), metadata.getZone(WorkItemMetadata.CAMERA_CLOCK));

        plan.getExecutor().analyseDeferred();
        plan.correctZone(List.of(first), MEXICO, null, null);

        assertEquals(Optional.of(MEXICO), metadata.getZone(WorkItemMetadata.CAPTURE_ZONE));
        assertFalse(metadata.raw().containsKey(WorkItemMetadata.CAMERA_CLOCK), "no clock: the one set before is gone");

        plan.getExecutor().analyseDeferred();
        plan.clearZone(List.of(first), null);

        assertFalse(metadata.raw().containsKey(WorkItemMetadata.CAPTURE_ZONE));
        assertFalse(metadata.raw().containsKey(WorkItemMetadata.CAMERA_CLOCK));
        assertEquals(List.of(first), plan.toAnalyse(), "cancelling a correction analyses the file again too");
    }

    @Test
    public void aSkippedFileCorrectedStaysSkippedForItsReason() {
        store().writeCursor(day(2));
        ZoneAnalyze analyze = new ZoneAnalyze();
        Plan plan = prepared(executor(4, analyze, new CaptureDayOut(), new ResumeContext(ResumeMode.STATE, store())));
        WorkItemExecution first = named(plan, "IMG_01.JPG");
        assertEquals(ItemStatus.SKIPPED, first.getStatus());
        String reason = first.getSkipReason();
        plan.requestAnalysis(List.of(first));
        plan.getExecutor().analyseDeferred();
        assertEquals(1, analyze.times("IMG_01.JPG"));

        plan.correctZone(List.of(first), PARIS, null, null);

        assertEquals(List.of(first), plan.toAnalyse());
        plan.getExecutor().analyseDeferred();

        assertEquals(2, analyze.times("IMG_01.JPG"));
        assertEquals(ItemStatus.SKIPPED, first.getStatus());
        assertTrue(first.isSkippedByResumePoint());
        assertEquals(reason, first.getSkipReason());
        assertEquals("02", targetDay(plan, first));
    }

    @Test
    public void anIgnoredFileCorrectedStaysIgnored() {
        ZoneAnalyze analyze = new ZoneAnalyze();
        Plan plan = prepared(3, analyze, new CaptureDayOut());
        WorkItemExecution second = named(plan, "IMG_02.JPG");
        plan.ignore(List.of(second), null);

        plan.correctZone(List.of(second), PARIS, null, null);
        plan.getExecutor().analyseDeferred();

        assertEquals(2, analyze.times("IMG_02.JPG"));
        assertEquals(ItemStatus.SKIPPED, second.getStatus());
        assertTrue(second.isIgnored());
        assertEquals(ResourcesEngine.getString("plan.skip.ignored"), second.getSkipReason());
        assertEquals("03", targetDay(plan, second));
    }

    @Test
    public void theLastCorrectionWins() {
        ZoneAnalyze analyze = new ZoneAnalyze();
        Plan plan = prepared(2, analyze, new CaptureDayOut());
        WorkItemExecution first = named(plan, "IMG_01.JPG");

        plan.correctZone(List.of(first), PARIS, null, null);
        plan.correctZone(List.of(first), MEXICO, null, null);
        plan.getExecutor().analyseDeferred();

        assertEquals(Optional.of(MEXICO), first.getWorkItem().getMetadatas().getZone(WorkItemMetadata.CAPTURE_ZONE));
        assertEquals(2, analyze.times("IMG_01.JPG"), "analysed once more, in the last zone");
        assertEquals("01", targetDay(plan, first));
    }

    @Test
    public void aStoppedReanalysisLeavesTheFilesToTheCopy() throws Exception {
        ZoneAnalyze analyze = new ZoneAnalyze();
        CaptureDayOut out = new CaptureDayOut();
        Plan plan = prepared(3, analyze, out);
        WorkItemExecution second = named(plan, "IMG_02.JPG");
        plan.correctZone(List.of(second), PARIS, null, null);
        analyze.block("IMG_02.JPG", 1);

        Thread runner = Thread.ofVirtual().start(plan.getExecutor()::analyseDeferred);
        assertTrue(analyze.blocked.await(10, TimeUnit.SECONDS), "the analysis started");
        plan.cancelAnalysis();
        runner.join(TimeUnit.SECONDS.toMillis(20));

        assertFalse(runner.isAlive());
        assertEquals(PipelineStatus.PREPARED, plan.getState().getStatus());
        assertEquals(ItemStatus.PENDING, second.getStatus());
        assertTrue(second.isAnalysisDeferred(), "still to analyse");
        assertEquals(Optional.of(PARIS), second.getWorkItem().getMetadatas().getZone(WorkItemMetadata.CAPTURE_ZONE));

        plan.getExecutor().execute(null);

        assertEquals(PipelineStatus.SUCCESS, plan.getState().getStatus());
        assertEquals(2, analyze.times("IMG_02.JPG"), "analysed by the execution");
        assertEquals(Map.of("IMG_01.JPG", "01", "IMG_02.JPG", "03", "IMG_03.JPG", "03"), out.written);
    }

    @Test
    public void correctZoneRequiresAPreparedPlan() {
        Plan plan = prepared(2, new ZoneAnalyze(), new CaptureDayOut());
        WorkItemExecution first = named(plan, "IMG_01.JPG");
        plan.getExecutor().execute(null);
        assertEquals(PipelineStatus.SUCCESS, plan.getState().getStatus());

        assertThrows(IllegalStateException.class, () -> plan.correctZone(List.of(first), PARIS, null, null));
        assertThrows(IllegalStateException.class, () -> plan.clearZone(List.of(first), null));
        assertFalse(first.getWorkItem().getMetadatas().raw().containsKey(WorkItemMetadata.CAPTURE_ZONE));
    }

    // ---- the destination checked again ----

    @Test
    public void everyFile_aCorrectedFileWhoseNewTargetIsFreeIsSelected() throws IOException {
        atDestination("01", "IMG_01.JPG");
        Plan plan = preparedEveryFile(3, new ZoneAnalyze(), new CaptureDayOut());
        WorkItemExecution first = named(plan, "IMG_01.JPG");
        assertEquals(ResumePoint.Kind.NOT_AT_DESTINATION, plan.getProposal().point().kind());
        assertEquals(ItemStatus.SKIPPED, first.getStatus());

        plan.correctZone(List.of(first), PARIS, null, null);
        plan.getExecutor().analyseDeferred();

        assertEquals(ItemStatus.PENDING, first.getStatus(), "its new target nas/02 is free");
        assertEquals("02", targetDay(plan, first));
        assertEquals(ItemStatus.PENDING, named(plan, "IMG_02.JPG").getStatus());
    }

    @Test
    public void everyFile_aCorrectedFileWhoseNewTargetExistsIsSkipped() throws IOException {
        atDestination("01", "IMG_01.JPG");
        atDestination("03", "IMG_02.JPG");
        Plan plan = preparedEveryFile(3, new ZoneAnalyze(), new CaptureDayOut());
        WorkItemExecution second = named(plan, "IMG_02.JPG");
        assertEquals(ItemStatus.PENDING, second.getStatus());

        plan.correctZone(List.of(second), PARIS, null, null);
        plan.getExecutor().analyseDeferred();

        assertEquals(ItemStatus.SKIPPED, second.getStatus(), "its new target nas/03 is there");
        assertTrue(second.isSkippedByResumePoint());
        assertEquals(atDestinationReason("03", "IMG_02.JPG"), second.getSkipReason());
        assertEquals(ItemStatus.SKIPPED, named(plan, "IMG_01.JPG").getStatus());
        assertEquals(ItemStatus.PENDING, named(plan, "IMG_03.JPG").getStatus());
    }

    @Test
    public void everyFile_clearZoneBringsBackTheFirstVerdict() throws IOException {
        atDestination("01", "IMG_01.JPG");
        Plan plan = preparedEveryFile(3, new ZoneAnalyze(), new CaptureDayOut());
        WorkItemExecution first = named(plan, "IMG_01.JPG");
        String reason = first.getSkipReason();
        assertEquals(atDestinationReason("01", "IMG_01.JPG"), reason);
        plan.correctZone(List.of(first), PARIS, null, null);
        plan.getExecutor().analyseDeferred();
        assertEquals(ItemStatus.PENDING, first.getStatus());

        plan.clearZone(List.of(first), null);
        plan.getExecutor().analyseDeferred();

        assertEquals(ItemStatus.SKIPPED, first.getStatus());
        assertEquals(reason, first.getSkipReason());
        assertEquals("01", targetDay(plan, first));
    }

    @Test
    public void everyFile_nothingAtTheDestination_aCorrectedFileWhoseNewTargetExistsIsSkipped() throws IOException {
        atDestination("03", "IMG_02.JPG"); // no file has its target there before the correction
        Plan plan = preparedEveryFile(3, new ZoneAnalyze(), new CaptureDayOut());
        assertEquals(ResumePoint.Kind.ALL, plan.getProposal().point().kind());
        WorkItemExecution second = named(plan, "IMG_02.JPG");

        plan.correctZone(List.of(second), PARIS, null, null);
        plan.getExecutor().analyseDeferred();

        assertEquals(ItemStatus.SKIPPED, second.getStatus(), "already at the destination");
        assertEquals(atDestinationReason("03", "IMG_02.JPG"), second.getSkipReason());
        assertEquals(ItemStatus.PENDING, named(plan, "IMG_01.JPG").getStatus());
        assertEquals(ItemStatus.PENDING, named(plan, "IMG_03.JPG").getStatus());

        WorkItemExecution first = named(plan, "IMG_01.JPG");
        plan.ignore(List.of(first), null);
        plan.unignore(List.of(first), null);

        assertEquals(ItemStatus.PENDING, first.getStatus());
        assertEquals(ItemStatus.SKIPPED, second.getStatus(), "the proposed point applied again keeps the verdict");
    }

    @Test
    public void everyFile_aManualPointSelectingEverythingKeepsACorrectedFile() throws IOException {
        atDestination("03", "IMG_02.JPG");
        Plan plan = preparedEveryFile(3, new ZoneAnalyze(), new CaptureDayOut());
        WorkItemExecution second = named(plan, "IMG_02.JPG");

        plan.correctZone(List.of(second), PARIS, null, ResumePoint.all());
        plan.getExecutor().analyseDeferred();

        assertEquals(ItemStatus.PENDING, second.getStatus(), "the point chosen by the user selects it");
    }

    @Test
    public void aFailingTargetAtTheRecheckLeavesThePlanPrepared() throws IOException {
        atDestination("01", "IMG_01.JPG");
        CaptureDayOut out = new CaptureDayOut();
        Plan plan = preparedEveryFile(3, new ZoneAnalyze(), out);
        WorkItemExecution second = named(plan, "IMG_02.JPG");
        out.failWhenCorrected = true;

        plan.correctZone(List.of(second), PARIS, null, null);
        plan.getExecutor().analyseDeferred();

        assertEquals(PipelineStatus.PREPARED, plan.getState().getStatus());
        assertFalse(plan.getExecutor().isPreparing());
        assertEquals(ItemStatus.PENDING, second.getStatus(), "its target unknown: still selected");
        assertEquals(ItemStatus.SKIPPED, named(plan, "IMG_01.JPG").getStatus());

        plan.getExecutor().execute(null);

        assertEquals(PipelineStatus.SUCCESS, plan.getState().getStatus());
        assertEquals("03", out.written.get("IMG_02.JPG"));
    }

    @Test
    public void lastFound_aCorrectionChangesNoStatus() throws IOException {
        Files.createDirectories(nas().resolve("01"));
        Files.createDirectories(nas().resolve("02"));
        Files.createDirectories(nas().resolve("05")); // where IMG_04 goes in Paris
        Plan plan = prepared(executor(4, new ZoneAnalyze(), new CaptureDayOut(),
                new ResumeContext(ResumeMode.DESTINATION, store())));
        assertEquals(ResumePoint.after(day(2)), plan.getProposal().point());
        List<ItemStatus> statuses = plan.getOrderedItems().stream().map(WorkItemExecution::getStatus).toList();
        List<String> reasons = plan.getOrderedItems().stream().map(WorkItemExecution::getSkipReason).toList();

        plan.correctZone(plan.getOrderedItems(), PARIS, null, null);
        plan.getExecutor().analyseDeferred();

        assertEquals(statuses, plan.getOrderedItems().stream().map(WorkItemExecution::getStatus).toList());
        assertEquals(reasons, plan.getOrderedItems().stream().map(WorkItemExecution::getSkipReason).toList());
        assertEquals("05", targetDay(plan, named(plan, "IMG_04.JPG")), "analysed again, its new target there");
    }

    // ---- kept in the state file (spec timezone-correction §3, like an own out pattern) ----

    /** Like {@link CopybotEngine}: the state file keeps the corrections whatever the resume mode. */
    private MainExecutor kept(int days, ZoneAnalyze analyze, CaptureDayOut out, ResumeContext resume) {
        MainExecutor exec = executor(days, analyze, out, resume);
        exec.setOutPatternStore(store());
        return exec;
    }

    private Plan keptNone(int days, ZoneAnalyze analyze, CaptureDayOut out) {
        return prepared(kept(days, analyze, out, new ResumeContext(ResumeMode.NONE, store())));
    }

    @Test
    public void aCorrectionIsWrittenToTheStateWithTheKeyOfAnOwnPattern() {
        Plan plan = keptNone(3, new ZoneAnalyze(), new CaptureDayOut());
        WorkItemExecution first = named(plan, "IMG_01.JPG");
        WorkItemExecution second = named(plan, "IMG_02.JPG");

        plan.correctZone(List.of(first), PARIS, ZoneOffset.ofHours(9), null);
        plan.correctZone(List.of(second), MEXICO, null, null);

        assertEquals(Map.of(day(1), new ZoneCorrection(PARIS, ZoneOffset.ofHours(9)), day(2), new ZoneCorrection(MEXICO, null)),
                store().readZoneCorrections(), "written when corrected, before any analysis");
        assertTrue(store().readOutPatterns().isEmpty());
        assertTrue(store().readCursor().isEmpty(), "mode none: no cursor");

        plan.correctZone(List.of(first), MEXICO, null, null);

        assertEquals(new ZoneCorrection(MEXICO, null), store().readZoneCorrections().get(day(1)), "the last one wins");
    }

    @Test
    public void aNewPreparationRestoresTheCorrectionBeforeTheAnalysis() {
        Plan plan = keptNone(3, new ZoneAnalyze(), new CaptureDayOut());
        plan.correctZone(List.of(named(plan, "IMG_01.JPG")), PARIS, ZoneOffset.ofHours(1), null);

        ZoneAnalyze analyze = new ZoneAnalyze();
        Plan again = keptNone(3, analyze, new CaptureDayOut());
        WorkItemExecution first = named(again, "IMG_01.JPG");

        WorkItemMetadata metadata = first.getWorkItem().getMetadatas();
        assertEquals(Optional.of(PARIS), metadata.getZone(WorkItemMetadata.CAPTURE_ZONE));
        assertEquals(Optional.of(ZoneOffset.ofHours(1)), metadata.getZone(WorkItemMetadata.CAMERA_CLOCK));
        assertEquals(1, analyze.times("IMG_01.JPG"), "analysed once, in its zone");
        assertEquals("02", targetDay(again, first));
        assertEquals("02", targetDay(again, named(again, "IMG_02.JPG")), "the others as listed");
        assertFalse(named(again, "IMG_02.JPG").getWorkItem().getMetadatas().raw().containsKey(WorkItemMetadata.CAPTURE_ZONE));
        assertTrue(again.toAnalyse().isEmpty(), "nothing to analyse again");
    }

    @Test
    public void everyResumeModeRestoresTheCorrection() {
        for (ResumeContext resume : List.of(new ResumeContext(ResumeMode.NONE, store()),
                new ResumeContext(ResumeMode.STATE, store()),
                new ResumeContext(ResumeMode.DESTINATION, store()),
                new ResumeContext(ResumeMode.DESTINATION, store(), DestinationCheck.EVERY_FILE, DestinationMatch.FILE))) {
            store().writeZoneCorrections(Map.of(day(3), new ZoneCorrection(PARIS, null)));
            ZoneAnalyze analyze = new ZoneAnalyze();

            Plan plan = prepared(kept(3, analyze, new CaptureDayOut(), resume));

            WorkItemExecution third = named(plan, "IMG_03.JPG");
            assertEquals(Optional.of(PARIS), third.getWorkItem().getMetadatas().getZone(WorkItemMetadata.CAPTURE_ZONE), resume.toString());
            assertEquals(1, analyze.times("IMG_03.JPG"), resume.toString());
            assertEquals("04", targetDay(plan, third), resume.toString());
        }
    }

    @Test
    public void aStreamingRunWritesARestoredFileAtItsCorrectedTarget() {
        store().writeZoneCorrections(Map.of(day(1), new ZoneCorrection(PARIS, null)));
        CaptureDayOut out = new CaptureDayOut();
        MainExecutor exec = kept(2, new ZoneAnalyze(), out, null);

        exec.stream();

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus(), String.valueOf(exec.getState().getFailure()));
        assertEquals(Map.of("IMG_01.JPG", "02", "IMG_02.JPG", "02"), out.written);
    }

    @Test
    public void everyFile_aRestoredCorrectionIsProbedAtItsCorrectedTarget() throws IOException {
        atDestination("01", "IMG_01.JPG"); // its target as listed
        atDestination("03", "IMG_02.JPG"); // its corrected target
        store().writeZoneCorrections(Map.of(day(1), new ZoneCorrection(PARIS, null), day(2), new ZoneCorrection(PARIS, null)));
        ZoneAnalyze analyze = new ZoneAnalyze();

        Plan plan = prepared(kept(3, analyze, new CaptureDayOut(),
                new ResumeContext(ResumeMode.DESTINATION, store(), DestinationCheck.EVERY_FILE, DestinationMatch.FILE)));

        WorkItemExecution first = named(plan, "IMG_01.JPG");
        WorkItemExecution second = named(plan, "IMG_02.JPG");
        assertEquals(ItemStatus.PENDING, first.getStatus(), "nas/02 is free");
        assertEquals(ItemStatus.SKIPPED, second.getStatus());
        assertEquals(atDestinationReason("03", "IMG_02.JPG"), second.getSkipReason());
        assertEquals(1, analyze.times("IMG_01.JPG"));
        assertEquals(1, analyze.times("IMG_02.JPG"));
        assertTrue(plan.toAnalyse().isEmpty(), "no recheck: probed once, corrected");
    }

    @Test
    public void undoingARestoredCorrectionRemovesItAndAnalysesTheFileAgain() throws IOException {
        store().writeZoneCorrections(Map.of(day(1), new ZoneCorrection(PARIS, null), day(2), new ZoneCorrection(MEXICO, null)));
        ZoneAnalyze analyze = new ZoneAnalyze();
        Plan plan = keptNone(3, analyze, new CaptureDayOut());
        WorkItemExecution first = named(plan, "IMG_01.JPG");

        plan.clearZone(List.of(first), null);

        assertEquals(Map.of(day(2), new ZoneCorrection(MEXICO, null)), store().readZoneCorrections());
        assertEquals(List.of(first), plan.toAnalyse());
        plan.getExecutor().analyseDeferred();
        assertEquals(2, analyze.times("IMG_01.JPG"));
        assertEquals("01", targetDay(plan, first));

        plan.clearZone(List.of(named(plan, "IMG_02.JPG")), null);

        assertEquals(Map.of(), store().readZoneCorrections());
        assertFalse(Files.readString(store().getPath()).contains("zoneCorrections"));
    }

    @Test
    public void anUnanalysedFileRestoredWithItsCorrectionLosesIt() {
        store().writeCursor(day(2));
        store().writeZoneCorrections(Map.of(day(1), new ZoneCorrection(PARIS, null)));
        ZoneAnalyze analyze = new ZoneAnalyze();
        Plan plan = prepared(kept(3, analyze, new CaptureDayOut(), new ResumeContext(ResumeMode.STATE, store())));
        WorkItemExecution first = named(plan, "IMG_01.JPG");
        assertTrue(first.isAnalysisDeferred(), "before the cursor");
        assertEquals(Optional.of(PARIS), first.getWorkItem().getMetadatas().getZone(WorkItemMetadata.CAPTURE_ZONE), "restored at the listing");

        plan.clearZone(List.of(first), null);

        assertFalse(first.getWorkItem().getMetadatas().raw().containsKey(WorkItemMetadata.CAPTURE_ZONE));
        assertEquals(Map.of(), store().readZoneCorrections());
        assertTrue(plan.toAnalyse().isEmpty(), "not asked for: still not analysed");
        assertEquals(0, analyze.times("IMG_01.JPG"));
    }

    @Test
    public void aFailingWriteChangesNothing() throws IOException {
        Plan plan = keptNone(2, new ZoneAnalyze(), new CaptureDayOut());
        WorkItemExecution first = named(plan, "IMG_01.JPG");
        // a non-empty directory where the state file goes: the write fails
        Files.createDirectories(store().getPath().resolve("child"));

        CopybotException e = assertThrows(CopybotException.class, () -> plan.correctZone(List.of(first), PARIS, null, null));

        assertEquals(ResourcesEngine.getString("resume.state.write-error", store().getPath().toAbsolutePath()), e.getMessage());
        assertFalse(first.getWorkItem().getMetadatas().raw().containsKey(WorkItemMetadata.CAPTURE_ZONE));
        assertFalse(first.isAnalysisDeferred());
        assertTrue(plan.toAnalyse().isEmpty());
    }

    @Test
    public void anInvalidZoneInTheStateFailsThePreparationLikeAnInvalidOwnPattern() throws IOException {
        Files.writeString(store().getPath(),
                "{\"zoneCorrections\":[{\"date\":\"2026-09-01T23:30:00Z\",\"name\":\"IMG_01.JPG\",\"zone\":\"Europe/Nowhere\"}]}");
        MainExecutor exec = kept(2, new ZoneAnalyze(), new CaptureDayOut(), new ResumeContext(ResumeMode.NONE, store()));

        exec.prepare();

        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus());
        assertEquals(ResourcesEngine.getString("resume.state.invalid", store().getPath().toAbsolutePath()),
                exec.getState().getFailure().getMessage());
    }

    @Test
    public void withoutAStoreTheCorrectionStaysInMemory() {
        Plan plan = prepared(3, new ZoneAnalyze(), new CaptureDayOut());

        plan.correctZone(List.of(named(plan, "IMG_01.JPG")), PARIS, null, null);

        assertFalse(Files.exists(store().getPath()));
    }
}

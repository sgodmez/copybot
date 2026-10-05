package com.copybot.ui.model;

import com.copybot.engine.ItemDetail;
import com.copybot.engine.Plan;
import com.copybot.engine.Projection;
import com.copybot.engine.TargetProjection;
import com.copybot.engine.pipeline.ExecutionMode;
import com.copybot.engine.pipeline.PipelineState;
import com.copybot.engine.pipeline.PipelineStatus;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.engine.resume.ItemKey;
import com.copybot.engine.resume.ResumePoint;
import com.copybot.engine.resume.ResumeProposal;
import com.copybot.engine.resume.ResumeSource;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import com.copybot.plugin.api.action.WorkStatus;
import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.model.PlanViewModel.Filter;
import com.copybot.ui.model.PlanViewModel.Phase;
import com.copybot.ui.model.PlanViewModel.PrepareStage;
import com.copybot.ui.model.PlanViewModel.Progress;
import com.copybot.ui.model.RecentPipelines.LastRun;
import com.copybot.utils.FileUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The plan view without JavaFX (spec desktop-ui §2, §7). */
public class PlanViewModelTest {

    @TempDir
    Path tempDir;

    private static final Instant SHOT = Instant.parse("2026-09-28T15:42:00Z");

    @BeforeAll
    public static void registerUiBundle() {
        ResourcesEngine.registerBundle("com.copybot.ui.i18n.uiBundle");
    }

    private WorkItemExecution item(String name, long size) throws IOException {
        WorkItem wi = new WorkItem(tempDir.resolve(name));
        wi.getMetadatas().setSize(size);
        wi.getMetadatas().setTime(WorkItemMetadata.LAST_MODIFIED, SHOT);
        return new WorkItemExecution(wi, List.of());
    }

    private static PipelineState state(PipelineStatus status, WorkItemExecution... items) {
        PipelineState state = new PipelineState(List.of());
        state.setStatus(status);
        state.getWorkItems().addAll(List.of(items));
        return state;
    }

    private static ResumeProposal proposal(ResumePoint point, ResumeSource source, String... warnings) {
        return new ResumeProposal(point, source, List.of(warnings));
    }

    /** A model whose "automatic execution" box is checked or not. */
    private static PlanViewModel model(boolean autoExecute) {
        PlanViewModel model = new PlanViewModel();
        model.setAutoExecute(autoExecute);
        return model;
    }

    /** A model with a prepared plan of these items (resume: everything). */
    private static PlanViewModel prepared(boolean autoExecute, WorkItemExecution... items) {
        PlanViewModel model = model(autoExecute);
        model.startPreparing();
        PipelineState state = state(PipelineStatus.PREPARED, items);
        state.setResumeProposal(proposal(ResumePoint.all(), ResumeSource.NONE));
        model.update(state, List.of(items));
        return model;
    }

    private static void assertTranslated(String text) {
        assertFalse(text.isEmpty());
        assertFalse(text.startsWith("%"), text);
    }

    // ---- phases and buttons ----

    @Test
    public void nothingIsPreparedAtOpening() {
        PlanViewModel model = new PlanViewModel();

        assertEquals(Phase.NOT_PREPARED, model.phase());
        assertTrue(model.canPrepare());
        assertTrue(model.canGoBack());
        assertFalse(model.canCopy());
        assertFalse(model.canStop());
        assertEquals(List.of(), model.items());
        assertEquals(Optional.empty(), model.resumeText());
    }

    @Test
    public void thePreparationListsTheRowsAndLocksTheView() throws IOException {
        PlanViewModel model = new PlanViewModel();
        WorkItemExecution a = item("a.jpg", 1);

        model.startPreparing();
        model.update(state(PipelineStatus.RUNNING, a), List.of());

        assertEquals(Phase.PREPARING, model.phase());
        assertEquals(List.of(a), model.items(), "the table fills during the listing");
        assertFalse(model.canGoBack());
        assertFalse(model.canPrepare());
        assertFalse(model.canStop(), "a preparation is not stopped from the view");
        assertTrue(model.isActive());
        assertTrue(model.progressText().contains("1"), model.progressText());
        assertEquals("", model.statusLine(), "the preparation is told next to its bar");
    }

    @Test
    public void thePreparationBarListsThenAnalysesThenResolves() throws IOException {
        PlanViewModel model = new PlanViewModel();
        WorkItemExecution a = item("a.jpg", 1);
        WorkItemExecution b = item("b.jpg", 1);
        model.startPreparing();
        assertEquals(PrepareStage.LISTING, model.prepareStage(), "listing until the engine says otherwise");

        PipelineState state = state(PipelineStatus.RUNNING, a, b);
        state.setListingInProgress(true);
        model.update(state, List.of());
        assertEquals(PrepareStage.LISTING, model.prepareStage());
        assertEquals(PlanViewModel.INDETERMINATE, model.prepareFraction(), "the total is unknown while listing");
        assertTrue(model.progressText().contains("2"), model.progressText());

        state.setListingInProgress(false);
        a.markPrepared();
        model.update(state, List.of());
        assertEquals(PrepareStage.ANALYSING, model.prepareStage());
        assertEquals(0.5, model.prepareFraction());
        assertTrue(model.progressText().contains("1 / 2"), model.progressText());

        b.markPrepared();
        model.update(state, List.of());
        assertEquals(PrepareStage.RESOLVING, model.prepareStage(), "every file analysed: the resume point remains");
        assertEquals(PlanViewModel.INDETERMINATE, model.prepareFraction());
        assertTranslated(model.progressText());
    }

    @Test
    public void aPreparedPlanCanBeCopiedOrPreparedAgain() throws IOException {
        PlanViewModel model = prepared(false, item("a.jpg", 1));

        assertEquals(Phase.PREPARED, model.phase());
        assertTrue(model.canCopy());
        assertTrue(model.canPrepare());
        assertTrue(model.canGoBack());
        assertTrue(model.canChangeResumePoint());
        assertFalse(model.isActive());
    }

    @Test
    public void aFailedOrCancelledPreparation() throws IOException {
        PlanViewModel model = new PlanViewModel();
        model.startPreparing();
        PipelineState failed = state(PipelineStatus.ERROR);
        failed.setFailure(new IllegalStateException("card removed"));

        model.update(failed, List.of());

        assertEquals(Phase.PREPARE_FAILED, model.phase());
        assertTrue(model.statusLine().contains("card removed"), model.statusLine());
        assertTrue(model.canPrepare());
        assertFalse(model.canCopy());

        model.startPreparing();
        model.update(state(PipelineStatus.CANCELLED), List.of());
        assertEquals(Phase.NOT_PREPARED, model.phase());
    }

    /** An item the preparation skipped at the listing without analysing it, selected again by a manual point. */
    private WorkItemExecution deferredSelected(String name) throws IOException {
        WorkItemExecution item = item(name, 1);
        item.deferAnalysis("before the cursor");
        item.setReady();
        return item;
    }

    @Test
    public void theFilesSelectedAgainAreAnalysedWithTheViewLocked() throws IOException {
        WorkItemExecution old1 = deferredSelected("old1.jpg");
        WorkItemExecution old2 = deferredSelected("old2.jpg");
        WorkItemExecution recent = item("recent.jpg", 1);
        PlanViewModel model = prepared(true, old1, old2, recent);
        model.setOverride(ResumePoint.all());

        model.startAnalysing(List.of(old1, old2));

        assertEquals(Phase.ANALYSING, model.phase());
        assertTrue(model.isActive(), "the engine is held");
        assertFalse(model.isExecutionActive());
        assertFalse(model.canCopy());
        assertFalse(model.canPrepare());
        assertFalse(model.canGoBack());
        assertFalse(model.canChangeResumePoint(), "the point is not changed while the files are analysed");
        assertTrue(model.resumePointFrom(recent).isEmpty());
        assertTrue(model.ignorable(List.of(recent)).isEmpty());
        assertTrue(model.canStop(), "the analysis can be stopped");
        assertFalse(model.canPause());
        assertTrue(model.resumeText().isPresent(), "the resume line stays");
        assertEquals("", model.statusLine());
        assertEquals(ResourcesEngine.getString("plan.analysing-again", 0, 2), model.progressText());
        assertEquals(0.0, model.prepareFraction());

        old1.markPrepared(); // analysed: no longer deferred
        model.update(state(PipelineStatus.RUNNING, old1, old2, recent), List.of(old1, old2, recent));
        assertEquals(Phase.ANALYSING, model.phase());
        assertEquals(ResourcesEngine.getString("plan.analysing-again", 1, 2), model.progressText());
        assertEquals(0.5, model.prepareFraction());

        PipelineState done = state(PipelineStatus.PREPARED, old1, old2, recent);
        done.setResumeProposal(proposal(ResumePoint.all(), ResumeSource.NONE));
        model.update(done, List.of(old1, old2, recent));
        assertEquals(Phase.PREPARED, model.phase(), "the plan is ready again, even when stopped before the end");
        assertTrue(model.canCopy());
        assertTrue(model.canChangeResumePoint());
        assertEquals(ResumePoint.all(), model.override(), "the manual point is kept");
        assertFalse(model.consumeAutoExecute(), "never an automatic execution after an analysis");
    }

    @Test
    public void theExecutionCanBePausedResumedAndStopped() throws IOException {
        WorkItemExecution a = item("a.jpg", 1);
        PlanViewModel model = prepared(false, a);

        model.startExecuting();
        assertEquals(Phase.RUNNING, model.phase());
        assertTrue(model.canPause());
        assertTrue(model.canStop());
        assertFalse(model.canResume());
        assertFalse(model.canGoBack(), "no way back during an execution");
        assertFalse(model.canChangeResumePoint());

        model.update(state(PipelineStatus.PAUSED, a), List.of(a));
        assertEquals(Phase.PAUSED, model.phase());
        assertTrue(model.canResume());
        assertFalse(model.canPause());
        assertTrue(model.canStop());

        a.setDone();
        model.update(state(PipelineStatus.SUCCESS, a), List.of(a));
        assertEquals(Phase.FINISHED, model.phase());
        assertTrue(model.canPrepare());
        assertTrue(model.canGoBack());
        assertFalse(model.canStop());
        assertTranslated(model.statusLine());
    }

    @Test
    public void nothingToCopyDisablesTheCopy() throws IOException {
        WorkItemExecution a = item("a.jpg", 1);
        a.setSkipped("already imported");

        assertFalse(prepared(false, a).canCopy());
    }

    @Test
    public void aPipelineThatDoesNothingCannotBeCopiedAndSaysWhy() throws IOException {
        WorkItemExecution a = item("a.jpg", 1);
        PlanViewModel model = prepared(false, a);
        assertTrue(model.canCopy());
        assertTrue(model.warning().isEmpty());

        model.setExecutionRefusal(Optional.of("nothing is done with the files"));

        assertFalse(model.canCopy());
        assertEquals(Optional.of("nothing is done with the files"), model.warning());
        assertEquals(1, model.counts().selected(), "the plan is still shown");

        model.startPreparing(); // a new preparation forgets the previous plan's refusal
        assertTrue(model.warning().isEmpty());
    }

    @Test
    public void nothingToCopyIsAWarning() throws IOException {
        WorkItemExecution skipped = item("a.jpg", 1);
        skipped.setSkipped("already imported");

        assertEquals(Optional.of(ResourcesEngine.getString("plan.warning.nothing-to-copy")),
                prepared(false, skipped).warning());
    }

    // ---- scroll to the resume junction ----

    /** n rows: the first `skipped` already imported, the others to copy. */
    private List<WorkItemExecution> rows(int n, int skipped) throws IOException {
        List<WorkItemExecution> rows = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            WorkItemExecution row = item("f" + i + ".jpg", 1);
            if (i < skipped) {
                row.setSkipped("already imported");
            }
            rows.add(row);
        }
        return rows;
    }

    @Test
    public void theTableScrollsToShowTheLastImportedRowsThenTheFirstToCopy() throws IOException {
        assertEquals(15, PlanViewModel.resumeScrollIndex(rows(30, 20), 5), "rows 15-19 imported, then 20 to copy");
    }

    @Test
    public void fewImportedRowsKeepTheTop() throws IOException {
        assertEquals(0, PlanViewModel.resumeScrollIndex(rows(30, 3), 5));
        assertEquals(0, PlanViewModel.resumeScrollIndex(rows(30, 0), 5), "no resume: from the top");
    }

    @Test
    public void everythingImportedShowsTheLastRows() throws IOException {
        assertEquals(25, PlanViewModel.resumeScrollIndex(rows(30, 30), 5));
        assertEquals(0, PlanViewModel.resumeScrollIndex(List.of(), 5));
    }

    @Test
    public void aPipelineWithoutInputCannotBePreparedAndSaysWhy() {
        PlanViewModel model = new PlanViewModel();

        model.setPreparationRefusal(Optional.of("nothing to list"));

        assertFalse(model.canPrepare());
        assertEquals(Optional.of("nothing to list"), model.warning());
        model.reset(); // a reload keeps it until the pipeline is read again
        assertFalse(model.canPrepare());

        model.setPreparationRefusal(Optional.empty()); // the pipeline was fixed in the editor
        assertTrue(model.canPrepare());
        assertTrue(model.warning().isEmpty());
    }

    @Test
    public void aPipelineThatDoesNothingCannotBeRunInOneStepButCanBePlanned() {
        PlanViewModel model = new PlanViewModel();
        model.setNothingDoneRefusal(Optional.of("does nothing"));

        model.setExecutionMode(ExecutionMode.PLAN);
        assertTrue(model.canPrepare(), "the plan can be looked at, the copy is refused afterwards");
        assertTrue(model.warning().isEmpty());

        for (ExecutionMode oneStep : List.of(ExecutionMode.AUTO, ExecutionMode.STREAMING)) {
            model.setExecutionMode(oneStep);
            assertFalse(model.canPrepare(), oneStep + ": its button copies");
            assertEquals(Optional.of("does nothing"), model.warning(), oneStep.name());
        }

        model.setNothingDoneRefusal(Optional.empty()); // an output step was added in the editor
        assertTrue(model.canPrepare());
    }

    @Test
    public void aRunningPreparationIsNoWarning() {
        PlanViewModel model = new PlanViewModel();

        model.startPreparing();

        assertTrue(model.warning().isEmpty(), "the status line tells it");
    }

    // ---- rows ----

    @Test
    public void theFilterKeepsTheMatchingRows() throws IOException {
        WorkItemExecution pending = item("a.jpg", 1);
        WorkItemExecution skipped = item("b.jpg", 1);
        skipped.setSkipped("already imported");
        WorkItemExecution failed = item("c.jpg", 1);
        failed.setError(new IllegalStateException("unreadable"));
        PlanViewModel model = prepared(false, pending, skipped, failed);

        assertEquals(List.of(pending, skipped, failed), model.visibleItems());
        model.setFilter(Filter.TO_COPY);
        assertEquals(List.of(pending), model.visibleItems());
        model.setFilter(Filter.SKIPPED);
        assertEquals(List.of(skipped), model.visibleItems());
        model.setFilter(Filter.ERRORS);
        assertEquals(List.of(failed), model.visibleItems());
    }

    @Test
    public void theRowsFollowTheResumeOrderThenTheForkedItems() throws IOException {
        WorkItemExecution first = item("a.jpg", 1);
        WorkItemExecution second = item("b.jpg", 1);
        WorkItemExecution forked = item("a-small.jpg", 1);
        PlanViewModel model = new PlanViewModel();
        model.startPreparing();

        model.update(state(PipelineStatus.PREPARED, forked, second, first), List.of(first, second));

        assertEquals(List.of(first, second, forked), model.items());
    }

    // ---- counters and texts ----

    @Test
    public void theCopyButtonTellsTheSelectedFilesAndSize() throws IOException {
        WorkItemExecution a = item("a.jpg", 1536);
        WorkItemExecution b = item("b.jpg", 1536);
        WorkItemExecution c = item("c.jpg", 1536);
        c.setSkipped("already imported");
        PlanViewModel model = prepared(false, a, b, c);

        assertEquals(new Plan.Counts(2, 1, 0, 3072), model.counts());
        String label = model.copyLabel();
        assertTranslated(label);
        assertTrue(label.contains("2"), label);
        assertTrue(label.contains(FileUtil.toAutoUnitSize(3072, 1, Locale.getDefault())), label); // the display form
    }

    @Test
    public void everyStatusHasItsText() throws IOException {
        WorkItemExecution pending = item("a.jpg", 1);
        WorkItemExecution waiting = item("b.jpg", 1);
        waiting.setWaitingResources(0, Set.of("disk:D"));
        WorkItemExecution running = item("c.jpg", 1);
        running.setRunning(0);
        running.setWorkStatus(new WorkStatus("copy", 42));
        WorkItemExecution runningUnknown = item("d.jpg", 1);
        runningUnknown.setRunning(0);
        WorkItemExecution done = item("e.jpg", 1);
        done.setDone();
        WorkItemExecution skipped = item("f.jpg", 1);
        skipped.setSkipped("already imported (cursor)");
        WorkItemExecution failed = item("g.jpg", 1);
        failed.setError(new IllegalStateException("disk full"));

        for (WorkItemExecution item : List.of(pending, waiting, running, runningUnknown, done, skipped, failed)) {
            assertTranslated(PlanViewModel.statusText(item));
        }
        assertTrue(PlanViewModel.statusText(waiting).contains("disk:D"));
        assertTrue(PlanViewModel.statusText(running).contains("42"));
        assertTrue(PlanViewModel.statusText(skipped).contains("already imported (cursor)"));
        assertTrue(PlanViewModel.statusText(failed).contains("disk full"));
        assertNotEquals(PlanViewModel.statusText(pending), PlanViewModel.statusText(done));
    }

    @Test
    public void aFileSkippedByTheResumePointSaysSkippedOnlyItsReasonInTheTooltip() throws IOException {
        WorkItemExecution byPoint = item("a.jpg", 1);
        byPoint.setSkippedByResumePoint("Already imported (cursor: a.jpg)");
        WorkItemExecution byOut = item("b.jpg", 1);
        byOut.setSkipped("identical to the destination");

        assertEquals(ResourcesEngine.getString("item.status.SKIPPED.no-reason"), PlanViewModel.statusText(byPoint),
                "the resume point is told once, by the header");
        assertEquals(Optional.of("Already imported (cursor: a.jpg)"), PlanViewModel.statusTooltip(byPoint));
        assertTrue(PlanViewModel.statusText(byOut).contains("identical to the destination"), "its own reason");
        assertEquals(Optional.empty(), PlanViewModel.statusTooltip(byOut));
    }

    @Test
    public void onlyTheFilesNotSkippedShowTheirTarget() throws IOException {
        WorkItemExecution pending = item("a.jpg", 1);
        WorkItemExecution skipped = item("b.jpg", 1);
        skipped.setSkippedByResumePoint("before the cursor");
        WorkItemExecution ignored = item("c.jpg", 1);
        ignored.setIgnored("ignored by the user");
        WorkItemExecution failed = item("d.jpg", 1);
        failed.setError(new IllegalStateException("x"));

        assertTrue(PlanViewModel.showsTarget(pending));
        assertFalse(PlanViewModel.showsTarget(skipped));
        assertFalse(PlanViewModel.showsTarget(ignored));
        assertTrue(PlanViewModel.showsTarget(failed));
    }

    @Test
    public void theResumeBannerCountsTheFilesTheResumePointSkips() throws IOException {
        WorkItemExecution a = item("a.jpg", 1);
        a.setSkippedByResumePoint("before");
        WorkItemExecution b = item("b.jpg", 1);
        b.setSkippedByResumePoint("before");
        WorkItemExecution c = item("c.jpg", 1);
        c.setIgnored("ignored by the user");
        WorkItemExecution d = item("d.jpg", 1);
        PlanViewModel model = new PlanViewModel();
        model.startPreparing();
        PipelineState state = state(PipelineStatus.PREPARED, a, b, c, d);
        state.setResumeProposal(proposal(ResumePoint.after(new ItemKey(SHOT, "b.jpg")), ResumeSource.DESTINATION));
        model.update(state, List.of(a, b, c, d));

        assertEquals(Optional.of(ResourcesEngine.getString("plan.resume.count.imported", 2)), model.resumeCountText());

        model.setOverride(ResumePoint.from(new ItemKey(SHOT, "b.jpg")));
        b.setReady();
        assertEquals(Optional.of(ResourcesEngine.getString("plan.resume.count.before", 1)), model.resumeCountText(),
                "a manual point: the files before it, not imported ones");

        model.setOverride(ResumePoint.all());
        a.setReady();
        assertEquals(Optional.empty(), model.resumeCountText(), "nothing skipped by the point");
    }

    @Test
    public void theDateColumnShowsTheResumeKeyOrTheFileDate() throws IOException {
        WorkItemExecution item = item("a.jpg", 1);
        String local = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").format(SHOT.atZone(ZoneId.systemDefault()));

        assertEquals(local, PlanViewModel.dateText(item), "before the preparation: the modification date");
        item.setResumeKey(new ItemKey(SHOT.plusSeconds(3600), "a.jpg"));
        assertNotEquals(local, PlanViewModel.dateText(item), "then the frozen resume key");
    }

    @Test
    public void theWarningsAreTheConfigurationOnesThenTheResumeOnes() throws IOException {
        PlanViewModel model = new PlanViewModel();
        model.startPreparing();
        PipelineState state = state(PipelineStatus.PREPARED, item("a.jpg", 1));
        state.setWarnings(List.of("sources deleted"));
        state.setResumeProposal(proposal(ResumePoint.all(), ResumeSource.NONE, "no target paths"));

        model.update(state, List.of());

        assertEquals(List.of("sources deleted", "no target paths"), model.warnings());
    }

    @Test
    public void theResumeLineNamesThePointAndItsOrigin() throws IOException {
        PlanViewModel model = new PlanViewModel();
        model.startPreparing();
        PipelineState state = state(PipelineStatus.PREPARED, item("DSC_4822.JPG", 1));
        state.setResumeProposal(proposal(ResumePoint.after(new ItemKey(SHOT, "DSC_4821.JPG")), ResumeSource.STATE));
        model.update(state, List.of());

        String proposed = model.resumeText().orElseThrow();
        assertTranslated(proposed);
        assertTrue(proposed.contains("DSC_4821.JPG"), proposed);

        model.setOverride(Plan.fromDate(LocalDate.of(2026, 9, 20)));
        String manual = model.resumeText().orElseThrow();
        assertTrue(manual.contains("20/09/2026"), manual);
        assertNotEquals(proposed, manual);

        model.setOverride(ResumePoint.all());
        assertTranslated(model.resumeText().orElseThrow());
    }

    @Test
    public void resumeFromHereNeedsAPreparedPlanAndAKey() throws IOException {
        WorkItemExecution keyed = item("a.jpg", 1);
        keyed.setResumeKey(new ItemKey(SHOT, "a.jpg"));
        WorkItemExecution noKey = item("b.jpg", 1);
        noKey.setResumeKey(null);
        PlanViewModel model = prepared(false, keyed, noKey);

        assertEquals(Optional.of(ResumePoint.from(new ItemKey(SHOT, "a.jpg"))), model.resumePointFrom(keyed));
        assertEquals(Optional.empty(), model.resumePointFrom(noKey));
        model.startExecuting();
        assertEquals(Optional.empty(), model.resumePointFrom(keyed));
    }

    // ---- files ignored by the user ----

    @Test
    public void thePlannedProcessingShowsTheAnalysedRowsOfTheSelectionFromTheClickedOne() throws IOException {
        WorkItemExecution a = item("a.jpg", 1);
        a.setProjection(new Projection.Projected(List.of(a.getWorkItem())));
        WorkItemExecution notAnalysed = item("b.jpg", 1);
        WorkItemExecution c = item("c.jpg", 1);
        c.setProjection(new Projection.Projected(List.of(c.getWorkItem())));
        List<WorkItemExecution> selection = List.of(a, notAnalysed, c);

        List<WorkItemExecution> shown = PlanViewModel.detailable(selection);

        assertEquals(List.of(a, c), shown, "whatever the clicked row");
        assertEquals(1, PlanViewModel.detailStart(shown, c), "from the clicked row");
        assertEquals(0, PlanViewModel.detailStart(shown, notAnalysed), "the first one when the clicked row has none");
        assertEquals("2 / 2", ResourcesEngine.getString("plan.detail.position", 2, 2));
    }

    @Test
    public void onlyTheSkippedRowsNotAnalysedCanBeAnalysed() throws IOException {
        WorkItemExecution deferred = item("a.jpg", 1);
        deferred.deferAnalysis("before the cursor");
        WorkItemExecution analysedSkipped = item("b.jpg", 1);
        analysedSkipped.setSkippedByResumePoint("before the cursor");
        WorkItemExecution toCopy = item("c.jpg", 1);
        PlanViewModel model = prepared(false, deferred, analysedSkipped, toCopy);
        List<WorkItemExecution> all = List.of(deferred, analysedSkipped, toCopy);

        assertEquals(List.of(deferred), model.analysable(all));

        model.startExecuting();

        assertEquals(List.of(), model.analysable(all), "only on a prepared, idle plan");
    }

    @Test
    public void onlyRowsToCopyOrSkippedByTheResumePointCanBeIgnoredForThisRun() throws IOException {
        WorkItemExecution toCopy = item("a.jpg", 1);
        WorkItemExecution beforeCursor = item("b.jpg", 1);
        beforeCursor.setSkipped("already imported");
        WorkItemExecution failed = item("c.jpg", 1);
        failed.setError(new IllegalStateException("no date"));
        WorkItemExecution ignored = item("d.jpg", 1);
        ignored.setIgnored("ignored by the user");
        PlanViewModel model = prepared(false, toCopy, beforeCursor, failed, ignored);
        List<WorkItemExecution> all = List.of(toCopy, beforeCursor, failed, ignored);

        assertEquals(List.of(toCopy, beforeCursor), model.ignorable(all));
        assertEquals(List.of(ignored), model.unignorable(all));

        model.startExecuting();

        assertEquals(List.of(), model.ignorable(all), "not once the copy started");
        assertEquals(List.of(), model.unignorable(all));
    }

    @Test
    public void alwaysIgnoreExcludesTheListedFilesOnceEachWhenTheEngineIsIdle() throws IOException {
        WorkItemExecution a = item("a.jpg", 1);
        WorkItemExecution fork = item("a-2.jpg", 1);
        fork.setParent(a);
        WorkItemExecution b = item("b.jpg", 1);
        b.setDone();
        PlanViewModel model = prepared(false, a, fork, b);

        assertEquals(List.of(tempDir.resolve("a.jpg"), tempDir.resolve("b.jpg")), model.excludable(List.of(a, fork, b)));

        model.startExecuting();
        assertEquals(List.of(), model.excludable(List.of(a, b)), "not while copying");

        model.update(state(PipelineStatus.SUCCESS, a, b), List.of(a, b));
        assertEquals(List.of(tempDir.resolve("b.jpg")), model.excludable(List.of(b)), "after the copy too");
    }

    // ---- automatic execution, progress, end of run ----

    @Test
    public void theAutomaticExecutionStartsOnceWhenThePlanIsReady() throws IOException {
        PlanViewModel model = prepared(true, item("a.jpg", 1));

        assertTrue(model.consumeAutoExecute());
        assertFalse(model.consumeAutoExecute(), "once");
    }

    @Test
    public void theAutomaticExecutionFollowsTheSessionBox() throws IOException {
        assertFalse(prepared(false, item("a.jpg", 1)).consumeAutoExecute());

        PlanViewModel unchecked = model(true);
        unchecked.setAutoExecute(false);
        unchecked.startPreparing();
        unchecked.update(state(PipelineStatus.PREPARED, item("b.jpg", 1)), List.of());
        assertFalse(unchecked.consumeAutoExecute());
    }

    // ---- execution modes (spec execution-mode §5) ----

    @Test
    public void theAutomaticExecutionBoxIsUncheckedByDefault() throws IOException {
        assertFalse(new PlanViewModel().isAutoExecute());
        PlanViewModel model = new PlanViewModel();
        model.startPreparing();
        model.update(state(PipelineStatus.PREPARED, item("a.jpg", 1)), List.of());
        assertFalse(model.consumeAutoExecute());
    }

    @Test
    public void eachModeHasItsOwnButtonLabel() {
        PlanViewModel model = new PlanViewModel();
        assertEquals(ExecutionMode.PLAN, model.executionMode());
        assertEquals(ResourcesEngine.getString("plan.prepare"), model.prepareLabel());
        model.setExecutionMode(ExecutionMode.AUTO);
        assertEquals(ResourcesEngine.getString("plan.prepare-and-copy"), model.prepareLabel());
        model.setExecutionMode(ExecutionMode.STREAMING);
        assertEquals(ResourcesEngine.getString("plan.stream"), model.prepareLabel());
        for (ExecutionMode mode : ExecutionMode.values()) {
            model.setExecutionMode(mode);
            assertTranslated(model.prepareLabel());
        }
    }

    @Test
    public void onlyThePlanModeShowsTheAutomaticExecutionBox() throws IOException {
        PlanViewModel model = new PlanViewModel();
        assertTrue(model.showsAutoExecuteBox());
        model.setExecutionMode(ExecutionMode.AUTO);
        assertFalse(model.showsAutoExecuteBox());
        model.setExecutionMode(ExecutionMode.STREAMING);
        assertFalse(model.showsAutoExecuteBox());

        PlanViewModel executing = prepared(false, item("a.jpg", 1));
        executing.startExecuting();
        assertFalse(executing.showsAutoExecuteBox(), "not while copying");
    }

    @Test
    public void theAutoModeCopiesAsSoonAsThePlanIsReadyWhateverTheBox() throws IOException {
        PlanViewModel model = new PlanViewModel();
        model.setExecutionMode(ExecutionMode.AUTO);
        model.startPreparing();
        model.update(state(PipelineStatus.PREPARED, item("a.jpg", 1)), List.of());

        assertTrue(model.consumeAutoExecute());
        assertFalse(model.consumeAutoExecute(), "once");
    }

    @Test
    public void theStreamingRunIsAnExecutionFromTheStart() throws IOException {
        PlanViewModel model = new PlanViewModel();
        model.setExecutionMode(ExecutionMode.STREAMING);
        WorkItemExecution old = item("old.jpg", 1000);
        old.deferAnalysis("before the cursor");
        WorkItemExecution a = item("a.jpg", 100);
        WorkItemExecution b = item("b.jpg", 300);

        model.startStreaming();
        assertEquals(Phase.RUNNING, model.phase());
        assertTrue(model.isExecutionActive());
        assertTrue(model.canPause());
        assertTrue(model.canStop());
        assertFalse(model.canPrepare());

        PipelineState state = state(PipelineStatus.RUNNING, old, a, b);
        state.setListingInProgress(true);
        state.setResumeProposal(proposal(ResumePoint.after(new ItemKey(SHOT, "old.jpg")), ResumeSource.STATE));
        a.setDone();
        model.update(state, List.of());

        assertEquals(Phase.RUNNING, model.phase());
        assertEquals(new Progress(1, 2, 100, 400), model.progress(), "the files the resume point skips are not counted");
        assertEquals(PlanViewModel.INDETERMINATE, model.executionFraction(), "the total grows while listing");
        assertTrue(model.resumeText().isPresent(), "the resume banner shows the point");

        state.setListingInProgress(false);
        model.update(state, List.of());
        assertEquals(0.25, model.executionFraction());

        b.setDone();
        state.setStatus(PipelineStatus.SUCCESS);
        model.update(state, List.of());
        assertEquals(Phase.FINISHED, model.phase());
        assertTrue(model.consumeFinishedRun(Instant.now()).isPresent());
        assertTrue(model.canPrepare(), "it can be run again");
    }

    @Test
    public void aStreamingRunFailingBeforeTheListingIsAPreparationFailure() {
        PlanViewModel model = new PlanViewModel();
        model.setExecutionMode(ExecutionMode.STREAMING);
        model.startStreaming();
        PipelineState state = state(PipelineStatus.RUNNING);
        state.setFailure(new IllegalStateException("invalid state file"));
        state.setPreparationFailed(true);
        state.setStatus(PipelineStatus.ERROR);

        model.update(state, List.of());

        assertEquals(Phase.PREPARE_FAILED, model.phase());
        assertEquals(ResourcesEngine.getString("plan.prepare-failed", "invalid state file"), model.statusLine());
    }

    @Test
    public void theResumeBannerOfTheFilesNotAtTheDestination() throws IOException {
        PlanViewModel model = prepared(false, item("a.jpg", 1));
        PipelineState state = state(PipelineStatus.PREPARED, model.items().toArray(WorkItemExecution[]::new));
        state.setResumeProposal(proposal(ResumePoint.notAtDestination(), ResumeSource.DESTINATION));
        model.update(state, model.items());

        assertEquals(Optional.of(ResourcesEngine.getString("plan.resume.missing",
                ResourcesEngine.getString("plan.resume.source.DESTINATION"))), model.resumeText());
    }

    @Test
    public void noAutomaticExecutionOfAFailedOrEmptyPlan() throws IOException {
        PlanViewModel failed = model(true);
        failed.startPreparing();
        failed.update(state(PipelineStatus.ERROR), List.of());
        assertFalse(failed.consumeAutoExecute());

        WorkItemExecution skipped = item("a.jpg", 1);
        skipped.setSkipped("already imported");
        assertFalse(prepared(true, skipped).consumeAutoExecute());
    }

    @Test
    public void theProgressCountsTheItemsSelectedAtTheStart() throws IOException {
        WorkItemExecution a = item("a.jpg", 100);
        WorkItemExecution b = item("b.jpg", 300);
        WorkItemExecution c = item("c.jpg", 50);
        c.setSkipped("already imported");
        PlanViewModel model = prepared(false, a, b, c);
        model.startExecuting();

        a.setDone();
        model.update(state(PipelineStatus.RUNNING, a, b, c), List.of(a, b, c));

        assertEquals(new Progress(1, 2, 100, 400), model.progress());
        assertEquals(0.25, model.progress().fraction(), 1e-9);
        assertTranslated(model.progressText());

        b.setSkipped("identical to the destination");
        assertEquals(new Progress(2, 2, 400, 400), model.progress(), "skipped by the write: processed");
    }

    @Test
    public void theEndOfTheRunIsReportedOnce() throws IOException {
        WorkItemExecution a = item("a.jpg", 1);
        WorkItemExecution b = item("b.jpg", 1);
        WorkItemExecution c = item("c.jpg", 1);
        PlanViewModel model = prepared(false, a, b, c);
        model.startExecuting();
        assertEquals(Optional.empty(), model.consumeFinishedRun(SHOT));

        a.setDone();
        b.setSkipped("identical to the destination");
        c.setError(new IllegalStateException("disk full"));
        model.update(state(PipelineStatus.ERROR, a, b, c), List.of(a, b, c));

        assertEquals(Optional.of(new LastRun(SHOT, PipelineStatus.ERROR, 1, 1, 1)), model.consumeFinishedRun(SHOT));
        assertEquals(Optional.empty(), model.consumeFinishedRun(SHOT), "once");
    }

    @Test
    public void theHeaderTexts() {
        assertTranslated(PlanViewModel.lastRunText(null));
        String run = PlanViewModel.lastRunText(new LastRun(SHOT, PipelineStatus.SUCCESS, 120, 3, 1));
        assertTranslated(run);
        assertTrue(run.contains("120"), run);
        for (String mode : List.of("none", "state", "destination", "stateThenDestination")) {
            assertTranslated(PlanViewModel.resumeModeText(mode));
        }
        assertTranslated(PlanViewModel.resumeModeText(null));
        for (PipelineStatus status : List.of(PipelineStatus.SUCCESS, PipelineStatus.ERROR, PipelineStatus.CANCELLED)) {
            assertTranslated(PlanViewModel.pipelineStatusText(status));
        }
    }

    @Test
    public void theLastRunBadgesTellTheStatusAndTheCounts() {
        assertEquals(List.of(), PlanViewModel.lastRunBadges(null), "never run: no badge");

        List<PlanViewModel.Badge> ok = PlanViewModel.lastRunBadges(new LastRun(SHOT, PipelineStatus.SUCCESS, 107, 803, 0));
        assertEquals(4, ok.size());
        assertEquals(PlanViewModel.BadgeKind.SUCCESS, ok.get(0).kind());
        assertTrue(ok.get(0).text().startsWith("✓ "), ok.get(0).text());
        assertTrue(ok.get(1).text().contains("107"), ok.get(1).text());
        assertTrue(ok.get(2).text().contains("803"), ok.get(2).text());
        assertTrue(ok.get(3).text().startsWith("0 "), ok.get(3).text());
        assertEquals(PlanViewModel.BadgeKind.NEUTRAL, ok.get(3).kind(), "no error: grey");
        ok.forEach(badge -> assertTranslated(badge.text()));

        List<PlanViewModel.Badge> failed = PlanViewModel.lastRunBadges(new LastRun(SHOT, PipelineStatus.ERROR, 1, 0, 2));
        assertEquals(PlanViewModel.BadgeKind.ERROR, failed.get(0).kind());
        assertTrue(failed.get(0).text().startsWith("✗ "), failed.get(0).text());
        assertEquals(PlanViewModel.BadgeKind.ERROR, failed.get(3).kind(), "errors: red");
        assertTrue(failed.get(3).text().startsWith("2 "), failed.get(3).text());

        List<PlanViewModel.Badge> stopped = PlanViewModel.lastRunBadges(new LastRun(SHOT, PipelineStatus.CANCELLED, 5, 0, 0));
        assertEquals(PlanViewModel.BadgeKind.NEUTRAL, stopped.get(0).kind());
        assertEquals(PlanViewModel.pipelineStatusText(PipelineStatus.CANCELLED), stopped.get(0).text());
        assertEquals(ITEM_DATE_SHOT, PlanViewModel.lastRunDate(new LastRun(SHOT, PipelineStatus.SUCCESS, 0, 0, 0)));
    }

    private static final String ITEM_DATE_SHOT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").format(SHOT.atZone(ZoneId.systemDefault()));

    @Test
    public void anUnknownHeaderValueIsShownRaw() {
        assertEquals("hand-edited", PlanViewModel.resumeModeText("hand-edited"), "never a %key label");
        assertEquals("NEW", PlanViewModel.pipelineStatusText(PipelineStatus.NEW), "never a %key label");
    }

    // ---- final fixes ----

    @Test
    public void anErrorWithoutMessageNamesItsClass() throws IOException {
        WorkItemExecution noMessage = item("a.jpg", 1);
        noMessage.setError(new IllegalStateException());
        WorkItemExecution blank = item("b.jpg", 1);
        blank.setError(new UnsupportedOperationException("  "));

        assertTrue(PlanViewModel.statusText(noMessage).contains("IllegalStateException"), PlanViewModel.statusText(noMessage));
        assertTrue(PlanViewModel.statusText(blank).contains("UnsupportedOperationException"), PlanViewModel.statusText(blank));

        PlanViewModel model = new PlanViewModel();
        model.startPreparing();
        PipelineState failed = state(PipelineStatus.ERROR);
        failed.setFailure(new NullPointerException());
        model.update(failed, List.of());
        assertTrue(model.statusLine().contains("NullPointerException"), model.statusLine());
    }

    @Test
    public void aSkipWithoutReasonIsAPlainSkipped() throws IOException {
        WorkItemExecution noReason = item("a.jpg", 1);
        noReason.setSkipped(null);
        WorkItemExecution blank = item("b.jpg", 1);
        blank.setSkipped(" ");
        String plain = ResourcesEngine.getString("item.status.SKIPPED.no-reason");

        assertTranslated(plain);
        assertEquals(plain, PlanViewModel.statusText(noReason));
        assertEquals(plain, PlanViewModel.statusText(blank));
        WorkItemExecution withReason = item("c.jpg", 1);
        withReason.setSkipped("already imported");
        assertNotEquals(plain, PlanViewModel.statusText(withReason));
    }

    @Test
    public void onceTheCopyStartedToCopyShowsOnlyWhatIsLeft() throws IOException {
        WorkItemExecution pending = item("a.jpg", 1);
        WorkItemExecution running = item("b.jpg", 1);
        WorkItemExecution done = item("c.jpg", 1);
        PlanViewModel model = prepared(false, pending, running, done);
        model.setFilter(Filter.TO_COPY);
        assertEquals(List.of(pending, running, done), model.visibleItems());

        model.startExecuting();
        running.setRunning(0);
        done.setDone();
        model.update(state(PipelineStatus.RUNNING, pending, running, done), List.of(pending, running, done));

        assertEquals(List.of(pending, running), model.visibleItems(), "a copied file is no longer to copy");
    }

    // ---- deferred review notes ----

    @Test
    public void withoutSizesTheProgressGoesByFiles() throws IOException {
        WorkItemExecution a = item("a.jpg", 0);
        WorkItemExecution b = item("b.jpg", 0);
        WorkItemExecution c = item("c.jpg", 0);
        WorkItemExecution d = item("d.jpg", 0);
        PlanViewModel model = prepared(false, a, b, c, d);
        model.startExecuting();

        a.setDone();
        model.update(state(PipelineStatus.RUNNING, a, b, c, d), List.of(a, b, c, d));

        assertEquals(new Progress(1, 4, 0, 0), model.progress());
        assertEquals(0.25, model.progress().fraction(), 1e-9);
        assertEquals(0, new Progress(0, 0, 0, 0).fraction(), "nothing selected: no division by zero");
    }

    @Test
    public void aForkedItemIsARowButNotInTheProgressTotals() throws IOException {
        WorkItemExecution a = item("a.jpg", 100);
        WorkItemExecution b = item("b.jpg", 100);
        PlanViewModel model = prepared(false, a, b);
        model.setFilter(Filter.TO_COPY);
        model.startExecuting();

        a.setDone();
        WorkItemExecution forkedPending = item("a-small.jpg", 10);
        WorkItemExecution forkedDone = item("a-thumb.jpg", 5);
        forkedDone.setDone();
        model.update(state(PipelineStatus.RUNNING, a, b, forkedPending, forkedDone), List.of(a, b));

        assertEquals(List.of(a, b, forkedPending, forkedDone), model.items());
        assertEquals(new Progress(1, 2, 100, 200), model.progress(), "the totals are those of the start");
        assertEquals(List.of(b, forkedPending), model.visibleItems(), "a pending forked item is left to copy");
        model.setFilter(Filter.ALL);
        assertEquals(4, model.visibleItems().size());
    }

    @Test
    public void theResumeLineFromANameAndFromTheDestination() throws IOException {
        PlanViewModel model = new PlanViewModel();
        model.startPreparing();
        PipelineState state = state(PipelineStatus.PREPARED, item("DSC_4822.JPG", 1));
        ItemKey key = new ItemKey(SHOT, "DSC_4821.JPG");
        state.setResumeProposal(proposal(ResumePoint.from(key), ResumeSource.DESTINATION));
        model.update(state, List.of());

        String from = model.resumeText().orElseThrow();
        String destination = ResourcesEngine.getString("plan.resume.source.DESTINATION");
        String date = DateTimeFormatter.ofPattern("dd/MM HH:mm").format(SHOT.atZone(ZoneId.systemDefault()));
        assertTranslated(destination);
        assertEquals(ResourcesEngine.getString("plan.resume.from", "DSC_4821.JPG", date, destination), from);

        model.setOverride(ResumePoint.after(key));
        assertNotEquals(from, model.resumeText().orElseThrow(), "from and after are told apart");
        for (ResumeSource source : ResumeSource.values()) {
            assertTranslated(ResourcesEngine.getString("plan.resume.source." + source.name()));
        }
    }

    @Test
    public void sizesAreShownInTheLocale() throws IOException {
        Locale previous = Locale.getDefault();
        try {
            ResourcesEngine.loadLanguage(Locale.FRENCH);
            WorkItemExecution big = item("big.jpg", 300_000);
            PlanViewModel model = prepared(false, big);

            assertTrue(PlanViewModel.sizeText(big).startsWith("292,97 "), PlanViewModel.sizeText(big));
            assertTrue(model.copyLabel().contains("293,0 "), model.copyLabel());
            assertEquals("?", PlanViewModel.sizeText(new WorkItemExecution(new WorkItem(tempDir.resolve("nosize.jpg")), List.of())));
        } finally {
            ResourcesEngine.loadLanguage(previous);
        }
    }

    @Test
    public void theTargetTextShowsTheFirstDirectoryAndHowManyMore() {
        TargetProjection two = new TargetProjection.Targets(List.of(Path.of("/nas/2026"), Path.of("/nas/thumbs")));

        assertEquals(Path.of("/nas/2026") + " " + ResourcesEngine.getString("plan.target.more", 1), PlanViewModel.targetText(two));
        assertEquals(Path.of("/nas/2026") + "\n" + Path.of("/nas/thumbs"), PlanViewModel.targetTooltip(two));
    }

    @Test
    public void theTargetTextExplainsWhyThereIsNoTarget() {
        assertEquals(ResourcesEngine.getString("dryrun.filtered", "drop"), PlanViewModel.targetText(new TargetProjection.Filtered("drop")));
        assertEquals(ResourcesEngine.getString("dryrun.unsupported", "legacy"), PlanViewModel.targetText(new TargetProjection.Unknown("legacy")));
        assertEquals("No value for {x}: a", PlanViewModel.targetText(new TargetProjection.Failed("No value for {x}: a")));
        assertEquals("", PlanViewModel.targetText(TargetProjection.NONE));
        assertNull(PlanViewModel.targetTooltip(TargetProjection.NONE));
    }

    @Test
    public void theDetailShowsTheSourceTheStatusEachStepAndEveryTarget() throws IOException {
        WorkItemExecution item = item("DSC_1.NEF", 2048);
        ItemDetail detail = new ItemDetail(List.of(
                new Projection.Step("convert", List.of("DSC_1.NEF", "DSC_1.jpg")),
                new Projection.Step("rename", List.of("a.nef", "a.jpg"))),
                new TargetProjection.Targets(List.of(Path.of("/nas"))), List.of(Path.of("/nas/a.nef"), Path.of("/nas/a.jpg")));

        List<String> lines = PlanViewModel.detailText(item, detail).lines().toList();

        assertEquals("📂 DSC_1.NEF", lines.get(0));
        assertTrue(lines.get(1).contains(tempDir.toString()), lines.get(1));
        assertTrue(lines.get(1).contains(PlanViewModel.sizeText(item)), lines.get(1));
        assertTrue(lines.get(1).contains(PlanViewModel.dateText(item)), lines.get(1));
        assertEquals(ResourcesEngine.getString("plan.detail.status", PlanViewModel.statusText(item)), lines.get(2));
        assertEquals(List.of(
                "⚙ convert → DSC_1.NEF, DSC_1.jpg",
                "⚙ rename → a.nef, a.jpg",
                "💾 " + Path.of("/nas/a.nef"),
                "   " + Path.of("/nas/a.jpg")), lines.subList(4, lines.size()));
    }

    @Test
    public void theDetailOfAFileSkippedByTheResumePointGivesItsReason() throws IOException {
        WorkItemExecution item = item("DSC_1.NEF", 2048);
        item.setSkippedByResumePoint("Already imported (cursor: DSC_1.NEF)");
        ItemDetail detail = new ItemDetail(List.of(), TargetProjection.NONE, List.of());

        String status = PlanViewModel.detailText(item, detail).lines().toList().get(2);

        assertEquals(ResourcesEngine.getString("plan.detail.status",
                ResourcesEngine.getString("item.status.SKIPPED", "Already imported (cursor: DSC_1.NEF)")), status,
                "the column only says Skipped, the detail tells why");
    }

    @Test
    public void theDetailEndsOnTheStepThatStoppedTheDryRun() throws IOException {
        WorkItemExecution item = item("DSC_1.NEF", 2048);
        List<Projection.Step> converted = List.of(new Projection.Step("convert", List.of("DSC_1.jpg")));

        assertEquals("⚙ drop → " + ResourcesEngine.getString("plan.detail.filtered"),
                lastLine(item, new ItemDetail(converted, new TargetProjection.Filtered("drop"), List.of())));
        assertEquals("⚙ legacy → " + ResourcesEngine.getString("plan.detail.unsupported"),
                lastLine(item, new ItemDetail(converted, new TargetProjection.Unknown("legacy"), List.of())));
        assertEquals("⚠ no codec", lastLine(item, new ItemDetail(converted, new TargetProjection.Failed("no codec"), List.of())));
        assertEquals("💾 " + ResourcesEngine.getString("plan.detail.no-target"),
                lastLine(item, new ItemDetail(converted, TargetProjection.NONE, List.of())));
    }

    private static String lastLine(WorkItemExecution item, ItemDetail detail) {
        return PlanViewModel.detailText(item, detail).lines().toList().getLast();
    }

    @Test
    public void aSingleTargetHasNoTooltip() {
        assertEquals(Path.of("/nas/2026").toString(), PlanViewModel.targetText(new TargetProjection.Targets(List.of(Path.of("/nas/2026")))));
        assertNull(PlanViewModel.targetTooltip(new TargetProjection.Targets(List.of(Path.of("/nas/2026")))));
    }
}

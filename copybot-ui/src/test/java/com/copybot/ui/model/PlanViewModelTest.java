package com.copybot.ui.model;

import com.copybot.engine.Plan;
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

    /** A model with a prepared plan of these items (resume: everything). */
    private static PlanViewModel prepared(boolean autoExecute, WorkItemExecution... items) {
        PlanViewModel model = new PlanViewModel(autoExecute);
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
        PlanViewModel model = new PlanViewModel(false);

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
        PlanViewModel model = new PlanViewModel(false);
        WorkItemExecution a = item("a.jpg", 1);

        model.startPreparing();
        model.update(state(PipelineStatus.RUNNING, a), List.of());

        assertEquals(Phase.PREPARING, model.phase());
        assertEquals(List.of(a), model.items(), "the table fills during the listing");
        assertFalse(model.canGoBack());
        assertFalse(model.canPrepare());
        assertFalse(model.canStop(), "a preparation is not stopped from the view");
        assertTrue(model.isActive());
        assertTrue(model.statusLine().contains("1"), model.statusLine());
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
        PlanViewModel model = new PlanViewModel(false);
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
        PlanViewModel model = new PlanViewModel(false);
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
    public void theDateColumnShowsTheResumeKeyOrTheFileDate() throws IOException {
        WorkItemExecution item = item("a.jpg", 1);
        String local = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").format(SHOT.atZone(ZoneId.systemDefault()));

        assertEquals(local, PlanViewModel.dateText(item), "before the preparation: the modification date");
        item.setResumeKey(new ItemKey(SHOT.plusSeconds(3600), "a.jpg"));
        assertNotEquals(local, PlanViewModel.dateText(item), "then the frozen resume key");
    }

    @Test
    public void theWarningsAreTheConfigurationOnesThenTheResumeOnes() throws IOException {
        PlanViewModel model = new PlanViewModel(false);
        model.startPreparing();
        PipelineState state = state(PipelineStatus.PREPARED, item("a.jpg", 1));
        state.setWarnings(List.of("sources deleted"));
        state.setResumeProposal(proposal(ResumePoint.all(), ResumeSource.NONE, "no target paths"));

        model.update(state, List.of());

        assertEquals(List.of("sources deleted", "no target paths"), model.warnings());
    }

    @Test
    public void theResumeLineNamesThePointAndItsOrigin() throws IOException {
        PlanViewModel model = new PlanViewModel(false);
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

        PlanViewModel unchecked = new PlanViewModel(true);
        unchecked.setAutoExecute(false);
        unchecked.startPreparing();
        unchecked.update(state(PipelineStatus.PREPARED, item("b.jpg", 1)), List.of());
        assertFalse(unchecked.consumeAutoExecute());
    }

    @Test
    public void noAutomaticExecutionOfAFailedOrEmptyPlan() throws IOException {
        PlanViewModel failed = new PlanViewModel(true);
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

        PlanViewModel model = new PlanViewModel(false);
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
        PlanViewModel model = new PlanViewModel(false);
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
}

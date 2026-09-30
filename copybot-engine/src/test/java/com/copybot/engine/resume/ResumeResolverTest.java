package com.copybot.engine.resume;

import com.copybot.engine.pipeline.ItemStatus;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.IOutAction;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import com.copybot.plugin.api.action.WorkStatus;
import com.copybot.plugin.api.definition.IPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

public class ResumeResolverTest {

    @TempDir
    Path tempDir;

    /** Out action writing to nas/<day>/<name>, day taken from the key date. */
    final class DayOut implements IOutAction {
        @Override
        public void writeItem(WorkItem workItem) {
        }

        @Override
        public Optional<Path> resolveTarget(WorkItem workItem) {
            ItemKey key = ItemKey.of(workItem).orElseThrow();
            return Optional.of(tempDir.resolve("nas").resolve(key.date().toString().substring(0, 10)).resolve(key.name()));
        }

        @Override
        public void setStatusWatcher(Consumer<WorkStatus> watcher) {
        }

        @Override
        public void setPlugin(IPlugin plugin) {
        }
    }

    private WorkItemExecution item(String name, String instant) throws IOException {
        WorkItem wi = new WorkItem(Files.createFile(tempDir.resolve(name)));
        if (instant != null) {
            wi.getMetadatas().setTime(WorkItemMetadata.LAST_MODIFIED, Instant.parse(instant));
        }
        return new WorkItemExecution(wi, List.of());
    }

    private List<WorkItemExecution> card() throws IOException {
        List<WorkItemExecution> items = new ArrayList<>();
        items.add(item("C.JPG", "2026-09-03T10:00:00Z"));
        items.add(item("A.JPG", "2026-09-01T10:00:00Z"));
        items.add(item("B.JPG", "2026-09-02T10:00:00Z"));
        return ResumeResolver.order(items);
    }

    private static ItemKey key(WorkItemExecution w) {
        return ItemKey.of(w.getWorkItem()).orElseThrow();
    }

    private ResumeStateStore store() {
        return new ResumeStateStore(tempDir.resolve("p.state.json"));
    }

    @Test
    public void orderSortsByKeyAndPutsKeylessItemsLast() throws IOException {
        List<WorkItemExecution> items = List.of(item("nodate.bin", null), item("B.JPG", "2026-09-02T10:00:00Z"),
                item("A.JPG", "2026-09-01T10:00:00Z"));
        List<String> names = ResumeResolver.order(items).stream().map(w -> w.getWorkItem().getNameDisplay()).toList();
        assertEquals(List.of("A.JPG", "B.JPG", "nodate.bin"), names);
    }

    @Test
    public void noneSelectsEverything() throws IOException {
        List<WorkItemExecution> ordered = card();
        ResumeProposal p = new ResumeResolver(ResumeMode.NONE, null, null).propose(ordered);
        assertEquals(ResumePoint.all(), p.point());
        assertEquals(ResumeSource.NONE, p.source());
    }

    @Test
    public void stateResumesAfterTheCursorAndSkipsWithAReason() throws IOException {
        List<WorkItemExecution> ordered = card();
        store().writeCursor(key(ordered.get(0)));
        ResumeResolver resolver = new ResumeResolver(ResumeMode.STATE, store(), null);

        ResumeProposal p = resolver.propose(ordered);
        resolver.apply(p.point(), p.source(), ordered);

        assertEquals(ResumePoint.after(key(ordered.get(0))), p.point());
        assertEquals(ResumeSource.STATE, p.source());
        assertEquals(ItemStatus.SKIPPED, ordered.get(0).getStatus());
        assertTrue(ordered.get(0).getSkipReason().contains("A.JPG"), ordered.get(0).getSkipReason());
        assertEquals(ItemStatus.PENDING, ordered.get(1).getStatus());
        assertEquals(ItemStatus.PENDING, ordered.get(2).getStatus());
    }

    @Test
    public void stateWithoutFileSelectsEverything() throws IOException {
        ResumeProposal p = new ResumeResolver(ResumeMode.STATE, store(), null).propose(card());
        assertEquals(ResumePoint.all(), p.point());
        assertEquals(ResumeSource.NONE, p.source());
    }

    @Test
    public void destinationResumesAfterTheLastExistingDay() throws IOException {
        List<WorkItemExecution> ordered = card();
        Files.createDirectories(tempDir.resolve("nas").resolve("2026-09-01"));
        Files.createDirectories(tempDir.resolve("nas").resolve("2026-09-02"));

        ResumeProposal p = new ResumeResolver(ResumeMode.DESTINATION, store(), new DayOut()).propose(ordered);

        assertEquals(ResumePoint.after(key(ordered.get(1))), p.point());
        assertEquals(ResumeSource.DESTINATION, p.source());
    }

    @Test
    public void explicitDestinationWithoutTargetResolutionFails() throws IOException {
        List<WorkItemExecution> ordered = card();
        assertThrows(CopybotException.class,
                () -> new ResumeResolver(ResumeMode.DESTINATION, store(), null).propose(ordered));
    }

    @Test
    public void stateThenDestinationFallsBackOnDestinationThenOnEverythingWithAWarning() throws IOException {
        List<WorkItemExecution> ordered = card();
        Files.createDirectories(tempDir.resolve("nas").resolve("2026-09-01"));

        ResumeProposal viaDestination = new ResumeResolver(ResumeMode.STATE_THEN_DESTINATION, store(), new DayOut()).propose(ordered);
        assertEquals(ResumeSource.DESTINATION, viaDestination.source());

        ResumeProposal noTarget = new ResumeResolver(ResumeMode.STATE_THEN_DESTINATION, store(), null).propose(ordered);
        assertEquals(ResumePoint.all(), noTarget.point());
        assertEquals(1, noTarget.warnings().size());

        store().writeCursor(key(ordered.get(1)));
        ResumeProposal viaState = new ResumeResolver(ResumeMode.STATE_THEN_DESTINATION, store(), new DayOut()).propose(ordered);
        assertEquals(ResumeSource.STATE, viaState.source());
        assertEquals(ResumePoint.after(key(ordered.get(1))), viaState.point());
    }

    @Test
    public void itemWithoutDateBecomesAnError() throws IOException {
        List<WorkItemExecution> ordered = ResumeResolver.order(List.of(item("nodate.bin", null)));
        new ResumeResolver(ResumeMode.NONE, null, null).apply(ResumePoint.all(), ResumeSource.NONE, ordered);
        assertEquals(ItemStatus.ERROR, ordered.get(0).getStatus());
        assertNotNull(ordered.get(0).getError());
    }

    @Test
    public void manualOverrideRecomputesSkippedItems() throws IOException {
        List<WorkItemExecution> ordered = card();
        store().writeCursor(key(ordered.get(2)));
        ResumeResolver resolver = new ResumeResolver(ResumeMode.STATE, store(), null);
        ResumeProposal p = resolver.propose(ordered);
        resolver.apply(p.point(), p.source(), ordered);
        assertTrue(ordered.stream().allMatch(w -> w.getStatus() == ItemStatus.SKIPPED));

        resolver.apply(ResumePoint.from(key(ordered.get(1))), ResumeSource.MANUAL, ordered);

        assertEquals(ItemStatus.SKIPPED, ordered.get(0).getStatus());
        assertEquals(ItemStatus.PENDING, ordered.get(1).getStatus());
        assertEquals(ItemStatus.PENDING, ordered.get(2).getStatus());
    }

    @Test
    public void manualDateWithoutFileNameGivesADateOnlySkipReason() throws IOException {
        List<WorkItemExecution> ordered = card();
        ResumeResolver resolver = new ResumeResolver(ResumeMode.STATE, store(), null);
        resolver.propose(ordered);

        resolver.apply(ResumePoint.from(new ItemKey(Instant.parse("2026-09-02T00:00:00Z"), "")), ResumeSource.MANUAL, ordered);

        assertEquals(ItemStatus.SKIPPED, ordered.get(0).getStatus());
        String reason = ordered.get(0).getSkipReason();
        assertFalse(reason.contains("(,"), "no empty file name in the reason: " + reason);
        assertTrue(reason.contains("2026"), reason);
    }

    @Test
    public void cursorAdvancesToTheLastItemWhenEverythingSucceeded() throws IOException {
        List<WorkItemExecution> ordered = card();
        ResumeResolver resolver = new ResumeResolver(ResumeMode.STATE, store(), null);
        ResumeProposal p = resolver.propose(ordered);
        ordered.forEach(WorkItemExecution::setDone);

        assertEquals(Optional.of(key(ordered.get(2))), resolver.nextCursor(ordered, p.point(), p.source()));
    }

    @Test
    public void cursorStopsBeforeTheFirstFailure() throws IOException {
        List<WorkItemExecution> ordered = card();
        ResumeResolver resolver = new ResumeResolver(ResumeMode.STATE, store(), null);
        ResumeProposal p = resolver.propose(ordered);
        ordered.get(0).setDone();
        ordered.get(1).setError(new IllegalStateException("NAS gone"));
        ordered.get(2).setDone();

        assertEquals(Optional.of(key(ordered.get(0))), resolver.nextCursor(ordered, p.point(), p.source()));
    }

    @Test
    public void cursorStopsBeforeAnItemWhoseForkFailed() throws IOException {
        List<WorkItemExecution> ordered = card();
        ResumeResolver resolver = new ResumeResolver(ResumeMode.STATE, store(), null);
        ResumeProposal p = resolver.propose(ordered);
        ordered.forEach(WorkItemExecution::setDone);
        ordered.get(1).markForkFailed();

        assertEquals(Optional.of(key(ordered.get(0))), resolver.nextCursor(ordered, p.point(), p.source()));
    }

    @Test
    public void noSuccessKeepsTheCursorUnchanged() throws IOException {
        List<WorkItemExecution> ordered = card();
        store().writeCursor(key(ordered.get(0)));
        ResumeResolver resolver = new ResumeResolver(ResumeMode.STATE, store(), null);
        ResumeProposal p = resolver.propose(ordered);
        ordered.get(1).setError(new IllegalStateException("boom"));

        assertTrue(resolver.nextCursor(ordered, p.point(), p.source()).isEmpty(), "nothing new to write");
    }

    @Test
    public void manualReimportOfOldFilesDoesNotMoveTheCursorBack() throws IOException {
        List<WorkItemExecution> ordered = card();
        store().writeCursor(key(ordered.get(2)));
        ResumeResolver resolver = new ResumeResolver(ResumeMode.STATE, store(), null);
        resolver.propose(ordered);
        ResumePoint manual = ResumePoint.from(key(ordered.get(0)));
        resolver.apply(manual, ResumeSource.MANUAL, ordered);
        ordered.get(0).setDone();
        ordered.get(1).setDone();
        ordered.get(2).setDone();

        assertTrue(resolver.nextCursor(ordered, manual, ResumeSource.MANUAL).isEmpty());
    }

    @Test
    public void destinationPointIsPersistedEvenWhenNothingNewWasCopied() throws IOException {
        List<WorkItemExecution> ordered = card();
        Files.createDirectories(tempDir.resolve("nas").resolve("2026-09-01"));
        Files.createDirectories(tempDir.resolve("nas").resolve("2026-09-02"));
        Files.createDirectories(tempDir.resolve("nas").resolve("2026-09-03"));
        ResumeResolver resolver = new ResumeResolver(ResumeMode.DESTINATION, store(), new DayOut());
        ResumeProposal p = resolver.propose(ordered);

        assertEquals(Optional.of(key(ordered.get(2))), resolver.nextCursor(ordered, p.point(), p.source()));
    }

    @Test
    public void anItemSkippedDuringTheExecutionCountsAsASuccess() throws IOException {
        List<WorkItemExecution> ordered = card();
        ResumeResolver resolver = new ResumeResolver(ResumeMode.STATE, store(), null);
        ResumeProposal p = resolver.propose(ordered);
        ordered.get(0).setDone();
        ordered.get(1).setSkipped("identical to the destination"); // selected, then skipped by the out step
        ordered.get(2).setDone();

        assertEquals(Optional.of(key(ordered.get(2))), resolver.nextCursor(ordered, p.point(), p.source()));
    }

    @Test
    public void anItemSkippedDuringTheExecutionWithAFailedForkHoldsTheCursor() throws IOException {
        List<WorkItemExecution> ordered = card();
        ResumeResolver resolver = new ResumeResolver(ResumeMode.STATE, store(), null);
        ResumeProposal p = resolver.propose(ordered);
        ordered.get(0).setDone();
        ordered.get(1).setSkipped("identical to the destination");
        ordered.get(1).markForkFailed();
        ordered.get(2).setDone();

        assertEquals(Optional.of(key(ordered.get(0))), resolver.nextCursor(ordered, p.point(), p.source()));
    }
}

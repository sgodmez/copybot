package com.copybot.engine;

import com.copybot.engine.pipeline.ItemStatus;
import com.copybot.engine.pipeline.PipelineStatus;
import com.copybot.engine.pipeline.PipelineStep;
import com.copybot.engine.pipeline.PipelineStepConfig;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.engine.resources.ResourceRegistry;
import com.copybot.engine.resume.ResumeContext;
import com.copybot.engine.resume.ResumeMode;
import com.copybot.engine.resume.ResumeStateStore;
import com.copybot.plugin.api.action.IInAction;
import com.copybot.plugin.api.action.IOutAction;
import com.copybot.plugin.api.action.IProcessAction;
import com.copybot.plugin.api.action.WorkItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static com.copybot.engine.ControlFakes.*;
import static org.junit.jupiter.api.Assertions.*;

/** cancel() / pause() / resume() of a MainExecutor (spec engine-instance §3 and §4). */
public class MainExecutorControlTest {

    @TempDir
    Path tempDir;

    private ResumeStateStore store() {
        return new ResumeStateStore(tempDir.resolve("p.state.json"));
    }

    @Test
    public void pauseStopsNewStepsFromStartingAndResumeReleasesThem() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        GatedOut out = new GatedOut(Set.of("proc"), gate);
        ResourceRegistry reg = registry(Map.of("proc", 1, "disk:*", 1000));
        MainExecutor exec = singlePhase(new DatedIn(tempDir, 3, null), out, reg);
        Thread runner = Thread.ofVirtual().start(exec);
        assertTrue(out.firstEntered.await(5, TimeUnit.SECONDS), "a first step is running");

        exec.pause();
        assertEquals(PipelineStatus.PAUSED, exec.getState().getStatus());
        gate.countDown(); // the running step ends and releases "proc" normally
        awaitTrue(() -> used(reg, "proc") == 0);
        Thread.sleep(200);
        assertEquals(1, out.started.get(), "no new step starts while paused");

        exec.resume();
        runner.join(TimeUnit.SECONDS.toMillis(10));
        assertFalse(runner.isAlive());
        assertEquals(3, out.started.get(), "resume lets the waiting steps start");
        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus(), "a pause has no effect on the final status");
    }

    @Test
    public void resumeGoesBackToRunning() throws Exception {
        GatedOut out = new GatedOut(Set.of(), new CountDownLatch(1)); // never opened: the run cannot end by itself
        MainExecutor exec = singlePhase(new DatedIn(tempDir, 1, null), out, registry(Map.of("disk:*", 1000)));
        Thread runner = Thread.ofVirtual().start(exec);
        assertTrue(out.firstEntered.await(5, TimeUnit.SECONDS));

        exec.pause();
        assertEquals(PipelineStatus.PAUSED, exec.getState().getStatus());
        exec.resume();
        assertEquals(PipelineStatus.RUNNING, exec.getState().getStatus());

        exec.cancel();
        runner.join(TimeUnit.SECONDS.toMillis(20));
        assertFalse(runner.isAlive());
        assertEquals(PipelineStatus.CANCELLED, exec.getState().getStatus());
    }

    @Test
    public void pauseAlsoHoldsTheListingBeforeItEmitsTheNextItem() throws Exception {
        CountDownLatch firstEmitted = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        MainExecutor exec = singlePhase(new DatedIn(tempDir, 3, () -> {
            firstEmitted.countDown();
            awaitQuietly(proceed);
        }), new GatedOut(Set.of(), null), registry(Map.of("disk:*", 1000)));
        Thread runner = Thread.ofVirtual().start(exec);
        assertTrue(firstEmitted.await(5, TimeUnit.SECONDS));

        exec.pause();
        proceed.countDown(); // the listing goes on, but must wait before creating the next item
        Thread.sleep(200);
        assertEquals(1, exec.getState().getWorkItems().size(), "no item is emitted while paused");

        exec.resume();
        runner.join(TimeUnit.SECONDS.toMillis(10));
        assertFalse(runner.isAlive());
        assertEquals(3, exec.getState().getWorkItems().size());
        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
    }

    @Test
    public void cancelDuringThePreparationEndsCancelledWithoutLeakingTheInterrupt() throws Exception {
        BlockingIn listing = new BlockingIn();
        ResourceRegistry reg = registry(Map.of("disk:*", 1000));
        MainExecutor exec = withResume(listing, new GatedOut(Set.of(), null), reg, store());
        AtomicBoolean callerInterrupted = new AtomicBoolean(true);
        Thread caller = Thread.ofVirtual().start(() -> {
            exec.prepare(); // prepare() runs in the caller's thread
            callerInterrupted.set(Thread.currentThread().isInterrupted());
        });
        assertTrue(listing.started.await(5, TimeUnit.SECONDS));

        exec.cancel();
        caller.join(TimeUnit.SECONDS.toMillis(20));

        assertFalse(caller.isAlive());
        assertEquals(PipelineStatus.CANCELLED, exec.getState().getStatus());
        assertFalse(callerInterrupted.get(), "the interrupt used by cancel() must not leak to the caller's thread");
        assertTrue(allReleased(reg), "cancellation releases every permit, got " + reg.snapshot());
        assertFalse(Files.exists(store().getPath()), "a cancelled preparation writes no state");
    }

    @Test
    public void cancelDuringTheExecutionEndsCancelledAndKeepsTheCursor() throws Exception {
        store().writeCursor(day(1));
        byte[] before = Files.readAllBytes(store().getPath());
        GatedOut out = new GatedOut(Set.of(), new CountDownLatch(1));
        ResourceRegistry reg = registry(Map.of("disk:*", 1000));
        MainExecutor exec = withResume(new DatedIn(tempDir, 3, null), out, reg, store());
        exec.prepare();
        assertEquals(PipelineStatus.PREPARED, exec.getState().getStatus());
        Thread runner = Thread.ofVirtual().start(() -> exec.execute(null));
        assertTrue(out.firstEntered.await(5, TimeUnit.SECONDS));

        exec.cancel();
        runner.join(TimeUnit.SECONDS.toMillis(20));

        assertFalse(runner.isAlive());
        assertEquals(PipelineStatus.CANCELLED, exec.getState().getStatus());
        assertTrue(allReleased(reg), "cancellation releases every permit, got " + reg.snapshot());
        assertArrayEquals(before, Files.readAllBytes(store().getPath()), "a cancelled run never moves the cursor");
    }

    @Test
    public void cancelDuringAPauseLiftsThePauseThenCancels() throws Exception {
        GatedOut out = new GatedOut(Set.of(), new CountDownLatch(1));
        ResourceRegistry reg = registry(Map.of("disk:*", 1000));
        MainExecutor exec = singlePhase(new DatedIn(tempDir, 3, null), out, reg);
        Thread runner = Thread.ofVirtual().start(exec);
        assertTrue(out.firstEntered.await(5, TimeUnit.SECONDS));
        exec.pause();

        exec.cancel();
        runner.join(TimeUnit.SECONDS.toMillis(20));

        assertFalse(runner.isAlive());
        assertEquals(PipelineStatus.CANCELLED, exec.getState().getStatus());
        assertFalse(reg.isPaused(), "cancel lifts the pause");
        assertTrue(allReleased(reg), "got " + reg.snapshot());
    }

    @Test
    public void cancellingAPausedExecutionStartsNoNewStep() throws Exception {
        for (int round = 0; round < 10; round++) {
            Path dir = Files.createDirectory(tempDir.resolve("round" + round));
            GatedOut out = new GatedOut(Set.of("proc"), null); // never blocks: a granted step runs at once
            ResourceRegistry reg = registry(Map.of("proc", 100, "disk:*", 1000)); // free capacity for everyone
            MainExecutor exec = withResume(new DatedIn(dir, 6, null), out, reg,
                    new ResumeStateStore(dir.resolve("p.state.json")));
            exec.prepare();
            assertEquals(PipelineStatus.PREPARED, exec.getState().getStatus(), "round " + round);
            exec.pause();
            Thread runner = Thread.ofVirtual().start(() -> exec.execute(null));
            // every selected item waits for "proc", held back by the pause alone
            awaitTrue(() -> reg.snapshot().stream().anyMatch(s -> s.name().equals("proc") && s.waiting() == 6));
            int startedBeforeCancel = out.started.get();

            exec.cancel(); // lifts the pause: the waiters are granted, but must not start their step
            runner.join(TimeUnit.SECONDS.toMillis(20));

            assertFalse(runner.isAlive(), "round " + round);
            assertEquals(0, startedBeforeCancel, "round " + round);
            assertEquals(startedBeforeCancel, out.started.get(), "round " + round + ": no step starts after the cancel");
            assertEquals(PipelineStatus.CANCELLED, exec.getState().getStatus(), "round " + round);
            for (WorkItemExecution item : exec.getState().getWorkItems()) {
                assertEquals(ItemStatus.PENDING, item.getStatus(),
                        "round " + round + ": an item interrupted by the cancel is not left in progress");
            }
            assertTrue(allReleased(reg), "round " + round + ": got " + reg.snapshot());
            assertFalse(reg.isPaused(), "round " + round);
        }
    }

    @Test
    public void cancelBeforeTheStartEndsCancelledWithoutRunningAnything() {
        GatedOut out = new GatedOut(Set.of(), null);
        MainExecutor exec = singlePhase(new DatedIn(tempDir, 2, null), out, registry(Map.of("disk:*", 1000)));

        exec.cancel();
        exec.run();

        assertEquals(PipelineStatus.CANCELLED, exec.getState().getStatus());
        assertEquals(0, exec.getState().getWorkItems().size(), "nothing is listed");
        assertEquals(0, out.started.get());
        assertFalse(Thread.interrupted(), "the calling thread is not left interrupted");
    }

    @Test
    public void cancelAndPauseHaveNoEffectOnceTheRunIsOver() {
        ResourceRegistry reg = registry(Map.of("disk:*", 1000));
        MainExecutor exec = singlePhase(new DatedIn(tempDir, 2, null), new GatedOut(Set.of(), null), reg);
        exec.run();
        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());

        exec.cancel();
        exec.pause();
        exec.cancel();

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
        assertFalse(reg.isPaused());
    }

    @Test
    public void aListingThrowingItsOwnCancellationExceptionIsAFailureNotACancel() {
        CancellationException own = new CancellationException("plugin's own");
        MainExecutor exec = singlePhase(new ThrowingIn(own), new GatedOut(Set.of(), null), registry(Map.of("disk:*", 1000)));

        exec.run();

        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus(), "a partial listing never looks successful");
        assertSame(own, exec.getState().getFailure());
    }

    @Test
    public void aCancelRacingWithTheEndOfTheExecutionNeverCorruptsTheOutcome() throws Exception {
        for (int round = 0; round < 40; round++) {
            Path dir = Files.createDirectory(tempDir.resolve("round" + round));
            ResumeStateStore store = new ResumeStateStore(dir.resolve("p.state.json"));
            store.writeCursor(day(1));
            byte[] before = Files.readAllBytes(store.getPath());
            ResourceRegistry reg = registry(Map.of("disk:*", 1000));
            MainExecutor exec = withResume(new DatedIn(dir, 3, null), new GatedOut(Set.of(), null), reg, store);
            exec.prepare();
            assertEquals(PipelineStatus.PREPARED, exec.getState().getStatus());
            Thread runner = Thread.ofVirtual().start(() -> exec.execute(null));
            Thread.sleep(0, (round % 8) * 50_000); // lands the cancel at different points of the run
            exec.cancel();
            runner.join(TimeUnit.SECONDS.toMillis(20));

            assertFalse(runner.isAlive());
            PipelineStatus status = exec.getState().getStatus();
            byte[] after = Files.readAllBytes(store.getPath());
            if (status == PipelineStatus.CANCELLED) {
                assertArrayEquals(before, after, "round " + round + ": a cancelled run never moves the cursor");
            } else {
                assertEquals(PipelineStatus.SUCCESS, status, "round " + round + ": a late cancel is ignored, got "
                        + status + " / " + exec.getState().getFailure());
                assertEquals(Optional.of(day(3)), store.readCursor(),
                        "round " + round + ": a completed run writes its whole cursor");
            }
            assertNull(exec.getState().getFailure(), "round " + round);
            assertTrue(allReleased(reg), "round " + round + ": got " + reg.snapshot());
            assertFalse(reg.isPaused());
        }
    }

    @Test
    public void cancelWhileTheListingWaitsOutAPauseRecordsNoListingFailure() throws Exception {
        for (int round = 0; round < 10; round++) {
            Path dir = Files.createDirectory(tempDir.resolve("round" + round));
            CountDownLatch firstEmitted = new CountDownLatch(1);
            CountDownLatch proceed = new CountDownLatch(1);
            AtomicReference<Thread> listingThread = new AtomicReference<>();
            ResourceRegistry reg = registry(Map.of("disk:*", 1000));
            MainExecutor exec = withResume(new ManyIn(dir, 2000, () -> {
                listingThread.set(Thread.currentThread());
                firstEmitted.countDown();
                awaitQuietly(proceed);
            }), new GatedOut(Set.of(), null), reg, new ResumeStateStore(dir.resolve("p.state.json")));
            Thread caller = Thread.ofVirtual().start(exec::prepare);
            assertTrue(firstEmitted.await(5, TimeUnit.SECONDS), "round " + round);
            exec.pause();
            proceed.countDown();
            awaitTrue(() -> listingThread.get().getState() == Thread.State.WAITING); // parked in awaitNotPaused

            exec.cancel(); // lifts the pause: the listing wakes up while the phase shuts its executor down
            caller.join(TimeUnit.SECONDS.toMillis(20));

            assertFalse(caller.isAlive(), "round " + round);
            assertEquals(PipelineStatus.CANCELLED, exec.getState().getStatus(), "round " + round);
            assertNull(exec.getState().getFailure(), "round " + round + ": a cancelled listing is not a failure");
            assertTrue(allReleased(reg), "round " + round + ": got " + reg.snapshot());
        }
    }

    @Test
    public void aFailureRacingWithACancelEndsCancelledNotInError() throws Exception {
        AtomicReference<MainExecutor> holder = new AtomicReference<>();
        IOutAction out = new CancellingTargetOut(() -> holder.get().cancel());
        MainExecutor exec = new MainExecutor(
                List.of(new PipelineStep<>(null, new DatedIn(tempDir, 2, null), emptyConfig())),
                List.of(new PipelineStep<>(null, new NoopAnalyze(), emptyConfig()), new PipelineStep<>(null, out, emptyConfig())),
                1, false, null, registry(Map.of("disk:*", 1000)), new ResumeContext(ResumeMode.DESTINATION, store()));
        holder.set(exec);

        assertDoesNotThrow(exec::prepare);

        assertEquals(PipelineStatus.CANCELLED, exec.getState().getStatus());
        assertInstanceOf(IllegalStateException.class, exec.getState().getFailure(), "the cause is kept");
        assertFalse(Thread.interrupted(), "the calling thread is not left interrupted");
    }

    @Test
    public void aFailureRacingWithACancelEndsASinglePhaseRunCancelled() {
        AtomicReference<MainExecutor> holder = new AtomicReference<>();
        PipelineStep<IOutAction> failingStep = new PipelineStep<>(null, new GatedOut(Set.of(), null), emptyConfig()) {
            @Override
            public PipelineStepConfig getConfig() {
                holder.get().cancel();
                throw new IllegalStateException("caused by the cancel");
            }
        };
        MainExecutor exec = new MainExecutor(
                List.of(new PipelineStep<>(null, new DatedIn(tempDir, 2, null), emptyConfig())),
                List.of(failingStep), true, null, registry(Map.of("disk:*", 1000)));
        holder.set(exec);

        assertDoesNotThrow(exec::run);

        assertEquals(PipelineStatus.CANCELLED, exec.getState().getStatus());
        assertInstanceOf(IllegalStateException.class, exec.getState().getFailure(), "the cause is kept");
        assertFalse(Thread.interrupted(), "the calling thread is not left interrupted");
    }

    @Test
    public void aForkRejectedBecauseTheCancelShutTheTasksDownIsNotAnItemError() throws Exception {
        ForkingOnInterruptProcess process = new ForkingOnInterruptProcess(tempDir);
        ResourceRegistry reg = registry(Map.of("disk:*", 1000));
        MainExecutor exec = new MainExecutor(
                List.of(new PipelineStep<>(null, new DatedIn(tempDir, 1, null), emptyConfig())),
                List.of(new PipelineStep<>(null, process, emptyConfig()),
                        new PipelineStep<>(null, new GatedOut(Set.of(), null), emptyConfig())),
                true, null, reg);
        Thread runner = Thread.ofVirtual().start(exec);
        assertTrue(process.entered.await(5, TimeUnit.SECONDS));

        exec.cancel();
        runner.join(TimeUnit.SECONDS.toMillis(20));

        assertFalse(runner.isAlive());
        assertTrue(process.forked.get(), "the process step returned a fork after the cancel");
        assertEquals(PipelineStatus.CANCELLED, exec.getState().getStatus());
        for (WorkItemExecution item : exec.getState().getWorkItems()) {
            assertNotEquals(ItemStatus.ERROR, item.getStatus(), item.getWorkItem().getNameDisplay());
            assertNull(item.getError(), item.getWorkItem().getNameDisplay());
            assertFalse(item.hasFailedFork(), item.getWorkItem().getNameDisplay());
            assertEquals(ItemStatus.PENDING, item.getStatus(),
                    item.getWorkItem().getNameDisplay() + ": an item interrupted by the cancel is not left in progress");
        }
        assertNull(exec.getState().getFailure(), "a cancelled fork is not a failure");
        assertTrue(allReleased(reg), "got " + reg.snapshot());
    }

    @Test
    public void anInterruptPendingAtTheEndOfTheListingCancelsASinglePhaseRun() throws Exception {
        GatedOut out = new GatedOut(Set.of(), null);
        MainExecutor exec = new MainExecutor(List.of(), List.of(new PipelineStep<>(null, out, emptyConfig())),
                true, null, registry(Map.of("disk:*", 1000)));
        AtomicBoolean interruptKept = new AtomicBoolean();
        Thread runner = Thread.ofVirtual().start(() -> {
            Thread.currentThread().interrupt(); // an external interrupt, not a cancel(); no listing to wait for
            exec.run();
            interruptKept.set(Thread.currentThread().isInterrupted());
        });
        runner.join(TimeUnit.SECONDS.toMillis(20));

        assertFalse(runner.isAlive());
        assertEquals(PipelineStatus.CANCELLED, exec.getState().getStatus());
        assertTrue(interruptKept.get(), "an external interrupt is restored for the caller");
    }

    @Test
    public void anInterruptPendingAtTheEndOfTheExecutionCancelsItBeforeTheCursorWrite() throws Exception {
        store().writeCursor(day(3));
        byte[] before = Files.readAllBytes(store().getPath());
        MainExecutor exec = withResume(new DatedIn(tempDir, 3, null), new GatedOut(Set.of(), null),
                registry(Map.of("disk:*", 1000)), store());
        exec.prepare();
        assertEquals(PipelineStatus.PREPARED, exec.getState().getStatus()); // every item SKIPPED: nothing to wait for
        AtomicBoolean interruptKept = new AtomicBoolean();
        Thread runner = Thread.ofVirtual().start(() -> {
            Thread.currentThread().interrupt(); // an external interrupt, not a cancel()
            exec.execute(null);
            interruptKept.set(Thread.currentThread().isInterrupted());
        });
        runner.join(TimeUnit.SECONDS.toMillis(20));

        assertFalse(runner.isAlive());
        assertEquals(PipelineStatus.CANCELLED, exec.getState().getStatus());
        assertTrue(interruptKept.get(), "an external interrupt is restored for the caller");
        assertArrayEquals(before, Files.readAllBytes(store().getPath()), "a cancelled run never moves the cursor");
    }

    @Test
    public void anInterruptPendingAtTheEndOfTheListingCancelsThePreparationBeforeTheStateRead() throws Exception {
        store().writeCursor(day(1));
        MainExecutor exec = new MainExecutor(List.of(),
                List.of(new PipelineStep<>(null, new NoopAnalyze(), emptyConfig()),
                        new PipelineStep<>(null, new GatedOut(Set.of(), null), emptyConfig())),
                1, false, null, registry(Map.of("disk:*", 1000)), new ResumeContext(ResumeMode.STATE, store()));
        AtomicBoolean interruptKept = new AtomicBoolean();
        Thread caller = Thread.ofVirtual().start(() -> {
            Thread.currentThread().interrupt(); // no listing: nothing to wait for before the state read
            exec.prepare();
            interruptKept.set(Thread.currentThread().isInterrupted());
        });
        caller.join(TimeUnit.SECONDS.toMillis(20));

        assertFalse(caller.isAlive());
        assertEquals(PipelineStatus.CANCELLED, exec.getState().getStatus());
        assertNull(exec.getState().getResumeProposal(), "the state file is not read");
        assertTrue(interruptKept.get(), "an external interrupt is restored for the caller");
    }

    /** Out step whose resolveTarget (resume point resolution) requests a cancel, then fails. */
    private static final class CancellingTargetOut extends FakeAction implements IOutAction {
        private final Runnable cancel;

        CancellingTargetOut(Runnable cancel) {
            this.cancel = cancel;
        }

        @Override
        public void writeItem(WorkItem item) {
        }

        @Override
        public Optional<Path> resolveTarget(WorkItem item) {
            cancel.run(); // the cancel lands after the listing, during the resume point resolution
            throw new IllegalStateException("caused by the cancel");
        }
    }

    /**
     * Process step that blocks until it is interrupted (the phase's executor is then already shut down),
     * then returns its item plus a fork, whose submission is rejected.
     */
    private static final class ForkingOnInterruptProcess extends FakeAction implements IProcessAction {
        private final Path dir;
        final CountDownLatch entered = new CountDownLatch(1);
        final AtomicBoolean forked = new AtomicBoolean();

        ForkingOnInterruptProcess(Path dir) {
            this.dir = dir;
        }

        @Override
        public List<WorkItem> doProcess(WorkItem item) {
            entered.countDown();
            try {
                new CountDownLatch(1).await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try {
                List<WorkItem> produced = List.of(item, new WorkItem(dir.resolve("FORK.JPG")));
                forked.set(true);
                return produced;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** Emits count items (no file IO in the listing thread); afterFirst runs right after the first one. */
    private static final class ManyIn extends FakeAction implements IInAction {
        private final Path dir;
        private final int count;
        private final Runnable afterFirst;

        ManyIn(Path dir, int count, Runnable afterFirst) {
            this.dir = dir;
            this.count = count;
            this.afterFirst = afterFirst;
        }

        @Override
        public void listFiles(Consumer<WorkItem> consumer) {
            for (int i = 0; i < count; i++) {
                try {
                    consumer.accept(new WorkItem(dir.resolve("F_" + i)));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                if (i == 0) {
                    afterFirst.run();
                }
            }
        }
    }

    /** A listing that fails with the given exception before emitting anything. */
    private static final class ThrowingIn extends FakeAction implements IInAction {
        private final RuntimeException failure;

        ThrowingIn(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public void listFiles(Consumer<WorkItem> consumer) {
            throw failure;
        }
    }
}

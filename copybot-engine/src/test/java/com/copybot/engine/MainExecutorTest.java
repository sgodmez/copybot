package com.copybot.engine;

import com.copybot.config.CopybotConfig;
import com.copybot.engine.pipeline.*;
import com.copybot.engine.resources.ResourceRegistry;
import com.copybot.engine.resources.ResourceSettings;
import com.copybot.plugin.api.action.*;
import com.copybot.plugin.api.definition.IPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

public class MainExecutorTest {

    @TempDir
    Path tempDir;

    // ---- fakes ----

    abstract static class FakeAction implements IAction {
        @Override
        public void setStatusWatcher(Consumer<WorkStatus> watcher) {
        }

        @Override
        public void setPlugin(IPlugin plugin) {
        }
    }

    final class FakeInAction extends FakeAction implements IInAction {
        final int count;
        final Runnable beforeReturn;

        FakeInAction(int count, Runnable beforeReturn) {
            this.count = count;
            this.beforeReturn = beforeReturn;
        }

        @Override
        public void listFiles(Consumer<WorkItem> workItemConsumer) {
            for (int i = 0; i < count; i++) {
                try {
                    workItemConsumer.accept(new WorkItem(Files.createFile(tempDir.resolve("in-" + System.nanoTime() + "-" + i + ".txt"))));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            if (beforeReturn != null) {
                beforeReturn.run();
            }
        }
    }

    static final class FakeProcessAction extends FakeAction implements IProcessAction {
        final Set<String> resources;
        final Function<WorkItem, List<WorkItem>> behavior;

        FakeProcessAction(Set<String> resources, Function<WorkItem, List<WorkItem>> behavior) {
            this.resources = resources;
            this.behavior = behavior;
        }

        @Override
        public Set<String> requiredResources(WorkItem item) {
            return resources;
        }

        @Override
        public List<WorkItem> doProcess(WorkItem item) {
            return behavior.apply(item);
        }
    }

    static PipelineStepConfig emptyConfig() {
        return new PipelineStepConfig(null, null, null, null, null, null, null, null);
    }

    static PipelineStepConfig configWithMaxConcurrency(int maxConcurrency) {
        return new PipelineStepConfig(null, null, null, null, maxConcurrency, null, null, null);
    }

    static ResourceRegistry registry(Map<String, Integer> capacities) {
        return new ResourceRegistry(ResourceSettings.from(new CopybotConfig(null, null, capacities, null)));
    }

    static MainExecutor executor(List<PipelineStep<IInAction>> inSteps, List<PipelineStep<?>> itemSteps,
                                 boolean pipelining, ResourceRegistry reg) {
        return new MainExecutor(inSteps, itemSteps, pipelining, null, reg);
    }

    // ---- tests ----

    @Test
    public void concurrencyNeverExceedsResourceCapacity() {
        AtomicInteger current = new AtomicInteger();
        AtomicInteger maxObserved = new AtomicInteger();
        FakeProcessAction process = new FakeProcessAction(Set.of("proc"), item -> {
            int now = current.incrementAndGet();
            maxObserved.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            current.decrementAndGet();
            return List.of(item);
        });

        // disk capacity high so only "proc" (capacity 2) limits concurrency
        ResourceRegistry reg = registry(Map.of("proc", 2, "disk:*", 1000));
        MainExecutor exec = executor(
                List.of(new PipelineStep<>(null, new FakeInAction(10, null), emptyConfig())),
                List.of(new PipelineStep<>(null, process, emptyConfig())),
                true, reg);
        exec.run();

        assertEquals(2, maxObserved.get(), "capacity 2 must be saturated but never exceeded");
        assertEquals(10, exec.getState().getWorkItems().size());
        assertTrue(exec.getState().getWorkItems().stream().allMatch(w -> w.getStatus() == ItemStatus.DONE));
        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
    }

    @Test
    public void maxConcurrencyLimitsTheStepWithoutSerializingIt() {
        AtomicInteger current = new AtomicInteger();
        AtomicInteger maxObserved = new AtomicInteger();
        AtomicBoolean neverOverlapped = new AtomicBoolean(false);
        // released once two items are inside the step at the same time: proves the step is NOT serialized
        CountDownLatch bothInside = new CountDownLatch(2);
        FakeProcessAction process = new FakeProcessAction(Set.of(), item -> {
            maxObserved.accumulateAndGet(current.incrementAndGet(), Math::max);
            bothInside.countDown();
            try {
                if (!bothInside.await(5, TimeUnit.SECONDS)) {
                    neverOverlapped.set(true);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            current.decrementAndGet();
            return List.of(item);
        });

        // every explicit resource is wide open: only "maxConcurrency: 2" may bound this step
        ResourceRegistry reg = registry(Map.of("disk:*", 1000));
        MainExecutor exec = executor(
                List.of(new PipelineStep<>(null, new FakeInAction(4, null), emptyConfig())),
                List.of(new PipelineStep<>(null, process, configWithMaxConcurrency(2))),
                true, reg);
        exec.run();

        assertFalse(neverOverlapped.get(), "maxConcurrency 2 must allow 2 items at once, not serialize the step");
        assertEquals(2, maxObserved.get(), "maxConcurrency 2 must be saturated but never exceeded");
        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
    }

    @Test
    public void interruptingTheRunReleasesEveryPermitAndReportsCancelled() throws Exception {
        CountDownLatch itemStarted = new CountDownLatch(1);
        CountDownLatch neverReleased = new CountDownLatch(1);
        FakeProcessAction process = new FakeProcessAction(Set.of("proc"), item -> {
            itemStarted.countDown();
            try {
                neverReleased.await(); // only shutdownNow's interrupt can free this
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", e);
            }
            return List.of(item);
        });

        ResourceRegistry reg = registry(Map.of("proc", 1, "disk:*", 1000));
        MainExecutor exec = executor(
                List.of(new PipelineStep<>(null, new FakeInAction(3, null), emptyConfig())),
                List.of(new PipelineStep<>(null, process, emptyConfig())),
                true, reg);

        Thread runner = new Thread(exec, "test-pipeline-runner");
        runner.start();
        assertTrue(itemStarted.await(5, TimeUnit.SECONDS), "an item must be running before cancelling");

        runner.interrupt();
        runner.join(20_000);
        assertFalse(runner.isAlive(), "run() must return after the pipeline thread is interrupted");

        assertEquals(PipelineStatus.CANCELLED, exec.getState().getStatus(), "an interrupted run is cancelled, not failed");
        assertTrue(reg.snapshot().stream().allMatch(s -> s.used() == 0),
                "cancellation must release every permit, got " + reg.snapshot());
        assertTrue(reg.snapshot().stream().allMatch(s -> s.waiting() == 0),
                "cancellation must leave no waiter behind, got " + reg.snapshot());
    }

    @Test
    public void watcherNotificationsAreCoalescedAndTheLastOneIsTerminal() {
        List<PipelineStatus> seen = Collections.synchronizedList(new ArrayList<>());
        FakeProcessAction process = new FakeProcessAction(Set.of(), List::of);

        ResourceRegistry reg = registry(Map.of("disk:*", 1000));
        // 50 items x 1 step: the raw transition count is well above 100 notifications
        MainExecutor exec = new MainExecutor(
                List.of(new PipelineStep<>(null, new FakeInAction(50, null), emptyConfig())),
                List.of(new PipelineStep<>(null, process, emptyConfig())),
                true, state -> seen.add(state.getStatus()), reg);
        exec.run();

        assertEquals(50, exec.getState().getWorkItems().size());
        assertFalse(seen.isEmpty(), "the watcher must be notified at least once");
        assertTrue(seen.size() <= 30, "notifications must be coalesced, got " + seen.size());
        assertEquals(PipelineStatus.SUCCESS, seen.get(seen.size() - 1),
                "the last notification must be delivered after completion, exposing the terminal status");
    }

    @Test
    public void twoPhaseMode_noProcessingBeforeListingEnds() {
        AtomicBoolean listingFinished = new AtomicBoolean(false);
        AtomicBoolean violation = new AtomicBoolean(false);
        FakeProcessAction process = new FakeProcessAction(Set.of(), item -> {
            if (!listingFinished.get()) {
                violation.set(true);
            }
            return List.of(item);
        });

        ResourceRegistry reg = registry(Map.of("disk:*", 1000));
        MainExecutor exec = executor(
                List.of(new PipelineStep<>(null, new FakeInAction(5, () -> listingFinished.set(true)), emptyConfig())),
                List.of(new PipelineStep<>(null, process, emptyConfig())),
                false, reg);
        exec.run();

        assertFalse(violation.get(), "two-phase mode must not process before listing completes");
        assertEquals(5, exec.getState().getWorkItems().size());
        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
    }

    @Test
    public void pipeliningMode_processingOverlapsListing() {
        CountDownLatch firstProcessed = new CountDownLatch(1);
        AtomicBoolean overlapped = new AtomicBoolean(false);
        FakeProcessAction process = new FakeProcessAction(Set.of(), item -> {
            firstProcessed.countDown();
            return List.of(item);
        });

        ResourceRegistry reg = registry(Map.of("disk:*", 1000));
        // the listing emits one item then BLOCKS until that item has been processed
        FakeInAction inAction = new FakeInAction(1, () -> {
            try {
                overlapped.set(firstProcessed.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        MainExecutor exec = executor(
                List.of(new PipelineStep<>(null, inAction, emptyConfig())),
                List.of(new PipelineStep<>(null, process, emptyConfig())),
                true, reg);
        exec.run();

        assertTrue(overlapped.get(), "pipelining mode must process items while listing is still running");
    }

    @Test
    public void processReturningEmptyFiltersTheItemOut() {
        AtomicInteger secondStepCalls = new AtomicInteger();
        FakeProcessAction filter = new FakeProcessAction(Set.of(), item -> List.of());
        FakeProcessAction after = new FakeProcessAction(Set.of(), item -> {
            secondStepCalls.incrementAndGet();
            return List.of(item);
        });

        ResourceRegistry reg = registry(Map.of("disk:*", 1000));
        MainExecutor exec = executor(
                List.of(new PipelineStep<>(null, new FakeInAction(3, null), emptyConfig())),
                List.of(new PipelineStep<>(null, filter, emptyConfig()),
                        new PipelineStep<>(null, after, emptyConfig())),
                true, reg);
        exec.run();

        assertEquals(0, secondStepCalls.get(), "filtered items must not reach later steps");
        assertTrue(exec.getState().getWorkItems().stream().allMatch(w -> w.getStatus() == ItemStatus.DONE));
        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
    }

    @Test
    public void processReturningTwoItemsForksASecondExecution() {
        AtomicInteger secondStepCalls = new AtomicInteger();
        FakeProcessAction splitter = new FakeProcessAction(Set.of(), item -> {
            try {
                return List.of(item, new WorkItem(Files.createFile(tempDir.resolve("forked-" + System.nanoTime() + ".txt"))));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        FakeProcessAction after = new FakeProcessAction(Set.of(), item -> {
            secondStepCalls.incrementAndGet();
            return List.of(item);
        });

        ResourceRegistry reg = registry(Map.of("disk:*", 1000));
        MainExecutor exec = executor(
                List.of(new PipelineStep<>(null, new FakeInAction(1, null), emptyConfig())),
                List.of(new PipelineStep<>(null, splitter, emptyConfig()),
                        new PipelineStep<>(null, after, emptyConfig())),
                true, reg);
        exec.run();

        assertEquals(2, secondStepCalls.get(), "both the original and the forked item must reach the next step");
        assertEquals(2, exec.getState().getWorkItems().size());
        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
    }

    @Test
    public void oneFailingItemDoesNotStopTheOthers() {
        AtomicInteger processed = new AtomicInteger();
        AtomicInteger index = new AtomicInteger();
        FakeProcessAction process = new FakeProcessAction(Set.of(), item -> {
            if (index.incrementAndGet() == 2) {
                throw new IllegalStateException("boom on item 2");
            }
            processed.incrementAndGet();
            return List.of(item);
        });

        ResourceRegistry reg = registry(Map.of("disk:*", 1000));
        MainExecutor exec = executor(
                List.of(new PipelineStep<>(null, new FakeInAction(4, null), emptyConfig())),
                List.of(new PipelineStep<>(null, process, emptyConfig())),
                false, reg); // two-phase for a deterministic item count before processing
        exec.run();

        assertEquals(3, processed.get());
        long errors = exec.getState().getWorkItems().stream().filter(w -> w.getStatus() == ItemStatus.ERROR).count();
        long done = exec.getState().getWorkItems().stream().filter(w -> w.getStatus() == ItemStatus.DONE).count();
        assertEquals(1, errors);
        assertEquals(3, done);
        assertNotNull(exec.getState().getWorkItems().stream()
                .filter(w -> w.getStatus() == ItemStatus.ERROR).findFirst().orElseThrow().getError());
        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus(),
                "a run with a failed item must not be reported as SUCCESS");
    }

    @Test
    public void listingFailureMarksPipelineStatusError() {
        AtomicInteger processed = new AtomicInteger();
        FakeProcessAction process = new FakeProcessAction(Set.of(), item -> {
            processed.incrementAndGet();
            return List.of(item);
        });

        ResourceRegistry reg = registry(Map.of("disk:*", 1000));
        // emits one item, then blows up before listFiles() returns
        FakeInAction inAction = new FakeInAction(1, () -> {
            throw new IllegalStateException("boom in listing");
        });
        MainExecutor exec = executor(
                List.of(new PipelineStep<>(null, inAction, emptyConfig())),
                List.of(new PipelineStep<>(null, process, emptyConfig())),
                true, reg);
        exec.run();

        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus(),
                "a failing listing must not be silently reported as SUCCESS");
        assertEquals(1, processed.get(), "the item emitted before the listing failure must still be processed");
    }

    @Test
    public void anUnresolvableStepEndsInErrorWithItsCauseInsteadOfThrowing() {
        PipelineStepConfig unknown = new PipelineStepConfig("no.such.plugin", "file.read", null, null, null, null, null, null);
        MainExecutor exec = new MainExecutor(new PipelineConfig(List.of(unknown), null, null, null, null, null),
                null, registry(Map.of("disk:*", 1000)), null);

        assertDoesNotThrow(exec::run);

        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus());
        assertNotNull(exec.getState().getFailure(), "the cause is kept in the state");
    }
}

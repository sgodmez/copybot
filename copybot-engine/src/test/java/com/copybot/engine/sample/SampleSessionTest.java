package com.copybot.engine.sample;

import com.copybot.engine.pipeline.PipelineConfig;
import com.copybot.engine.pipeline.PipelineStep;
import com.copybot.engine.pipeline.PipelineStepConfig;
import com.copybot.plugin.api.action.IAction;
import com.copybot.plugin.api.action.IAnalyzeAction;
import com.copybot.plugin.api.action.IInAction;
import com.copybot.plugin.api.action.IProcessAction;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkStatus;
import com.copybot.plugin.api.definition.IPlugin;
import com.copybot.resources.ResourcesEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SampleSessionTest {

    private static final PipelineConfig CONFIG = new PipelineConfig(List.of(), List.of(), List.of(), null, null, null);

    @TempDir
    Path dir;

    abstract static class Fake implements IAction {
        @Override
        public void setStatusWatcher(Consumer<WorkStatus> watcher) {
        }

        @Override
        public void setPlugin(IPlugin plugin) {
        }
    }

    interface Lister {
        void list(Consumer<WorkItem> consumer) throws InterruptedException;
    }

    private static <A extends IAction> PipelineStep<A> step(A action, String code) {
        return new PipelineStep<>(null, action, new PipelineStepConfig(null, code, null, null, null, null, null, null));
    }

    private static final class FakeIn extends Fake implements IInAction {
        final Lister lister;

        FakeIn(Lister lister) {
            this.lister = lister;
        }

        @Override
        public void listFiles(Consumer<WorkItem> consumer) {
            try {
                lister.list(consumer);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class FakeAnalyse extends Fake implements IAnalyzeAction {
        volatile Consumer<WorkItem> behaviour;

        FakeAnalyse(Consumer<WorkItem> behaviour) {
            this.behaviour = behaviour;
        }

        @Override
        public void doAnalyze(WorkItem item) {
            behaviour.accept(item);
        }
    }

    private static final class FakeProcess extends Fake implements IProcessAction {
        final java.util.function.Function<WorkItem, Optional<List<WorkItem>>> dryRun;

        FakeProcess(java.util.function.Function<WorkItem, Optional<List<WorkItem>>> dryRun) {
            this.dryRun = dryRun;
        }

        @Override
        public List<WorkItem> doProcess(WorkItem item) {
            return List.of(item);
        }

        @Override
        public Optional<List<WorkItem>> dryRun(WorkItem item) {
            return dryRun.apply(item);
        }
    }

    /** Steps built from fixed actions; counts the calls to in(). */
    private static final class Steps implements SampleSteps {
        final AtomicInteger inCalls = new AtomicInteger();
        final List<PipelineStep<IInAction>> in = new ArrayList<>();
        final List<PipelineStep<IAnalyzeAction>> analyses = new ArrayList<>();
        final List<PipelineStep<IProcessAction>> processes = new ArrayList<>();

        Steps withIn(Lister lister) {
            in.add(SampleSessionTest.<IInAction>step(new FakeIn(lister), "fake.in"));
            return this;
        }

        FakeAnalyse withAnalyse(Consumer<WorkItem> behaviour) {
            FakeAnalyse analyse = new FakeAnalyse(behaviour);
            analyses.add(SampleSessionTest.<IAnalyzeAction>step(analyse, "fake.analyse"));
            return analyse;
        }

        Steps withProcess(String code, java.util.function.Function<WorkItem, Optional<List<WorkItem>>> dryRun) {
            processes.add(SampleSessionTest.<IProcessAction>step(new FakeProcess(dryRun), code));
            return this;
        }

        @Override
        public List<PipelineStep<IInAction>> in(PipelineConfig c) {
            inCalls.incrementAndGet();
            return in;
        }

        @Override
        public List<PipelineStep<IAnalyzeAction>> analyse(PipelineConfig c) {
            return analyses;
        }

        @Override
        public List<PipelineStep<IProcessAction>> process(PipelineConfig c) {
            return processes;
        }
    }

    private WorkItem item(String name) {
        try {
            Path file = dir.resolve(name);
            if (!Files.exists(file)) {
                Files.writeString(file, "x");
            }
            WorkItem item = new WorkItem(file);
            item.getMetadatas().display().put("name", name);
            return item;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private Lister emitting(String... names) {
        return consumer -> {
            for (String name : names) {
                consumer.accept(item(name));
            }
        };
    }

    private static SampleSession session(SampleSteps steps) {
        return new SampleSession(steps, 200, Duration.ofSeconds(2), 10);
    }

    private static List<String> sources(Sample sample) {
        return sample.items().stream().map(SampleItem::sourceName).toList();
    }

    @Test
    void listingStopsAtTheCapAndKeepsTen() {
        AtomicInteger emitted = new AtomicInteger();
        Steps steps = new Steps().withIn(consumer -> {
            for (int i = 0; i < 1000; i++) {
                emitted.incrementAndGet();
                consumer.accept(item("f" + i + ".JPG"));
            }
        });
        Sample sample = new SampleSession(steps, 200, Duration.ofSeconds(10), 10).list(CONFIG);
        assertEquals(Optional.empty(), sample.failure());
        assertEquals(200, sample.listed());
        assertTrue(sample.truncated());
        assertEquals(10, sample.items().size());
        assertTrue(emitted.get() <= 201, "the in action stopped emitting: " + emitted.get());
    }

    @Test
    void theKeptItemsAlternateTheExtensions() {
        Steps steps = new Steps().withIn(emitting("a.JPG", "b.JPG", "c.JPG", "d.NEF", "e.MP4", "f.NEF", "g"));
        Sample sample = new SampleSession(steps, 200, Duration.ofSeconds(2), 5).list(CONFIG);
        assertEquals(List.of("a.JPG", "d.NEF", "e.MP4", "g", "b.JPG"), sources(sample));
        assertFalse(sample.truncated());
        assertEquals(7, sample.listed());
    }

    @Test
    void aListingThatSwallowsTheStopEndsAtTheDeadline() {
        Steps steps = new Steps().withIn(consumer -> {
            for (String name : List.of("a.JPG", "b.JPG", "c.JPG")) {
                try {
                    consumer.accept(item(name));
                } catch (RuntimeException ignored) {
                    // swallowing the stop, like a careless plugin
                }
            }
            while (true) {
                Thread.sleep(10);
            }
        });
        long start = System.nanoTime();
        Sample sample = new SampleSession(steps, 2, Duration.ofMillis(300), 10).list(CONFIG);
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 2000, "returns in less than 2 s");
        assertEquals(2, sample.listed());
        assertTrue(sample.truncated());
    }

    @Test
    void aSlowListingIsCutAtTheTimeout() {
        Steps steps = new Steps().withIn(consumer -> {
            consumer.accept(item("a.JPG"));
            Thread.sleep(5000);
        });
        long start = System.nanoTime();
        Sample sample = new SampleSession(steps, 200, Duration.ofMillis(200), 10).list(CONFIG);
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 2000, "returns in less than 2 s");
        assertEquals(1, sample.items().size());
        assertTrue(sample.truncated());
    }

    @Test
    void aTimedOutListingIsInterruptedBeforeTheAnalysesRun() {
        AtomicBoolean listerInterrupted = new AtomicBoolean();
        AtomicBoolean seenAtAnalysis = new AtomicBoolean();
        Steps steps = new Steps().withIn(consumer -> {
            consumer.accept(item("a.JPG"));
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                listerInterrupted.set(true);
                throw e;
            }
        });
        steps.withAnalyse(item -> {
            // the interrupt is delivered asynchronously: give the lister a moment to record it
            long deadline = System.nanoTime() + Duration.ofMillis(500).toNanos();
            while (!listerInterrupted.get() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            seenAtAnalysis.set(listerInterrupted.get());
        });
        new SampleSession(steps, 200, Duration.ofMillis(200), 10).list(CONFIG);
        assertTrue(seenAtAnalysis.get(), "the lister was interrupted at the deadline, before the analyses");
    }

    @Test
    void anErrorThrownByAnAnalysisIsNotedAndNeverEscapes() {
        Steps steps = new Steps().withIn(emitting("a.JPG"));
        steps.withAnalyse(item -> {
            throw new AssertionError("boom");
        });
        Sample sample = session(steps).list(CONFIG);
        assertEquals(1, sample.items().size());
        assertTrue(sample.items().get(0).error().orElseThrow().contains("boom"));
    }

    @Test
    void aCancelledSessionCanListAgainOnTheSameThread() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch never = new CountDownLatch(1);
        AtomicBoolean block = new AtomicBoolean(true);
        Steps steps = new Steps().withIn(consumer -> {
            if (block.get()) {
                started.countDown();
                never.await();
            }
            consumer.accept(item("a.JPG"));
        });
        SampleSession session = session(steps);
        AtomicReference<Sample> first = new AtomicReference<>();
        AtomicReference<Sample> second = new AtomicReference<>();
        AtomicBoolean flagAfterFirst = new AtomicBoolean(true);
        AtomicBoolean flagAfterSecond = new AtomicBoolean(true);
        Thread caller = new Thread(() -> {
            first.set(session.list(CONFIG));
            flagAfterFirst.set(Thread.currentThread().isInterrupted());
            block.set(false);
            second.set(session.list(CONFIG));
            flagAfterSecond.set(Thread.currentThread().isInterrupted());
        });
        caller.start();
        assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS));
        session.cancel();
        caller.join(4000);
        assertFalse(caller.isAlive());
        assertEquals(Optional.of(ResourcesEngine.getString("sample.cancelled")), first.get().failure());
        assertFalse(flagAfterFirst.get(), "interrupt flag clear after a cancelled call");
        assertEquals(Optional.empty(), second.get().failure());
        assertEquals(1, second.get().items().size());
        assertFalse(flagAfterSecond.get(), "interrupt flag clear after the second call");
    }

    @Test
    void aFailingListingIsAGlobalFailure() {
        Steps steps = new Steps().withIn(consumer -> {
            throw new IllegalStateException("no card");
        });
        Sample sample = session(steps).list(CONFIG);
        assertTrue(sample.failure().orElseThrow().contains("no card"));
        assertTrue(sample.items().isEmpty());
    }

    @Test
    void analysesRunOnTheKeptItems() {
        Steps steps = new Steps().withIn(emitting("a.JPG", "b.NEF"));
        steps.withAnalyse(item -> item.getMetadatas().display().put("captureDate.Y", "2026"));
        Sample sample = session(steps).list(CONFIG);
        assertEquals(2, sample.items().size());
        for (SampleItem item : sample.items()) {
            assertEquals("2026", item.display().get("captureDate.Y"));
        }
    }

    @Test
    void aFailingAnalysisIsNotedForThatItemOnly() {
        Steps steps = new Steps().withIn(emitting("a.JPG", "b.JPG", "c.JPG"));
        steps.withAnalyse(item -> {
            if ("b.JPG".equals(item.getMetadatas().display().get("name"))) {
                item.getMetadatas().display().put("partial", "x");
                throw new IllegalStateException("corrupt");
            }
        });
        Sample sample = session(steps).list(CONFIG);
        assertEquals(3, sample.items().size());
        for (SampleItem item : sample.items()) {
            if ("b.JPG".equals(item.sourceName())) {
                assertNull(item.display().get("partial"), "the item as it was before the failing analysis");
            }
            assertEquals("b.JPG".equals(item.sourceName()), item.error().isPresent(), item.sourceName());
        }
        String expected = ResourcesEngine.getString("sample.analysis-failed", "b.JPG", "fake.analyse", "corrupt");
        assertEquals(List.of(expected), sample.notes());
    }

    @Test
    void analyseReusesTheListedItemsWithoutListingAgain() {
        Steps steps = new Steps().withIn(emitting("a.JPG"));
        FakeAnalyse analyse = steps.withAnalyse(item -> item.getMetadatas().display().put("first", "1"));
        SampleSession session = session(steps);
        Sample first = session.list(CONFIG);
        assertEquals("1", first.items().get(0).display().get("first"));

        analyse.behaviour = item -> item.getMetadatas().display().put("second", "2");
        Sample second = session.analyse(CONFIG);
        assertEquals("2", second.items().get(0).display().get("second"));
        assertNull(second.items().get(0).display().get("first"), "fresh copies of the listed items");
        assertEquals(1, steps.inCalls.get());
    }

    @Test
    void processStepsRunAsDryRun() {
        Steps steps = new Steps().withIn(emitting("x.NEF"));
        steps.withProcess("fork", item -> {
            WorkItem jpg = item.copyForDryRun();
            jpg.getMetadatas().display().put("name", "x.jpg");
            WorkItem thumb = item.copyForDryRun();
            thumb.getMetadatas().display().put("name", "thumb_x.jpg");
            return Optional.of(List.of(jpg, thumb));
        });
        Sample sample = session(steps).list(CONFIG);
        assertEquals(List.of("x.NEF", "x.NEF"), sources(sample));
        assertEquals(List.of("x.jpg", "thumb_x.jpg"), sample.items().stream().map(SampleItem::name).toList());
    }

    @Test
    void aFilteringProcessLeavesNoItemAndANote() {
        Steps steps = new Steps().withIn(emitting("x.NEF"));
        steps.withProcess("filter", item -> Optional.of(List.of()));
        Sample sample = session(steps).list(CONFIG);
        assertTrue(sample.items().isEmpty());
        assertEquals(List.of(ResourcesEngine.getString("sample.filtered", "x.NEF", "filter")), sample.notes());
    }

    @Test
    void aProcessWithoutDryRunKeepsTheItemsBeforeItWithANote() {
        Steps steps = new Steps().withIn(emitting("x.NEF"));
        steps.withProcess("legacy", item -> Optional.empty());
        Sample sample = session(steps).list(CONFIG);
        assertEquals(List.of("x.NEF"), sources(sample));
        assertEquals(List.of(ResourcesEngine.getString("sample.unsupported", "x.NEF", "legacy")), sample.notes());
    }

    @Test
    void analyseBeforeListIsAFailure() {
        Sample sample = session(new Steps()).analyse(CONFIG);
        assertEquals(Optional.of(ResourcesEngine.getString("sample.not-listed")), sample.failure());
    }

    @Test
    void cancelEndsTheOperationWithACancelledSample() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch never = new CountDownLatch(1);
        Steps steps = new Steps().withIn(consumer -> {
            started.countDown();
            never.await();
        });
        SampleSession session = session(steps);
        AtomicReference<Sample> result = new AtomicReference<>();
        Thread caller = new Thread(() -> result.set(session.list(CONFIG)));
        caller.start();
        assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS));
        session.cancel();
        caller.join(2000);
        assertFalse(caller.isAlive(), "returned within 2 s");
        assertEquals(Optional.of(ResourcesEngine.getString("sample.cancelled")), result.get().failure());
    }
}

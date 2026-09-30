package com.copybot.engine;

import com.copybot.config.CopybotConfig;
import com.copybot.engine.pipeline.PipelineStatus;
import com.copybot.engine.pipeline.PipelineStep;
import com.copybot.engine.pipeline.PipelineStepConfig;
import com.copybot.engine.resources.ResourceRegistry;
import com.copybot.engine.resources.ResourceSettings;
import com.copybot.engine.resources.ResourceSnapshot;
import com.copybot.engine.resume.ItemKey;
import com.copybot.engine.resume.ResumeContext;
import com.copybot.engine.resume.ResumeMode;
import com.copybot.engine.resume.ResumeStateStore;
import com.copybot.plugin.api.action.IAction;
import com.copybot.plugin.api.action.IAnalyzeAction;
import com.copybot.plugin.api.action.IInAction;
import com.copybot.plugin.api.action.IOutAction;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import com.copybot.plugin.api.action.WorkStatus;
import com.copybot.plugin.api.definition.IPlugin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fakes shared by the cancel / pause / engine instance tests. */
final class ControlFakes {

    private ControlFakes() {
    }

    abstract static class FakeAction implements IAction {
        @Override
        public void setStatusWatcher(Consumer<WorkStatus> watcher) {
        }

        @Override
        public void setPlugin(IPlugin plugin) {
        }
    }

    /**
     * Emits IMG_01.JPG, IMG_02.JPG... created in dir and dated 2026-09-01, 2026-09-02... through lastModified.
     * afterFirst (may be null) runs in the listing thread right after the first item was emitted.
     */
    static final class DatedIn extends FakeAction implements IInAction {
        final Path dir;
        final int count;
        final Runnable afterFirst;

        DatedIn(Path dir, int count, Runnable afterFirst) {
            this.dir = dir;
            this.count = count;
            this.afterFirst = afterFirst;
        }

        @Override
        public void listFiles(Consumer<WorkItem> consumer) {
            for (int day = 1; day <= count; day++) {
                try {
                    WorkItem wi = new WorkItem(Files.createFile(dir.resolve(String.format("IMG_%02d.JPG", day))));
                    wi.getMetadatas().setTime(WorkItemMetadata.LAST_MODIFIED,
                            Instant.parse(String.format("2026-09-%02dT10:00:00Z", day)));
                    consumer.accept(wi);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                if (day == 1 && afterFirst != null) {
                    afterFirst.run();
                }
            }
        }
    }

    /** A listing that blocks until it is interrupted, then returns without emitting anything. */
    static final class BlockingIn extends FakeAction implements IInAction {
        final CountDownLatch started = new CountDownLatch(1);

        @Override
        public void listFiles(Consumer<WorkItem> consumer) {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static final class NoopAnalyze extends FakeAction implements IAnalyzeAction {
        @Override
        public void doAnalyze(WorkItem item) {
        }
    }

    /**
     * Out step counting the writes that started. The first write blocks until gate opens (gate null: never
     * blocks; a gate never opened blocks until the write is interrupted, which then fails the item).
     */
    static final class GatedOut extends FakeAction implements IOutAction {
        final Set<String> resources;
        final CountDownLatch gate;
        final CountDownLatch firstEntered = new CountDownLatch(1);
        final AtomicInteger started = new AtomicInteger();

        GatedOut(Set<String> resources, CountDownLatch gate) {
            this.resources = resources;
            this.gate = gate;
        }

        @Override
        public Set<String> requiredResources(WorkItem item) {
            return resources;
        }

        @Override
        public void writeItem(WorkItem item) {
            if (started.incrementAndGet() == 1) {
                firstEntered.countDown();
                if (gate != null) {
                    try {
                        gate.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("interrupted", e);
                    }
                }
            }
        }
    }

    /** A configuration without plugins, every disk wide open. */
    static CopybotConfig config() {
        return new CopybotConfig(null, null, Map.of("disk:*", 1000), null);
    }

    static ResourceRegistry registry(Map<String, Integer> capacities) {
        return new ResourceRegistry(ResourceSettings.from(new CopybotConfig(null, null, capacities, null)));
    }

    static PipelineStepConfig emptyConfig() {
        return new PipelineStepConfig(null, null, null, null, null, null, null, null);
    }

    /** Single-phase pipeline without resume (pipelining): listing -> out. */
    static MainExecutor singlePhase(IInAction in, IOutAction out, ResourceRegistry registry) {
        return new MainExecutor(
                List.of(new PipelineStep<>(null, in, emptyConfig())),
                List.of(new PipelineStep<>(null, out, emptyConfig())),
                true, null, registry);
    }

    /** Two-phase pipeline with resume (mode state): listing -> analyse | barrier | out. */
    static MainExecutor withResume(IInAction in, IOutAction out, ResourceRegistry registry, ResumeStateStore store) {
        return new MainExecutor(
                List.of(new PipelineStep<>(null, in, emptyConfig())),
                List.of(new PipelineStep<>(null, new NoopAnalyze(), emptyConfig()), new PipelineStep<>(null, out, emptyConfig())),
                1, false, null, registry, new ResumeContext(ResumeMode.STATE, store));
    }

    /** The resume key of the item DatedIn emits for this day. */
    static ItemKey day(int d) {
        return new ItemKey(Instant.parse(String.format("2026-09-%02dT10:00:00Z", d)), String.format("IMG_%02d.JPG", d));
    }

    static int used(ResourceRegistry reg, String name) {
        return reg.snapshot().stream().filter(s -> s.name().equals(name)).mapToInt(ResourceSnapshot::used).sum();
    }

    static boolean allReleased(ResourceRegistry reg) {
        return reg.snapshot().stream().allMatch(s -> s.used() == 0 && s.waiting() == 0);
    }

    static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean()) {
            assertTrue(System.currentTimeMillis() < deadline, "condition not met within 5s");
            Thread.sleep(5);
        }
    }

    /** Bounded wait for the end of an execution (never an unbounded await in tests): its final status. */
    static PipelineStatus awaitStatus(Execution execution) throws InterruptedException {
        assertTrue(execution.await(20, TimeUnit.SECONDS), "the execution did not end within 20s");
        return execution.getState().getStatus();
    }

    static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

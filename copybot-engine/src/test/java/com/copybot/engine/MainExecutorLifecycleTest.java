package com.copybot.engine;

import com.copybot.engine.pipeline.ItemStatus;
import com.copybot.engine.pipeline.PipelineStatus;
import com.copybot.engine.pipeline.PipelineStep;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.engine.resume.ResumeContext;
import com.copybot.engine.resume.ResumeMode;
import com.copybot.engine.resume.ResumeStateStore;
import com.copybot.plugin.api.action.AbstractAction;
import com.copybot.plugin.api.action.IAnalyzeAction;
import com.copybot.plugin.api.action.IInAction;
import com.copybot.plugin.api.action.IOutAction;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import com.copybot.plugin.api.action.WorkStatus;
import com.copybot.plugin.api.action.WriteContext;
import com.copybot.plugin.api.action.WriteResult;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.copybot.engine.MainExecutorTest.emptyConfig;
import static com.copybot.engine.MainExecutorTest.registry;
import static org.junit.jupiter.api.Assertions.*;

/**
 * What the engine gives an action around its work: the progress channel of each item, the lifecycle calls
 * (beforeAll / beforeEach / afterEach / afterAll, see {@link com.copybot.plugin.api.action.IAction}).
 */
public class MainExecutorLifecycleTest {

    @TempDir
    Path tempDir;

    /** Every lifecycle call of the actions, in order: "name.call" or "name.call:item". */
    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());

    /** Records its lifecycle calls; fails the call named in failOn. */
    abstract class Recording extends AbstractAction {
        final String name;
        volatile String failOn;

        Recording(String name) {
            this.name = name;
        }

        private void record(String call, WorkItem item) {
            String entry = name + "." + call + (item != null ? ":" + item.getNameDisplay() : "");
            calls.add(entry);
            if (entry.equals(failOn) || (name + "." + call).equals(failOn)) {
                throw new IllegalStateException(entry + " failed");
            }
        }

        @Override
        public void beforeAll() {
            record("beforeAll", null);
        }

        @Override
        public void beforeEach(WorkItem item) {
            record("beforeEach", item);
        }

        @Override
        public void afterEach(WorkItem item) {
            record("afterEach", item);
        }

        @Override
        public void afterAll() {
            record("afterAll", null);
        }
    }

    final class In extends Recording implements IInAction {
        final List<String> names;

        In(String... names) {
            super("in");
            this.names = List.of(names);
        }

        @Override
        public void listFiles(Consumer<WorkItem> consumer) {
            calls.add("in.list");
            for (String name : names) {
                try {
                    WorkItem item = new WorkItem(Files.writeString(tempDir.resolve(name), name));
                    item.getMetadatas().setTime(WorkItemMetadata.LAST_MODIFIED, java.time.Instant.parse("2026-09-01T10:00:00Z"));
                    consumer.accept(item);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
    }

    class Analyse extends Recording implements IAnalyzeAction {
        Analyse() {
            super("analyse");
        }

        @Override
        public void doAnalyze(WorkItem item) {
            calls.add("analyse.run:" + item.getNameDisplay());
        }
    }

    final class Out extends Recording implements IOutAction {
        Out() {
            super("out");
        }

        @Override
        public WriteResult write(WorkItem item, WriteContext context) {
            calls.add("out.run:" + item.getNameDisplay());
            return WriteResult.written(null);
        }
    }

    private static <A extends com.copybot.plugin.api.action.IAction> PipelineStep<A> step(A action) {
        return new PipelineStep<>(null, action, emptyConfig());
    }

    private MainExecutor singlePhase(In in, Analyse analyse, Out out) {
        return new MainExecutor(List.of(step(in)), List.of(step(analyse), step(out)), true, null,
                registry(Map.of("disk:*", 1000)));
    }

    private MainExecutor twoPhases(In in, Analyse analyse, Out out) {
        return new MainExecutor(List.of(step(in)), List.of(step(analyse), step(out)), 1, false, null,
                registry(Map.of("disk:*", 1000)),
                new ResumeContext(ResumeMode.NONE, new ResumeStateStore(tempDir.resolve("p.state.json"))));
    }

    private List<String> callsOf(String prefix) {
        synchronized (calls) {
            return calls.stream().filter(c -> c.startsWith(prefix)).toList();
        }
    }

    // ---- lifecycle ----

    @Test
    public void aRunCallsBeforeAllOnceThenBeforeAndAfterEachAroundEveryStepThenAfterAllOnce() {
        In in = new In("a.jpg", "b.jpg");
        MainExecutor exec = singlePhase(in, new Analyse(), new Out());

        exec.run();

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
        assertEquals(List.of("in.beforeAll", "in.list", "in.afterAll"), callsOf("in."), "no item: no beforeEach");
        for (String action : List.of("analyse", "out")) {
            List<String> own = callsOf(action + ".");
            assertEquals(action + ".beforeAll", own.getFirst());
            assertEquals(action + ".afterAll", own.getLast());
            assertEquals(1, Collections.frequency(own, action + ".beforeAll"));
            assertEquals(1, Collections.frequency(own, action + ".afterAll"));
            for (String item : List.of("a.jpg", "b.jpg")) {
                int before = own.indexOf(action + ".beforeEach:" + item);
                int run = own.indexOf(action + ".run:" + item);
                int after = own.indexOf(action + ".afterEach:" + item);
                assertTrue(before >= 0 && before < run && run < after, action + " " + item + ": " + own);
            }
        }
    }

    @Test
    public void thePreparationAndTheExecutionAreTwoPhasesEachWithItsOwnActions() {
        Analyse analyse = new Analyse();
        Out out = new Out();
        MainExecutor exec = twoPhases(new In("a.jpg"), analyse, out);

        exec.prepare();
        assertEquals(PipelineStatus.PREPARED, exec.getState().getStatus());
        assertEquals(List.of("in.beforeAll", "in.list", "in.afterAll"), callsOf("in."));
        assertEquals(List.of("analyse.beforeAll", "analyse.beforeEach:a.jpg", "analyse.run:a.jpg",
                "analyse.afterEach:a.jpg", "analyse.afterAll"), callsOf("analyse."));
        assertEquals(List.of(), callsOf("out."), "the out step does not run in the preparation");

        exec.execute(null);
        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
        assertEquals(List.of("out.beforeAll", "out.beforeEach:a.jpg", "out.run:a.jpg", "out.afterEach:a.jpg",
                "out.afterAll"), callsOf("out."));
        assertEquals(5, callsOf("analyse.").size(), "the analysis is not run again");
    }

    @Test
    public void aFailingBeforeAllFailsTheRunAndTheActionsBegunGetTheirAfterAll() {
        Analyse analyse = new Analyse();
        analyse.failOn = "analyse.beforeAll";
        MainExecutor exec = singlePhase(new In("a.jpg"), analyse, new Out());

        exec.run();

        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus());
        assertEquals("analyse.beforeAll failed", exec.getState().getFailure().getMessage());
        assertTrue(exec.getState().isPreparationFailed(), "nothing was written");
        assertEquals(List.of("in.beforeAll", "in.afterAll"), callsOf("in."), "nothing listed, the in action ended");
        assertEquals(List.of("analyse.beforeAll"), callsOf("analyse."), "never begun: no afterAll");
        assertEquals(List.of(), callsOf("out."));
    }

    @Test
    public void aFailingAfterAllFailsANormalRun() {
        Out out = new Out();
        out.failOn = "out.afterAll";
        MainExecutor exec = singlePhase(new In("a.jpg"), new Analyse(), out);

        exec.run();

        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus());
        assertEquals("out.afterAll failed", exec.getState().getFailure().getMessage());
        assertTrue(callsOf("in.").contains("in.afterAll"), "every afterAll is called: " + calls);
        assertTrue(callsOf("analyse.").contains("analyse.afterAll"), "every afterAll is called: " + calls);
    }

    @Test
    public void aFailingBeforeEachFailsTheItemWithoutRunningItsStep() {
        Analyse analyse = new Analyse();
        analyse.failOn = "analyse.beforeEach:a.jpg";
        MainExecutor exec = singlePhase(new In("a.jpg", "b.jpg"), analyse, new Out());

        exec.run();

        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus(), "an item failed");
        WorkItemExecution a = item(exec, "a.jpg");
        assertEquals(ItemStatus.ERROR, a.getStatus());
        assertEquals("analyse.beforeEach:a.jpg failed", a.getError().getMessage());
        assertFalse(calls.contains("analyse.run:a.jpg"));
        assertFalse(calls.contains("analyse.afterEach:a.jpg"), "beforeEach did not return");
        assertEquals(ItemStatus.DONE, item(exec, "b.jpg").getStatus());
    }

    @Test
    public void aFailingAfterEachFailsTheItem() {
        Out out = new Out();
        out.failOn = "out.afterEach:a.jpg";
        MainExecutor exec = singlePhase(new In("a.jpg", "b.jpg"), new Analyse(), out);

        exec.run();

        assertEquals(ItemStatus.ERROR, item(exec, "a.jpg").getStatus());
        assertEquals("out.afterEach:a.jpg failed", item(exec, "a.jpg").getError().getMessage());
        assertEquals(ItemStatus.DONE, item(exec, "b.jpg").getStatus());
    }

    @Test
    public void afterEachRunsWhenTheStepFails() {
        Analyse analyse = new Analyse() {
            @Override
            public void doAnalyze(WorkItem item) {
                throw new IllegalStateException("analysis failed");
            }
        };
        MainExecutor exec = singlePhase(new In("a.jpg"), analyse, new Out());

        exec.run();

        assertEquals("analysis failed", item(exec, "a.jpg").getError().getMessage());
        assertTrue(calls.contains("analyse.afterEach:a.jpg"), calls.toString());
        assertTrue(calls.contains("analyse.afterAll"), calls.toString());
    }

    private static WorkItemExecution item(MainExecutor exec, String name) {
        return exec.getState().getWorkItems().stream()
                .filter(w -> w.getWorkItem().getNameDisplay().equals(name))
                .findFirst().orElseThrow();
    }

    // ---- progress ----

    /** An analysis reporting through the helpers of AbstractAction, then waiting for the test. */
    final class Reporting extends AbstractAction implements IAnalyzeAction {
        final CountDownLatch reported = new CountDownLatch(2);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void doAnalyze(WorkItem item) {
            boolean first = item.getNameDisplay().equals("a.jpg");
            updateStatus(new WorkStatus("hashing " + item.getNameDisplay(), -1));
            updatePercent(first ? 42 : 7); // each item its own percent, from one shared action instance
            reported.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    public void eachItemShowsThePercentItsOwnStepReports() throws Exception {
        Reporting reporting = new Reporting();
        List<Map<String, WorkStatus>> seen = Collections.synchronizedList(new ArrayList<>());
        MainExecutor exec = new MainExecutor(List.of(step(new In("a.jpg", "b.jpg"))), List.of(step(reporting)), true,
                state -> seen.add(running(state.getWorkItems())), registry(Map.of("disk:*", 1000)));

        CompletableFuture<Void> run = CompletableFuture.runAsync(exec);
        try {
            assertTrue(reporting.reported.await(10, TimeUnit.SECONDS));
            assertEquals(new WorkStatus("hashing a.jpg", 42), item(exec, "a.jpg").getWorkStatus());
            assertEquals(new WorkStatus("hashing b.jpg", 7), item(exec, "b.jpg").getWorkStatus());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (seen.stream().noneMatch(m -> m.size() == 2) && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(seen.stream().anyMatch(m -> m.equals(Map.of("a.jpg", new WorkStatus("hashing a.jpg", 42),
                    "b.jpg", new WorkStatus("hashing b.jpg", 7)))), "the watcher sees both percents: " + seen);
        } finally {
            reporting.release.countDown();
        }
        run.get(10, TimeUnit.SECONDS);
        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
    }

    private static Map<String, WorkStatus> running(Iterable<WorkItemExecution> items) {
        Map<String, WorkStatus> running = new java.util.HashMap<>();
        for (WorkItemExecution item : items) {
            WorkStatus status = item.getWorkStatus();
            if (item.getStatus() == ItemStatus.RUNNING && status != null) {
                running.put(item.getWorkItem().getNameDisplay(), status);
            }
        }
        return running;
    }

    @Test
    public void anOutStepReportsThroughItsWriteContext() throws Exception {
        CountDownLatch reported = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        IOutAction out = new AbstractOut() {
            @Override
            public WriteResult write(WorkItem item, WriteContext context) {
                context.progress().report(new WorkStatus("copy", 10));
                context.progress().percent(60);
                reported.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return WriteResult.written(null);
            }
        };
        MainExecutor exec = new MainExecutor(List.of(step(new In("a.jpg"))), List.of(step(out)), true, null,
                registry(Map.of("disk:*", 1000)));

        CompletableFuture<Void> run = CompletableFuture.runAsync(exec);
        try {
            assertTrue(reported.await(10, TimeUnit.SECONDS));
            WorkItemExecution a = item(exec, "a.jpg");
            assertEquals(ItemStatus.RUNNING, a.getStatus());
            assertEquals(new WorkStatus("copy", 60), a.getWorkStatus(), "a percent keeps the action name");
        } finally {
            release.countDown();
        }
        run.get(10, TimeUnit.SECONDS);
        assertEquals(ItemStatus.DONE, item(exec, "a.jpg").getStatus());
    }

    abstract static class AbstractOut extends AbstractAction implements IOutAction {
    }

    @Test
    public void outsideAStepTheHelpersReportIntoTheVoid() {
        Reporting reporting = new Reporting();
        reporting.release.countDown();

        assertDoesNotThrow(() -> reporting.doAnalyze(new WorkItem(Files.writeString(tempDir.resolve("x.jpg"), "x"))));
    }

    @Test
    public void theActionsOfAResolvedStepKnowTheirPlugin() {
        // PluginEngine.resolve chains each new action with its plugin: see PluginEngineTest; here, without it
        Reporting reporting = new Reporting();
        assertNull(reporting.getPlugin());
        IllegalStateException e = assertThrows(IllegalStateException.class, reporting::getResourceBundle);
        assertTrue(e.getMessage().contains("not created by the engine"), e.getMessage());
        assertEquals(Set.of(), reporting.requiredResources(null));
    }
}

package com.copybot.engine;

import com.copybot.config.CopybotConfig;
import com.copybot.engine.pipeline.PipelineStatus;
import com.copybot.engine.pipeline.PipelineStep;
import com.copybot.engine.plugin.PluginDefinition;
import com.copybot.engine.plugin.PluginEngine;
import com.copybot.engine.plugin.report.PluginReport;
import com.copybot.engine.resources.ResourceRegistry;
import com.copybot.engine.resume.ResumeContext;
import com.copybot.engine.resume.ResumeMode;
import com.copybot.engine.resume.ResumeStateStore;
import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.IOutAction;
import com.copybot.plugin.api.action.WorkItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.copybot.engine.ControlFakes.*;
import static org.junit.jupiter.api.Assertions.*;

/** One engine instance, one operation at a time (spec engine-instance §1, §2, §5). */
public class CopybotEngineTest {

    /** Relative to the module directory, the working directory of the test JVM. */
    private static final Path CONFIG = Path.of("src", "test", "resources", "com", "copybot", "engine", "config.json");

    @TempDir
    Path tempDir;

    @Test
    public void thePluginReportNamesTheConfiguredDirectories() {
        Path devDir = Path.of("does-not-exist-dev");
        CopybotConfig config = new CopybotConfig(null, devDir, null, null);
        try (CopybotEngine engine = new CopybotEngine(config)) {
            PluginReport report = engine.pluginReport();

            assertFalse(report.pluginPathConfigured());
            assertEquals(Path.of("plugins").toAbsolutePath().normalize(), report.pluginPath());
            assertEquals(List.of(devDir.toAbsolutePath().normalize()), report.devPluginPaths());
            assertTrue(report.warnings().stream().anyMatch(w -> w.contains(devDir.toAbsolutePath().normalize().toString())));
        }
    }

    private Path dir(String name) throws IOException {
        return Files.createDirectories(tempDir.resolve(name));
    }

    private static String json(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }

    /** A real file.read -> out pipeline; extra is appended to the root object (e.g. a resume block). */
    private Path pipeline(String name, Path in, Path out, String outAction, String extra) throws IOException {
        return Files.writeString(tempDir.resolve(name), """
                {
                  "inSteps": [ { "action": "file.read", "actionConfig": { "path": "%s" } } ],
                  "outStep": { "action": "%s", "actionConfig": { "outPattern": "%s/{name}", "onConflict": { "ifDifferent": "error" } } }%s
                }
                """.formatted(json(in), outAction, json(out), extra));
    }

    /** A message read from the bundles, not the "%key" placeholder of a missing key. */
    private static void assertTranslated(Throwable e) {
        assertNotNull(e.getMessage());
        assertFalse(e.getMessage().startsWith("%"), e.getMessage());
    }

    @Test
    public void aSecondOperationWhileOneIsActiveIsRefused() throws Exception {
        try (CopybotEngine engine = new CopybotEngine(config())) {
            Plan plan = engine.prepare(withResume(new DatedIn(dir("a"), 1, null), new GatedOut(Set.of(), null),
                    registry(Map.of("disk:*", 1000)), new ResumeStateStore(tempDir.resolve("a.state.json"))));
            GatedOut blocking = new GatedOut(Set.of(), new CountDownLatch(1));
            MainExecutor busy = singlePhase(new DatedIn(dir("b"), 1, null), blocking, registry(Map.of("disk:*", 1000)));
            Execution running = engine.submit(busy, busy::run);
            assertTrue(blocking.firstEntered.await(5, TimeUnit.SECONDS));

            Path any = tempDir.resolve("any.json");
            assertTranslated(assertThrows(IllegalStateException.class, () -> engine.run(any, null)));
            assertThrows(IllegalStateException.class, () -> engine.prepare(any, null));
            assertThrows(IllegalStateException.class, () -> engine.execute(plan, null));

            running.cancel();
            assertEquals(PipelineStatus.CANCELLED, awaitStatus(running));
            assertEquals(PipelineStatus.SUCCESS, awaitStatus(engine.execute(plan, null)),
                    "once the operation is over the engine accepts the next one");
        }
    }

    /** Blocks every analysis until interrupted, signalling the first one. */
    static final class BlockingAnalyze extends FakeAction implements com.copybot.plugin.api.action.IAnalyzeAction {
        final CountDownLatch started = new CountDownLatch(1);
        volatile boolean block;

        @Override
        public void doAnalyze(WorkItem item) {
            if (!block) {
                return;
            }
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted");
            }
        }
    }

    @Test
    public void theAnalysisOfTheItemsSelectedAgainIsTheActiveOperationAndCloseStopsIt() throws Exception {
        ResumeStateStore store = new ResumeStateStore(tempDir.resolve("a.state.json"));
        store.writeCursor(day(2));
        BlockingAnalyze analyze = new BlockingAnalyze();
        MainExecutor executor = new MainExecutor(
                List.of(new PipelineStep<>(null, new DatedIn(dir("a"), 3, null), emptyConfig())),
                List.of(new PipelineStep<>(null, analyze, emptyConfig()),
                        new PipelineStep<>(null, new GatedOut(Set.of(), null), emptyConfig())),
                1, false, null, registry(Map.of("disk:*", 1000)), new ResumeContext(ResumeMode.STATE, store));
        CopybotEngine engine = new CopybotEngine(config());
        try {
            Plan plan = engine.prepare(executor);
            plan.preview(com.copybot.engine.resume.ResumePoint.all());
            analyze.block = true;
            Thread caller = Thread.ofVirtual().start(() -> engine.analyse(plan));
            assertTrue(analyze.started.await(5, TimeUnit.SECONDS));

            assertThrows(IllegalStateException.class, () -> engine.execute(plan, null),
                    "analyse() counts as the active operation for its whole duration");

            engine.close();
            caller.join(TimeUnit.SECONDS.toMillis(20));

            assertFalse(caller.isAlive());
            assertEquals(PipelineStatus.PREPARED, plan.getState().getStatus(), "stopping the analysis keeps the plan");
            assertFalse(plan.toAnalyse().isEmpty(), "the items not analysed are still to analyse");
        } finally {
            engine.close();
        }
    }

    @Test
    public void analyseWithNothingToAnalyseReleasesTheEngine() throws Exception {
        try (CopybotEngine engine = new CopybotEngine(config())) {
            Plan plan = engine.prepare(withResume(new DatedIn(dir("a"), 1, null), new GatedOut(Set.of(), null),
                    registry(Map.of("disk:*", 1000)), new ResumeStateStore(tempDir.resolve("a.state.json"))));

            engine.analyse(plan);

            assertEquals(PipelineStatus.PREPARED, plan.getState().getStatus());
            assertEquals(PipelineStatus.SUCCESS, awaitStatus(engine.execute(plan, null)));
        }
    }

    @Test
    public void aBlockingPrepareIsTheActiveOperationAndCloseCancelsIt() throws Exception {
        CopybotEngine engine = new CopybotEngine(config());
        try {
            BlockingIn listing = new BlockingIn();
            MainExecutor preparing = withResume(listing, new GatedOut(Set.of(), null), registry(Map.of("disk:*", 1000)),
                    new ResumeStateStore(tempDir.resolve("p.state.json")));
            AtomicReference<Plan> plan = new AtomicReference<>();
            Thread caller = Thread.ofVirtual().start(() -> plan.set(engine.prepare(preparing)));
            assertTrue(listing.started.await(5, TimeUnit.SECONDS));

            assertThrows(IllegalStateException.class, () -> engine.run(tempDir.resolve("any.json"), null),
                    "prepare() counts as the active operation for its whole duration");

            engine.close();
            caller.join(TimeUnit.SECONDS.toMillis(20));

            assertFalse(caller.isAlive());
            assertEquals(PipelineStatus.CANCELLED, plan.get().getState().getStatus());
            assertTranslated(assertThrows(IllegalStateException.class, () -> engine.run(tempDir.resolve("any.json"), null),
                    "a closed engine accepts nothing"));
        } finally {
            engine.close(); // a failed assertion must not leave the listing parked
        }
    }

    @Test
    public void aPreparationStoppedFromItsPlanEndsCancelledWithWhatWasListedAndFreesTheEngine() throws Exception {
        CopybotEngine engine = new CopybotEngine(config());
        try {
            CountDownLatch firstListed = new CountDownLatch(1);
            Path source = Files.createDirectory(tempDir.resolve("source"));
            MainExecutor preparing = withResume(new DatedIn(source, 3, () -> {
                firstListed.countDown();
                try {
                    new CountDownLatch(1).await(); // the listing hangs after its first file
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }), new GatedOut(Set.of(), null), registry(Map.of("disk:*", 1000)),
                    new ResumeStateStore(tempDir.resolve("p.state.json")));
            AtomicReference<Plan> started = new AtomicReference<>();
            AtomicReference<Plan> returned = new AtomicReference<>();
            Thread caller = Thread.ofVirtual().start(() -> returned.set(engine.prepare(preparing, started::set)));
            assertTrue(firstListed.await(5, TimeUnit.SECONDS));

            started.get().cancelPreparation();
            caller.join(TimeUnit.SECONDS.toMillis(20));

            assertFalse(caller.isAlive());
            assertSame(started.get(), returned.get());
            assertEquals(PipelineStatus.CANCELLED, returned.get().getState().getStatus());
            assertTrue(returned.get().getState().getWorkItems().stream()
                            .anyMatch(i -> i.getWorkItem().getNameDisplay().equals("IMG_01.JPG")),
                    "what was listed is kept");
            assertFalse(Files.exists(tempDir.resolve("p.state.json")), "a stopped preparation writes no state");

            Path other = Files.createDirectory(tempDir.resolve("other"));
            Plan next = engine.prepare(withResume(new DatedIn(other, 2, null), new GatedOut(Set.of(), null),
                    registry(Map.of("disk:*", 1000)), new ResumeStateStore(tempDir.resolve("q.state.json"))));
            assertEquals(PipelineStatus.PREPARED, next.getState().getStatus(), "the engine is free again");
        } finally {
            engine.close(); // a failed assertion must not leave the listing parked
        }
    }

    @Test
    public void stoppingAPreparationAlreadyOverHasNoEffectOnThePlan() throws Exception {
        try (CopybotEngine engine = new CopybotEngine(config())) {
            Plan plan = engine.prepare(withResume(new DatedIn(tempDir, 2, null), new GatedOut(Set.of(), null),
                    registry(Map.of("disk:*", 1000)), new ResumeStateStore(tempDir.resolve("p.state.json"))));
            assertEquals(PipelineStatus.PREPARED, plan.getState().getStatus());

            plan.cancelPreparation(); // too late: it must not cancel the execution to come

            assertEquals(PipelineStatus.PREPARED, plan.getState().getStatus());
            assertEquals(PipelineStatus.SUCCESS, awaitStatus(engine.execute(plan, null)));
        }
    }

    @Test
    public void closeCancelsTheRunningExecutionAndWaitsForIt() throws Exception {
        GatedOut out = new GatedOut(Set.of(), new CountDownLatch(1));
        ResourceRegistry reg = registry(Map.of("disk:*", 1000));
        MainExecutor pipeline = singlePhase(new DatedIn(tempDir, 2, null), out, reg);
        CopybotEngine engine = new CopybotEngine(config());
        try {
            Execution execution = engine.submit(pipeline, pipeline::run);
            assertTrue(out.firstEntered.await(5, TimeUnit.SECONDS));

            engine.close();

            assertTrue(execution.isDone(), "close() waits for the cancelled execution");
            assertEquals(PipelineStatus.CANCELLED, execution.getState().getStatus());
            assertTrue(allReleased(reg), "got " + reg.snapshot());
            engine.close(); // idempotent
        } finally {
            engine.close(); // a failed assertion must not leave the write parked
        }
    }

    @Test
    public void closeFromAnInterruptedThreadStillWaitsForTheExecutionAndKeepsTheInterrupt() throws Exception {
        SlowToUnwindOut out = new SlowToUnwindOut();
        ResourceRegistry reg = registry(Map.of("disk:*", 1000));
        MainExecutor pipeline = singlePhase(new DatedIn(tempDir, 1, null), out, reg);
        CopybotEngine engine = new CopybotEngine(config());
        try {
            Execution execution = engine.submit(pipeline, pipeline::run);
            assertTrue(out.entered.await(5, TimeUnit.SECONDS));
            AtomicBoolean doneAfterClose = new AtomicBoolean();
            AtomicBoolean interruptKept = new AtomicBoolean();
            Thread closer = Thread.ofVirtual().start(() -> {
                Thread.currentThread().interrupt(); // e.g. a UI thread being stopped
                engine.close();
                doneAfterClose.set(execution.isDone() && out.unwound.get());
                interruptKept.set(Thread.currentThread().isInterrupted());
            });
            closer.join(TimeUnit.SECONDS.toMillis(20));

            assertFalse(closer.isAlive());
            assertTrue(doneAfterClose.get(), "an interrupted caller does not cut the grace period short");
            assertTrue(interruptKept.get(), "the caller's interrupt is restored");
            assertEquals(PipelineStatus.CANCELLED, execution.getState().getStatus());
            assertTrue(allReleased(reg), "got " + reg.snapshot());
        } finally {
            engine.close();
        }
    }

    @Test
    public void closeFromAnInterruptedThreadStillWaitsForAPreparePastItsPointOfNoReturn() throws Exception {
        SlowTargetOut out = new SlowTargetOut();
        MainExecutor preparing = new MainExecutor(
                List.of(new PipelineStep<>(null, new DatedIn(dir("a"), 1, null), emptyConfig())),
                List.of(new PipelineStep<>(null, new NoopAnalyze(), emptyConfig()), new PipelineStep<>(null, out, emptyConfig())),
                1, false, null, registry(Map.of("disk:*", 1000)),
                new ResumeContext(ResumeMode.DESTINATION, new ResumeStateStore(tempDir.resolve("a.state.json"))));
        CopybotEngine engine = new CopybotEngine(config());
        try {
            Thread caller = Thread.ofVirtual().start(() -> engine.prepare(preparing));
            assertTrue(out.entered.await(5, TimeUnit.SECONDS), "the resume point resolution started");
            AtomicReference<PipelineStatus> statusAfterClose = new AtomicReference<>();
            AtomicBoolean interruptKept = new AtomicBoolean();
            Thread closer = Thread.ofVirtual().start(() -> {
                Thread.currentThread().interrupt();
                engine.close();
                statusAfterClose.set(preparing.getState().getStatus());
                interruptKept.set(Thread.currentThread().isInterrupted());
            });
            closer.join(TimeUnit.SECONDS.toMillis(20));
            caller.join(TimeUnit.SECONDS.toMillis(20));

            assertFalse(closer.isAlive());
            assertFalse(caller.isAlive());
            assertNotEquals(PipelineStatus.RUNNING, statusAfterClose.get(), "close() waited for the preparation");
            assertTrue(interruptKept.get(), "the caller's interrupt is restored");
        } finally {
            engine.close();
        }
    }

    @Test
    public void closeLiftsAPauseLeftOnAPlanThatWasNeverExecuted() throws Exception {
        ResourceRegistry reg = registry(Map.of("disk:*", 1000));
        CopybotEngine engine = new CopybotEngine(config());
        try {
            Plan plan = engine.prepare(withResume(new DatedIn(dir("a"), 1, null), new GatedOut(Set.of(), null), reg,
                    new ResumeStateStore(tempDir.resolve("a.state.json"))));
            assertEquals(PipelineStatus.PREPARED, plan.getState().getStatus());
            plan.getExecutor().pause();
            assertTrue(reg.isPaused());

            engine.close();

            assertFalse(reg.isPaused(), "nothing stays paused once the engine is closed");
        } finally {
            engine.close();
        }
    }

    @Test
    public void theSafetyNetNeverTurnsACancelledExecutionIntoAnError() throws Exception {
        try (CopybotEngine engine = new CopybotEngine(config())) {
            MainExecutor cancelled = singlePhase(new DatedIn(dir("a"), 1, null), new GatedOut(Set.of(), null),
                    registry(Map.of("disk:*", 1000)));
            Execution first = engine.submit(cancelled, () -> {
                cancelled.cancel();
                cancelled.run();
                throw new IllegalStateException("after the cancelled run");
            });
            assertEquals(PipelineStatus.CANCELLED, awaitStatus(first), "an already CANCELLED status is kept");
            assertNotNull(first.getState().getFailure(), "the cause is kept");

            MainExecutor pending = singlePhase(new DatedIn(dir("b"), 1, null), new GatedOut(Set.of(), null),
                    registry(Map.of("disk:*", 1000)));
            Execution second = engine.submit(pending, () -> {
                pending.cancel();
                throw new IllegalStateException("while a cancel is pending");
            });
            assertEquals(PipelineStatus.CANCELLED, awaitStatus(second), "a pending cancel ends terminal, never ERROR");
            assertNotNull(second.getState().getFailure(), "the cause is kept");
        }
    }

    @Test
    public void twoSuccessiveInstancesInOneJvmShareThePluginsLoadedOnce() throws Exception {
        Path in = dir("in");
        Files.writeString(in.resolve("alpha.txt"), "alpha");
        List<PluginDefinition> loaded;
        try (CopybotEngine first = CopybotEngine.create(Optional.of(CONFIG))) {
            loaded = PluginEngine.getLoadedPlugins();
            assertEquals(PipelineStatus.SUCCESS,
                    awaitStatus(first.run(pipeline("one.json", in, dir("out1"), "file.write", ""), null)));
        }
        try (CopybotEngine second = CopybotEngine.create(Optional.of(CONFIG))) {
            assertSame(loaded, PluginEngine.getLoadedPlugins(), "the plugins are loaded once per JVM");
            assertEquals(PipelineStatus.SUCCESS,
                    awaitStatus(second.run(pipeline("two.json", in, dir("out2"), "file.write", ""), null)));
        }
        assertTrue(Files.exists(tempDir.resolve("out2").resolve("alpha.txt")));
    }

    @Test
    public void aPreparationFailureIsAPlanInErrorNotAnException() throws Exception {
        Path in = dir("in");
        Files.writeString(in.resolve("alpha.txt"), "alpha");
        try (CopybotEngine engine = CopybotEngine.create(Optional.of(CONFIG))) {
            Plan plan = engine.prepare(pipeline("p.json", in, dir("out"), "no.such.action",
                    ",\"resume\":{\"mode\":\"state\"}"), null);

            assertEquals(PipelineStatus.ERROR, plan.getState().getStatus());
            assertNotNull(plan.getState().getFailure(), "the cause of the failure is in the state");
        }
    }

    @Test
    public void errorsBeforeAPlanExistsAreThrownAndLeaveTheEngineUsable() throws Exception {
        Path in = dir("in");
        try (CopybotEngine engine = CopybotEngine.create(Optional.of(CONFIG))) {
            assertThrows(CopybotException.class, () -> engine.prepare(tempDir.resolve("missing.json"), null));

            Path numericMode = pipeline("n.json", in, dir("out"), "file.write", ",\"resume\":{\"mode\":1}");
            CopybotException unknown = assertThrows(CopybotException.class, () -> engine.prepare(numericMode, null));
            assertTrue(unknown.getMessage().contains("\"1\""), unknown.getMessage());

            Path notJson = Files.writeString(tempDir.resolve("bad.json"), "{ not json");
            assertThrows(CopybotException.class, () -> engine.run(notJson, null),
                    "run() reads the pipeline before starting; a refused operation never keeps the engine busy");
        }
    }

    private Path pipelineWithout(String name, String inSteps, String rest) throws IOException {
        return Files.writeString(tempDir.resolve(name), "{" + inSteps + rest + "}");
    }

    private String inStep(Path in) {
        return "\"inSteps\": [ { \"action\": \"file.read\", \"actionConfig\": { \"path\": \"" + json(in) + "\" } } ]";
    }

    @Test
    public void aPipelineWithoutInputStepIsRefusedBeforeAnythingRuns() throws Exception {
        Path in = dir("in");
        Files.writeString(in.resolve("a.txt"), "a");
        Path out = tempDir.resolve("out");
        String outStep = "\"outStep\": { \"action\": \"file.write\", \"actionConfig\": { \"outPattern\": \"" + json(out) + "/{name}\" } }";
        Path none = pipelineWithout("none.json", "", outStep);
        Path empty = pipelineWithout("empty.json", "\"inSteps\": [],", outStep);
        try (CopybotEngine engine = CopybotEngine.create(Optional.of(CONFIG))) {
            for (Path pipeline : List.of(none, empty)) {
                CopybotException prepare = assertThrows(CopybotException.class, () -> engine.prepare(pipeline, null));
                assertEquals(com.copybot.resources.ResourcesEngine.getString("pipeline.no-input"), prepare.getMessage());
                assertThrows(CopybotException.class, () -> engine.run(pipeline, null));
            }
            assertFalse(Files.exists(out), "nothing is written");
            // the engine is not left busy: a valid pipeline still runs
            Path valid = pipelineWithout("valid.json", inStep(in) + ",", outStep);
            assertEquals(PipelineStatus.SUCCESS, engine.run(valid, null).await());
        }
    }

    @Test
    public void aPipelineThatDoesNothingIsPreparedButNeverExecuted() throws Exception {
        Path in = dir("in");
        Files.writeString(in.resolve("a.txt"), "a");
        Path inOnly = pipelineWithout("in-only.json", inStep(in), "");
        try (CopybotEngine engine = CopybotEngine.create(Optional.of(CONFIG))) {
            Plan plan = engine.prepare(inOnly, null);
            assertEquals(PipelineStatus.PREPARED, plan.getState().getStatus(), "inspecting the analyses is useful");
            assertFalse(plan.canExecute());
            assertEquals(com.copybot.resources.ResourcesEngine.getString("pipeline.does-nothing"),
                    plan.executionRefusal().orElseThrow());
            CopybotException refused = assertThrows(CopybotException.class, () -> engine.execute(plan, null));
            assertEquals(com.copybot.resources.ResourcesEngine.getString("pipeline.does-nothing"), refused.getMessage());
            assertThrows(CopybotException.class, () -> engine.run(inOnly, null));
            // the engine is not left busy
            assertEquals(PipelineStatus.PREPARED, engine.prepare(inOnly, null).getState().getStatus());
        }
    }

    @Test
    public void aPipelineWithOnlyAProcessStepCanBeExecuted() throws Exception {
        Path in = dir("in");
        Files.writeString(in.resolve("a.txt"), "a");
        Path process = pipelineWithout("process.json", inStep(in) + ",",
                "\"actionSteps\": [ { \"action\": \"nope.nope\", \"actionConfig\": {} } ]");
        try (CopybotEngine engine = CopybotEngine.create(Optional.of(CONFIG))) {
            // no embedded process action exists: an unknown one fails the preparation itself, so only the
            // execution rule is checked here (a process step without out step is allowed)
            Plan plan = engine.prepare(process, null);
            assertTrue(plan.canExecute());
            assertTrue(plan.executionRefusal().isEmpty());
        }
    }

    /** Gson would read an unknown execution mode as null, that is silently "plan": it is refused instead. */
    @Test
    public void anUnknownExecutionModeIsRefused() throws Exception {
        Path in = dir("in");
        Files.writeString(in.resolve("alpha.txt"), "alpha");
        try (CopybotEngine engine = CopybotEngine.create(Optional.of(CONFIG))) {
            for (String mode : List.of("\"stream\"", "true", "{}")) {
                Path pipeline = pipeline("x.json", in, dir("out"), "file.write", ",\"execution\":" + mode);
                CopybotException prepare = assertThrows(CopybotException.class, () -> engine.prepare(pipeline, null), mode);
                assertTrue(prepare.getMessage().contains("streaming"), mode + " -> " + prepare.getMessage());
                assertThrows(CopybotException.class, () -> engine.run(pipeline, null), mode);
            }
            for (String option : List.of("\"destinationCheck\":\"all\"", "\"destinationCheck\":1",
                    "\"destinationMatch\":\"name\"", "\"destinationMatch\":[]")) {
                Path pipeline = pipeline("d.json", in, dir("out"), "file.write",
                        ",\"resume\":{\"mode\":\"destination\"," + option + "}");
                CopybotException prepare = assertThrows(CopybotException.class, () -> engine.prepare(pipeline, null), option);
                assertTrue(prepare.getMessage().contains(option.contains("Check") ? "everyFile" : "directory"),
                        option + " -> " + prepare.getMessage());
            }
            Path known = pipeline("k.json", in, dir("out"), "file.write", ",\"execution\":\"streaming\"");
            assertEquals(PipelineStatus.SUCCESS, engine.run(known, null).await(), "the engine is not left busy");
            assertTrue(Files.exists(tempDir.resolve("out").resolve("alpha.txt")));
        }
    }

    /** An unknown or non-string conflictCheck is refused, never silently "quick" (spec conflict-check §1). */
    @Test
    public void anUnknownConflictCheckIsRefused() throws Exception {
        Path in = dir("in");
        Files.writeString(in.resolve("alpha.txt"), "alpha");
        try (CopybotEngine engine = CopybotEngine.create(Optional.of(CONFIG))) {
            for (String check : List.of("\"fast\"", "1", "[]")) {
                Path pipeline = pipeline("c.json", in, dir("out"), "file.write", ",\"conflictCheck\":" + check);
                CopybotException prepare = assertThrows(CopybotException.class, () -> engine.prepare(pipeline, null), check);
                assertTrue(prepare.getMessage().contains("quick"), check + " -> " + prepare.getMessage());
            }
            Path known = pipeline("k.json", in, dir("out"), "file.write", ",\"conflictCheck\":\"full\"");
            assertEquals(PipelineStatus.PREPARED, engine.prepare(known, null).getState().getStatus());
        }
    }

    /** Any present, non-null mode that is not a string is an unknown resume mode, never "not a JSON". */
    @Test
    public void aNonStringResumeModeIsAnUnknownMode() throws Exception {
        Path in = dir("in");
        try (CopybotEngine engine = CopybotEngine.create(Optional.of(CONFIG))) {
            for (String mode : List.of("true", "{}", "[\"state\"]", "{\"name\":\"state\"}")) {
                Path pipeline = pipeline("m.json", in, dir("out"), "file.write", ",\"resume\":{\"mode\":" + mode + "}");
                CopybotException unknown = assertThrows(CopybotException.class, () -> engine.prepare(pipeline, null),
                        mode);
                // the resume.mode.unknown message lists the expected modes, in every language
                assertTrue(unknown.getMessage().contains("stateThenDestination"), mode + " -> " + unknown.getMessage());
            }
        }
    }

    /** An out step whose write, once interrupted, still needs ~500 ms to unwind (e.g. closing a stream). */
    private static final class SlowToUnwindOut extends FakeAction implements IOutAction {
        final CountDownLatch entered = new CountDownLatch(1);
        final AtomicBoolean unwound = new AtomicBoolean();

        @Override
        public void writeItem(WorkItem item) {
            entered.countDown();
            try {
                new CountDownLatch(1).await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
                while (System.nanoTime() < end) {
                    Thread.onSpinWait(); // deaf to further interrupts, like a native write
                }
                unwound.set(true);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", e);
            }
        }
    }

    /** An out step whose resolveTarget (resume point resolution, past the point of no return) takes ~500 ms. */
    private static final class SlowTargetOut extends FakeAction implements IOutAction {
        final CountDownLatch entered = new CountDownLatch(1);

        @Override
        public void writeItem(WorkItem item) {
        }

        @Override
        public Optional<Path> resolveTarget(WorkItem item) {
            entered.countDown();
            long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
            while (System.nanoTime() < end) {
                Thread.onSpinWait();
            }
            return Optional.empty();
        }
    }
}

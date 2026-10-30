package com.copybot.engine;

import com.copybot.config.SecretReferences;
import com.copybot.config.SecretStore;
import com.copybot.engine.ControlFakes.DatedIn;
import com.copybot.engine.ControlFakes.FakeAction;
import com.copybot.engine.ControlFakes.GatedOut;
import com.copybot.engine.pipeline.PipelineConfig;
import com.copybot.engine.pipeline.PipelineStatus;
import com.copybot.engine.pipeline.PipelineStep;
import com.copybot.engine.pipeline.PipelineStepConfig;
import com.copybot.engine.pipeline.RunEndStepConfig;
import com.copybot.engine.resources.ResourceRegistry;
import com.copybot.engine.resume.ResumeContext;
import com.copybot.engine.resume.ResumeMode;
import com.copybot.engine.resume.ResumeStateStore;
import com.copybot.plugin.api.action.Companion;
import com.copybot.plugin.api.action.CompanionLink;
import com.copybot.plugin.api.action.CompanionRole;
import com.copybot.plugin.api.action.IInAction;
import com.copybot.plugin.api.action.IOutAction;
import com.copybot.plugin.api.action.IProcessAction;
import com.copybot.plugin.api.action.IRunEndAction;
import com.copybot.plugin.api.action.ItemOutcome;
import com.copybot.plugin.api.action.RunCounts;
import com.copybot.plugin.api.action.RunEndContext;
import com.copybot.plugin.api.action.RunItem;
import com.copybot.plugin.api.action.RunOutcome;
import com.copybot.plugin.api.action.RunReport;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import com.copybot.plugin.api.action.WriteContext;
import com.copybot.plugin.api.action.WriteResult;
import com.copybot.plugin.api.action.WrittenFile;
import com.copybot.plugin.embedded.actions.FileWriteAction;
import com.copybot.resources.ResourcesEngine;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static com.copybot.engine.ControlFakes.emptyConfig;
import static org.junit.jupiter.api.Assertions.*;

/** The run-end actions after a copy run (spec run-end, "Déroulé"), and the run report they get. */
public class MainExecutorRunEndTest {

    private static final Set<RunOutcome> ALL = EnumSet.allOf(RunOutcome.class);
    private static final Duration LONG = Duration.ofSeconds(30);

    @TempDir
    Path tempDir;

    @BeforeAll
    static void loadPlugins() {
        // the embedded plugin, for the tests that resolve a pipeline file
        CopybotEngine.create(Optional.of(Path.of("./src/test/resources/com/copybot/engine/config.json"))).close();
    }

    /** A run-end action recording its calls in a shared log, then running its body. */
    static final class Act extends FakeAction implements IRunEndAction {
        final String name;
        final List<String> log;
        final BiConsumer<RunReport, RunEndContext> body;
        volatile RunReport report;
        volatile PipelineStatus statusDuring;
        volatile String stepDuring;
        volatile MainExecutor exec;
        volatile List<String> configWarnings = List.of();

        Act(String name, List<String> log, BiConsumer<RunReport, RunEndContext> body) {
            this.name = name;
            this.log = log;
            this.body = body;
        }

        @Override
        public List<String> configWarnings() {
            return configWarnings;
        }

        @Override
        public void runEnded(RunReport report, RunEndContext context) {
            log.add(name);
            this.report = report;
            if (exec != null) {
                statusDuring = exec.getState().getStatus();
                stepDuring = exec.getState().getRunEndStep();
            }
            body.accept(report, context);
        }
    }

    private final List<String> log = new CopyOnWriteArrayList<>();

    private Act act(String name) {
        return new Act(name, log, (r, c) -> { });
    }

    private Act act(String name, BiConsumer<RunReport, RunEndContext> body) {
        return new Act(name, log, body);
    }

    private static RunEnd.Entry entry(Act action, Set<RunOutcome> when, Duration timeout) {
        return new RunEnd.Entry(action.name, action, when, timeout);
    }

    private static RunEnd.Entry entry(Act action, Set<RunOutcome> when) {
        return entry(action, when, LONG);
    }

    private Path card() throws IOException {
        return Files.createDirectories(tempDir.resolve("card"));
    }

    private static ResourceRegistry registry() {
        return ControlFakes.registry(Map.of("disk:*", 1000));
    }

    /** Writes nothing, fails the item named failOn. */
    static final class Out extends FakeAction implements IOutAction {
        final String failOn;

        Out(String failOn) {
            this.failOn = failOn;
        }

        @Override
        public WriteResult write(WorkItem item, WriteContext context) {
            if (item.getNameDisplay().equals(failOn)) {
                throw new IllegalStateException("disk full");
            }
            return WriteResult.written(Path.of("nas", item.getNameDisplay()));
        }
    }

    private MainExecutor singlePhase(int count, String failOn) throws IOException {
        return ControlFakes.singlePhase(new DatedIn(card(), count, null), new Out(failOn), registry());
    }

    // ---- order and conditions ----

    @Test
    public void theActionsOfTheOutcomeRunInOrder() throws IOException {
        MainExecutor exec = singlePhase(2, null);
        exec.setRunEnd(List.of(entry(act("a"), EnumSet.of(RunOutcome.SUCCESS)),
                entry(act("b"), EnumSet.of(RunOutcome.ERROR)),
                entry(act("c"), ALL),
                entry(act("d"), EnumSet.of(RunOutcome.SUCCESS, RunOutcome.ERROR))));

        exec.run();

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
        assertEquals(List.of("a", "c", "d"), log);
    }

    @Test
    public void aRunInErrorRunsTheErrorActions() throws IOException {
        MainExecutor exec = singlePhase(2, "IMG_02.JPG");
        Act b = act("b");
        exec.setRunEnd(List.of(entry(act("a"), EnumSet.of(RunOutcome.SUCCESS)), entry(b, EnumSet.of(RunOutcome.ERROR)),
                entry(act("c"), EnumSet.of(RunOutcome.CANCELLED))));

        exec.run();

        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus());
        assertEquals(List.of("b"), log);
        assertEquals(RunOutcome.ERROR, b.report.outcome());
        assertNull(b.report.failure(), "items in error: no failure of the run itself");
        assertEquals(new RunCounts(1, 0, 1, 0), b.report.counts());
    }

    @Test
    public void aCancelledRunRunsTheCancelledActionsBeforeItsStatus() throws Exception {
        CountDownLatch never = new CountDownLatch(1);
        GatedOut out = new GatedOut(Set.of(), never);
        MainExecutor exec = ControlFakes.singlePhase(new DatedIn(card(), 2, null), out, registry());
        Act cancelled = act("cancelled");
        cancelled.exec = exec;
        exec.setRunEnd(List.of(entry(act("success"), EnumSet.of(RunOutcome.SUCCESS)),
                entry(cancelled, EnumSet.of(RunOutcome.CANCELLED))));
        Thread runner = Thread.ofVirtual().start(exec::run);
        assertTrue(out.firstEntered.await(5, TimeUnit.SECONDS));

        exec.cancel();
        runner.join(10_000);

        assertEquals(PipelineStatus.CANCELLED, exec.getState().getStatus());
        assertEquals(List.of("cancelled"), log);
        assertEquals(RunOutcome.CANCELLED, cancelled.report.outcome());
        assertFalse(cancelled.statusDuring.isTerminal(), "the final status is published after the actions");
        assertTrue(cancelled.report.items().size() < 2, "the item stopped by the cancel was not processed");
    }

    @Test
    public void aRunCancelledBeforeItsFirstFileRunsTheCancelledActions() throws IOException {
        MainExecutor exec = singlePhase(1, null);
        Act a = act("a");
        exec.setRunEnd(List.of(entry(a, EnumSet.of(RunOutcome.CANCELLED))));
        exec.cancel();

        exec.run();

        assertEquals(PipelineStatus.CANCELLED, exec.getState().getStatus());
        assertEquals(List.of("a"), log);
        assertNull(a.report.copyStarted());
    }

    @Test
    public void theStatusIsPublishedAfterTheActionsWhichSeeTheCurrentStep() throws IOException {
        MainExecutor exec = singlePhase(1, null);
        Act a = act("a");
        a.exec = exec;
        exec.setRunEnd(List.of(new RunEnd.Entry("Send an e-mail", a, ALL, LONG)));

        exec.run();

        assertEquals(PipelineStatus.RUNNING, a.statusDuring);
        assertEquals("Send an e-mail", a.stepDuring);
        assertNull(exec.getState().getRunEndStep(), "none outside the run-end");
        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
    }

    // ---- failures, timeout, cancel ----

    @Test
    public void anActionThatThrowsIsAWarningAndTheNextOneRuns() throws IOException {
        MainExecutor exec = singlePhase(1, null);
        exec.setRunEnd(List.of(entry(act("boom", (r, c) -> {
            throw new IllegalStateException("smtp refused");
        }), ALL), entry(act("next"), ALL)));

        exec.run();

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
        assertEquals(List.of("boom", "next"), log);
        List<String> warnings = exec.getState().getWarnings();
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.getFirst().contains("boom") && warnings.getFirst().contains("smtp refused"), warnings.getFirst());
    }

    @Test
    public void theSecretValuesOfAnActionNeverReachTheWarningsNorTheItemErrors() throws Exception {
        SecretStore store = SecretStore.at(tempDir.resolve("secrets.json"));
        store.set("smtp", "hunter2-pass");
        SecretReferences.Resolved secrets = SecretReferences.resolve(
                JsonParser.parseString("{\"password\":\"${secret:smtp}\"}"), () -> store);
        final class Failing extends FakeAction implements IOutAction {
            @Override
            public WriteResult write(WorkItem item, WriteContext context) {
                throw new IllegalStateException("login refused for hunter2-pass");
            }
        }
        Failing failing = new Failing();
        MainExecutor exec = new MainExecutor(
                List.of(new PipelineStep<IInAction>(null, new DatedIn(card(), 1, null), emptyConfig())),
                List.of(new PipelineStep<IOutAction>(null, failing, emptyConfig(), secrets)),
                true, null, registry());
        Act warns = act("warns", (r, c) -> c.warn("the server answered: bad password hunter2-pass"));
        warns.configWarnings = List.of("hunter2-pass looks weak");
        Act throwing = act("throws", (r, c) -> {
            throw new IllegalStateException("535 authentication failed (hunter2-pass)");
        });
        exec.setRunEnd(List.of(new RunEnd.Entry("warns", warns, ALL, LONG, secrets),
                new RunEnd.Entry("throws", throwing, ALL, LONG, secrets)));

        exec.run();

        List<String> warnings = exec.getState().getWarnings();
        assertEquals(3, warnings.size(), warnings.toString());
        for (String warning : warnings) {
            assertFalse(warning.contains("hunter2-pass"), warning);
            assertTrue(warning.contains("${secret:smtp}"), "the reference tells which one: " + warning);
        }
        Throwable error = exec.getState().getWorkItems().peek().getError();
        for (Throwable t = error; t != null; t = t.getCause()) {
            assertFalse(String.valueOf(t.getMessage()).contains("hunter2-pass"), String.valueOf(t));
        }
        assertTrue(error.getMessage().contains("${secret:smtp}"), error.getMessage());
    }

    @Test
    public void theBeforeAndAfterEachFailuresOfAStepNeverShowASecretEither() throws Exception {
        SecretStore store = SecretStore.at(tempDir.resolve("secrets.json"));
        store.set("smtp", "hunter2-pass");
        SecretReferences.Resolved secrets = SecretReferences.resolve(
                JsonParser.parseString("{\"password\":\"${secret:smtp}\"}"), () -> store);
        final class Hooks extends FakeAction implements IOutAction {
            @Override
            public void beforeEach(WorkItem item) {
                if (item.getNameDisplay().equals("IMG_01.JPG")) {
                    throw new IllegalStateException("before: hunter2-pass");
                }
            }

            @Override
            public void afterEach(WorkItem item) {
                if (item.getNameDisplay().equals("IMG_02.JPG")) {
                    throw new IllegalStateException("after: hunter2-pass");
                }
            }

            @Override
            public WriteResult write(WorkItem item, WriteContext context) {
                return WriteResult.written(Path.of("nas", item.getNameDisplay()));
            }
        }
        MainExecutor exec = new MainExecutor(
                List.of(new PipelineStep<IInAction>(null, new DatedIn(card(), 2, null), emptyConfig())),
                List.of(new PipelineStep<IOutAction>(null, new Hooks(), emptyConfig(), secrets)),
                true, null, registry());

        exec.run();

        List<String> errors = exec.getState().getWorkItems().stream()
                .map(item -> String.valueOf(item.getError())).sorted().toList();
        assertEquals(2, errors.size(), errors.toString());
        assertTrue(errors.getFirst().contains("after: ${secret:smtp}"), errors.toString());
        assertTrue(errors.getLast().contains("before: ${secret:smtp}"), errors.toString());
    }

    @Test
    public void theEngineControlExceptionsAreNeverRedacted() throws Exception {
        SecretStore store = SecretStore.at(tempDir.resolve("secrets.json"));
        store.set("short", "e"); // a letter of "phase stopped" and of every class name
        PipelineStep<IOutAction> step = new PipelineStep<>(null, new Out(null), emptyConfig(),
                SecretReferences.resolve(JsonParser.parseString("{\"p\":\"${secret:short}\"}"), () -> store));
        PhaseStopped stopped = new PhaseStopped();
        java.util.concurrent.RejectedExecutionException rejected =
                new java.util.concurrent.RejectedExecutionException("rejected: executor shut down");

        assertSame(stopped, ItemRunner.redacted(step, stopped), "an interruption, told apart by its type");
        assertSame(rejected, ItemRunner.redacted(step, rejected));
        assertEquals(ResourcesEngine.getString("secret.redacted", "${secret:short}"),
                ItemRunner.redacted(step, new IllegalStateException("refused")).getMessage(),
                "an error of the action is still hidden");
    }

    @Test
    public void anActionThatBlocksIsStoppedAtItsTimeLimit() throws IOException {
        MainExecutor exec = singlePhase(1, null);
        CountDownLatch interrupted = new CountDownLatch(1);
        exec.setRunEnd(List.of(entry(act("smtp", (r, c) -> {
            try {
                new CountDownLatch(1).await(); // an unreachable server
            } catch (InterruptedException e) {
                interrupted.countDown();
            }
        }), ALL, Duration.ofMillis(200)), entry(act("next"), ALL)));
        long start = System.nanoTime();

        exec.run();

        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(10), "the run ends at the time limit");
        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
        assertEquals(List.of("smtp", "next"), log);
        assertTrue(awaitQuietly(interrupted), "its thread is interrupted");
        List<String> warnings = exec.getState().getWarnings();
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.getFirst().contains("smtp") && warnings.getFirst().contains("0.2"), warnings.getFirst());
    }

    private static boolean awaitQuietly(CountDownLatch latch) {
        try {
            return latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Test
    public void aCancelDuringTheActionsStopsTheCurrentOneAndSkipsTheNext() throws Exception {
        MainExecutor exec = singlePhase(1, null);
        CountDownLatch started = new CountDownLatch(1);
        exec.setRunEnd(List.of(entry(act("archive", (r, c) -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }), ALL, Duration.ZERO), entry(act("mail"), ALL)));
        Thread runner = Thread.ofVirtual().start(exec::run);
        assertTrue(started.await(5, TimeUnit.SECONDS));

        exec.cancel();
        runner.join(10_000);

        assertFalse(runner.isAlive());
        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus(), "the outcome, decided, does not change");
        assertEquals(List.of("archive"), log);
        List<String> warnings = exec.getState().getWarnings();
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.getFirst().contains("archive"), warnings.getFirst());
    }

    // ---- outputs and context ----

    @Test
    public void whatAnActionPublishesIsSeenByTheNextOnes() throws IOException {
        MainExecutor exec = singlePhase(1, null);
        Act archive = act("archive", (r, c) -> {
            c.publish("archive", "first.zip");
            c.publish("archive", "x.zip"); // the last value wins
            c.warn("3 files left out");
        });
        Act bad = act("bad", (r, c) -> c.publish("key", null));
        Act mail = act("mail");
        exec.setRunEnd(List.of(entry(archive, ALL), entry(bad, ALL), entry(mail, ALL)));

        exec.run();

        assertEquals(Map.of(), archive.report.outputs());
        assertEquals(Map.of("archive", "x.zip"), mail.report.outputs());
        assertThrows(UnsupportedOperationException.class, () -> mail.report.outputs().put("k", "v"));
        assertTrue(mail.report.warnings().contains("3 files left out"), "the warnings of the run so far");
        List<String> warnings = exec.getState().getWarnings();
        assertEquals(2, warnings.size(), warnings.toString());
        assertEquals("3 files left out", warnings.getFirst());
        assertTrue(warnings.get(1).contains("bad"), warnings.get(1));
    }

    // ---- when it runs ----

    @Test
    public void aPreparationAloneRunsNothingTheExecutionDoes() throws IOException {
        MainExecutor exec = ControlFakes.withResume(new DatedIn(card(), 1, null), new Out(null), registry(),
                new ResumeStateStore(tempDir.resolve("p.state.json")));
        Act a = act("a");
        exec.setRunEnd(List.of(entry(a, ALL)));

        exec.prepare();
        assertEquals(List.of(), log, "a plan copies nothing");
        exec.execute(null);

        assertEquals(List.of("a"), log);
        RunReport report = a.report;
        assertNotNull(report.copyStarted());
        assertFalse(report.copyStarted().isBefore(report.started()), "started: the start of the preparation");
        assertFalse(report.ended().isBefore(report.copyStarted()));
        assertEquals(Duration.between(report.copyStarted(), report.ended()), report.duration());
    }

    @Test
    public void aFailedPreparationOfAPlanRunsNothing() throws IOException {
        Path pipeline = pipeline(tempDir.resolve("no-card"), true);
        MainExecutor exec = new MainExecutor(PipelineReader.read(pipeline), null, registry(),
                new ResumeContext(ResumeMode.STATE, ResumeStateStore.forPipeline(pipeline)), pipeline);
        exec.setRunEnd(List.of(entry(act("a"), ALL)));

        exec.prepare();

        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus());
        assertEquals(List.of(), log);
    }

    /** file.read -> file.write in a pipeline file sd.json. */
    private Path pipeline(Path source, boolean resume) throws IOException {
        return Files.writeString(tempDir.resolve("sd.json"), """
                {
                  "inSteps": [ { "action": "file.read", "actionConfig": { "path": "%s" } } ],
                  "outStep": { "action": "file.write", "actionConfig": { "outPattern": "%s/{name}" } }%s
                }
                """.formatted(json(source), json(tempDir.resolve("nas")), resume ? ",\"resume\":{\"mode\":\"state\"}" : ""));
    }

    private static String json(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }

    /** Review Focus 2: a run that fails before the copy (card missing) still runs its "error" actions. */
    @Test
    public void aRunWhosePreparationFailsRunsItsErrorActions() throws IOException {
        for (boolean resume : new boolean[]{true, false}) { // prepare then execute, then a single phase
            log.clear();
            Path pipeline = pipeline(tempDir.resolve("no-card"), resume);
            MainExecutor exec = new MainExecutor(PipelineReader.read(pipeline), null, registry(),
                    resume ? new ResumeContext(ResumeMode.STATE, ResumeStateStore.forPipeline(pipeline)) : null,
                    pipeline);
            Act error = act("error");
            error.exec = exec;
            exec.setRunEnd(List.of(entry(act("success"), EnumSet.of(RunOutcome.SUCCESS)),
                    entry(error, EnumSet.of(RunOutcome.ERROR))));

            exec.run();

            assertEquals(PipelineStatus.ERROR, exec.getState().getStatus());
            assertEquals(List.of("error"), log, "resume " + resume);
            RunReport report = error.report;
            assertEquals(RunOutcome.ERROR, report.outcome());
            assertNotNull(report.failure());
            assertTrue(report.failure().contains("no-card"), report.failure());
            if (resume) {
                assertNull(report.copyStarted(), "the copy did not begin");
                assertEquals(Duration.between(report.started(), report.ended()), report.duration());
            } // a single phase copies while it lists: its copy began with the listing
            assertEquals("sd", report.pipelineName());
            assertEquals(pipeline, report.pipelinePath());
            assertFalse(error.statusDuring.isTerminal());
        }
    }

    @Test
    public void anUnresolvableStepStillRunsTheRunEndActionsResolvedFirst() throws IOException {
        Path pipeline = Files.writeString(tempDir.resolve("sd.json"), """
                {
                  "inSteps": [ { "action": "file.read", "actionConfig": { "path": "%s" } } ],
                  "outStep": { "action": "nope.nope", "actionConfig": {} },
                  "runEnd": [ { "action": "post.process", "when": ["error"],
                                "actionConfig": { "command": "%s" } } ]
                }
                """.formatted(json(card()), MISSING_PROGRAM));
        MainExecutor exec = new MainExecutor(PipelineReader.read(pipeline), null, registry(), null, pipeline);

        exec.run();

        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus());
        assertTrue(exec.getState().isPreparationFailed());
        assertEquals(1, exec.runEndEntries().size(), "resolved before the step that failed");
        assertEquals(1, launches(exec), "the error action ran: its launch failed, a warning");
    }

    @Test
    public void aRunCancelledBeforeItsActionsAreResolvedWarnsWhenTheyCannotBe() throws IOException {
        Path pipeline = Files.writeString(tempDir.resolve("sd.json"), """
                {
                  "inSteps": [ { "action": "file.read", "actionConfig": { "path": "%s" } } ],
                  "outStep": { "action": "file.write", "actionConfig": { "outPattern": "%s/{name}" } },
                  "runEnd": [ { "action": "nope.nope", "when": ["cancelled"], "actionConfig": {} } ]
                }
                """.formatted(json(card()), json(tempDir.resolve("nas"))));
        MainExecutor exec = new MainExecutor(PipelineReader.read(pipeline), null, registry(), null, pipeline);
        exec.cancel();

        exec.run();

        assertEquals(PipelineStatus.CANCELLED, exec.getState().getStatus());
        List<String> warnings = exec.getState().getWarnings();
        assertEquals(1, warnings.size(), warnings.toString());
        String expected = ResourcesEngine.getString("run-end.unresolved", "");
        assertTrue(warnings.getFirst().startsWith(expected) && warnings.getFirst().contains("nope.nope"),
                warnings.getFirst());
    }

    /** A program that cannot be started: each launch of post.process leaves one warning naming it. */
    private static final String MISSING_PROGRAM = "copybot-no-such-program";

    private static long launches(MainExecutor exec) {
        return launches(exec.getState().getWarnings());
    }

    private static long launches(List<String> warnings) {
        return warnings.stream().filter(warning -> warning.contains(MISSING_PROGRAM)).count();
    }

    // ---- exactly once, whatever the way the run goes ----

    @Test
    public void aStreamingRunRunsTheActionsOnce() throws IOException {
        MainExecutor exec = singlePhase(2, null);
        exec.setRunEnd(List.of(entry(act("a"), ALL)));

        exec.stream();

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
        assertEquals(List.of("a"), log);
    }

    @Test
    public void anAutomaticRunWithResumeRunsTheActionsOnce() throws IOException {
        MainExecutor exec = ControlFakes.withResume(new DatedIn(card(), 2, null), new Out(null), registry(),
                new ResumeStateStore(tempDir.resolve("p.state.json")));
        Act a = act("a");
        exec.setRunEnd(List.of(entry(a, ALL)));

        exec.run(); // prepare, then execute

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
        assertEquals(List.of("a"), log);
        assertEquals(new RunCounts(2, 0, 0, 0), a.report.counts());
    }

    @Test
    public void aRunOfTheEngineRunsTheActionsOnceInEveryMode() throws Exception {
        String[] modes = {"", ",\"resume\":{\"mode\":\"state\"}", ",\"execution\":\"streaming\""};
        for (int i = 0; i < modes.length; i++) {
            Path card = Files.createDirectories(tempDir.resolve("card" + i));
            Files.writeString(card.resolve("IMG_1.JPG"), "jpeg");
            Path pipeline = Files.writeString(tempDir.resolve("run" + i + ".json"), """
                    {
                      "inSteps": [ { "action": "file.read", "actionConfig": { "path": "%s" } } ],
                      "outStep": { "action": "file.write", "actionConfig": { "outPattern": "%s/{name}" } },
                      "runEnd": [ { "action": "post.process", "actionConfig": { "command": "%s" } } ]%s
                    }
                    """.formatted(json(card), json(tempDir.resolve("nas" + i)), MISSING_PROGRAM, modes[i]));
            try (CopybotEngine engine = new CopybotEngine(ControlFakes.config())) {
                Execution execution = engine.run(pipeline, null);

                assertEquals(PipelineStatus.SUCCESS, ControlFakes.awaitStatus(execution), "mode " + modes[i]);
                assertEquals(1, launches(execution.getState().getWarnings()), "mode " + modes[i]);
            }
        }
    }

    // ---- a cancel just after a failure ----

    /** Lists nothing; its afterAll waits for the gate (or an interrupt). */
    static final class GatedEndIn extends FakeAction implements IInAction {
        final CountDownLatch inAfterAll = new CountDownLatch(1);
        final CountDownLatch gate = new CountDownLatch(1);
        volatile boolean interrupted;

        @Override
        public void listFiles(Consumer<WorkItem> consumer) {
        }

        @Override
        public void afterAll() {
            inAfterAll.countDown();
            try {
                gate.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
    }

    /** Its beforeAll fails: the run fails in its preparation. */
    static final class BrokenOut extends FakeAction implements IOutAction {
        @Override
        public void beforeAll() {
            throw new IllegalStateException("no disk");
        }

        @Override
        public WriteResult write(WorkItem item, WriteContext context) {
            return WriteResult.written(null);
        }
    }

    @Test
    public void aCancelJustAfterAFailureDoesNotSuppressTheErrorActions() throws Exception {
        GatedEndIn in = new GatedEndIn();
        MainExecutor exec = new MainExecutor(List.of(new PipelineStep<IInAction>(null, in, emptyConfig())),
                List.of(new PipelineStep<>(null, new BrokenOut(), emptyConfig())), true, null, registry());
        exec.setRunEnd(List.of(entry(act("error"), EnumSet.of(RunOutcome.ERROR))));
        Thread runner = Thread.ofVirtual().start(exec::run);
        assertTrue(in.inAfterAll.await(5, TimeUnit.SECONDS));

        exec.cancel(); // the outcome is decided: like a cancel once ERROR is published
        in.gate.countDown();
        runner.join(10_000);

        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus());
        assertFalse(in.interrupted, "the end of the failed phase is not interrupted");
        assertEquals(List.of("error"), log);
        assertFalse(exec.isCancelRequested());
    }

    // ---- configuration warnings ----

    @Test
    public void theConfigurationWarningsOfTheActionsAreShownWithTheSteps() throws IOException {
        MainExecutor exec = singlePhase(1, null);
        Act a = act("mail");
        a.configWarnings = List.of("mail: no recipient");
        exec.setRunEnd(List.of(entry(a, ALL)));

        exec.run();

        assertEquals(List.of("mail: no recipient"), exec.getState().getWarnings(), "once");
        assertEquals(List.of("mail: no recipient"), a.report.warnings(), "known before the run ended");
    }

    @Test
    public void aRunEndActionThatCannotBeConfiguredIsALocalisedRefusal() throws IOException {
        Path pipeline = Files.writeString(tempDir.resolve("sd.json"), """
                {
                  "inSteps": [ { "action": "file.read", "actionConfig": { "path": "%s" } } ],
                  "outStep": { "action": "file.write", "actionConfig": { "outPattern": "%s/{name}" } },
                  "runEnd": [ { "action": "post.process", "actionConfig": { "command": ["not", "a", "string"] } } ]
                }
                """.formatted(json(card()), json(tempDir.resolve("nas"))));
        MainExecutor exec = new MainExecutor(PipelineReader.read(pipeline), null, registry(), null, pipeline);

        exec.run();

        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus());
        assertTrue(exec.getState().isPreparationFailed());
        Throwable failure = exec.getState().getFailure();
        assertInstanceOf(com.copybot.exception.CopybotException.class, failure, "not a raw Gson exception");
        assertTrue(failure.getMessage().contains("post.process") && failure.getMessage().contains("1"),
                failure.getMessage());
        assertNotNull(failure.getCause());
    }

    @Test
    public void anExecutorWithoutPipelineHasAnEmptyName() throws IOException {
        MainExecutor exec = singlePhase(1, null);
        Act a = act("a");
        exec.setRunEnd(List.of(entry(a, ALL)));

        exec.run();

        assertEquals("", a.report.pipelineName());
        assertNull(a.report.pipelinePath());
        assertEquals(exec.getRunId(), a.report.runId());
    }

    // ---- the report ----

    /** Drops the items named IMG_02.JPG (filtered by a process step). */
    static final class DropSecond extends FakeAction implements IProcessAction {
        @Override
        public List<WorkItem> doProcess(WorkItem item) {
            return item.getNameDisplay().equals("IMG_02.JPG") ? List.of() : List.of(item);
        }
    }

    @Test
    public void theReportLeavesOutTheItemsTheResumePointSkipped() throws IOException {
        ResumeStateStore store = new ResumeStateStore(tempDir.resolve("p.state.json"));
        store.writeCursor(ControlFakes.day(1));
        MainExecutor exec = new MainExecutor(
                List.of(new PipelineStep<IInAction>(null, new DatedIn(card(), 4, null), emptyConfig())),
                List.of(new PipelineStep<>(null, new ControlFakes.NoopAnalyze(), emptyConfig()),
                        new PipelineStep<>(null, new DropSecond(), emptyConfig()),
                        new PipelineStep<>(null, new Out("IMG_03.JPG"), emptyConfig())),
                1, false, null, registry(), new ResumeContext(ResumeMode.STATE, store));
        Act a = act("a");
        exec.setRunEnd(List.of(entry(a, ALL)));

        exec.run();

        RunReport report = a.report;
        assertEquals(RunOutcome.ERROR, report.outcome());
        assertEquals(new RunCounts(1, 0, 1, 1), report.counts());
        assertEquals(List.of("IMG_02.JPG", "IMG_03.JPG", "IMG_04.JPG"),
                report.items().stream().map(RunItem::name).toList(), "IMG_01.JPG: before the cursor");
        Map<String, RunItem> byName = report.items().stream()
                .collect(java.util.stream.Collectors.toMap(RunItem::name, item -> item));
        assertEquals(ItemOutcome.FILTERED, byName.get("IMG_02.JPG").outcome());
        assertEquals(ItemOutcome.ERROR, byName.get("IMG_03.JPG").outcome());
        assertTrue(byName.get("IMG_03.JPG").reason().contains("disk full"));
        assertEquals(ItemOutcome.WRITTEN, byName.get("IMG_04.JPG").outcome());
        assertEquals(Path.of("nas", "IMG_04.JPG"), byName.get("IMG_04.JPG").target());
        assertNotNull(report.copyStarted());
    }

    /** Skips every item: an existing file is already at its target. */
    static final class SkipOut extends FakeAction implements IOutAction {
        @Override
        public WriteResult write(WorkItem item, WriteContext context) {
            return WriteResult.skipped(Path.of("nas", item.getNameDisplay()), "already at the destination");
        }
    }

    @Test
    public void aSkippedItemKeepsTheTargetThatMadeTheOutputSkipIt() throws IOException {
        MainExecutor exec = ControlFakes.singlePhase(new DatedIn(card(), 1, null), new SkipOut(), registry());
        Act a = act("a");
        exec.setRunEnd(List.of(entry(a, ALL)));

        exec.run();

        assertEquals(List.of(new RunItem("IMG_01.JPG", a.report.items().getFirst().source(), ItemOutcome.SKIPPED,
                Path.of("nas", "IMG_01.JPG"), "already at the destination")), a.report.items());
    }

    /** Lists one item with a COPY companion. */
    static final class GroupIn extends FakeAction implements IInAction {
        final Path dir;

        GroupIn(Path dir) {
            this.dir = dir;
        }

        @Override
        public void listFiles(Consumer<WorkItem> consumer) {
            try {
                WorkItem raw = file("IMG_1.CR3", "raw");
                raw.getMetadatas().setTime(WorkItemMetadata.LAST_MODIFIED, Instant.parse("2026-09-01T10:00:00Z"));
                WorkItem xmp = file("IMG_1.xmp", "xmp!");
                consumer.accept(raw.withCompanions(List.of(
                        new Companion(xmp, CompanionRole.COPY, CompanionLink.BASE, ".xmp"))));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private WorkItem file(String name, String content) throws IOException {
            WorkItem item = new WorkItem(Files.writeString(dir.resolve(name), content));
            item.getMetadatas().setSize((long) content.length());
            item.getMetadatas().display().put("name", name);
            return item;
        }
    }

    @Test
    public void theWrittenFilesHoldTheCompanions() throws IOException {
        FileWriteAction write = new FileWriteAction();
        Path nas = tempDir.resolve("nas");
        write.loadConfig(JsonParser.parseString("{\"outPattern\":\"" + json(nas) + "/{name}\"}"));
        MainExecutor exec = new MainExecutor(
                List.of(new PipelineStep<IInAction>(null, new GroupIn(card()), emptyConfig())),
                List.of(new PipelineStep<>(null, write, new PipelineStepConfig(null, "file.write", null, null, null,
                        null, null, null))),
                true, null, registry());
        Act a = act("a");
        exec.setRunEnd(List.of(entry(a, ALL)));

        exec.run();

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus(), String.valueOf(exec.getState().getFailure()));
        List<WrittenFile> written = a.report.writtenFiles();
        assertEquals(List.of(nas.resolve("IMG_1.CR3"), nas.resolve("IMG_1.xmp")),
                written.stream().map(WrittenFile::target).toList());
        assertEquals(4, written.get(1).size());
        assertEquals(64, written.get(1).sha256().length());
        assertEquals(List.of(nas), a.report.createdDirectories());
        assertEquals(new RunCounts(1, 0, 0, 0), a.report.counts());
    }

    @Test
    public void aRunEndEntryOfThePipelineIsResolvedWithItsTimeLimit() throws IOException {
        RunEndStepConfig noLimit = new RunEndStepConfig(null, "post.process", null, null, 0,
                JsonParser.parseString("{\"command\":\"x\"}"));
        RunEndStepConfig byDefault = new RunEndStepConfig(null, "post.process", null, List.of("cancelled"), null,
                JsonParser.parseString("{\"command\":\"x\"}"));
        RunEndStepConfig fiveSeconds = new RunEndStepConfig("embedded", "post.process", null, null, 5,
                JsonParser.parseString("{\"command\":\"x\"}"));

        List<RunEnd.Entry> entries = RunEnd.resolve(List.of(noLimit, byDefault, fiveSeconds));

        assertEquals(List.of(Duration.ZERO, Duration.ofSeconds(30), Duration.ofSeconds(5)),
                entries.stream().map(RunEnd.Entry::timeout).toList());
        assertEquals(EnumSet.of(RunOutcome.CANCELLED), entries.get(1).when());
        assertFalse(entries.getFirst().name().isBlank());
    }

    @Test
    public void thePipelineFileReachesTheExecutorFromTheEngine() throws IOException {
        Path pipeline = pipeline(card(), true);
        try (CopybotEngine engine = new CopybotEngine(ControlFakes.config())) {
            Plan plan = engine.prepare(pipeline, null);

            assertEquals(pipeline, plan.getExecutor().pipelinePath());
            plan.discard();
        }
    }

    /** The CLI with --all, --from-date or --from-file: a preparation launched to copy runs the actions of its failure. */
    @Test
    public void aFailedPreparationForACopyRunsItsErrorActionsAPlanDoesNot() throws IOException {
        Path pipeline = Files.writeString(tempDir.resolve("sd.json"), """
                {
                  "inSteps": [ { "action": "file.read", "actionConfig": { "path": "%s" } } ],
                  "outStep": { "action": "file.write", "actionConfig": { "outPattern": "%s/{name}" } },
                  "runEnd": [ { "action": "post.process", "when": ["error"], "actionConfig": { "command": "%s" } } ],
                  "resume": { "mode": "state" }
                }
                """.formatted(json(tempDir.resolve("no-card")), json(tempDir.resolve("nas")), MISSING_PROGRAM));
        try (CopybotEngine engine = new CopybotEngine(ControlFakes.config())) {
            Plan plan = engine.prepare(pipeline, null);

            assertEquals(PipelineStatus.ERROR, plan.getState().getStatus());
            assertEquals(0, launches(plan.getState().getWarnings()), "a plan copies nothing");

            Plan forCopy = engine.prepareForCopy(pipeline, null, null);

            assertEquals(PipelineStatus.ERROR, forCopy.getState().getStatus());
            assertEquals(1, launches(forCopy.getState().getWarnings()), "the error action ran");
        }
    }

    @Test
    public void aNegativeTimeLimitIsRefused() {
        PipelineConfig config = new PipelineConfig(null, null, null, null, null, null, null, List.of(
                new RunEndStepConfig(null, "post.process", null, null, -1, null)));

        Optional<String> refusal = com.copybot.engine.pipeline.PipelineChecks.runEndRefusal(config);

        assertTrue(refusal.isPresent());
        assertTrue(refusal.get().contains("post.process") && refusal.get().contains("-1"), refusal.get());
    }
}

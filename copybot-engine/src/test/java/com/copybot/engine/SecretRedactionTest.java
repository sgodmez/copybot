package com.copybot.engine;

import com.copybot.config.SecretReferences;
import com.copybot.config.SecretStore;
import com.copybot.engine.ControlFakes.DatedIn;
import com.copybot.engine.ControlFakes.FakeAction;
import com.copybot.engine.pipeline.PipelineStatus;
import com.copybot.engine.pipeline.PipelineStep;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.engine.resume.ResumeContext;
import com.copybot.engine.resume.ResumeMode;
import com.copybot.engine.resume.ResumePoint;
import com.copybot.engine.resume.ResumeStateStore;
import com.copybot.engine.sample.Sample;
import com.copybot.engine.sample.SampleItem;
import com.copybot.engine.sample.SampleSession;
import com.copybot.engine.sample.SampleSteps;
import com.copybot.engine.pipeline.PipelineConfig;
import com.copybot.plugin.api.action.IAnalyzeAction;
import com.copybot.plugin.api.action.IInAction;
import com.copybot.plugin.api.action.IOutAction;
import com.copybot.plugin.api.action.IProcessAction;
import com.copybot.plugin.api.action.RunReport;
import com.copybot.plugin.api.action.RunOutcome;
import com.copybot.plugin.api.action.TargetCheck;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WriteContext;
import com.copybot.plugin.api.action.WriteResult;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static com.copybot.engine.ControlFakes.emptyConfig;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The secret values a step was configured with never reach a failure, a message or a log of the pipeline, whatever
 * the path (spec remote §1): each one hidden by the redactor of the step concerned.
 */
public class SecretRedactionTest {

    private static final String VALUE = "hunter2-pass";
    private static final String REFERENCE = "${secret:smtp}";

    @TempDir
    Path tempDir;

    private SecretReferences.Resolved secrets() throws IOException {
        SecretStore store = SecretStore.at(tempDir.resolve("secrets.json"));
        store.set("smtp", VALUE);
        return SecretReferences.resolve(JsonParser.parseString("{\"password\":\"" + REFERENCE + "\"}"), () -> store);
    }

    private Path card() throws IOException {
        return Files.createDirectories(tempDir.resolve("card"));
    }

    private ResumeStateStore store() {
        return new ResumeStateStore(tempDir.resolve("p.state.json"));
    }

    private static <A extends com.copybot.plugin.api.action.IAction> PipelineStep<A> step(A action,
                                                                                          SecretReferences.Redactor redactor) {
        return new PipelineStep<>(null, action, emptyConfig(), redactor);
    }

    private static <A extends com.copybot.plugin.api.action.IAction> PipelineStep<A> plain(A action) {
        return new PipelineStep<>(null, action, emptyConfig());
    }

    /** No message, cause or suppressed failure shows the value; the top one shows the reference. */
    private static void assertHidden(Throwable failure) {
        assertNotNull(failure, "a failure");
        assertTrue(String.valueOf(failure.getMessage()).contains(REFERENCE), String.valueOf(failure));
        assertNoValue(failure);
    }

    private static void assertNoValue(Throwable failure) {
        assertNoValue(failure, VALUE, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static void assertNoValue(Throwable failure, String value, Set<Throwable> seen) {
        if (failure == null || !seen.add(failure)) {
            return;
        }
        assertFalse(String.valueOf(failure).contains(value), String.valueOf(failure));
        for (Throwable suppressed : failure.getSuppressed()) {
            assertNoValue(suppressed, value, seen);
        }
        assertNoValue(failure.getCause(), value, seen);
    }

    private static void assertHidden(String text) {
        assertNotNull(text);
        assertFalse(text.contains(VALUE), text);
        assertTrue(text.contains(REFERENCE), text);
    }

    /** The records of these loggers, every level, while body runs (not printed). */
    public static List<LogRecord> recordLogs(List<Class<?>> classes, Runnable body) {
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        handler.setLevel(Level.ALL);
        List<Logger> loggers = new ArrayList<>();
        List<Level> levels = new ArrayList<>();
        for (Class<?> type : classes) {
            Logger logger = Logger.getLogger(type.getName());
            loggers.add(logger);
            levels.add(logger.getLevel());
            logger.setLevel(Level.ALL);
            logger.setUseParentHandlers(false);
            logger.addHandler(handler);
        }
        try {
            body.run();
        } finally {
            for (int i = 0; i < loggers.size(); i++) {
                loggers.get(i).removeHandler(handler);
                loggers.get(i).setUseParentHandlers(true);
                loggers.get(i).setLevel(levels.get(i));
            }
        }
        return records;
    }

    /** Some record was logged, none showing the value: its message, parameters, failure. */
    public static void assertNoValueLogged(List<LogRecord> records, String value) {
        assertFalse(records.isEmpty(), "the failure is logged");
        for (LogRecord record : records) {
            assertFalse(String.valueOf(record.getMessage()).contains(value), record.getMessage());
            if (record.getParameters() != null) {
                for (Object parameter : record.getParameters()) {
                    assertFalse(String.valueOf(parameter).contains(value), String.valueOf(parameter));
                }
            }
            assertNoValue(record.getThrown(), value, Collections.newSetFromMap(new IdentityHashMap<>()));
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException sneaky(Throwable failure) throws E {
        throw (E) failure;
    }

    /** Writes nothing. */
    static class Out extends FakeAction implements IOutAction {
        @Override
        public WriteResult write(WorkItem item, WriteContext context) {
            return WriteResult.written(Path.of("nas", item.getNameDisplay()));
        }
    }

    /** A run-end action keeping the report it got. */
    static final class Reported extends FakeAction implements com.copybot.plugin.api.action.IRunEndAction {
        volatile RunReport report;

        @Override
        public void runEnded(RunReport report, com.copybot.plugin.api.action.RunEndContext context) {
            this.report = report;
        }
    }

    private static Reported reportOf(MainExecutor exec) {
        Reported reported = new Reported();
        exec.setRunEnd(List.of(new RunEnd.Entry("report", reported, EnumSet.allOf(RunOutcome.class),
                Duration.ofSeconds(30))));
        return reported;
    }

    private MainExecutor run(IInAction in, SecretReferences.Redactor inRedactor, IOutAction out,
                             SecretReferences.Redactor outRedactor) {
        return new MainExecutor(List.of(step(in, inRedactor)), List.of(step(out, outRedactor)), true, null,
                ControlFakes.registry(Map.of("disk:*", 1000)));
    }

    // ---- 1. listing ----

    @Test
    public void aListingFailureHidesTheSecretsOfItsInput() throws Exception {
        final class FailingIn extends FakeAction implements IInAction {
            @Override
            public void listFiles(Consumer<WorkItem> consumer) {
                throw new IllegalStateException("login refused for " + VALUE);
            }
        }
        MainExecutor exec = run(new FailingIn(), secrets(), new Out(), SecretReferences.Redactor.NONE);
        Reported reported = reportOf(exec);

        exec.run();

        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus());
        assertHidden(exec.getState().getFailure());
        assertHidden(reported.report.failure());
    }

    @Test
    public void aFailureOfTheListingFootprintHidesTheSecretsOfItsInput() throws Exception {
        final class FootprintIn extends FakeAction implements IInAction {
            @Override
            public Set<String> requiredResources(WorkItem item) {
                throw new IllegalStateException("no such host " + VALUE);
            }

            @Override
            public void listFiles(Consumer<WorkItem> consumer) {
            }
        }
        MainExecutor exec = run(new FootprintIn(), secrets(), new Out(), SecretReferences.Redactor.NONE);

        exec.run();

        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus());
        assertHidden(exec.getState().getFailure());
    }

    // ---- 2. beforeAll ----

    @Test
    public void aBeforeAllFailureHidesTheSecretsOfItsStep() throws Exception {
        final class Refusing extends Out {
            @Override
            public void beforeAll() {
                throw new IllegalStateException("cannot log in with " + VALUE);
            }
        }
        MainExecutor exec = run(new DatedIn(card(), 1, null), SecretReferences.Redactor.NONE, new Refusing(),
                secrets());

        exec.run();

        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus());
        assertHidden(exec.getState().getFailure());
    }

    @Test
    public void aBeforeAllFailureBeforeADeferredAnalysisHidesTheSecretsOfItsStep() throws Exception {
        store().writeCursor(ControlFakes.day(3));
        final class SecondTimeRefusing extends FakeAction implements IAnalyzeAction {
            final AtomicInteger begun = new AtomicInteger();

            @Override
            public void beforeAll() {
                if (begun.incrementAndGet() == 2) { // the first one is the preparation's
                    throw new IllegalStateException("cannot log in with " + VALUE);
                }
            }

            @Override
            public void doAnalyze(WorkItem item) {
            }
        }
        MainExecutor exec = new MainExecutor(List.of(plain(new DatedIn(card(), 4, null))),
                List.of(step(new SecondTimeRefusing(), secrets()), plain(new Out())), 1, false, null,
                ControlFakes.registry(Map.of("disk:*", 1000)), new ResumeContext(ResumeMode.STATE, store()));
        exec.prepare();
        exec.applyOverride(ResumePoint.all());
        List<WorkItemExecution> deferred = exec.toAnalyse();
        assertFalse(deferred.isEmpty());

        exec.analyseDeferred();

        for (WorkItemExecution item : deferred) {
            assertHidden(item.getError());
        }
    }

    // ---- 3. afterAll ----

    @Test
    public void anAfterAllFailureHidesTheSecretsOfItsStep() throws Exception {
        final class Closing extends Out {
            @Override
            public void afterAll() {
                throw new IllegalStateException("logout refused for " + VALUE);
            }
        }
        MainExecutor exec = run(new DatedIn(card(), 1, null), SecretReferences.Redactor.NONE, new Closing(),
                secrets());

        exec.run();

        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus());
        assertHidden(exec.getState().getFailure());
    }

    @Test
    public void anAfterAllFailureOfAFailedPhaseIsLoggedWithoutTheSecrets() throws Exception {
        final class ClosingIn extends DatedInWrapper {
            ClosingIn(Path dir) {
                super(dir);
            }

            @Override
            public void afterAll() {
                throw new IllegalStateException("logout refused for " + VALUE);
            }
        }
        final class Refusing extends Out {
            @Override
            public void beforeAll() {
                throw new IllegalStateException("destination offline");
            }
        }
        MainExecutor exec = run(new ClosingIn(card()), secrets(), new Refusing(), SecretReferences.Redactor.NONE);

        List<LogRecord> records = recordLogs(List.of(MainExecutor.class), exec::run);

        assertEquals("destination offline", exec.getState().getFailure().getMessage());
        assertNoValueLogged(records, VALUE);
        assertNoValue(exec.getState().getFailure());
    }

    /** DatedIn of one item, open to subclasses. */
    static class DatedInWrapper extends FakeAction implements IInAction {
        private final DatedIn in;

        DatedInWrapper(Path dir) {
            this.in = new DatedIn(dir, 1, null);
        }

        @Override
        public void listFiles(Consumer<WorkItem> consumer) {
            in.listFiles(consumer);
        }
    }

    // ---- 4. afterItem ----

    @Test
    public void anAfterItemFailureIsLoggedWithoutTheSecrets() throws Exception {
        final class Cleaning extends Out {
            @Override
            public void afterItem(WorkItem item) {
                throw new IllegalStateException("cannot remove the temporary file of " + VALUE);
            }
        }
        MainExecutor exec = run(new DatedIn(card(), 1, null), SecretReferences.Redactor.NONE, new Cleaning(),
                secrets());

        List<LogRecord> records = recordLogs(List.of(ItemRunner.class), exec::run);

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
        assertNoValueLogged(records, VALUE);
    }

    // ---- 5. checkTarget, 6. plan targets ----

    private MainExecutor prepared(IOutAction out) throws IOException {
        MainExecutor exec = new MainExecutor(List.of(plain(new DatedIn(card(), 1, null))),
                List.of(plain(new ControlFakes.NoopAnalyze()), step(out, secrets())), 1, false, null,
                ControlFakes.registry(Map.of("disk:*", 1000)), new ResumeContext(ResumeMode.STATE, store()));
        return exec;
    }

    @Test
    public void aFailingTargetCheckIsLoggedWithoutTheSecrets() throws Exception {
        final class Checking extends Out {
            @Override
            public TargetCheck checkTarget(WorkItem item, boolean compareContent) {
                throw new IllegalStateException("share refused " + VALUE);
            }
        }
        MainExecutor exec = prepared(new Checking());

        List<LogRecord> records = recordLogs(List.of(ItemRunner.class), exec::prepare);

        assertEquals(PipelineStatus.PREPARED, exec.getState().getStatus());
        assertEquals(TargetCheck.UNKNOWN, exec.getState().getWorkItems().peek().getTargetCheck());
        assertNoValueLogged(records, VALUE);
    }

    @Test
    public void aFailingTargetOfThePlanHidesTheSecrets() throws Exception {
        final class Targeting extends Out {
            @Override
            public Optional<Path> resolveTarget(WorkItem item) {
                throw new IllegalStateException("bucket refused " + VALUE);
            }
        }
        MainExecutor exec = prepared(new Targeting());
        exec.prepare();
        WorkItemExecution item = exec.getState().getWorkItems().peek();
        AtomicReference<TargetProjection> projection = new AtomicReference<>();

        List<LogRecord> records = recordLogs(List.of(Plan.class),
                () -> projection.set(new Plan(exec).projectionOf(item)));

        TargetProjection.Failed failed = assertInstanceOf(TargetProjection.Failed.class, projection.get());
        assertHidden(failed.message());
        assertNoValueLogged(records, VALUE);
    }

    // ---- 7. dry run ----

    static final class DryRunning extends FakeAction implements IProcessAction {
        final Throwable failure;

        DryRunning(Throwable failure) {
            this.failure = failure;
        }

        @Override
        public List<WorkItem> doProcess(WorkItem item) {
            return List.of(item);
        }

        @Override
        public Optional<List<WorkItem>> dryRun(WorkItem item) {
            throw sneaky(failure);
        }
    }

    @Test
    public void aFailingDryRunHidesTheSecrets() throws Exception {
        WorkItem item = new WorkItem(Files.createFile(card().resolve("IMG_01.JPG")));

        Projection projection = DryRunner.project(item,
                List.of(step(new DryRunning(new IllegalStateException("refused " + VALUE)), secrets())));

        assertHidden(assertInstanceOf(Projection.Failed.class, projection).message());
        Throwable error = assertThrows(Throwable.class, () -> DryRunner.project(item,
                List.of(step(new DryRunning(new AssertionError("refused " + VALUE)), secrets()))));
        assertHidden(error);
    }

    // ---- 8. sample ----

    private record Steps(List<PipelineStep<IInAction>> in, List<PipelineStep<IAnalyzeAction>> analyses,
                         List<PipelineStep<IProcessAction>> processes) implements SampleSteps {
        @Override
        public List<PipelineStep<IInAction>> in(PipelineConfig config) {
            return in;
        }

        @Override
        public List<PipelineStep<IAnalyzeAction>> analyse(PipelineConfig config) {
            return analyses;
        }

        @Override
        public List<PipelineStep<IProcessAction>> process(PipelineConfig config) {
            return processes;
        }
    }

    private static SampleSession session(SampleSteps steps) {
        return new SampleSession(steps, 10, Duration.ofSeconds(10), 5);
    }

    @Test
    public void aFailingSampleListingHidesTheSecrets() throws Exception {
        final class FailingIn extends FakeAction implements IInAction {
            @Override
            public void listFiles(Consumer<WorkItem> consumer) {
                throw new IllegalStateException("login refused for " + VALUE);
            }
        }
        Sample sample = session(new Steps(List.of(step(new FailingIn(), secrets())), List.of(), List.of())).list(null);

        assertHidden(sample.failure().orElseThrow());
    }

    @Test
    public void aFailingSampleAnalysisOrDryRunHidesTheSecrets() throws Exception {
        final class FailingAnalyse extends FakeAction implements IAnalyzeAction {
            @Override
            public void doAnalyze(WorkItem item) {
                if (item.getNameDisplay().equals("IMG_01.JPG")) {
                    throw new IllegalStateException("metadata service refused " + VALUE);
                }
            }
        }
        Path dir = card();
        Steps steps = new Steps(List.of(plain(new DatedIn(dir, 2, null))),
                List.of(step(new FailingAnalyse(), secrets())),
                List.of(step(new DryRunning(new IllegalStateException("refused " + VALUE)), secrets())));

        Sample sample = session(steps).list(null);

        assertEquals(Optional.empty(), sample.failure());
        assertEquals(2, sample.notes().size(), sample.notes().toString());
        for (String note : sample.notes()) {
            assertHidden(note);
        }
        for (SampleItem item : sample.items()) {
            assertHidden(item.error().orElseThrow());
        }
    }

    // ---- 9. a step failing with an error or a checked exception ----

    @Test
    public void anErrorOrACheckedExceptionOfAStepHidesTheSecrets() throws Exception {
        final class Throwing extends Out {
            @Override
            public WriteResult write(WorkItem item, WriteContext context) {
                if (item.getNameDisplay().equals("IMG_01.JPG")) {
                    throw new AssertionError("refused " + VALUE);
                }
                throw sneaky(new IOException("refused " + VALUE));
            }
        }
        MainExecutor exec = run(new DatedIn(card(), 2, null), SecretReferences.Redactor.NONE, new Throwing(),
                secrets());

        exec.run();

        assertEquals(2, exec.getState().getWorkItems().size());
        for (WorkItemExecution item : exec.getState().getWorkItems()) {
            assertHidden(item.getError());
        }
    }

    @Test
    public void anInterruptOrTheJvmFailingIsNeverRedacted() throws Exception {
        SecretReferences.Resolved secrets = secrets();
        InterruptedException interrupted = new InterruptedException(VALUE);
        StackOverflowError overflow = new StackOverflowError(VALUE);

        assertSame(interrupted, Redaction.hidden(secrets, interrupted));
        assertSame(overflow, Redaction.hidden(secrets, overflow));
        assertHidden(Redaction.hidden(secrets, new AssertionError(VALUE)));
    }

    // ---- 11. resume target ----

    @Test
    public void aFailingTargetOfTheResumeProbeHidesTheSecrets() throws Exception {
        final class Targeting extends Out {
            @Override
            public Optional<Path> resolveTarget(WorkItem item) {
                throw new IllegalStateException("bucket refused " + VALUE);
            }
        }
        MainExecutor exec = new MainExecutor(List.of(plain(new DatedIn(card(), 1, null))),
                List.of(plain(new ControlFakes.NoopAnalyze()), step(new Targeting(), secrets())), 1, false, null,
                ControlFakes.registry(Map.of("disk:*", 1000)), new ResumeContext(ResumeMode.DESTINATION, store()));

        exec.prepare();

        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus());
        assertHidden(exec.getState().getFailure());
    }

    @Test
    public void anErrorOfTheResumeProbeHidesTheSecrets() throws Exception {
        final class Targeting extends Out {
            @Override
            public Optional<Path> resolveTarget(WorkItem item) {
                throw new AssertionError("bucket refused " + VALUE);
            }
        }
        MainExecutor exec = new MainExecutor(List.of(plain(new DatedIn(card(), 1, null))),
                List.of(plain(new ControlFakes.NoopAnalyze()), step(new Targeting(), secrets())), 1, false, null,
                ControlFakes.registry(Map.of("disk:*", 1000)), new ResumeContext(ResumeMode.DESTINATION, store()));

        exec.prepare();

        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus());
        assertHidden(exec.getState().getFailure());
    }

    @Test
    public void aFailureTellingWhetherTheTargetDirectoryVariesHidesTheSecrets() throws Exception {
        Path nas = Files.createDirectories(tempDir.resolve("nas"));
        final class Varying extends Out {
            @Override
            public Optional<Path> resolveTarget(WorkItem item) {
                return Optional.of(nas.resolve(item.getNameDisplay())); // its directory is there: at the destination
            }

            @Override
            public Optional<Boolean> targetDirectoryVaries() {
                throw new IllegalStateException("bucket refused " + VALUE);
            }
        }
        MainExecutor exec = new MainExecutor(List.of(plain(new DatedIn(card(), 1, null))),
                List.of(plain(new ControlFakes.NoopAnalyze()), step(new Varying(), secrets())), 1, false, null,
                ControlFakes.registry(Map.of("disk:*", 1000)), new ResumeContext(ResumeMode.DESTINATION, store(),
                com.copybot.engine.resume.DestinationCheck.EVERY_FILE, null));

        exec.prepare();

        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus());
        assertHidden(exec.getState().getFailure());
    }

    // ---- errors of the target check and the plan targets (5, 6) ----

    @Test
    public void anErrorOfTheTargetCheckHidesTheSecrets() throws Exception {
        final class Checking extends Out {
            @Override
            public TargetCheck checkTarget(WorkItem item, boolean compareContent) {
                throw new AssertionError("share refused " + VALUE);
            }
        }
        MainExecutor exec = prepared(new Checking());

        exec.prepare();

        assertHidden(exec.getState().getWorkItems().peek().getError());
    }

    @Test
    public void anErrorTellingTheDisksOfTheTargetCheckHidesTheSecrets() throws Exception {
        final class Touching extends Out {
            @Override
            public Set<Path> touchedPaths(WorkItem item) {
                throw new AssertionError("share refused " + VALUE);
            }
        }
        MainExecutor exec = prepared(new Touching());

        exec.prepare();

        assertHidden(exec.getState().getWorkItems().peek().getError());
    }

    @Test
    public void anErrorOfAPlanTargetIsThrownWithoutTheSecrets() throws Exception {
        final class Targeting extends Out {
            @Override
            public Optional<Path> resolveTarget(WorkItem item) {
                throw new AssertionError("bucket refused " + VALUE);
            }
        }
        MainExecutor exec = prepared(new Targeting());
        exec.prepare();
        WorkItemExecution item = exec.getState().getWorkItems().peek();

        assertHidden(assertThrows(Throwable.class, () -> new Plan(exec).projectionOf(item)));
    }

    // ---- a checked exception thrown sneakily by beforeAll / afterAll (2, 3) ----

    @Test
    public void aCheckedExceptionOfBeforeAllHidesTheSecrets() throws Exception {
        final class Refusing extends Out {
            @Override
            public void beforeAll() {
                throw sneaky(new IOException("cannot log in with " + VALUE));
            }
        }
        MainExecutor exec = run(new DatedIn(card(), 1, null), SecretReferences.Redactor.NONE, new Refusing(),
                secrets());

        exec.run();

        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus());
        assertHidden(exec.getState().getFailure());
    }

    @Test
    public void aCheckedExceptionOfAfterAllHidesTheSecretsAndTheOtherActionsStillEnd() throws Exception {
        AtomicInteger inEnded = new AtomicInteger();
        final class EndingIn extends DatedInWrapper {
            EndingIn(Path dir) {
                super(dir);
            }

            @Override
            public void afterAll() {
                inEnded.incrementAndGet();
            }
        }
        final class Closing extends Out {
            @Override
            public void afterAll() {
                throw sneaky(new IOException("logout refused for " + VALUE));
            }
        }
        MainExecutor exec = run(new EndingIn(card()), SecretReferences.Redactor.NONE, new Closing(), secrets());

        exec.run();

        assertEquals(1, inEnded.get(), "the input action still ended");
        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus());
        assertHidden(exec.getState().getFailure());
    }
}

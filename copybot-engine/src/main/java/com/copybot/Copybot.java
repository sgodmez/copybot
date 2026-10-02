package com.copybot;

import com.copybot.engine.CopybotEngine;
import com.copybot.engine.Execution;
import com.copybot.engine.Plan;
import com.copybot.engine.pipeline.ItemStatus;
import com.copybot.engine.pipeline.PipelineState;
import com.copybot.engine.pipeline.PipelineStatus;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.engine.resume.ItemKey;
import com.copybot.engine.resume.ResumePoint;
import com.copybot.logger.CopybotLogger;
import com.copybot.resources.ResourcesEngine;
import picocli.CommandLine;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@CommandLine.Command(name = "Copybot", version = "0.1", mixinStandardHelpOptions = true)
public class Copybot implements Callable<Integer> {

    /** SUCCESS (skipped items are not errors), or a dry run prepared without error. */
    public static final int EXIT_SUCCESS = 0;
    /** The execution ended in ERROR: items in error, cursor not written, listing failure. */
    public static final int EXIT_FAILED = 1;
    /**
     * Fatal: config or pipeline missing or invalid, preparation in ERROR (on every path, the default run
     * included), unknown --from-file, a plugin that cannot be loaded. Invalid options end with the same
     * code (picocli's usage error code).
     */
    public static final int EXIT_FATAL = 2;
    /** CANCELLED (Ctrl+C). */
    public static final int EXIT_CANCELLED = 130;

    /** Grace period the Ctrl+C hook gives the cancelled execution to release its files. */
    private static final long CANCEL_AWAIT_SECONDS = 5;

    private static final CopybotLogger LOG = CopybotLogger.getLogger(Copybot.class);

    @CommandLine.Option(names = {"-c", "--config-file"}, description = "Configuration file")
    private Path configPath;

    @CommandLine.Option(names = {"-p", "--pipeline"}, description = "Pipeline file", required = true)
    private Path pipelinePath;

    @CommandLine.Option(names = {"-dr", "--dry-run"}, description = "Dry run")
    private boolean isDryRun;

    @CommandLine.Option(names = {"--debug"}, description = "Debug: print the stacktraces (the exit code is unchanged)")
    private boolean isDebug;

    @CommandLine.ArgGroup(exclusive = true)
    private ResumeOverride resumeOverride;

    static class ResumeOverride {
        @CommandLine.Option(names = "--from-file", description = "Resume from this file (included)")
        String fromFile;

        @CommandLine.Option(names = "--from-date", description = "Resume from this day (included), yyyy-MM-dd")
        LocalDate fromDate;

        @CommandLine.Option(names = "--all", description = "Ignore the resume point: process every file")
        boolean all;
    }

    /** Receives each execution as soon as it has started (visible for tests: programmatic cancellation). */
    private final Consumer<Execution> onExecutionStarted;

    /** The Ctrl+C hook registered while an execution runs, null otherwise. */
    private volatile Thread cancelHook;

    public Copybot() {
        this(execution -> {
        });
    }

    Copybot(Consumer<Execution> onExecutionStarted) {
        this.onExecutionStarted = onExecutionStarted;
    }

    @Override
    public Integer call() {
        try (CopybotEngine engine = CopybotEngine.create(Optional.ofNullable(configPath))) {
            return doRun(engine);
        } catch (InterruptedException e) {
            // the engine is closed by the try-with-resources, which cancels the execution
            Thread.currentThread().interrupt();
            System.out.println("Cancelled !");
            return EXIT_CANCELLED;
        } catch (RuntimeException | ServiceConfigurationError | LinkageError e) {
            // the Errors: a plugin that cannot be loaded (bad provider, missing or incompatible class)
            System.err.println(message(e));
            printStackTraceIfDebug(e);
            return EXIT_FATAL;
        }
    }

    /** The exit code is decided on the final status (and the preparation marker), never on the failure. */
    private int doRun(CopybotEngine engine) throws InterruptedException {
        Execution execution;
        // a dry run prints the warnings with the plan (stdout), a real run on stderr as soon as they are known
        Consumer<PipelineState> watcher = isDryRun ? null : warningsOnStderrOnce();
        if (isDryRun || resumeOverride != null) {
            Plan plan = engine.prepare(pipelinePath, watcher);
            PipelineStatus prepared = plan.getState().getStatus();
            if (prepared == PipelineStatus.CANCELLED) {
                System.out.println("Cancelled !");
                return EXIT_CANCELLED;
            }
            if (prepared != PipelineStatus.PREPARED) {
                return failed(plan.getState());
            }
            ResumePoint override = resolveOverride(plan);
            if (isDryRun) {
                plan.preview(override);
                PlanPrinter.print(plan, override, System.out);
                return EXIT_SUCCESS;
            }
            execution = engine.execute(plan, override);
        } else {
            execution = engine.run(pipelinePath, watcher);
        }
        PipelineStatus status = awaitCancellingOnCtrlC(execution);
        if (status == PipelineStatus.CANCELLED) {
            System.out.println("Cancelled !");
            return EXIT_CANCELLED;
        }
        if (status == PipelineStatus.ERROR && execution.getState().isPreparationFailed()) {
            return failed(execution.getState());
        }
        System.out.println("Done !");
        report(execution.getState());
        return status == PipelineStatus.SUCCESS ? EXIT_SUCCESS : EXIT_FAILED;
    }

    /**
     * A pipeline that ended ERROR before being executed: {@value #EXIT_FATAL} when its preparation failed,
     * {@value #EXIT_FAILED} for a listing failure (a run failure, like items in error).
     */
    private int failed(PipelineState state) {
        report(state);
        if (!state.isPreparationFailed()) {
            return EXIT_FAILED;
        }
        System.err.println(ResourcesEngine.getString("pipeline.prepare-failed"));
        return EXIT_FATAL;
    }

    /**
     * A watcher printing the configuration warnings of the steps on stderr, once, as soon as the steps are
     * resolved (spec safe-write §5): the first notifications come before, the terminal one never misses them.
     */
    private static Consumer<PipelineState> warningsOnStderrOnce() {
        AtomicBoolean printed = new AtomicBoolean();
        return state -> {
            List<String> warnings = state.getWarnings();
            if (!warnings.isEmpty() && printed.compareAndSet(false, true)) {
                warnings.forEach(w -> System.err.println(ResourcesEngine.getString("cli.warning", w)));
            }
        };
    }

    /**
     * Waits for the execution. Meanwhile a JVM shutdown (Ctrl+C) cancels it and gives it a few seconds
     * to release its files; the hook is removed once the execution is over. A preparation run by
     * {@link CopybotEngine#prepare} (blocking, nothing written) is not covered: Ctrl+C simply stops it
     * with the JVM.
     */
    private PipelineStatus awaitCancellingOnCtrlC(Execution execution) throws InterruptedException {
        Thread hook = new Thread(() -> cancelAndWait(execution), "copybot-cancel-on-exit");
        Runtime.getRuntime().addShutdownHook(hook);
        cancelHook = hook;
        try {
            onExecutionStarted.accept(execution);
            return execution.await();
        } finally {
            cancelHook = null;
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException e) {
                // the JVM is already shutting down: the hook is running and must be left alone
            }
        }
    }

    /** The Ctrl+C hook: cancels the execution and waits for it, {@value #CANCEL_AWAIT_SECONDS} s at most. */
    static void cancelAndWait(Execution execution) {
        execution.cancel();
        try {
            execution.await(CANCEL_AWAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // visible for tests: the Ctrl+C hook registered while an execution runs, null otherwise
    Thread cancelHook() {
        return cancelHook;
    }

    /** Reports on stderr the pipeline failure (listing, cursor write...) and every item in error. */
    private void report(PipelineState state) {
        if (state.getFailure() != null) {
            System.err.println(message(state.getFailure()));
            printStackTraceIfDebug(state.getFailure());
        }
        for (WorkItemExecution item : state.getWorkItems()) {
            if (item.getStatus() == ItemStatus.ERROR) {
                System.err.println(ResourcesEngine.getString("cli.item.error", name(item), message(item.getError())));
            }
        }
    }

    private void printStackTraceIfDebug(Throwable t) {
        if (isDebug) {
            t.printStackTrace();
        }
    }

    /** The item name as listed (the resume key name), even when a later step replaced the work item. */
    static String name(WorkItemExecution item) {
        return item.getResumeKey().map(ItemKey::name).orElseGet(() -> item.getWorkItem().getNameDisplay());
    }

    static String message(Throwable t) {
        if (t == null) {
            return "";
        }
        return t.getMessage() != null ? t.getMessage() : t.getClass().getName();
    }

    private ResumePoint resolveOverride(Plan plan) {
        if (resumeOverride == null) {
            return null;
        }
        if (resumeOverride.all) {
            return ResumePoint.all();
        }
        if (resumeOverride.fromFile != null) {
            return plan.fromFile(resumeOverride.fromFile);
        }
        return Plan.fromDate(resumeOverride.fromDate);
    }


    public static void main(String... args) {
        int exitCode = doMain(args);
        System.exit(exitCode);
    }

    public static int doMain(String... args) {
        return new CommandLine(new Copybot()).execute(args);
    }
}

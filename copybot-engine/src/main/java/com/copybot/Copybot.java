package com.copybot;

import com.copybot.config.SecretStore;
import com.copybot.engine.CopybotEngine;
import com.copybot.engine.Execution;
import com.copybot.engine.Plan;
import com.copybot.engine.pipeline.ItemStatus;
import com.copybot.engine.pipeline.PipelineState;
import com.copybot.engine.pipeline.PipelineStatus;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.engine.plugin.ConfirmationHandler;
import com.copybot.engine.resume.ItemKey;
import com.copybot.engine.resume.ResumePoint;
import com.copybot.logger.CopybotLogger;
import com.copybot.resources.ResourcesEngine;
import picocli.CommandLine;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

@CommandLine.Command(name = "Copybot", version = "0.1", mixinStandardHelpOptions = true,
        subcommands = SecretCommand.class)
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

    /**
     * Grace period the Ctrl+C hook gives the cancelled execution to release its files (its "cancelled" end-of-run
     * actions get their own time after it).
     */
    private static final long CANCEL_AWAIT_SECONDS = 5;

    private static final CopybotLogger LOG = CopybotLogger.getLogger(Copybot.class);

    @CommandLine.Option(names = {"-c", "--config-file"}, description = "Configuration file")
    private Path configPath;

    // required to run a pipeline, checked by call(): picocli would require it of the secret subcommand too
    @CommandLine.Option(names = {"-p", "--pipeline"}, description = "Pipeline file (required to run a pipeline)")
    private Path pipelinePath;

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

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

    /** The store of the secret subcommand. */
    private final Supplier<SecretStore> secrets;

    public Copybot() {
        this(execution -> {
        });
    }

    Copybot(Consumer<Execution> onExecutionStarted) {
        this(onExecutionStarted, SecretStore::forCurrentUser);
    }

    private Copybot(Consumer<Execution> onExecutionStarted, Supplier<SecretStore> secrets) {
        this.onExecutionStarted = onExecutionStarted;
        this.secrets = secrets;
    }

    // visible for tests: the secret subcommand on this store
    static Copybot withSecrets(SecretStore store) {
        return new Copybot(execution -> {
        }, () -> store);
    }

    SecretStore secretStore() {
        return secrets.get();
    }

    @Override
    public Integer call() {
        if (pipelinePath == null) {
            throw new CommandLine.ParameterException(spec.commandLine(),
                    "Missing required option: '--pipeline=<pipelinePath>'");
        }
        // nobody to ask on the command line: the plugins get yes, and the question is shown (decision of the spec
        // remote §2); for this run only (a test runs several in the same JVM)
        ConfirmationHandler previous = CopybotEngine.setConfirmationHandler(Copybot::yesWithWarning);
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
        } finally {
            CopybotEngine.setConfirmationHandler(previous);
        }
    }

    /** The answer of the command line to a question of a plugin: yes, the question shown on stderr. */
    private static CompletableFuture<Boolean> yesWithWarning(String question) {
        System.err.println(ResourcesEngine.getString("cli.confirmation", question));
        return CompletableFuture.completedFuture(true);
    }

    /** The exit code is decided on the final status (and the preparation marker), never on the failure. */
    private int doRun(CopybotEngine engine) throws InterruptedException {
        Execution execution;
        // a dry run prints the warnings with the plan (stdout), a real run on stderr as soon as they are known
        Consumer<PipelineState> watcher = isDryRun ? null : warningsOnStderr();
        if (isDryRun || resumeOverride != null) {
            // --all and --from-date are known before the listing: a chosen point, like a cursor there (spec
            // manual-point §1); --from-file names a listed file: applied once the listing found its key
            // a dry run is a plan to look at; the other ones are executed at once: a run launched to copy, whose
            // failed or cancelled preparation ends with the end-of-run actions
            Plan plan = isDryRun ? engine.prepare(pipelinePath, watcher, null, chosenPoint())
                    : engine.prepareForCopy(pipelinePath, watcher, chosenPoint());
            PipelineStatus prepared = plan.getState().getStatus();
            if (prepared == PipelineStatus.CANCELLED) {
                System.out.println("Cancelled !");
                return EXIT_CANCELLED;
            }
            if (prepared != PipelineStatus.PREPARED) {
                return failed(plan.getState());
            }
            ResumePoint override = fromFileOverride(plan);
            if (isDryRun) {
                if (override != null) {
                    plan.preview(override);
                    // the files the override selects again although the cursor skipped them at the listing: analysed
                    // like the others before being printed (nothing to do otherwise)
                    engine.analyse(plan);
                }
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
     * A watcher printing each warning on stderr once, as soon as it is known: the configuration warnings of the
     * steps once they are resolved (spec safe-write §5), a failed post-processing at the end (spec post-process).
     * The warnings only grow: the ones past the count printed are new. The terminal notification never misses them.
     */
    private static Consumer<PipelineState> warningsOnStderr() {
        AtomicInteger printed = new AtomicInteger();
        return state -> {
            List<String> warnings = state.getWarnings();
            synchronized (printed) {
                for (int i = printed.get(); i < warnings.size(); i++) {
                    System.err.println(ResourcesEngine.getString("cli.warning", warnings.get(i)));
                }
                printed.set(Math.max(printed.get(), warnings.size()));
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

    /**
     * The Ctrl+C hook: cancels the execution and waits for it, {@value #CANCEL_AWAIT_SECONDS} s at most, then, when it
     * has "cancelled" end-of-run actions, as long as they may still take ({@link Execution#cancelledRunEndWait()}).
     */
    static void cancelAndWait(Execution execution) {
        execution.cancel();
        try {
            if (execution.await(CANCEL_AWAIT_SECONDS, TimeUnit.SECONDS)) {
                return;
            }
            Duration runEnd = execution.cancelledRunEndWait();
            if (runEnd.isZero()) {
                return;
            }
            System.err.println(ResourcesEngine.getString("cli.cancel.run-end-wait", String.valueOf(runEnd.toSeconds())));
            execution.await(runEnd.toMillis(), TimeUnit.MILLISECONDS);
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

    /** --all or --from-date: a point known before the listing; null otherwise. */
    private ResumePoint chosenPoint() {
        if (resumeOverride == null || resumeOverride.fromFile != null) {
            return null;
        }
        return resumeOverride.all ? ResumePoint.all() : Plan.fromDate(resumeOverride.fromDate);
    }

    /** --from-file: the key of the listed file of that name; null otherwise. */
    private ResumePoint fromFileOverride(Plan plan) {
        return resumeOverride == null || resumeOverride.fromFile == null ? null : plan.fromFile(resumeOverride.fromFile);
    }


    public static void main(String... args) {
        int exitCode = doMain(args);
        System.exit(exitCode);
    }

    public static int doMain(String... args) {
        return new CommandLine(new Copybot()).execute(args);
    }
}

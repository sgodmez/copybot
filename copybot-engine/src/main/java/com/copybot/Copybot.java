package com.copybot;

import com.copybot.engine.CopybotEngine;
import com.copybot.engine.Plan;
import com.copybot.engine.pipeline.ItemStatus;
import com.copybot.engine.pipeline.PipelineState;
import com.copybot.engine.pipeline.PipelineStatus;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.engine.resume.ItemKey;
import com.copybot.engine.resume.ResumePoint;
import com.copybot.exception.CopybotException;
import com.copybot.logger.CopybotLogger;
import picocli.CommandLine;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

@CommandLine.Command(name = "Copybot", version = "0.1", mixinStandardHelpOptions = true)
public class Copybot implements Runnable {

    private static final CopybotLogger LOG = CopybotLogger.getLogger(Copybot.class);

    @CommandLine.Option(names = {"-c", "--config-file"}, description = "Configuration file")
    private Path configPath;

    @CommandLine.Option(names = {"-p", "--pipeline"}, description = "Pipeline file", required = true)
    private Path pipelinePath;

    @CommandLine.Option(names = {"-dr", "--dry-run"}, description = "Dry run")
    private boolean isDryRun;

    @CommandLine.Option(names = {"--debug"}, description = "Debug")
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

    @Override
    public void run() {
        try {
            doRun();
        } catch (Exception e) {
            if (isDebug) {
                throw CopybotException.wrapIfNeeded(e);
            } else {
                System.err.println(e.getMessage());
            }
        }
    }

    public void doRun() {
        CopybotEngine.init(Optional.ofNullable(configPath));

        if (!pipelinePath.toFile().canRead()) {
            throw CopybotException.ofResource("pipeline.not-found", pipelinePath);
        }

        if (isDryRun || resumeOverride != null) {
            runWithPlan();
        } else {
            // the watcher always receives a final notification once the run is over
            AtomicReference<PipelineState> lastState = new AtomicReference<>();
            CopybotEngine.run(pipelinePath, lastState::set);
            if (awaitEngine() && lastState.get() != null) {
                report(lastState.get());
            }
        }
    }

    private void runWithPlan() {
        Plan plan = CopybotEngine.prepare(pipelinePath, null);
        if (plan.getState().getStatus() != PipelineStatus.PREPARED) {
            if (plan.getState().getFailure() != null) {
                System.err.println(message(plan.getState().getFailure()));
            }
            throw CopybotException.ofResource("pipeline.prepare-failed");
        }
        ResumePoint override = resolveOverride(plan);
        if (isDryRun) {
            plan.preview(override);
            PlanPrinter.print(plan, override, System.out);
            return;
        }
        CopybotEngine.execute(plan, override);
        if (awaitEngine()) {
            report(plan.getState());
        }
    }

    /** Reports on stderr the pipeline failure (listing, cursor write...) and every item in error. */
    private static void report(PipelineState state) {
        if (state.getFailure() != null) {
            System.err.println(message(state.getFailure()));
        }
        for (WorkItemExecution item : state.getWorkItems()) {
            if (item.getStatus() == ItemStatus.ERROR) {
                System.err.println("ERROR " + name(item) + "  " + message(item.getError()));
            }
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

    /** @return false when the wait was cancelled */
    private boolean awaitEngine() {
        try {
            CopybotEngine.waitForCompletion();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            CopybotEngine.destroy();
            System.out.println("Cancelled !");
            return false;
        } catch (ExecutionException e) {
            // The pipeline itself blew up (e.g. an unresolvable step): that is a failure, not a
            // cancellation, and it must reach the exit code instead of being reported as "Cancelled".
            CopybotEngine.destroy();
            throw CopybotException.wrapIfNeeded(e.getCause() == null ? e : e.getCause());
        }
        System.out.println("Done !");
        return true;
    }


    public static void main(String... args) {
        int exitCode = doMain(args);
        System.exit(exitCode);
    }

    public static int doMain(String... args) {
        return new CommandLine(new Copybot()).execute(args);
    }
}

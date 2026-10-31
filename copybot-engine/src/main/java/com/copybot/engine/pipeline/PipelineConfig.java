package com.copybot.engine.pipeline;

import com.copybot.engine.resume.ResumeConfig;
import com.copybot.engine.resume.ResumeMode;
import java.util.List;
import java.util.Objects;

public record PipelineConfig(

        List<PipelineStepConfig> inSteps,
        List<PipelineStepConfig> analyseSteps,
        List<PipelineStepConfig> actionSteps,
        PipelineStepConfig outStep,

        /**
         * How the pipeline is run (spec execution-mode §1): plan then confirmation, plan then copy, or each file
         * processed as soon as it is listed. Absent: plan.
         */
        ExecutionMode execution,

        /**
         * Resume detection. Absent: no resume (every listed file is processed, no state file).
         */
        ResumeConfig resume,

        /**
         * How far the plan checks the targets that already exist (spec conflict-check §1). Absent: quick.
         */
        ConflictCheck conflictCheck,

        /**
         * The actions run at the end of a run launched to copy, in order (spec run-end). Absent: none; never null.
         * A former "postProcess" block is read as its first entry ({@link RunEndMigration}).
         */
        List<RunEndStepConfig> runEnd,

        /**
         * The rules that attach companion files to a main file at the listing. Absent: no grouping.
         */
        GroupingConfig grouping,

        /**
         * The unfolding of the archives at the listing. Absent: no unfolding.
         */
        ArchivesConfig archives,

        /**
         * The secondary outputs, run in order after the analyses and before the main branch (spec secondary-outputs
         * §2). Absent: none; never null.
         */
        List<SecondaryOutputConfig> secondaryOutputs

        ) {

    public PipelineConfig {
        runEnd = runEnd == null ? List.of() : runEnd.stream().filter(Objects::nonNull).toList();
        secondaryOutputs = secondaryOutputs == null ? List.of()
                : secondaryOutputs.stream().filter(Objects::nonNull).toList();
    }

    /** Without secondary outputs. */
    public PipelineConfig(List<PipelineStepConfig> inSteps, List<PipelineStepConfig> analyseSteps,
                          List<PipelineStepConfig> actionSteps, PipelineStepConfig outStep, ExecutionMode execution,
                          ResumeConfig resume, ConflictCheck conflictCheck, List<RunEndStepConfig> runEnd,
                          GroupingConfig grouping, ArchivesConfig archives) {
        this(inSteps, analyseSteps, actionSteps, outStep, execution, resume, conflictCheck, runEnd, grouping,
                archives, null);
    }

    /** Without grouping nor archives. */
    public PipelineConfig(List<PipelineStepConfig> inSteps, List<PipelineStepConfig> analyseSteps,
                          List<PipelineStepConfig> actionSteps, PipelineStepConfig outStep, ExecutionMode execution,
                          ResumeConfig resume, ConflictCheck conflictCheck, List<RunEndStepConfig> runEnd) {
        this(inSteps, analyseSteps, actionSteps, outStep, execution, resume, conflictCheck, runEnd, null, null);
    }

    /** Without conflictCheck (quick). */
    public PipelineConfig(List<PipelineStepConfig> inSteps, List<PipelineStepConfig> analyseSteps,
                          List<PipelineStepConfig> actionSteps, PipelineStepConfig outStep, ExecutionMode execution,
                          ResumeConfig resume) {
        this(inSteps, analyseSteps, actionSteps, outStep, execution, resume, null);
    }

    /** Without run-end actions. */
    public PipelineConfig(List<PipelineStepConfig> inSteps, List<PipelineStepConfig> analyseSteps,
                          List<PipelineStepConfig> actionSteps, PipelineStepConfig outStep, ExecutionMode execution,
                          ResumeConfig resume, ConflictCheck conflictCheck) {
        this(inSteps, analyseSteps, actionSteps, outStep, execution, resume, conflictCheck, null);
    }

    public ResumeMode resumeMode() {
        return resume == null ? ResumeMode.NONE : resume.effectiveMode();
    }

    public ExecutionMode executionMode() {
        return execution == null ? ExecutionMode.PLAN : execution;
    }

    public ConflictCheck conflictCheckMode() {
        return conflictCheck == null ? ConflictCheck.QUICK : conflictCheck;
    }
}

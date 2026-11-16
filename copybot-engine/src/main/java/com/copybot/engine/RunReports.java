package com.copybot.engine;

import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.plugin.api.action.ItemOutcome;
import com.copybot.plugin.api.action.RunCounts;
import com.copybot.plugin.api.action.RunItem;
import com.copybot.plugin.api.action.RunOutcome;
import com.copybot.plugin.api.action.RunReport;
import com.copybot.plugin.api.action.WrittenFile;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Builds the report of a run handed to its end-of-run actions (spec run-end, "Bilan"). */
final class RunReports {

    private RunReports() {
    }

    /**
     * @param pipelinePath       the pipeline file, null when unknown (its name is then empty)
     * @param items              every item of the run: the ones it did not process (skipped by the resume point,
     *                           ignored in the plan, not reached) are left out
     * @param createdDirectories sorted, parents first
     * @param failure            the failure of the run, kept only for a run in ERROR
     */
    static RunReport of(Path pipelinePath, RunOutcome outcome, Instant started, Instant copyStarted, Instant ended,
                        Collection<WorkItemExecution> items, List<WrittenFile> writtenFiles,
                        Collection<Path> createdDirectories, List<String> warnings, Throwable failure, String runId) {
        List<RunItem> processed = new ArrayList<>();
        int written = 0;
        int skipped = 0;
        int errors = 0;
        int filtered = 0;
        for (WorkItemExecution exec : items) {
            Optional<RunItem> item = item(exec);
            if (item.isEmpty()) {
                continue;
            }
            processed.add(item.get());
            switch (item.get().outcome()) {
                case WRITTEN -> written++;
                case SKIPPED -> skipped++;
                case ERROR -> errors++;
                case FILTERED -> filtered++;
            }
        }
        return new RunReport(pipelineName(pipelinePath), pipelinePath, outcome, started, copyStarted, ended,
                new RunCounts(written, skipped, errors, filtered), processed, writtenFiles,
                createdDirectories.stream().sorted().toList(), warnings,
                outcome == RunOutcome.ERROR && failure != null ? message(failure) : null, runId, Map.of());
    }

    /** The report handed to one action: the warnings of the run and what the previous actions published, so far. */
    static RunReport with(RunReport report, List<String> warnings, Map<String, String> outputs) {
        return new RunReport(report.pipelineName(), report.pipelinePath(), report.outcome(), report.started(),
                report.copyStarted(), report.ended(), report.counts(), report.items(), report.writtenFiles(),
                report.createdDirectories(), warnings, report.failure(), report.runId(), outputs);
    }

    /** The pipeline file's name without its extension; empty when unknown. */
    static String pipelineName(Path pipelinePath) {
        if (pipelinePath == null || pipelinePath.getFileName() == null) {
            return "";
        }
        String name = pipelinePath.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** The item as the report shows it; empty for an item this run did not process. */
    private static Optional<RunItem> item(WorkItemExecution exec) {
        if (exec.isIgnored() || exec.isSkippedByResumePoint()) {
            return Optional.empty();
        }
        ItemOutcome outcome;
        String reason = null;
        switch (exec.getStatus()) {
            case ERROR -> {
                outcome = ItemOutcome.ERROR;
                reason = exec.getError() == null ? null : message(exec.getError());
            }
            case DONE -> outcome = exec.getEnding() == WorkItemExecution.Ending.FILTERED
                    ? ItemOutcome.FILTERED : ItemOutcome.WRITTEN;
            case SKIPPED -> {
                if (exec.getEnding() != WorkItemExecution.Ending.SKIPPED) {
                    return Optional.empty();
                }
                outcome = ItemOutcome.SKIPPED;
                reason = exec.getSkipReason();
            }
            default -> {
                return Optional.empty(); // not processed: stopped by a cancel, or never reached
            }
        }
        return Optional.of(new RunItem(exec.getWorkItem().getNameDisplay(), exec.getWorkItem().getSourceLocationDisplay(),
                outcome, outcome == ItemOutcome.SKIPPED ? exec.getSkippedTarget() : exec.getWrittenTarget(), reason));
    }

    private static String message(Throwable failure) {
        return failure.getMessage() != null ? failure.getMessage() : failure.getClass().getName();
    }
}

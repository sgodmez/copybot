package com.copybot.engine;

import com.copybot.engine.pipeline.PipelineState;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.engine.resume.ItemKey;
import com.copybot.engine.resume.ResumePoint;
import com.copybot.engine.resume.ResumeProposal;
import com.copybot.exception.CopybotException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/** A prepared pipeline: what would be imported, and from where, before anything is written. */
public final class Plan {

    private final MainExecutor executor;

    Plan(MainExecutor executor) {
        this.executor = executor;
    }

    MainExecutor getExecutor() {
        return executor;
    }

    public PipelineState getState() {
        return executor.getState();
    }

    public ResumeProposal getProposal() {
        return executor.getProposal();
    }

    /** Listed items in resume order (items without date last). */
    public List<WorkItemExecution> getOrderedItems() {
        return executor.getOrderedItems();
    }

    /** Re-applies a manual resume point to the item statuses, without executing anything (dry-run display). */
    public void preview(ResumePoint override) {
        executor.applyOverride(override);
    }

    /** Resume from this listed file (its name when it was listed), included. */
    public ResumePoint fromFile(String fileName) {
        return getOrderedItems().stream()
                .map(WorkItemExecution::getResumeKey)
                .flatMap(Optional::stream)
                .filter(key -> fileName.equals(key.name()))
                .findFirst()
                .map(ResumePoint::from)
                .orElseThrow(() -> CopybotException.ofResource("resume.from-file.not-found", fileName));
    }

    /** Resume from the start of this day (system time zone), included. */
    public static ResumePoint fromDate(LocalDate date) {
        return ResumePoint.from(new ItemKey(date.atStartOfDay(ZoneId.systemDefault()).toInstant(), ""));
    }
}

package com.copybot.engine.pipeline;

import com.copybot.engine.Projection;
import com.copybot.engine.resume.ItemKey;
import com.copybot.plugin.api.action.TargetCheck;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkStatus;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class WorkItemExecution {

    /**
     * How the steps of the copy ended an item, which {@link ItemStatus} alone does not tell (SKIPPED also covers the
     * resume point, DONE also covers a filtered item): for the run report of the end-of-run actions.
     */
    public enum Ending {
        /** DONE, written by the out step (or by the process steps of a pipeline without out step). */
        WRITTEN,
        /** SKIPPED by the out step (e.g. already at the destination). */
        SKIPPED,
        /** DONE, filtered out by a process step: nothing written. */
        FILTERED
    }

    private volatile WorkItem wi;
    private volatile WorkStatus ws;

    private final List<PipelineStep<?>> pipelineSteps;

    private volatile ItemStatus status = ItemStatus.PENDING;
    private volatile int currentStepIndex = -1;
    private volatile Set<String> waitingFor = Set.of();
    private volatile Throwable error;
    private volatile String skipReason;
    /** How the steps of the copy ended it, null otherwise (not ended, failed, skipped by the resume point...). */
    private volatile Ending ending;
    /** Where the out step wrote it (WRITTEN), or the existing target that made it skip it (SKIPPED); null when it cannot tell. */
    private volatile Path writtenTarget;
    /** SKIPPED because the resume point does not select it (its reason is the resume point, shown once by the UI). */
    private volatile boolean skippedByResumePoint;
    /** What the process steps would produce, by their dry run; set at the end of the preparation (spec pattern-helper §4.3). */
    private volatile Projection projection;
    /** What the out step would find at the target, checked by the plan (spec conflict-check §4); null when not checked. */
    private volatile TargetCheck targetCheck;
    private volatile boolean targetCheckPending;

    /**
     * Resume ordering key, frozen at the preparation barrier: a later step may replace the work item
     * (new name, other metadata) but the item keeps the place it had when the resume point was applied.
     * null when the item has no date or when no resume preparation took place.
     */
    private volatile ItemKey resumeKey;
    /** True once the resume key has been frozen, possibly to null (an item without date). */
    private volatile boolean resumeKeyFrozen;

    /** The execution this one was forked from by a process step, null for a listed item. */
    private volatile WorkItemExecution parent;
    private volatile boolean forkFailed;
    /** True once the preparation is over for this item: it reached the preparation barrier or failed before it. */
    private volatile boolean prepared;
    /** The steps before the barrier did not run for it: skipped at the listing, or left to the destination probe. */
    private volatile boolean analysisDeferred;
    /** Left out of this run by the user (SKIPPED): a resume point no longer selects it. */
    private volatile boolean ignored;
    /** The local file this item was created from, null for a remote one. */
    private final Path listedPath;
    /** The item each step that began for this item was handed, by step index: what its afterItem gets. */
    private final Map<Integer, WorkItem> handed = new ConcurrentSkipListMap<>();
    /** This item, plus the items forked from it not finished yet. */
    private final AtomicInteger unfinished = new AtomicInteger(1);
    private final AtomicBoolean finished = new AtomicBoolean();

    public WorkItemExecution(WorkItem wi, List<PipelineStep<?>> pipelineSteps) {
        this.wi = wi;
        this.pipelineSteps = List.copyOf(pipelineSteps);
        this.listedPath = wi.isLocal() ? wi.getLocalLocation() : null;
    }

    /**
     * The local file this item was listed from, kept when a step replaces the work item; for an item forked by
     * a process step, the one of the item it was forked from. Empty for an item that is not a local file.
     */
    public Optional<Path> getListedPath() {
        return parent != null ? parent.getListedPath() : Optional.ofNullable(listedPath);
    }

    public void setParent(WorkItemExecution parent) {
        this.parent = parent;
    }

    /** Called when a fork of this item failed: the item itself is not fully imported, whatever its own status. */
    public void markForkFailed() {
        this.forkFailed = true;
    }

    public boolean hasFailedFork() {
        return forkFailed;
    }

    /** Marks this execution and every ancestor as having a failed fork (no-op without parent). */
    public void propagateFailureToAncestors() {
        for (WorkItemExecution p = parent; p != null; p = p.parent) {
            p.markForkFailed();
        }
    }

    public WorkItem getWorkItem() {
        return wi;
    }

    /** The step {@code stepIndex} begins for this item, handed {@code item}. */
    public void stepBegan(int stepIndex, WorkItem item) {
        handed.put(stepIndex, item);
    }

    /** The items the steps that began for this item were handed, by step index (ascending). */
    public Map<Integer, WorkItem> handedItems() {
        return handed;
    }

    /** An item forked from this one is about to run: this one is not finished until it is. */
    public void forkStarted() {
        unfinished.incrementAndGet();
    }

    /**
     * This item is finished in this run (calls after the first are ignored). Returns the executions now finished
     * with every item forked from them: this one, then the ancestors it was the last unfinished fork of.
     */
    public List<WorkItemExecution> finish() {
        if (!finished.compareAndSet(false, true)) {
            return List.of();
        }
        List<WorkItemExecution> done = new ArrayList<>();
        for (WorkItemExecution e = this; e != null && e.unfinished.decrementAndGet() == 0; e = e.parent) {
            done.add(e);
        }
        return done;
    }

    /** A process step may transform the item (e.g. transcoding produces a new file). */
    public void replaceWorkItem(WorkItem newWorkItem) {
        this.wi = newWorkItem;
    }

    public Optional<ItemKey> getResumeKey() {
        return Optional.ofNullable(resumeKey);
    }

    /**
     * Freezes the resume ordering key (called by {@link com.copybot.engine.resume.ResumeResolver#order}
     * at the preparation barrier). Only the first call counts, later calls are ignored: the item keeps the
     * place it had when the resume point was applied. null freezes "no key".
     */
    public synchronized void setResumeKey(ItemKey resumeKey) {
        if (resumeKeyFrozen) {
            return;
        }
        this.resumeKey = resumeKey;
        this.resumeKeyFrozen = true;
    }

    public List<PipelineStep<?>> getPipelineSteps() {
        return pipelineSteps;
    }

    public ItemStatus getStatus() {
        return status;
    }

    public int getCurrentStepIndex() {
        return currentStepIndex;
    }

    public Set<String> getWaitingFor() {
        return waitingFor;
    }

    public Throwable getError() {
        return error;
    }

    public void setWaitingResources(int stepIndex, Set<String> resources) {
        this.currentStepIndex = stepIndex;
        this.waitingFor = Set.copyOf(resources);
        this.status = ItemStatus.WAITING_RESOURCES;
    }

    public void setRunning(int stepIndex) {
        this.currentStepIndex = stepIndex;
        this.waitingFor = Set.of();
        this.ws = null; // the progress of the previous step is over
        this.status = ItemStatus.RUNNING;
    }

    public void setDone() {
        this.ending = null;
        this.waitingFor = Set.of();
        this.status = ItemStatus.DONE;
    }

    /** DONE, written by the out step at this target (null when it cannot tell, or without out step). */
    public void setWritten(Path target) {
        // how it ended first: a reader seeing the status sees it too
        this.writtenTarget = target;
        this.ending = Ending.WRITTEN;
        this.waitingFor = Set.of();
        this.status = ItemStatus.DONE;
    }

    /** DONE, filtered out by a process step. */
    public void setFiltered() {
        this.ending = Ending.FILTERED; // before the status, like setWritten
        this.waitingFor = Set.of();
        this.status = ItemStatus.DONE;
    }

    /**
     * SKIPPED by the out step (e.g. already at the destination), with the reason shown to the user and the existing
     * target that made it skip the item (null when it cannot tell).
     */
    public void setSkippedByOutput(String reason, Path target) {
        this.skipReason = reason;
        this.writtenTarget = target;
        this.skippedByResumePoint = false;
        this.ending = Ending.SKIPPED; // before the status, like setWritten
        this.waitingFor = Set.of();
        this.status = ItemStatus.SKIPPED;
    }

    /** How the steps of the copy ended this item; null when they did not (see {@link Ending}). */
    public Ending getEnding() {
        return ending;
    }

    /** Where the out step wrote it, null unless {@link Ending#WRITTEN} (or when the out step cannot tell). */
    public Path getWrittenTarget() {
        return ending == Ending.WRITTEN ? writtenTarget : null;
    }

    /** The existing target that made the out step skip it, null unless {@link Ending#SKIPPED} (or when it cannot tell). */
    public Path getSkippedTarget() {
        return ending == Ending.SKIPPED ? writtenTarget : null;
    }

    public void setError(Throwable error) {
        this.error = error;
        this.ending = null;
        this.waitingFor = Set.of();
        this.status = ItemStatus.ERROR;
    }

    /** Not selected by the resume point, or skipped by the out step (e.g. already at the destination); the reason is shown to the user. */
    public void setSkipped(String reason) {
        this.skipReason = reason;
        this.skippedByResumePoint = false;
        this.ending = null;
        this.waitingFor = Set.of();
        this.status = ItemStatus.SKIPPED;
    }

    /** SKIPPED because the resume point in force does not select it (not by the out step nor by the user). */
    public void setSkippedByResumePoint(String reason) {
        setSkipped(reason);
        this.skippedByResumePoint = true;
    }

    public boolean isSkippedByResumePoint() {
        return status == ItemStatus.SKIPPED && skippedByResumePoint;
    }

    public String getSkipReason() {
        return skipReason;
    }

    /** Left out of this run by the user: SKIPPED with this reason, whatever the resume point. */
    public void setIgnored(String reason) {
        this.ignored = true;
        setSkipped(reason);
    }

    /** No longer ignored: the status stays as it is until a resume point is applied again. */
    public void clearIgnored() {
        this.ignored = false;
    }

    public boolean isIgnored() {
        return ignored;
    }

    /** The dry run of the process steps; null when not computed. */
    public Projection getProjection() {
        return projection;
    }

    public void setProjection(Projection projection) {
        this.projection = projection;
    }

    /** What the out step would find at the target of this file (the most severe of its products); null when not checked. */
    public TargetCheck getTargetCheck() {
        return targetCheck;
    }

    public void setTargetCheck(TargetCheck targetCheck) {
        this.targetCheck = targetCheck;
    }

    /** True while the plan checks the target of this file: waiting for the destination disk, then checking. */
    public boolean isTargetCheckPending() {
        return targetCheckPending;
    }

    public void setTargetCheckPending(boolean targetCheckPending) {
        this.targetCheckPending = targetCheckPending;
    }

    /**
     * Back to PENDING: stopped at the preparation barrier, selected again by a manual resume point, or
     * interrupted by a cancel before it was done.
     */
    public void setReady() {
        this.skipReason = null;
        this.skippedByResumePoint = false;
        this.ending = null;
        this.waitingFor = Set.of();
        this.status = ItemStatus.PENDING;
    }

    /**
     * Called by the preparation once this item reached the barrier (analysed) or failed before it; an item whose
     * analysis was deferred is no longer (spec deferred-analysis §1).
     */
    public void markPrepared() {
        this.prepared = true;
        this.analysisDeferred = false;
    }

    /** True once the preparation is over for this item: the progress of the analyses. */
    public boolean isPrepared() {
        return prepared;
    }

    /**
     * Called by the preparation for an item the resume cursor skips as soon as it is listed: its preparation is
     * over without its analyses. A manual resume point may select it again: the execution then analyses it.
     */
    public void deferAnalysis(String skipReason) {
        markAnalysisDeferred();
        this.prepared = true;
        setSkippedByResumePoint(skipReason);
    }

    /**
     * Called by the preparation for an item listed while the resume point is not known yet: it is analysed only
     * when the destination probe checks it or the point selects it.
     */
    public void markAnalysisDeferred() {
        this.analysisDeferred = true;
    }

    /** True when the steps before the barrier have not run for this item (see {@link #deferAnalysis}). */
    public boolean isAnalysisDeferred() {
        return analysisDeferred;
    }

    /**
     * The last progress the running step reported for this item (shown as "Copying 42 %"); null before its first
     * report.
     */
    public WorkStatus getWorkStatus() {
        return ws;
    }

    /**
     * A progress report of the step stepIndex for this item (its {@link com.copybot.plugin.api.action.ItemProgress}):
     * ignored once the item is no longer running that step (a late report of a thread the action started). A null
     * action name keeps the one of the previous report of the step.
     */
    public synchronized void reportProgress(int stepIndex, WorkStatus report) {
        if (report == null || status != ItemStatus.RUNNING || currentStepIndex != stepIndex) {
            return;
        }
        WorkStatus previous = ws;
        String name = report.actionName() != null || previous == null ? report.actionName() : previous.actionName();
        ws = new WorkStatus(name, report.actionPercent());
    }
}

package com.copybot.engine.pipeline;

import com.copybot.engine.Projection;
import com.copybot.engine.resume.ItemKey;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkStatus;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class WorkItemExecution {

    private volatile WorkItem wi;
    private volatile WorkStatus ws;

    private final List<PipelineStep<?>> pipelineSteps;

    private volatile ItemStatus status = ItemStatus.PENDING;
    private volatile int currentStepIndex = -1;
    private volatile Set<String> waitingFor = Set.of();
    private volatile Throwable error;
    private volatile String skipReason;
    /** What the process steps would produce, by their dry run; set at the end of the preparation (spec pattern-helper §4.3). */
    private volatile Projection projection;

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
    /** Skipped by the resume cursor as soon as it was listed: the steps before the barrier did not run for it. */
    private volatile boolean analysisDeferred;
    /** Left out of this run by the user (SKIPPED): a resume point no longer selects it. */
    private volatile boolean ignored;
    /** The local file this item was created from, null for a remote or temporary one. */
    private final Path listedPath;

    public WorkItemExecution(WorkItem wi, List<PipelineStep<?>> pipelineSteps) {
        this.wi = wi;
        this.pipelineSteps = List.copyOf(pipelineSteps);
        this.listedPath = wi.isLocal() && !wi.isTempFile() ? wi.getLocalLocation() : null;
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
        this.status = ItemStatus.RUNNING;
    }

    public void setDone() {
        this.waitingFor = Set.of();
        this.status = ItemStatus.DONE;
    }

    public void setError(Throwable error) {
        this.error = error;
        this.waitingFor = Set.of();
        this.status = ItemStatus.ERROR;
    }

    /** Not selected by the resume point, or skipped by the out step (e.g. already at the destination); the reason is shown to the user. */
    public void setSkipped(String reason) {
        this.skipReason = reason;
        this.waitingFor = Set.of();
        this.status = ItemStatus.SKIPPED;
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

    /**
     * Back to PENDING: stopped at the preparation barrier, selected again by a manual resume point, or
     * interrupted by a cancel before it was done.
     */
    public void setReady() {
        this.skipReason = null;
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
        this.analysisDeferred = true;
        this.prepared = true;
        setSkipped(skipReason);
    }

    /** True when the steps before the barrier have not run for this item (see {@link #deferAnalysis}). */
    public boolean isAnalysisDeferred() {
        return analysisDeferred;
    }

    public WorkStatus getWorkStatus() {
        return ws;
    }

    public void setWorkStatus(WorkStatus ws) {
        this.ws = ws;
    }
}

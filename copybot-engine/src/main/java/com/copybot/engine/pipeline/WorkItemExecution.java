package com.copybot.engine.pipeline;

import com.copybot.engine.resume.ItemKey;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkStatus;

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

    /**
     * Resume ordering key, frozen at the preparation barrier: a later step may replace the work item
     * (new name, other metadata) but the item keeps the place it had when the resume point was applied.
     * null when the item has no date or when no resume preparation took place.
     */
    private volatile ItemKey resumeKey;

    /** The execution this one was forked from by a process step, null for a listed item. */
    private volatile WorkItemExecution parent;
    private volatile boolean forkFailed;

    public WorkItemExecution(WorkItem wi, List<PipelineStep<?>> pipelineSteps) {
        this.wi = wi;
        this.pipelineSteps = List.copyOf(pipelineSteps);
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

    public void setResumeKey(ItemKey resumeKey) {
        this.resumeKey = resumeKey;
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

    /** Not selected by the resume point; the reason is shown to the user. */
    public void setSkipped(String reason) {
        this.skipReason = reason;
        this.waitingFor = Set.of();
        this.status = ItemStatus.SKIPPED;
    }

    public String getSkipReason() {
        return skipReason;
    }

    /** Back to PENDING: stopped at the preparation barrier, or selected again by a manual resume point. */
    public void setReady() {
        this.skipReason = null;
        this.waitingFor = Set.of();
        this.status = ItemStatus.PENDING;
    }

    public WorkStatus getWorkStatus() {
        return ws;
    }

    public void setWorkStatus(WorkStatus ws) {
        this.ws = ws;
    }
}

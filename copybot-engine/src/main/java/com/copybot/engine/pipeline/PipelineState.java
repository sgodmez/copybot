package com.copybot.engine.pipeline;

import com.copybot.engine.resources.ResourceRegistry;
import com.copybot.engine.resources.ResourceSnapshot;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

public class PipelineState {
    private List<PipelineStepState> stepStates;

    // written by the pipeline thread, read by watchers/UI on other threads
    private volatile PipelineStatus status;

    private ConcurrentLinkedQueue<WorkItemExecution> workItems;

    // written by listing threads, read by watchers/UI on other threads
    private volatile boolean listingInProgress;

    private ResourceRegistry registry;

    public PipelineState(List<PipelineStepState> stepStates) {
        this.stepStates = stepStates;
        status = PipelineStatus.NEW;
        workItems = new ConcurrentLinkedQueue<>();
    }

    public PipelineStatus getStatus() {
        return status;
    }

    public void setStatus(PipelineStatus status) {
        this.status = status;
    }

    public List<PipelineStepState> getStepStates() {
        return stepStates;
    }

    public ConcurrentLinkedQueue<WorkItemExecution> getWorkItems() {
        return workItems;
    }

    public boolean isListingInProgress() {
        return listingInProgress;
    }

    public void setListingInProgress(boolean listingInProgress) {
        this.listingInProgress = listingInProgress;
    }

    public void setRegistry(ResourceRegistry registry) {
        this.registry = registry;
    }

    public List<ResourceSnapshot> getResourceSnapshot() {
        return registry == null ? List.of() : registry.snapshot();
    }
}

package com.copybot.engine.pipeline;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

public class PipelineState {
    private List<PipelineStepState> stepStates;
    private PipelineStatus status;

    private ConcurrentLinkedQueue<WorkItemExecution> workItems;

    private boolean listingInProgress;

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
}

package com.copybot.engine.pipeline;

import com.copybot.engine.resources.ResourceRegistry;
import com.copybot.engine.resources.ResourceSnapshot;
import com.copybot.engine.resume.ResumeProposal;

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

    // written by the pipeline thread, read by watchers/UI on other threads
    private volatile ResumeProposal resumeProposal;
    private volatile Throwable failure;

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
    public ResumeProposal getResumeProposal() {
        return resumeProposal;
    }

    public void setResumeProposal(ResumeProposal resumeProposal) {
        this.resumeProposal = resumeProposal;
    }

    /** A pipeline-level failure that is not tied to an item (e.g. the state file could not be written). */
    public Throwable getFailure() {
        return failure;
    }

    public void setFailure(Throwable failure) {
        this.failure = failure;
    }
}

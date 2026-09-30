package com.copybot.engine.pipeline;

import com.copybot.engine.resources.ResourceRegistry;
import com.copybot.engine.resources.ResourceSnapshot;
import com.copybot.engine.resume.ResumeProposal;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

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
    private volatile List<String> warnings = List.of();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private volatile boolean preparationFailed;

    public PipelineState(List<PipelineStepState> stepStates) {
        this.stepStates = stepStates;
        status = PipelineStatus.NEW;
        workItems = new ConcurrentLinkedQueue<>();
    }

    public PipelineStatus getStatus() {
        return status;
    }

    public synchronized void setStatus(PipelineStatus status) {
        this.status = status;
    }

    /** Atomically replaces the status when it is still the expected one (pause / resume vs. the end of the run). */
    public synchronized boolean compareAndSetStatus(PipelineStatus expected, PipelineStatus next) {
        if (status != expected) {
            return false;
        }
        status = next;
        return true;
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

    /**
     * The configuration warnings of the steps ({@code IAction#configWarnings}), set once the steps are
     * resolved; empty before. Shown before the run: dry-run output, CLI start, UI banner.
     */
    public List<String> getWarnings() {
        return warnings;
    }

    public void setWarnings(List<String> warnings) {
        this.warnings = List.copyOf(warnings);
    }

    /** A pipeline-level failure that is not tied to an item (e.g. the state file could not be written). */
    public Throwable getFailure() {
        return failure.get();
    }

    public void setFailure(Throwable failure) {
        this.failure.set(failure);
    }

    /**
     * Records the failure only when none is recorded yet (atomically: concurrent listings may fail together).
     *
     * @return true when this failure was recorded
     */
    public boolean recordFailureIfAbsent(Throwable failure) {
        return this.failure.compareAndSet(null, failure);
    }

    /**
     * True when the pipeline ended ERROR because its preparation failed (unresolvable step, invalid state
     * file, "destination" mode without target paths...): nothing was written. A listing failure,
     * items in error or a cursor write failure are run failures: false.
     */
    public boolean isPreparationFailed() {
        return preparationFailed;
    }

    public void setPreparationFailed(boolean preparationFailed) {
        this.preparationFailed = preparationFailed;
    }
}

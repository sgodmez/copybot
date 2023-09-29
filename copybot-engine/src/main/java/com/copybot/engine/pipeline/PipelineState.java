package com.copybot.engine.pipeline;

import com.copybot.plugin.api.action.WorkItem;

import java.util.ArrayList;
import java.util.List;

public class PipelineState {
    List<PipelineStepState> stepStates;
    PipelineStatus status;

    List<WorkItem> workItems;

    public PipelineState(List<PipelineStepState> stepStates) {
        this.stepStates = stepStates;
        status = PipelineStatus.NEW;
        workItems = new ArrayList<>();
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

    public List<WorkItem> getWorkItems() {
        return workItems;
    }

}

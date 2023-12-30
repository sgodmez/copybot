package com.copybot.engine.pipeline;

import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkStatus;

import java.util.Collections;
import java.util.List;

public class WorkItemExecution {

    private WorkItem wi;

    private WorkStatus ws;

    private List<PipelineStep> pipelineSteps;

    private int stepIndex;

    public WorkItemExecution(WorkItem wi, List<PipelineStep> pipelineSteps) {
        this.wi = wi;
        this.pipelineSteps = Collections.unmodifiableList(pipelineSteps);
        this.stepIndex = 0;
    }

    public WorkItem getWorkItem() {
        return wi;
    }

    public WorkStatus getWorkStatus() {
        return ws;
    }

    public void setWorkStatus(WorkStatus ws) {
        this.ws = ws;
    }
}

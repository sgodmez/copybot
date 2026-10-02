package com.copybot.engine;

import com.copybot.engine.pipeline.PipelineStep;
import com.copybot.plugin.api.action.IProcessAction;
import com.copybot.plugin.api.action.WorkItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Chains the dry run of the process steps on a copy of an item (spec pattern-helper §4.2).
 * A produced item that one step drops simply disappears; the projection is Filtered only when no item is left.
 */
public final class DryRunner {

    private DryRunner() {
    }

    /** Never throws for a plugin failure: it becomes {@link Projection.Failed}. */
    public static Projection project(WorkItem item, List<PipelineStep<IProcessAction>> processSteps) {
        List<WorkItem> current = List.of(item.copyForDryRun());
        for (PipelineStep<IProcessAction> step : processSteps) {
            List<WorkItem> next = new ArrayList<>();
            for (WorkItem produced : current) {
                Optional<List<WorkItem>> result;
                try {
                    result = step.getAction().dryRun(produced);
                } catch (RuntimeException e) {
                    return new Projection.Failed(actionName(step), e.getMessage() != null ? e.getMessage() : e.getClass().getName());
                }
                if (result == null || result.isEmpty()) {
                    return new Projection.Unsupported(actionName(step), current);
                }
                next.addAll(result.get());
            }
            if (next.isEmpty()) {
                return new Projection.Filtered(actionName(step));
            }
            current = next;
        }
        return new Projection.Projected(current);
    }

    /** The action code of the step, as written in the pipeline. */
    public static String actionName(PipelineStep<?> step) {
        return step.getConfig() == null ? step.getAction().getClass().getSimpleName() : step.getConfig().action();
    }
}

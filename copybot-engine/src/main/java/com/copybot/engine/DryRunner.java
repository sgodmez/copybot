package com.copybot.engine;

import com.copybot.engine.pipeline.PipelineStep;
import com.copybot.plugin.api.action.IProcessAction;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Chains the dry run of the process steps on a copy of an item (spec pattern-helper §4.2).
 * A produced item that one step drops simply disappears; the projection is Filtered only when no item is left.
 * The names produced by each step are kept as its trace (names only: a plan holds one per listed item).
 */
public final class DryRunner {

    private DryRunner() {
    }

    /**
     * A runtime exception of a plugin is never thrown: it becomes {@link Projection.Failed}, its message without the
     * secret values of the step. An error of a plugin is thrown, hidden the same way (the JVM failing as it is).
     */
    public static Projection project(WorkItem item, List<PipelineStep<IProcessAction>> processSteps) {
        List<WorkItem> current = List.of(item.copyForDryRun());
        List<Projection.Step> trace = new ArrayList<>();
        for (PipelineStep<IProcessAction> step : processSteps) {
            List<WorkItem> next = new ArrayList<>();
            for (WorkItem produced : current) {
                Optional<List<WorkItem>> result;
                try {
                    result = step.getAction().dryRun(produced);
                } catch (RuntimeException e) {
                    String message = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
                    return new Projection.Failed(actionName(step), step.getRedactor().text(message), trace);
                } catch (Error e) {
                    throw Redaction.rethrow(step.getRedactor(), e);
                }
                if (result == null || result.isEmpty()) {
                    return new Projection.Unsupported(actionName(step), current, trace);
                }
                for (WorkItem child : result.get()) {
                    inheritOutPattern(produced, child);
                    next.add(child);
                }
            }
            if (next.isEmpty()) {
                return new Projection.Filtered(actionName(step), trace);
            }
            current = next;
            trace.add(new Projection.Step(actionName(step), current.stream().map(DryRunner::itemName).toList(), takeNotes(current)));
        }
        return new Projection.Projected(current, trace);
    }

    /**
     * A produced item without its own out pattern takes the one of the item it comes from (spec own-out-pattern §2).
     */
    static void inheritOutPattern(WorkItem parent, WorkItem produced) {
        if (produced != null && produced != parent && produced.getOutPattern().isEmpty()) {
            parent.getOutPattern().ifPresent(produced::setOutPattern);
        }
    }

    /** Removes the note a step left on each item and joins the distinct ones with "; "; null when there is none. */
    private static String takeNotes(List<WorkItem> items) {
        Set<String> notes = new LinkedHashSet<>();
        for (WorkItem item : items) {
            if (item.getMetadatas().raw().remove(WorkItemMetadata.NOTE) instanceof String note && !note.isEmpty()) {
                notes.add(note);
            }
        }
        return notes.isEmpty() ? null : String.join("; ", notes);
    }

    /** The action code of the step, as written in the pipeline. */
    public static String actionName(PipelineStep<?> step) {
        return step.getConfig() == null ? step.getAction().getClass().getSimpleName() : step.getConfig().action();
    }

    /** display.name, else the name of the source, else its location. */
    public static String itemName(WorkItem item) {
        String name = item.getMetadatas().display().get("name");
        if (name != null) {
            return name;
        }
        return item.getNameDisplay() != null ? item.getNameDisplay() : item.getSourceLocationDisplay();
    }
}

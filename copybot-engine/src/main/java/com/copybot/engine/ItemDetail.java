package com.copybot.engine;

import com.copybot.engine.pipeline.WorkItemExecution;

import java.nio.file.Path;
import java.util.List;

/**
 * The planned processing of one item, for the desktop UI: the process steps of its dry run, then how it ends.
 *
 * @param steps    the process steps that produced items, in order ({@link Projection#trace})
 * @param outcome  how the dry run ended and where the out step would write ({@link Plan#projectionOf})
 * @param targets  the files (absolute) the out step would write, one per produced item, when the outcome is
 *                 {@link TargetProjection.Targets}; empty otherwise
 * @param branches the secondary outputs of the pipeline, in order (spec secondary-outputs §6); empty without any
 */
public record ItemDetail(List<Projection.Step> steps, TargetProjection outcome, List<Path> targets,
                         List<BranchDetail> branches) {

    /**
     * One secondary output for this item: where it would write (dry run of its process steps, then
     * {@link com.copybot.plugin.api.action.IOutAction#resolveTarget}), or what it did once the item is copied.
     *
     * @param name       the name of the output
     * @param projection the planned target; {@link TargetProjection#NONE} once the item is copied ({@code result} is shown)
     * @param targets    the files (absolute) the output would write, when the projection is
     *                   {@link TargetProjection.Targets}; empty otherwise
     * @param result     what the output really did, null until the item has been copied
     */
    public record BranchDetail(String name, TargetProjection projection, List<Path> targets,
                               WorkItemExecution.BranchResult result) {

        public BranchDetail {
            targets = List.copyOf(targets);
        }

        /** A secondary output whose file-level targets are not known. */
        public BranchDetail(String name, TargetProjection projection, WorkItemExecution.BranchResult result) {
            this(name, projection, List.of(), result);
        }
    }

    public ItemDetail {
        steps = List.copyOf(steps);
        targets = List.copyOf(targets);
        branches = List.copyOf(branches);
    }

    /** An item without secondary output. */
    public ItemDetail(List<Projection.Step> steps, TargetProjection outcome, List<Path> targets) {
        this(steps, outcome, targets, List.of());
    }
}

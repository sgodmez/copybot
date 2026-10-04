package com.copybot.engine;

import java.nio.file.Path;
import java.util.List;

/**
 * The planned processing of one item, for the desktop UI: the process steps of its dry run, then how it ends.
 *
 * @param steps   the process steps that produced items, in order ({@link Projection#trace})
 * @param outcome how the dry run ended and where the out step would write ({@link Plan#projectionOf})
 * @param targets the files (absolute) the out step would write, one per produced item, when the outcome is
 *                {@link TargetProjection.Targets}; empty otherwise
 */
public record ItemDetail(List<Projection.Step> steps, TargetProjection outcome, List<Path> targets) {

    public ItemDetail {
        steps = List.copyOf(steps);
        targets = List.copyOf(targets);
    }
}

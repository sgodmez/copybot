package com.copybot.engine;

import java.nio.file.Path;
import java.util.List;

/** Where the out step would write what an item becomes after the process steps (spec pattern-helper §4.3). */
public sealed interface TargetProjection {

    TargetProjection NONE = new None();

    /** The directories (absolute) of every produced item, in order. */
    record Targets(List<Path> directories) implements TargetProjection {
        public Targets {
            directories = List.copyOf(directories);
        }
    }

    record Filtered(String action) implements TargetProjection {
    }

    /** A process step does not support the dry run. */
    record Unknown(String action) implements TargetProjection {
    }

    /** A failed dry run, or a target that cannot be resolved (e.g. a missing pattern key): the message. */
    record Failed(String message) implements TargetProjection {
    }

    /** No out step, or it cannot tell. */
    record None() implements TargetProjection {
    }
}

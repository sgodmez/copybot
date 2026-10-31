package com.copybot.plugin.api.action;

import java.nio.file.Path;

/**
 * What the out step would find at the target of an item, told by the plan before the copy
 * ({@link IOutAction#checkTarget}, spec conflict-check §3).
 *
 * @param kind     what was found
 * @param existing the existing target, null when nothing exists or the action cannot tell
 * @param message  what the copy will do with it, in the current language, for the user; null when nothing exists
 * @param skipped  the copy will leave the item out (e.g. identical with "ifIdentical": "skip", the source itself):
 *                 known only when the action compared as the copy will; false when it cannot tell
 */
public record TargetCheck(Kind kind, Path existing, String message, boolean skipped) {

    public enum Kind {
        /** the action cannot tell (the default), or the target could not be resolved */
        UNKNOWN(0),
        /** the target does not exist */
        FREE(1),
        /** a target exists with the same size (sizes compared only) */
        SAME_SIZE(2),
        /** a target exists, identical by the action's own comparison */
        IDENTICAL(2),
        /** a target exists with another size (or is a directory) */
        DIFFERENT_SIZE(3),
        /** a target exists, different by the action's own comparison */
        DIFFERENT(3);

        private final int severity;

        Kind(int severity) {
            this.severity = severity;
        }

        /** A target exists. */
        public boolean exists() {
            return severity >= 2;
        }
    }

    public static final TargetCheck UNKNOWN = new TargetCheck(Kind.UNKNOWN, null, null);

    private static final TargetCheck FREE = new TargetCheck(Kind.FREE, null, null);

    public TargetCheck {
        if (kind == null) {
            throw new IllegalArgumentException("kind");
        }
    }

    /** A check that cannot tell whether the copy leaves the item out. */
    public TargetCheck(Kind kind, Path existing, String message) {
        this(kind, existing, message, false);
    }

    public static TargetCheck free() {
        return FREE;
    }

    /** A target exists. */
    public boolean exists() {
        return kind.exists();
    }

    /** The most severe of two checks (several items produced from one): a free target hides nothing; a tie keeps a. */
    public static TargetCheck mostSevere(TargetCheck a, TargetCheck b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return b.kind.severity > a.kind.severity ? b : a;
    }
}

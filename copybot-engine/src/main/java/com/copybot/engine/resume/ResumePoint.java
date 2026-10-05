package com.copybot.engine.resume;

import java.util.Objects;

/**
 * Where an import resumes: everything, strictly after a key (automatic detection),
 * from a key included (manual choice of a file or a date), or the files not at the destination yet.
 */
public record ResumePoint(Kind kind, ItemKey key) {

    public enum Kind {
        ALL, AFTER, FROM,
        /**
         * every file whose own target is not at the destination ({@code destinationCheck: everyFile}, spec
         * execution-mode §2): no key, the resolver decides each file; {@link #selects} is true for any key
         */
        NOT_AT_DESTINATION
    }

    /** @throws NullPointerException without kind, or without key for AFTER and FROM */
    public ResumePoint {
        Objects.requireNonNull(kind, "kind");
        if (kind == Kind.AFTER || kind == Kind.FROM) {
            Objects.requireNonNull(key, "key");
        }
    }

    public static ResumePoint all() {
        return new ResumePoint(Kind.ALL, null);
    }

    public static ResumePoint after(ItemKey key) {
        return new ResumePoint(Kind.AFTER, key);
    }

    public static ResumePoint from(ItemKey key) {
        return new ResumePoint(Kind.FROM, key);
    }

    public static ResumePoint notAtDestination() {
        return new ResumePoint(Kind.NOT_AT_DESTINATION, null);
    }

    public boolean selects(ItemKey candidate) {
        return switch (kind) {
            case ALL, NOT_AT_DESTINATION -> true;
            case AFTER -> candidate.compareTo(key) > 0;
            case FROM -> candidate.compareTo(key) >= 0;
        };
    }
}

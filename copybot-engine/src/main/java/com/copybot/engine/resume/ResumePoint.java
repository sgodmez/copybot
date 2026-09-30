package com.copybot.engine.resume;

/**
 * Where an import resumes: everything, strictly after a key (automatic detection),
 * or from a key included (manual choice of a file or a date).
 */
public record ResumePoint(Kind kind, ItemKey key) {

    public enum Kind { ALL, AFTER, FROM }

    public static ResumePoint all() {
        return new ResumePoint(Kind.ALL, null);
    }

    public static ResumePoint after(ItemKey key) {
        return new ResumePoint(Kind.AFTER, key);
    }

    public static ResumePoint from(ItemKey key) {
        return new ResumePoint(Kind.FROM, key);
    }

    public boolean selects(ItemKey candidate) {
        return switch (kind) {
            case ALL -> true;
            case AFTER -> candidate.compareTo(key) > 0;
            case FROM -> candidate.compareTo(key) >= 0;
        };
    }
}

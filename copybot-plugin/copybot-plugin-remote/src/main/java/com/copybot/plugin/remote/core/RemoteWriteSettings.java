package com.copybot.plugin.remote.core;

/**
 * The fields of a remote output (spec remote §5), implemented by the configuration record of each protocol's
 * {@code *.write} next to its connection fields.
 */
public interface RemoteWriteSettings {

    /** Where each item goes, a pattern as file.write's ('/' or '\' between the names); required. */
    String outPattern();

    /** "error" (default), "skip" or "literal": what to do when an expression of outPattern has no value. */
    String onMissingKey();

    /** What to do when the target exists; absent: the defaults of {@link RemoteOnConflict}. */
    RemoteOnConflict onConflict();

    /** Delete the source once written and verified (default false); never for a source kept. */
    Boolean deleteSource();

    /** The connections open at once for the step; absent: {@value RemotePool#DEFAULT_MAX_CONNECTIONS}. */
    Integer maxConnections();
}

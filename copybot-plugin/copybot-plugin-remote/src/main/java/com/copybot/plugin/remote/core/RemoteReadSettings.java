package com.copybot.plugin.remote.core;

import java.util.List;

/**
 * The fields of a remote input (spec remote §4), implemented by the configuration record of each protocol's
 * {@code *.read} next to its connection fields.
 */
public interface RemoteReadSettings {

    /** The remote folder (an S3 prefix) to list; required. */
    String folder();

    /** The whole tree (default), or the first level only when false. */
    Boolean recursive();

    /** Globs relative to the folder, case-insensitive, as file.read's; absent or empty: every file. */
    List<String> include();

    /** Globs relative to the folder, case-insensitive; they win over include. */
    List<String> exclude();

    /** The files smaller than this many bytes are left out; absent: none. */
    Long minSize();

    /** The connections open at once for the step; absent: {@value RemotePool#DEFAULT_MAX_CONNECTIONS}. */
    Integer maxConnections();
}

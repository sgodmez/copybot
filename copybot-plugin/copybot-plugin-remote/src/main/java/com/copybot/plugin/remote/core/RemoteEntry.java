package com.copybot.plugin.remote.core;

import java.time.Instant;
import java.util.Objects;

/**
 * An entry of a remote folder, or the state of a remote path.
 *
 * @param name         the last segment of its path (no separator)
 * @param size         in bytes; 0 for a folder
 * @param lastModified the date of modification the server gives, null when it gives none
 * @param directory    a folder (or a prefix of S3 keys)
 */
public record RemoteEntry(String name, long size, Instant lastModified, boolean directory) {

    public RemoteEntry {
        Objects.requireNonNull(name, "name");
    }

    public static RemoteEntry file(String name, long size, Instant lastModified) {
        return new RemoteEntry(name, size, lastModified, false);
    }

    public static RemoteEntry folder(String name) {
        return new RemoteEntry(name, 0, null, true);
    }
}

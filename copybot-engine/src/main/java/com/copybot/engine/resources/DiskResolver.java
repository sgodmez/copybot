package com.copybot.engine.resources;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Maps a path to a disk resource name ("disk:<volume>"), volume-level in v1. */
public final class DiskResolver {

    private DiskResolver() {
    }

    public static String diskResource(Path path) {
        Path absolute = path.toAbsolutePath();
        Path existing = absolute;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing != null) {
            try {
                return ResourceSettings.DISK_PREFIX + Files.getFileStore(existing);
            } catch (IOException e) {
                // fall through to root-based naming
            }
        }
        Path root = absolute.getRoot();
        return ResourceSettings.DISK_PREFIX + (root != null ? root : absolute);
    }
}

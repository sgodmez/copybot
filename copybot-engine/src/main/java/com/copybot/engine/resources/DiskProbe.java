package com.copybot.engine.resources;

import java.io.IOException;
import java.nio.file.Path;

/** Finds the disk that holds a path, for one operating system. */
interface DiskProbe {

    /** The disk holding {@code existing}, an existing absolute path; falls back to its volume, never null. */
    DiskIdentity identify(Path existing) throws IOException;

    /**
     * The name of a volume that cannot be reached now (a share switched off, a card not inserted), from its root:
     * the same name it gets once there, when the system can tell it.
     */
    default String unreachableId(Path root) {
        return root.toString();
    }
}

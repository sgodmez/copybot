package com.copybot.engine.resources;

import java.io.IOException;
import java.nio.file.Path;

/** Finds the disk that holds a path, for one operating system. */
interface DiskProbe {

    /** The disk holding {@code existing}, an existing absolute path; falls back to its volume, never null. */
    DiskIdentity identify(Path existing) throws IOException;
}

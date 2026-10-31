package com.copybot.engine.resources;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;

/** Systems without a physical disk lookup: one resource per volume, named after its device. */
final class GenericDiskProbe implements DiskProbe {

    @Override
    public DiskIdentity identify(Path existing) throws IOException {
        FileStore store = Files.getFileStore(existing);
        String name = store.name();
        if (name == null || name.isBlank()) {
            name = String.valueOf(existing.getRoot());
        }
        return new DiskIdentity(name, DiskKind.UNKNOWN);
    }
}

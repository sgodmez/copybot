package com.copybot.plugin.api.action;

import java.io.IOException;

/** Deletes the source of an item that is not a local file (e.g. an entry of an archive) once it is copied. */
@FunctionalInterface
public interface SourceDeleter {

    /** @throws IOException the source cannot be deleted */
    void delete() throws IOException;
}

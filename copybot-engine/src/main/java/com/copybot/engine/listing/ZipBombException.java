package com.copybot.engine.listing;

import java.io.IOException;

/** A ZIP archive or entry that looks like a zip bomb: overlapping entries or an abusive compression ratio. */
public final class ZipBombException extends IOException {

    public ZipBombException(String message) {
        super(message);
    }
}

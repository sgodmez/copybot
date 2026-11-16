package com.copybot.engine.resume;

import java.time.ZoneId;
import java.util.Objects;

/**
 * The time zone correction of a file, kept in the state file (spec timezone-correction §3): the zone of the place
 * where it was shot, and the zone (possibly a fixed offset) the camera clock was set to, null for the one of the place.
 */
public record ZoneCorrection(ZoneId place, ZoneId clock) {

    public ZoneCorrection {
        Objects.requireNonNull(place, "place");
    }
}

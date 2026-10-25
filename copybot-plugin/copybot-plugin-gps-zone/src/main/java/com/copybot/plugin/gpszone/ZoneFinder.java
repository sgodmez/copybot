package com.copybot.plugin.gpszone;

import net.iakovlev.timeshape.TimeZoneEngine;

import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The time zone of a GPS position, from the offline time zone boundaries of timeshape (data of Timezone Boundary
 * Builder, ODbL). Trusted only far enough from a boundary: the zone at the point must be the one of 4 points
 * {@value #GUARD_KM} km north, south, east and west, else nothing (spec gps-zone-check §2). Nothing at sea either
 * (the Etc/GMT±n zones of the oceans). The boundaries are loaded at the first query, once, whatever the threads
 * (about 1 s and 200 MB of heap for the world): never for an import without GPS.
 */
public final class ZoneFinder {

    /** The distance of the 4 points around the position, in km. */
    static final double GUARD_KM = 5;
    private static final double KM_PER_DEGREE = 111.32;

    private static final System.Logger LOG = System.getLogger(ZoneFinder.class.getName());

    /** The zones of a point: one, several where boundaries overlap, none where the data has none. */
    @FunctionalInterface
    public interface Zones {
        List<ZoneId> at(double latitude, double longitude);
    }

    private static final ZoneFinder WORLD = new ZoneFinder(ZoneFinder::loadWorld);

    private final Supplier<Zones> loader;
    private volatile Zones zones;

    /** @param loader loads the zones, called once at the first query; failing, every query finds nothing */
    public ZoneFinder(Supplier<Zones> loader) {
        this.loader = loader;
    }

    /** The finder over the world data of the library, shared by the whole run. */
    public static ZoneFinder world() {
        return WORLD;
    }

    private static Zones loadWorld() {
        TimeZoneEngine engine = TimeZoneEngine.initialize();
        // safe from several threads: a read-only index, queried without accelerating the geometries (no lazy state)
        // near the antimeridian or a pole, a point around may fall out of the data: no zone there, nothing suggested
        return engine::queryAll;
    }

    private Zones zones() {
        Zones loaded = zones;
        if (loaded == null) {
            synchronized (this) {
                loaded = zones;
                if (loaded == null) {
                    try {
                        loaded = loader.get();
                    } catch (RuntimeException | LinkageError | OutOfMemoryError e) {
                        LOG.log(System.Logger.Level.WARNING, "The time zone boundaries could not be loaded: no GPS check", e);
                        loaded = (latitude, longitude) -> List.of();
                    }
                    zones = loaded;
                }
            }
        }
        return loaded;
    }

    /**
     * The zone of the position, when the 4 points {@value #GUARD_KM} km around are in the same one; empty near a
     * boundary, at sea, or when the library does not answer.
     */
    public Optional<ZoneId> zoneAt(double latitude, double longitude) {
        try {
            Zones all = zones();
            Optional<ZoneId> zone = single(all.at(latitude, longitude));
            if (zone.isEmpty() || zone.get().getId().startsWith("Etc/")) {
                return Optional.empty();
            }
            double dLat = GUARD_KM / KM_PER_DEGREE;
            double dLon = GUARD_KM / (KM_PER_DEGREE * Math.cos(Math.toRadians(latitude)));
            double[][] around = {{latitude + dLat, longitude}, {latitude - dLat, longitude},
                    {latitude, longitude + dLon}, {latitude, longitude - dLon}};
            for (double[] point : around) {
                if (!single(all.at(point[0], point[1])).equals(zone)) {
                    return Optional.empty();
                }
            }
            return zone;
        } catch (RuntimeException e) { // a position out of range near a pole...
            return Optional.empty();
        }
    }

    private static Optional<ZoneId> single(List<ZoneId> zones) {
        return zones.size() == 1 ? Optional.of(zones.get(0)) : Optional.empty();
    }
}

package com.copybot.plugin.gpszone;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class ZoneFinderTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final ZoneId MADRID = ZoneId.of("Europe/Madrid");

    /** A finder over fixed zones, counting its loads and recording the points it was asked. */
    private static final class Fake {
        final AtomicInteger loads = new AtomicInteger();
        final List<double[]> asked = Collections.synchronizedList(new ArrayList<>());
        final ZoneFinder finder;

        Fake(ZoneFinder.Zones zones) {
            finder = new ZoneFinder(() -> {
                loads.incrementAndGet();
                return (lat, lon) -> {
                    asked.add(new double[]{lat, lon});
                    return zones.at(lat, lon);
                };
            });
        }
    }

    @Test
    public void theZoneWhenTheFourPointsAroundAgree() {
        Fake fake = new Fake((lat, lon) -> List.of(PARIS));

        assertEquals(Optional.of(PARIS), fake.finder.zoneAt(48.8566, 2.3522));
        assertEquals(5, fake.asked.size(), "the point and the 4 points around");
    }

    @Test
    public void theFourPointsAreAboutFiveKilometresAway() {
        Fake fake = new Fake((lat, lon) -> List.of(PARIS));

        fake.finder.zoneAt(48.8566, 2.3522);

        for (double[] point : fake.asked.subList(1, 5)) {
            double north = (point[0] - 48.8566) * 111.32;
            double east = (point[1] - 2.3522) * 111.32 * Math.cos(Math.toRadians(48.8566));
            assertEquals(5.0, Math.hypot(north, east), 0.01);
        }
    }

    @Test
    public void nothingWhenAPointAroundDiffers() {
        Fake fake = new Fake((lat, lon) -> lon < 2.3 ? List.of(MADRID) : List.of(PARIS));

        assertEquals(Optional.empty(), fake.finder.zoneAt(48.8566, 2.3522));
    }

    @Test
    public void nothingWhenThePointHasNoneOrSeveralZones() {
        assertEquals(Optional.empty(), new Fake((lat, lon) -> List.of()).finder.zoneAt(48.8566, 2.3522));
        assertEquals(Optional.empty(), new Fake((lat, lon) -> List.of(PARIS, MADRID)).finder.zoneAt(48.8566, 2.3522));
    }

    @Test
    public void nothingAtSea() {
        assertEquals(Optional.empty(), new Fake((lat, lon) -> List.of(ZoneId.of("Etc/GMT+2"))).finder.zoneAt(45, -30));
    }

    @Test
    public void nothingWhenTheLibraryFailsAndItIsNotLoadedAgain() {
        AtomicInteger loads = new AtomicInteger();
        ZoneFinder finder = new ZoneFinder(() -> {
            loads.incrementAndGet();
            throw new IllegalStateException("no data");
        });

        assertEquals(Optional.empty(), finder.zoneAt(48.8566, 2.3522));
        assertEquals(Optional.empty(), finder.zoneAt(48.8566, 2.3522));
        assertEquals(1, loads.get());
    }

    @Test
    public void nothingWhenAQueryFails() {
        assertEquals(Optional.empty(), new Fake((lat, lon) -> {
            throw new IllegalArgumentException("out of range");
        }).finder.zoneAt(48.8566, 2.3522));
    }

    @Test
    public void loadedOnceAtTheFirstQueryEvenConcurrently() throws Exception {
        Fake fake = new Fake((lat, lon) -> List.of(PARIS));
        assertEquals(0, fake.loads.get(), "not loaded before a query");

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Optional<ZoneId>>> results = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return fake.finder.zoneAt(48.8566, 2.3522);
                }));
            }
            start.countDown();
            for (Future<Optional<ZoneId>> result : results) {
                assertEquals(Optional.of(PARIS), result.get());
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, fake.loads.get());
    }

    // ---- the world data of the library ----

    @Test
    public void theWorldFindsParisAndMexico() {
        assertEquals(Optional.of(PARIS), ZoneFinder.world().zoneAt(48.8566, 2.3522));
        assertEquals(Optional.of(ZoneId.of("America/Mexico_City")), ZoneFinder.world().zoneAt(19.4326, -99.1332));
    }

    @Test
    public void theWorldFindsNothingOnTheSpainPortugalBorderNorAtSea() {
        assertEquals(Optional.empty(), ZoneFinder.world().zoneAt(41.97, -6.79)); // Lisbon, Madrid 2 km away
        assertEquals(Optional.empty(), ZoneFinder.world().zoneAt(45, -30));
    }
}

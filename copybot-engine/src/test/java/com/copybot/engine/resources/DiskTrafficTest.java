package com.copybot.engine.resources;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class DiskTrafficTest {

    /** "card/x.jpg" is on "disk:card", "nas/y.jpg" on "disk:nas". */
    private static String diskOf(Path path) {
        return ResourceSettings.DISK_PREFIX + path.getName(0);
    }

    @Test
    public void readsAndWritesAreCountedPerDisk() {
        DiskTraffic traffic = new DiskTraffic(DiskTrafficTest::diskOf);

        traffic.on(Path.of("card", "a.jpg")).read(10);
        traffic.on(Path.of("card", "b.jpg")).read(5);
        traffic.on(Path.of("nas", "a.jpg")).written(15);

        assertEquals(Map.of("disk:card", new DiskTraffic.Bytes(15, 0), "disk:nas", new DiskTraffic.Bytes(0, 15)),
                traffic.snapshot());
    }

    @Test
    public void aCountedStreamCountsTheBytesReadNotTheSkippedOnes() throws IOException {
        DiskTraffic traffic = new DiskTraffic(DiskTrafficTest::diskOf);
        Path file = Path.of("card", "a.jpg");

        try (InputStream in = traffic.countReads(new ByteArrayInputStream(new byte[100]), file)) {
            assertEquals(0, in.read());
            assertEquals(9, in.read(new byte[9]));
            assertEquals(10, in.skip(10));
            assertEquals(30, in.readNBytes(30).length);
            assertEquals(50, in.transferTo(OutputStream.nullOutputStream()));
            assertEquals(-1, in.read());
        }

        assertEquals(new DiskTraffic.Bytes(90, 0), traffic.snapshot().get("disk:card"));
    }

    @Test
    public void theDiskIsResolvedOncePerStream() throws IOException {
        AtomicInteger resolved = new AtomicInteger();
        DiskTraffic traffic = new DiskTraffic(path -> {
            resolved.incrementAndGet();
            return diskOf(path);
        });

        try (InputStream in = traffic.countReads(new ByteArrayInputStream(new byte[10]), Path.of("card", "a.jpg"))) {
            while (in.read(new byte[1]) >= 0) {
                // one byte at a time
            }
        }

        assertEquals(1, resolved.get());
    }

    @Test
    public void theDisksOfAGroupAreSummedUnderItsName() {
        DiskTraffic traffic = new DiskTraffic(DiskTrafficTest::diskOf);
        traffic.on(Path.of("card", "a.jpg")).read(10);
        traffic.on(Path.of("nas", "a.jpg")).written(20);
        traffic.on(Path.of("ssd", "a.jpg")).read(1);

        Map<String, DiskTraffic.Bytes> grouped = traffic.snapshot(
                name -> name.equals("disk:nas") ? "disk:card" : name);

        assertEquals(Map.of("disk:card", new DiskTraffic.Bytes(10, 20), "disk:ssd", new DiskTraffic.Bytes(1, 0)),
                grouped);
    }
}

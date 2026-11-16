package com.copybot.ui.model;

import com.copybot.engine.resources.DiskTraffic;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * The throughput of each disk, from the bytes Copybot has read and written so far sampled about once a second:
 * averaged over the last seconds, so that a gauge does not flicker from one file to the next.
 */
public final class DiskRates {

    /** Bytes per second read from a disk and written to it. */
    public record Rate(double readPerSecond, double writtenPerSecond) {
    }

    private record Sample(long nanos, Map<String, DiskTraffic.Bytes> bytes) {
    }

    /** The samples kept: a span of about 3 s with one sample a second. */
    static final long WINDOW_NANOS = 3_500_000_000L;
    /** Under this span, two samples tell more of the last file than of the disk: no rate yet. */
    static final long MIN_SPAN_NANOS = 900_000_000L;
    /** Samples closer than this to the last one are not kept. */
    static final long MIN_GAP_NANOS = 200_000_000L;

    private final Deque<Sample> samples = new ArrayDeque<>();

    /**
     * @param nanos the time of the sample ({@link System#nanoTime()})
     * @param bytes the bytes of each disk so far
     */
    public void sample(long nanos, Map<String, DiskTraffic.Bytes> bytes) {
        if (!samples.isEmpty() && nanos - samples.getLast().nanos() < MIN_GAP_NANOS) {
            return; // the gauges are also drawn on each notification: a burst of them keeps no more samples
        }
        samples.addLast(new Sample(nanos, Map.copyOf(bytes)));
        while (nanos - samples.getFirst().nanos() > WINDOW_NANOS) {
            samples.removeFirst();
        }
    }

    /** The rate of each disk over the samples kept; none while they span too short a time. */
    public Map<String, Rate> rates() {
        if (samples.isEmpty()) {
            return Map.of();
        }
        Sample first = samples.getFirst();
        Sample last = samples.getLast();
        long span = last.nanos() - first.nanos();
        if (span < MIN_SPAN_NANOS) {
            return Map.of();
        }
        double seconds = span / 1e9;
        Map<String, Rate> rates = new HashMap<>();
        last.bytes().forEach((disk, now) -> {
            // a disk not counted yet at the first sample had read and written nothing
            DiskTraffic.Bytes before = first.bytes().getOrDefault(disk, new DiskTraffic.Bytes(0, 0));
            rates.put(disk, new Rate((now.read() - before.read()) / seconds,
                    (now.written() - before.written()) / seconds));
        });
        return rates;
    }
}

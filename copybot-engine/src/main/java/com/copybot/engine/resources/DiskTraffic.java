package com.copybot.engine.resources;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * The bytes Copybot reads and writes, per disk resource (the names of {@link DiskResolver#diskResource}), since it
 * started: the real throughput of a disk is told from two snapshots, whereas the slots of the
 * {@link ResourceRegistry} only tell how many actions it allows at once. Counts the content of the items, their
 * copies and the read-backs, not the listings nor the metadata of the file system.
 * <p>
 * The disk of a file is resolved once per file (it may take a native call), then each buffer only adds to a
 * {@link LongAdder}: nothing shared is locked on the way of a copy.
 */
public final class DiskTraffic {

    /** The bytes read from a disk and written to it. */
    public record Bytes(long read, long written) {

        Bytes plus(Bytes other) {
            return new Bytes(read + other.read, written + other.written);
        }
    }

    /** The counts of one disk, resolved once for a file: given each buffer read or written. */
    public static final class Counter {
        private final LongAdder read = new LongAdder();
        private final LongAdder written = new LongAdder();

        public void read(long bytes) {
            read.add(bytes);
        }

        public void written(long bytes) {
            written.add(bytes);
        }

        Bytes bytes() {
            return new Bytes(read.sum(), written.sum());
        }
    }

    private static final DiskTraffic GLOBAL = new DiskTraffic(DiskResolver::diskResource);

    private final Function<Path, String> diskOf;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    // visible for tests: a disk told without the file system
    DiskTraffic(Function<Path, String> diskOf) {
        this.diskOf = diskOf;
    }

    /** The traffic of the whole of Copybot. */
    public static DiskTraffic global() {
        return GLOBAL;
    }

    /** The counts of the disk that holds path (a file not there yet: the disk it will be written to). */
    public Counter on(Path path) {
        return counters.computeIfAbsent(diskOf.apply(path), disk -> new Counter());
    }

    /** in, its bytes counted as read from the disk of path. */
    public InputStream countReads(InputStream in, Path path) {
        return new CountedInputStream(in, on(path));
    }

    /** The bytes of each disk so far. */
    public Map<String, Bytes> snapshot() {
        return snapshot(UnaryOperator.identity());
    }

    /**
     * The bytes so far, the disks that canonical names alike (a group sharing one capacity) summed under that name.
     */
    public Map<String, Bytes> snapshot(UnaryOperator<String> canonical) {
        Map<String, Bytes> bytes = new HashMap<>();
        counters.forEach((disk, counter) -> bytes.merge(canonical.apply(disk), counter.bytes(), Bytes::plus));
        return bytes;
    }

    /** Counts what is read; a skip reads nothing. */
    private static final class CountedInputStream extends FilterInputStream {
        private final Counter counter;

        CountedInputStream(InputStream in, Counter counter) {
            super(in);
            this.counter = counter;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0) {
                counter.read(1);
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n > 0) {
                counter.read(n);
            }
            return n;
        }
    }
}

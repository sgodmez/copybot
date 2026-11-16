package com.copybot.engine.listing;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/**
 * Reads the structure of a ZIP file (end of central directory, ZIP64 included, central directory, local headers)
 * to detect entries whose stored data overlap, the technique of the most efficient zip bombs. Positional reads
 * only, no dependency.
 */
public final class ZipLayout {

    private static final int EOCD_SIG = 0x06054b50;
    private static final int EOCD64_LOCATOR_SIG = 0x07064b50;
    private static final int EOCD64_SIG = 0x06064b50;
    private static final int CEN_SIG = 0x02014b50;
    private static final int LOC_SIG = 0x04034b50;
    private static final int EOCD_SIZE = 22;
    private static final int CEN_SIZE = 46;
    private static final int LOC_SIZE = 30;
    private static final int MAX_COMMENT = 0xFFFF;
    private static final long MASK32 = 0xFFFFFFFFL;

    private ZipLayout() {
    }

    /** Throws {@link ZipBombException} when two entries share bytes, {@link IOException} when the ZIP is malformed. */
    public static void checkNoOverlap(Path zip) throws IOException {
        try (FileChannel ch = FileChannel.open(zip, StandardOpenOption.READ)) {
            long size = ch.size();
            long eocdPos = findEocd(ch, size);
            ByteBuffer eocd = read(ch, eocdPos, EOCD_SIZE);
            long entries = eocd.getShort(10) & 0xFFFFL;
            long cdSize = eocd.getInt(12) & MASK32;
            long cdOffset = eocd.getInt(16) & MASK32;
            if (entries == 0xFFFFL || cdSize == MASK32 || cdOffset == MASK32) {
                long[] zip64 = readZip64Eocd(ch, eocdPos);
                entries = zip64[0];
                cdSize = zip64[1];
                cdOffset = zip64[2];
            }
            if (cdOffset < 0 || cdOffset > size || cdSize < 0 || cdSize > size - cdOffset || entries < 0
                    || entries > cdSize / CEN_SIZE) {
                throw new IOException("Corrupt ZIP central directory: " + zip);
            }
            int count = (int) entries;
            long[] starts = new long[count];
            long[] ends = new long[count];
            long pos = cdOffset;
            for (int i = 0; i < count; i++) {
                ByteBuffer cen = read(ch, pos, CEN_SIZE);
                if (cen.getInt(0) != CEN_SIG) {
                    throw new IOException("Corrupt ZIP central directory entry: " + zip);
                }
                long compressed = cen.getInt(20) & MASK32;
                long localOffset = cen.getInt(42) & MASK32;
                int nameLen = cen.getShort(28) & 0xFFFF;
                int extraLen = cen.getShort(30) & 0xFFFF;
                int commentLen = cen.getShort(32) & 0xFFFF;
                boolean needUncompressed = (cen.getInt(24) & MASK32) == MASK32;
                boolean needCompressed = compressed == MASK32;
                boolean needOffset = localOffset == MASK32;
                if (needCompressed || needOffset) {
                    ByteBuffer extra = read(ch, pos + CEN_SIZE + nameLen, extraLen);
                    long[] values = zip64Extra(extra, needUncompressed, needCompressed, needOffset);
                    if (needCompressed) {
                        compressed = values[1];
                    }
                    if (needOffset) {
                        localOffset = values[2];
                    }
                }
                if (localOffset < 0 || localOffset > size - LOC_SIZE || compressed < 0 || compressed > size) {
                    throw new IOException("Corrupt ZIP64 extra field: " + zip);
                }
                ByteBuffer loc = read(ch, localOffset, LOC_SIZE);
                if (loc.getInt(0) != LOC_SIG) {
                    throw new IOException("Corrupt ZIP local header: " + zip);
                }
                long dataStart = localOffset + LOC_SIZE + (loc.getShort(26) & 0xFFFF) + (loc.getShort(28) & 0xFFFF);
                starts[i] = localOffset;
                ends[i] = dataStart + compressed;
                pos += CEN_SIZE + nameLen + extraLen + commentLen;
            }
            checkRanges(starts, ends, zip);
        }
    }

    private static void checkRanges(long[] starts, long[] ends, Path zip) throws ZipBombException {
        Integer[] order = new Integer[starts.length];
        Arrays.setAll(order, i -> i);
        Arrays.sort(order, (a, b) -> Long.compare(starts[a], starts[b]));
        for (int i = 1; i < order.length; i++) {
            if (ends[order[i - 1]] > starts[order[i]]) {
                throw new ZipBombException("ZIP entries overlap: " + zip);
            }
        }
    }

    private static long findEocd(FileChannel ch, long size) throws IOException {
        if (size < EOCD_SIZE) {
            throw new IOException("Not a ZIP file (too short)");
        }
        long from = Math.max(0, size - EOCD_SIZE - MAX_COMMENT);
        ByteBuffer tail = read(ch, from, (int) (size - from));
        for (int i = tail.limit() - EOCD_SIZE; i >= 0; i--) {
            if (tail.getInt(i) == EOCD_SIG) {
                return from + i;
            }
        }
        throw new IOException("Not a ZIP file (no end of central directory)");
    }

    /** Returns {entries, central directory size, central directory offset} from the ZIP64 end record. */
    private static long[] readZip64Eocd(FileChannel ch, long eocdPos) throws IOException {
        if (eocdPos < 20) {
            throw new IOException("Missing ZIP64 end of central directory locator");
        }
        ByteBuffer locator = read(ch, eocdPos - 20, 20);
        if (locator.getInt(0) != EOCD64_LOCATOR_SIG) {
            throw new IOException("Missing ZIP64 end of central directory locator");
        }
        long pos = locator.getLong(8);
        if (pos < 0) {
            throw new IOException("Corrupt ZIP64 end of central directory locator");
        }
        ByteBuffer rec = read(ch, pos, 56);
        if (rec.getInt(0) != EOCD64_SIG) {
            throw new IOException("Corrupt ZIP64 end of central directory record");
        }
        return new long[] {rec.getLong(32), rec.getLong(40), rec.getLong(48)};
    }

    /** Values of the ZIP64 extra field (id 0x0001), in order: uncompressed size, compressed size, local offset. */
    private static long[] zip64Extra(ByteBuffer extra, boolean uncompressed, boolean compressed, boolean offset)
            throws IOException {
        int p = 0;
        while (p + 4 <= extra.limit()) {
            int id = extra.getShort(p) & 0xFFFF;
            int len = extra.getShort(p + 2) & 0xFFFF;
            if (id == 0x0001) {
                long[] values = new long[3];
                int q = p + 4;
                int end = p + 4 + len;
                boolean[] wanted = {uncompressed, compressed, offset};
                for (int k = 0; k < 3; k++) {
                    if (wanted[k]) {
                        if (q + 8 > end || end > extra.limit()) {
                            throw new IOException("Corrupt ZIP64 extra field");
                        }
                        values[k] = extra.getLong(q);
                        q += 8;
                    }
                }
                return values;
            }
            p += 4 + len;
        }
        throw new IOException("Missing ZIP64 extra field");
    }

    private static ByteBuffer read(FileChannel ch, long position, int length) throws IOException {
        if (position < 0 || length < 0) {
            throw new IOException("Corrupt ZIP file (negative position)");
        }
        ByteBuffer buf = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        long pos = position;
        while (buf.hasRemaining()) {
            int n = ch.read(buf, pos);
            if (n < 0) {
                throw new IOException("Truncated ZIP file");
            }
            pos += n;
        }
        buf.flip();
        return buf;
    }
}

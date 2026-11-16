package com.copybot.engine.listing;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Enforces the maximum compression ratio of a ZIP entry while it is read: the bytes really produced are counted
 * (the sizes announced by the archive can lie) and a {@link ZipBombException} is thrown once they exceed
 * {@code max(1 MB, 100 x compressedSize)}.
 */
public final class RatioGuardInputStream extends FilterInputStream {

    static final long FLOOR = 1024L * 1024L;
    static final long MAX_RATIO = 100L;

    private final String entryName;
    private final long limit;
    private long produced;

    /**
     * @param compressedSize the compressed size from the central directory (as {@code ZipFile} provides); a
     *                       negative value (unknown) is refused
     */
    public RatioGuardInputStream(InputStream in, long compressedSize, String entryName) {
        super(in);
        if (compressedSize < 0) {
            throw new IllegalArgumentException("Unknown compressed size for entry '" + entryName + "'");
        }
        this.entryName = entryName;
        long ratioLimit = compressedSize > Long.MAX_VALUE / MAX_RATIO ? Long.MAX_VALUE : compressedSize * MAX_RATIO;
        this.limit = Math.max(FLOOR, ratioLimit);
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b >= 0) {
            count(1);
        }
        return b;
    }

    @Override
    public int read(byte[] buffer, int off, int len) throws IOException {
        int n = super.read(buffer, off, len);
        if (n > 0) {
            count(n);
        }
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        long skipped = super.skip(n);
        if (skipped > 0) {
            count(skipped);
        }
        return skipped;
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    private void count(long n) throws ZipBombException {
        produced += n;
        if (produced > limit) {
            throw new ZipBombException("Entry '" + entryName + "' exceeds the maximum compression ratio (x"
                    + MAX_RATIO + ")");
        }
    }
}

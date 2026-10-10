package com.copybot.engine.resources;

/** The kind of a physical disk, which sets how many actions it takes at once by default. */
public enum DiskKind {
    /** A rotating disk: parallel accesses make it seek, so it keeps the former default. */
    HDD(2),
    SSD(4),
    NVME(8),
    /** Not detected: memory card, network share, unknown device. */
    UNKNOWN(2);

    private final int defaultCapacity;

    DiskKind(int defaultCapacity) {
        this.defaultCapacity = defaultCapacity;
    }

    public int defaultCapacity() {
        return defaultCapacity;
    }
}

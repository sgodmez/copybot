package com.copybot.engine.resources;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Linux: the device number of a path is looked up in /sys/block (a disk, or a partition of a disk); a device
 * mapper or md device over exactly one disk (dm-crypt, LVM on one disk) is followed down to it, one over several
 * disks (RAID, LVM spanning) is its own resource. File systems without a block device number (btrfs) are matched
 * in /proc/self/mountinfo to their source device; the others (network shares, tmpfs, overlay) are their mount point.
 */
final class LinuxDiskProbe implements DiskProbe {

    /** The device numbers of a file system (st_dev of a file) and of a device node (st_rdev). */
    interface DeviceNumbers {
        long dev(Path path) throws IOException;

        long rdev(Path deviceNode) throws IOException;
    }

    static final DeviceNumbers UNIX = new DeviceNumbers() {
        @Override
        public long dev(Path path) throws IOException {
            return (Long) Files.getAttribute(path, "unix:dev");
        }

        @Override
        public long rdev(Path deviceNode) throws IOException {
            return (Long) Files.getAttribute(deviceNode, "unix:rdev");
        }
    };

    private static final long CACHE_MILLIS = 30_000; // a removable disk may come back under another name
    private static final int MAX_DEPTH = 8;

    private final Path sysBlock;
    private final Path mountInfo;
    private final DeviceNumbers numbers;
    private final Map<Long, Cached> byDevice = new ConcurrentHashMap<>();

    private record Cached(DiskIdentity identity, long at) {
    }

    /** A block device and the disk it belongs to (itself for a whole disk). */
    private record Block(String name, String disk) {
    }

    record Mount(String device, String point, String source) {
    }

    LinuxDiskProbe() {
        this(Path.of("/sys/block"), Path.of("/proc/self/mountinfo"), UNIX);
    }

    LinuxDiskProbe(Path sysBlock, Path mountInfo, DeviceNumbers numbers) {
        this.sysBlock = sysBlock;
        this.mountInfo = mountInfo;
        this.numbers = numbers;
    }

    @Override
    public DiskIdentity identify(Path existing) throws IOException {
        long dev = numbers.dev(existing);
        long now = System.currentTimeMillis();
        Cached cached = byDevice.get(dev);
        if (cached != null && now - cached.at() < CACHE_MILLIS) {
            return cached.identity();
        }
        DiskIdentity identity = lookup(dev, existing);
        byDevice.put(dev, new Cached(identity, now));
        return identity;
    }

    private DiskIdentity lookup(long dev, Path existing) throws IOException {
        String number = majorMinor(dev);
        Block block = block(number);
        if (block == null) {
            Mount mount = mount(number);
            if (mount != null && mount.source().startsWith("/dev/")) {
                try {
                    block = block(majorMinor(numbers.rdev(Path.of(mount.source()))));
                } catch (IOException | RuntimeException e) {
                    // no such device node: the mount point stands for the disk
                }
            }
            if (block == null) {
                String point = mount != null ? mount.point() : String.valueOf(existing.getRoot());
                return new DiskIdentity(point, DiskKind.UNKNOWN);
            }
        }
        String disk = underlying(block.disk(), 0);
        return new DiskIdentity(disk, kind(disk));
    }

    /** "8:2" from a st_dev, decoded like glibc's major() and minor(). */
    static String majorMinor(long dev) {
        long major = ((dev >>> 8) & 0xfff) | ((dev >>> 32) & 0xfffff000L);
        long minor = (dev & 0xff) | ((dev >>> 12) & 0xffffff00L);
        return major + ":" + minor;
    }

    private Block block(String number) throws IOException {
        if (!Files.isDirectory(sysBlock)) {
            return null;
        }
        for (Path disk : list(sysBlock)) {
            String diskName = disk.getFileName().toString();
            if (number.equals(read(disk.resolve("dev")))) {
                return new Block(diskName, diskName);
            }
            for (Path partition : list(disk)) {
                if (Files.exists(partition.resolve("partition")) && number.equals(read(partition.resolve("dev")))) {
                    return new Block(partition.getFileName().toString(), diskName);
                }
            }
        }
        return null;
    }

    /** The disk under a device mapper or md device lying on exactly one disk; the device itself otherwise. */
    private String underlying(String disk, int depth) throws IOException {
        Path slaves = sysBlock.resolve(disk).resolve("slaves");
        if (depth >= MAX_DEPTH || !Files.isDirectory(slaves)) {
            return disk;
        }
        List<Path> below = list(slaves);
        if (below.size() != 1) {
            return disk;
        }
        String slave = below.getFirst().getFileName().toString();
        String slaveDisk = diskOf(slave);
        return slaveDisk == null ? disk : underlying(slaveDisk, depth + 1);
    }

    /** The disk a block device name belongs to: itself when it is a disk, its parent when it is a partition. */
    private String diskOf(String name) throws IOException {
        if (Files.isDirectory(sysBlock.resolve(name))) {
            return name;
        }
        for (Path disk : list(sysBlock)) {
            if (Files.isDirectory(disk.resolve(name))) {
                return disk.getFileName().toString();
            }
        }
        return null;
    }

    private DiskKind kind(String disk) throws IOException {
        if (disk.startsWith("nvme")) {
            return DiskKind.NVME;
        }
        String rotational = read(sysBlock.resolve(disk).resolve("queue").resolve("rotational"));
        if ("1".equals(rotational)) {
            return DiskKind.HDD;
        }
        return "0".equals(rotational) ? DiskKind.SSD : DiskKind.UNKNOWN;
    }

    private Mount mount(String number) throws IOException {
        if (!Files.isReadable(mountInfo)) {
            return null;
        }
        Mount found = null;
        for (String line : Files.readAllLines(mountInfo)) {
            Mount mount = parseMountInfo(line);
            if (mount != null && mount.device().equals(number)) {
                found = mount; // the last one wins: it is the one on top
            }
        }
        return found;
    }

    /**
     * One line of /proc/self/mountinfo: "36 35 0:31 / /home rw,relatime shared:1 - btrfs /dev/sda3 rw" (the
     * optional fields end at "-").
     */
    static Mount parseMountInfo(String line) {
        String[] fields = line.split(" ");
        int separator = List.of(fields).indexOf("-");
        if (fields.length < 5 || separator < 0 || separator + 2 >= fields.length) {
            return null;
        }
        return new Mount(fields[2], unescape(fields[4]), unescape(fields[separator + 2]));
    }

    /** Mountinfo writes a space, tab, newline or backslash of a path as an octal escape ("\040"). */
    private static String unescape(String field) {
        StringBuilder out = new StringBuilder(field.length());
        for (int i = 0; i < field.length(); i++) {
            char c = field.charAt(i);
            if (c == '\\' && i + 3 < field.length() &&field.substring(i + 1, i + 4).matches("[0-7]{3}")) {
                out.append((char) Integer.parseInt(field.substring(i + 1, i + 4), 8));
                i += 3;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static List<Path> list(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.sorted().toList();
        }
    }

    private static String read(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file).trim() : null;
        } catch (IOException e) {
            return null;
        }
    }
}

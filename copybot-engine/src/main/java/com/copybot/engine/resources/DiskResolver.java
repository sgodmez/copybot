package com.copybot.engine.resources;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps a path to a disk resource name: the physical disk when it can be found ("disk:PhysicalDrive1" on Windows,
 * "disk:sda" on Linux), so that two drive letters or partitions of one disk share one budget; else the volume
 * ("disk:D:\", "disk:/home"). Names stay the same across volume labels.
 */
public final class DiskResolver {

    private static final Map<String, DiskKind> KINDS = new ConcurrentHashMap<>();
    private static volatile DiskProbe probe;

    private DiskResolver() {
    }

    public static String diskResource(Path path) {
        Path absolute = path.toAbsolutePath();
        Path existing = existingAncestor(absolute);
        if (existing != null) {
            try {
                DiskIdentity identity = probe().identify(existing);
                String name = ResourceSettings.DISK_PREFIX + identity.id();
                KINDS.put(name, identity.kind());
                return name;
            } catch (Exception | LinkageError e) {
                // fall through to root-based naming
            }
        }
        Path root = absolute.getRoot();
        if (root == null) {
            return ResourceSettings.DISK_PREFIX + absolute;
        }
        try {
            return ResourceSettings.DISK_PREFIX + probe().unreachableId(root); // "Z:\" mapped: its share, offline too
        } catch (Exception | LinkageError e) {
            return ResourceSettings.DISK_PREFIX + root;
        }
    }

    /** The kind of a disk named by {@link #diskResource}; UNKNOWN for a name it has not given. */
    public static DiskKind kindOf(String resourceName) {
        return KINDS.getOrDefault(resourceName, DiskKind.UNKNOWN);
    }

    /**
     * The resource a disk name of the configuration stands for: a volume or folder written as a path ("disk:D:\",
     * "disk:D:", "disk:/home") becomes the disk that holds it, so that the user can name disks by what they see;
     * a path that does not exist now is named by its volume ("disk:F:\", a share in lower case), the way it will be
     * once there; any other name ("disk:sda", "disk:*", "cpu") is kept as written.
     */
    public static String resourceForConfigName(String name) {
        if (name == null || !name.startsWith(ResourceSettings.DISK_PREFIX) || name.endsWith("*")) {
            return name;
        }
        String location = name.substring(ResourceSettings.DISK_PREFIX.length());
        if (location.length() == 2 && location.charAt(1) == ':') {
            location += "\\"; // "D:" alone means the drive, not its current folder
        }
        try {
            Path path = Path.of(location);
            return path.isAbsolute() ? diskResource(path) : name;
        } catch (InvalidPathException e) {
            return name;
        }
    }

    private static Path existingAncestor(Path absolute) {
        Path existing = absolute;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        return existing;
    }

    private static DiskProbe probe() {
        DiskProbe current = probe;
        if (current == null) {
            synchronized (DiskResolver.class) {
                current = probe;
                if (current == null) {
                    current = systemProbe();
                    probe = current;
                }
            }
        }
        return current;
    }

    private static DiskProbe systemProbe() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) {
            // the label of a volume is not stable: its root is ("D:\", "\\server\share\")
            DiskProbe byRoot = existing -> new DiskIdentity(WindowsDiskProbe.share(String.valueOf(existing.getRoot())),
                    DiskKind.UNKNOWN);
            try {
                return new FallbackProbe(new WindowsDiskProbe(), byRoot);
            } catch (Exception | LinkageError e) {
                return byRoot; // native access refused: one resource per volume
            }
        }
        DiskProbe generic = new GenericDiskProbe();
        return os.startsWith("linux") ? new FallbackProbe(new LinuxDiskProbe(), generic) : generic;
    }

    /** A probe that falls back to the volume when its lookup fails, so that a path always gets a stable name. */
    private record FallbackProbe(DiskProbe primary, DiskProbe fallback) implements DiskProbe {

        @Override
        public DiskIdentity identify(Path existing) throws IOException {
            try {
                return primary.identify(existing);
            } catch (Exception | LinkageError e) {
                return fallback.identify(existing);
            }
        }

        @Override
        public String unreachableId(Path root) {
            try {
                return primary.unreachableId(root);
            } catch (Exception | LinkageError e) {
                return fallback.unreachableId(root);
            }
        }
    }
}

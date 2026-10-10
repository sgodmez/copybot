package com.copybot.engine.resources;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Windows: the volume of a path (drive letter, mounted folder) is asked for the disks it lies on
 * (IOCTL_VOLUME_GET_VOLUME_DISK_EXTENTS), then that disk for its kind (IOCTL_STORAGE_QUERY_PROPERTY). Both work
 * without administrator rights: the devices are opened with no access, only to query them. A volume spread over
 * several disks (striped, spanned, Storage Spaces), a network share or a subst drive stays its own resource.
 */
final class WindowsDiskProbe implements DiskProbe {

    private static final int NO_ACCESS = 0;
    private static final int FILE_SHARE_READ_WRITE = 0x1 | 0x2;
    private static final int OPEN_EXISTING = 3;
    private static final int IOCTL_VOLUME_GET_VOLUME_DISK_EXTENTS = 0x00560000;
    private static final int IOCTL_STORAGE_QUERY_PROPERTY = 0x002D1400;
    private static final int STORAGE_DEVICE_PROPERTY = 0;
    private static final int STORAGE_DEVICE_SEEK_PENALTY_PROPERTY = 7;
    private static final int BUS_TYPE_OFFSET = 28; // STORAGE_DEVICE_DESCRIPTOR.BusType
    private static final int BUS_TYPE_NVME = 0x11;
    private static final int EXTENTS_HEADER = 8;   // VOLUME_DISK_EXTENTS.NumberOfDiskExtents + padding
    private static final int EXTENT_SIZE = 24;     // DISK_EXTENT: DiskNumber + padding, StartingOffset, ExtentLength
    private static final int MAX_EXTENTS = 32;
    private static final int PATH_CHARS = 1024;
    private static final long CACHE_MILLIS = 30_000; // a removable disk may come back under another number

    private final MethodHandle getVolumePathName;
    private final MethodHandle getVolumeNameForVolumeMountPoint;
    private final MethodHandle createFile;
    private final MethodHandle deviceIoControl;
    private final MethodHandle closeHandle;
    private final Map<String, Cached> byVolume = new ConcurrentHashMap<>();

    private record Cached(DiskIdentity identity, long at) {
    }

    WindowsDiskProbe() {
        Linker linker = Linker.nativeLinker();
        SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
        getVolumePathName = linker.downcallHandle(kernel32.findOrThrow("GetVolumePathNameW"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
        getVolumeNameForVolumeMountPoint = linker.downcallHandle(
                kernel32.findOrThrow("GetVolumeNameForVolumeMountPointW"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
        createFile = linker.downcallHandle(kernel32.findOrThrow("CreateFileW"),
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
        deviceIoControl = linker.downcallHandle(kernel32.findOrThrow("DeviceIoControl"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS,
                        ADDRESS));
        closeHandle = linker.downcallHandle(kernel32.findOrThrow("CloseHandle"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
    }

    @Override
    public DiskIdentity identify(Path existing) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            String volume = volumePath(arena, existing);
            long now = System.currentTimeMillis();
            Cached cached = byVolume.get(volume);
            if (cached != null && now - cached.at() < CACHE_MILLIS) {
                return cached.identity();
            }
            DiskIdentity identity = lookup(arena, volume);
            byVolume.put(volume, new Cached(identity, now));
            return identity;
        } catch (IOException | RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException(t);
        }
    }

    /** "D:\", "C:\mnt\photos\" or "\\server\share\": the mount point of the volume holding the path. */
    private String volumePath(Arena arena, Path existing) throws Throwable {
        String volume = volumePathOf(arena, existing.toString());
        if (volume == null && existing.getRoot() != null) {
            volume = volumePathOf(arena, existing.getRoot().toString()); // a path too long for the call
        }
        if (volume == null) {
            throw new IOException("GetVolumePathNameW failed for " + existing);
        }
        return volume;
    }

    private String volumePathOf(Arena arena, String path) throws Throwable {
        MemorySegment out = arena.allocate((long) PATH_CHARS * 2);
        int ok = (int) getVolumePathName.invokeExact(wide(arena, path), out, PATH_CHARS);
        return ok != 0 ? out.getString(0, StandardCharsets.UTF_16LE) : null;
    }

    private DiskIdentity lookup(Arena arena, String volume) throws Throwable {
        DiskIdentity ownVolume = new DiskIdentity(volume, DiskKind.UNKNOWN);
        MemorySegment out = arena.allocate((long) PATH_CHARS * 2);
        int ok = (int) getVolumeNameForVolumeMountPoint.invokeExact(wide(arena, volume), out, PATH_CHARS);
        if (ok == 0) {
            return ownVolume; // network share, subst drive
        }
        String guidPath = out.getString(0, StandardCharsets.UTF_16LE); // "\\?\Volume{...}\"
        Integer disk = singleDisk(arena, guidPath.substring(0, guidPath.length() - 1));
        return disk == null ? ownVolume : new DiskIdentity("PhysicalDrive" + disk, kind(arena, disk));
    }

    /** The number of the disk holding the whole volume, or null when it lies on none or several. */
    private Integer singleDisk(Arena arena, String volumeDevice) throws Throwable {
        MemorySegment handle = open(arena, volumeDevice);
        if (handle == null) {
            return null;
        }
        try {
            int size = EXTENTS_HEADER + EXTENT_SIZE * MAX_EXTENTS;
            MemorySegment out = arena.allocate(size, 8);
            if (!ioctl(arena, handle, IOCTL_VOLUME_GET_VOLUME_DISK_EXTENTS, MemorySegment.NULL, 0, out, size)) {
                return null;
            }
            int count = out.get(JAVA_INT, 0);
            if (count < 1 || count > MAX_EXTENTS) {
                return null;
            }
            int first = out.get(JAVA_INT, EXTENTS_HEADER);
            for (int i = 1; i < count; i++) {
                if (out.get(JAVA_INT, EXTENTS_HEADER + (long) EXTENT_SIZE * i) != first) {
                    return null;
                }
            }
            return first;
        } finally {
            int ignored = (int) closeHandle.invokeExact(handle);
        }
    }

    private DiskKind kind(Arena arena, int disk) throws Throwable {
        MemorySegment handle = open(arena, "\\\\.\\PhysicalDrive" + disk);
        if (handle == null) {
            return DiskKind.UNKNOWN;
        }
        try {
            MemorySegment query = arena.allocate(12, 4); // STORAGE_PROPERTY_QUERY, standard query
            query.set(JAVA_INT, 0, STORAGE_DEVICE_PROPERTY);
            MemorySegment descriptor = arena.allocate(1024, 8);
            if (ioctl(arena, handle, IOCTL_STORAGE_QUERY_PROPERTY, query, 12, descriptor, 1024)
                    && descriptor.get(JAVA_INT, BUS_TYPE_OFFSET) == BUS_TYPE_NVME) {
                return DiskKind.NVME;
            }
            query.set(JAVA_INT, 0, STORAGE_DEVICE_SEEK_PENALTY_PROPERTY);
            MemorySegment penalty = arena.allocate(12, 4); // DEVICE_SEEK_PENALTY_DESCRIPTOR
            if (!ioctl(arena, handle, IOCTL_STORAGE_QUERY_PROPERTY, query, 12, penalty, 12)) {
                return DiskKind.UNKNOWN;
            }
            return penalty.get(JAVA_BYTE, 8) != 0 ? DiskKind.HDD : DiskKind.SSD;
        } finally {
            int ignored = (int) closeHandle.invokeExact(handle);
        }
    }

    private MemorySegment open(Arena arena, String device) throws Throwable {
        MemorySegment handle = (MemorySegment) createFile.invokeExact(wide(arena, device), NO_ACCESS,
                FILE_SHARE_READ_WRITE, MemorySegment.NULL, OPEN_EXISTING, 0, MemorySegment.NULL);
        long address = handle.address();
        return address == 0 || address == -1 ? null : handle;
    }

    private boolean ioctl(Arena arena, MemorySegment handle, int code, MemorySegment in, int inSize,
                          MemorySegment out, int outSize) throws Throwable {
        MemorySegment returned = arena.allocate(JAVA_INT);
        int ok = (int) deviceIoControl.invokeExact(handle, code, in, inSize, out, outSize, returned,
                MemorySegment.NULL);
        return ok != 0;
    }

    private static MemorySegment wide(Arena arena, String text) {
        return arena.allocateFrom(text, StandardCharsets.UTF_16LE);
    }
}

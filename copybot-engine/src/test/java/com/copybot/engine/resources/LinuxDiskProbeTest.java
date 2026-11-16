package com.copybot.engine.resources;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The Linux lookup against a fake /sys/block and mountinfo, so that it runs on any system. */
public class LinuxDiskProbeTest {

    @TempDir
    Path tempDir;

    private Path sysBlock;
    private Path mountInfo;
    private final Map<String, Long> devOfPath = new HashMap<>();
    private final Map<String, Long> rdevOfNode = new HashMap<>();
    private LinuxDiskProbe probe;

    @BeforeEach
    public void fakeSystem() throws IOException {
        sysBlock = tempDir.resolve("sys/block");
        disk("sda", "8:0", "1");
        partition("sda", "sda1", "8:1");
        partition("sda", "sda2", "8:2");
        disk("sdb", "8:16", "0");
        partition("sdb", "sdb1", "8:17");
        partition("sdb", "sdb2", "8:18");
        disk("nvme0n1", "259:0", "0");
        partition("nvme0n1", "nvme0n1p1", "259:1");
        partition("nvme0n1", "nvme0n1p2", "259:2");
        disk("dm-0", "253:0", "0");
        Files.createDirectories(sysBlock.resolve("dm-0/slaves/nvme0n1p2")); // dm-crypt over one partition
        disk("dm-1", "253:1", "0");
        Files.createDirectories(sysBlock.resolve("dm-1/slaves/dm-0"));      // LVM inside that dm-crypt
        disk("md0", "9:0", null);
        Files.createDirectories(sysBlock.resolve("md0/slaves/sda2"));       // RAID over two disks
        Files.createDirectories(sysBlock.resolve("md0/slaves/sdb1"));

        mountInfo = tempDir.resolve("mountinfo");
        Files.writeString(mountInfo, String.join("\n",
                "22 1 8:1 / / rw,relatime shared:1 - ext4 /dev/sda1 rw",
                "30 22 0:31 / /home rw,relatime shared:2 - btrfs /dev/sdb2 rw,ssd",
                "31 22 0:45 / /mnt/nas\\040photos rw,relatime shared:3 - cifs //nas/photos rw",
                ""));
        rdevOfNode.put("/dev/sdb2", makedev(8, 18));

        probe = new LinuxDiskProbe(sysBlock, mountInfo, new LinuxDiskProbe.DeviceNumbers() {
            @Override
            public long dev(Path path) throws IOException {
                return number(devOfPath, path);
            }

            @Override
            public long rdev(Path deviceNode) throws IOException {
                return number(rdevOfNode, deviceNode);
            }
        });
    }

    @Test
    public void twoPartitionsOfOneDiskAreThatDisk() throws IOException {
        assertEquals(new DiskIdentity("sda", DiskKind.HDD), on("/", 8, 1));
        assertEquals(new DiskIdentity("sda", DiskKind.HDD), on("/var", 8, 2));
    }

    @Test
    public void aWholeDiskIsItself() throws IOException {
        assertEquals(new DiskIdentity("sdb", DiskKind.SSD), on("/srv", 8, 16));
    }

    @Test
    public void anNvmeDiskIsSeenAsNvme() throws IOException {
        assertEquals(new DiskIdentity("nvme0n1", DiskKind.NVME), on("/boot", 259, 1));
    }

    @Test
    public void anEncryptedVolumeAndTheLvmInsideItAreTheirDisk() throws IOException {
        assertEquals(new DiskIdentity("nvme0n1", DiskKind.NVME), on("/crypt", 253, 0));
        assertEquals(new DiskIdentity("nvme0n1", DiskKind.NVME), on("/data", 253, 1));
    }

    @Test
    public void aRaidOverSeveralDisksIsItsOwnResource() throws IOException {
        assertEquals(new DiskIdentity("md0", DiskKind.UNKNOWN), on("/raid", 9, 0));
    }

    @Test
    public void aBtrfsVolumeIsFoundThroughItsSourceDevice() throws IOException {
        assertEquals(new DiskIdentity("sdb", DiskKind.SSD), on("/home/me", 0, 31));
    }

    @Test
    public void aNetworkShareIsItsMountPoint() throws IOException {
        assertEquals(new DiskIdentity("/mnt/nas photos", DiskKind.UNKNOWN), on("/mnt/nas photos/2024", 0, 45));
    }

    @Test
    public void deviceNumbersDecodeLikeGlibc() {
        assertEquals("8:2", LinuxDiskProbe.majorMinor(makedev(8, 2)));
        assertEquals("259:300", LinuxDiskProbe.majorMinor(makedev(259, 300)));
        assertEquals("4100:1048577", LinuxDiskProbe.majorMinor(makedev(4100, 1048577)));
    }

    @Test
    public void mountInfoLinesAreParsed() {
        LinuxDiskProbe.Mount mount = LinuxDiskProbe.parseMountInfo(
                "36 35 98:0 /mnt1 /mnt/a\\040b rw,noatime master:1 shared:2 - ext3 /dev/root rw,errors=continue");
        assertEquals(new LinuxDiskProbe.Mount("98:0", "/mnt/a b", "ext3", "/dev/root"), mount);
        assertNull(LinuxDiskProbe.parseMountInfo("garbage"));
    }

    @Test
    public void onlyTheMountsHoldingFilesOfTheUserAreListed() throws IOException {
        List<String> lines = new java.util.ArrayList<>(Files.readAllLines(mountInfo));
        lines.addAll(List.of(
                "23 22 0:5 / /proc rw - proc proc rw",
                "24 22 0:6 / /sys/fs/cgroup rw - cgroup2 cgroup2 rw",
                "25 22 0:7 / /run/user/1000 rw - tmpfs tmpfs rw",
                "26 22 7:0 / /snap/core/1 ro - squashfs /dev/loop0 ro",
                "27 22 8:17 / /media/me/USB\\040KEY rw - vfat /dev/sdb1 rw",
                "28 22 0:50 / /dev/shm rw - ext4 /dev/sdz rw",
                "29 22 8:1 / / rw - ext4 /dev/sda1 rw"));
        assertEquals(List.of("/", "/home", "/mnt/nas photos", "/media/me/USB KEY"),
                LinuxDiskProbe.userMountPoints(lines));
    }

    private DiskIdentity on(String path, long major, long minor) throws IOException {
        devOfPath.put(path, makedev(major, minor));
        return probe.identify(Path.of(path));
    }

    private void disk(String name, String dev, String rotational) throws IOException {
        Path dir = Files.createDirectories(sysBlock.resolve(name));
        Files.writeString(dir.resolve("dev"), dev + "\n");
        Files.createDirectories(dir.resolve("queue"));
        if (rotational != null) {
            Files.writeString(dir.resolve("queue/rotational"), rotational + "\n");
        }
    }

    private void partition(String disk, String name, String dev) throws IOException {
        Path dir = Files.createDirectories(sysBlock.resolve(disk).resolve(name));
        Files.writeString(dir.resolve("dev"), dev + "\n");
        Files.writeString(dir.resolve("partition"), "1\n");
    }

    private static long number(Map<String, Long> numbers, Path path) throws IOException {
        Long number = numbers.get(path.toString().replace('\\', '/'));
        if (number == null) {
            throw new NoSuchFileException(path.toString());
        }
        return number;
    }

    /** glibc's makedev(). */
    private static long makedev(long major, long minor) {
        return (minor & 0xff) | ((major & 0xfff) << 8) | ((minor & 0xffffff00L) << 12)
                | ((major & 0xfffff000L) << 32);
    }
}

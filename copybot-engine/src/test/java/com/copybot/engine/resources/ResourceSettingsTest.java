package com.copybot.engine.resources;

import com.copybot.config.CopybotConfig;
import com.copybot.utils.GsonUtil;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ResourceSettingsTest {

    /** D: and E: are two volumes of one SSD, F: is an HDD; any other name is kept as written. */
    private static final ResourceSettings.Disks TWO_VOLUMES_ONE_DISK = new ResourceSettings.Disks() {
        @Override
        public String resourceFor(String configName) {
            return switch (configName) {
                case "disk:D:\\", "disk:E:\\" -> "disk:PhysicalDrive1";
                case "disk:F:\\" -> "disk:PhysicalDrive2";
                default -> configName;
            };
        }

        @Override
        public DiskKind kindOf(String resourceName) {
            return switch (resourceName) {
                case "disk:PhysicalDrive1" -> DiskKind.SSD;
                case "disk:PhysicalDrive2" -> DiskKind.HDD;
                case "disk:nvme0n1" -> DiskKind.NVME;
                default -> DiskKind.UNKNOWN;
            };
        }
    };

    @Test
    public void exactCapacityWinsOverPrefix() {
        CopybotConfig config = new CopybotConfig(null, null,
                Map.of("disk:*", 4, "disk:C:\\", 1), null);
        ResourceSettings settings = ResourceSettings.from(config, ResourceSettings.Disks.AS_WRITTEN);
        assertEquals(1, settings.capacityFor("disk:C:\\"));
        assertEquals(4, settings.capacityFor("disk:D:\\"));
    }

    @Test
    public void builtinDefaults() {
        ResourceSettings settings = ResourceSettings.from(null);
        assertEquals(Runtime.getRuntime().availableProcessors(), settings.capacityFor("cpu"));
        assertEquals(1, settings.capacityFor("gpu"));
        assertEquals(2, settings.capacityFor("disk:X:\\"));
        assertEquals(1, settings.capacityFor("net:flickr"));
    }

    @Test
    public void groupMembersShareOneCanonicalName() {
        CopybotConfig config = new CopybotConfig(null, null, null,
                List.of(List.of("disk:D:\\", "disk:E:\\")));
        ResourceSettings settings = ResourceSettings.from(config, ResourceSettings.Disks.AS_WRITTEN);
        assertEquals("disk:D:\\", settings.canonical("disk:D:\\"));
        assertEquals("disk:D:\\", settings.canonical("disk:E:\\"));
        assertEquals("cpu", settings.canonical("cpu"));
    }

    @Test
    public void configRecordParsesFromJson() {
        CopybotConfig config = GsonUtil.getGson().fromJson(
                "{\"resources\":{\"cpu\":4,\"disk:*\":8},\"resourceGroups\":[[\"disk:D\",\"disk:E\"]]}",
                CopybotConfig.class);
        ResourceSettings settings = ResourceSettings.from(config, ResourceSettings.Disks.AS_WRITTEN);
        assertEquals(4, settings.capacityFor("cpu"));
        assertEquals(8, settings.capacityFor("disk:D"));
        assertEquals("disk:D", settings.canonical("disk:E"));
    }

    @Test
    public void aCapacityGivenToAVolumeAppliesToItsDisk() {
        CopybotConfig config = new CopybotConfig(null, null, Map.of("disk:D:\\", 3), null);
        ResourceSettings settings = ResourceSettings.from(config, TWO_VOLUMES_ONE_DISK);
        assertEquals(3, settings.capacityFor("disk:PhysicalDrive1"));
    }

    @Test
    public void twoVolumesOfOneDiskKeepTheSmallerCapacity() {
        CopybotConfig config = new CopybotConfig(null, null, Map.of("disk:D:\\", 3, "disk:E:\\", 1), null);
        ResourceSettings settings = ResourceSettings.from(config, TWO_VOLUMES_ONE_DISK);
        assertEquals(1, settings.capacityFor("disk:PhysicalDrive1"));
    }

    @Test
    public void aGroupOfVolumesJoinsTheirDisks() {
        CopybotConfig config = new CopybotConfig(null, null, null,
                List.of(List.of("disk:D:\\", "disk:F:\\")));
        ResourceSettings settings = ResourceSettings.from(config, TWO_VOLUMES_ONE_DISK);
        assertEquals("disk:PhysicalDrive1", settings.canonical("disk:PhysicalDrive2"));
        assertEquals("disk:PhysicalDrive1", settings.canonical("disk:PhysicalDrive1"));
        assertEquals("disk:PhysicalDrive1", settings.canonical("disk:F:\\"));
    }

    @Test
    public void theDefaultCapacityOfADiskFollowsItsKind() {
        ResourceSettings settings = ResourceSettings.from(null, TWO_VOLUMES_ONE_DISK);
        assertEquals(DiskKind.SSD.defaultCapacity(), settings.capacityFor("disk:PhysicalDrive1"));
        assertEquals(DiskKind.HDD.defaultCapacity(), settings.capacityFor("disk:PhysicalDrive2"));
        assertEquals(DiskKind.NVME.defaultCapacity(), settings.capacityFor("disk:nvme0n1"));
        assertEquals(DiskKind.UNKNOWN.defaultCapacity(), settings.capacityFor("disk:\\\\nas\\photos\\"));
    }

    @Test
    public void aConfiguredPrefixWinsOverTheKind() {
        CopybotConfig config = new CopybotConfig(null, null, Map.of("disk:*", 1), null);
        ResourceSettings settings = ResourceSettings.from(config, TWO_VOLUMES_ONE_DISK);
        assertEquals(1, settings.capacityFor("disk:PhysicalDrive1"));
    }
}

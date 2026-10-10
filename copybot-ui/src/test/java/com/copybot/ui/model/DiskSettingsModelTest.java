package com.copybot.ui.model;

import com.copybot.config.ConfigFiles;
import com.copybot.engine.resources.DiskInventory.Volume;
import com.copybot.engine.resources.DiskKind;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

public class DiskSettingsModelTest {

    private static final String NVME = "disk:PhysicalDrive1";
    private static final String SSD = "disk:PhysicalDrive0";
    private static final String NAS = "disk:\\\\nas\\photos\\";

    /** C: on an NVMe disk, D: and E: on one SSD, a share of a NAS. */
    private static final List<Volume> MACHINE = List.of(
            new Volume("C:\\", NVME, DiskKind.NVME),
            new Volume("D:\\", SSD, DiskKind.SSD),
            new Volume("E:\\", SSD, DiskKind.SSD),
            new Volume("\\\\nas\\photos\\", NAS, DiskKind.UNKNOWN));

    private static final UnaryOperator<String> RESOURCE_FOR = name -> switch (name) {
        case "disk:C:\\" -> NVME;
        case "disk:D:\\", "disk:E:\\", "disk:D:" -> SSD;
        default -> name;
    };

    private static DiskSettingsModel model(Map<String, Integer> capacities, List<List<String>> groups) {
        return new DiskSettingsModel(MACHINE, new ConfigFiles.Resources(capacities, groups), RESOURCE_FOR);
    }

    private static DiskSettingsModel.Row row(DiskSettingsModel model, String resource) {
        return model.rows().stream().filter(r -> r.resource().equals(resource)).findFirst().orElseThrow();
    }

    @Test
    public void volumesOfOneDiskAreOneRowWithTheDefaultOfItsKind() {
        DiskSettingsModel model = model(Map.of(), List.of());

        assertEquals(List.of(NVME, SSD, NAS), model.rows().stream().map(DiskSettingsModel.Row::resource).toList());
        DiskSettingsModel.Row ssd = row(model, SSD);
        assertEquals(List.of("D:\\", "E:\\"), ssd.volumes());
        assertEquals(DiskKind.SSD, ssd.kind());
        assertEquals(4, ssd.capacity());
        assertTrue(ssd.defaultCapacity());
        assertEquals(8, row(model, NVME).capacity());
        assertEquals(2, row(model, NAS).capacity());
        assertFalse(model.changed());
    }

    @Test
    public void aCapacityIsWrittenUnderTheFirstVolumeOfTheDisk() {
        DiskSettingsModel model = model(Map.of("cpu", 4, "disk:*", 3), List.of());

        model.setCapacity(SSD, 1);

        assertEquals(Map.of("cpu", 4, "disk:*", 3, "disk:D:\\", 1), model.resources().capacities());
        assertEquals(1, row(model, SSD).capacity());
        assertFalse(row(model, SSD).defaultCapacity());
        assertTrue(model.changed());
    }

    @Test
    public void theSpellingOfTheUserIsKeptAndTheDefaultRemovesIt() {
        Map<String, Integer> asInTheFile = new LinkedHashMap<>(); // Gson keeps the order of the file
        asInTheFile.put("disk:D:", 3);
        asInTheFile.put("disk:E:\\", 2);
        DiskSettingsModel model = model(asInTheFile, List.of());
        assertEquals(2, row(model, SSD).capacity(), "two volumes of one disk: the smaller one");

        model.setCapacity(SSD, 5);
        assertEquals(Map.of("disk:D:", 5), model.resources().capacities());

        model.setCapacity(SSD, null);
        assertEquals(Map.of(), model.resources().capacities());
        assertEquals(4, row(model, SSD).capacity());
    }

    @Test
    public void theDefaultFollowsAConfiguredPattern() {
        DiskSettingsModel model = model(Map.of("disk:*", 3, "disk:D:\\", 1), List.of());
        assertEquals(3, model.defaultCapacity(SSD));
        assertEquals(3, model.defaultCapacity(NVME));
    }

    @Test
    public void groupingJoinsTwoDisksWithTheSmallerCapacity() {
        DiskSettingsModel model = model(Map.of("disk:C:\\", 6, "disk:D:\\", 3), List.of());

        model.group(NAS, SSD);

        assertEquals(List.of(List.of(NAS, "disk:D:\\")), model.resources().groups());
        assertEquals(Map.of(NAS, 3, "disk:C:\\", 6), model.resources().capacities());
        assertEquals(List.of(SSD), row(model, NAS).groupedWith());
        assertEquals(List.of(NAS), row(model, SSD).groupedWith());
        assertEquals(3, row(model, SSD).capacity());
        assertEquals(3, row(model, NAS).capacity());
        assertTrue(row(model, NVME).groupedWith().isEmpty());
    }

    @Test
    public void aCapacitySetOnAnyDiskOfAGroupIsTheGroups() {
        DiskSettingsModel model = model(Map.of(), List.of(List.of(NAS, "disk:D:\\")));

        model.setCapacity(SSD, 1);

        assertEquals(Map.of(NAS, 1), model.resources().capacities());
        assertEquals(1, row(model, NAS).capacity());
        assertEquals(1, row(model, SSD).capacity());
    }

    @Test
    public void groupingADiskWithAGroupExtendsIt() {
        DiskSettingsModel model = model(Map.of(), List.of(List.of(NAS, "disk:D:\\")));

        model.group(NVME, SSD);

        assertEquals(List.of(List.of("disk:C:\\", NAS, "disk:D:\\")), model.resources().groups());
    }

    @Test
    public void ungroupingKeepsTheCapacityForBoth() {
        DiskSettingsModel model = model(Map.of(NAS, 1), List.of(List.of(NAS, "disk:D:\\")));

        model.ungroup(NAS);

        assertEquals(List.of(), model.resources().groups());
        assertEquals(Map.of(NAS, 1, "disk:D:\\", 1), model.resources().capacities());
        assertTrue(row(model, NAS).groupedWith().isEmpty());
    }

    @Test
    public void otherGroupsAndResourcesStayAsTheyAre() {
        DiskSettingsModel model = model(Map.of("gpu", 2), List.of(List.of("net:flickr", "net:google")));

        model.group(NAS, SSD);
        model.ungroup(NAS);

        assertEquals(List.of(List.of("net:flickr", "net:google")), model.resources().groups());
        assertEquals(Map.of("gpu", 2), model.resources().capacities());
        assertFalse(model.changed());
    }

    @Test
    public void aDiskOfTheConfigurationThatIsNotThereStaysShown() {
        DiskSettingsModel model = model(Map.of("disk:sdx", 1), List.of(List.of("disk:\\\\old\\share\\", "disk:D:\\")));

        assertEquals(List.of(), row(model, "disk:sdx").volumes());
        assertEquals(1, row(model, "disk:sdx").capacity());
        assertEquals(List.of(SSD), row(model, "disk:\\\\old\\share\\").groupedWith());
    }

    @Test
    public void aFolderChosenByTheUserIsAddedToItsDisk() {
        DiskSettingsModel model = model(Map.of(), List.of());

        model.addVolume(new Volume("D:\\Photos", SSD, DiskKind.SSD));
        model.addVolume(new Volume("\\\\nas\\videos\\", "disk:\\\\nas\\videos\\", DiskKind.UNKNOWN));

        assertEquals(List.of("D:\\", "E:\\", "D:\\Photos"), row(model, SSD).volumes());
        assertEquals(List.of("\\\\nas\\videos\\"), row(model, "disk:\\\\nas\\videos\\").volumes());
    }
}

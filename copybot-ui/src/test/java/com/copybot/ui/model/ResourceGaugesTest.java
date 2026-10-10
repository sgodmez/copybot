package com.copybot.ui.model;

import com.copybot.engine.resources.DiskInventory.Volume;
import com.copybot.engine.resources.DiskKind;
import com.copybot.engine.resources.ResourceSnapshot;
import com.copybot.resources.ResourcesEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ResourceGaugesTest {

    private static final List<Volume> MACHINE = List.of(
            new Volume("C:\\", "disk:PhysicalDrive1", DiskKind.NVME),
            new Volume("D:\\", "disk:PhysicalDrive0", DiskKind.SSD),
            new Volume("E:\\", "disk:PhysicalDrive0", DiskKind.SSD));

    @BeforeAll
    static void bundles() {
        ResourcesEngine.registerBundle("com.copybot.ui.i18n.uiBundle");
    }

    private static ResourceSnapshot resource(String name, int capacity, int used, int waiting) {
        return new ResourceSnapshot(name, capacity, used, waiting, false);
    }

    @Test
    public void theCpuIsAlwaysShownAndNothingElseUntilAsked() {
        List<ResourceGauges.Gauge> gauges = ResourceGauges.of(List.of(), 0.42, 0.3, MACHINE);

        assertEquals(1, gauges.size());
        assertEquals(ResourcesEngine.getString("plan.resources.cpu"), gauges.getFirst().label());
        assertEquals(0.42, gauges.getFirst().fraction());
        assertEquals(0.3, gauges.getFirst().part(), "the part of Copybot, drawn inside the load of the machine");
        assertEquals(ResourcesEngine.getString("plan.resources.cpu.load", 42, 30), gauges.getFirst().text());
    }

    @Test
    public void theResourcesAskedForFollowInOrder() {
        List<ResourceGauges.Gauge> gauges = ResourceGauges.of(List.of(
                resource("step:0", 2, 1, 0),
                resource("net:flickr", 1, 1, 0),
                resource("gpu", 1, 0, 0),
                resource("disk:PhysicalDrive0", 4, 4, 12),
                resource("disk:\\\\nas\\photos\\", 2, 1, 0)), 0.1, 0.05, MACHINE);

        assertEquals(List.of(
                ResourcesEngine.getString("plan.resources.cpu"),
                ResourcesEngine.getString("plan.resources.disk.kind", "D:\\ E:\\",
                        ResourcesEngine.getString("pref.disks.kind.SSD")),
                ResourcesEngine.getString("plan.resources.disk", "\\\\nas\\photos\\"),
                ResourcesEngine.getString("plan.resources.gpu"),
                "net:flickr",
                ResourcesEngine.getString("plan.resources.step", 1)),
                gauges.stream().map(ResourceGauges.Gauge::label).toList());
    }

    @Test
    public void aFullResourceWithWaitersIsSaturated() {
        ResourceGauges.Gauge disk = ResourceGauges.of(List.of(resource("disk:PhysicalDrive0", 4, 4, 12)),
                0, 0, MACHINE).get(1);

        assertEquals(1.0, disk.fraction());
        assertTrue(disk.saturated());
        assertEquals(ResourcesEngine.getString("plan.resources.used", 4, 4) + " \u00b7 "
                + ResourcesEngine.getString("plan.resources.waiting", 12), disk.text());
        assertFalse(ResourceGauges.of(List.of(resource("gpu", 1, 1, 0)), 0, 0, MACHINE).get(1).saturated());
    }

    @Test
    public void cpuActionsAreAddedWhenAStepHoldsTheCpu() {
        ResourceGauges.Gauge cpu = ResourceGauges.of(List.of(resource("cpu", 8, 3, 0)), 0.5, 0.4, MACHINE)
                .getFirst();

        assertEquals(ResourcesEngine.getString("plan.resources.cpu.load", 50, 40) + " \u00b7 "
                + ResourcesEngine.getString("plan.resources.used", 3, 8), cpu.text());
    }

    @Test
    public void anUnknownLoadIsSaidSo() {
        ResourceGauges.Gauge cpu = ResourceGauges.of(List.of(), -1, -1, MACHINE).getFirst();

        assertTrue(cpu.fraction() < 0);
        assertTrue(cpu.part() < 0);
        assertTrue(ResourceGauges.of(List.of(), 0.2, 0.25, MACHINE).getFirst().part() <= 0.2,
                "two samples a little apart: Copybot never more than the machine");
        assertTrue(ResourceGauges.of(List.of(resource("gpu", 1, 1, 0)), 0, 0, MACHINE).get(1).part() < 0);
        assertEquals(ResourcesEngine.getString("plan.resources.cpu.unknown"), cpu.text());
    }
}

package com.copybot.ui.model;

import com.copybot.engine.resources.DiskInventory.Volume;
import com.copybot.engine.resources.DiskKind;
import com.copybot.engine.resources.ResourceSnapshot;
import com.copybot.resources.ResourcesEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;

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
        List<ResourceGauges.Gauge> gauges = ResourceGauges.of(List.of(), 0.42, 0.3, MACHINE, Map.of());

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
                resource("disk:\\\\nas\\photos\\", 2, 1, 0)), 0.1, 0.05, MACHINE, Map.of());

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
                0, 0, MACHINE, Map.of()).get(1);

        assertEquals(1.0, disk.fraction());
        assertTrue(disk.saturated());
        assertEquals(ResourcesEngine.getString("plan.resources.limit-reached", 4) + " \u00b7 "
                + ResourcesEngine.getString("plan.resources.waiting", 12), disk.text(), "the limit of a setting");
        assertFalse(ResourceGauges.of(List.of(resource("gpu", 1, 1, 0)), 0, 0, MACHINE, Map.of()).get(1).saturated());
    }

    @Test
    public void theShortFormsFitTheLineOfTheProgressBar() {
        List<ResourceGauges.Gauge> gauges = ResourceGauges.of(List.of(
                resource("disk:PhysicalDrive0", 4, 4, 37), resource("step:2", 3, 2, 0)), 0.354, 0.1, MACHINE, Map.of());

        assertEquals("CPU", gauges.get(0).shortLabel());
        assertEquals(ResourcesEngine.getString("plan.resources.cpu.short", 35), gauges.get(0).shortText());
        assertEquals("D:\\ E:\\", gauges.get(1).shortLabel(), "a disk by its drives alone");
        assertEquals(ResourcesEngine.getString("plan.resources.used.short", 4, 4) + " \u00b7 37",
                gauges.get(1).shortText());
        assertEquals(ResourcesEngine.getString("plan.resources.step", 3), gauges.get(2).shortLabel());
        assertEquals(ResourcesEngine.getString("plan.resources.used.short", 2, 3), gauges.get(2).shortText());
        assertEquals("\u2014", ResourceGauges.of(List.of(), -1, -1, MACHINE, Map.of()).getFirst().shortText());
    }

    @Test
    public void cpuActionsAreAddedWhenAStepHoldsTheCpu() {
        ResourceGauges.Gauge cpu = ResourceGauges.of(List.of(resource("cpu", 8, 3, 0)), 0.5, 0.4, MACHINE, Map.of())
                .getFirst();

        assertEquals(ResourcesEngine.getString("plan.resources.cpu.load", 50, 40) + " \u00b7 "
                + ResourcesEngine.getString("plan.resources.used", 3, 8), cpu.text());
    }

    @Test
    public void anUnknownLoadIsSaidSo() {
        ResourceGauges.Gauge cpu = ResourceGauges.of(List.of(), -1, -1, MACHINE, Map.of()).getFirst();

        assertTrue(cpu.fraction() < 0);
        assertTrue(cpu.part() < 0);
        assertTrue(ResourceGauges.of(List.of(), 0.2, 0.25, MACHINE, Map.of()).getFirst().part() <= 0.2,
                "two samples a little apart: Copybot never more than the machine");
        assertTrue(ResourceGauges.of(List.of(resource("gpu", 1, 1, 0)), 0, 0, MACHINE, Map.of()).get(1).part() < 0);
        assertEquals(ResourcesEngine.getString("plan.resources.cpu.unknown"), cpu.text());
    }

    @Test
    public void theActionsAtOnceReadAsALimitOfASettingNotAsALoad() {
        ResourceBundle french = ResourceBundle.getBundle("com.copybot.ui.i18n.uiBundle", Locale.FRENCH);

        assertEquals("2 actions en cours sur 2 max", french(french, "plan.resources.used", 2, 2));
        assertEquals("1 action en cours sur 4 max", french(french, "plan.resources.used", 1, 4));
        assertEquals("limite du réglage atteinte (2 max)", french(french, "plan.resources.limit-reached", 2));
        assertEquals("2/2 max", french(french, "plan.resources.used.short", 2, 2));
        assertEquals("lecture 45 Mo/s", french(french, "plan.resources.read", 45));
        assertEquals("écriture 0,4 Mo/s", french(french, "plan.resources.written", 0.4));
    }

    private static String french(ResourceBundle bundle, String key, Object... args) {
        return new MessageFormat(bundle.getString(key), Locale.FRENCH).format(args);
    }

    @Test
    public void theThroughputOfADiskComesFirst() {
        List<ResourceGauges.Gauge> gauges = ResourceGauges.of(List.of(
                        resource("disk:PhysicalDrive0", 2, 2, 37), resource("disk:PhysicalDrive1", 8, 1, 0)),
                0, 0, MACHINE, Map.of(
                        "disk:PhysicalDrive0", new DiskRates.Rate(45_200_000, 110_000_000),
                        "disk:PhysicalDrive1", new DiskRates.Rate(400_000, 0)));

        ResourceGauges.Gauge card = gauges.get(1);
        assertEquals(ResourcesEngine.getString("plan.resources.read", 45) + " · "
                + ResourcesEngine.getString("plan.resources.written", 110) + " · "
                + ResourcesEngine.getString("plan.resources.limit-reached", 2) + " · "
                + ResourcesEngine.getString("plan.resources.waiting", 37), card.text());
        assertEquals(ResourcesEngine.getString("plan.resources.read.short", 45) + " "
                + ResourcesEngine.getString("plan.resources.written.short", 110) + " "
                + ResourcesEngine.getString("plan.resources.rate.unit") + " · "
                + ResourcesEngine.getString("plan.resources.used.short", 2, 2) + " · 37", card.shortText());

        ResourceGauges.Gauge system = gauges.get(2);
        assertEquals(ResourcesEngine.getString("plan.resources.read", 0.4) + " · "
                + ResourcesEngine.getString("plan.resources.used", 1, 8), system.text(), "a direction at 0 left out");
        assertEquals(ResourcesEngine.getString("plan.resources.read.short", 0.4) + " "
                + ResourcesEngine.getString("plan.resources.rate.unit") + " · "
                + ResourcesEngine.getString("plan.resources.used.short", 1, 8), system.shortText());
    }

    @Test
    public void anIdleDiskShowsItsActionsAlone() {
        ResourceGauges.Gauge disk = ResourceGauges.of(List.of(resource("disk:PhysicalDrive0", 2, 1, 0)), 0, 0,
                MACHINE, Map.of("disk:PhysicalDrive0", new DiskRates.Rate(10_000, 0))).get(1);

        assertEquals(ResourcesEngine.getString("plan.resources.used", 1, 2), disk.text());
        assertEquals(ResourcesEngine.getString("plan.resources.used.short", 1, 2), disk.shortText());
    }

    @Test
    public void theTooltipSaysWhatTheActionsAtOnceAre() {
        List<ResourceGauges.Gauge> gauges = ResourceGauges.of(List.of(resource("disk:PhysicalDrive0", 2, 2, 0),
                resource("step:0", 2, 1, 0), resource("gpu", 1, 1, 0)), 0.5, 0.1, MACHINE, Map.of());

        assertEquals(ResourcesEngine.getString("plan.resources.cpu.legend"), gauges.get(0).tip());
        assertEquals(ResourcesEngine.getString("plan.resources.disk.tip", ResourcesEngine.getString("menu.edit"),
                ResourcesEngine.getString("menu.tools.resources"), ResourcesEngine.getString("pref.disks.capacity")),
                gauges.get(1).tip());
        assertEquals(ResourcesEngine.getString("plan.resources.limit.tip"), gauges.get(2).tip());
        assertEquals(ResourcesEngine.getString("plan.resources.step.tip",
                ResourcesEngine.getString("editor.advanced.maxConcurrency")), gauges.get(3).tip());
    }
}

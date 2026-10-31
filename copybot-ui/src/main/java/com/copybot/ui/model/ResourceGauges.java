package com.copybot.ui.model;

import com.copybot.engine.resources.DiskInventory;
import com.copybot.engine.resources.DiskKind;
import com.copybot.engine.resources.ResourceSettings;
import com.copybot.engine.resources.ResourceSnapshot;
import com.copybot.resources.ResourcesEngine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The gauges of the resources while a plan is prepared or run: the CPU always (the load of the machine and of
 * Copybot, and the actions holding "cpu" when a step asks for it), then only the resources the pipeline has asked
 * for so far (its disks, the GPU, the resources of the plugins, the limits of its steps).
 * <p>
 * The slots of a resource are a limit Copybot sets itself (how many actions at once), not a load: "2/2" on a memory
 * card read as a card at its max while it was nearly idle. The words say so, and a disk tells its real throughput
 * first, measured by Copybot.
 */
public final class ResourceGauges {

    /**
     * One gauge: what it measures, how full it is (0 to 1, negative when unknown), the part of it that is Copybot's
     * own (the CPU only, negative for the others), and in words; the short forms fit the line of the progress bar;
     * tip tells what the gauge counts, for its tooltip (empty when nothing more to say).
     */
    public record Gauge(String label, String shortLabel, double fraction, double part, String text, String shortText,
                        boolean saturated, String tip) {
    }

    private static final String CPU = ResourceSettings.CPU;
    private static final String GPU = ResourceSettings.GPU;
    private static final String DISK = ResourceSettings.DISK_PREFIX;
    private static final String STEP = "step:";
    private static final String SEPARATOR = " · ";
    private static final double MEGABYTE = 1_000_000;

    private ResourceGauges() {
    }

    /**
     * @param systemLoad  the CPU load of the machine, 0 to 1, negative when the system does not tell it
     * @param processLoad the CPU load of Copybot, the same way
     * @param volumes     the volumes of the machine, to name a disk by its drives
     * @param rates       the throughput of the disks, by resource name (see {@link DiskRates})
     */
    public static List<Gauge> of(List<ResourceSnapshot> snapshot, double systemLoad, double processLoad,
                                 List<DiskInventory.Volume> volumes, Map<String, DiskRates.Rate> rates) {
        List<ResourceSnapshot> sorted = new ArrayList<>(snapshot);
        sorted.sort(Comparator.comparingInt(ResourceGauges::rank).thenComparing(ResourceSnapshot::name));
        List<Gauge> gauges = new ArrayList<>();
        gauges.add(cpu(sorted.stream().filter(s -> s.name().equals(CPU)).findFirst().orElse(null),
                systemLoad, processLoad));
        for (ResourceSnapshot resource : sorted) {
            if (!resource.name().equals(CPU)) {
                DiskRates.Rate rate = rates.get(resource.name());
                gauges.add(new Gauge(label(resource.name(), volumes), shortLabel(resource.name(), volumes),
                        fraction(resource), -1, join(rateText(rate), usedText(resource)),
                        join(shortRateText(rate), shortUsedText(resource)), saturated(resource), tip(resource.name())));
            }
        }
        return gauges;
    }

    private static Gauge cpu(ResourceSnapshot held, double systemLoad, double processLoad) {
        String text = systemLoad < 0
                ? ResourcesEngine.getString("plan.resources.cpu.unknown")
                : ResourcesEngine.getString("plan.resources.cpu.load", percent(systemLoad),
                        percent(Math.max(processLoad, 0)));
        if (held != null) {
            text += SEPARATOR + usedText(held);
        }
        // the load of Copybot is a part of the load of the machine: never drawn longer than it
        double part = systemLoad < 0 || processLoad < 0 ? -1 : Math.min(processLoad, systemLoad);
        String shortText = systemLoad < 0 ? "—" : ResourcesEngine.getString("plan.resources.cpu.short", percent(systemLoad));
        String cpu = ResourcesEngine.getString("plan.resources.cpu");
        return new Gauge(cpu, cpu, systemLoad, part, text, shortText, held != null && saturated(held),
                ResourcesEngine.getString("plan.resources.cpu.legend"));
    }

    /** What the actions at once of a resource are: a limit of a setting, which the throughput of a disk is not. */
    private static String tip(String name) {
        if (name.startsWith(DISK)) {
            return ResourcesEngine.getString("plan.resources.disk.tip", ResourcesEngine.getString("menu.edit"),
                    ResourcesEngine.getString("menu.tools.resources"),
                    ResourcesEngine.getString("pref.disks.capacity"));
        }
        if (name.startsWith(STEP)) {
            return ResourcesEngine.getString("plan.resources.step.tip",
                    ResourcesEngine.getString("editor.advanced.maxConcurrency"));
        }
        return ResourcesEngine.getString("plan.resources.limit.tip");
    }

    /** The name on the line of the progress bar: the drives of a disk alone, the others as in full. */
    private static String shortLabel(String name, List<DiskInventory.Volume> volumes) {
        return name.startsWith(DISK) ? drives(name, volumes) : label(name, volumes);
    }

    private static String drives(String disk, List<DiskInventory.Volume> volumes) {
        List<String> drives = volumes.stream().filter(v -> v.resource().equals(disk)).map(DiskInventory.Volume::path)
                .toList();
        return drives.isEmpty() ? disk.substring(DISK.length()) : String.join(" ", drives);
    }

    /** "4/4 max", and "· 37" for the actions waiting. */
    private static String shortUsedText(ResourceSnapshot resource) {
        String text = ResourcesEngine.getString("plan.resources.used.short", resource.used(), resource.capacity());
        return resource.waiting() > 0 ? text + SEPARATOR + resource.waiting() : text;
    }

    /** "read 45 MB/s · write 110 MB/s", a direction without bytes left out; empty when idle or unknown. */
    private static String rateText(DiskRates.Rate rate) {
        if (rate == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        Number read = megabytes(rate.readPerSecond());
        Number written = megabytes(rate.writtenPerSecond());
        if (read != null) {
            parts.add(ResourcesEngine.getString("plan.resources.read", read));
        }
        if (written != null) {
            parts.add(ResourcesEngine.getString("plan.resources.written", written));
        }
        return String.join(SEPARATOR, parts);
    }

    /** "45↓ 110↑ MB/s", the same way. */
    private static String shortRateText(DiskRates.Rate rate) {
        if (rate == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        Number read = megabytes(rate.readPerSecond());
        Number written = megabytes(rate.writtenPerSecond());
        if (read != null) {
            parts.add(ResourcesEngine.getString("plan.resources.read.short", read));
        }
        if (written != null) {
            parts.add(ResourcesEngine.getString("plan.resources.written.short", written));
        }
        return parts.isEmpty() ? "" : String.join(" ", parts) + " " + ResourcesEngine.getString("plan.resources.rate.unit");
    }

    /** MB/s rounded to a whole number from 10 on, to a tenth below; null when that rounds to nothing. */
    private static Number megabytes(double bytesPerSecond) {
        double megabytes = bytesPerSecond / MEGABYTE;
        if (megabytes >= 10) {
            return Math.round(megabytes);
        }
        double tenths = Math.round(megabytes * 10) / 10.0;
        return tenths > 0 ? tenths : null;
    }

    private static String join(String first, String second) {
        return first.isEmpty() ? second : first + SEPARATOR + second;
    }

    private static String label(String name, List<DiskInventory.Volume> volumes) {
        if (name.equals(GPU)) {
            return ResourcesEngine.getString("plan.resources.gpu");
        }
        if (name.startsWith(STEP)) {
            try {
                return ResourcesEngine.getString("plan.resources.step", Integer.parseInt(name.substring(STEP.length())) + 1);
            } catch (NumberFormatException e) {
                return name;
            }
        }
        if (name.startsWith(DISK)) {
            String drivesText = drives(name, volumes);
            DiskKind kind = volumes.stream().filter(v -> v.resource().equals(name)).map(DiskInventory.Volume::kind)
                    .findFirst().orElse(DiskKind.UNKNOWN);
            return kind == DiskKind.UNKNOWN
                    ? ResourcesEngine.getString("plan.resources.disk", drivesText)
                    : ResourcesEngine.getString("plan.resources.disk.kind", drivesText,
                            ResourcesEngine.getString("pref.disks.kind." + kind.name()));
        }
        return name;
    }

    /**
     * "2 actions running of 2 max", or, full with actions waiting, "limit of the setting reached (2 max)": a limit
     * Copybot sets itself, never a load.
     */
    private static String usedText(ResourceSnapshot resource) {
        if (saturated(resource)) {
            return ResourcesEngine.getString("plan.resources.limit-reached", resource.capacity()) + SEPARATOR
                    + ResourcesEngine.getString("plan.resources.waiting", resource.waiting());
        }
        String text = ResourcesEngine.getString("plan.resources.used", resource.used(), resource.capacity());
        if (resource.waiting() > 0) {
            text += SEPARATOR + ResourcesEngine.getString("plan.resources.waiting", resource.waiting());
        }
        return text;
    }

    private static double fraction(ResourceSnapshot resource) {
        return resource.capacity() <= 0 ? 0 : Math.min(1, (double) resource.used() / resource.capacity());
    }

    /** Full, and actions wait for it: the resource that holds the pipeline back. */
    private static boolean saturated(ResourceSnapshot resource) {
        return resource.waiting() > 0 && resource.used() >= resource.capacity();
    }

    private static long percent(double load) {
        return Math.round(Math.min(1, load) * 100);
    }

    /** CPU, disks, GPU, the resources of the plugins, then the limits of the steps. */
    private static int rank(ResourceSnapshot resource) {
        String name = resource.name();
        if (name.equals(CPU)) {
            return 0;
        }
        if (name.startsWith(DISK)) {
            return 1;
        }
        if (name.equals(GPU)) {
            return 2;
        }
        return name.startsWith(STEP) ? 4 : 3;
    }
}

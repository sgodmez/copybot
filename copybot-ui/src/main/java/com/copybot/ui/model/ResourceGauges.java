package com.copybot.ui.model;

import com.copybot.engine.resources.DiskInventory;
import com.copybot.engine.resources.DiskKind;
import com.copybot.engine.resources.ResourceSettings;
import com.copybot.engine.resources.ResourceSnapshot;
import com.copybot.resources.ResourcesEngine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The gauges of the resources while a plan is prepared or run: the CPU always (the load of the machine and of
 * Copybot, and the actions holding "cpu" when a step asks for it), then only the resources the pipeline has asked
 * for so far (its disks, the GPU, the resources of the plugins, the limits of its steps).
 */
public final class ResourceGauges {

    /**
     * One gauge: what it measures, how full it is (0 to 1, negative when unknown), the part of it that is Copybot's
     * own (the CPU only, negative for the others), and in words.
     */
    public record Gauge(String label, double fraction, double part, String text, boolean saturated) {
    }

    private static final String CPU = ResourceSettings.CPU;
    private static final String GPU = ResourceSettings.GPU;
    private static final String DISK = ResourceSettings.DISK_PREFIX;
    private static final String STEP = "step:";

    private ResourceGauges() {
    }

    /**
     * @param systemLoad  the CPU load of the machine, 0 to 1, negative when the system does not tell it
     * @param processLoad the CPU load of Copybot, the same way
     * @param volumes     the volumes of the machine, to name a disk by its drives
     */
    public static List<Gauge> of(List<ResourceSnapshot> snapshot, double systemLoad, double processLoad,
                                 List<DiskInventory.Volume> volumes) {
        List<ResourceSnapshot> sorted = new ArrayList<>(snapshot);
        sorted.sort(Comparator.comparingInt(ResourceGauges::rank).thenComparing(ResourceSnapshot::name));
        List<Gauge> gauges = new ArrayList<>();
        gauges.add(cpu(sorted.stream().filter(s -> s.name().equals(CPU)).findFirst().orElse(null),
                systemLoad, processLoad));
        for (ResourceSnapshot resource : sorted) {
            if (!resource.name().equals(CPU)) {
                gauges.add(new Gauge(label(resource.name(), volumes), fraction(resource), -1, usedText(resource),
                        saturated(resource)));
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
            text += " · " + usedText(held);
        }
        // the load of Copybot is a part of the load of the machine: never drawn longer than it
        double part = systemLoad < 0 || processLoad < 0 ? -1 : Math.min(processLoad, systemLoad);
        return new Gauge(ResourcesEngine.getString("plan.resources.cpu"), systemLoad, part,
                text, held != null && saturated(held));
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
            Map<String, DiskKind> kinds = new LinkedHashMap<>();
            List<String> drives = new ArrayList<>();
            for (DiskInventory.Volume volume : volumes) {
                if (volume.resource().equals(name)) {
                    drives.add(volume.path());
                    kinds.put(name, volume.kind());
                }
            }
            String drivesText = drives.isEmpty() ? name.substring(DISK.length()) : String.join(" ", drives);
            DiskKind kind = kinds.getOrDefault(name, DiskKind.UNKNOWN);
            return kind == DiskKind.UNKNOWN
                    ? ResourcesEngine.getString("plan.resources.disk", drivesText)
                    : ResourcesEngine.getString("plan.resources.disk.kind", drivesText,
                            ResourcesEngine.getString("pref.disks.kind." + kind.name()));
        }
        return name;
    }

    private static String usedText(ResourceSnapshot resource) {
        String text = ResourcesEngine.getString("plan.resources.used", resource.used(), resource.capacity());
        if (resource.waiting() > 0) {
            text += " · " + ResourcesEngine.getString("plan.resources.waiting", resource.waiting());
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

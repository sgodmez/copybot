package com.copybot.ui.model;

import com.copybot.config.ConfigFiles;
import com.copybot.config.CopybotConfig;
import com.copybot.engine.resources.DiskInventory;
import com.copybot.engine.resources.DiskKind;
import com.copybot.engine.resources.ResourceSettings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * The disks of the preferences: one row per disk resource, with the volumes on it, its kind and the capacity a new
 * run gives it; and the changes of capacity and of groups, written back to the resources of the configuration.
 * Only exact disk names and groups of disks are changed: "cpu", "gpu", "disk:*" and the other groups stay as they
 * are. A capacity is written under a volume of the disk ("disk:D:\"), which stays right when the disk numbers of
 * the system change.
 */
public final class DiskSettingsModel {

    private static final String DISK = ResourceSettings.DISK_PREFIX;

    /**
     * A disk resource: its volumes (none for a disk named in the configuration and not there now), its kind, the
     * capacity in force, whether that capacity is the default one, and the other disks of its group.
     */
    public record Row(String resource, List<String> volumes, DiskKind kind, int capacity, boolean defaultCapacity,
                      List<String> groupedWith) {
    }

    private final UnaryOperator<String> resourceFor;
    private final Map<String, Integer> initialCapacities;
    private final List<List<String>> initialGroups;
    private final Map<String, Integer> capacities;
    private final List<List<String>> groups;
    /** Disk resource -> its volumes and kind, in the order of the volumes of the machine. */
    private final Map<String, List<String>> volumesOf = new LinkedHashMap<>();
    private final Map<String, DiskKind> kindOf = new LinkedHashMap<>();

    /**
     * @param resourceFor the disk resource of a name of the configuration (DiskResolver.resourceForConfigName)
     */
    public DiskSettingsModel(List<DiskInventory.Volume> volumes, ConfigFiles.Resources resources,
                             UnaryOperator<String> resourceFor) {
        this.resourceFor = resourceFor;
        this.initialCapacities = new LinkedHashMap<>(resources.capacities());
        this.initialGroups = copy(resources.groups());
        this.capacities = new LinkedHashMap<>(resources.capacities());
        this.groups = copy(resources.groups());
        volumes.forEach(this::addVolume);
        // a disk named in the configuration and not there now (unplugged, share not connected) stays shown
        List<String> named = new ArrayList<>(capacities.keySet());
        groups.stream().filter(DiskSettingsModel::diskGroup).forEach(named::addAll);
        for (String name : named) {
            if (diskName(name)) {
                String resource = resourceFor.apply(name);
                if (!volumesOf.containsKey(resource)) {
                    volumesOf.put(resource, new ArrayList<>());
                    kindOf.put(resource, DiskKind.UNKNOWN);
                }
            }
        }
    }

    /** Adds a volume, or a folder chosen by the user (a share that is no drive). */
    public void addVolume(DiskInventory.Volume volume) {
        List<String> onDisk = volumesOf.computeIfAbsent(volume.resource(), r -> new ArrayList<>());
        if (!onDisk.contains(volume.path())) {
            onDisk.add(volume.path());
        }
        kindOf.merge(volume.resource(), volume.kind(), (old, added) -> old == DiskKind.UNKNOWN ? added : old);
    }

    public List<Row> rows() {
        ResourceSettings settings = settings();
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, List<String>> disk : volumesOf.entrySet()) {
            String resource = disk.getKey();
            String canonical = settings.canonical(resource);
            List<String> groupedWith = groupOf(resource).stream().filter(r -> !r.equals(resource)).toList();
            rows.add(new Row(resource, List.copyOf(disk.getValue()), kindOf.get(resource),
                    settings.capacityFor(canonical), explicitCapacity(canonical) == null, groupedWith));
        }
        return rows;
    }

    /** Sets the capacity of a disk and of its group; null gives back the default one. */
    public void setCapacity(String resource, Integer capacity) {
        String canonical = settings().canonical(resource);
        List<String> group = groupOf(resource);
        List<String> disks = group.isEmpty() ? List.of(resource) : group;
        String key = null;
        for (String name : List.copyOf(capacities.keySet())) {
            if (diskName(name) && disks.contains(resourceFor.apply(name))) {
                if (key == null && resourceFor.apply(name).equals(canonical)) {
                    key = name; // the user's own spelling is kept
                }
                capacities.remove(name);
            }
        }
        if (capacity != null) {
            capacities.put(key != null ? key : configName(canonical), capacity);
        }
    }

    /** Puts two disks (and the groups they are in) in one group, which keeps the smaller capacity set. */
    public void group(String resource, String with) {
        if (resource.equals(with) || groupOf(resource).contains(with)) {
            return;
        }
        Integer capacity = smaller(explicitCapacity(settings().canonical(resource)),
                explicitCapacity(settings().canonical(with)));
        List<String> merged = new ArrayList<>();
        for (String disk : List.of(resource, with)) {
            List<String> existing = groupListOf(disk);
            if (existing != null) {
                groups.remove(existing);
                existing.stream().filter(n -> !merged.contains(n)).forEach(merged::add);
            } else {
                merged.add(configName(disk));
            }
        }
        groups.add(merged);
        setCapacity(resource, capacity);
    }

    /** Takes a disk out of its group: both keep the capacity the group had. */
    public void ungroup(String resource) {
        List<String> group = groupListOf(resource);
        if (group == null) {
            return;
        }
        Integer capacity = explicitCapacity(settings().canonical(resource));
        setCapacity(resource, null);
        group.removeIf(name -> resourceFor.apply(name).equals(resource));
        if (group.size() < 2) {
            groups.remove(group);
        }
        if (capacity != null) {
            setCapacity(resource, capacity);
            if (!group.isEmpty()) {
                setCapacity(resourceFor.apply(group.getFirst()), capacity);
            }
        }
    }

    public boolean changed() {
        return !capacities.equals(initialCapacities) || !groups.equals(initialGroups);
    }

    /** What to write in the configuration. */
    public ConfigFiles.Resources resources() {
        return new ConfigFiles.Resources(new LinkedHashMap<>(capacities), copy(groups));
    }

    /** The capacity a disk of this kind takes when none is set, "disk:*" included. */
    public int defaultCapacity(String resource) {
        Map<String, Integer> withoutExact = new LinkedHashMap<>(capacities);
        withoutExact.keySet().removeIf(DiskSettingsModel::diskName);
        return ResourceSettings.from(new CopybotConfig(null, null, withoutExact, List.of()), disks())
                .capacityFor(resource);
    }

    private ResourceSettings settings() {
        return ResourceSettings.from(new CopybotConfig(null, null, capacities, groups), disks());
    }

    private ResourceSettings.Disks disks() {
        return new ResourceSettings.Disks() {
            @Override
            public String resourceFor(String configName) {
                return resourceFor.apply(configName);
            }

            @Override
            public DiskKind kindOf(String resourceName) {
                return kindOf.getOrDefault(resourceName, DiskKind.UNKNOWN);
            }
        };
    }

    /** The capacity set for a canonical disk, null when it has the default one. */
    private Integer explicitCapacity(String canonical) {
        Integer found = null;
        for (Map.Entry<String, Integer> entry : capacities.entrySet()) {
            if (diskName(entry.getKey()) && resourceFor.apply(entry.getKey()).equals(canonical)) {
                found = smaller(found, entry.getValue());
            }
        }
        return found;
    }

    /** The disk resources of the group of a disk (itself included), empty when it is in none. */
    private List<String> groupOf(String resource) {
        List<String> group = groupListOf(resource);
        return group == null ? List.of() : group.stream().map(resourceFor).distinct().toList();
    }

    private List<String> groupListOf(String resource) {
        return groups.stream()
                .filter(DiskSettingsModel::diskGroup)
                .filter(g -> g.stream().map(resourceFor).anyMatch(resource::equals))
                .findFirst()
                .orElse(null);
    }

    /** The name to write for a disk: its first volume when it has one, which reads better and stays right. */
    private String configName(String resource) {
        List<String> volumes = volumesOf.getOrDefault(resource, List.of());
        return volumes.isEmpty() ? resource : DISK + volumes.getFirst();
    }

    private static boolean diskName(String name) {
        return name.startsWith(DISK) && !name.endsWith("*");
    }

    private static boolean diskGroup(List<String> group) {
        return !group.isEmpty() && group.stream().allMatch(DiskSettingsModel::diskName);
    }

    private static Integer smaller(Integer a, Integer b) {
        return a == null ? b : b == null ? a : Integer.valueOf(Math.min(a, b));
    }

    private static List<List<String>> copy(List<List<String>> groups) {
        List<List<String>> copy = new ArrayList<>();
        groups.forEach(g -> copy.add(new ArrayList<>(g)));
        return copy;
    }
}

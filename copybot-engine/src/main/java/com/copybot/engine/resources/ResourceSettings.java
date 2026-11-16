package com.copybot.engine.resources;

import com.copybot.config.CopybotConfig;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolved resource configuration: capacities (exact name, prefix pattern or
 * built-in default, which for a disk depends on its kind) and group aliasing
 * (several names counted as one resource).
 */
public final class ResourceSettings {

    public static final String CPU = "cpu";
    public static final String GPU = "gpu";
    public static final String DISK_PREFIX = "disk:";

    /** How disk names are read: the real disks, or names taken as written (tests). */
    public interface Disks {
        /** The resource a disk name of the configuration stands for. */
        String resourceFor(String configName);

        /** The kind of a disk resource, which sets its default capacity. */
        DiskKind kindOf(String resourceName);

        Disks SYSTEM = new Disks() {
            @Override
            public String resourceFor(String configName) {
                return DiskResolver.resourceForConfigName(configName);
            }

            @Override
            public DiskKind kindOf(String resourceName) {
                return DiskResolver.kindOf(resourceName);
            }
        };

        Disks AS_WRITTEN = new Disks() {
            @Override
            public String resourceFor(String configName) {
                return configName;
            }

            @Override
            public DiskKind kindOf(String resourceName) {
                return DiskKind.UNKNOWN;
            }
        };
    }

    private final Map<String, Integer> exact;
    private final Map<String, Integer> prefixes; // key stored without the trailing '*'
    private final Map<String, String> aliases;   // group member -> canonical (first of group)
    private final Disks disks;

    private ResourceSettings(Map<String, Integer> exact, Map<String, Integer> prefixes, Map<String, String> aliases,
                             Disks disks) {
        this.exact = exact;
        this.prefixes = prefixes;
        this.aliases = aliases;
        this.disks = disks;
    }

    public static ResourceSettings from(CopybotConfig config) {
        return from(config, Disks.SYSTEM);
    }

    /**
     * A disk named in the configuration by a volume or a folder ("disk:D:\") counts for the disk that holds it:
     * two volumes of one disk are one resource, and when both get a capacity the smaller one is kept.
     */
    public static ResourceSettings from(CopybotConfig config, Disks disks) {
        Map<String, Integer> exact = new HashMap<>();
        Map<String, Integer> prefixes = new HashMap<>();
        Map<String, String> aliases = new HashMap<>();
        if (config != null && config.resources() != null) {
            config.resources().forEach((name, capacity) -> {
                if (name.endsWith("*")) {
                    prefixes.put(name.substring(0, name.length() - 1), capacity);
                } else {
                    exact.merge(disks.resourceFor(name), capacity, Math::min);
                }
            });
        }
        if (config != null && config.resourceGroups() != null) {
            for (List<String> group : config.resourceGroups()) {
                String canonical = disks.resourceFor(group.get(0));
                for (String member : group) {
                    aliases.put(member, canonical);
                    aliases.put(disks.resourceFor(member), canonical);
                }
            }
        }
        return new ResourceSettings(exact, prefixes, aliases, disks);
    }

    /** Resolves group aliasing: returns the canonical resource name. */
    public String canonical(String name) {
        return aliases.getOrDefault(name, name);
    }

    /** Capacity for a (canonical) resource name: exact > longest prefix > built-in default. */
    public int capacityFor(String name) {
        Integer capacity = exact.get(name);
        if (capacity != null) {
            return capacity;
        }
        capacity = prefixes.entrySet().stream()
                .filter(e -> name.startsWith(e.getKey()))
                .max(Comparator.comparingInt(e -> e.getKey().length()))
                .map(Map.Entry::getValue)
                .orElse(null);
        if (capacity != null) {
            return capacity;
        }
        if (CPU.equals(name)) {
            return Runtime.getRuntime().availableProcessors();
        }
        if (GPU.equals(name)) {
            return 1;
        }
        if (name.startsWith(DISK_PREFIX)) {
            return disks.kindOf(name).defaultCapacity();
        }
        return 1;
    }
}

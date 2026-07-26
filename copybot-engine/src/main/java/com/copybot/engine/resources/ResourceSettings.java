package com.copybot.engine.resources;

import com.copybot.config.CopybotConfig;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolved resource configuration: capacities (exact name, prefix pattern or
 * built-in default) and group aliasing (several names counted as one resource).
 */
public final class ResourceSettings {

    public static final String CPU = "cpu";
    public static final String GPU = "gpu";
    public static final String DISK_PREFIX = "disk:";

    private final Map<String, Integer> exact;
    private final Map<String, Integer> prefixes; // key stored without the trailing '*'
    private final Map<String, String> aliases;   // group member -> canonical (first of group)

    private ResourceSettings(Map<String, Integer> exact, Map<String, Integer> prefixes, Map<String, String> aliases) {
        this.exact = exact;
        this.prefixes = prefixes;
        this.aliases = aliases;
    }

    public static ResourceSettings from(CopybotConfig config) {
        Map<String, Integer> exact = new HashMap<>();
        Map<String, Integer> prefixes = new HashMap<>();
        Map<String, String> aliases = new HashMap<>();
        if (config != null && config.resources() != null) {
            config.resources().forEach((name, capacity) -> {
                if (name.endsWith("*")) {
                    prefixes.put(name.substring(0, name.length() - 1), capacity);
                } else {
                    exact.put(name, capacity);
                }
            });
        }
        if (config != null && config.resourceGroups() != null) {
            for (List<String> group : config.resourceGroups()) {
                String canonical = group.get(0);
                group.forEach(member -> aliases.put(member, canonical));
            }
        }
        return new ResourceSettings(exact, prefixes, aliases);
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
            return 2;
        }
        return 1;
    }
}

package com.copybot.engine.listing;

import com.copybot.engine.pipeline.GroupingConfig;
import com.copybot.engine.pipeline.GroupingRuleConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The grouping presets shipped by the core, and their lenient expansion into plain rules. The presets of the loaded
 * plugins join them in the {@link GroupingPresetRegistry}.
 */
public final class GroupingPresets {

    private static final Map<String, List<GroupingRuleConfig>> PRESETS = new LinkedHashMap<>();

    static {
        PRESETS.put("raw-jpeg-xmp", List.of(
                rule("sameBase", List.of("cr3", "cr2", "nef", "arw", "raf", "orf", "rw2", "dng", "pef", "srw", "jpg",
                        "jpeg", "heic"), Map.of("jpg", "copy", "jpeg", "copy", "heic", "copy", "xmp", "copy")),
                rule("doubleExtension", null, Map.of("xmp", "copy"))));
        PRESETS.put("iphone", List.of(
                rule("sameBase", List.of("heic", "jpg", "jpeg"), Map.of("aae", "copy", "mov", "copy"))));
        PRESETS.put("action-cam", List.of(
                rule("sameBase", List.of("mp4"), Map.of("lrv", "copy", "thm", "copy"))));
        PRESETS.put("sony-xml", List.of(
                new GroupingRuleConfig(null, null, "regex", List.of("(.+)\\.mp4"), null, null, "read", "\\1M01\\.xml",
                        "base")));
        PRESETS.put("web-page", List.of(
                new GroupingRuleConfig(null, null, "folder", List.of("html", "htm"), null, "{base}_files", "copy", null,
                        null)));
    }

    private GroupingPresets() {
    }

    private static GroupingRuleConfig rule(String type, List<String> main, Map<String, String> companions) {
        return new GroupingRuleConfig(null, null, type, main, companions, null, null, null, null);
    }

    /** The preset names, in the order they are offered. */
    public static List<String> names() {
        return List.copyOf(PRESETS.keySet());
    }

    /** Whether the preset name is shipped. */
    public static boolean isKnown(String name) {
        return name != null && PRESETS.containsKey(name);
    }

    /** The rules of the core preset, empty when the name is not shipped. */
    public static Optional<List<GroupingRuleConfig>> rules(String name) {
        return Optional.ofNullable(name == null ? null : PRESETS.get(name));
    }

    /**
     * The rules of the configuration with each core preset replaced by its rules; the own rules pass through
     * unchanged. Idempotent: an already expanded configuration comes back as is. Lenient: the presets of a plugin and
     * the unknown ones are left out (the editor expands those of the loaded plugins from its catalog). A run expands
     * with {@link GroupingPresetRegistry#expand}, which never leaves a cited preset out. Absent configuration: no rule.
     */
    public static List<GroupingRuleConfig> expand(GroupingConfig config) {
        List<GroupingRuleConfig> expanded = new ArrayList<>();
        if (config == null || config.rules() == null) {
            return expanded;
        }
        for (GroupingRuleConfig rule : config.rules()) {
            if (rule == null) {
                continue;
            }
            if (rule.preset() == null) {
                expanded.add(rule);
            } else if (GroupingPresetRegistry.isCore(rule)) {
                rules(rule.preset()).ifPresent(expanded::addAll);
            }
        }
        return expanded;
    }
}

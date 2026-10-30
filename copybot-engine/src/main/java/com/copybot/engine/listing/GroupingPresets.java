package com.copybot.engine.listing;

import com.copybot.engine.pipeline.GroupingConfig;
import com.copybot.engine.pipeline.GroupingRuleConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The grouping presets shipped by the core, and their expansion into plain rules. */
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
                new GroupingRuleConfig(null, "regex", List.of("(.+)\\.mp4"), null, null, "read", "\\1M01\\.xml",
                        "base")));
        PRESETS.put("web-page", List.of(
                new GroupingRuleConfig(null, "folder", List.of("html", "htm"), null, "{base}_files", "copy", null,
                        null)));
        PRESETS.put("takeout", List.of(
                new GroupingRuleConfig(null, "regex", List.of("(.+\\.(?:jpe?g|heic|png|gif|mp4|mov))"), null, null,
                        "read", "\\1(?:\\.supplemental-metadata)?\\.json", "name")));
    }

    private GroupingPresets() {
    }

    private static GroupingRuleConfig rule(String type, List<String> main, Map<String, String> companions) {
        return new GroupingRuleConfig(null, type, main, companions, null, null, null, null);
    }

    /** The preset names, in the order they are offered. */
    public static List<String> names() {
        return List.copyOf(PRESETS.keySet());
    }

    /** Whether the preset name is shipped. */
    public static boolean isKnown(String name) {
        return name != null && PRESETS.containsKey(name);
    }

    /**
     * The rules of the configuration with each preset replaced by its rules; the own rules pass through unchanged.
     * Idempotent: an already expanded configuration comes back as is. Unknown presets are left out (the validation
     * refuses them). Absent configuration: no rule.
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
            } else if (isKnown(rule.preset())) {
                expanded.addAll(PRESETS.get(rule.preset()));
            }
        }
        return expanded;
    }

    /** Whether a preset of the configuration asks for the merge of the archive folders (takeout). */
    public static boolean impliesMergeFolder(GroupingConfig config) {
        return config != null && config.rules() != null
                && config.rules().stream().anyMatch(rule -> rule != null && "takeout".equals(rule.preset()));
    }
}

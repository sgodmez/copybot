package com.copybot.engine.listing;

import com.copybot.engine.pipeline.GroupingConfig;
import com.copybot.engine.pipeline.GroupingRuleConfig;
import com.copybot.engine.plugin.PluginDefinition;
import com.copybot.engine.plugin.TestPlugins;
import com.copybot.plugin.api.grouping.GroupingLink;
import com.copybot.plugin.api.grouping.GroupingPreset;
import com.copybot.plugin.api.grouping.GroupingRole;
import com.copybot.plugin.api.grouping.GroupingRule;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The grouping presets of the core and of the loaded plugins (spec plugin grouping presets §2, §4). */
class GroupingPresetRegistryTest {

    static final String TAKEOUT = "com.acme.takeout";

    /** The rule of the Takeout preset: the JSON of a photo, read only. */
    static final GroupingRule JSON = GroupingRule.regex("(.+\\.(?:jpe?g|mp4))",
            "\\1(?:\\.supplemental-metadata)?\\.json", GroupingRole.READ, GroupingLink.NAME);

    static PluginDefinition takeout(Supplier<List<GroupingPreset>> presets) {
        return TestPlugins.loaded(TAKEOUT, "1.0.0", TestPlugins.withPresets("takeout", presets));
    }

    static GroupingPresetRegistry withTakeout() {
        return GroupingPresetRegistry.of(List.of(takeout(() -> List.of(new GroupingPreset("takeout", JSON)))));
    }

    static GroupingRuleConfig cite(String preset, String plugin) {
        return new GroupingRuleConfig(preset, plugin, null, null, null, null, null, null, null);
    }

    @Test
    void aPluginPresetIsFoundByModuleNameAndCode() {
        GroupingPresetRegistry registry = withTakeout();

        assertTrue(registry.hasPlugin(TAKEOUT));
        assertEquals(Optional.of(List.of(new GroupingRuleConfig(null, null, "regex",
                        List.of("(.+\\.(?:jpe?g|mp4))"), null, null, "read", "\\1(?:\\.supplemental-metadata)?\\.json",
                        "name"))),
                registry.rules(cite("takeout", TAKEOUT)));
        assertEquals(Optional.empty(), registry.rules(cite("takeout", null)), "not a core preset");
        assertEquals(Optional.empty(), registry.rules(cite("nope", TAKEOUT)));
        assertEquals(1, registry.pluginPresets().size());
        GroupingPresetRegistry.PluginPreset preset = registry.pluginPresets().get(0);
        assertEquals(List.of(TAKEOUT, "takeout", "1.0.0", "takeout"),
                List.of(preset.plugin(), preset.pluginCode(), preset.version(), preset.code()));
    }

    @Test
    void theCorePresetsStayWithOrWithoutPlugins() {
        for (GroupingPresetRegistry registry : List.of(GroupingPresetRegistry.of(List.of()), withTakeout())) {
            for (String name : GroupingPresets.names()) {
                assertEquals(GroupingPresets.rules(name), registry.rules(cite(name, null)));
                assertEquals(GroupingPresets.rules(name), registry.rules(cite(name, " ")), "blank: the core");
            }
        }
        assertEquals(Optional.empty(), withTakeout().rules(cite("iphone", TAKEOUT)), "not the core one");
    }

    @Test
    void theExpansionReplacesEveryPresetAndRefusesAMissingOne() {
        GroupingPresetRegistry registry = withTakeout();
        GroupingRuleConfig own = GroupingRuleConfig.of(GroupingRule.doubleExtension(Map.of("xmp", GroupingRole.COPY)));

        List<GroupingRuleConfig> once = registry.expand(new GroupingConfig(List.of(own, cite("takeout", TAKEOUT),
                cite("iphone", null))));

        List<GroupingRuleConfig> expected = new ArrayList<>(List.of(own));
        expected.addAll(registry.rules(cite("takeout", TAKEOUT)).orElseThrow());
        expected.addAll(GroupingPresets.rules("iphone").orElseThrow());
        assertEquals(expected, once);
        assertEquals(once, registry.expand(new GroupingConfig(once)), "idempotent");
        assertEquals(List.of(), registry.expand(null));
        assertThrows(IllegalStateException.class,
                () -> registry.expand(new GroupingConfig(List.of(cite("takeout", "com.acme.absent")))));
        assertThrows(IllegalStateException.class,
                () -> GroupingPresetRegistry.of(List.of())
                        .expand(new GroupingConfig(List.of(cite("takeout", TAKEOUT)))));
    }

    @Test
    void aPresetWithInvalidRulesIsDiscardedWithAWarning() {
        GroupingPreset badRegex = new GroupingPreset("bad-regex",
                GroupingRule.regex("((", "x", GroupingRole.READ, null));
        GroupingPreset badFolder = new GroupingPreset("bad-folder",
                GroupingRule.folder(List.of("html"), "files_{base}", GroupingRole.COPY));
        GroupingPreset badRole = new GroupingPreset("bad-role", GroupingRule.sameBase(List.of("cr3"), nullRole()));
        GroupingPreset good = new GroupingPreset("good", JSON);
        GroupingPresetRegistry[] built = new GroupingPresetRegistry[1];
        List<LogRecord> records = recordLogs(() -> built[0] = GroupingPresetRegistry.of(List.of(
                takeout(() -> List.of(badRegex, badFolder, badRole, good)))));

        GroupingPresetRegistry registry = built[0];
        assertTrue(registry.rules(cite("good", TAKEOUT)).isPresent());
        for (String code : List.of("bad-regex", "bad-folder", "bad-role")) {
            assertEquals(Optional.empty(), registry.rules(cite(code, TAKEOUT)), code);
            assertTrue(registry.isDiscarded(TAKEOUT, code), code);
            assertTrue(records.stream().anyMatch(r -> r.getLevel() == Level.WARNING && r.getMessage().contains(code)),
                    code + ": " + records.stream().map(LogRecord::getMessage).toList());
        }
        assertFalse(registry.isDiscarded(TAKEOUT, "good"));
        assertEquals(List.of("good"), registry.pluginPresets().stream().map(p -> p.code()).toList());
    }

    private static Map<String, GroupingRole> nullRole() {
        Map<String, GroupingRole> companions = new LinkedHashMap<>();
        companions.put("xmp", null);
        return companions;
    }

    @Test
    void aPluginWhosePresetsCannotBeReadHasNone() {
        GroupingPresetRegistry[] registry = new GroupingPresetRegistry[1];
        List<LogRecord> records = recordLogs(() -> registry[0] = GroupingPresetRegistry.of(List.of(
                takeout(() -> List.of(new GroupingPreset("empty", List.of()))))));

        assertFalse(registry[0].hasPlugin(TAKEOUT), "as if it were absent");
        assertTrue(records.stream().anyMatch(r -> r.getLevel() == Level.WARNING && r.getMessage().contains(TAKEOUT)),
                records.stream().map(LogRecord::getMessage).toList().toString());
    }

    @Test
    void theMostRecentVersionOfAPluginBringsItsPresets() {
        GroupingPresetRegistry registry = GroupingPresetRegistry.of(List.of(
                TestPlugins.loaded(TAKEOUT, "2.0.0", TestPlugins.withPresets("takeout",
                        () -> List.of(new GroupingPreset("v2", JSON)))),
                TestPlugins.loaded(TAKEOUT, "1.0.0", TestPlugins.withPresets("takeout",
                        () -> List.of(new GroupingPreset("v1", JSON))))));

        assertTrue(registry.rules(cite("v2", TAKEOUT)).isPresent());
        assertEquals(Optional.empty(), registry.rules(cite("v1", TAKEOUT)));
    }

    @Test
    void aPresetCodeAndItsRulesAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> new GroupingPreset("Takeout", JSON));
        assertThrows(IllegalArgumentException.class, () -> new GroupingPreset("a b", JSON));
        assertThrows(IllegalArgumentException.class, () -> new GroupingPreset(null, JSON));
        assertThrows(IllegalArgumentException.class, () -> new GroupingPreset("empty", List.of()));
        assertThrows(NullPointerException.class, () -> new GroupingPreset("none", (List<GroupingRule>) null));
        assertThrows(NullPointerException.class, () -> new GroupingRule(null, null, null, null, null, null, null));
    }

    @Test
    void eachRuleTypeHasItsPipelineForm() {
        assertEquals(new GroupingRuleConfig(null, null, "sameBase", List.of("heic"),
                        Map.of("aae", "copy", "mov", "read"), null, null, null, null),
                GroupingRuleConfig.of(GroupingRule.sameBase(List.of("heic"),
                        Map.of("aae", GroupingRole.COPY, "mov", GroupingRole.READ))));
        assertEquals(new GroupingRuleConfig(null, null, "doubleExtension", null, Map.of("xmp", "copy"), null, null,
                        null, null),
                GroupingRuleConfig.of(GroupingRule.doubleExtension(Map.of("xmp", GroupingRole.COPY))));
        assertEquals(new GroupingRuleConfig(null, null, "folder", List.of("html"), null, "{base}_files", "copy", null,
                        null),
                GroupingRuleConfig.of(GroupingRule.folder(List.of("html"), "{base}_files", GroupingRole.COPY)));
        assertEquals(new GroupingRuleConfig(null, null, "regex", List.of("(.+)\\.mp4"), null, null, "read",
                        "\\1M01\\.xml", "base"),
                GroupingRuleConfig.of(GroupingRule.regex("(.+)\\.mp4", "\\1M01\\.xml", GroupingRole.READ,
                        GroupingLink.BASE)));
        assertEquals(new GroupingRuleConfig(null, null, "sameBase", List.of("cr3"), Map.of("xmp", "ignore"), null,
                        null, null, null),
                GroupingRuleConfig.of(GroupingRule.sameBase(List.of("cr3"), Map.of("xmp", GroupingRole.IGNORE))));
    }

    @Test
    void aBlankCompanionExtensionIsRefusedAndItsPluginHasNoPresets() {
        Map<String, GroupingRole> nullKey = new LinkedHashMap<>();
        nullKey.put(null, GroupingRole.COPY);
        assertThrows(IllegalArgumentException.class, () -> GroupingRule.sameBase(List.of("cr3"), nullKey));
        assertThrows(IllegalArgumentException.class,
                () -> GroupingRule.doubleExtension(Map.of(" ", GroupingRole.COPY)));

        GroupingPresetRegistry[] registry = new GroupingPresetRegistry[1];
        List<LogRecord> records = recordLogs(() -> registry[0] = GroupingPresetRegistry.of(List.of(takeout(
                () -> List.of(new GroupingPreset("blank",
                        GroupingRule.doubleExtension(Map.of("", GroupingRole.COPY))))))));

        assertFalse(registry[0].hasPlugin(TAKEOUT), "reported as a plugin whose presets cannot be read");
        assertTrue(records.stream().anyMatch(r -> r.getLevel() == Level.WARNING && r.getMessage().contains(TAKEOUT)));
    }

    @Test
    void theRegistryCannotBeChangedFromOutside() {
        GroupingPresetRegistry registry = withTakeout();

        assertThrows(UnsupportedOperationException.class, () -> registry.pluginPresets().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> registry.rules(cite("takeout", TAKEOUT)).orElseThrow().clear());
    }

    static List<LogRecord> recordLogs(Runnable action) {
        Logger jul = Logger.getLogger(GroupingPresetRegistry.class.getCanonicalName());
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        jul.addHandler(handler);
        try {
            action.run();
        } finally {
            jul.removeHandler(handler);
        }
        return records;
    }
}

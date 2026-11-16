package com.copybot.engine.pipeline;

import com.copybot.engine.listing.GroupingPresetRegistry;
import com.copybot.engine.plugin.TestPlugins;
import com.copybot.plugin.api.action.IOutAction;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WriteContext;
import com.copybot.plugin.api.action.WriteResult;
import com.copybot.plugin.api.grouping.GroupingPreset;
import com.copybot.plugin.api.grouping.GroupingRole;
import com.copybot.plugin.api.grouping.GroupingRule;
import com.copybot.resources.ResourcesEngine;
import com.copybot.utils.GsonUtil;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The validation of the presets a plugin brings, cited by a pipeline (spec plugin grouping presets §3, §4). */
class PluginGroupingPresetChecksTest {

    private static final String DEMO = "com.acme.demo";

    private static final IOutAction PLAIN_OUT = new IOutAction() {
        @Override
        public WriteResult write(WorkItem workItem, WriteContext context) {
            return null;
        }
    };

    /** The demo plugin: a valid copy preset, and one whose regex does not compile (discarded). */
    private static final GroupingPresetRegistry PRESETS = GroupingPresetRegistry.of(List.of(TestPlugins.loaded(DEMO,
            "1.0.0", TestPlugins.withPresets("demo", () -> List.of(
                    new GroupingPreset("thumbs",
                            GroupingRule.sameBase(List.of("mp4"), Map.of("thm", GroupingRole.COPY))),
                    new GroupingPreset("broken", GroupingRule.regex("((", "x", GroupingRole.READ, null)))))));

    private static PipelineConfig withRules(GroupingRuleConfig... rules) {
        return new PipelineConfig(null, null, null, null, null, null, null, null,
                new GroupingConfig(List.of(rules)), null);
    }

    private static GroupingRuleConfig cite(String preset, String plugin) {
        return new GroupingRuleConfig(preset, plugin, null, null, null, null, null, null, null);
    }

    @Test
    void thePluginIsReadFromThePipeline() {
        PipelineConfig config = GsonUtil.getGson().fromJson(
                "{\"grouping\": {\"rules\": [{\"preset\": \"thumbs\", \"plugin\": \"com.acme.demo\"}]}}",
                PipelineConfig.class);

        assertEquals(List.of(cite("thumbs", DEMO)), config.grouping().rules());
    }

    @Test
    void aPresetOfALoadedPluginIsAccepted() {
        assertEquals(Optional.empty(), PipelineChecks.groupingRefusal(withRules(cite("thumbs", DEMO)), null, PRESETS));
        assertEquals(Optional.empty(), PipelineChecks.groupingRefusal(withRules(cite("iphone", null)), null, PRESETS),
                "the core presets are unchanged");
    }

    @Test
    void aPresetOfAnAbsentPluginAsksToInstallIt() {
        Optional<String> refusal = PipelineChecks.groupingRefusal(
                withRules(cite("iphone", null), cite("takeout", "com.acme.takeout")), null, PRESETS);

        assertEquals(Optional.of(ResourcesEngine.getString("grouping.preset.plugin-missing", 2, "takeout",
                "com.acme.takeout")), refusal);
        assertTrue(refusal.orElseThrow().contains("com.acme.takeout"), refusal.orElseThrow());
    }

    @Test
    void aPresetOfAPluginWhosePresetsCannotBeReadPointsToTheLog() {
        GroupingPresetRegistry presets = GroupingPresetRegistry.of(List.of(TestPlugins.loaded(DEMO, "1.0.0",
                TestPlugins.withPresets("demo", () -> {
                    throw new IllegalStateException("no presets");
                }))));

        Optional<String> refusal = PipelineChecks.groupingRefusal(withRules(cite("thumbs", DEMO)), null, presets);

        assertEquals(Optional.of(ResourcesEngine.getString("grouping.preset.plugin-failed", 1, "thumbs", DEMO)),
                refusal);
        assertTrue(refusal.orElseThrow().contains(DEMO), refusal.orElseThrow());
        assertTrue(presets.hasFailed(DEMO));
        assertFalse(PRESETS.hasFailed(DEMO));
    }

    @Test
    void anUnknownPresetOfALoadedPluginIsRefused() {
        assertEquals(Optional.of(ResourcesEngine.getString("grouping.preset.unknown-in-plugin", 1, "nope", DEMO)),
                PipelineChecks.groupingRefusal(withRules(cite("nope", DEMO)), null, PRESETS));
        assertEquals(Optional.of(ResourcesEngine.getString("grouping.preset.unknown", 1, "thumbs")),
                PipelineChecks.groupingRefusal(withRules(cite("thumbs", null)), null, PRESETS),
                "without its plugin, a plugin preset is an unknown preset of the core");
    }

    @Test
    void aDiscardedPresetIsRefusedWhenCited() {
        assertEquals(Optional.of(ResourcesEngine.getString("grouping.preset.plugin-invalid", 1, "broken", DEMO)),
                PipelineChecks.groupingRefusal(withRules(cite("broken", DEMO)), null, PRESETS));
    }

    @Test
    void aPluginWithoutPresetIsRefused() {
        GroupingRuleConfig rule = new GroupingRuleConfig(null, DEMO, "doubleExtension", null, Map.of("xmp", "read"),
                null, null, null, null);

        assertEquals(Optional.of(ResourcesEngine.getString("grouping.rule.plugin-without-preset", 1, DEMO)),
                PipelineChecks.groupingRefusal(withRules(rule), null, PRESETS));
        assertTrue(ResourcesEngine.getString("grouping.rule.plugin-without-preset", 1, DEMO).contains(DEMO));
    }

    @Test
    void aBlankCompanionExtensionOfAPipelineRuleIsRefused() {
        Map<String, String> blank = new HashMap<>();
        blank.put(" ", "copy");
        Map<String, String> none = new HashMap<>();
        none.put(null, "copy");
        for (Map<String, String> companions : List.of(blank, none, Map.of("", "copy"))) {
            GroupingRuleConfig rule = new GroupingRuleConfig(null, null, "doubleExtension", null, companions, null,
                    null, null, null);
            assertTrue(PipelineChecks.ruleRefusal(rule, 1).isPresent(), companions.toString());
        }
    }

    @Test
    void theCopiedCompanionsOfAPluginPresetNeedAnOutputThatWritesThem() {
        assertTrue(PipelineChecks.groupingRefusal(withRules(cite("thumbs", DEMO)), PLAIN_OUT, PRESETS).isPresent());
    }
}

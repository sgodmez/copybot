package com.copybot.engine.pipeline;

import com.copybot.engine.listing.GroupingPresets;
import com.copybot.plugin.api.action.IOutAction;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WriteContext;
import com.copybot.plugin.api.action.WriteResult;
import com.copybot.resources.ResourcesEngine;
import com.copybot.utils.GsonUtil;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** The grouping and archives blocks of the pipeline, the presets and the validation of the rules. */
public class GroupingConfigTest {

    private static final PipelineStepConfig OUT = new PipelineStepConfig("p", "file.write", null, null, null, null, null, null);

    private static final IOutAction PLAIN_OUT = new IOutAction() {
        @Override
        public WriteResult write(WorkItem workItem, WriteContext context) {
            return null;
        }
    };

    private static final IOutAction COMPANION_OUT = new IOutAction() {
        @Override
        public WriteResult write(WorkItem workItem, WriteContext context) {
            return null;
        }

        @Override
        public boolean writesCompanions() {
            return true;
        }
    };

    private static PipelineConfig parse(String json) {
        return GsonUtil.getGson().fromJson(json, PipelineConfig.class);
    }

    private static PipelineConfig withRules(GroupingRuleConfig... rules) {
        return new PipelineConfig(null, null, null, OUT, null, null, null, null,
                new GroupingConfig(List.of(rules)), null);
    }

    private static GroupingRuleConfig preset(String name) {
        return new GroupingRuleConfig(name, null, null, null, null, null, null, null);
    }

    private static GroupingRuleConfig rule(String type, List<String> main, Map<String, String> companions,
                                           String folder, String role, String companion, String link) {
        return new GroupingRuleConfig(null, type, main, companions, folder, role, companion, link);
    }

    @Test
    public void aPipelineWithoutTheBlocksHasNone() {
        PipelineConfig config = parse("{\"inSteps\":[]}");

        assertNull(config.grouping());
        assertNull(config.archives());
        assertFalse(new ArchivesConfig(null, null, null, null, null).isUnfold());
        assertFalse(new ArchivesConfig(null, null, null, null, null).isNested());
        assertFalse(new ArchivesConfig(null, null, null, null, null).isMergeFolder());
    }

    @Test
    public void theSpecExampleIsRead() {
        PipelineConfig config = parse("""
                {"grouping": {"rules": [
                  { "preset": "raw-jpeg-xmp" },
                  { "type": "sameBase", "main": ["cr3", "nef", "arw", "jpg"], "companions": { "xmp": "copy", "thm": "copy" } },
                  { "type": "doubleExtension", "companions": { "xmp": "copy" } },
                  { "type": "folder", "main": ["html", "htm"], "folder": "{base}_files", "role": "copy" },
                  { "type": "regex", "main": "(.+\\\\.(?:jpe?g|heic|mp4))",
                    "companion": "\\\\1(?:\\\\.supplemental-metadata)?\\\\.json", "role": "read" }
                ]},
                "archives": { "unfold": true, "nested": false, "mergeFolder": false, "include": ["*.jpg"] }}""");

        List<GroupingRuleConfig> rules = config.grouping().rules();
        assertEquals(5, rules.size());
        assertEquals("raw-jpeg-xmp", rules.get(0).preset());
        assertEquals(List.of("cr3", "nef", "arw", "jpg"), rules.get(1).main());
        assertEquals(Map.of("xmp", "copy", "thm", "copy"), rules.get(1).companions());
        assertEquals("{base}_files", rules.get(3).folder());
        assertEquals("copy", rules.get(3).role());
        assertEquals(List.of("(.+\\.(?:jpe?g|heic|mp4))"), rules.get(4).main());
        assertEquals("\\1(?:\\.supplemental-metadata)?\\.json", rules.get(4).companion());
        assertEquals("read", rules.get(4).role());
        assertTrue(config.archives().isUnfold());
        assertFalse(config.archives().isNested());
        assertFalse(config.archives().isMergeFolder());
        assertEquals(List.of("*.jpg"), config.archives().include());
    }

    @Test
    public void theExistingConstructorsKeepNullBlocks() {
        PipelineConfig config = new PipelineConfig(null, null, null, null, null, null);
        assertNull(config.grouping());
        assertNull(config.archives());
        assertNull(new PipelineConfig(null, null, null, null, null, null, null, null).archives());
    }

    @Test
    public void presetsAreExpandedToTheirRules() {
        assertEquals(List.of(
                rule("sameBase", List.of("cr3", "cr2", "nef", "arw", "raf", "orf", "rw2", "dng", "pef", "srw", "jpg",
                        "jpeg", "heic"), Map.of("jpg", "copy", "jpeg", "copy", "heic", "copy", "xmp", "copy"),
                        null, null, null, null),
                rule("doubleExtension", null, Map.of("xmp", "copy"), null, null, null, null)),
                GroupingPresets.expand(new GroupingConfig(List.of(preset("raw-jpeg-xmp")))));
        assertEquals(List.of(rule("sameBase", List.of("heic", "jpg", "jpeg"), Map.of("aae", "copy", "mov", "copy"),
                        null, null, null, null)),
                GroupingPresets.expand(new GroupingConfig(List.of(preset("iphone")))));
        assertEquals(List.of(rule("sameBase", List.of("mp4"), Map.of("lrv", "copy", "thm", "copy"),
                        null, null, null, null)),
                GroupingPresets.expand(new GroupingConfig(List.of(preset("action-cam")))));
        assertEquals(List.of(rule("regex", List.of("(.+)\\.mp4"), null, null, "read", "\\1M01\\.xml", "base")),
                GroupingPresets.expand(new GroupingConfig(List.of(preset("sony-xml")))));
        assertEquals(List.of(rule("folder", List.of("html", "htm"), null, "{base}_files", "copy", null, null)),
                GroupingPresets.expand(new GroupingConfig(List.of(preset("web-page")))));
    }

    @Test
    public void expandingIsIdempotentAndKeepsOtherRules() {
        GroupingRuleConfig own = rule("doubleExtension", null, Map.of("xmp", "copy"), null, null, null, null);
        GroupingConfig config = new GroupingConfig(List.of(own, preset("iphone"), own));

        List<GroupingRuleConfig> once = GroupingPresets.expand(config);

        assertEquals(3, once.size());
        assertSame(own, once.get(0));
        assertEquals(once, GroupingPresets.expand(new GroupingConfig(once)));
        assertEquals(List.of(), GroupingPresets.expand(null));
    }

    @Test
    public void takeoutIsNoLongerAPresetOfTheCore() {
        assertFalse(GroupingPresets.isKnown("takeout"));
        assertEquals(List.of(), GroupingPresets.expand(new GroupingConfig(List.of(preset("takeout")))));
        assertEquals(Optional.of(ResourcesEngine.getString("grouping.preset.unknown", 1, "takeout")),
                PipelineChecks.groupingRefusal(withRules(preset("takeout")), PLAIN_OUT));
    }

    @Test
    public void presetNamesAreListedInOrder() {
        assertEquals(List.of("raw-jpeg-xmp", "iphone", "action-cam", "sony-xml", "web-page"),
                GroupingPresets.names());
    }

    @Test
    public void invalidRulesAreRefused() {
        assertTrue(PipelineChecks.groupingRefusal(withRules(
                rule("regex", List.of("(("), null, null, "read", "x", null)), PLAIN_OUT).isPresent());
        assertTrue(PipelineChecks.groupingRefusal(withRules(
                rule("folder", List.of("html"), null, "files_{base}", "copy", null, null)), PLAIN_OUT).isPresent());
        assertTrue(PipelineChecks.groupingRefusal(withRules(preset("nope")), PLAIN_OUT).isPresent());
        assertTrue(PipelineChecks.groupingRefusal(withRules(
                rule("sideways", List.of("a"), null, null, null, null, null)), PLAIN_OUT).isPresent());
        assertTrue(PipelineChecks.groupingRefusal(withRules(
                rule("regex", List.of("(.+)"), null, null, "keep", "\\1.json", null)), PLAIN_OUT).isPresent());
    }

    @Test
    public void aCompanionExpressionThatDoesNotCompileOrNamesAMissingGroupIsRefused() {
        assertEquals(Optional.of(ResourcesEngine.getString("grouping.rule.regex-invalid", 1, "\\1(?:\\.json")),
                PipelineChecks.groupingRefusal(withRules(
                        rule("regex", List.of("(.+)"), null, null, "read", "\\1(?:\\.json", null)), PLAIN_OUT));
        assertEquals(Optional.of(ResourcesEngine.getString("grouping.rule.regex-invalid", 1, "\\2\\.json")),
                PipelineChecks.groupingRefusal(withRules(
                        rule("regex", List.of("(.+)"), null, null, "read", "\\2\\.json", null)), PLAIN_OUT));
        assertTrue(PipelineChecks.groupingRefusal(withRules(
                rule("regex", List.of("(.+)_(\\d+)"), null, null, "read", "\\1\\2[.]json", null)), PLAIN_OUT).isEmpty());
    }

    @Test
    public void validRulesAndPresetsAreNotRefused() {
        assertTrue(PipelineChecks.groupingRefusal(withRules(preset("sony-xml"), preset("web-page"),
                rule("regex", List.of("(.+)\\.mp4"), null, null, "read", "\\1.xml", null)), COMPANION_OUT).isEmpty());
        assertTrue(PipelineChecks.groupingRefusal(parse("{}"), PLAIN_OUT).isEmpty());
    }

    @Test
    public void copyCompanionsNeedAnOutputThatWritesThem() {
        PipelineConfig copy = withRules(preset("iphone"));
        PipelineConfig readOnly = withRules(
                rule("regex", List.of("(.+)\\.mp4"), null, null, "read", "\\1.xml", null));

        Optional<String> refusal = PipelineChecks.groupingRefusal(copy, PLAIN_OUT);
        assertTrue(refusal.isPresent());
        assertTrue(refusal.get().contains("file.write"), refusal.get());
        assertTrue(PipelineChecks.groupingRefusal(copy, COMPANION_OUT).isEmpty());
        assertTrue(PipelineChecks.groupingRefusal(readOnly, PLAIN_OUT).isEmpty(), "READ companions are never written");
        assertTrue(PipelineChecks.groupingRefusal(copy, null).isEmpty(), "no output: no refusal");
    }
}

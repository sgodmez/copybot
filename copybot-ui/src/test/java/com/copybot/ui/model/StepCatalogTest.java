package com.copybot.ui.model;

import com.copybot.engine.listing.GroupingPresets;
import com.copybot.engine.pipeline.GroupingRuleConfig;
import com.copybot.engine.pipeline.StepType;
import com.copybot.engine.plugin.CatalogAction;
import com.copybot.engine.plugin.PluginCatalog;
import com.copybot.engine.plugin.PluginCatalog.FailedPlugin;
import com.copybot.ui.model.PipelineDocument.Section;
import com.copybot.utils.GsonUtil;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.copybot.ui.model.TestCatalog.CATALOG;
import static com.copybot.ui.model.TestCatalog.EXIF_1;
import static com.copybot.ui.model.TestCatalog.EXIF_2;
import static com.copybot.ui.model.TestCatalog.LAUNCH;
import static com.copybot.ui.model.TestCatalog.MAIL;
import static com.copybot.ui.model.TestCatalog.READ;
import static com.copybot.ui.model.TestCatalog.TAKEOUT;
import static com.copybot.ui.model.TestCatalog.WITH_PRESETS;
import static com.copybot.ui.model.TestCatalog.WRITE;
import static org.junit.jupiter.api.Assertions.*;

/** Which actions a section offers and which one a step resolves to (spec desktop-ui §3). */
public class StepCatalogTest {

    private static JsonObject step(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    public void aSectionOffersTheActionsOfItsStepTypeInTheirMostRecentVersion() {
        assertEquals(List.of(READ), CATALOG.forSection(Section.IN));
        assertEquals(List.of(EXIF_2), CATALOG.forSection(Section.ANALYZE));
        assertEquals(List.of(), CATALOG.forSection(Section.PROCESS));
        assertEquals(List.of(WRITE), CATALOG.forSection(Section.OUT));
        assertEquals(List.of(LAUNCH, MAIL), CATALOG.forSection(Section.RUN_END), "post.process and the plugins' actions");
    }

    @Test
    public void aStepWithoutPluginIsAnEmbeddedAction() {
        assertEquals(Optional.of(READ), CATALOG.find(Section.IN, step("{\"action\":\"file.read\"}")));
        assertEquals(Optional.of(READ), CATALOG.find(Section.IN, step("{\"plugin\":\" \",\"action\":\"file.read\"}")));
        assertEquals(Optional.of(READ), CATALOG.find(Section.IN, step("{\"plugin\":\"embedded\",\"action\":\"file.read\"}")));
    }

    @Test
    public void theVersionPicksTheCompatiblePlugin() {
        assertEquals(Optional.of(EXIF_2), CATALOG.find(Section.ANALYZE, step("{\"plugin\":\"com.acme.exif\",\"action\":\"exif.read\"}")));
        assertEquals(Optional.of(EXIF_1), CATALOG.find(Section.ANALYZE,
                step("{\"plugin\":\"com.acme.exif\",\"action\":\"exif.read\",\"version\":\"1.4\"}")));
        assertEquals(Optional.empty(), CATALOG.find(Section.ANALYZE,
                step("{\"plugin\":\"com.acme.exif\",\"action\":\"exif.read\",\"version\":\"3.0\"}")));
    }

    @Test
    public void anUnknownPluginActionOrSectionFindsNothing() {
        assertEquals(Optional.empty(), CATALOG.find(Section.ANALYZE, step("{\"plugin\":\"com.other\",\"action\":\"exif.read\"}")));
        assertEquals(Optional.empty(), CATALOG.find(Section.IN, step("{\"action\":\"file.list\"}")));
        assertEquals(Optional.empty(), CATALOG.find(Section.OUT, step("{\"action\":\"file.read\"}")), "file.read is an input");
        assertEquals(Optional.empty(), CATALOG.find(Section.IN, step("{}")));
    }

    @Test
    public void thePluginVersionIsPickedFirstThenTheActionInIt() {
        CatalogAction legacy = TestCatalog.action("com.acme.exif", "1.4.0", "exif.legacy", StepType.ANALYZE,
                TestCatalog.ExifConfig.class);
        StepCatalog catalog = TestCatalog.catalog(EXIF_2, EXIF_1, legacy, READ, WRITE);

        assertEquals(Optional.empty(), catalog.find(Section.ANALYZE,
                step("{\"plugin\":\"com.acme.exif\",\"action\":\"exif.legacy\"}")),
                "the engine picks 2.1.0, which has no exif.legacy: it would fail");
        assertEquals(Optional.of(legacy), catalog.find(Section.ANALYZE,
                step("{\"plugin\":\"com.acme.exif\",\"action\":\"exif.legacy\",\"version\":\"1.4\"}")));
        assertEquals(List.of(EXIF_2), catalog.forSection(Section.ANALYZE),
                "an action missing from the newest version is not offered");
    }

    @Test
    public void aStepResolvedToAFailedVersionFindsNoActionButThatPlugin() {
        FailedPlugin exif3 = new FailedPlugin("com.acme.exif", "3.0.0");
        StepCatalog catalog = new StepCatalog(new PluginCatalog(List.of(EXIF_2, EXIF_1, READ, WRITE), List.of(exif3)));
        JsonObject newest = step("{\"plugin\":\"com.acme.exif\",\"action\":\"exif.read\"}");

        assertEquals(Optional.empty(), catalog.find(Section.ANALYZE, newest), "the engine picks 3.0.0, not 2.1.0");
        assertEquals(Optional.of(exif3), catalog.failedPlugin(newest));
        assertEquals(List.of(), catalog.forSection(Section.ANALYZE), "a new step would resolve to 3.0.0 too");

        JsonObject pinned = step("{\"plugin\":\"com.acme.exif\",\"action\":\"exif.read\",\"version\":\"2.1\"}");
        assertEquals(Optional.of(EXIF_2), catalog.find(Section.ANALYZE, pinned));
        assertEquals(Optional.empty(), catalog.failedPlugin(pinned));
    }

    @Test
    public void anOlderFailedVersionOnlyConcernsTheStepsPinnedToIt() {
        FailedPlugin exif13 = new FailedPlugin("com.acme.exif", "1.3.0");
        StepCatalog catalog = new StepCatalog(new PluginCatalog(List.of(EXIF_2, EXIF_1, READ, WRITE), List.of(exif13)));

        assertEquals(Optional.of(EXIF_2), catalog.find(Section.ANALYZE, step("{\"plugin\":\"com.acme.exif\",\"action\":\"exif.read\"}")));
        assertEquals(List.of(EXIF_2), catalog.forSection(Section.ANALYZE));
        JsonObject pinned = step("{\"plugin\":\"com.acme.exif\",\"action\":\"exif.read\",\"version\":\"1.3\"}");
        assertEquals(Optional.empty(), catalog.find(Section.ANALYZE, pinned));
        assertEquals(Optional.of(exif13), catalog.failedPlugin(pinned));
        assertEquals(Optional.empty(), catalog.failedPlugin(step("{\"plugin\":\"com.other\",\"action\":\"x\"}")),
                "a plugin not loaded is not found, not failed");
    }

    @Test
    public void aFailedEmbeddedPluginConcernsTheStepsWithoutPlugin() {
        FailedPlugin embedded = new FailedPlugin(CatalogAction.EMBEDDED_PLUGIN, null);
        StepCatalog catalog = new StepCatalog(new PluginCatalog(List.of(EXIF_2), List.of(embedded)));

        assertEquals(Optional.of(embedded), catalog.failedPlugin(step("{\"action\":\"file.read\"}")));
        assertEquals(Optional.empty(), catalog.find(Section.IN, step("{\"action\":\"file.read\"}")));
    }

    @Test
    public void thePluginsOfAPipelineThatAreNotLoadedAtAllAreMissing() {
        FailedPlugin failed = new FailedPlugin("com.acme.failed", "1.0.0");
        StepCatalog catalog = new StepCatalog(new PluginCatalog(List.of(EXIF_2, READ, WRITE), List.of(failed)));
        PipelineDocument document = PipelineDocument.parse("""
                {"inSteps": [{"action": "file.read"}],
                 "analyseSteps": [{"plugin": "com.acme.exif", "action": "exif.read", "version": "9.0"},
                                  {"plugin": "com.acme.gps", "action": "gps.zone"},
                                  {"plugin": "com.acme.failed", "action": "x"}],
                 "actionSteps": [{"plugin": "com.acme.gps", "action": "other"}],
                 "outStep": {"plugin": "com.acme.tag", "action": "tag.write"}}""");

        assertEquals(List.of("com.acme.gps", "com.acme.tag"), catalog.missingPlugins(document));
        assertTrue(catalog.isLoaded("com.acme.exif"));
        assertTrue(catalog.isLoaded("com.acme.failed"), "failed, but loaded: installing would not help");
        assertFalse(catalog.isLoaded("com.acme.gps"));
    }

    @Test
    public void thePluginsOfTheSecondaryOutputsCountToo() {
        StepCatalog catalog = new StepCatalog(new PluginCatalog(List.of(READ, WRITE), List.of()));
        PipelineDocument document = PipelineDocument.parse("""
                {"inSteps": [{"action": "file.read"}],
                 "outStep": {"action": "file.write"},
                 "secondaryOutputs": [{"name": "Thumbs",
                                       "actionSteps": [{"plugin": "com.acme.thumbs", "action": "thumb.make"}],
                                       "outStep": {"plugin": "com.acme.ftp", "action": "ftp.write"}}]}""");

        assertEquals(List.of("com.acme.thumbs", "com.acme.ftp"), catalog.missingPlugins(document));
    }

    private static GroupingRuleConfig rule(String json) {
        return GsonUtil.getGson().fromJson(json, GroupingRuleConfig.class);
    }

    @Test
    public void aPluginPresetIsFoundByItsPluginAndCode() {
        assertEquals(List.of(TAKEOUT), WITH_PRESETS.groupingPresets());
        assertEquals(Optional.of(TAKEOUT), WITH_PRESETS.groupingPreset("com.acme.takeout", "takeout"));
        assertEquals(Optional.empty(), WITH_PRESETS.groupingPreset("com.acme.takeout", "other"));
        assertEquals(Optional.empty(), WITH_PRESETS.groupingPreset("com.acme.other", "takeout"));
        assertEquals(Optional.empty(), WITH_PRESETS.groupingPreset(null, "takeout"), "a core citation");
        assertTrue(WITH_PRESETS.isLoaded("com.acme.takeout"), "a plugin with presets only is loaded");
    }

    @Test
    public void theExpansionReplacesTheCorePresetsAndThoseOfTheLoadedPlugins() {
        GroupingRuleConfig own = rule("{\"type\":\"sameBase\",\"main\":[\"cr3\"],\"companions\":{\"xmp\":\"copy\"}}");
        List<GroupingRuleConfig> expanded = WITH_PRESETS.expandGroupingRules(List.of(own,
                rule("{\"preset\":\"iphone\"}"), rule("{\"preset\":\"takeout\",\"plugin\":\"com.acme.takeout\"}"),
                rule("{\"preset\":\"takeout\",\"plugin\":\"com.acme.absent\"}"), rule("{\"preset\":\"unknown\"}")));

        List<GroupingRuleConfig> expected = new java.util.ArrayList<>(List.of(own));
        expected.addAll(GroupingPresets.rules("iphone").orElseThrow());
        expected.addAll(TAKEOUT.rules());
        assertEquals(expected, expanded, "lenient: what is not available is left out");
    }

    @Test
    public void thePluginOfAPresetThatIsNotLoadedIsMissing() {
        PipelineDocument document = PipelineDocument.parse("""
                {"inSteps": [{"plugin": "com.acme.gps", "action": "gps.read"}],
                 "grouping": {"rules": [{"preset": "iphone"},
                                        {"preset": "takeout", "plugin": "com.acme.takeout"},
                                        {"preset": "maps", "plugin": "com.acme.maps"},
                                        {"preset": "more", "plugin": "com.acme.gps"},
                                        {"plugin": "com.acme.alone", "type": "sameBase"}]}}""");

        assertEquals(List.of("com.acme.gps", "com.acme.maps"), WITH_PRESETS.missingPlugins(document),
                "steps first; a core preset or a rule without preset names no plugin");
    }

    @Test
    public void aPluginWhosePresetsWereReadButAllDiscardedIsLoaded() {
        StepCatalog catalog = new StepCatalog(new PluginCatalog(List.of(), List.of(), List.of(),
                Set.of("com.acme.invalid")));
        PipelineDocument document = PipelineDocument.parse("""
                {"grouping": {"rules": [{"preset": "broken", "plugin": "com.acme.invalid"}]}}""");

        assertTrue(catalog.isLoaded("com.acme.invalid"));
        assertEquals(List.of(), catalog.missingPlugins(document), "no install prompt: the plugin is there");
    }
}

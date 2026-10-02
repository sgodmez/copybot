package com.copybot.ui.model;

import com.copybot.engine.sample.Sample;
import com.copybot.engine.sample.SampleItem;
import com.copybot.plugin.api.config.ConfigSchema;
import com.copybot.resources.ResourcesEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class PatternHelperModelTest {

    @BeforeAll
    public static void registerUiBundle() {
        ResourcesEngine.registerBundle("com.copybot.ui.i18n.uiBundle");
    }

    private static SampleItem item(String name, Map<String, String> display) {
        return new SampleItem(name, name, display, Optional.empty());
    }

    private static final Sample SAMPLE = new Sample(List.of(
            item("a.JPG", Map.of("name", "a.JPG", "captureDate.Y", "2026")),
            item("b.MP4", Map.of("name", "b.MP4")),
            item("c.JPG", Map.of("name", "c.JPG", "captureDate.Y", "2025")),
            item("d.MP4", Map.of("name", "d.MP4", "captureDate.Y", " "))),
            150, true, List.of(), Optional.empty());

    @Test
    public void syntaxErrorIsEmptyForAValidPatternAndNamesTheInvalidOne() {
        assertTrue(PatternHelperModel.syntaxError("/nas/{captureDate.Y}").isEmpty());
        assertTrue(PatternHelperModel.syntaxError("/nas/{captureDate.Y|").orElseThrow().contains("/nas/{captureDate.Y|"));
    }

    @Test
    public void neverSampledOrNewInputMeansList() {
        assertEquals(PatternHelperModel.Rerun.LIST, PatternHelperModel.rerun(null, null, "[in]", "[a]"));
        assertEquals(PatternHelperModel.Rerun.LIST, PatternHelperModel.rerun("[in]", "[a]", "[in2]", "[a]"));
    }

    @Test
    public void otherAnalysesMeanAnalyseOnlyAndNothingChangedMeansNone() {
        assertEquals(PatternHelperModel.Rerun.ANALYSE, PatternHelperModel.rerun("[in]", "[a]", "[in]", "[a2]"));
        assertEquals(PatternHelperModel.Rerun.NONE, PatternHelperModel.rerun("[in]", "[a]", "[in]", "[a]"));
    }

    // ---- keys ----

    @Test
    public void keysAreCompleteFirstThenPartialWithExamplesAndTheFilesMissingThem() {
        List<PatternHelperModel.KeyRow> keys = PatternHelperModel.keys(SAMPLE);

        assertEquals(List.of("name", "captureDate.Y"), keys.stream().map(PatternHelperModel.KeyRow::key).toList());
        assertEquals(List.of("a.JPG", "b.MP4", "c.JPG"), keys.get(0).examples());
        assertEquals(List.of(), keys.get(0).missingIn());
        assertFalse(keys.get(0).partial());
        assertEquals(new PatternHelperModel.KeyRow("captureDate.Y", List.of("2026", "2025"), 2, 4, List.of("b.MP4", "d.MP4")),
                keys.get(1));
        assertTrue(keys.get(1).partial());
    }

    @Test
    public void aSourceProducingSeveralItemsIsNamedOnceAmongTheMissing() {
        Sample sample = new Sample(List.of(new SampleItem("x.NEF", "x.jpg", Map.of("name", "x.jpg"), Optional.empty()),
                new SampleItem("x.NEF", "t.jpg", Map.of("name", "t.jpg"), Optional.empty()),
                item("y.NEF", Map.of("name", "y.NEF", "k", "1"))),
                3, false, List.of(), Optional.empty());

        PatternHelperModel.KeyRow k = PatternHelperModel.keys(sample).get(1);
        assertEquals("k", k.key());
        assertEquals(List.of("x.NEF"), k.missingIn());
    }

    @Test
    public void itemsInErrorAreLeftOutOfTheKeys() {
        Sample sample = new Sample(List.of(item("a.JPG", Map.of("name", "a.JPG")),
                new SampleItem("b.JPG", "b.JPG", Map.of("name", "b.JPG", "x", "1"), Optional.of("boom"))),
                2, false, List.of(), Optional.empty());

        assertEquals(List.of(new PatternHelperModel.KeyRow("name", List.of("a.JPG"), 1, 1, List.of())),
                PatternHelperModel.keys(sample));
    }

    @Test
    public void withoutAUsableSampleTheKeysAreTheKnownVariablesWithoutExamples() {
        for (Sample sample : java.util.Arrays.asList(null, Sample.failed("no card"))) {
            List<PatternHelperModel.KeyRow> keys = PatternHelperModel.keys(sample);
            assertEquals(ConfigSchema.PATTERN_VARIABLES, keys.stream().map(PatternHelperModel.KeyRow::key).toList());
            assertTrue(keys.stream().allMatch(row -> row.examples().isEmpty() && !row.partial()));
        }
    }

    @Test
    public void theTooltipNamesAtMostTenFilesThenTheCountOfTheOthers() {
        assertEquals(ResourcesEngine.getString("helper.missing-in", "b.MP4, d.MP4"),
                PatternHelperModel.missingTooltip(PatternHelperModel.keys(SAMPLE).get(1)));

        List<String> names = new ArrayList<>();
        for (int i = 1; i <= 13; i++) {
            names.add("f" + i + ".MP4");
        }
        PatternHelperModel.KeyRow row = new PatternHelperModel.KeyRow("k", List.of(), 0, 13, names);
        String ten = String.join(", ", names.subList(0, 10));
        assertEquals(ResourcesEngine.getString("helper.missing-in", ten + " " + ResourcesEngine.getString("helper.and-others", 3)),
                PatternHelperModel.missingTooltip(row));
        assertTrue(PatternHelperModel.missingTooltip(row).contains("f10.MP4"));
        assertFalse(PatternHelperModel.missingTooltip(row).contains("f11.MP4"));
    }

    // ---- summary ----

    @Test
    public void theSummaryCountsTheResolvedAndTheMissingAndSaysTheEffect() {
        PatternHelperModel.Summary summary = PatternHelperModel.summary("/nas/{captureDate.Y}/{name}", "skip", SAMPLE);

        assertTrue(summary.syntaxError().isEmpty());
        assertEquals(2, summary.ok());
        assertEquals(2, summary.missing());
        assertEquals(ResourcesEngine.getString("helper.effect.skip"), summary.effect());
    }

    @Test
    public void theRowsPutTheMissingFirstThenTheOthersInSampleOrder() {
        PatternHelperModel.Summary summary = PatternHelperModel.summary("/nas/{captureDate.Y}/{name}", "error", SAMPLE);

        assertEquals(List.of("b.MP4", "d.MP4", "a.JPG", "c.JPG"),
                summary.rows().stream().map(PatternHelperModel.Row::name).toList());
        PatternHelperModel.Row video = summary.rows().get(0);
        assertEquals(List.of("{captureDate.Y}"), video.missing());
        assertTrue(video.hasMissing());
        assertEquals("/nas/2026/a.JPG", summary.rows().get(2).path());
        assertFalse(summary.rows().get(2).hasMissing());
    }

    @Test
    public void theExampleIsTheFirstMissingRowElseTheFirstRow() {
        assertEquals("b.MP4", PatternHelperModel.summary("/nas/{captureDate.Y}", "error", SAMPLE).example().orElseThrow().name());
        PatternHelperModel.Summary complete = PatternHelperModel.summary("/nas/{name}", "error", SAMPLE);
        assertEquals("a.JPG", complete.example().orElseThrow().name());
        assertEquals(0, complete.missing());
        assertEquals(4, complete.ok());
    }

    @Test
    public void anUnknownOrAbsentPolicyIsTheDefaultErrorAndLiteralIsKept() {
        assertEquals(ResourcesEngine.getString("helper.effect.error"),
                PatternHelperModel.summary("{captureDate.Y}", null, SAMPLE).effect());
        assertEquals(ResourcesEngine.getString("helper.effect.error"),
                PatternHelperModel.summary("{captureDate.Y}", "bogus", SAMPLE).effect());
        assertEquals(ResourcesEngine.getString("helper.effect.literal"),
                PatternHelperModel.summary("{captureDate.Y}", "literal", SAMPLE).effect());
    }

    @Test
    public void anInvalidPatternGivesTheSyntaxErrorAndNoRows() {
        PatternHelperModel.Summary summary = PatternHelperModel.summary("/nas/{captureDate.Y|", "error", SAMPLE);

        assertTrue(summary.syntaxError().orElseThrow().contains("/nas/{captureDate.Y|"));
        assertTrue(summary.rows().isEmpty());
        assertTrue(summary.example().isEmpty());
    }

    @Test
    public void anItemInErrorShowsItsErrorAndCountsNeitherOkNorMissing() {
        Sample sample = new Sample(List.of(new SampleItem("b.JPG", "b.JPG", Map.of(), Optional.of("boom")),
                item("a.JPG", Map.of("name", "a.JPG"))),
                2, false, List.of(), Optional.empty());

        PatternHelperModel.Summary summary = PatternHelperModel.summary("{name}", "error", sample);
        assertEquals("boom", summary.rows().getFirst().error());
        assertEquals(1, summary.ok());
        assertEquals(0, summary.missing());
    }

    @Test
    public void rowsKeepTheSampleOrder() {
        assertEquals(List.of("a.JPG", "b.MP4", "c.JPG", "d.MP4"),
                PatternHelperModel.rows("/{captureDate.Y}", SAMPLE).stream().map(PatternHelperModel.Row::name).toList());
        assertTrue(PatternHelperModel.rows("/{x|", SAMPLE).isEmpty());
    }

    // ---- misc ----

    @Test
    public void insertPutsTheKeyAtTheCaret() {
        assertEquals("/nas/{name}/x", PatternHelperModel.insert("/nas//x", 5, "name"));
        assertEquals("{name}", PatternHelperModel.insert("", 3, "name"));
    }

    @Test
    public void theSampleLineCountsTheSourceFilesAndTellsWhenTheListingWasCut() {
        assertEquals(4, PatternHelperModel.sampledFiles(SAMPLE));
        assertEquals(ResourcesEngine.getString("helper.sample", 4, 150) + " " + ResourcesEngine.getString("helper.truncated"),
                PatternHelperModel.sampleLine(SAMPLE));
        Sample whole = new Sample(SAMPLE.items(), 4, false, List.of(), Optional.empty());
        assertEquals(ResourcesEngine.getString("helper.sample", 4, 4), PatternHelperModel.sampleLine(whole));
    }
}

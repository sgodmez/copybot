package com.copybot.ui.model;

import com.copybot.engine.sample.Sample;
import com.copybot.engine.sample.SampleItem;
import com.copybot.resources.ResourcesEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class PatternHelperModelTest {

    private static SampleItem item(String name, Map<String, String> display) {
        return new SampleItem(name, name, display, Optional.empty());
    }

    private static final Sample SAMPLE = new Sample(List.of(
            item("a.JPG", Map.of("name", "a.JPG", "captureDate.Y", "2026")),
            item("b.JPG", Map.of("name", "b.JPG", "captureDate.Y", "2025")),
            item("c.MP4", Map.of("name", "c.MP4", "captureDate.Y", " "))),
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

    @Test
    public void keysAreCompleteFirstThenPartialWithExamplesAndPresence() {
        List<PatternHelperModel.KeyRow> keys = PatternHelperModel.keys(SAMPLE);

        assertEquals(List.of("name", "captureDate.Y"), keys.stream().map(PatternHelperModel.KeyRow::key).toList());
        assertEquals(List.of("a.JPG", "b.JPG", "c.MP4"), keys.get(0).examples());
        assertEquals(new PatternHelperModel.KeyRow("captureDate.Y", List.of("2026", "2025"), 2, 3), keys.get(1));
        assertTrue(keys.get(1).partial());
    }

    @Test
    public void itemsInErrorAreLeftOutOfTheKeys() {
        Sample sample = new Sample(List.of(item("a.JPG", Map.of("name", "a.JPG")),
                new SampleItem("b.JPG", "b.JPG", Map.of("name", "b.JPG", "x", "1"), Optional.of("boom"))),
                2, false, List.of(), Optional.empty());

        assertEquals(List.of(new PatternHelperModel.KeyRow("name", List.of("a.JPG"), 1, 1)), PatternHelperModel.keys(sample));
    }

    @Test
    public void thePreviewResolvesEveryItemAndShowsTheEffectOfAMissingKey() {
        PatternHelperModel.Preview preview = PatternHelperModel.preview("/nas/{captureDate.Y}/{name}", "skip", SAMPLE);

        assertTrue(preview.syntaxError().isEmpty());
        assertEquals("/nas/2026/a.JPG", preview.rows().get(0).path());
        assertNull(preview.rows().get(0).effect());
        PatternHelperModel.PreviewRow video = preview.rows().get(2);
        assertEquals(List.of("{captureDate.Y}"), video.missing());
        assertEquals(ResourcesEngine.getString("helper.effect.skip"), video.effect());
    }

    @Test
    public void anUnknownOrAbsentPolicyIsTheDefaultError() {
        assertEquals(ResourcesEngine.getString("helper.effect.error"),
                PatternHelperModel.preview("{captureDate.Y}", null, SAMPLE).rows().get(2).effect());
    }

    @Test
    public void anInvalidPatternGivesTheSyntaxErrorAndNoRows() {
        PatternHelperModel.Preview preview = PatternHelperModel.preview("/nas/{captureDate.Y|", "error", SAMPLE);

        assertTrue(preview.syntaxError().orElseThrow().contains("/nas/{captureDate.Y|"));
        assertTrue(preview.rows().isEmpty());
    }

    @Test
    public void anItemInErrorShowsItsError() {
        Sample sample = new Sample(List.of(new SampleItem("b.JPG", "b.JPG", Map.of(), Optional.of("boom"))),
                1, false, List.of(), Optional.empty());

        assertEquals("boom", PatternHelperModel.preview("{name}", "error", sample).rows().getFirst().error());
    }

    @Test
    public void insertPutsTheKeyAtTheCaret() {
        assertEquals("/nas/{name}/x", PatternHelperModel.insert("/nas//x", 5, "name"));
        assertEquals("{name}", PatternHelperModel.insert("", 3, "name"));
    }

    @Test
    public void theStatusCountsTheSourceFilesAndTellsWhenTheListingWasCut() {
        assertEquals(ResourcesEngine.getString("helper.sample", 3, 150) + " " + ResourcesEngine.getString("helper.truncated"),
                PatternHelperModel.status(SAMPLE));
        assertEquals("no card", PatternHelperModel.status(Sample.failed("no card")));
    }
}

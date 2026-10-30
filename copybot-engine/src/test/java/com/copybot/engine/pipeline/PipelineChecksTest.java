package com.copybot.engine.pipeline;

import com.copybot.exception.CopybotException;
import com.copybot.resources.ResourcesEngine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** What a pipeline needs to be listed and to be executed (steps validation). */
public class PipelineChecksTest {

    private static final PipelineStepConfig STEP = new PipelineStepConfig(null, "file.read", null, null, null, null, null, null);

    private static PipelineConfig config(List<PipelineStepConfig> in, List<PipelineStepConfig> analyse,
                                         List<PipelineStepConfig> process, PipelineStepConfig out) {
        return new PipelineConfig(in, analyse, process, out, null, null);
    }

    @Test
    public void aMissingOrEmptyInputIsReported() {
        assertTrue(PipelineChecks.missingInput(config(null, null, null, STEP)).isPresent());
        assertTrue(PipelineChecks.missingInput(config(List.of(), null, null, STEP)).isPresent());
        assertEquals(ResourcesEngine.getString("pipeline.no-input"),
                PipelineChecks.missingInput(config(null, null, null, STEP)).orElseThrow());
        assertTrue(PipelineChecks.missingInput(config(List.of(STEP), null, null, null)).isEmpty());
    }

    @Test
    public void neitherOutputNorProcessDoesNothing() {
        assertEquals(ResourcesEngine.getString("pipeline.does-nothing"),
                PipelineChecks.doesNothing(config(List.of(STEP), null, null, null)).orElseThrow());
        assertTrue(PipelineChecks.doesNothing(config(List.of(STEP), List.of(), List.of(), null)).isPresent());
    }

    @Test
    public void analyseStepsNeverCountAsDoingSomething() {
        assertTrue(PipelineChecks.doesNothing(config(List.of(STEP), List.of(STEP), null, null)).isPresent());
    }

    @Test
    public void anOutputOrAProcessStepIsEnough() {
        assertTrue(PipelineChecks.doesNothing(config(List.of(STEP), null, null, STEP)).isEmpty());
        assertTrue(PipelineChecks.doesNothing(config(List.of(STEP), null, List.of(STEP), null)).isEmpty());
        assertTrue(PipelineChecks.doesNothing(config(List.of(STEP), null, List.of(STEP), STEP)).isEmpty());
    }

    @Test
    public void prepareOnlyNeedsAnInput() {
        assertDoesNotThrow(() -> PipelineChecks.requirePreparable(config(List.of(STEP), null, null, null)));
        CopybotException e = assertThrows(CopybotException.class,
                () -> PipelineChecks.requirePreparable(config(null, null, List.of(STEP), STEP)));
        assertEquals(ResourcesEngine.getString("pipeline.no-input"), e.getMessage());
    }

    @Test
    public void executionNeedsAnInputThenSomethingToDo() {
        assertDoesNotThrow(() -> PipelineChecks.requireExecutable(config(List.of(STEP), null, List.of(STEP), null)));
        assertEquals(ResourcesEngine.getString("pipeline.no-input"), assertThrows(CopybotException.class,
                () -> PipelineChecks.requireExecutable(config(null, null, null, null))).getMessage());
        assertEquals(ResourcesEngine.getString("pipeline.does-nothing"), assertThrows(CopybotException.class,
                () -> PipelineChecks.requireExecutable(config(List.of(STEP), null, null, null))).getMessage());
    }

    @Test
    public void secondaryOutputsAreCheckedByNameAndRank() {
        SecondaryOutputConfig ok = new SecondaryOutputConfig("Thumbs", null, null, STEP);
        assertTrue(PipelineChecks.secondaryOutputsRefusal(config(null, null, null, null)).isEmpty());
        assertTrue(PipelineChecks.secondaryOutputsRefusal(withBranches(ok, new SecondaryOutputConfig("Copy", null, null, STEP))).isEmpty());

        String noName = PipelineChecks.secondaryOutputsRefusal(withBranches(ok, new SecondaryOutputConfig(" ", null, null, STEP))).orElseThrow();
        assertEquals(ResourcesEngine.getString("secondary.name.missing", 2), noName);
        assertEquals(ResourcesEngine.getString("secondary.name.duplicate", 2, "thumbs"),
                PipelineChecks.secondaryOutputsRefusal(withBranches(ok, new SecondaryOutputConfig("thumbs", null, null, STEP))).orElseThrow());
        assertEquals(ResourcesEngine.getString("secondary.out.missing", "Lonely"),
                PipelineChecks.secondaryOutputsRefusal(withBranches(new SecondaryOutputConfig("Lonely", null, null, null))).orElseThrow());

        SecondaryOutputConfig[] nine = new SecondaryOutputConfig[9];
        for (int i = 0; i < nine.length; i++) {
            nine[i] = new SecondaryOutputConfig("b" + i, null, null, STEP);
        }
        assertEquals(ResourcesEngine.getString("secondary.too-many", 8, 9),
                PipelineChecks.secondaryOutputsRefusal(withBranches(nine)).orElseThrow());
        assertTrue(PipelineChecks.secondaryOutputsRefusal(withBranches(java.util.Arrays.copyOf(nine, 8))).isEmpty());
    }

    @Test
    public void aBranchAloneDoesNotMakeThePipelineDoSomething() {
        PipelineConfig onlyBranch = withBranches(new SecondaryOutputConfig("Thumbs", null, null, STEP));
        assertTrue(PipelineChecks.doesNothing(onlyBranch).isPresent());
    }

    private static PipelineConfig withBranches(SecondaryOutputConfig... branches) {
        return new PipelineConfig(List.of(STEP), null, null, null, null, null, null, null, null, null,
                List.of(branches));
    }

    @Test
    public void theSecondaryOutputMessagesAreTranslated() {
        for (String key : List.of("secondary.too-many", "secondary.name.missing", "secondary.name.duplicate",
                "secondary.out.missing", "secondary.on-error.unknown", "secondary.step.failed")) {
            assertFalse(ResourcesEngine.getString(key, "1", "2").startsWith("%"), key);
        }
    }

    @Test
    public void theMessagesAreTranslated() {
        assertFalse(ResourcesEngine.getString("pipeline.no-input").startsWith("%"));
        assertFalse(ResourcesEngine.getString("pipeline.does-nothing").startsWith("%"));
    }
}

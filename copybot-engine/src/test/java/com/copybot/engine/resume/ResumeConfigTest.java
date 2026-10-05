package com.copybot.engine.resume;

import com.copybot.engine.pipeline.PipelineConfig;
import com.copybot.utils.GsonUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ResumeConfigTest {

    private static PipelineConfig parse(String json) {
        return GsonUtil.getGson().fromJson(json, PipelineConfig.class);
    }

    @Test
    public void absentResumeBlockMeansNone() {
        assertEquals(ResumeMode.NONE, parse("{\"inSteps\":[]}").resumeMode());
    }

    @Test
    public void resumeBlockWithoutModeMeansStateThenDestination() {
        assertEquals(ResumeMode.STATE_THEN_DESTINATION, parse("{\"resume\":{}}").resumeMode());
    }

    @Test
    public void theDestinationOptionsDefaultToTheDichotomyOnTheDirectory() {
        ResumeConfig config = parse("{\"resume\":{\"mode\":\"destination\"}}").resume();
        assertEquals(DestinationCheck.DICHOTOMY, config.effectiveDestinationCheck());
        assertEquals(DestinationMatch.DIRECTORY, config.effectiveDestinationMatch());
    }

    @Test
    public void theDestinationOptionsAreReadFromTheirJsonNames() {
        ResumeConfig config = parse("{\"resume\":{\"destinationCheck\":\"everyFile\",\"destinationMatch\":\"file\"}}").resume();
        assertEquals(DestinationCheck.EVERY_FILE, config.effectiveDestinationCheck());
        assertEquals(DestinationMatch.FILE, config.effectiveDestinationMatch());
        assertEquals(DestinationCheck.DICHOTOMY,
                parse("{\"resume\":{\"destinationCheck\":\"dichotomy\"}}").resume().effectiveDestinationCheck());
        assertEquals(DestinationMatch.DIRECTORY,
                parse("{\"resume\":{\"destinationMatch\":\"directory\"}}").resume().effectiveDestinationMatch());
    }

    @Test
    public void modesAreReadFromTheirJsonNames() {
        assertEquals(ResumeMode.NONE, parse("{\"resume\":{\"mode\":\"none\"}}").resumeMode());
        assertEquals(ResumeMode.STATE, parse("{\"resume\":{\"mode\":\"state\"}}").resumeMode());
        assertEquals(ResumeMode.DESTINATION, parse("{\"resume\":{\"mode\":\"destination\"}}").resumeMode());
        assertEquals(ResumeMode.STATE_THEN_DESTINATION, parse("{\"resume\":{\"mode\":\"stateThenDestination\"}}").resumeMode());
    }
}

package com.copybot.engine.pipeline;

import com.copybot.utils.GsonUtil;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PostProcessConfigTest {

    private static PipelineConfig parse(String json) {
        return GsonUtil.getGson().fromJson(json, PipelineConfig.class);
    }

    @Test
    public void theBlockIsReadWithItsArguments() {
        PipelineConfig config = parse("{\"postProcess\":{\"command\":\"C:\\\\progs\\\\synoRight.exe\","
                + "\"args\":[\"gui\",\"{createdDirs}\"]}}");

        assertEquals(new PostProcessConfig("C:\\progs\\synoRight.exe", List.of("gui", "{createdDirs}")),
                config.postProcess());
        assertTrue(config.postProcess().isSet());
    }

    @Test
    public void withoutCommandThereIsNoPostProcessing() {
        assertNull(parse("{}").postProcess());
        assertFalse(parse("{\"postProcess\":{\"command\":\" \"}}").postProcess().isSet());
        assertEquals(List.of(), parse("{\"postProcess\":{\"command\":\"a\"}}").postProcess().args(),
                "no args: an empty list");
        assertEquals(List.of("a"), parse("{\"postProcess\":{\"command\":\"a\",\"args\":[null,\"a\"]}}")
                .postProcess().args(), "a null argument is left out");
    }
}

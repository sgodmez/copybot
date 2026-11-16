package com.copybot.engine.pipeline;

import com.copybot.utils.GsonUtil;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The configuration of the embedded run-end action post.process. */
public class PostProcessConfigTest {

    private static PostProcessConfig parse(String json) {
        return GsonUtil.getGson().fromJson(json, PostProcessConfig.class);
    }

    @Test
    public void theConfigurationIsReadWithItsArguments() {
        PostProcessConfig config = parse("{\"command\":\"C:\\\\progs\\\\synoRight.exe\","
                + "\"args\":[\"gui\",\"{createdDirs}\"]}");

        assertEquals(new PostProcessConfig("C:\\progs\\synoRight.exe", List.of("gui", "{createdDirs}")), config);
        assertTrue(config.isSet());
    }

    @Test
    public void withoutCommandNothingIsLaunched() {
        assertFalse(parse("{}").isSet());
        assertFalse(parse("{\"command\":\" \"}").isSet());
        assertFalse(parse("{\"command\":\"\\\"\\\"\"}").isSet(), "only quotes");
        assertEquals(List.of(), parse("{\"command\":\"a\"}").args(), "no args: an empty list");
        assertEquals(List.of("a"), parse("{\"command\":\"a\",\"args\":[null,\"a\"]}").args(),
                "a null argument is left out");
    }
}

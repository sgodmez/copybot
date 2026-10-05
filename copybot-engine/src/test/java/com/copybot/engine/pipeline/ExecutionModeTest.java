package com.copybot.engine.pipeline;

import com.copybot.utils.GsonUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ExecutionModeTest {

    private static PipelineConfig parse(String json) {
        return GsonUtil.getGson().fromJson(json, PipelineConfig.class);
    }

    @Test
    public void anAbsentExecutionMeansPlan() {
        assertEquals(ExecutionMode.PLAN, parse("{\"inSteps\":[]}").executionMode());
    }

    @Test
    public void modesAreReadFromTheirJsonNames() {
        assertEquals(ExecutionMode.PLAN, parse("{\"execution\":\"plan\"}").executionMode());
        assertEquals(ExecutionMode.AUTO, parse("{\"execution\":\"auto\"}").executionMode());
        assertEquals(ExecutionMode.STREAMING, parse("{\"execution\":\"streaming\"}").executionMode());
        for (ExecutionMode mode : ExecutionMode.values()) {
            assertEquals(mode, parse("{\"execution\":\"" + mode.jsonName() + "\"}").executionMode());
        }
    }

    @Test
    public void theOldFieldsAreIgnored() {
        assertEquals(ExecutionMode.PLAN,
                parse("{\"startProcessingWhileListing\":true,\"ui\":{\"autoExecute\":true}}").executionMode());
    }
}

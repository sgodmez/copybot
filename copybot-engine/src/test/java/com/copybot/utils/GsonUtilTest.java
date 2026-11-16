package com.copybot.utils;

import com.copybot.engine.pipeline.PipelineStepConfig;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The integers of a pipeline or of an action configuration are read exactly: never wrapped around. */
public class GsonUtilTest {

    public record Numbers(int small, Integer boxed, long big, Long boxedBig, short tiny, Byte octet) {
    }

    private static Numbers numbers(String json) {
        return GsonUtil.getGson().fromJson(JsonParser.parseString(json), Numbers.class);
    }

    @Test
    public void anIntOutOfRangeIsRefusedNotWrapped() {
        JsonSyntaxException e = assertThrows(JsonSyntaxException.class, () -> GsonUtil.getGson().fromJson(
                JsonParser.parseString("{\"action\":\"x\",\"maxConcurrency\":3000000000}"), PipelineStepConfig.class));

        assertTrue(e.getMessage().contains("3000000000"), e.getMessage());
        assertTrue(e.getMessage().contains("maxConcurrency"), e.getMessage());
    }

    @Test
    public void eachIntegerTypeKeepsItsRange() {
        for (String json : List.of("{\"small\":2147483648}", "{\"boxed\":-2147483649}",
                "{\"big\":9223372036854775808}", "{\"boxedBig\":1e19}", "{\"tiny\":32768}", "{\"octet\":128}",
                "{\"small\":8.5}", "{\"small\":\"eight\"}")) {
            assertThrows(JsonSyntaxException.class, () -> numbers(json), json);
        }
    }

    @Test
    public void integersInRangeAreReadAsBefore() {
        assertEquals(new Numbers(2147483647, -2147483648, 3000000000L, -9223372036854775808L, (short) 8, (byte) -128),
                numbers("{\"small\":2147483647,\"boxed\":-2147483648,\"big\":3000000000,"
                        + "\"boxedBig\":-9223372036854775808,\"tiny\":8.0,\"octet\":\"-128\"}"));
        assertEquals(new Numbers(0, null, 0, null, (short) 0, null),
                numbers("{\"boxed\":null,\"boxedBig\":null}"), "absent or null as before");
    }
}

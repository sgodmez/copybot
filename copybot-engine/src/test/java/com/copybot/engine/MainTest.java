package com.copybot.engine;

import com.copybot.Copybot;
import org.junit.jupiter.api.Test;

public class MainTest {

    @Test
    public void testMain() {
        Copybot.doMain(
                "-p=./src/test/resources/com/copybot/engine/test-pipeline.json",
                "-c=./src/test/resources/com/copybot/engine/config.json",
                "--debug");
    }
}

package com.copybot.ui.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Handler;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

public class AppLoggingTest {

    @TempDir
    Path tempDir;

    @Test
    public void theLogFileIsCreatedInTheGivenFolder() {
        Logger root = Logger.getLogger("");
        Handler[] before = root.getHandlers();
        Path logs = tempDir.resolve("logs");
        try {
            AppLogging.install(logs);
            assertTrue(Files.exists(logs.resolve("copybot0.log")));
        } finally {
            for (Handler h : root.getHandlers()) {
                if (!java.util.Arrays.asList(before).contains(h)) {
                    root.removeHandler(h);
                    h.close();
                }
            }
        }
    }
}

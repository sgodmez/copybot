package com.copybot.engine;

import com.copybot.Copybot;
import com.copybot.engine.pipeline.PipelineStep;
import com.copybot.engine.pipeline.PipelineStepConfig;
import com.copybot.engine.plugin.PluginEngine;
import com.copybot.plugin.api.action.IOutAction;
import com.copybot.plugin.embedded.actions.FileWriteAction;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end run of the CLI on the fixture pipeline: file.read (in step) -> file.write (out step).
 * Paths are relative to the module directory, which is the working directory of the test JVM.
 *
 * <p>Ordering matters: {@code testMain} is what loads the plugins (through
 * {@code CopybotEngine.init}), and {@code PluginEngine.load} may only run once per JVM.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class MainTest {

    private static final Path IN_DIR = Path.of("target", "maintest-in");
    private static final Path OUT_DIR = Path.of("target", "maintest-out");

    @BeforeEach
    public void prepareDirectories() throws IOException {
        deleteRecursively(IN_DIR);
        deleteRecursively(OUT_DIR);
        Files.createDirectories(IN_DIR);
        Files.createDirectories(OUT_DIR); // file.write does not create its target directory
        Files.writeString(IN_DIR.resolve("alpha.txt"), "alpha content");
        Files.writeString(IN_DIR.resolve("beta.txt"), "beta content");
    }

    @Test
    @Order(1)
    public void testMain() throws IOException {
        int exitCode = Copybot.doMain(
                "-p=./src/test/resources/com/copybot/engine/test-pipeline.json",
                "-c=./src/test/resources/com/copybot/engine/config.json",
                "--debug");

        assertEquals(0, exitCode, "the CLI must complete without error");
        // the pipeline is only really wired if the out step produced the files
        assertTrue(Files.exists(OUT_DIR.resolve("alpha.txt")), "alpha.txt must have been written by the out step");
        assertTrue(Files.exists(OUT_DIR.resolve("beta.txt")), "beta.txt must have been written by the out step");
        assertEquals("alpha content", Files.readString(OUT_DIR.resolve("alpha.txt")));
        assertEquals("beta content", Files.readString(OUT_DIR.resolve("beta.txt")));
    }

    /** An out step must resolve through the real plugin registry (it used to throw ActionNotFoundException). */
    @Test
    @Order(2)
    public void outStepResolvesThroughThePluginEngine() {
        PipelineStepConfig outStep = new PipelineStepConfig(null, "file.write", null, null, null, null, null,
                JsonParser.parseString("{\"outPattern\":\"./target/maintest-out/{name}\",\"overwrite\":false}"));

        PipelineStep<IOutAction> step = PluginEngine.resolve(outStep, IOutAction.class);

        assertNotNull(step);
        assertInstanceOf(FileWriteAction.class, step.getAction());
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}

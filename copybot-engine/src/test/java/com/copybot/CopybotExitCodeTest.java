package com.copybot;

import com.copybot.engine.CopybotEngine;
import com.copybot.engine.Execution;
import com.copybot.engine.pipeline.PipelineStatus;
import com.copybot.resources.ResourcesEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** One test per CLI exit code (spec engine-instance §6). */
@Timeout(60)
public class CopybotExitCodeTest {

    private static final String CONFIG = "-c=./src/test/resources/com/copybot/engine/config.json";
    private static final String STATE = ",\"resume\":{\"mode\":\"state\"}";
    /** A resource nobody can ever get: only a cancellation ends a run whose out step needs it. */
    private static final String BLOCKED_OUT = ", \"resources\": [\"blocked\"]";

    @TempDir
    Path tempDir;

    Path card;
    Path nas;

    @BeforeEach
    public void createCard() throws IOException {
        card = Files.createDirectories(tempDir.resolve("card"));
        nas = tempDir.resolve("nas");
        photo("IMG_01.JPG", "2026-09-01T10:00:00Z");
        photo("IMG_02.JPG", "2026-09-02T10:00:00Z");
    }

    private void photo(String name, String date) throws IOException {
        Path file = Files.writeString(card.resolve(name), name);
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse(date)));
    }

    private static String json(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }

    /** file.read -> file.write; extraOut is appended to the out step, extra to the root object. */
    private Path pipeline(String extraOut, String extra) throws IOException {
        return Files.writeString(tempDir.resolve("sd.json"), """
                {
                  "inSteps": [ { "action": "file.read", "actionConfig": { "path": "%s" } } ],
                  "outStep": { "action": "file.write", "actionConfig": { "outPattern": "%s/{name}", "overwrite": false }%s }%s
                }
                """.formatted(json(card), json(nas), extraOut, extra));
    }

    private Path blockedConfig() throws IOException {
        return Files.writeString(tempDir.resolve("blocked-config.json"), "{ \"resources\": { \"blocked\": 0 } }");
    }

    /** Runs the CLI capturing stdout and stderr; returns {exitCode, stdout, stderr}. */
    private static String[] cli(Consumer<Execution> onExecutionStarted, String... args) {
        return cli(new Copybot(onExecutionStarted), args);
    }

    private static String[] cli(Copybot copybot, String... args) {
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true));
            System.setErr(new PrintStream(err, true));
            int code = new CommandLine(copybot).execute(args);
            return new String[]{String.valueOf(code), out.toString(), err.toString()};
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }

    private static String[] cli(String... args) {
        return cli(execution -> {
        }, args);
    }

    @Test
    public void aSuccessfulRunExitsZero() throws IOException {
        String[] result = cli("-p=" + pipeline("", ""), CONFIG);

        assertEquals("0", result[0], result[2]);
        assertTrue(Files.exists(nas.resolve("IMG_02.JPG")));
    }

    @Test
    public void aDryRunPreparedWithoutErrorExitsZero() throws IOException {
        String[] result = cli("-p=" + pipeline("", STATE), CONFIG, "--dry-run");

        assertEquals("0", result[0], result[2]);
        assertFalse(Files.exists(nas));
    }

    @Test
    public void anItemInErrorExitsOne() throws IOException {
        Files.createDirectories(nas);
        Files.writeString(nas.resolve("IMG_01.JPG"), "already there"); // overwrite=false: this item fails

        String[] result = cli("-p=" + pipeline("", ""), CONFIG);

        assertEquals("1", result[0]);
        assertTrue(result[2].contains(ResourcesEngine.getString("cli.item.error", "IMG_01.JPG", "").strip()), result[2]);
        assertTrue(Files.exists(nas.resolve("IMG_02.JPG")), "the other items are still copied");
    }

    @Test
    public void aCursorWriteFailureExitsOne() throws IOException {
        Files.createDirectories(tempDir.resolve("sd.state.json.tmp")); // the temporary state file cannot be written

        String[] result = cli("-p=" + pipeline("", STATE), CONFIG);

        assertEquals("1", result[0]);
        assertTrue(result[2].contains("sd.state.json"), result[2]);
    }

    @Test
    public void aListingFailureExitsOneOnTheDefaultRun() throws IOException {
        for (String extra : new String[]{"", STATE}) { // single phase, then prepare + execute
            Path pipeline = Files.writeString(tempDir.resolve("missing-card.json"), """
                    {
                      "inSteps": [ { "action": "file.read", "actionConfig": { "path": "%s" } } ],
                      "outStep": { "action": "file.write", "actionConfig": { "outPattern": "%s/{name}", "overwrite": false } }%s
                    }
                    """.formatted(json(tempDir.resolve("no-card")), json(nas), extra));

            String[] result = cli("-p=" + pipeline, CONFIG);

            assertEquals("1", result[0], "resume block '" + extra + "': " + result[2]);
            assertTrue(result[2].contains("no-card"), result[2]);
        }
    }

    @Test
    public void aPluginLoadingErrorExitsTwo() throws IOException {
        for (Error error : new Error[]{new ServiceConfigurationError("bad provider"), new NoClassDefFoundError("bad class")}) {
            String[] result = cli(execution -> {
                throw error;
            }, "-p=" + pipeline(BLOCKED_OUT, ""), "-c=" + blockedConfig());

            assertEquals("2", result[0], error + ": " + result[2]);
            assertTrue(result[2].contains(error.getMessage()), result[2]);
        }
    }

    @Test
    public void aMissingConfigExitsTwo() throws IOException {
        String[] result = cli("-p=" + pipeline("", ""), "-c=" + tempDir.resolve("nope.json"));

        assertEquals("2", result[0]);
        assertTrue(result[2].contains("nope.json"), result[2]);
    }

    @Test
    public void aMissingPipelineExitsTwoWithOrWithoutDebug() {
        String missing = "-p=" + tempDir.resolve("missing.json");

        assertEquals("2", cli(missing, CONFIG)[0]);
        String[] debug = cli(missing, CONFIG, "--debug");
        assertEquals("2", debug[0], "--debug prints the stacktrace but keeps the exit code");
        assertTrue(debug[2].contains("missing.json"), debug[2]);
    }

    @Test
    public void aPreparationInErrorExitsTwo() throws IOException {
        Path pipeline = pipeline("", STATE);
        Files.writeString(tempDir.resolve("sd.state.json"), "not json");

        String[] result = cli("-p=" + pipeline, CONFIG, "--dry-run");

        assertEquals("2", result[0]);
        assertTrue(result[2].contains("sd.state.json"), result[2]);
    }

    @Test
    public void aPreparationInErrorExitsTwoOnTheDefaultRunToo() throws IOException {
        Path pipeline = pipeline("", STATE);
        Files.writeString(tempDir.resolve("sd.state.json"), "not json");

        String[] result = cli("-p=" + pipeline, CONFIG);

        assertEquals("2", result[0], result[2]);
        assertTrue(result[2].contains("sd.state.json"), result[2]);
        assertFalse(Files.exists(nas), "nothing is copied");
    }

    @Test
    public void anUnresolvableStepExitsTwoWithoutResumeBlock() throws IOException {
        Path pipeline = Files.writeString(tempDir.resolve("sd.json"), """
                {
                  "inSteps": [ { "action": "file.read", "actionConfig": { "path": "%s" } } ],
                  "outStep": { "action": "nope.nope", "actionConfig": {} }
                }
                """.formatted(json(card)));

        String[] result = cli("-p=" + pipeline, CONFIG);

        assertEquals("2", result[0], result[2]);
        assertFalse(Files.exists(nas), "nothing is copied");
    }

    @Test
    public void aFileReadWithoutPathExitsTwoWithItsMessage() throws IOException {
        for (String resume : new String[]{"", STATE}) {
            Path pipeline = Files.writeString(tempDir.resolve("sd.json"), """
                    {
                      "inSteps": [ { "action": "file.read", "actionConfig": {} } ],
                      "outStep": { "action": "file.write", "actionConfig": { "outPattern": "%s/{name}" } }%s
                    }
                    """.formatted(json(nas), resume));

            String[] result = cli("-p=" + pipeline, CONFIG);

            assertEquals("2", result[0], resume + ": " + result[2]);
            assertTrue(result[2].contains(ResourcesEngine.getString("read.config.no-path")), result[2]);
            assertFalse(Files.exists(nas), "nothing is copied");
        }
    }

    @Test
    public void anUnknownFromFileExitsTwo() throws IOException {
        String[] result = cli("-p=" + pipeline("", STATE), CONFIG, "--from-file=NOPE.JPG");

        assertEquals("2", result[0]);
        assertFalse(Files.exists(nas));
    }

    @Test
    public void invalidOptionsExitTwo() throws IOException {
        assertEquals("2", cli("-p=" + pipeline("", STATE), CONFIG, "--all", "--from-date=2026-09-02")[0]);
    }

    @Test
    public void aCancelledExecutionExitsOneHundredThirty() throws IOException {
        String[] result = cli(Execution::cancel, "-p=" + pipeline(BLOCKED_OUT, ""), "-c=" + blockedConfig());

        assertEquals("130", result[0], result[2]);
        assertTrue(result[1].contains("Cancelled !"), result[1]);
        assertFalse(Files.exists(nas.resolve("IMG_01.JPG")), "nothing is written");
    }

    @Test
    public void theCtrlCHookCancelsTheExecutionAndWaitsForIt() throws Exception {
        try (CopybotEngine engine = CopybotEngine.create(Optional.of(blockedConfig()))) {
            Execution execution = engine.run(pipeline(BLOCKED_OUT, ""), null);

            Copybot.cancelAndWait(execution);

            assertTrue(execution.isDone(), "the hook waits for the cancelled execution");
            assertEquals(PipelineStatus.CANCELLED, execution.getState().getStatus());
        }
    }

    @Test
    public void theCtrlCHookIsInstalledDuringTheRunAndRemovedAfterANormalEnd() throws IOException {
        Thread[] hook = new Thread[1];
        boolean[] installedDuringTheRun = new boolean[1];
        Copybot[] copybot = new Copybot[1];
        copybot[0] = new Copybot(execution -> {
            hook[0] = copybot[0].cancelHook();
            installedDuringTheRun[0] = hook[0] != null && Runtime.getRuntime().removeShutdownHook(hook[0]);
            if (installedDuringTheRun[0]) {
                Runtime.getRuntime().addShutdownHook(hook[0]); // put back: the CLI removes it at the end
            }
        });

        String[] result = cli(copybot[0], "-p=" + pipeline("", ""), CONFIG);

        assertEquals("0", result[0], result[2]);
        assertTrue(installedDuringTheRun[0], "the hook is registered while the execution runs");
        assertFalse(Runtime.getRuntime().removeShutdownHook(hook[0]), "the hook is removed after a normal end");
        assertNull(copybot[0].cancelHook());
    }
}

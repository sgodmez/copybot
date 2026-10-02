package com.copybot.engine;

import com.copybot.Copybot;
import com.copybot.resources.ResourcesEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CLI runs of a real file.read -> file.write pipeline with resume. Dates come from the files'
 * modification time, set explicitly so that the ordering is deterministic.
 */
public class ResumeEndToEndTest {

    private static final String CONFIG = "-c=./src/test/resources/com/copybot/engine/config.json";

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

    private Path pipeline(String mode, String outPattern) throws IOException {
        String resume = mode == null ? "" : ",\"resume\":{\"mode\":\"" + mode + "\"}";
        return Files.writeString(tempDir.resolve("sd.json"), """
                {
                  "inSteps": [ { "action": "file.read", "actionConfig": { "path": "%s" } } ],
                  "outStep": { "action": "file.write", "actionConfig": { "outPattern": "%s", "onConflict": { "ifDifferent": "error" } } }%s
                }
                """.formatted(json(card), json(nas) + "/" + outPattern, resume));
    }

    private static int cli(Path pipeline, String... extra) {
        String[] args = new String[extra.length + 3];
        args[0] = "-p=" + pipeline.toAbsolutePath();
        args[1] = CONFIG;
        args[2] = "--debug";
        System.arraycopy(extra, 0, args, 3, extra.length);
        return Copybot.doMain(args);
    }

    @Test
    public void stateModeImportsOnlyTheDeltaAndNeverReimportsDeletedFiles() throws IOException {
        Path pipeline = pipeline("state", "{name}");

        assertEquals(0, cli(pipeline));
        assertTrue(Files.exists(nas.resolve("IMG_01.JPG")));
        assertTrue(Files.exists(nas.resolve("IMG_02.JPG")));
        assertTrue(Files.exists(tempDir.resolve("sd.state.json")), "the cursor is persisted next to the pipeline");

        Files.delete(nas.resolve("IMG_01.JPG")); // sorted out on the NAS afterwards
        photo("IMG_03.JPG", "2026-09-03T10:00:00Z");
        assertEquals(0, cli(pipeline));

        assertFalse(Files.exists(nas.resolve("IMG_01.JPG")), "a file deleted from the NAS must not come back");
        assertTrue(Files.exists(nas.resolve("IMG_03.JPG")), "the new photo is imported");
    }

    @Test
    public void dryRunCopiesAndWritesNothing() throws IOException {
        Path pipeline = pipeline("state", "{name}");

        assertEquals(0, cli(pipeline, "--dry-run"));

        assertFalse(Files.exists(nas));
        assertFalse(Files.exists(tempDir.resolve("sd.state.json")));
    }

    /** Runs the CLI capturing stdout and stderr; returns {exitCode, stdout, stderr} as strings. */
    private static String[] capture(Path pipeline, String... extra) {
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true));
            System.setErr(new PrintStream(err, true));
            int code = cli(pipeline, extra);
            return new String[]{String.valueOf(code), out.toString(), err.toString()};
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }

    @Test
    public void fromFileForcesAResumePoint() throws IOException {
        Path pipeline = pipeline("state", "{name}");
        assertEquals(0, cli(pipeline));
        Files.delete(nas.resolve("IMG_02.JPG"));
        Files.writeString(nas.resolve("IMG_01.JPG"), "different content");

        String[] result = capture(pipeline, "--from-file=IMG_02.JPG");

        assertEquals("0", result[0]);
        assertTrue(Files.exists(nas.resolve("IMG_02.JPG")), "the manual resume point re-imports from that file");
        assertEquals("different content", Files.readString(nas.resolve("IMG_01.JPG")),
                "IMG_01 is skipped (neither copied nor failed)");
        assertFalse(result[2].contains("ERROR"), "no item is reported in error: " + result[2]);
    }

    @Test
    public void dryRunWithFromFilePrintsSkippedAndCopiedItemsAndWritesNothing() throws IOException {
        Path pipeline = pipeline("state", "{name}");
        assertEquals(0, cli(pipeline));
        Files.delete(nas.resolve("IMG_02.JPG"));

        String[] result = capture(pipeline, "--dry-run", "--from-file=IMG_02.JPG");

        assertEquals("0", result[0]);
        assertTrue(result[1].contains(ResourcesEngine.getString("cli.plan.skip", "IMG_01.JPG", "").strip()), result[1]);
        assertTrue(result[1].contains(ResourcesEngine.getString("cli.plan.copy", "IMG_02.JPG")), result[1]);
        assertTrue(result[1].contains("[" + ResourcesEngine.getString("cli.plan.source.MANUAL") + "]"), result[1]);
        assertFalse(Files.exists(nas.resolve("IMG_02.JPG")), "a dry run writes nothing");
    }

    @Test
    public void unknownFromFileFailsWithNonZeroExitAndCopiesNothingNorWritesState() throws IOException {
        Path pipeline = pipeline("state", "{name}");

        assertNotEquals(0, cli(pipeline, "--from-file=NOPE.JPG"));

        assertFalse(Files.exists(nas));
        assertFalse(Files.exists(tempDir.resolve("sd.state.json")));
    }

    @Test
    public void allReimportsEverything() throws IOException {
        Path pipeline = pipeline("state", "{name}");
        assertEquals(0, cli(pipeline));
        Files.delete(nas.resolve("IMG_01.JPG"));

        // IMG_02.JPG is still there and identical: ifDifferent "error" still skips it (spec safe-write §1), IMG_01 is copied again
        assertEquals(0, cli(pipeline, "--all"));

        assertTrue(Files.exists(nas.resolve("IMG_01.JPG")));
    }

    @Test
    public void destinationModeResumesAfterTheLastExistingDayDirectory() throws IOException {
        Path pipeline = pipeline("destination", "{lastModified.Y}-{lastModified.m}-{lastModified.D}/{name}");
        Files.createDirectories(nas.resolve("2026-09-01")); // day 1 already imported, then emptied

        assertEquals(0, cli(pipeline));

        assertFalse(Files.exists(nas.resolve("2026-09-01").resolve("IMG_01.JPG")), "day 1 counts as imported");
        assertTrue(Files.exists(nas.resolve("2026-09-02").resolve("IMG_02.JPG")));
    }

    @Test
    public void defaultRunReportsACursorWriteFailureOnStderr() throws IOException {
        Path pipeline = pipeline("state", "{name}");
        Files.createDirectories(tempDir.resolve("sd.state.json.tmp")); // the temporary state file cannot be written

        String[] result = capture(pipeline);

        assertTrue(Files.exists(nas.resolve("IMG_02.JPG")), "the files are copied");
        assertTrue(result[2].contains("sd.state.json"), "the cursor write failure is reported: " + result[2]);
        assertFalse(result[1].contains("PipelineState@"), "no raw state dump: " + result[1]);
    }

    @Test
    public void defaultRunReportsItemErrorsOnStderr() throws IOException {
        Path pipeline = pipeline("state", "{name}");
        Files.createDirectories(nas);
        Files.writeString(nas.resolve("IMG_01.JPG"), "already there"); // ifDifferent "error": this item fails

        String[] result = capture(pipeline);

        assertTrue(result[2].contains(ResourcesEngine.getString("cli.item.error", "IMG_01.JPG", "").strip()), result[2]);
    }

    @Test
    public void fromDateSkipsTheDaysBeforeAndCopiesFromThatDay() throws IOException {
        Path pipeline = pipeline("state", "{name}");

        String[] result = capture(pipeline, "--from-date=2026-09-02");

        assertEquals("0", result[0], result[2]);
        assertFalse(Files.exists(nas.resolve("IMG_01.JPG")), "day 1 is before the chosen date");
        assertTrue(Files.exists(nas.resolve("IMG_02.JPG")));
    }

    @Test
    public void allAndFromDateTogetherAreRejectedAndCopyNothing() throws IOException {
        Path pipeline = pipeline("state", "{name}");

        String[] result = capture(pipeline, "--all", "--from-date=2026-09-02");

        assertNotEquals("0", result[0]);
        assertFalse(Files.exists(nas));
        assertFalse(Files.exists(tempDir.resolve("sd.state.json")));
    }

    @Test
    public void unknownResumeModeIsRejected() throws IOException {
        Path pipeline = pipeline("stat", "{name}");

        String[] result = capture(pipeline);

        assertNotEquals("0", result[0]);
        assertTrue(result[2].contains("\"stat\""), result[2]);
        assertFalse(Files.exists(nas), "nothing is copied");
    }

    @Test
    public void emptyPipelineFileIsReportedAsNotJson() throws IOException {
        Path pipeline = Files.writeString(tempDir.resolve("empty.json"), "");

        String[] result = capture(pipeline);

        assertNotEquals("0", result[0]);
        assertTrue(result[2].contains("empty.json"), result[2]);
        assertFalse(result[2].contains("NullPointerException"), result[2]);
    }

    @Test
    public void pipelineWithoutResumeBlockKeepsCopyingEverythingWithoutStateFile() throws IOException {
        Path pipeline = pipeline(null, "{name}");

        assertEquals(0, cli(pipeline));

        assertTrue(Files.exists(nas.resolve("IMG_01.JPG")));
        assertFalse(Files.exists(tempDir.resolve("sd.state.json")));
    }

    /** file.read -> file.write to nas/{name}; outConfig is appended to the write actionConfig, mode null: no resume. */
    private Path pipelineWithOut(String mode, String outConfig) throws IOException {
        String resume = mode == null ? "" : ",\"resume\":{\"mode\":\"" + mode + "\"}";
        return Files.writeString(tempDir.resolve("sd.json"), """
                {
                  "inSteps": [ { "action": "file.read", "actionConfig": { "path": "%s" } } ],
                  "outStep": { "action": "file.write", "actionConfig": { "outPattern": "%s/{name}"%s } }%s
                }
                """.formatted(json(card), json(nas), outConfig, resume));
    }

    @Test
    public void aConfigurationErrorCopiesNothing() throws IOException {
        Path pipeline = pipelineWithOut(null, ", \"onConflict\": { \"ifDifferent\": \"merge\" }");

        String[] result = capture(pipeline);

        assertEquals("2", result[0], result[2]);
        assertFalse(Files.exists(nas), "nothing is copied");
    }

    @Test
    public void aSecondRunSkipsTheFilesAlreadyThereAndSucceeds() throws IOException {
        Path pipeline = pipelineWithOut(null, "");
        assertEquals(0, cli(pipeline));

        String[] again = capture(pipeline);

        assertEquals("0", again[0], again[2]);
        assertFalse(again[2].contains("ERROR"), again[2]);
        assertFalse(Files.exists(nas.resolve("IMG_01 (1).JPG")), "an identical file is not copied twice");
    }

    private static boolean hasWarning(String output) {
        return output.lines().anyMatch(line -> line.startsWith(ResourcesEngine.getString("cli.warning", "")));
    }

    @Test
    public void dryRunPrintsTheConfigurationWarningsAndDeletesNothing() throws IOException {
        Path pipeline = pipelineWithOut("state", ", \"deleteSource\": true");

        String[] result = capture(pipeline, "--dry-run");

        assertEquals("0", result[0], result[2]);
        assertTrue(hasWarning(result[1]), result[1]);
        assertTrue(Files.exists(card.resolve("IMG_01.JPG")), "a dry run deletes nothing");
    }

    @Test
    public void aRunPrintsTheConfigurationWarningsOnStderrThenMovesTheFiles() throws IOException {
        Path pipeline = pipelineWithOut(null, ", \"deleteSource\": true");

        String[] result = capture(pipeline);

        assertEquals("0", result[0], result[2]);
        assertEquals(1, result[2].lines().filter(line -> line.startsWith(ResourcesEngine.getString("cli.warning", ""))).count(), result[2]);
        assertTrue(Files.exists(nas.resolve("IMG_01.JPG")));
        assertFalse(Files.exists(card.resolve("IMG_01.JPG")), "deleteSource: the source is gone once copied and verified");
    }

    @Test
    public void aSafeConfigurationPrintsNoWarning() throws IOException {
        Path pipeline = pipelineWithOut("state", ", \"deleteSource\": true, \"verify\": \"readBack\"");

        String[] dryRun = capture(pipeline, "--dry-run");
        String[] run = capture(pipeline);

        assertFalse(hasWarning(dryRun[1]), dryRun[1]);
        assertFalse(hasWarning(run[2]), run[2]);
        assertEquals("0", run[0], run[2]);
    }
}

package com.copybot.engine;

import com.copybot.engine.pipeline.PipelineConfig;
import com.copybot.engine.pipeline.RunEndStepConfig;
import com.copybot.engine.pipeline.SecondaryOutputConfig;
import com.copybot.utils.GsonUtil;
import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.RunOutcome;
import com.copybot.plugin.api.action.RunReport;
import com.copybot.plugin.embedded.actions.PostProcessAction;
import com.copybot.plugin.embedded.actions.PostProcessActionTest.RecordingStarter;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The reading of the runEnd list, and of the former postProcess block (spec run-end, Configuration). */
public class PipelineReaderTest {

    @TempDir
    Path tempDir;

    private PipelineConfig read(String json) throws IOException {
        return PipelineReader.read(Files.writeString(tempDir.resolve("p.json"), json));
    }

    /** An existing pipeline: the same program, with the same arguments, on success and error (Review Focus 1). */
    @Test
    public void aPostProcessBlockIsReadAsAPostProcessRunEndEntry() throws IOException {
        PipelineConfig config = read("{\"postProcess\":{\"command\":\"D:\\\\progs\\\\synoRight.exe\","
                + "\"args\":[\"rights\",\"{createdDirs}\"]}}");

        assertEquals(1, config.runEnd().size());
        RunEndStepConfig entry = config.runEnd().getFirst();
        assertTrue(entry.isPostProcess());
        assertEquals(List.of("success", "error"), entry.when());
        assertEquals(Set.of(RunOutcome.SUCCESS, RunOutcome.ERROR), entry.outcomes());
        assertNull(entry.timeoutSeconds());
        assertEquals(JsonParser.parseString("{\"command\":\"D:\\\\progs\\\\synoRight.exe\","
                + "\"args\":[\"rights\",\"{createdDirs}\"]}"), entry.actionConfig());

        Path year = tempDir.resolve("2026");
        for (RunOutcome outcome : RunOutcome.values()) {
            RecordingStarter starter = new RecordingStarter();
            assertEquals(List.of(), run(config, starter, List.of(year), outcome));
            assertEquals(outcome == RunOutcome.CANCELLED ? List.of()
                            : List.of(List.of("D:\\progs\\synoRight.exe", "rights", year.toString())),
                    starter.commandLines, outcome.name());
        }
        RecordingStarter starter = new RecordingStarter();
        run(config, starter, List.of(), RunOutcome.SUCCESS);
        assertEquals(List.of(), starter.commandLines, "nothing launched without a created directory");
    }

    /**
     * The run-end entries of the pipeline (post.process ones, launched by this starter) run by the engine for this
     * outcome, after a run that created these directories.
     *
     * @return the warnings of the run
     */
    private static List<String> run(PipelineConfig config, RecordingStarter starter, List<Path> createdDirectories,
                                    RunOutcome outcome) {
        List<RunEnd.Entry> entries = new ArrayList<>();
        for (RunEndStepConfig step : config.runEnd()) {
            PostProcessAction action = new PostProcessAction(starter);
            action.loadConfig(step.actionConfig());
            entries.add(new RunEnd.Entry(step.action(), action, step.outcomes(), action.defaultTimeout()));
        }
        List<String> warnings = new ArrayList<>();
        RunReport report = RunReports.of(null, outcome, Instant.EPOCH, null, Instant.EPOCH, List.of(), List.of(),
                createdDirectories, List.of(), null, "run");
        new RunEnd(entries).run(outcome, report, new RunEnd.Run() {
            @Override
            public List<String> warnings() {
                return List.copyOf(warnings);
            }

            @Override
            public void warn(String warning) {
                warnings.add(warning);
            }

            @Override
            public void running(String name) {
            }
        });
        return warnings;
    }

    @Test
    public void aPostProcessBlockBesideAPostProcessEntryIsIgnored() throws IOException {
        PipelineConfig config = read("{\"postProcess\":{\"command\":\"old\"},"
                + "\"runEnd\":[{\"action\":\"post.process\",\"actionConfig\":{\"command\":\"new\"}}]}");

        assertEquals(1, config.runEnd().size());
        assertEquals(JsonParser.parseString("{\"command\":\"new\"}"), config.runEnd().getFirst().actionConfig());
    }

    @Test
    public void anUnknownOrEmptyConditionRefusesThePipeline() {
        CopybotException unknown = assertThrows(CopybotException.class,
                () -> read("{\"runEnd\":[{\"action\":\"post.process\",\"when\":[\"oops\"]}]}"));
        assertTrue(unknown.getMessage().contains("oops"), unknown.getMessage());

        assertThrows(CopybotException.class, () -> read("{\"runEnd\":[{\"action\":\"post.process\",\"when\":[]}]}"));
    }

    private static final String OUT = "{\"action\":\"file.write\",\"actionConfig\":{\"outPattern\":\"/o/{name}\"}}";

    private static String branch(String name, String extra) {
        return "{" + (name == null ? "" : "\"name\":\"" + name + "\",") + extra + "\"outStep\":" + OUT + "}";
    }

    /** A pipeline of 0.8.0-beta.1 naming exec for an ffmpeg action reads as the ffmpeg plugin's step. */
    @Test
    public void anFfmpegStepOfTheExecPluginIsReadAsAStepOfTheFfmpegPlugin() throws IOException {
        PipelineConfig config = read("{\"actionSteps\":[{\"plugin\":\"com.copybot.plugin.exec\","
                + "\"action\":\"ffmpeg.video\",\"version\":\"1.0.0\"}],\"secondaryOutputs\":[" + branch("Thumbs",
                "\"actionSteps\":[{\"plugin\":\"com.copybot.plugin.exec\",\"action\":\"ffmpeg.thumbnail\"}],") + "]}");

        assertEquals("com.copybot.plugin.ffmpeg", config.actionSteps().getFirst().plugin());
        assertNull(config.actionSteps().getFirst().version());
        assertEquals("com.copybot.plugin.ffmpeg", config.secondaryOutputs().getFirst().actionSteps().getFirst().plugin());
    }

    @Test
    public void aPipelineWithoutSecondaryOutputsHasNone() throws IOException {
        assertEquals(List.of(), read("{}").secondaryOutputs());
        assertEquals(List.of(), read("{\"secondaryOutputs\":null}").secondaryOutputs());
    }

    @Test
    public void secondaryOutputsAreReadInOrderWithTheirDefaults() throws IOException {
        PipelineConfig config = read("{\"secondaryOutputs\":[" + branch("Thumbs", "\"onError\":\"warn\","
                + "\"actionSteps\":[{\"action\":\"file.buffer\"}],") + "," + branch("Copy", "") + "]}");

        assertEquals(2, config.secondaryOutputs().size());
        SecondaryOutputConfig thumbs = config.secondaryOutputs().get(0);
        assertEquals("Thumbs", thumbs.name());
        assertEquals(SecondaryOutputConfig.OnError.WARN, thumbs.onError());
        assertEquals(1, thumbs.actionSteps().size());
        assertEquals("file.write", thumbs.outStep().action());
        SecondaryOutputConfig copy = config.secondaryOutputs().get(1);
        assertEquals(SecondaryOutputConfig.OnError.FAIL, copy.onError());
        assertEquals(List.of(), copy.actionSteps());
    }

    @Test
    public void secondaryOutputsSurviveAJsonRoundTrip() throws IOException {
        PipelineConfig config = read("{\"secondaryOutputs\":[" + branch("Thumbs", "\"onError\":\"warn\",") + "]}");

        String json = GsonUtil.getGson().toJson(config);
        assertTrue(json.replaceAll("\\s", "").contains("\"onError\":\"warn\""), json);
        assertEquals(config, PipelineReader.read(Files.writeString(tempDir.resolve("again.json"), json)));
    }

    @Test
    public void anInvalidSecondaryOutputRefusesThePipelineNamingTheBranch() throws IOException {
        CopybotException noName = assertThrows(CopybotException.class,
                () -> read("{\"secondaryOutputs\":[" + branch("a", "") + "," + branch(null, "") + "]}"));
        assertTrue(noName.getMessage().contains("2"), noName.getMessage());
        assertThrows(CopybotException.class, () -> read("{\"secondaryOutputs\":[" + branch("  ", "") + "]}"));

        CopybotException twice = assertThrows(CopybotException.class,
                () -> read("{\"secondaryOutputs\":[" + branch("Thumbs", "") + "," + branch("THUMBS", "") + "]}"));
        assertTrue(twice.getMessage().contains("THUMBS"), twice.getMessage());

        CopybotException noOut = assertThrows(CopybotException.class,
                () -> read("{\"secondaryOutputs\":[{\"name\":\"Lonely\"}]}"));
        assertTrue(noOut.getMessage().contains("Lonely"), noOut.getMessage());

        List<String> nine = java.util.stream.IntStream.range(0, 9).mapToObj(i -> branch("b" + i, "")).toList();
        assertThrows(CopybotException.class, () -> read("{\"secondaryOutputs\":[" + String.join(",", nine) + "]}"));
        assertEquals(8, read("{\"secondaryOutputs\":[" + String.join(",", nine.subList(0, 8)) + "]}")
                .secondaryOutputs().size());
    }

    @Test
    public void anUnknownOnErrorIsRefusedNotReadAsFail() {
        for (String bad : List.of("\"oops\"", "\"WARN\"", "5", "{}")) {
            CopybotException e = assertThrows(CopybotException.class,
                    () -> read("{\"secondaryOutputs\":[" + branch("Thumbs", "\"onError\":" + bad + ",") + "]}"), bad);
            assertTrue(e.getMessage().contains("Thumbs"), e.getMessage());
        }
    }
}

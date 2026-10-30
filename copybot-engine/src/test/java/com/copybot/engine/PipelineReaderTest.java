package com.copybot.engine;

import com.copybot.engine.pipeline.PipelineConfig;
import com.copybot.engine.pipeline.RunEndStepConfig;
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
}

package com.copybot.ui.model;

import com.copybot.engine.PipelineReader;
import com.copybot.engine.pipeline.PipelineChecks;
import com.copybot.engine.pipeline.PipelineConfig;
import com.copybot.engine.pipeline.RunEndStepConfig;
import com.copybot.plugin.api.config.ConfigField;
import com.copybot.ui.model.PipelineDocument.Section;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.copybot.ui.model.TestCatalog.LAUNCH;
import static com.copybot.ui.model.TestCatalog.MAIL;
import static org.junit.jupiter.api.Assertions.*;

/** What the editor writes in "runEnd" is what the engine reads (spec run-end). */
public class RunEndRoundTripTest {

    @TempDir
    Path tempDir;

    private PipelineConfig readByTheEngine(PipelineDocument document) throws IOException {
        Path file = tempDir.resolve("p.json");
        document.save(file);
        return PipelineReader.read(file);
    }

    private static ConfigField field(String path) {
        return MAIL.configSchema().orElseThrow().field(path).orElseThrow();
    }

    @Test
    public void anActionAddedWithItsConditionsAndTimeLimitIsReadByTheEngine() throws IOException {
        PipelineDocument document = PipelineDocument.empty();
        JsonObject step = document.addStep(Section.RUN_END, MAIL);
        document.setConfigText(step, field("host"), "smtp.example.org");
        document.setConfigText(step, field("password"), SecretField.value("smtp"));
        document.setRunEndWhen(step, Set.of("error", "cancelled"));
        document.setAdvancedText(step, PipelineDocument.advancedFields(Section.RUN_END).getFirst(), "60");
        document.addStep(Section.RUN_END, LAUNCH);

        PipelineConfig config = readByTheEngine(document);

        assertEquals(2, config.runEnd().size());
        RunEndStepConfig mail = config.runEnd().getFirst();
        assertEquals("notify-email", mail.plugin());
        assertEquals("email.send", mail.action());
        assertEquals(List.of("error", "cancelled"), mail.when());
        assertEquals(60, mail.timeoutSeconds());
        assertEquals(JsonParser.parseString("{ \"host\": \"smtp.example.org\", \"password\": \"${secret:smtp}\" }"),
                mail.actionConfig());
        RunEndStepConfig launch = config.runEnd().get(1);
        assertTrue(launch.isPostProcess());
        assertNull(launch.when(), "no conditions written: the default ones");
        assertEquals(RunEndStepConfig.DEFAULT_WHEN, document.runEndWhen(document.steps(Section.RUN_END).get(1)));
        assertEquals(Optional.empty(), PipelineChecks.runEndRefusal(config));
    }

    @Test
    public void aMigratedPostProcessIsReadByTheEngineAsTheSameAction() throws IOException {
        PipelineDocument document = PipelineDocument.parse("""
                { "postProcess": { "command": "D:\\\\progs\\\\synoRight.exe", "args": ["gui", "{createdDirs}"] } }
                """);

        PipelineConfig config = readByTheEngine(document);

        assertFalse(Files.readString(tempDir.resolve("p.json")).contains("postProcess"));
        assertEquals(1, config.runEnd().size());
        RunEndStepConfig entry = config.runEnd().getFirst();
        assertTrue(entry.isPostProcess());
        assertEquals(List.of("success", "error"), entry.when());
        assertNull(entry.timeoutSeconds());
        assertEquals(JsonParser.parseString("{ \"command\": \"D:\\\\progs\\\\synoRight.exe\", \"args\": [\"gui\", \"{createdDirs}\"] }"),
                entry.actionConfig());
        assertEquals(Optional.empty(), PipelineChecks.runEndRefusal(config));
    }
}

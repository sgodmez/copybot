package com.copybot.ui.model;

import com.copybot.engine.PipelineReader;
import com.copybot.engine.pipeline.PipelineChecks;
import com.copybot.engine.pipeline.PipelineConfig;
import com.copybot.engine.pipeline.PipelineStepConfig;
import com.copybot.engine.pipeline.SecondaryOutputConfig;
import com.copybot.engine.pipeline.SecondaryOutputConfig.OnError;
import com.copybot.engine.plugin.CatalogAction;
import com.copybot.plugin.api.config.ConfigField;
import com.copybot.ui.model.PipelineDocument.Section;
import com.copybot.ui.model.PipelineDocument.StepAddress;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static com.copybot.ui.model.TestCatalog.READ;
import static com.copybot.ui.model.TestCatalog.THUMB;
import static com.copybot.ui.model.TestCatalog.WRITE;
import static org.junit.jupiter.api.Assertions.*;

/** What the editor writes in "secondaryOutputs" is what the engine reads (spec secondary-outputs §2, §7). */
public class SecondaryOutputsRoundTripTest {

    @TempDir
    Path tempDir;

    private PipelineConfig readByTheEngine(PipelineDocument document) throws IOException {
        Path file = tempDir.resolve("p.json");
        document.save(file);
        return PipelineReader.read(file);
    }

    private static ConfigField field(CatalogAction action, String path) {
        return action.configSchema().orElseThrow().field(path).orElseThrow();
    }

    @Test
    public void branchesBuiltInTheEditorAreReadByTheEngine() throws IOException {
        PipelineDocument document = PipelineDocument.empty();
        document.setConfigText(document.addStep(Section.IN, READ), field(READ, "path"), "D:/DCIM");
        document.setConfigText(document.addStep(Section.OUT, WRITE), field(WRITE, "outPattern"),
                "nas/{name}");
        int original = document.addBranch("Original");
        document.setConfigText(document.addStep(original, Section.OUT, WRITE),
                field(WRITE, "outPattern"), "orig/{name}");
        int thumbs = document.addBranch("Miniatures");
        JsonObject thumb = document.addStep(thumbs, Section.PROCESS, THUMB);
        document.setConfigText(thumb, field(THUMB, "size"), "256");
        document.setAdvancedText(thumb, PipelineDocument.advancedFields(StepAddress.branch(thumbs, Section.PROCESS, 0))
                .getFirst(), "2");
        document.setConfigText(document.addStep(thumbs, Section.OUT, WRITE),
                field(WRITE, "outPattern"), "thumbs/{name}");
        document.setOnError(thumbs, OnError.WARN);
        document.moveBranch(thumbs, -1);

        PipelineConfig config = readByTheEngine(document);

        assertEquals(Optional.empty(), PipelineChecks.secondaryOutputsRefusal(config));
        assertEquals(List.of("Miniatures", "Original"),
                config.secondaryOutputs().stream().map(SecondaryOutputConfig::name).toList());
        SecondaryOutputConfig first = config.secondaryOutputs().getFirst();
        assertEquals(OnError.WARN, first.onError());
        assertEquals(1, first.actionSteps().size());
        PipelineStepConfig step = first.actionSteps().getFirst();
        assertEquals("com.acme.thumbs", step.plugin());
        assertEquals("thumb.make", step.action());
        assertEquals(2, step.maxConcurrency());
        assertEquals(JsonParser.parseString("{ \"size\": 256 }"), step.actionConfig());
        assertEquals(JsonParser.parseString("{ \"outPattern\": \"thumbs/{name}\" }"), first.outStep().actionConfig());
        SecondaryOutputConfig second = config.secondaryOutputs().get(1);
        assertEquals(OnError.FAIL, second.onError());
        assertEquals(List.of(), second.actionSteps());
        assertEquals("file.write", second.outStep().action());
        assertEquals(JsonParser.parseString("{ \"outPattern\": \"nas/{name}\" }"), config.outStep().actionConfig(),
                "the main output is untouched");
        assertEquals(List.of(), document.branchProblems());
    }

    @Test
    public void aPipelineWithoutBranchIsWrittenWithoutTheMember() throws IOException {
        PipelineDocument document = PipelineDocument.empty();
        document.addStep(Section.OUT, WRITE);
        document.addBranch("tmp");
        document.removeBranch(0);

        PipelineConfig config = readByTheEngine(document);

        assertFalse(document.toJson().contains("secondaryOutputs"));
        assertEquals(List.of(), config.secondaryOutputs());
    }
}

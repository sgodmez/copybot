package com.copybot.ui.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.copybot.ui.model.TestCatalog.CATALOG;
import static org.junit.jupiter.api.Assertions.*;

/** The header of the plan view (spec desktop-ui §2). */
public class PipelineSummaryTest {

    @Test
    public void theHeaderNamesTheStepsTheSourceTheOutputAndTheResumeMode() {
        PipelineDocument document = PipelineDocument.parse(PipelineDocumentTest.PIPELINE);

        PipelineSummary summary = PipelineSummary.of(document, CATALOG);

        assertEquals(List.of("file.read name", "faces", "file.write name"), summary.stepNames(),
                "the catalog name, the action code of a missing plugin");
        assertEquals("D:/DCIM", summary.sourcePath());
        assertEquals("nas/{name}", summary.outPattern());
        assertEquals("state", summary.resumeMode());
    }

    @Test
    public void anEmptyPipelineHasNothingToShow() {
        PipelineSummary summary = PipelineSummary.of(PipelineDocument.empty(), CATALOG);

        assertEquals(List.of(), summary.stepNames());
        assertNull(summary.sourcePath());
        assertNull(summary.outPattern());
        assertNull(summary.resumeMode());
    }

    @Test
    public void anUnknownResumeModeIsShownRawAndAStepWithoutActionFallsBackToAPlaceholder() {
        PipelineDocument document = PipelineDocument.parse(
                "{\"inSteps\":[{\"plugin\":\"com.missing\"}],\"resume\":{\"mode\":\"futureMode\"}}");

        PipelineSummary summary = PipelineSummary.of(document, CATALOG);

        assertEquals("futureMode", summary.resumeMode());
        assertEquals(List.of("?"), summary.stepNames());
    }

    @Test
    public void aStepListsItsConfiguredFieldsFromTheSchemaWithRecordsFlattenedAndEmptyOnesSkipped() {
        PipelineDocument document = PipelineDocument.parse("""
                {"inSteps":[{"action":"file.read","actionConfig":{"path":"D:/in","recursive":false,"include":["*.jpg","*.png"]}}],
                 "outStep":{"action":"file.write","actionConfig":{"outPattern":"out/{name}","onConflict":{"compare":"size"}}}}""");

        PipelineSummary summary = PipelineSummary.of(document, CATALOG);

        assertEquals(List.of(new PipelineSummary.Setting("path", "D:/in"),
                        new PipelineSummary.Setting("recursive", "false"),
                        new PipelineSummary.Setting("include", "*.jpg, *.png")),
                summary.steps().get(0).settings());
        assertEquals(PipelineDocument.Section.IN, summary.steps().get(0).section());
        assertEquals(List.of(new PipelineSummary.Setting("outPattern", "out/{name}"),
                        new PipelineSummary.Setting("compare", "size")),
                summary.steps().get(1).settings(), "bufferSize and ifIdentical are empty, onConflict itself is not a line");
    }

    @Test
    public void aStepWithoutSchemaListsItsRawMembersAndAnUnconfiguredOneNothing() {
        PipelineDocument document = PipelineDocument.parse("""
                {"inSteps":[{"action":"file.read"}],
                 "analyseSteps":[{"plugin":"com.missing","action":"faces","actionConfig":{"model":"big","n":3,"empty":"","opts":{"a":1}}}]}""");

        PipelineSummary summary = PipelineSummary.of(document, CATALOG);

        assertEquals(List.of(), summary.steps().get(0).settings());
        assertEquals(List.of(new PipelineSummary.Setting("model", "big"), new PipelineSummary.Setting("n", "3"),
                        new PipelineSummary.Setting("opts", "{\"a\":1}")),
                summary.steps().get(1).settings());
    }

    @Test
    public void sourceAndDestinationFallBackToTheStepNamesThenToADash() {
        PipelineDocument noPaths = PipelineDocument.parse(
                "{\"inSteps\":[{\"action\":\"file.read\"}],\"outStep\":{\"action\":\"file.write\"}}");
        PipelineSummary named = PipelineSummary.of(noPaths, CATALOG);
        assertEquals("file.read name", named.sourceText());
        assertEquals("file.write name", named.destinationText());

        PipelineSummary empty = PipelineSummary.of(PipelineDocument.empty(), CATALOG);
        assertEquals("—", empty.sourceText());
        assertEquals("—", empty.destinationText());

        PipelineSummary full = PipelineSummary.of(PipelineDocument.parse(PipelineDocumentTest.PIPELINE), CATALOG);
        assertEquals("D:/DCIM", full.sourceText());
        assertEquals("nas/{name}", full.destinationText());
    }
}

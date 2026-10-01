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
}

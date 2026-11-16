package com.copybot.ui.model;

import com.copybot.engine.Projection;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import com.copybot.resources.ResourcesEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The model of the "Out pattern" dialog (spec own-out-pattern §3). */
public class OutPatternModelTest {

    private static final String DEFAULT = "{lastModified.Y}/{name}";

    @TempDir
    Path tempDir;

    private WorkItem item(String name, String own, boolean dated) throws IOException {
        WorkItem item = new WorkItem(tempDir.resolve(name));
        if (dated) {
            item.getMetadatas().setTime(WorkItemMetadata.LAST_MODIFIED, Instant.parse("2026-09-29T08:00:00Z"));
        }
        if (own != null) {
            item.setOutPattern(own);
        }
        return item;
    }

    @Test
    public void theDialogStartsOnTheSharedOwnPatternElseOnTheDefault() throws IOException {
        WorkItem a = item("a.jpg", "x/{name}", true);
        WorkItem b = item("b.jpg", "x/{name}", true);
        WorkItem mixed = item("c.jpg", "y/{name}", true);
        WorkItem none = item("d.jpg", null, true);

        assertEquals("x/{name}", OutPatternModel.initialPattern(List.of(a, b), DEFAULT));
        assertEquals(DEFAULT, OutPatternModel.initialPattern(List.of(a, mixed), DEFAULT), "different own patterns");
        assertEquals(DEFAULT, OutPatternModel.initialPattern(List.of(a, none), DEFAULT), "one without own pattern");
        assertEquals(DEFAULT, OutPatternModel.initialPattern(List.of(none), DEFAULT));
        assertEquals(DEFAULT, OutPatternModel.initialPattern(List.of(), DEFAULT));
    }

    @Test
    public void thePreviewResolvesTheTargetOfAtMostThreeFiles() throws IOException {
        List<WorkItem> items = List.of(item("a.jpg", null, true), item("b.jpg", null, true),
                item("c.jpg", null, true), item("d.jpg", null, true));

        OutPatternModel.Preview preview = OutPatternModel.preview("{lastModified.Y}/x", items, "error");

        assertTrue(preview.syntaxError().isEmpty());
        assertEquals(3, preview.rows().size());
        assertEquals("2026/x", preview.rows().getFirst().path());
        assertEquals("a.jpg", preview.rows().getFirst().name());
        assertTrue(OutPatternModel.canApply(preview));
    }

    @Test
    public void thePreviewListsTheMissingKeys() throws IOException {
        OutPatternModel.Preview preview = OutPatternModel.preview("{lastModified.Y}/x",
                List.of(item("a.jpg", null, false)), "skip");

        assertEquals(1, preview.rows().size());
        assertEquals(List.of("{lastModified.Y}"), preview.rows().getFirst().missing());
        assertTrue(OutPatternModel.canApply(preview), "a missing key is the step's business, not a syntax error");
    }

    @Test
    public void theMissingFilesAreCountedOverTheWholeSelectionWithTheStepsConsequence() throws IOException {
        ResourcesEngine.registerBundle("com.copybot.ui.i18n.uiBundle");
        List<WorkItem> items = List.of(item("a.jpg", null, true), item("b.jpg", null, true),
                item("c.jpg", null, true), item("d.jpg", null, false), item("e.jpg", null, false));

        OutPatternModel.Preview skip = OutPatternModel.preview("{lastModified.Y}/x", items, "skip");
        OutPatternModel.Preview fallback = OutPatternModel.preview("{lastModified.Y}/x", items, null);

        assertEquals(2, skip.missing(), "the files beyond the rows shown count too");
        assertEquals(ResourcesEngine.getString("helper.effect.skip"), skip.effect());
        assertEquals(ResourcesEngine.getString("helper.effect.error"), fallback.effect(), "error by default");
        assertEquals(0, OutPatternModel.preview("x/{name", items, "skip").missing());
    }

    @Test
    public void aSyntaxErrorShowsNoRowAndDisablesOk() throws IOException {
        OutPatternModel.Preview preview = OutPatternModel.preview("{unclosed",
                List.of(item("a.jpg", null, true)), "error");

        assertTrue(preview.syntaxError().isPresent());
        assertEquals(List.of(), preview.rows());
        assertFalse(OutPatternModel.canApply(preview));
        assertEquals(Optional.empty(), OutPatternModel.preview("{name}", List.of(), "error").syntaxError());
    }

    @Test
    public void theDialogWorksOnTheItemsTheOutStepWrites() throws IOException {
        WorkItemExecution renamed = new WorkItemExecution(item("a.jpg", null, true), List.of());
        renamed.setProjection(new Projection.Projected(List.of(item("a-renamed.jpg", null, true), item("a-2.jpg", null, true))));
        WorkItem plain = item("b.jpg", null, true);
        WorkItemExecution unprojected = new WorkItemExecution(plain, List.of());
        unprojected.setProjection(new Projection.Filtered("x"));

        List<WorkItem> written = OutPatternModel.written(List.of(renamed, unprojected));

        assertEquals(List.of("a-renamed.jpg", "b.jpg"), written.stream().map(WorkItem::getNameDisplay).toList(),
                "the first projected item of each file (a process step may rename it), else the file itself");
        assertEquals("a-renamed.jpg", OutPatternModel.preview("x", written, "error").rows().getFirst().name());
    }

    @Test
    public void theKeysAreTheSortedUnionOfTheDisplayKeys() throws IOException {
        List<String> keys = OutPatternModel.keys(List.of(item("a.jpg", null, true), item("b.jpg", null, false)));

        assertTrue(keys.contains("lastModified.Y"));
        assertEquals(keys.stream().sorted().toList(), keys);
        assertEquals(keys.stream().distinct().toList(), keys);
    }
}

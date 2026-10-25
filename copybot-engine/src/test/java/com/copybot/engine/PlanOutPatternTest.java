package com.copybot.engine;

import com.copybot.engine.pipeline.PipelineStatus;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.engine.resume.ItemKey;
import com.copybot.engine.resume.ResumeStateStore;
import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.TargetCheck.Kind;
import com.copybot.plugin.api.pattern.OutPattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Chosen files get their own out pattern on a prepared plan (spec own-out-pattern §2), with the real file.write. */
public class PlanOutPatternTest {

    /** Relative to the module directory, the working directory of the test JVM. */
    private static final Path CONFIG = Path.of("src", "test", "resources", "com", "copybot", "engine", "config.json");
    private static final Instant A_DATE = Instant.parse("2026-07-03T10:12:00Z");
    private static final Instant B_DATE = Instant.parse("2026-07-05T08:00:00Z");

    @TempDir
    Path tempDir;

    private Path in;
    private Path out;
    private Path south;

    private static String json(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }

    /** in/a.jpg ("a", 2026-07-03), in/b.jpg ("b", 2026-07-05) | file.write to out/{name}; extra appended to the root. */
    private Path pipeline(String extra) throws IOException {
        in = Files.createDirectories(tempDir.resolve("in"));
        out = tempDir.resolve("out");
        south = tempDir.resolve("south");
        Files.setLastModifiedTime(Files.writeString(in.resolve("a.jpg"), "a"), FileTime.from(A_DATE.plusMillis(400)));
        Files.setLastModifiedTime(Files.writeString(in.resolve("b.jpg"), "b"), FileTime.from(B_DATE));
        return Files.writeString(tempDir.resolve("p.json"), """
                {
                  "inSteps": [ { "action": "file.read", "actionConfig": { "path": "%s" } } ],
                  "outStep": { "action": "file.write", "actionConfig": { "outPattern": "%s/{name}" } }%s
                }
                """.formatted(json(in), json(out), extra));
    }

    private String southPattern() {
        return json(south) + "/{name}";
    }

    private static Plan prepared(CopybotEngine engine, Path pipeline) {
        Plan plan = engine.prepare(pipeline, null);
        assertEquals(PipelineStatus.PREPARED, plan.getState().getStatus(), String.valueOf(plan.getState().getFailure()));
        return plan;
    }

    private static WorkItemExecution named(Plan plan, String name) {
        return plan.getOrderedItems().stream()
                .filter(w -> w.getWorkItem().getNameDisplay().equals(name)).findFirst().orElseThrow();
    }

    private static TargetProjection.Targets targets(Path directory) {
        return new TargetProjection.Targets(List.of(directory.toAbsolutePath().normalize()));
    }

    private Map<ItemKey, String> stored(Path pipeline) {
        return ResumeStateStore.forPipeline(pipeline).readOutPatterns();
    }

    @Test
    public void setOutPatternChangesTheProjection() throws Exception {
        Path pipeline = pipeline("");
        try (CopybotEngine engine = CopybotEngine.create(Optional.of(CONFIG))) {
            Plan plan = prepared(engine, pipeline);
            WorkItemExecution a = named(plan, "a.jpg");
            WorkItemExecution b = named(plan, "b.jpg");
            assertEquals(Optional.of(json(out) + "/{name}"), plan.defaultOutPattern());
            assertFalse(Plan.hasOwnPattern(a));

            plan.setOutPattern(List.of(a), southPattern());

            assertTrue(Plan.hasOwnPattern(a));
            assertFalse(Plan.hasOwnPattern(b));
            assertEquals(targets(south), plan.projectionOf(a));
            assertEquals(List.of(south.resolve("a.jpg").toAbsolutePath().normalize()), plan.detailOf(a).orElseThrow().targets());
            assertEquals(targets(out), plan.projectionOf(b));
        }
    }

    @Test
    public void setOutPatternIsPersisted() throws Exception {
        Path pipeline = pipeline("");
        try (CopybotEngine engine = CopybotEngine.create(Optional.of(CONFIG))) {
            Plan plan = prepared(engine, pipeline);
            plan.setOutPattern(List.of(named(plan, "a.jpg")), southPattern());

            assertEquals(Map.of(new ItemKey(A_DATE, "a.jpg"), southPattern()), stored(pipeline));

            Plan again = prepared(engine, pipeline);
            assertEquals(targets(south), again.projectionOf(named(again, "a.jpg")), "restored by a new preparation");
            assertEquals(targets(out), again.projectionOf(named(again, "b.jpg")));
        }
    }

    @Test
    public void samePatternAsPipelineRemoves() throws Exception {
        Path pipeline = pipeline("");
        try (CopybotEngine engine = CopybotEngine.create(Optional.of(CONFIG))) {
            Plan plan = prepared(engine, pipeline);
            WorkItemExecution a = named(plan, "a.jpg");
            plan.setOutPattern(List.of(a), southPattern());

            plan.setOutPattern(List.of(a), "  " + json(out) + "/{name} ");

            assertFalse(Plan.hasOwnPattern(a));
            assertEquals(targets(out), plan.projectionOf(a));
            assertEquals(Map.of(), stored(pipeline));
        }
    }

    @Test
    public void blankRemoves() throws Exception {
        Path pipeline = pipeline("");
        try (CopybotEngine engine = CopybotEngine.create(Optional.of(CONFIG))) {
            Plan plan = prepared(engine, pipeline);
            WorkItemExecution a = named(plan, "a.jpg");
            WorkItemExecution b = named(plan, "b.jpg");
            plan.setOutPattern(List.of(a, b), southPattern());

            plan.setOutPattern(List.of(a), "   ");
            plan.setOutPattern(List.of(b), null);

            assertFalse(Plan.hasOwnPattern(a));
            assertFalse(Plan.hasOwnPattern(b));
            assertEquals(targets(out), plan.projectionOf(a));
            assertEquals(Map.of(), stored(pipeline));
        }
    }

    @Test
    public void unanalysedItemsAreLeftAlone() throws Exception {
        Path pipeline = pipeline(",\"resume\":{\"mode\":\"state\"}");
        ResumeStateStore.forPipeline(pipeline).writeCursor(new ItemKey(A_DATE, "a.jpg"));
        try (CopybotEngine engine = CopybotEngine.create(Optional.of(CONFIG))) {
            Plan plan = prepared(engine, pipeline);
            WorkItemExecution a = named(plan, "a.jpg");
            WorkItemExecution b = named(plan, "b.jpg");
            assertTrue(a.isAnalysisDeferred(), "before the cursor");

            plan.setOutPattern(List.of(a, b), southPattern());

            assertFalse(Plan.hasOwnPattern(a));
            assertTrue(Plan.hasOwnPattern(b));
            assertEquals(Map.of(new ItemKey(B_DATE, "b.jpg"), southPattern()), stored(pipeline));
        }
    }

    @Test
    public void anUnanalysedItemRestoredWithItsPatternLosesIt() throws Exception {
        Path pipeline = pipeline(",\"resume\":{\"mode\":\"state\"}");
        ResumeStateStore store = ResumeStateStore.forPipeline(pipeline);
        store.writeCursor(new ItemKey(A_DATE, "a.jpg"));
        store.writeOutPatterns(Map.of(new ItemKey(A_DATE, "a.jpg"), southPattern()));
        try (CopybotEngine engine = CopybotEngine.create(Optional.of(CONFIG))) {
            Plan plan = prepared(engine, pipeline);
            WorkItemExecution a = named(plan, "a.jpg");
            assertTrue(a.isAnalysisDeferred(), "before the cursor");
            assertTrue(Plan.hasOwnPattern(a), "restored at the listing");

            plan.setOutPattern(List.of(a), null);

            assertFalse(Plan.hasOwnPattern(a));
            assertEquals(Map.of(), stored(pipeline));
            assertNull(a.getTargetCheck(), "not analysed: not checked");
        }
    }

    @Test
    public void invalidSyntaxIsRefused() throws Exception {
        Path pipeline = pipeline("");
        String expected = assertThrows(CopybotException.class, () -> OutPattern.parse("x/{name")).getMessage();
        try (CopybotEngine engine = CopybotEngine.create(Optional.of(CONFIG))) {
            Plan plan = prepared(engine, pipeline);
            WorkItemExecution a = named(plan, "a.jpg");

            CopybotException e = assertThrows(CopybotException.class, () -> plan.setOutPattern(List.of(a), "x/{name"));

            assertEquals(expected, e.getMessage());
            assertFalse(Plan.hasOwnPattern(a));
            assertEquals(Map.of(), stored(pipeline));
        }
    }

    @Test
    public void refusedWhileNotPrepared() throws Exception {
        Path pipeline = pipeline("");
        try (CopybotEngine engine = CopybotEngine.create(Optional.of(CONFIG))) {
            Plan plan = prepared(engine, pipeline);
            assertEquals(PipelineStatus.SUCCESS, engine.execute(plan, null).await());

            assertThrows(IllegalStateException.class, () -> plan.setOutPattern(List.of(named(plan, "a.jpg")), southPattern()));
            assertFalse(Plan.hasOwnPattern(named(plan, "a.jpg")));
        }
    }

    @Test
    public void conflictCheckIsRedone() throws Exception {
        Path pipeline = pipeline("");
        Files.createDirectories(south);
        Files.writeString(south.resolve("a.jpg"), "another content");
        try (CopybotEngine engine = CopybotEngine.create(Optional.of(CONFIG))) {
            Plan plan = prepared(engine, pipeline);
            WorkItemExecution a = named(plan, "a.jpg");
            assertEquals(Kind.FREE, a.getTargetCheck().kind());

            plan.setOutPattern(List.of(a), southPattern());

            assertEquals(Kind.DIFFERENT_SIZE, a.getTargetCheck().kind(), "checked at its own target");

            plan.setOutPattern(List.of(a), null);

            assertEquals(Kind.FREE, a.getTargetCheck().kind(), "checked again at the pipeline's target");
        }
    }

    @Test
    public void withoutConflictCheckNothingIsChecked() throws Exception {
        Path pipeline = pipeline(",\"conflictCheck\":\"none\"");
        Files.createDirectories(south);
        Files.writeString(south.resolve("a.jpg"), "another content");
        try (CopybotEngine engine = CopybotEngine.create(Optional.of(CONFIG))) {
            Plan plan = prepared(engine, pipeline);
            WorkItemExecution a = named(plan, "a.jpg");

            plan.setOutPattern(List.of(a), southPattern());

            assertNull(a.getTargetCheck());
            assertEquals(targets(south), plan.projectionOf(a));
        }
    }

    @Test
    public void executionWritesAtTheOwnTarget() throws Exception {
        Path pipeline = pipeline("");
        try (CopybotEngine engine = CopybotEngine.create(Optional.of(CONFIG))) {
            Plan plan = prepared(engine, pipeline);
            plan.setOutPattern(List.of(named(plan, "a.jpg")), southPattern());

            assertEquals(PipelineStatus.SUCCESS, engine.execute(plan, null).await());
        }
        assertEquals("a", Files.readString(south.resolve("a.jpg")));
        assertFalse(Files.exists(out.resolve("a.jpg")));
        assertTrue(Files.exists(out.resolve("b.jpg")));
    }
}

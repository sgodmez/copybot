package com.copybot.engine;

import com.copybot.engine.pipeline.ConflictCheck;
import com.copybot.engine.pipeline.ItemStatus;
import com.copybot.engine.pipeline.PipelineStatus;
import com.copybot.engine.pipeline.PipelineStep;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.engine.resume.ResumeContext;
import com.copybot.engine.resume.ResumeMode;
import com.copybot.engine.resume.ResumePoint;
import com.copybot.engine.resume.ResumeStateStore;
import com.copybot.plugin.api.action.IOutAction;
import com.copybot.plugin.api.action.IProcessAction;
import com.copybot.plugin.api.action.TargetCheck;
import com.copybot.plugin.api.action.TargetCheck.Kind;
import com.copybot.plugin.api.action.WorkItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.copybot.engine.ControlFakes.*;
import static org.junit.jupiter.api.Assertions.*;

/** The plan checks the targets of the files it selects (spec conflict-check §2, §4). */
public class ConflictCheckTest {

    @TempDir
    Path tempDir;

    private ResumeStateStore store() {
        return new ResumeStateStore(tempDir.resolve("p.state.json"));
    }

    /** Out step answering a check per name (FREE by default); the failing names throw. Records what it was asked. */
    static final class CheckingOut extends FakeAction implements IOutAction {
        final Map<String, Kind> kinds;
        final Map<String, Boolean> asked = new ConcurrentHashMap<>();
        final Set<String> failing = ConcurrentHashMap.newKeySet();

        CheckingOut(Map<String, Kind> kinds) {
            this.kinds = kinds;
        }

        @Override
        public void writeItem(WorkItem item) {
        }

        @Override
        public TargetCheck checkTarget(WorkItem item, boolean compareContent) {
            String name = name(item);
            asked.put(name, compareContent);
            if (failing.contains(name)) {
                throw new IllegalStateException("unreachable share");
            }
            Kind kind = kinds.getOrDefault(name, Kind.FREE);
            return kind == Kind.FREE ? TargetCheck.free() : new TargetCheck(kind, Path.of("nas", name), "msg " + name);
        }
    }

    static String name(WorkItem item) {
        String display = item.getMetadatas().display().get("name");
        return display != null ? display : item.getNameDisplay();
    }

    /** Process step whose dry run forks each item into "<name>#1" and "<name>#2". */
    static final class ForkingProcess extends FakeAction implements IProcessAction {
        @Override
        public List<WorkItem> doProcess(WorkItem item) {
            return List.of(item);
        }

        @Override
        public Optional<List<WorkItem>> dryRun(WorkItem item) {
            WorkItem one = item.copyForDryRun();
            one.getMetadatas().display().put("name", item.getNameDisplay() + "#1");
            WorkItem two = item.copyForDryRun();
            two.getMetadatas().display().put("name", item.getNameDisplay() + "#2");
            return Optional.of(List.of(one, two));
        }
    }

    private MainExecutor executor(int days, IOutAction out) {
        return withResume(new DatedIn(tempDir, days, null), out, registry(Map.of("disk:*", 1000)), store());
    }

    private static WorkItemExecution named(MainExecutor exec, String name) {
        return exec.getState().getWorkItems().stream()
                .filter(i -> i.getWorkItem().getNameDisplay().equals(name)).findFirst().orElseThrow();
    }

    @Test
    public void thePreparationChecksTheTargetOfEachFileItAnalyses() {
        CheckingOut out = new CheckingOut(Map.of("IMG_02.JPG", Kind.SAME_SIZE));
        MainExecutor exec = executor(3, out);

        exec.prepare();

        assertEquals(PipelineStatus.PREPARED, exec.getState().getStatus());
        assertEquals(Kind.SAME_SIZE, named(exec, "IMG_02.JPG").getTargetCheck().kind());
        assertEquals("msg IMG_02.JPG", named(exec, "IMG_02.JPG").getTargetCheck().message());
        assertEquals(Kind.FREE, named(exec, "IMG_01.JPG").getTargetCheck().kind());
        assertEquals(Map.of("IMG_01.JPG", false, "IMG_02.JPG", false, "IMG_03.JPG", false), out.asked,
                "quick by default: no content comparison");
    }

    @Test
    public void noneChecksNothing() {
        CheckingOut out = new CheckingOut(Map.of());
        MainExecutor exec = executor(2, out);
        exec.setConflictCheck(ConflictCheck.NONE);

        exec.prepare();

        assertTrue(out.asked.isEmpty());
        assertNull(named(exec, "IMG_01.JPG").getTargetCheck());
    }

    @Test
    public void fullAsksTheOutStepForItsOwnComparison() throws Exception {
        Path source = java.nio.file.Files.createDirectory(tempDir.resolve("full"));
        CheckingOut out = new CheckingOut(Map.of());
        MainExecutor exec = withResume(new DatedIn(source, 2, null), out, registry(Map.of("disk:*", 1000)), store());
        exec.setConflictCheck(ConflictCheck.FULL);

        exec.prepare();

        assertEquals(Map.of("IMG_01.JPG", true, "IMG_02.JPG", true), out.asked);
    }

    @Test
    public void theFilesBeforeTheCursorAreNotCheckedUntilSelectedAgain() {
        store().writeCursor(day(2));
        CheckingOut out = new CheckingOut(Map.of("IMG_01.JPG", Kind.DIFFERENT_SIZE));
        MainExecutor exec = executor(3, out);

        exec.prepare();

        assertEquals(Set.of("IMG_03.JPG"), out.asked.keySet(), "skipped at the listing: never analysed nor checked");
        assertNull(named(exec, "IMG_01.JPG").getTargetCheck());

        exec.applyOverride(ResumePoint.all());
        exec.analyseDeferred();

        assertEquals(Kind.DIFFERENT_SIZE, named(exec, "IMG_01.JPG").getTargetCheck().kind(), "checked by its deferred analysis");
        assertEquals(Set.of("IMG_01.JPG", "IMG_02.JPG", "IMG_03.JPG"), out.asked.keySet());
    }

    @Test
    public void aFailingCheckIsUnknownAndNeverAnError() {
        CheckingOut out = new CheckingOut(Map.of());
        out.failing.add("IMG_01.JPG");
        MainExecutor exec = executor(1, out);

        exec.prepare();

        WorkItemExecution item = named(exec, "IMG_01.JPG");
        assertEquals(ItemStatus.PENDING, item.getStatus());
        assertSame(TargetCheck.UNKNOWN, item.getTargetCheck());
    }

    @Test
    public void aForkedFileKeepsTheMostSevereCheck() {
        CheckingOut out = new CheckingOut(Map.of("IMG_01.JPG#1", Kind.SAME_SIZE, "IMG_01.JPG#2", Kind.DIFFERENT_SIZE));
        MainExecutor exec = new MainExecutor(
                List.of(new PipelineStep<>(null, new DatedIn(tempDir, 1, null), emptyConfig())),
                List.of(new PipelineStep<>(null, new NoopAnalyze(), emptyConfig()),
                        new PipelineStep<>(null, new ForkingProcess(), emptyConfig()),
                        new PipelineStep<>(null, out, emptyConfig())),
                1, false, null, registry(Map.of("disk:*", 1000)), new ResumeContext(ResumeMode.STATE, store()));

        exec.prepare();

        assertEquals(Kind.DIFFERENT_SIZE, named(exec, "IMG_01.JPG").getTargetCheck().kind());
        assertEquals(Set.of("IMG_01.JPG#1", "IMG_01.JPG#2"), out.asked.keySet());
    }

    @Test
    public void anOutStepThatCannotTellLeavesTheFilesUnknown() {
        MainExecutor exec = executor(1, new GatedOut(Set.of(), null));

        exec.prepare();

        assertSame(TargetCheck.UNKNOWN, named(exec, "IMG_01.JPG").getTargetCheck());
    }
}

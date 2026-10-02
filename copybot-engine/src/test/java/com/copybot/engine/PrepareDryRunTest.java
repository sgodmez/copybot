package com.copybot.engine;

import com.copybot.engine.ControlFakes.FakeAction;
import com.copybot.engine.pipeline.ItemStatus;
import com.copybot.engine.pipeline.PipelineStatus;
import com.copybot.engine.pipeline.PipelineStep;
import com.copybot.engine.pipeline.PipelineStepConfig;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.engine.resume.ResumeContext;
import com.copybot.engine.resume.ResumeMode;
import com.copybot.engine.resume.ResumeStateStore;
import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.IInAction;
import com.copybot.plugin.api.action.IOutAction;
import com.copybot.plugin.api.action.IProcessAction;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.copybot.engine.ControlFakes.registry;
import static org.junit.jupiter.api.Assertions.*;

/** "Prepare" projects the process steps by their dry run, for every item prepared without error (spec pattern-helper §4.3). */
public class PrepareDryRunTest {

    @TempDir
    Path tempDir;

    /** Emits the given files, dated, with their display name set. */
    final class NamedIn extends FakeAction implements IInAction {
        final List<String> names;

        NamedIn(String... names) {
            this.names = List.of(names);
        }

        @Override
        public void listFiles(Consumer<WorkItem> consumer) {
            int day = 1;
            for (String name : names) {
                try {
                    WorkItem wi = new WorkItem(Files.createFile(tempDir.resolve(name)));
                    wi.getMetadatas().display().put("name", name);
                    wi.getMetadatas().setTime(WorkItemMetadata.LAST_MODIFIED,
                            Instant.parse(String.format("2026-09-%02dT10:00:00Z", day++)));
                    consumer.accept(wi);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
    }

    /** A process action: dry run as given, doProcess records the display names it sees. */
    static final class Process extends FakeAction implements IProcessAction {
        final Function<WorkItem, Optional<List<WorkItem>>> dryRun;
        final List<String> processed = new CopyOnWriteArrayList<>();

        Process(Function<WorkItem, Optional<List<WorkItem>>> dryRun) {
            this.dryRun = dryRun;
        }

        @Override
        public List<WorkItem> doProcess(WorkItem item) {
            processed.add(item.getMetadatas().display().get("name"));
            return List.of(item);
        }

        @Override
        public Optional<List<WorkItem>> dryRun(WorkItem item) {
            return dryRun.apply(item);
        }
    }

    static Process toJpg() {
        return new Process(item -> {
            item.getMetadatas().display().put("name", item.getMetadatas().display().get("name").replace(".NEF", ".jpg"));
            return Optional.of(List.of(item));
        });
    }

    /** Its target is out/&lt;display name&gt;; a name starting with "bad" has no value for a pattern key. */
    final class Out extends FakeAction implements IOutAction {
        @Override
        public void writeItem(WorkItem item) {
        }

        @Override
        public Optional<Path> resolveTarget(WorkItem item) {
            String name = item.getMetadatas().display().get("name");
            if (name.startsWith("bad")) {
                throw CopybotException.ofResource("write.pattern.missing-key", "{x}", name);
            }
            return Optional.of(tempDir.resolve("out").resolve(name));
        }
    }

    private static PipelineStep<IProcessAction> processStep(String name, IProcessAction action) {
        return new PipelineStep<>(null, action, new PipelineStepConfig(null, name, null, null, null, null, null, null));
    }

    private Plan prepared(NamedIn in, IProcessAction process, boolean withOut) {
        List<PipelineStep<?>> steps = new ArrayList<>();
        steps.add(processStep("convert", process));
        if (withOut) {
            steps.add(new PipelineStep<>(null, new Out(), ControlFakes.emptyConfig()));
        }
        MainExecutor executor = new MainExecutor(
                List.of(new PipelineStep<>(null, in, ControlFakes.emptyConfig())), steps,
                0, false, null, registry(Map.of("disk:*", 1000)),
                new ResumeContext(ResumeMode.STATE, new ResumeStateStore(tempDir.resolve("p.state.json"))));
        executor.prepare();
        assertEquals(PipelineStatus.PREPARED, executor.getState().getStatus());
        return new Plan(executor);
    }

    private static WorkItemExecution named(Plan plan, String name) {
        return plan.getOrderedItems().stream()
                .filter(w -> w.getWorkItem().getNameDisplay().equals(name))
                .findFirst().orElseThrow();
    }

    private TargetProjection.Targets out() {
        return new TargetProjection.Targets(List.of(tempDir.resolve("out").toAbsolutePath().normalize()));
    }

    @Test
    public void prepareProjectsTheProcessStepsWithoutRunningThem() {
        Process process = toJpg();
        Plan plan = prepared(new NamedIn("DSC_1.NEF"), process, true);

        assertEquals(out(), plan.projectionOf(named(plan, "DSC_1.NEF")));
        assertTrue(process.processed.isEmpty(), "doProcess never runs while preparing");
    }

    @Test
    public void aForkGivesOneTargetPerProducedItem() {
        Process fork = new Process(item -> {
            WorkItem thumb = item.copyForDryRun();
            thumb.getMetadatas().display().put("name", "thumbs/DSC_1.jpg");
            item.getMetadatas().display().put("name", "DSC_1.jpg");
            return Optional.of(List.of(item, thumb));
        });
        Plan plan = prepared(new NamedIn("DSC_1.NEF"), fork, true);

        assertEquals(new TargetProjection.Targets(List.of(
                        tempDir.resolve("out").toAbsolutePath().normalize(),
                        tempDir.resolve("out").resolve("thumbs").toAbsolutePath().normalize())),
                plan.projectionOf(named(plan, "DSC_1.NEF")));
    }

    @Test
    public void aFilteringStepShowsWhoFilteredIt() {
        Plan plan = prepared(new NamedIn("DSC_1.NEF"), new Process(item -> Optional.of(List.of())), true);

        assertEquals(new TargetProjection.Filtered("convert"), plan.projectionOf(named(plan, "DSC_1.NEF")));
    }

    @Test
    public void aStepWithoutDryRunMakesTheTargetUnknown() {
        Plan plan = prepared(new NamedIn("DSC_1.NEF"), new Process(item -> Optional.empty()), true);

        assertEquals(new TargetProjection.Unknown("convert"), plan.projectionOf(named(plan, "DSC_1.NEF")));
    }

    @Test
    public void aFailingDryRunDoesNotFailTheItem() {
        Plan plan = prepared(new NamedIn("DSC_1.NEF"), new Process(item -> {
            throw new IllegalStateException("boom");
        }), true);

        WorkItemExecution item = named(plan, "DSC_1.NEF");
        TargetProjection projection = plan.projectionOf(item);
        assertInstanceOf(TargetProjection.Failed.class, projection);
        assertTrue(((TargetProjection.Failed) projection).message().contains("boom"));
        assertEquals(ItemStatus.PENDING, item.getStatus());
    }

    @Test
    public void aMissingKeyShowsItsMessage() {
        Plan plan = prepared(new NamedIn("DSC_1.NEF", "bad.NEF"), toJpg(), true);

        TargetProjection projection = plan.projectionOf(named(plan, "bad.NEF"));
        assertInstanceOf(TargetProjection.Failed.class, projection);
        assertTrue(((TargetProjection.Failed) projection).message().contains("{x}"));
        assertEquals(out(), plan.projectionOf(named(plan, "DSC_1.NEF")));
    }

    @Test
    public void theExecutionStillRunsDoProcessOnTheRealItem() {
        Process process = toJpg();
        Plan plan = prepared(new NamedIn("DSC_1.NEF"), process, true);

        plan.getExecutor().execute(null);

        assertEquals(List.of("DSC_1.NEF"), process.processed, "the dry run did not touch the real item");
    }

    @Test
    public void withoutOutStepThereIsNoTarget() {
        Plan plan = prepared(new NamedIn("DSC_1.NEF"), toJpg(), false);

        assertEquals(TargetProjection.NONE, plan.projectionOf(named(plan, "DSC_1.NEF")));
    }
}

package com.copybot.engine;

import com.copybot.engine.pipeline.PipelineStep;
import com.copybot.engine.pipeline.PipelineStepConfig;
import com.copybot.plugin.api.action.IProcessAction;
import com.copybot.plugin.api.action.WorkItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

public class DryRunnerTest {

    @TempDir
    Path tempDir;

    /** A process action whose dry run is the given function; doProcess must never be called. */
    static final class FakeProcess extends ControlFakes.FakeAction implements IProcessAction {
        private final Function<WorkItem, Optional<List<WorkItem>>> dryRun;

        FakeProcess(Function<WorkItem, Optional<List<WorkItem>>> dryRun) {
            this.dryRun = dryRun;
        }

        @Override
        public List<WorkItem> doProcess(WorkItem item) {
            throw new AssertionError("doProcess must not run in a dry run");
        }

        @Override
        public Optional<List<WorkItem>> dryRun(WorkItem item) {
            return dryRun.apply(item);
        }
    }

    /** Converts the item to JPG (display name). */
    static FakeProcess toJpg() {
        return new FakeProcess(item -> {
            item.getMetadatas().display().put("name", item.getMetadatas().display().get("name").replace(".NEF", ".jpg"));
            return Optional.of(List.of(item));
        });
    }

    static PipelineStep<IProcessAction> step(String name, IProcessAction action) {
        return new PipelineStep<>(null, action, new PipelineStepConfig(null, name, null, null, null, null, null, null));
    }

    private WorkItem nef() throws IOException {
        WorkItem item = new WorkItem(Files.writeString(tempDir.resolve("DSC_1.NEF"), "x"));
        item.getMetadatas().display().put("name", "DSC_1.NEF");
        return item;
    }

    @Test
    public void withoutProcessStepTheProjectionIsACopyOfTheItem() throws IOException {
        WorkItem item = nef();

        Projection.Projected projected = assertInstanceOf(Projection.Projected.class, DryRunner.project(item, List.of()));

        assertEquals(1, projected.items().size());
        assertNotSame(item, projected.items().getFirst());
        assertEquals("DSC_1.NEF", projected.items().getFirst().getMetadatas().display().get("name"));
    }

    @Test
    public void theStepsAreChainedOnEveryProducedItem() throws IOException {
        FakeProcess fork = new FakeProcess(item -> {
            WorkItem thumb = item.copyForDryRun();
            thumb.getMetadatas().display().put("name", "thumb_" + item.getMetadatas().display().get("name"));
            return Optional.of(List.of(item, thumb));
        });

        Projection projection = DryRunner.project(nef(), List.of(step("fork", fork), step("conv", toJpg())));

        List<String> names = assertInstanceOf(Projection.Projected.class, projection).items().stream()
                .map(i -> i.getMetadatas().display().get("name")).toList();
        assertEquals(List.of("DSC_1.jpg", "thumb_DSC_1.jpg"), names);
    }

    @Test
    public void anEmptyListIsFilteredByThatStep() throws IOException {
        Projection projection = DryRunner.project(nef(), List.of(step("drop", new FakeProcess(i -> Optional.of(List.of())))));

        assertEquals(new Projection.Filtered("drop"), projection);
    }

    @Test
    public void aStepWithoutDryRunStopsWithTheItemsBeforeIt() throws IOException {
        Projection projection = DryRunner.project(nef(),
                List.of(step("conv", toJpg()), step("legacy", new FakeProcess(i -> Optional.empty()))));

        Projection.Unsupported unsupported = assertInstanceOf(Projection.Unsupported.class, projection);
        assertEquals("legacy", unsupported.action());
        assertEquals("DSC_1.jpg", unsupported.before().getFirst().getMetadatas().display().get("name"));
    }

    @Test
    public void aNullDryRunMeansUnsupported() throws IOException {
        assertInstanceOf(Projection.Unsupported.class,
                DryRunner.project(nef(), List.of(step("legacy", new FakeProcess(i -> null)))));
    }

    @Test
    public void aFailingDryRunGivesItsMessage() throws IOException {
        Projection projection = DryRunner.project(nef(), List.of(step("boom", new FakeProcess(i -> {
            throw new IllegalStateException("no codec");
        }))));

        assertEquals(new Projection.Failed("boom", "no codec"), projection);
    }

    @Test
    public void theTraceNamesWhatEachStepProduced() throws IOException {
        FakeProcess fork = new FakeProcess(item -> {
            WorkItem thumb = item.copyForDryRun();
            thumb.getMetadatas().display().put("name", "thumb_" + item.getMetadatas().display().get("name"));
            return Optional.of(List.of(item, thumb));
        });

        Projection projection = DryRunner.project(nef(), List.of(step("fork", fork), step("conv", toJpg())));

        assertEquals(List.of(
                new Projection.Step("fork", List.of("DSC_1.NEF", "thumb_DSC_1.NEF")),
                new Projection.Step("conv", List.of("DSC_1.jpg", "thumb_DSC_1.jpg"))), projection.trace());
    }

    @Test
    public void theTraceStopsBeforeTheStepThatEndedTheDryRun() throws IOException {
        List<Projection.Step> converted = List.of(new Projection.Step("conv", List.of("DSC_1.jpg")));

        assertEquals(converted, DryRunner.project(nef(), List.of(step("conv", toJpg()),
                step("drop", new FakeProcess(i -> Optional.of(List.of()))))).trace());
        assertEquals(converted, DryRunner.project(nef(), List.of(step("conv", toJpg()),
                step("legacy", new FakeProcess(i -> Optional.empty())))).trace());
        assertEquals(converted, DryRunner.project(nef(), List.of(step("conv", toJpg()),
                step("boom", new FakeProcess(i -> {
                    throw new IllegalStateException("no codec");
                })))).trace());
    }

    @Test
    public void withoutProcessStepTheTraceIsEmpty() throws IOException {
        assertEquals(List.of(), DryRunner.project(nef(), List.of()).trace());
    }

    @Test
    public void theRealItemIsNeverTouchedByTheDryRun() throws IOException {
        WorkItem item = nef();

        DryRunner.project(item, List.of(step("conv", toJpg())));

        assertEquals("DSC_1.NEF", item.getMetadatas().display().get("name"));
    }
}

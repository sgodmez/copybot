package com.copybot.engine.resume;

import com.copybot.engine.pipeline.ItemStatus;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.IOutAction;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import com.copybot.plugin.api.action.WorkStatus;
import com.copybot.plugin.api.definition.IPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

public class ResumeResolverMissingKeyTest {

    @TempDir
    Path tempDir;

    /** Out action whose target resolution is the given function (it may throw). */
    private static final class FakeOut implements IOutAction {
        private final Function<WorkItem, Path> target;

        FakeOut(Function<WorkItem, Path> target) {
            this.target = target;
        }

        @Override
        public void writeItem(WorkItem workItem) {
        }

        @Override
        public Optional<Path> resolveTarget(WorkItem workItem) {
            return Optional.of(target.apply(workItem));
        }

        @Override
        public void setStatusWatcher(Consumer<WorkStatus> watcher) {
        }

        @Override
        public void setPlugin(IPlugin plugin) {
        }
    }

    private WorkItemExecution item(String name, String instant) throws IOException {
        WorkItem wi = new WorkItem(Files.createFile(tempDir.resolve(name)));
        wi.getMetadatas().setTime(WorkItemMetadata.LAST_MODIFIED, Instant.parse(instant));
        return new WorkItemExecution(wi, List.of());
    }

    private List<WorkItemExecution> card() throws IOException {
        return ResumeResolver.order(List.of(
                item("A.JPG", "2026-09-01T10:00:00Z"),
                item("B.JPG", "2026-09-02T10:00:00Z"),
                item("VID.MP4", "2026-09-03T10:00:00Z")));
    }

    private static String day(WorkItem workItem) {
        return ItemKey.of(workItem).orElseThrow().date().toString().substring(0, 10);
    }

    private ResumeStateStore store() {
        return new ResumeStateStore(tempDir.resolve("p.state.json"));
    }

    @Test
    public void anItemWithoutTargetIsLeftOutOfTheProbe() throws IOException {
        List<WorkItemExecution> ordered = card();
        Files.createDirectories(tempDir.resolve("nas").resolve("2026-09-01"));
        FakeOut out = new FakeOut(workItem -> {
            if (workItem.getNameDisplay().equals("VID.MP4")) {
                throw CopybotException.ofResource("write.pattern.missing-key", "{captureDate.Y}", "VID.MP4");
            }
            return tempDir.resolve("nas").resolve(day(workItem)).resolve(workItem.getNameDisplay());
        });
        ResumeResolver resolver = new ResumeResolver(ResumeMode.DESTINATION, store(), out);

        ResumeProposal p = resolver.propose(ordered);
        resolver.apply(p.point(), p.source(), ordered);

        assertEquals(ResumeSource.DESTINATION, p.source());
        assertEquals(ResumePoint.after(ItemKey.of(ordered.get(0).getWorkItem()).orElseThrow()), p.point());
        assertEquals(ItemStatus.SKIPPED, ordered.get(0).getStatus());
        assertEquals(ItemStatus.PENDING, ordered.get(1).getStatus());
        assertEquals(ItemStatus.PENDING, ordered.get(2).getStatus(), "the item without target stays selected");
    }

    @Test
    public void anotherFailureOfTheTargetStillPropagates() throws IOException {
        List<WorkItemExecution> ordered = card();
        FakeOut out = new FakeOut(workItem -> {
            throw new IllegalStateException("boom");
        });

        assertThrows(IllegalStateException.class,
                () -> new ResumeResolver(ResumeMode.DESTINATION, store(), out).propose(ordered));
    }
}

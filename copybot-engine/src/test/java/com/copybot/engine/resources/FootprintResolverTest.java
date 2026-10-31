package com.copybot.engine.resources;

import com.copybot.engine.pipeline.PipelineStepConfig;
import com.copybot.plugin.api.action.IAction;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkStatus;
import com.copybot.plugin.api.definition.IPlugin;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class FootprintResolverTest {

    @TempDir
    Path tempDir;

    static final class FakeAction implements IAction {
        final Set<String> required;
        final Set<Path> touched;

        FakeAction(Set<String> required, Set<Path> touched) {
            this.required = required;
            this.touched = touched;
        }

        @Override
        public Set<String> requiredResources(WorkItem item) {
            return required;
        }

        @Override
        public Set<Path> touchedPaths(WorkItem item) {
            return touched;
        }

        @Override
        public void setPlugin(IPlugin plugin) {
        }
    }

    static PipelineStepConfig config(Integer maxConcurrency, List<String> resources) {
        return new PipelineStepConfig(null, null, null, null, maxConcurrency, null, resources, null);
    }

    @Test
    public void mergesActionEngineAndUserContributions() throws IOException {
        Path file = Files.createFile(tempDir.resolve("photo.jpg"));
        Path target = tempDir.resolve("out");
        WorkItem item = new WorkItem(file);
        FakeAction action = new FakeAction(Set.of("cpu"), Set.of(target));

        Set<String> footprint = FootprintResolver.resolve(action, item, config(2, List.of("gpu")), 3);

        String disk = DiskResolver.diskResource(tempDir);
        assertEquals(Set.of("cpu", "gpu", disk, "step:3"), footprint);
    }

    @Test
    public void aLookAtTheTargetsTakesTheirDisksOnlyNotTheSourceDisk() throws IOException {
        WorkItem item = new WorkItem(Files.createFile(tempDir.resolve("photo.jpg")));

        assertEquals(Set.of(), FootprintResolver.forTargets(new FakeAction(Set.of("cpu"), Set.of()), item),
                "nothing touched: nothing held, not even the disk of the file");
        assertEquals(Set.of(DiskResolver.diskResource(tempDir)),
                FootprintResolver.forTargets(new FakeAction(Set.of("cpu"), Set.of(tempDir.resolve("nas"))), item));
    }

    @Test
    public void listingFootprintUsesNullItem() {
        FakeAction action = new FakeAction(Set.of(), Set.of(tempDir));
        Set<String> footprint = FootprintResolver.forListing(action, config(null, null));
        assertEquals(Set.of(DiskResolver.diskResource(tempDir)), footprint);
    }

    @Test
    public void noConcurrencyLimitMeansNoStepResource() throws IOException {
        Path file = Files.createFile(tempDir.resolve("a.txt"));
        WorkItem item = new WorkItem(file);
        FakeAction action = new FakeAction(Set.of(), Set.of());

        Set<String> footprint = FootprintResolver.resolve(action, item, config(null, null), 0);

        assertEquals(Set.of(DiskResolver.diskResource(tempDir)), footprint);
    }

    @Test
    public void aBufferedItemHoldsTheDiskOfItsBufferNotOfItsSource() throws IOException {
        Path source = Path.of(tempDir.getRoot().toString().startsWith("/") ? "/copybot-no-card/a.jpg" : "Q:\\DCIM\\a.jpg");
        Path buffer = Files.createFile(tempDir.resolve("a.jpg.buffer"));
        String sourceDisk = DiskResolver.diskResource(source);
        String bufferDisk = DiskResolver.diskResource(buffer);
        Assumptions.assumeFalse(sourceDisk.equals(bufferDisk), "the source and the buffer must be on two disks");
        WorkItem item = new WorkItem(source).withContentFile(buffer);

        Set<String> footprint = FootprintResolver.resolve(new FakeAction(Set.of(), Set.of()), item, config(null, null), 0);

        assertEquals(Set.of(bufferDisk), footprint);
    }

    @Test
    public void anItemFromAUrlBufferedLocallyHoldsTheDiskOfItsBuffer() throws IOException {
        Path buffer = Files.createFile(tempDir.resolve("b.jpg.buffer"));
        WorkItem item = new WorkItem(new java.net.URL("http://example.invalid/b.jpg"), java.io.InputStream::nullInputStream)
                .withContentFile(buffer);

        Set<String> footprint = FootprintResolver.resolve(new FakeAction(Set.of(), Set.of()), item, config(null, null), 0);

        assertEquals(Set.of(DiskResolver.diskResource(buffer)), footprint);
    }
}

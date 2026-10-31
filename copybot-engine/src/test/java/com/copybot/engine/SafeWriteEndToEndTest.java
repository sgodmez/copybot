package com.copybot.engine;

import com.copybot.engine.pipeline.ItemStatus;
import com.copybot.engine.pipeline.PipelineStatus;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.plugin.api.action.IInAction;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.embedded.actions.FileWriteAction;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static com.copybot.engine.ControlFakes.*;
import static org.junit.jupiter.api.Assertions.*;

/** A real file.read -> file.write pipeline through the engine: concurrency and cancel (spec safe-write). */
public class SafeWriteEndToEndTest {

    private static final String TEMP_SUFFIX = ".copybot-tmp";

    /** Relative to the module directory, the working directory of the test JVM; no resources: disk capacity 2. */
    private static final Path CONFIG = Path.of("src", "test", "resources", "com", "copybot", "engine", "config.json");

    @TempDir
    Path tempDir;

    private static String json(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }

    private static void photo(Path file, String content, String date) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse(date)));
    }

    private static List<String> names(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    public void twoSameNamePhotosWrittenConcurrentlyAreBothKeptThenSkippedAsIdentical() throws Exception {
        Path card = tempDir.resolve("card");
        Path nas = tempDir.resolve("nas");
        photo(card.resolve("100CANON").resolve("IMG_0001.JPG"), "first folder", "2026-09-01T10:00:00Z");
        photo(card.resolve("101CANON").resolve("IMG_0001.JPG"), "second folder, other content", "2026-09-02T10:00:00Z");
        Path pipeline = Files.writeString(tempDir.resolve("sd.json"), """
                {
                  "inSteps": [ { "action": "file.read", "actionConfig": { "path": "%s" } } ],
                  "outStep": { "action": "file.write", "actionConfig": { "outPattern": "%s/{name}" } },
                  "resume": { "mode": "state" }
                }
                """.formatted(json(card), json(nas)));
        Path state = tempDir.resolve("sd.state.json");

        // default disk capacity (2): both items are written at the same time
        try (CopybotEngine engine = CopybotEngine.create(Optional.of(CONFIG))) {
            Execution first = engine.run(pipeline, null);
            assertEquals(PipelineStatus.SUCCESS, awaitStatus(first), String.valueOf(first.getState().getFailure()));
            assertEquals(List.of(ItemStatus.DONE, ItemStatus.DONE),
                    first.getState().getWorkItems().stream().map(WorkItemExecution::getStatus).toList());
            assertEquals(List.of("IMG_0001 (1).JPG", "IMG_0001.JPG"), names(nas));
            assertEquals(Set.of("first folder", "second folder, other content"),
                    Set.of(Files.readString(nas.resolve("IMG_0001.JPG")), Files.readString(nas.resolve("IMG_0001 (1).JPG"))));
            assertTrue(Files.exists(state));

            Files.delete(state); // everything is selected again: the out step finds both at the destination
            Execution second = engine.run(pipeline, null);

            assertEquals(PipelineStatus.SUCCESS, awaitStatus(second));
            for (WorkItemExecution item : second.getState().getWorkItems()) {
                assertEquals(ItemStatus.SKIPPED, item.getStatus());
                assertTrue(item.getSkipReason().contains("IMG_0001"), item.getSkipReason());
            }
            assertEquals(List.of("IMG_0001 (1).JPG", "IMG_0001.JPG"), names(nas), "no duplicate");
            assertTrue(Files.exists(state), "skipped items count as imported: the cursor is written");
            assertTrue(Files.readString(state).contains("2026-09-02"), Files.readString(state));
        }
    }

    /** One photo whose content stops after the first bytes until the copy is interrupted (bounded: 20 s). */
    private static final class StalledIn extends FakeAction implements IInAction {
        final Path source;
        final CountDownLatch copying = new CountDownLatch(1);

        StalledIn(Path source) {
            this.source = source;
        }

        @Override
        public void listFiles(Consumer<WorkItem> consumer) {
            try {
                WorkItem item = new WorkItem(source) {
                    @Override
                    public InputStream openInputStream() {
                        return new InputStream() {
                            private int served;

                            @Override
                            public int read() throws IOException {
                                if (served < 8192) {
                                    served++;
                                    return 'x';
                                }
                                copying.countDown();
                                try {
                                    new CountDownLatch(1).await(20, TimeUnit.SECONDS);
                                } catch (InterruptedException e) {
                                    throw new InterruptedIOException("cancelled");
                                }
                                return -1;
                            }
                        };
                    }
                };
                item.getMetadatas().display().put("name", "IMG_0001.JPG");
                item.getMetadatas().setSize(1_000_000L);
                consumer.accept(item);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @Test
    public void aCancelDuringTheCopyLeavesNoTemporaryFileAndTheItemPending() throws Exception {
        Path source = Files.writeString(Files.createDirectories(tempDir.resolve("card")).resolve("IMG_0001.JPG"), "x");
        Path nas = tempDir.resolve("nas");
        FileWriteAction out = new FileWriteAction();
        out.loadConfig(JsonParser.parseString("{\"outPattern\":\"" + json(nas) + "/{name}\"}"));
        StalledIn in = new StalledIn(source);
        MainExecutor pipeline = singlePhase(in, out, registry(Map.of("disk:*", 1000)));

        try (CopybotEngine engine = new CopybotEngine(config())) {
            Execution execution = engine.submit(pipeline, pipeline::run);
            assertTrue(in.copying.await(10, TimeUnit.SECONDS), "the copy started");
            try (Stream<Path> files = Files.list(nas)) {
                assertTrue(files.anyMatch(p -> p.getFileName().toString().endsWith(TEMP_SUFFIX)), "copying to a temporary file");
            }

            execution.cancel();

            assertEquals(PipelineStatus.CANCELLED, awaitStatus(execution));
            assertEquals(ItemStatus.PENDING, execution.getState().getWorkItems().peek().getStatus());
            assertEquals(List.of(), names(nas), "no temporary file, no partial file");
            assertTrue(Files.exists(source));
        }
    }
}

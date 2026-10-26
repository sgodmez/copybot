package com.copybot.engine;

import com.copybot.engine.pipeline.ItemStatus;
import com.copybot.engine.pipeline.PipelineStatus;
import com.copybot.engine.pipeline.WorkItemExecution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static com.copybot.engine.ControlFakes.awaitStatus;
import static org.junit.jupiter.api.Assertions.*;

/** A real file.read -> file.buffer -> file.write pipeline through the engine. */
public class BufferEndToEndTest {

    /** Relative to the module directory, the working directory of the test JVM. */
    private static final Path CONFIG = Path.of("src", "test", "resources", "com", "copybot", "engine", "config.json");

    @TempDir
    Path tempDir;

    private static String json(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }

    private static List<Path> files(Path dir) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile).toList();
        }
    }

    @Test
    public void theCardIsCopiedThroughTheBufferMovedAndTheBufferLeftEmpty() throws Exception {
        Path card = Files.createDirectories(tempDir.resolve("card").resolve("DCIM"));
        Path nas = tempDir.resolve("nas");
        Path buffer = tempDir.resolve("buffer");
        for (int i = 1; i <= 5; i++) {
            Files.writeString(card.resolve("IMG_000" + i + ".JPG"), "photo " + i);
        }
        Path pipeline = Files.writeString(tempDir.resolve("sd.json"), """
                {
                  "inSteps": [ { "action": "file.read", "actionConfig": { "path": "%s" } } ],
                  "actionSteps": [ { "action": "file.buffer", "actionConfig": { "folder": "%s", "keepFreeGb": 0 } } ],
                  "outStep": { "action": "file.write", "actionConfig": { "outPattern": "%s/{name}",
                      "verify": "readBack", "deleteSource": true } }
                }
                """.formatted(json(card), json(buffer), json(nas)));

        try (CopybotEngine engine = CopybotEngine.create(Optional.of(CONFIG))) {
            Execution run = engine.run(pipeline, null);

            assertEquals(PipelineStatus.SUCCESS, awaitStatus(run), String.valueOf(run.getState().getFailure()));
            assertTrue(run.getState().getWorkItems().stream().map(WorkItemExecution::getStatus)
                    .allMatch(ItemStatus.DONE::equals));
        }
        for (int i = 1; i <= 5; i++) {
            assertEquals("photo " + i, Files.readString(nas.resolve("IMG_000" + i + ".JPG")));
        }
        assertEquals(List.of(), files(card), "deleteSource deletes the card's files, not the buffered copies");
        assertEquals(List.of(), files(buffer), "every copy is deleted");
        try (Stream<Path> runs = Files.list(buffer)) {
            assertEquals(0, runs.count(), "the run folder is removed");
        }
    }
}

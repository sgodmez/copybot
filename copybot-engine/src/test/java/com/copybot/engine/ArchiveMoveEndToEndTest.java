package com.copybot.engine;

import com.copybot.engine.pipeline.ItemStatus;
import com.copybot.engine.pipeline.PipelineStatus;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.resources.ResourcesEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.copybot.engine.ControlFakes.awaitStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A real file.read -> unfold -> file.write pipeline in move mode (spec grouping/archives, "Entrées"): the archive is
 * deleted only once every one of its entries was written in the run, else kept with a warning.
 */
public class ArchiveMoveEndToEndTest {

    /** Relative to the module directory, the working directory of the test JVM. */
    private static final Path CONFIG = Path.of("src", "test", "resources", "com", "copybot", "engine", "config.json");
    private static final Instant DAY_1 = Instant.parse("2026-09-01T10:00:00Z");

    @TempDir
    Path tempDir;

    private static String json(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }

    private Path card() {
        return tempDir.resolve("card");
    }

    private Path nas() {
        return tempDir.resolve("nas");
    }

    /** card/photos.zip with photos/IMG_1.JPG, photos/IMG_2.JPG and photos/notes.txt. */
    private Path zip() throws IOException {
        Path zip = Files.createDirectories(card()).resolve("photos.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (String name : List.of("IMG_1.JPG", "IMG_2.JPG", "notes.txt")) {
                ZipEntry entry = new ZipEntry("photos/" + name);
                entry.setLastModifiedTime(FileTime.from(DAY_1));
                out.putNextEntry(entry);
                out.write(("content of " + name).getBytes());
                out.closeEntry();
            }
        }
        return zip;
    }

    private Path pipeline(String archives, String ifDifferent) throws IOException {
        return Files.writeString(tempDir.resolve("move.json"), """
                {
                  "inSteps": [ { "action": "file.read", "actionConfig": { "path": "%s" } } ],
                  "outStep": { "action": "file.write", "actionConfig": { "outPattern": "%s/{name}",
                    "deleteSource": true, "onConflict": { "compare": "fullHash", "ifDifferent": "%s" } } },
                  "archives": %s
                }
                """.formatted(json(card()), json(nas()), ifDifferent, archives));
    }

    private static Execution run(Path pipeline) throws InterruptedException {
        try (CopybotEngine engine = CopybotEngine.create(Optional.of(CONFIG))) {
            Execution execution = engine.run(pipeline, null);
            awaitStatus(execution);
            return execution;
        }
    }

    private static String keptWarning(Path zip, int notCopied) {
        return ResourcesEngine.getString("archive.kept", zip.toString(), notCopied);
    }

    @Test
    public void anArchiveWhoseEntriesAreAllWrittenIsDeleted() throws Exception {
        Path zip = zip();

        Execution execution = run(pipeline("{ \"unfold\": true }", "rename"));

        assertEquals(PipelineStatus.SUCCESS, execution.getState().getStatus(),
                String.valueOf(execution.getState().getFailure()));
        assertEquals("content of notes.txt", Files.readString(nas().resolve("notes.txt")));
        assertEquals("content of IMG_1.JPG", Files.readString(nas().resolve("IMG_1.JPG")));
        assertFalse(Files.exists(zip), "every entry written: the archive is deleted");
        assertTrue(execution.getState().getWarnings().stream().noneMatch(w -> w.contains("photos.zip")),
                execution.getState().getWarnings().toString());
    }

    @Test
    public void anArchiveWithAnExcludedEntryIsKeptWithAWarning() throws Exception {
        Path zip = zip();

        Execution execution = run(pipeline("{ \"unfold\": true, \"exclude\": [\"**/*.txt\"] }", "rename"));

        assertEquals(PipelineStatus.SUCCESS, execution.getState().getStatus(),
                String.valueOf(execution.getState().getFailure()));
        assertTrue(Files.exists(nas().resolve("IMG_1.JPG")));
        assertTrue(Files.exists(nas().resolve("IMG_2.JPG")));
        assertFalse(Files.exists(nas().resolve("notes.txt")));
        assertTrue(Files.exists(zip), "an entry not copied: the archive is kept");
        assertTrue(execution.getState().getWarnings().contains(keptWarning(zip, 1)),
                execution.getState().getWarnings().toString());
    }

    @Test
    public void anArchiveWithAnEntryInErrorIsKept() throws Exception {
        Path zip = zip();
        Files.createDirectories(nas().resolve("IMG_2.JPG")); // a directory at its target, ifDifferent error

        Execution execution = run(pipeline("{ \"unfold\": true }", "error"));

        assertEquals(PipelineStatus.ERROR, execution.getState().getStatus());
        WorkItemExecution failed = execution.getState().getWorkItems().stream()
                .filter(e -> e.getWorkItem().getNameDisplay().equals("IMG_2.JPG")).findFirst().orElseThrow();
        assertEquals(ItemStatus.ERROR, failed.getStatus());
        assertTrue(Files.exists(nas().resolve("IMG_1.JPG")));
        assertTrue(Files.exists(zip), "an entry in error: the archive is kept");
        assertTrue(execution.getState().getWarnings().contains(keptWarning(zip, 1)),
                execution.getState().getWarnings().toString());
    }
}

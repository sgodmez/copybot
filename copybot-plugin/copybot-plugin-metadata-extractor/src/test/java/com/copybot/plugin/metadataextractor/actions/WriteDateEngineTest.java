package com.copybot.plugin.metadataextractor.actions;

import com.copybot.engine.CopybotEngine;
import com.copybot.engine.Execution;
import com.copybot.engine.ItemDetail;
import com.copybot.engine.Plan;
import com.copybot.engine.Projection;
import com.copybot.engine.pipeline.ItemStatus;
import com.copybot.engine.pipeline.PipelineStatus;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.copybot.plugin.metadataextractor.actions.TestJpegs.jpegWithDateOriginal;
import static org.junit.jupiter.api.Assertions.*;

/**
 * write-date through the engine (spec exif-date-rewrite §5): a real pipeline (file.read, extract, write-date,
 * file.write) whose plan is prepared, corrected to another time zone, analysed again, then executed. The plugin is
 * loaded by the engine as in a run, from this module's build directory (devPluginPaths).
 */
public class WriteDateEngineTest {

    private static final ZoneId MEXICO = ZoneId.of("America/Mexico_City");

    @TempDir
    Path tempDir;

    private static String json(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }

    /**
     * A development plugin directory holding this module's compiled classes only (target/classes, the surefire
     * working directory being this module). Its libraries (metadata-extractor, Commons Imaging) are those of the
     * test's boot layer: the engine loads only what the boot layer lacks, and target/lib, when an earlier package
     * left it, would hold them twice.
     */
    private Path devPlugin() throws IOException {
        Path classes = Path.of("target", "classes").toAbsolutePath();
        Path plugin = tempDir.resolve("plugin");
        try (Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.toList()) {
                Path copy = plugin.resolve("classes").resolve(classes.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(copy);
                } else {
                    Files.copy(file, copy);
                }
            }
        }
        return plugin;
    }

    @Test
    public void aCorrectedJpegIsCopiedWithItsLocalDateAndItsSourceDeleted() throws Exception {
        Path card = Files.createDirectories(tempDir.resolve("card"));
        Path nas = tempDir.resolve("nas");
        // shot at 01:30 on the 25th at +02:00 (the camera clock): 17:30 on the 24th in Mexico
        Path source = Files.write(card.resolve("DSC00001.JPG"), jpegWithDateOriginal("2026:10:25 01:30:00", "+02:00"));
        Path config = Files.writeString(tempDir.resolve("config.json"),
                "{ \"devPluginPaths\": \"" + json(devPlugin()) + "\" }");
        Path pipeline = Files.writeString(tempDir.resolve("photos.json"), """
                {
                  "inSteps": [ { "action": "file.read", "actionConfig": { "path": "%s" } } ],
                  "analyseSteps": [ { "plugin": "com.copybot.plugin.metadataextractor", "action": "extract" } ],
                  "actionSteps": [ { "plugin": "com.copybot.plugin.metadataextractor", "action": "write-date" } ],
                  "outStep": { "action": "file.write",
                               "actionConfig": { "outPattern": "%s/{name}", "verify": "readBack", "deleteSource": true } }
                }
                """.formatted(json(card), json(nas)));

        try (CopybotEngine engine = CopybotEngine.create(Optional.of(config))) {
            Plan plan = engine.prepare(pipeline, null);
            assertEquals(PipelineStatus.PREPARED, plan.getState().getStatus(), String.valueOf(plan.getState().getFailure()));
            WorkItemExecution photo = plan.getOrderedItems().getFirst();
            assertNull(plan.detailOf(photo).orElseThrow().steps().getFirst().note(), "not corrected: no verdict");

            plan.correctZone(List.of(photo), MEXICO, null, null);
            engine.analyse(plan);

            ItemDetail detail = plan.detailOf(photo).orElseThrow();
            assertEquals(List.of("EXIF réécrit : 2026-10-24 17:30 -06:00"),
                    detail.steps().stream().map(Projection.Step::note).toList(), "the planned processing");
            assertEquals(List.of(nas.resolve("DSC00001.JPG").toAbsolutePath().normalize()), detail.targets());

            Execution execution = engine.execute(plan, null);
            assertTrue(execution.await(20, TimeUnit.SECONDS), "the execution did not end within 20s");

            assertEquals(PipelineStatus.SUCCESS, execution.getState().getStatus(), String.valueOf(execution.getState().getFailure()));
            assertEquals(ItemStatus.DONE, photo.getStatus());
        }
        ExifSubIFDDirectory exif = ImageMetadataReader.readMetadata(nas.resolve("DSC00001.JPG").toFile())
                .getFirstDirectoryOfType(ExifSubIFDDirectory.class);
        assertEquals("2026:10:24 17:30:00", exif.getString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL));
        assertEquals("-06:00", exif.getString(ExifSubIFDDirectory.TAG_TIME_ZONE_ORIGINAL));
        assertFalse(Files.exists(source), "deleteSource: the source is deleted once its rewritten copy is verified");
    }
}

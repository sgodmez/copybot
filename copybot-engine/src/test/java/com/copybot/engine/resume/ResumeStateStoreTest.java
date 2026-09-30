package com.copybot.engine.resume;

import com.copybot.exception.CopybotException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public class ResumeStateStoreTest {

    @TempDir
    Path tempDir;

    @Test
    public void stateFileLivesNextToThePipelineWithoutItsExtension() {
        Path pipeline = tempDir.resolve("sd-to-nas.json");
        assertEquals(tempDir.resolve("sd-to-nas.state.json"), ResumeStateStore.forPipeline(pipeline).getPath());
    }

    @Test
    public void missingFileMeansNoCursor() {
        assertTrue(new ResumeStateStore(tempDir.resolve("none.state.json")).readCursor().isEmpty());
    }

    @Test
    public void writeThenReadRoundTrips() throws Exception {
        ResumeStateStore store = new ResumeStateStore(tempDir.resolve("p.state.json"));
        ItemKey cursor = new ItemKey(Instant.parse("2026-09-28T15:42:10Z"), "DSC_4821.NEF");

        store.writeCursor(cursor);

        assertEquals(cursor, store.readCursor().orElseThrow());
        String json = Files.readString(store.getPath());
        assertTrue(json.contains("\"2026-09-28T15:42:10Z\""), "the date must be human readable: " + json);
        assertTrue(json.contains("DSC_4821.NEF"));
        try (Stream<Path> files = Files.list(tempDir)) {
            assertEquals(1, files.count(), "no temporary file may be left behind");
        }
    }

    @Test
    public void writeReplacesAnExistingCursor() {
        ResumeStateStore store = new ResumeStateStore(tempDir.resolve("p.state.json"));
        store.writeCursor(new ItemKey(Instant.parse("2026-09-01T00:00:00Z"), "A"));
        ItemKey second = new ItemKey(Instant.parse("2026-09-02T00:00:00Z"), "B");

        store.writeCursor(second);

        assertEquals(second, store.readCursor().orElseThrow());
    }

    @Test
    public void fileWithoutCursorMeansNoCursor() throws Exception {
        Path file = tempDir.resolve("p.state.json");
        Files.writeString(file, "{}");
        assertTrue(new ResumeStateStore(file).readCursor().isEmpty());
    }

    @Test
    public void invalidFileIsAnExplicitErrorNotAFreshStart() throws Exception {
        Path file = tempDir.resolve("p.state.json");
        Files.writeString(file, "{ this is not json");
        assertThrows(CopybotException.class, () -> new ResumeStateStore(file).readCursor());

        Files.writeString(file, "{\"cursor\":{\"date\":\"yesterday\",\"name\":\"A\"}}");
        assertThrows(CopybotException.class, () -> new ResumeStateStore(file).readCursor());
    }
}

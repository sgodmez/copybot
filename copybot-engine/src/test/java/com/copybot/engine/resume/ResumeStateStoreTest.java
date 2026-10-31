package com.copybot.engine.resume;

import com.copybot.exception.CopybotException;
import com.copybot.resources.ResourcesEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
    public void aNullCursorMeansNoCursor() throws Exception {
        Path file = tempDir.resolve("p.state.json");
        Files.writeString(file, "{\"cursor\":null}");
        assertTrue(new ResumeStateStore(file).readCursor().isEmpty());
    }

    @Test
    public void aCursorWithoutDateOrNameIsAnExplicitError() throws Exception {
        Path file = tempDir.resolve("p.state.json");
        for (String json : new String[]{
                "{\"cursor\":{\"name\":\"A\"}}",
                "{\"cursor\":{\"date\":\"2026-09-28T15:42:10Z\"}}",
                "{\"cursor\":{\"date\":null,\"name\":\"A\"}}",
                "{\"cursor\":{\"date\":\"2026-09-28T15:42:10Z\",\"name\":null}}"}) {
            Files.writeString(file, json);
            assertThrows(CopybotException.class, () -> new ResumeStateStore(file).readCursor(), json);
        }
    }

    @Test
    public void aWriteFailureIsAnExplicitErrorAndLeavesNoTemporaryFile() throws Exception {
        Path stateFile = tempDir.resolve("p.state.json");
        // a non-empty directory where the state file goes: the move over it fails
        Files.createDirectories(stateFile.resolve("child"));
        ResumeStateStore store = new ResumeStateStore(stateFile);

        assertThrows(CopybotException.class,
                () -> store.writeCursor(new ItemKey(Instant.parse("2026-09-28T15:42:10Z"), "A")));
        try (Stream<Path> files = Files.list(tempDir)) {
            assertEquals(java.util.List.of(stateFile), files.toList(), "the temporary file is removed, whatever its name");
        }
        assertTrue(Files.isDirectory(stateFile.resolve("child")), "what was there is left as it was");
    }

    private static final ItemKey KEY_A = new ItemKey(Instant.parse("2026-09-28T15:42:10Z"), "A.NEF");
    private static final ItemKey KEY_B = new ItemKey(Instant.parse("2026-09-28T15:42:11Z"), "B.NEF");

    private static Map<ItemKey, String> twoPatterns() {
        Map<ItemKey, String> patterns = new LinkedHashMap<>();
        patterns.put(KEY_B, "${year}/b");
        patterns.put(KEY_A, "${year}/a");
        return patterns;
    }

    @Test
    public void outPatternsRoundTrip() {
        ResumeStateStore store = new ResumeStateStore(tempDir.resolve("p.state.json"));

        store.writeOutPatterns(twoPatterns());

        assertEquals(twoPatterns(), store.readOutPatterns());
        assertEquals(List.of(KEY_A, KEY_B), List.copyOf(store.readOutPatterns().keySet()), "ordered by key");
    }

    @Test
    public void writeCursorKeepsOutPatterns() {
        ResumeStateStore store = new ResumeStateStore(tempDir.resolve("p.state.json"));
        store.writeOutPatterns(twoPatterns());

        store.writeCursor(KEY_B);

        assertEquals(twoPatterns(), store.readOutPatterns());
        assertEquals(KEY_B, store.readCursor().orElseThrow());
    }

    @Test
    public void writeOutPatternsKeepsTheCursor() {
        ResumeStateStore store = new ResumeStateStore(tempDir.resolve("p.state.json"));
        store.writeCursor(KEY_B);

        store.writeOutPatterns(twoPatterns());

        assertEquals(KEY_B, store.readCursor().orElseThrow());
        assertEquals(twoPatterns(), store.readOutPatterns());
    }

    @Test
    public void anEmptyMapRemovesTheMember() throws Exception {
        ResumeStateStore store = new ResumeStateStore(tempDir.resolve("p.state.json"));
        store.writeCursor(KEY_A);
        store.writeOutPatterns(twoPatterns());

        store.writeOutPatterns(Map.of());

        assertTrue(store.readOutPatterns().isEmpty());
        assertFalse(Files.readString(store.getPath()).contains("outPatterns"));
        assertEquals(KEY_A, store.readCursor().orElseThrow());
    }

    @Test
    public void missingFileMeansNoOutPatterns() {
        assertTrue(new ResumeStateStore(tempDir.resolve("none.state.json")).readOutPatterns().isEmpty());
    }

    @Test
    public void oldStateFileHasNoOutPatterns() throws Exception {
        Path file = tempDir.resolve("p.state.json");
        Files.writeString(file, "{\"cursor\":{\"date\":\"2026-09-28T15:42:10Z\",\"name\":\"A.NEF\"}}");
        ResumeStateStore store = new ResumeStateStore(file);

        assertTrue(store.readOutPatterns().isEmpty());
        assertEquals(KEY_A, store.readCursor().orElseThrow());
    }

    @Test
    public void invalidOutPatternsFails() throws Exception {
        Path file = tempDir.resolve("p.state.json");
        for (String json : new String[]{
                "{\"outPatterns\":3}",
                "{\"outPatterns\":[3]}",
                "{\"outPatterns\":[{\"name\":\"A\",\"pattern\":\"p\"}]}",
                "{\"outPatterns\":[{\"date\":\"2026-09-28T15:42:10Z\",\"pattern\":\"p\"}]}",
                "{\"outPatterns\":[{\"date\":\"2026-09-28T15:42:10Z\",\"name\":\"A\"}]}",
                "{\"outPatterns\":[{\"date\":\"yesterday\",\"name\":\"A\",\"pattern\":\"p\"}]}"}) {
            Files.writeString(file, json);
            assertThrows(CopybotException.class, () -> new ResumeStateStore(file).readOutPatterns(), json);
        }
    }

    @Test
    public void anOutPatternWithAnInvalidSyntaxIsAnInvalidStateFile() throws Exception {
        Path file = tempDir.resolve("p.state.json");
        Files.writeString(file, "{\"outPatterns\":3}");
        String invalid = assertThrows(CopybotException.class, () -> new ResumeStateStore(file).readOutPatterns()).getMessage();
        Files.writeString(file, "{\"outPatterns\":[{\"date\":\"2026-09-28T15:42:10Z\",\"name\":\"A\",\"pattern\":\"x/{name\"}]}");

        CopybotException e = assertThrows(CopybotException.class, () -> new ResumeStateStore(file).readOutPatterns());

        assertEquals(invalid, e.getMessage(), "refused when read, never at the write (spec own-out-pattern §1)");
    }

    @Test
    public void aStateFileThatCannotBeReadIsLeftAsItWasByAWrite() throws Exception {
        Path file = tempDir.resolve("p.state.json");
        ResumeStateStore store = new ResumeStateStore(file);
        store.writeOutPatterns(twoPatterns());
        String before = Files.readString(file);
        // a read failure while the file can still be replaced: reading denied, not deleting
        Runnable restore = denyReading(file);
        try {
            assumeTrue(unreadable(file), "reading cannot be denied here");

            CopybotException e = assertThrows(CopybotException.class, () -> store.writeCursor(KEY_B));

            assertEquals(ResourcesEngine.getString("resume.state.write-error", file.toAbsolutePath()), e.getMessage());
        } finally {
            restore.run();
        }
        assertEquals(before, Files.readString(file), "the own patterns are not erased");
    }

    /** @return what gives the reading back */
    private static Runnable denyReading(Path file) throws IOException {
        AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (acl != null) {
            List<AclEntry> original = acl.getAcl();
            List<AclEntry> denied = new ArrayList<>(original);
            denied.addFirst(AclEntry.newBuilder().setType(AclEntryType.DENY)
                    .setPrincipal(file.getFileSystem().getUserPrincipalLookupService()
                            .lookupPrincipalByName(System.getProperty("user.name")))
                    .setPermissions(AclEntryPermission.READ_DATA).build());
            acl.setAcl(denied);
            return () -> {
                try {
                    acl.setAcl(original);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            };
        }
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(file);
        Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_WRITE));
        return () -> {
            try {
                Files.setPosixFilePermissions(file, original);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    private static boolean unreadable(Path file) {
        try {
            Files.readString(file);
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    @Test
    public void invalidFileIsAnExplicitErrorNotAFreshStart() throws Exception {
        Path file = tempDir.resolve("p.state.json");
        Files.writeString(file, "{ this is not json");
        assertThrows(CopybotException.class, () -> new ResumeStateStore(file).readCursor());

        Files.writeString(file, "{\"cursor\":{\"date\":\"yesterday\",\"name\":\"A\"}}");
        assertThrows(CopybotException.class, () -> new ResumeStateStore(file).readCursor());
    }

    // ---- zone corrections (spec timezone-correction §3, kept in the state) ----

    private static Map<ItemKey, ZoneCorrection> twoCorrections() {
        Map<ItemKey, ZoneCorrection> corrections = new LinkedHashMap<>();
        corrections.put(KEY_B, new ZoneCorrection(ZoneId.of("America/Mexico_City"), null));
        corrections.put(KEY_A, new ZoneCorrection(ZoneId.of("Europe/Paris"), ZoneOffset.ofHours(1)));
        return corrections;
    }

    @Test
    public void zoneCorrectionsRoundTrip() throws Exception {
        ResumeStateStore store = new ResumeStateStore(tempDir.resolve("p.state.json"));

        store.writeZoneCorrections(twoCorrections());

        assertEquals(twoCorrections(), store.readZoneCorrections());
        assertEquals(List.of(KEY_A, KEY_B), List.copyOf(store.readZoneCorrections().keySet()), "ordered by key");
        String json = Files.readString(store.getPath());
        assertTrue(json.contains("\"Europe/Paris\"") && json.contains("\"+01:00\""), "human readable: " + json);
        assertInstanceOf(ZoneOffset.class, store.readZoneCorrections().get(KEY_A).clock(), "a fixed offset stays one");
    }

    @Test
    public void zoneCorrectionsKeepTheCursorAndTheOutPatterns() {
        ResumeStateStore store = new ResumeStateStore(tempDir.resolve("p.state.json"));
        store.writeCursor(KEY_B);
        store.writeOutPatterns(twoPatterns());

        store.writeZoneCorrections(twoCorrections());
        store.writeCursor(KEY_A);
        store.writeOutPatterns(Map.of(KEY_A, "x/{name}"));

        assertEquals(KEY_A, store.readCursor().orElseThrow());
        assertEquals(Map.of(KEY_A, "x/{name}"), store.readOutPatterns());
        assertEquals(twoCorrections(), store.readZoneCorrections());
    }

    @Test
    public void anEmptyMapRemovesTheZoneCorrections() throws Exception {
        ResumeStateStore store = new ResumeStateStore(tempDir.resolve("p.state.json"));
        store.writeCursor(KEY_A);
        store.writeZoneCorrections(twoCorrections());

        store.writeZoneCorrections(Map.of());

        assertTrue(store.readZoneCorrections().isEmpty());
        assertFalse(Files.readString(store.getPath()).contains("zoneCorrections"));
        assertEquals(KEY_A, store.readCursor().orElseThrow());
    }

    @Test
    public void oldStateFileHasNoZoneCorrections() throws Exception {
        assertTrue(new ResumeStateStore(tempDir.resolve("none.state.json")).readZoneCorrections().isEmpty());
        Path file = tempDir.resolve("p.state.json");
        Files.writeString(file, "{\"cursor\":{\"date\":\"2026-09-28T15:42:10Z\",\"name\":\"A.NEF\"},"
                + "\"outPatterns\":[{\"date\":\"2026-09-28T15:42:10Z\",\"name\":\"A.NEF\",\"pattern\":\"p/{name}\"}]}");

        assertTrue(new ResumeStateStore(file).readZoneCorrections().isEmpty());
    }

    @Test
    public void invalidZoneCorrectionsAreAnInvalidStateFile() throws Exception {
        Path file = tempDir.resolve("p.state.json");
        Files.writeString(file, "{\"outPatterns\":3}");
        String invalid = assertThrows(CopybotException.class, () -> new ResumeStateStore(file).readOutPatterns()).getMessage();
        for (String json : new String[]{
                "{\"zoneCorrections\":3}",
                "{\"zoneCorrections\":[3]}",
                "{\"zoneCorrections\":[{\"name\":\"A\",\"zone\":\"Europe/Paris\"}]}",
                "{\"zoneCorrections\":[{\"date\":\"2026-09-28T15:42:10Z\",\"zone\":\"Europe/Paris\"}]}",
                "{\"zoneCorrections\":[{\"date\":\"2026-09-28T15:42:10Z\",\"name\":\"A\"}]}",
                "{\"zoneCorrections\":[{\"date\":\"yesterday\",\"name\":\"A\",\"zone\":\"Europe/Paris\"}]}",
                "{\"zoneCorrections\":[{\"date\":\"2026-09-28T15:42:10Z\",\"name\":\"A\",\"zone\":\"Europe/Nowhere\"}]}",
                "{\"zoneCorrections\":[{\"date\":\"2026-09-28T15:42:10Z\",\"name\":\"A\",\"zone\":\"Europe/Paris\",\"clock\":\"+25:00\"}]}"}) {
            Files.writeString(file, json);

            CopybotException e = assertThrows(CopybotException.class, () -> new ResumeStateStore(file).readZoneCorrections(), json);

            assertEquals(invalid, e.getMessage(), "like an own pattern edited by hand: " + json);
        }
    }
}

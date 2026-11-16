package com.copybot.plugin.embedded.actions;

import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.Companion;
import com.copybot.plugin.api.action.CompanionLink;
import com.copybot.plugin.api.action.CompanionRole;
import com.copybot.plugin.api.action.TargetCheck;
import com.copybot.plugin.api.action.TargetCheck.Kind;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WriteContext;
import com.copybot.plugin.api.action.WriteResult;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** file.write writes a main item with its COPY companions as one group (spec grouping-archives). */
public class FileWriteGroupTest {

    @TempDir
    Path tempDir;

    private FileWriteAction action(String extra) {
        FileWriteAction action = new FileWriteAction();
        String pattern = tempDir.resolve("nas").toString().replace("\\", "/") + "/{name}";
        action.loadConfig(JsonParser.parseString("{\"outPattern\":\"" + pattern + "\"" + extra + "}"));
        return action;
    }

    private WorkItem source(String name, String content) throws IOException {
        Path file = Files.writeString(Files.createDirectories(tempDir.resolve("card")).resolve(name), content);
        WorkItem item = new WorkItem(file);
        item.getMetadatas().setSize((long) content.length());
        item.getMetadatas().display().put("name", name);
        return item;
    }

    private Companion copy(String name, String content, String suffix) throws IOException {
        return new Companion(source(name, content), CompanionRole.COPY, CompanionLink.BASE, suffix);
    }

    private Path nas(String name, String content) throws IOException {
        Path file = tempDir.resolve("nas").resolve(name);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }

    private Path nasPath(String name) {
        return tempDir.resolve("nas").resolve(name);
    }

    private List<String> nasFiles() throws IOException {
        if (!Files.exists(tempDir.resolve("nas"))) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(tempDir.resolve("nas"))) {
            return files.filter(Files::isRegularFile)
                    .map(f -> tempDir.resolve("nas").relativize(f).toString().replace('\\', '/')).sorted().toList();
        }
    }

    /** IMG_1.CR3 with IMG_1.xmp. */
    private WorkItem rawWithXmp(String raw, String xmp) throws IOException {
        return source("IMG_1.CR3", raw).withCompanions(List.of(copy("IMG_1.xmp", xmp, ".xmp")));
    }

    @Test
    public void fileWriteWritesCompanions() {
        assertTrue(new FileWriteAction().writesCompanions());
    }

    @Test
    public void aFreeGroupIsWrittenWhole() throws IOException {
        WorkItem item = source("IMG_1.CR3", "raw").withCompanions(List.of(
                copy("IMG_1.JPG", "jpeg", ".JPG"), copy("IMG_1.xmp", "xmp", ".xmp")));

        WriteResult result = action("").write(item, WriteContext.newRun());

        assertEquals(WriteResult.written(nasPath("IMG_1.CR3")), result);
        assertEquals(List.of("IMG_1.CR3", "IMG_1.JPG", "IMG_1.xmp"), nasFiles());
        assertEquals("jpeg", Files.readString(nasPath("IMG_1.JPG")));
        assertEquals("xmp", Files.readString(nasPath("IMG_1.xmp")));
    }

    @Test
    public void everyFileWrittenIsTold() throws IOException {
        List<String> told = new CopyOnWriteArrayList<>();
        WriteContext context = new WriteContext("run1", null, null,
                (target, sha256, size) -> told.add(tempDir.relativize(target) + " " + sha256.length() + " " + size));

        action("").write(rawWithXmp("raw", "xmp!"), context);
        action("").write(source("IMG_2.JPG", "jpeg"), context);

        String nas = "nas" + java.io.File.separator;
        assertEquals(List.of(nas + "IMG_1.CR3 64 3", nas + "IMG_1.xmp 64 4", nas + "IMG_2.JPG 64 4"), told);
    }

    @Test
    public void aCompanionWrittenNextToASkippedMainIsTold() throws IOException {
        nas("IMG_1.CR3", "raw");
        List<Path> told = new CopyOnWriteArrayList<>();

        action("").write(rawWithXmp("raw", "xmp"), new WriteContext("run1", null, null,
                (target, sha256, size) -> told.add(target)));

        assertEquals(List.of(nasPath("IMG_1.xmp")), told);
    }

    @Test
    public void aMissingCompanionIsWrittenWhenTheMainIsSkippedAsIdentical() throws IOException {
        nas("IMG_1.CR3", "raw");

        WriteResult result = action("").write(rawWithXmp("raw", "xmp"), WriteContext.newRun());

        assertTrue(result.isSkipped(), result.toString());
        assertEquals("xmp", Files.readString(nasPath("IMG_1.xmp")));
    }

    @Test
    public void aDifferentCompanionRenamesTheWholeGroup() throws IOException {
        nas("IMG_1.CR3", "raw");
        nas("IMG_1.xmp", "edited elsewhere");

        WriteResult result = action(",\"onConflict\":{\"ifDifferent\":\"rename\"}")
                .write(rawWithXmp("raw", "xmp"), WriteContext.newRun());

        assertEquals(WriteResult.written(nasPath("IMG_1 (1).CR3")), result);
        assertEquals(List.of("IMG_1 (1).CR3", "IMG_1 (1).xmp", "IMG_1.CR3", "IMG_1.xmp"), nasFiles());
        assertEquals("xmp", Files.readString(nasPath("IMG_1 (1).xmp")));
        assertEquals("edited elsewhere", Files.readString(nasPath("IMG_1.xmp")));
    }

    @Test
    public void aDifferentCompanionSkipsTheWholeGroup() throws IOException {
        nas("IMG_1.CR3", "raw");
        nas("IMG_1.xmp", "edited elsewhere");

        WriteResult result = action(",\"onConflict\":{\"ifDifferent\":\"skip\"}")
                .write(rawWithXmp("raw", "xmp"), WriteContext.newRun());

        assertTrue(result.isSkipped());
        assertTrue(result.reason().contains("IMG_1.xmp"), "names the member in conflict: " + result.reason());
        assertEquals(List.of("IMG_1.CR3", "IMG_1.xmp"), nasFiles());
        assertEquals("edited elsewhere", Files.readString(nasPath("IMG_1.xmp")));
    }

    @Test
    public void aCompanionCandidateTakenMovesTheWholeGroupFurther() throws IOException {
        nas("IMG_1.CR3", "raw");
        nas("IMG_1.xmp", "edited elsewhere");
        nas("IMG_1 (1).xmp", "another");

        WriteResult result = action("").write(rawWithXmp("raw", "xmp"), WriteContext.newRun());

        assertEquals(WriteResult.written(nasPath("IMG_1 (2).CR3")), result);
        assertEquals("xmp", Files.readString(nasPath("IMG_1 (2).xmp")));
        assertFalse(Files.exists(nasPath("IMG_1 (1).CR3")));
    }

    @Test
    public void aFolderCompanionFollowsTheNewNameAndItsDirectoriesAreReported() throws IOException {
        nas("page.html", "older page");
        WorkItem item = source("page.html", "<html>").withCompanions(List.of(
                copy("b.png", "png", "_files/img/b.png")));
        List<Path> created = new CopyOnWriteArrayList<>();

        WriteResult result = action("").write(item, WriteContext.newRun(created::add));

        assertEquals(WriteResult.written(nasPath("page (1).html")), result);
        assertEquals("png", Files.readString(nasPath("page (1)_files/img/b.png")));
        Path files = nasPath("page (1)_files").toAbsolutePath();
        assertTrue(created.containsAll(List.of(files, files.resolve("img"))), created.toString());
    }

    @Test
    public void aTransformedMainKeepsItsCompanionsUnderItsNewName() throws IOException {
        WorkItem main = source("C0001.MOV", "video");
        main.setOutPattern(tempDir.resolve("nas").toString().replace("\\", "/") + "/clip.mp4");
        WorkItem item = main.withCompanions(List.of(copy("C0001M01.XML", "xml", "M01.XML")));

        action("").write(item, WriteContext.newRun());

        assertEquals(List.of("clip.mp4", "clipM01.XML"), nasFiles());
    }

    @Test
    public void aMissingMainIsWrittenNextToIdenticalCompanions() throws IOException {
        nas("IMG_1.xmp", "xmp");

        WriteResult result = action("").write(rawWithXmp("raw", "xmp"), WriteContext.newRun());

        assertEquals(WriteResult.written(nasPath("IMG_1.CR3")), result);
        assertEquals(List.of("IMG_1.CR3", "IMG_1.xmp"), nasFiles());
    }

    private static final String MOVE = ",\"deleteSource\":true,\"onConflict\":{\"compare\":\"fullHash\"}";

    @Test
    public void aGroupMarkedSourceKeptDeletesNoSource() throws IOException {
        WorkItem item = rawWithXmp("raw", "xmp").withSourceKept();
        WorkItem xmp = item.getCompanions().getFirst().item();

        action(MOVE).write(item, WriteContext.newRun());

        assertEquals(List.of("IMG_1.CR3", "IMG_1.xmp"), nasFiles());
        assertTrue(Files.exists(item.getLocalLocation()), "the main source stays");
        assertTrue(Files.exists(xmp.getLocalLocation()), "the companion source stays");
    }

    @Test
    public void aFailedCompanionDeletesNoSourceAndTheNextRunCatchesUp() throws IOException {
        WorkItem item = rawWithXmp("raw", "xmp");
        WorkItem xmp = item.getCompanions().getFirst().item();
        FileWriteAction failing = action(MOVE);
        failing.writer().setBeforeVerify(file -> {
            if (file.getFileName().toString().contains("xmp")) {
                try {
                    Files.writeString(file, "garbage"); // the verification fails
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        });

        assertThrows(CopybotException.class, () -> failing.write(item, WriteContext.newRun()));

        assertTrue(Files.exists(item.getLocalLocation()), "no source deleted while a member failed");
        assertTrue(Files.exists(xmp.getLocalLocation()));
        assertFalse(item.isDeleted());
        assertEquals("raw", Files.readString(nasPath("IMG_1.CR3")), "the main file written stays");
        assertFalse(Files.exists(nasPath("IMG_1.xmp")));

        WorkItem again = rawWithXmp("raw", "xmp");
        WriteResult result = action(MOVE).write(again, WriteContext.newRun());

        assertTrue(result.isSkipped(), "the main file is identical");
        assertEquals("xmp", Files.readString(nasPath("IMG_1.xmp")), "the missing companion is written");
        assertFalse(Files.exists(again.getLocalLocation()), "every source deleted");
        assertFalse(Files.exists(again.getCompanions().getFirst().item().getLocalLocation()));
    }

    @Test
    public void twoMembersWithTheSameTargetFailTheGroupBeforeAnyWrite() throws IOException {
        // IMG_1.xmp and IMG_1.XMP of a source that tells case: one file on a destination that does not
        WorkItem item = source("IMG_1.CR3", "raw").withCompanions(List.of(
                copy("first.xmp", "xmp 1", ".xmp"), copy("second.xmp", "xmp 2", ".XMP")));

        CopybotException e = assertThrows(CopybotException.class,
                () -> action(MOVE).write(item, WriteContext.newRun()));

        assertTrue(e.getMessage().contains("first.xmp") && e.getMessage().contains("second.xmp"), e.getMessage());
        assertEquals(List.of(), nasFiles(), "nothing written");
        assertTrue(Files.exists(item.getLocalLocation()), "no source deleted");
        assertTrue(Files.exists(item.getCompanions().get(0).item().getLocalLocation()));
        assertTrue(Files.exists(item.getCompanions().get(1).item().getLocalLocation()));
    }

    @Test
    public void aCompanionWithTheMainTargetFailsTheGroup() throws IOException {
        WorkItem item = source("a.mp4", "video").withCompanions(List.of(
                new Companion(source("other.mp4", "other video"), CompanionRole.COPY, CompanionLink.NAME, "")));

        assertThrows(CopybotException.class, () -> action(MOVE).write(item, WriteContext.newRun()));

        assertEquals(List.of(), nasFiles(), "nothing written");
        assertTrue(Files.exists(item.getLocalLocation()));
    }

    private static final String MOVE_BY_SIZE =",\"deleteSource\":true,\"onConflict\":{\"compare\":\"size\"}";

    @Test
    public void aMainSkippedAsIdenticalWithoutFullHashKeepsEverySourceOfTheGroup() throws IOException {
        nas("IMG_1.CR3", "raw");
        WorkItem item = rawWithXmp("raw", "xmp");

        WriteResult result = action(MOVE_BY_SIZE).write(item, WriteContext.newRun());

        assertTrue(result.isSkipped());
        assertEquals("xmp", Files.readString(nasPath("IMG_1.xmp")), "the missing companion is written");
        assertTrue(Files.exists(item.getLocalLocation()), "the main source is not proven at the destination");
        assertTrue(Files.exists(item.getCompanions().getFirst().item().getLocalLocation()),
                "the group's sources stay together");
    }

    @Test
    public void aMainWrittenNextToCompanionsNotProvenKeepsEverySourceOfTheGroup() throws IOException {
        nas("IMG_1.xmp", "xmp");
        WorkItem item = rawWithXmp("raw", "xmp");

        WriteResult result = action(MOVE_BY_SIZE).write(item, WriteContext.newRun());

        assertEquals(WriteResult.written(nasPath("IMG_1.CR3")), result);
        assertTrue(Files.exists(item.getLocalLocation()), "the group's sources stay together");
        assertTrue(Files.exists(item.getCompanions().getFirst().item().getLocalLocation()),
                "the companion is not proven at the destination");
    }

    private WorkItem entry(String name, String content, AtomicInteger deletions) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return WorkItem.remote(name, "archive.zip", "archive.zip!/" + name, bytes.length, Instant.now(),
                () -> new ByteArrayInputStream(bytes), deletions::incrementAndGet);
    }

    @Test
    public void archiveEntriesAreDeletedThroughTheirDeleterOnceWritten() {
        AtomicInteger mainDeletions = new AtomicInteger();
        AtomicInteger xmpDeletions = new AtomicInteger();
        WorkItem item = entry("IMG_1.CR3", "raw", mainDeletions).withCompanions(List.of(
                new Companion(entry("IMG_1.xmp", "xmp", xmpDeletions), CompanionRole.COPY, CompanionLink.BASE, ".xmp")));

        action(MOVE).write(item, WriteContext.newRun());

        assertEquals(1, mainDeletions.get());
        assertEquals(1, xmpDeletions.get());
        assertTrue(item.isDeleted());
        assertTrue(item.getCompanions().getFirst().item().isDeleted());
    }

    @Test
    public void anArchiveEntryAloneIsDeletedThroughItsDeleter() {
        AtomicInteger deletions = new AtomicInteger();
        WorkItem item = entry("IMG_1.CR3", "raw", deletions);

        action(MOVE).write(item, WriteContext.newRun());

        assertEquals(1, deletions.get());
        assertTrue(item.isDeleted());
    }

    @Test
    public void aReadCompanionIsNeverWrittenNorDeleted() throws IOException {
        WorkItem thm = source("IMG_1.THM", "thumb");
        WorkItem item = source("IMG_1.CR3", "raw").withCompanions(List.of(
                new Companion(thm, CompanionRole.READ, CompanionLink.BASE, ".THM"), copy("IMG_1.xmp", "xmp", ".xmp")));

        action(MOVE).write(item, WriteContext.newRun());

        assertEquals(List.of("IMG_1.CR3", "IMG_1.xmp"), nasFiles());
        assertTrue(Files.exists(thm.getLocalLocation()));
        assertFalse(thm.isDeleted());
    }

    @Test
    public void theCheckTellsTheMostSevereMemberAndNamesIt() throws IOException {
        nas("IMG_1.CR3", "raw");
        nas("IMG_1.xmp", "edited elsewhere");
        WorkItem item = rawWithXmp("raw", "xmp");

        TargetCheck full = action("").checkTarget(item, true);
        TargetCheck quick = action("").checkTarget(item, false);

        assertEquals(Kind.DIFFERENT, full.kind());
        assertTrue(full.message().contains("IMG_1.xmp"), full.message());
        assertTrue(full.message().contains("IMG_1 (1).CR3"), "the group outcome: " + full.message());
        assertFalse(full.skipped());
        assertEquals(Kind.DIFFERENT_SIZE, quick.kind());
        assertTrue(quick.message().contains("IMG_1.xmp"), quick.message());
        assertEquals(List.of("IMG_1.CR3", "IMG_1.xmp"), nasFiles(), "nothing written");
    }
}

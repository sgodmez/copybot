package com.copybot.plugin.embedded.actions;

import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.WorkItem;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** file.read: recursive, include / exclude, hidden files (spec safe-write §7). */
public class FileReadActionTest {

    @TempDir
    Path tempDir;

    Path card;

    @BeforeEach
    public void createCard() throws IOException {
        card = Files.createDirectories(tempDir.resolve("card"));
        file("a.jpg");
        file("b.NEF");
        file("notes.tmp");
        file("sub/c.JPG");
        file("sub/deep/d.nef");
        file("sub/e.tmp");
    }

    private Path file(String relative) throws IOException {
        Path file = card.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, relative);
    }

    private FileReadAction action(String extra) {
        FileReadAction action = new FileReadAction();
        action.loadConfig(JsonParser.parseString(
                "{\"path\":\"" + card.toString().replace("\\", "\\\\") + "\"" + extra + "}"));
        return action;
    }

    /** Lists the card with this extra actionConfig; relative paths with '/', sorted. */
    private List<String> list(String extra) {
        List<String> listed = new ArrayList<>();
        action(extra).listFiles((WorkItem item) -> listed.add(card.relativize(item.getLocalLocation()).toString().replace('\\', '/')));
        return listed.stream().sorted().toList();
    }

    @Test
    public void thePathAloneListsTheWholeTree() {
        assertEquals(List.of("a.jpg", "b.NEF", "notes.tmp", "sub/c.JPG", "sub/deep/d.nef", "sub/e.tmp"), list(""));
    }

    @Test
    public void recursiveFalseListsTheFirstLevelOnly() {
        assertEquals(List.of("a.jpg", "b.NEF", "notes.tmp"), list(",\"recursive\":false"));
        assertEquals(6, list(",\"recursive\":true").size());
    }

    @Test
    public void includeIsCaseInsensitiveAndAlsoMatchesTheFirstLevel() {
        assertEquals(List.of("a.jpg", "b.NEF", "sub/c.JPG", "sub/deep/d.nef"),
                list(",\"include\":[\"**/*.NEF\",\"**/*.jpg\"]"));
        assertEquals(List.of("sub/c.JPG"), list(",\"include\":[\"sub/*.jpg\"]"));
    }

    @Test
    public void excludeWinsOverInclude() {
        assertEquals(List.of("a.jpg", "b.NEF", "sub/c.JPG", "sub/deep/d.nef"),
                list(",\"include\":[\"**/*\"],\"exclude\":[\"**/*.TMP\"]"));
        assertEquals(List.of("a.jpg", "b.NEF", "notes.tmp", "sub/c.JPG", "sub/e.tmp"),
                list(",\"exclude\":[\"sub/deep/**\"]"));
    }

    @Test
    public void hiddenFilesAndFoldersAreSkippedByDefault() throws IOException {
        file(".hidden.jpg");
        file("._a.jpg");
        file(".thumbnails/t.jpg");
        file("System Volume Information/IndexerVolumeGuid");
        file("$RECYCLE.BIN/r.jpg");

        assertEquals(6, list("").size(), "only the visible files");
        assertEquals(11, list(",\"includeHidden\":true").size());
    }

    @Test
    public void theWindowsHiddenAndSystemAttributesHideAFile() throws IOException {
        assumeTrue(Files.getFileStore(card).supportsFileAttributeView(DosFileAttributeView.class), "a DOS file system");
        Files.setAttribute(file("hidden.jpg"), "dos:hidden", true);
        Files.setAttribute(file("system.jpg"), "dos:system", true);

        assertFalse(list("").contains("hidden.jpg"));
        assertFalse(list("").contains("system.jpg"));
        assertTrue(list(",\"includeHidden\":true").containsAll(List.of("hidden.jpg", "system.jpg")));
    }

    @Test
    public void aWindowsHiddenFolderHidesItsWholeSubtree() throws IOException {
        assumeTrue(Files.getFileStore(card).supportsFileAttributeView(DosFileAttributeView.class), "a DOS file system");
        Files.setAttribute(card.resolve("sub/deep"), "dos:hidden", true);

        assertFalse(list("").contains("sub/deep/d.nef"));
        assertTrue(list("").contains("sub/c.JPG"));
        assertTrue(list(",\"includeHidden\":true").contains("sub/deep/d.nef"));
    }

    // ---- a folder whose whole content is excluded is not walked ----

    @Test
    public void aFolderExcludedWithSlashStarStarIsNotWalkedNorFailsWhenUnreadable() throws IOException {
        SimpleFileVisitor<Path> visitor = action(",\"exclude\":[\"SUB/deep/**\",\"**/cache/**\"]").visitor(card, false, item -> { });
        Path deep = card.resolve("sub/deep");
        Path cache = Files.createDirectories(card.resolve("sub/Cache"));
        IOException denied = new AccessDeniedException("denied");

        assertEquals(FileVisitResult.SKIP_SUBTREE, visitor.preVisitDirectory(deep, Files.readAttributes(deep, BasicFileAttributes.class)));
        assertEquals(FileVisitResult.SKIP_SUBTREE, visitor.preVisitDirectory(cache, Files.readAttributes(cache, BasicFileAttributes.class)));
        assertEquals(FileVisitResult.CONTINUE, visitor.visitFileFailed(deep, denied));
        Path sub = card.resolve("sub");
        assertEquals(FileVisitResult.CONTINUE, visitor.preVisitDirectory(sub, Files.readAttributes(sub, BasicFileAttributes.class)));
        assertSame(denied, assertThrows(IOException.class, () -> visitor.visitFileFailed(sub, denied)));
    }

    @Test
    public void excludingAFolderNameAloneStillListsItsContent() {
        // "sub" matches the folder, not the files under it: the folder is walked as before
        assertEquals(List.of("a.jpg", "b.NEF", "notes.tmp", "sub/c.JPG", "sub/deep/d.nef", "sub/e.tmp"),
                list(",\"exclude\":[\"sub\",\"sub/deep/*.tmp\"]"));
        assertEquals(List.of("a.jpg", "b.NEF", "notes.tmp", "sub/c.JPG", "sub/e.tmp"), list(",\"exclude\":[\"**/deep/**\"]"));
    }

    @Test
    public void aValidExcludeWhosePartBeforeSlashStarStarIsNotAGlobIsAccepted() {
        // "sub\/**" (an escaped separator) is a valid glob, its part before "/**" ("sub\") is not: the folder
        // shortcut is not taken for it, the glob is not refused
        assertDoesNotThrow(() -> list(",\"exclude\":[\"sub\\\\/**\"]"));
    }

    @Test
    public void anInvalidGlobIsAConfigurationErrorQuotingIt() {
        CopybotException e = assertThrows(CopybotException.class, () -> list(",\"include\":[\"[a\"]"));
        assertTrue(e.getMessage().contains("[a"), e.getMessage());
    }

    // walkFileTree opens a folder before preVisitDirectory: an unreadable hidden folder fails in visitFileFailed.

    @Test
    public void anUnreadableHiddenEntryIsSkippedWhenHiddenFilesAreExcluded() {
        SimpleFileVisitor<Path> visitor = action("").visitor(card, false, item -> { });
        IOException denied = new AccessDeniedException("denied");

        for (String name : List.of(".private", "._a.jpg", "System Volume Information", "$RECYCLE.BIN", "system volume information")) {
            assertDoesNotThrow(() -> assertEquals(FileVisitResult.CONTINUE, visitor.visitFileFailed(card.resolve(name), denied)), name);
        }
    }

    @Test
    public void anUnreadableVisibleEntryStillFails() {
        SimpleFileVisitor<Path> visitor = action("").visitor(card, false, item -> { });
        IOException denied = new AccessDeniedException("denied");

        assertSame(denied, assertThrows(IOException.class, () -> visitor.visitFileFailed(card.resolve("photos"), denied)));
        // the listed directory itself is never treated as hidden
        assertSame(denied, assertThrows(IOException.class, () -> visitor.visitFileFailed(card, denied)));
    }

    @Test
    public void anUnreadableHiddenEntryFailsWhenHiddenFilesAreIncluded() {
        SimpleFileVisitor<Path> visitor = action("").visitor(card, true, item -> { });
        IOException denied = new AccessDeniedException("denied");

        assertSame(denied, assertThrows(IOException.class, () -> visitor.visitFileFailed(card.resolve(".private"), denied)));
    }

    @Test
    public void aNullConfigIsAConfigurationError() {
        FileReadAction action = new FileReadAction();

        assertThrows(CopybotException.class, () -> action.loadConfig(JsonParser.parseString("null")));
    }

    @Test
    public void aMissingPathIsAConfigurationError() {
        FileReadAction action = new FileReadAction();

        assertThrows(CopybotException.class, () -> action.loadConfig(JsonParser.parseString("{\"recursive\":true}")));
        assertThrows(CopybotException.class, () -> action.loadConfig(JsonParser.parseString("{\"path\":\" \"}")));
    }
}

package com.copybot.plugin.embedded.actions;

import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.resources.ResourcesEngine;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** What happens when the target already exists (spec safe-write §2). */
public class ConflictResolverTest {

    @TempDir
    Path tempDir;

    private ConflictResolver resolver(String onConflict) {
        String json = "{\"outPattern\":\"x\"" + (onConflict == null ? "" : ",\"onConflict\":" + onConflict) + "}";
        return new ConflictResolver(FileWriteSettings.of(new Gson().fromJson(json, FileWriteConfig.class)));
    }

    private WorkItem item(String content) throws IOException {
        Path file = Files.writeString(Files.createDirectories(tempDir.resolve("card")).resolve("photo.jpg"), content);
        WorkItem item = new WorkItem(file);
        item.getMetadatas().setSize(content.length());
        return item;
    }

    private Path existing(String name, String content) throws IOException {
        return Files.writeString(Files.createDirectories(tempDir.resolve("nas")).resolve(name), content);
    }

    private Path target() {
        return tempDir.resolve("nas").resolve("photo.jpg");
    }

    @Test
    public void aFreeTargetIsWritten() throws IOException {
        ConflictResolver.Decision decision = resolver(null).resolve(item("new"), target());

        assertEquals(target(), decision.target());
        assertFalse(decision.isSkip());
        assertFalse(decision.replaceExisting());
    }

    @Test
    public void anIdenticalTargetIsSkippedWithItsReasonByDefault() throws IOException {
        existing("photo.jpg", "same");

        ConflictResolver.Decision decision = resolver(null).resolve(item("same"), target());

        assertTrue(decision.isSkip());
        assertTrue(decision.identical());
        assertEquals(target(), decision.target());
        assertTrue(decision.skipReason().contains("photo.jpg"), decision.skipReason());
    }

    @Test
    public void aDifferentTargetIsRenamedByDefault() throws IOException {
        existing("photo.jpg", "other content");

        ConflictResolver.Decision decision = resolver(null).resolve(item("new"), target());

        assertEquals(target().resolveSibling("photo (1).jpg"), decision.target());
        assertFalse(decision.isSkip());
        assertFalse(decision.replaceExisting());
    }

    @Test
    public void renameGoesOnWhileTheNumberedCandidatesAreDifferent() throws IOException {
        existing("photo.jpg", "other content");
        existing("photo (1).jpg", "yet another content");

        assertEquals(target().resolveSibling("photo (2).jpg"), resolver(null).resolve(item("new"), target()).target());
    }

    @Test
    public void renameFindsAnIdenticalCopyAlreadyNumbered() throws IOException {
        existing("photo.jpg", "other content");
        existing("photo (1).jpg", "new");

        ConflictResolver.Decision decision = resolver(null).resolve(item("new"), target());

        assertTrue(decision.isSkip(), "no photo (2).jpg duplicate");
        assertTrue(decision.identical());
        assertEquals(target().resolveSibling("photo (1).jpg"), decision.target());
    }

    @Test
    public void overwriteReplacesTheTarget() throws IOException {
        existing("photo.jpg", "other content");

        ConflictResolver.Decision decision = resolver("{\"ifDifferent\":\"overwrite\"}").resolve(item("new"), target());

        assertEquals(target(), decision.target());
        assertTrue(decision.replaceExisting());
        assertFalse(decision.isSkip());
    }

    @Test
    public void errorFailsTheItemAndQuotesTheTarget() throws IOException {
        existing("photo.jpg", "other content");

        CopybotException e = assertThrows(CopybotException.class,
                () -> resolver("{\"ifDifferent\":\"error\"}").resolve(item("new"), target()));
        assertTrue(e.getMessage().contains("photo.jpg"), e.getMessage());
        assertThrows(CopybotException.class,
                () -> resolver("{\"ifIdentical\":\"error\"}").resolve(item("other content"), target()));
    }

    @Test
    public void skipOnADifferentTargetIsNotAnIdenticalSkip() throws IOException {
        existing("photo.jpg", "other content");

        ConflictResolver.Decision decision = resolver("{\"ifDifferent\":\"skip\"}").resolve(item("new"), target());

        assertTrue(decision.isSkip());
        assertFalse(decision.identical());
        assertTrue(decision.skipReason().contains("photo.jpg"), decision.skipReason());
    }

    @Test
    public void legacyOverwriteFalseSkipsAnIdenticalTargetAndFailsOnADifferentOne() throws IOException {
        existing("photo.jpg", "same");
        ConflictResolver legacy = new ConflictResolver(FileWriteSettings.of(
                new Gson().fromJson("{\"outPattern\":\"x\",\"overwrite\":false}", FileWriteConfig.class)));

        assertTrue(legacy.resolve(item("same"), target()).isSkip());
        assertThrows(CopybotException.class, () -> legacy.resolve(item("different"), target()));
    }

    // ---- sort in place: the target resolves to the source itself (final review, critical) ----

    /** An item whose local file is the existing nas file itself (file.read on the destination tree). */
    private WorkItem itemAt(Path file) throws IOException {
        WorkItem item = new WorkItem(file);
        item.getMetadatas().setSize(Files.size(file));
        return item;
    }

    @Test
    public void aTargetThatIsTheSourceItselfIsSkippedAsSameFileWhateverThePolicy() throws IOException {
        Path source = existing("photo.jpg", "only copy");
        for (String onConflict : new String[]{null, "{\"ifIdentical\":\"overwrite\"}", "{\"ifIdentical\":\"error\"}",
                "{\"compare\":\"fullHash\",\"ifIdentical\":\"rename\"}"}) {
            ConflictResolver.Decision decision = resolver(onConflict).resolve(itemAt(source), target());

            assertTrue(decision.isSkip(), String.valueOf(onConflict));
            assertTrue(decision.sameFile(), String.valueOf(onConflict));
            assertFalse(decision.replaceExisting(), String.valueOf(onConflict));
            assertEquals(target(), decision.target());
            assertTrue(decision.skipReason().contains("photo.jpg"), decision.skipReason());
        }
        assertEquals("only copy", Files.readString(source));
    }

    @Test
    public void aRenameCandidateThatIsTheSourceItselfIsSkippedAsSameFile() throws IOException {
        existing("photo.jpg", "other content");
        Path source = existing("photo (1).jpg", "renamed by a previous run");

        ConflictResolver.Decision decision = resolver(null).resolve(itemAt(source), target());

        assertTrue(decision.isSkip(), "no photo (2).jpg copy of itself");
        assertTrue(decision.sameFile());
        assertEquals(source, decision.target());
    }

    @Test
    public void anOrdinaryIdenticalSkipIsNotASameFileSkip() throws IOException {
        existing("photo.jpg", "same");

        assertFalse(resolver(null).resolve(item("same"), target()).sameFile());
    }

    // ---- skip reasons, policies on an identical target, mixed rename chains ----

    @Test
    public void eachSkipHasItsOwnReason() throws IOException {
        Path existing = existing("photo.jpg", "same");

        assertEquals(ResourcesEngine.getString("write.skip.identical", existing),
                resolver(null).resolve(item("same"), target()).skipReason());
        assertEquals(ResourcesEngine.getString("write.skip.exists", existing),
                resolver("{\"ifDifferent\":\"skip\"}").resolve(item("diff"), target()).skipReason());
        assertEquals(ResourcesEngine.getString("write.skip.same-file", existing),
                resolver(null).resolve(itemAt(existing), target()).skipReason());
    }

    @Test
    public void ifIdenticalOverwriteReplacesAnIdenticalTarget() throws IOException {
        existing("photo.jpg", "same");

        ConflictResolver.Decision decision = resolver("{\"ifIdentical\":\"overwrite\"}").resolve(item("same"), target());

        assertEquals(target(), decision.target());
        assertTrue(decision.replaceExisting());
        assertTrue(decision.identical());
        assertFalse(decision.isSkip());
    }

    @Test
    public void ifIdenticalRenameWritesANumberedCopy() throws IOException {
        existing("photo.jpg", "same");

        ConflictResolver.Decision decision = resolver("{\"ifIdentical\":\"rename\"}").resolve(item("same"), target());

        assertEquals(target().resolveSibling("photo (1).jpg"), decision.target());
        assertFalse(decision.replaceExisting());
        assertFalse(decision.isSkip());
    }

    @Test
    public void aRenameChainAppliesThePolicyOfEachCandidate() throws IOException {
        existing("photo.jpg", "same");
        existing("photo (1).jpg", "diff");

        ConflictResolver.Decision skipped = resolver("{\"ifIdentical\":\"rename\",\"ifDifferent\":\"skip\"}")
                .resolve(item("same"), target());
        assertTrue(skipped.isSkip());
        assertFalse(skipped.identical());
        assertEquals(target().resolveSibling("photo (1).jpg"), skipped.target());

        ConflictResolver.Decision overwritten = resolver("{\"ifIdentical\":\"overwrite\"}").resolve(item("diff"), target());
        assertEquals(target().resolveSibling("photo (1).jpg"), overwritten.target(), "photo.jpg differs: renamed");
        assertTrue(overwritten.replaceExisting(), "photo (1).jpg is identical: overwritten");

        assertThrows(CopybotException.class,
                () -> resolver("{\"ifIdentical\":\"error\"}").resolve(item("diff"), target()));
    }

    @Test
    public void aFreeTargetReadsNothing() throws IOException {
        WorkItem neverRead = new WorkItem(tempDir.toUri().toURL(), () -> {
            throw new AssertionError("a free target: nothing is read");
        });

        assertEquals(target(), resolver("{\"compare\":\"fullHash\"}").resolve(neverRead, target()).target());
    }

    @Test
    public void aDirectoryAtTheTargetIsNeverIdenticalNorRead() throws IOException {
        Files.createDirectories(target());
        WorkItem empty = item(""); // on Windows a directory has size 0

        for (String compare : new String[]{"size", "sizeAndDate", "partialHash", "fullHash"}) {
            ConflictResolver.Decision decision = resolver("{\"compare\":\"" + compare + "\"}").resolve(empty, target());

            assertEquals(target().resolveSibling("photo (1).jpg"), decision.target(), compare);
            assertFalse(decision.isSkip(), compare);
        }
        ConflictResolver.Decision skipped = resolver("{\"compare\":\"size\",\"ifDifferent\":\"skip\"}").resolve(empty, target());
        assertEquals(ResourcesEngine.getString("write.skip.exists", target()), skipped.skipReason());
    }

    @Test
    public void numberedNamesKeepTheExtension() {
        Path dir = tempDir;

        assertEquals(dir.resolve("IMG_01 (1).JPG"), ConflictResolver.numbered(dir.resolve("IMG_01.JPG"), 1));
        assertEquals(dir.resolve("archive.tar (2).gz"), ConflictResolver.numbered(dir.resolve("archive.tar.gz"), 2));
        assertEquals(dir.resolve("README (1)"), ConflictResolver.numbered(dir.resolve("README"), 1));
        assertEquals(dir.resolve(".hidden (1)"), ConflictResolver.numbered(dir.resolve(".hidden"), 1));
    }
}

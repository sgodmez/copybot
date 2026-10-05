package com.copybot.plugin.embedded.actions;

import com.copybot.plugin.api.action.IOutAction;
import com.copybot.plugin.api.action.TargetCheck;
import com.copybot.plugin.api.action.TargetCheck.Kind;
import com.copybot.plugin.api.action.WorkItem;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** file.write tells what its copy would find at the target, without writing anything (spec conflict-check §3). */
public class FileWriteCheckTest {

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

    private Path nas(String name, String content) throws IOException {
        return Files.writeString(Files.createDirectories(tempDir.resolve("nas")).resolve(name), content);
    }

    private List<Path> nasFiles() throws IOException {
        if (!Files.exists(tempDir.resolve("nas"))) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(tempDir.resolve("nas"))) {
            return files.sorted().toList();
        }
    }

    @Test
    public void aFreeTargetIsFree() throws IOException {
        TargetCheck check = action("").checkTarget(source("a.jpg", "abc"), false);

        assertEquals(Kind.FREE, check.kind());
        assertNull(check.existing());
        assertFalse(check.exists());
        assertEquals(List.of(), nasFiles(), "nothing is created");
    }

    @Test
    public void quickComparesTheSizesOnly() throws IOException {
        Path same = nas("a.jpg", "xyz"); // same size, other content: quick does not read it
        nas("b.jpg", "longer");

        TargetCheck sameSize = action("").checkTarget(source("a.jpg", "abc"), false);
        TargetCheck differentSize = action("").checkTarget(source("b.jpg", "abc"), false);

        assertEquals(Kind.SAME_SIZE, sameSize.kind());
        assertFalse(sameSize.skipped(), "quick cannot tell: the copy compares");
        assertEquals(same, sameSize.existing());
        assertNotNull(sameSize.message());
        assertEquals(Kind.DIFFERENT_SIZE, differentSize.kind());
        assertTrue(differentSize.exists());
        assertNotNull(differentSize.message());
    }

    @Test
    public void quickTakesADirectoryAtTheTargetAsDifferent() throws IOException {
        Files.createDirectories(tempDir.resolve("nas").resolve("a.jpg"));

        assertEquals(Kind.DIFFERENT_SIZE, action("").checkTarget(source("a.jpg", "abc"), false).kind());
    }

    @Test
    public void fullTellsWhatTheCopyWillDo() throws IOException {
        nas("same.jpg", "abc");
        nas("other.jpg", "xyz");
        nas("other (1).jpg", "uvw");

        TargetCheck identical = action("").checkTarget(source("same.jpg", "abc"), true);
        TargetCheck renamed = action("").checkTarget(source("other.jpg", "abc"), true);

        assertEquals(Kind.IDENTICAL, identical.kind());
        assertEquals(Kind.DIFFERENT, renamed.kind());
        assertTrue(identical.skipped(), "identical, ifIdentical skip (the default): left out by the copy");
        assertFalse(renamed.skipped(), "renamed: copied");
        assertTrue(renamed.message().contains("other (2).jpg"), renamed.message());
        assertEquals(3, nasFiles().size(), "nothing is written");
    }

    @Test
    public void fullFollowsTheConflictPolicies() throws IOException {
        nas("a.jpg", "xyz");
        String overwrite = action(",\"onConflict\":{\"ifDifferent\":\"overwrite\"}")
                .checkTarget(source("a.jpg", "abc"), true).message();
        String error = action(",\"onConflict\":{\"ifDifferent\":\"error\"}")
                .checkTarget(source("a.jpg", "abc"), true).message();
        String skip = action(",\"onConflict\":{\"ifDifferent\":\"skip\"}")
                .checkTarget(source("a.jpg", "abc"), true).message();
        assertTrue(action(",\"onConflict\":{\"ifDifferent\":\"skip\"}").checkTarget(source("a.jpg", "abc"), true).skipped());
        assertFalse(action(",\"onConflict\":{\"ifDifferent\":\"overwrite\"}").checkTarget(source("a.jpg", "abc"), true).skipped());
        assertFalse(action(",\"onConflict\":{\"ifIdentical\":\"overwrite\"}").checkTarget(source("a.jpg", "xyz"), true).skipped(),
                "identical but overwritten: written");

        assertNotEquals(overwrite, error);
        assertNotEquals(overwrite, skip);
        assertNotEquals(error, skip);
        assertEquals("xyz", Files.readString(tempDir.resolve("nas").resolve("a.jpg")), "nothing is overwritten");
    }

    @Test
    public void theSourceItselfIsIdentical() throws IOException {
        Path file = nas("a.jpg", "abc");
        WorkItem inPlace = new WorkItem(file);
        inPlace.getMetadatas().setSize(3L);
        inPlace.getMetadatas().display().put("name", "a.jpg");

        assertEquals(Kind.IDENTICAL, action("").checkTarget(inPlace, false).kind());
        assertEquals(Kind.IDENTICAL, action("").checkTarget(inPlace, true).kind());
        assertTrue(action("").checkTarget(inPlace, false).skipped(), "the source itself is never written");
    }

    @Test
    public void aMissingPatternKeyIsUnknown() throws IOException {
        FileWriteAction action = new FileWriteAction();
        String pattern = tempDir.resolve("nas").toString().replace("\\", "/") + "/{captureDate.Y}/{name}";
        action.loadConfig(JsonParser.parseString("{\"outPattern\":\"" + pattern + "\"}"));

        assertEquals(Kind.UNKNOWN, action.checkTarget(source("a.jpg", "abc"), false).kind());
    }

    @Test
    public void anOutActionThatCannotTellAnswersUnknown() {
        IOutAction plugin = new IOutAction() {
            @Override
            public void writeItem(WorkItem workItem) {
            }

            @Override
            public void setStatusWatcher(java.util.function.Consumer<com.copybot.plugin.api.action.WorkStatus> watcher) {
            }

            @Override
            public void setPlugin(com.copybot.plugin.api.definition.IPlugin plugin) {
            }
        };

        assertSame(TargetCheck.UNKNOWN, plugin.checkTarget(null, true));
    }

    @Test
    public void theMostSevereCheckWins() {
        TargetCheck free = TargetCheck.free();
        TargetCheck same = new TargetCheck(Kind.SAME_SIZE, Path.of("a"), "m");
        TargetCheck different = new TargetCheck(Kind.DIFFERENT_SIZE, Path.of("b"), "m");

        assertSame(different, TargetCheck.mostSevere(same, different));
        assertSame(same, TargetCheck.mostSevere(same, free));
        assertSame(free, TargetCheck.mostSevere(TargetCheck.UNKNOWN, free));
        assertSame(same, TargetCheck.mostSevere(same, new TargetCheck(Kind.IDENTICAL, Path.of("c"), "m")), "a tie keeps the first");
    }
}

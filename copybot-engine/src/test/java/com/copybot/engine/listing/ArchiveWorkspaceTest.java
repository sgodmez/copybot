package com.copybot.engine.listing;

import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.embedded.actions.FileReadAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchiveWorkspaceTest {

    @TempDir
    Path base;

    @Test
    void openCreatesALockedRunFolder() throws IOException {
        try (ArchiveWorkspace ws = ArchiveWorkspace.open(base)) {
            assertTrue(ws.folder().getFileName().toString().startsWith("run-"));
            assertTrue(Files.exists(ws.folder().resolve("run.lock")));
        }
    }

    @Test
    void lockedFolderOfAnotherInstanceIsKept() throws IOException {
        try (ArchiveWorkspace other = ArchiveWorkspace.open(base);
             ArchiveWorkspace ws = ArchiveWorkspace.open(base)) {
            assertTrue(Files.isDirectory(other.folder()));
            assertTrue(Files.isDirectory(ws.folder()));
        }
    }

    @Test
    void unlockedFolderIsRemoved() throws IOException {
        Path stale = Files.createDirectories(base.resolve("run-crashed"));
        Files.createFile(stale.resolve("run.lock"));
        Files.writeString(stale.resolve("1-a.zip"), "left over");
        Path other = Files.createDirectories(base.resolve("not-a-run"));
        try (ArchiveWorkspace ws = ArchiveWorkspace.open(base)) {
            assertFalse(Files.exists(stale));
            assertTrue(Files.exists(other));
        }
    }

    @Test
    void closeRemovesEverything() throws IOException {
        ArchiveWorkspace ws = ArchiveWorkspace.open(base);
        byte[] data = "zip bytes".getBytes(StandardCharsets.UTF_8);
        WorkItem remote = WorkItem.remote("a.zip", "ftp://h/d", "ftp://h/d", data.length, Instant.EPOCH,
                () -> new ByteArrayInputStream(data), null);
        Path copy = ws.localCopy(remote);
        assertTrue(copy.startsWith(ws.folder()));
        assertArrayEquals(data, Files.readAllBytes(copy));
        Path folder = ws.folder();
        ws.close();
        assertFalse(Files.exists(folder));
    }

    @Test
    void localFileIsUsedAsItIs() throws IOException {
        Path zip = Files.writeString(base.resolve("a.zip"), "x");
        try (ArchiveWorkspace ws = ArchiveWorkspace.open(base.resolve("ws"))) {
            assertEquals(zip, ws.localCopy(FileReadAction.workItemOf(zip)));
            try (var files = Files.list(ws.folder())) {
                assertEquals(1, files.count()); // run.lock only
            }
        }
    }
}

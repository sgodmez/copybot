package com.copybot.plugin.api.action;

import com.copybot.engine.resume.ItemKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

public class WorkItemCompanionTest {

    @TempDir
    Path tempDir;

    private static final Supplier<InputStream> STREAM = () -> new ByteArrayInputStream(new byte[10]);

    @Test
    public void remoteItemHasNameDisplayAndKeyMetadata() {
        Instant t = Instant.parse("2026-09-28T10:00:00Z");
        WorkItem item = WorkItem.remote("IMG_1.jpg", "a.zip!/DCIM", "a.zip!/DCIM", 10, t, STREAM, null);

        assertEquals("IMG_1.jpg", item.getNameDisplay());
        assertEquals("a.zip!/DCIM", item.getSourceLocationDisplay());
        assertEquals("IMG_1.jpg", item.getMetadatas().display().get("name"));
        assertEquals(10L, item.getMetadatas().getSize());
        assertEquals(t, item.getMetadatas().getTime(WorkItemMetadata.LAST_MODIFIED).orElseThrow());
        assertFalse(item.isLocal());
        assertNull(item.getLocalLocation());
        assertEquals("a.zip!/DCIM", item.getListedFolder());
        assertTrue(item.getSourceDeleter().isEmpty());
        assertEquals("IMG_1.jpg", ItemKey.of(item).orElseThrow().name());
    }

    @Test
    public void remoteItemKeepsItsDeleterAcrossCopies() {
        SourceDeleter deleter = () -> { };
        WorkItem item = WorkItem.remote("a.jpg", "f", "f", 1, Instant.EPOCH, STREAM, deleter);

        assertSame(deleter, item.getSourceDeleter().orElseThrow());
        assertSame(deleter, item.copyForDryRun().getSourceDeleter().orElseThrow());
        assertSame(deleter, item.withContent(STREAM, 3).getSourceDeleter().orElseThrow());
    }

    @Test
    public void companionsSurviveCopies() throws IOException {
        WorkItem main = new WorkItem(Files.writeString(tempDir.resolve("x.jpg"), "x"));
        WorkItem side = new WorkItem(Files.writeString(tempDir.resolve("x.xmp"), "y"));
        Companion c = new Companion(side, CompanionRole.COPY, CompanionLink.BASE, ".xmp");

        assertTrue(main.getCompanions().isEmpty());
        WorkItem grouped = main.withCompanions(List.of(c));
        assertTrue(main.getCompanions().isEmpty());
        assertEquals(List.of(c), grouped.getCompanions());
        assertThrows(UnsupportedOperationException.class, () -> grouped.getCompanions().add(c));

        assertEquals(List.of(c), grouped.copyForDryRun().getCompanions());
        assertEquals(List.of(c), grouped.withContent(STREAM, 1).getCompanions());
        assertEquals(List.of(c), grouped.withContentFile(tempDir.resolve("x.jpg")).getCompanions());

        assertTrue(grouped.withoutCompanions().getCompanions().isEmpty());
        assertEquals(List.of(c), grouped.getCompanions());
    }

    @Test
    public void targetNameByLink() throws IOException {
        WorkItem item = new WorkItem(Files.writeString(tempDir.resolve("x.jpg"), "x"));
        assertEquals("IMG_1 (1).xmp", target(item, CompanionLink.BASE, ".xmp", "IMG_1 (1).CR3"));
        assertEquals("IMG_1.CR3.xmp", target(item, CompanionLink.NAME, ".xmp", "IMG_1.CR3"));
        assertEquals("page_files/img/a.png", target(item, CompanionLink.BASE, "_files/img/a.png", "page.html"));
        assertEquals("README.txt", target(item, CompanionLink.BASE, ".txt", "README"));
    }

    private static String target(WorkItem item, CompanionLink link, String suffix, String mainName) {
        return new Companion(item, CompanionRole.COPY, link, suffix).targetName(mainName);
    }

    @Test
    public void fileItemListedFolderIsParent() throws IOException {
        assertEquals(tempDir.toString(), new WorkItem(tempDir.resolve("x.jpg")).getListedFolder());
    }

    @Test
    public void listFilesSinkDefaultsToConsumer() throws IOException {
        WorkItem item = new WorkItem(tempDir.resolve("x.jpg"));
        IInAction in = new IInAction() {
            @Override
            public void listFiles(Consumer<WorkItem> workItemConsumer) {
                workItemConsumer.accept(item);
            }
        };
        List<WorkItem> received = new ArrayList<>();
        in.listFiles(new ListingSink() {
            @Override
            public void item(WorkItem i) {
                received.add(i);
            }

            @Override
            public void folderDone(String folder) {
                fail("not signalled");
            }
        });
        assertEquals(List.of(item), received);
        assertFalse(in.signalsFolders());
    }
}

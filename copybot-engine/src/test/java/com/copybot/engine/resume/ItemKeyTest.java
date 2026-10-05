package com.copybot.engine.resume;

import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

public class ItemKeyTest {

    @TempDir
    Path tempDir;

    @Test
    public void ordersByDateThenName() {
        ItemKey a = new ItemKey(Instant.parse("2026-09-28T10:00:00Z"), "DSC_9999.NEF");
        ItemKey b = new ItemKey(Instant.parse("2026-09-28T10:00:01Z"), "DSC_0001.NEF");
        ItemKey c = new ItemKey(Instant.parse("2026-09-28T10:00:01Z"), "DSC_0002.NEF");

        assertTrue(a.compareTo(b) < 0, "date first: the counter wrap must not matter");
        assertTrue(b.compareTo(c) < 0, "same second: name breaks the tie");
    }

    @Test
    public void aKeyWithoutDateIsRejectedWithAClearMessage() {
        NullPointerException e = assertThrows(NullPointerException.class, () -> new ItemKey(null, "X"));
        assertEquals("date", e.getMessage());
    }

    @Test
    public void truncatesToTheSecond() {
        ItemKey precise = new ItemKey(Instant.parse("2026-09-28T10:00:00.750Z"), "X");
        ItemKey rounded = new ItemKey(Instant.parse("2026-09-28T10:00:00Z"), "X");

        assertEquals(rounded, precise);
        assertEquals(0, precise.compareTo(rounded));
    }

    @Test
    public void ofUsesTheFileModificationDateOnly() throws Exception {
        WorkItem item = new WorkItem(Files.createFile(tempDir.resolve("IMG_1.JPG")));
        item.getMetadatas().setTime(WorkItemMetadata.LAST_MODIFIED, Instant.parse("2026-09-29T08:00:00Z"));
        item.getMetadatas().setTime(WorkItemMetadata.CAPTURE_DATE, Instant.parse("2026-09-28T15:42:10Z"));

        assertEquals(new ItemKey(Instant.parse("2026-09-29T08:00:00Z"), "IMG_1.JPG"), ItemKey.of(item).orElseThrow(),
                "known as soon as the file is listed, whatever the analyses");
    }

    @Test
    public void aCaptureDateAloneGivesNoKey() throws Exception {
        WorkItem item = new WorkItem(Files.createFile(tempDir.resolve("IMG_2.JPG")));
        item.getMetadatas().setTime(WorkItemMetadata.CAPTURE_DATE, Instant.parse("2026-09-28T15:42:10Z"));

        assertTrue(ItemKey.of(item).isEmpty());
    }

    @Test
    public void ofIsEmptyWithoutAnyDate() throws Exception {
        WorkItem item = new WorkItem(Files.createFile(tempDir.resolve("nodate.bin")));
        assertTrue(ItemKey.of(item).isEmpty());
    }
}

package com.copybot.plugin.api.action;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

public class WorkItemCopyTest {

    @TempDir
    Path tempDir;

    @Test
    public void aCopyHasTheSameSourceAndIndependentMetadata() throws IOException {
        Path file = Files.writeString(tempDir.resolve("DSC_1.NEF"), "x");
        WorkItem item = new WorkItem(file);
        item.getMetadatas().display().put("name", "DSC_1.NEF");
        item.getMetadatas().setTime(WorkItemMetadata.CAPTURE_DATE, Instant.parse("2026-09-28T10:00:00Z"));

        WorkItem copy = item.copyForDryRun();
        copy.getMetadatas().display().put("name", "DSC_1.jpg");
        copy.getMetadatas().raw().put("extra", 1L);

        assertEquals(item.getLocalLocation(), copy.getLocalLocation());
        assertEquals(item.getNameDisplay(), copy.getNameDisplay());
        assertEquals("DSC_1.NEF", item.getMetadatas().display().get("name"));
        assertFalse(item.getMetadatas().raw().containsKey("extra"));
        assertEquals(item.getMetadatas().getTime(WorkItemMetadata.CAPTURE_DATE), copy.getMetadatas().getTime(WorkItemMetadata.CAPTURE_DATE));
    }
}

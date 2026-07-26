package com.copybot.plugin.embedded.actions;

import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkStatus;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

public class FileWriteActionTest {

    @TempDir
    Path tempDir;

    private FileWriteAction action(Path outFile, boolean overwrite) {
        FileWriteAction action = new FileWriteAction();
        action.loadConfig(JsonParser.parseString(
                "{\"outPattern\":\"" + outFile.toString().replace("\\", "\\\\") + "\",\"overwrite\":" + overwrite + "}"));
        return action;
    }

    private WorkItem itemWithContent(String name, byte[] content) throws IOException {
        Path source = tempDir.resolve(name);
        Files.write(source, content);
        WorkItem item = new WorkItem(source);
        item.getMetadatas().setSize((long) content.length);
        return item;
    }

    @Test
    public void overwriteTrueCreatesAMissingTargetFile() throws IOException {
        Path target = tempDir.resolve("out").resolve("created.bin");
        Files.createDirectories(target.getParent());
        WorkItem item = itemWithContent("src1.bin", "hello".getBytes());

        action(target, true).writeItem(item);

        assertTrue(Files.exists(target), "overwrite:true must be able to create the target");
        assertArrayEquals("hello".getBytes(), Files.readAllBytes(target));
    }

    @Test
    public void overwriteTrueReplacesAnExistingTargetFile() throws IOException {
        Path target = tempDir.resolve("existing.bin");
        Files.write(target, "old content that is longer".getBytes());
        WorkItem item = itemWithContent("src2.bin", "new".getBytes());

        action(target, true).writeItem(item);

        assertArrayEquals("new".getBytes(), Files.readAllBytes(target), "target must be truncated then replaced");
    }

    @Test
    public void overwriteFalseFailsOnAnExistingTargetFile() throws IOException {
        Path target = tempDir.resolve("protected.bin");
        Files.write(target, "keep me".getBytes());
        WorkItem item = itemWithContent("src3.bin", "intruder".getBytes());

        assertThrows(RuntimeException.class, () -> action(target, false).writeItem(item));
        assertArrayEquals("keep me".getBytes(), Files.readAllBytes(target));
    }

    @Test
    public void missingParentDirectoriesAreCreated() throws IOException {
        Path target = tempDir.resolve("out").resolve("nested").resolve("deep.bin");
        WorkItem item = itemWithContent("src5.bin", "payload".getBytes());

        action(target, false).writeItem(item);

        assertArrayEquals("payload".getBytes(), Files.readAllBytes(target));
    }

    @Test
    public void progressPercentsAreProportionalNotZero() throws IOException {
        // 2 buffers of 8192: first update must report 50, not 0 (integer-division regression)
        byte[] content = new byte[16384];
        WorkItem item = itemWithContent("src4.bin", content);
        Path target = tempDir.resolve("progress.bin");

        FileWriteAction action = action(target, true);
        List<WorkStatus> statuses = new CopyOnWriteArrayList<>();
        action.setStatusWatcher(statuses::add);

        action.writeItem(item);

        List<Integer> percents = statuses.stream().map(WorkStatus::actionPercent).filter(p -> p >= 0).toList();
        assertTrue(percents.contains(50), "mid-copy percent must be 50, got " + percents);
        assertEquals(100, percents.getLast().intValue());
    }
}

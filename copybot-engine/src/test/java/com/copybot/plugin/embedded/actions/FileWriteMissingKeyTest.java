package com.copybot.plugin.embedded.actions;

import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WriteContext;
import com.copybot.plugin.api.action.WriteResult;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class FileWriteMissingKeyTest {

    @TempDir
    Path tempDir;

    /** file.write to tempDir/out/{captureDate.Y}/{name}; extra is appended to the actionConfig. */
    private FileWriteAction action(String extra) {
        String out = tempDir.resolve("out").toString().replace("\\", "/") + "/{captureDate.Y}/{name}";
        FileWriteAction action = new FileWriteAction();
        action.loadConfig(JsonParser.parseString("{\"outPattern\":\"" + out + "\"" + extra + "}"));
        return action;
    }

    /** A source file without captureDate (like a video without EXIF). */
    private WorkItem video() throws IOException {
        Path source = Files.writeString(tempDir.resolve("VID_0001.MP4"), "video");
        WorkItem item = new WorkItem(source);
        item.getMetadatas().display().put("name", "VID_0001.MP4");
        item.getMetadatas().setSize(5);
        return item;
    }

    @Test
    public void byDefaultAMissingKeyFailsTheItemAndCreatesNothing() throws IOException {
        WorkItem item = video();

        CopybotException e = assertThrows(CopybotException.class, () -> action("").write(item, WriteContext.newRun()));

        assertTrue(e.getMessage().contains("{captureDate.Y}"), e.getMessage());
        assertTrue(e.getMessage().contains("VID_0001.MP4"), e.getMessage());
        assertFalse(Files.exists(tempDir.resolve("out")), "nothing is created on the destination");
    }

    @Test
    public void skipSkipsTheItemWithTheMissingExpression() throws IOException {
        WriteResult result = action(",\"onMissingKey\":\"skip\"").write(video(), WriteContext.newRun());

        assertTrue(result.isSkipped());
        assertTrue(result.reason().contains("{captureDate.Y}"), result.reason());
        assertFalse(Files.exists(tempDir.resolve("out")));
    }

    @Test
    public void literalKeepsTheExpressionAsWritten() throws IOException {
        action(",\"onMissingKey\":\"literal\"").write(video(), WriteContext.newRun());

        assertTrue(Files.exists(tempDir.resolve("out").resolve("{captureDate.Y}").resolve("VID_0001.MP4")));
    }

    @Test
    public void aFallbackAvoidsTheMissingKey() throws IOException {
        String out = tempDir.resolve("out").toString().replace("\\", "/") + "/{captureDate.Y|'sans-date'}/{name}";
        FileWriteAction action = new FileWriteAction();
        action.loadConfig(JsonParser.parseString("{\"outPattern\":\"" + out + "\"}"));

        action.write(video(), WriteContext.newRun());

        assertTrue(Files.exists(tempDir.resolve("out").resolve("sans-date").resolve("VID_0001.MP4")));
    }

    @Test
    public void theSourceIsNeverDeletedOnAMissingKey() throws IOException {
        WorkItem item = video();

        action(",\"onMissingKey\":\"skip\",\"deleteSource\":true,\"verify\":\"readBack\"").write(item, WriteContext.newRun());
        assertThrows(CopybotException.class,
                () -> action(",\"deleteSource\":true,\"verify\":\"readBack\"").write(item, WriteContext.newRun()));

        assertTrue(Files.exists(tempDir.resolve("VID_0001.MP4")));
    }

    @Test
    public void resolveTargetThrowsInErrorAndSkipModesAndResolvesInLiteralMode() throws IOException {
        WorkItem item = video();

        assertThrows(CopybotException.class, () -> action("").resolveTarget(item));
        assertThrows(CopybotException.class, () -> action(",\"onMissingKey\":\"skip\"").resolveTarget(item));
        assertTrue(action(",\"onMissingKey\":\"literal\"").resolveTarget(item).orElseThrow().toString().contains("{captureDate.Y}"));
    }

    @Test
    public void anInvalidPatternIsAConfigurationError() {
        FileWriteAction action = new FileWriteAction();
        assertThrows(CopybotException.class, () -> action.loadConfig(JsonParser.parseString("{\"outPattern\":\"/a/{name\"}")));
    }

    @Test
    public void anUnknownOnMissingKeyIsRefused() {
        FileWriteAction action = new FileWriteAction();
        CopybotException e = assertThrows(CopybotException.class,
                () -> action.loadConfig(JsonParser.parseString("{\"outPattern\":\"x\",\"onMissingKey\":\"ignore\"}")));
        assertTrue(e.getMessage().contains("ignore"), e.getMessage());
    }
}

package com.copybot.plugin.embedded.actions;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class EmbeddedActionResourcesTest {

    @TempDir
    Path tempDir;

    @Test
    public void fileReadDeclaresItsConfiguredRoot() {
        FileReadAction action = new FileReadAction();
        action.loadConfig(JsonParser.parseString(
                "{\"path\":\"" + tempDir.toString().replace("\\", "\\\\") + "\"}"));
        assertEquals(Set.of(tempDir), action.touchedPaths(null));
    }

    @Test
    public void fileWriteDeclaresTheStaticPrefixOfItsOutPattern() {
        FileWriteAction action = new FileWriteAction();
        String outDir = tempDir.resolve("out").toString();
        action.loadConfig(JsonParser.parseString(
                "{\"outPattern\":\"" + (outDir + "\\\\{name}").replace("\\", "\\\\") + "\",\"onConflict\":{\"ifDifferent\":\"overwrite\"}}"));
        assertEquals(Set.of(Path.of(outDir)), action.touchedPaths(null));
    }
}

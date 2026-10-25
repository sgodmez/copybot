package com.copybot.config;

import com.copybot.utils.GsonUtil;
import com.google.gson.JsonSyntaxException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** devPluginPaths: a list of development plugin directories, a single one still written as a string. */
public class CopybotConfigTest {

    private static CopybotConfig read(String json) {
        return GsonUtil.getGson().fromJson(json, CopybotConfig.class);
    }

    @Test
    public void aSingleDevPluginPathIsStillAString() {
        assertEquals(List.of(Path.of("dev/target")), read("{ \"devPluginPaths\": \"dev/target\" }").devPluginPaths());
    }

    @Test
    public void devPluginPathsCanBeAList() {
        assertEquals(List.of(Path.of("a/target"), Path.of("b/target")),
                read("{ \"devPluginPaths\": [\"a/target\", \"b/target\"] }").devPluginPaths());
    }

    @Test
    public void devPluginPathsAreOptional() {
        assertNull(read("{ }").devPluginPaths());
        assertNull(read("{ \"devPluginPaths\": null }").devPluginPaths());
    }

    @Test
    public void aDevPluginPathOfAnotherKindIsRefused() {
        assertThrows(JsonSyntaxException.class, () -> read("{ \"devPluginPaths\": 3 }"));
        assertThrows(JsonSyntaxException.class, () -> read("{ \"devPluginPaths\": [3] }"));
    }

    @Test
    public void theDevConfigLoadsTheMetadataAndGpsZonePlugins() throws IOException {
        // the working directory of the test JVM is the engine module
        Path devConfig = Path.of("..", "copybot-ui", "src", "dev", "config.json");

        CopybotConfig config = read(Files.readString(devConfig));

        assertEquals(List.of(Path.of("copybot-plugin/copybot-plugin-metadata-extractor/target"),
                Path.of("copybot-plugin/copybot-plugin-gps-zone/target")), config.devPluginPaths());
    }
}

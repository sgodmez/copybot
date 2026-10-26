package com.copybot.ui.plugins;

import com.copybot.engine.plugin.report.PluginEntry;
import com.copybot.engine.plugin.report.PluginReport;
import com.copybot.engine.plugin.report.PluginSource;
import com.copybot.engine.plugin.report.PluginStatus;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class PluginChangesTest {

    @TempDir
    Path dir;

    private static PluginCatalog catalog() throws Exception {
        return PluginCatalog.parse("{\"format\": 1, \"plugins\": ["
                + "{\"module\": \"m.one\", \"code\": \"one\", \"version\": \"1.1.0\", \"engine\": \"1.0\", \"tag\": \"v1\", \"asset\": \"a.zip\"},"
                + "{\"module\": \"m.two\", \"code\": \"two\", \"version\": \"1.0.0\", \"engine\": \"1.0\", \"tag\": \"v1\", \"asset\": \"b.zip\"},"
                + "{\"module\": \"m.three\", \"code\": \"three\", \"version\": \"1.0.0\", \"engine\": \"1.0\", \"tag\": \"v1\", \"asset\": \"c.zip\"}]}");
    }

    private static PluginEntry loaded(String module, String version, Path path) {
        return new PluginEntry(module, version, PluginSource.PLUGIN_PATH, path, PluginStatus.LOADED, null, List.of(),
                List.of(), List.of(), List.of(), null);
    }

    private List<PluginRow> rows() throws Exception {
        PluginReport report = new PluginReport(Path.of("c.json"), dir.resolve("plugins"), List.of(), List.of(), List.of(
                loaded("m.one", "1.0.0", dir.resolve("plugins/free-name")),
                loaded("m.one", "0.9.0", dir.resolve("plugins/older")),
                loaded("m.two", "1.0.0", dir.resolve("plugins/two"))));
        return PluginRow.of(catalog(), report, "1.3.0");
    }

    @Test
    public void anUpdateRemovesTheFoldersOfTheSameModule() throws Exception {
        PluginRow one = rows().get(0);
        Path source = dir.resolve("tmp/one");

        PluginChanges changes = PluginChanges.plan(List.of(one), Map.of(one, source), List.of());

        assertEquals(List.of(new PluginChanges.Install(source, "one")), changes.installs());
        assertEquals(2, changes.removals().size());
        assertTrue(changes.removals().containsAll(List.of(dir.resolve("plugins/free-name"), dir.resolve("plugins/older"))));
    }

    @Test
    public void anUninstallRemovesItsFoldersWithoutDuplicatesAndInstallsNothing() throws Exception {
        PluginRow two = rows().get(1);

        PluginChanges changes = PluginChanges.plan(List.of(), Map.of(), List.of(two, two));

        assertTrue(changes.installs().isEmpty());
        assertEquals(List.of(dir.resolve("plugins/two")), changes.removals());
    }

    @Test
    public void aNewInstallRemovesNothing() throws Exception {
        PluginRow three = rows().get(2);
        Path source = dir.resolve("tmp/three");

        PluginChanges changes = PluginChanges.plan(List.of(three), Map.of(three, source), List.of());

        assertEquals(1, changes.installs().size());
        assertTrue(changes.removals().isEmpty());
    }

    @Test
    public void anInstallWithoutItsUnpackedFolderIsRefused() throws Exception {
        PluginRow three = rows().get(2);

        assertThrows(IllegalArgumentException.class, () -> PluginChanges.plan(List.of(three), Map.of(), List.of()));
    }

    @Test
    public void theManifestHoldsAbsolutePathsInTheFormOfTheScript() throws Exception {
        PluginChanges changes = new PluginChanges(
                List.of(new PluginChanges.Install(Path.of("tmp", "one"), "one")),
                List.of(dir.resolve("plugins/free-name")));
        Path file = dir.resolve("manifest.json");

        changes.writeManifest(file);

        JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        JsonObject install = root.getAsJsonArray("install").get(0).getAsJsonObject();
        assertEquals(Path.of("tmp", "one").toAbsolutePath().toString(), install.get("source").getAsString());
        assertEquals("one", install.get("target").getAsString());
        assertEquals(dir.resolve("plugins/free-name").toAbsolutePath().toString(),
                root.getAsJsonArray("remove").get(0).getAsString());
    }

    @Test
    public void anEmptyPlanWritesEmptyLists() throws Exception {
        Path file = dir.resolve("manifest.json");

        new PluginChanges(List.of(), List.of()).writeManifest(file);

        JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertEquals(0, root.getAsJsonArray("install").size());
        assertEquals(0, root.getAsJsonArray("remove").size());
    }
}

package com.copybot.engine.plugin.report;

import com.copybot.engine.pipeline.StepType;
import com.copybot.engine.plugin.PluginDefinition;
import com.copybot.engine.plugin.loader.PluginLoader;
import com.copybot.engine.plugin.loader.TestPluginJar;
import com.copybot.utils.FileUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PluginReportsTest {

    // not @TempDir: a loaded layer keeps its jars open, which Windows cannot delete
    Path tempDir;

    @BeforeEach
    void root() throws IOException {
        tempDir = TestPluginJar.newRoot("report");
    }

    private List<PluginDefinition> load(Path pluginPath, List<Path> devDirs) {
        PluginLoader loader = new PluginLoader();
        loader.resolve(FileUtil.listDirectory(pluginPath), false);
        loader.resolve(devDirs, true);
        return new ArrayList<>(loader.load());
    }

    private static PluginEntry entry(PluginReport report, String name) {
        return report.plugins().stream().filter(p -> p.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(name + " not in " + report.plugins()));
    }

    @Test
    public void aLoadedPluginHasItsModulesAndActions() throws Exception {
        Path pluginPath = Files.createDirectories(tempDir.resolve("plugins"));
        TestPluginJar.plugin("fixture.alpha").version("1.0.0").writeTo(pluginPath.resolve("alpha"));

        PluginReport report = PluginReports.of(tempDir.resolve("config.json"), pluginPath, true, List.of(), load(pluginPath, List.of()));

        PluginEntry alpha = entry(report, "fixture.alpha");
        assertEquals(PluginStatus.LOADED, alpha.status());
        assertNull(alpha.message());
        assertEquals(PluginSource.PLUGIN_PATH, alpha.source());
        assertEquals("1.0.0", alpha.version());
        assertEquals(1, alpha.modules().size());
        assertEquals(List.of(new ActionEntry(StepType.ANALYZE, "act", "act")), alpha.actions());
    }

    @Test
    public void statusesAndOrder() throws Exception {
        Path pluginPath = Files.createDirectories(tempDir.resolve("plugins"));
        TestPluginJar.plugin("fixture.zeta").version("1.0.0").writeTo(pluginPath.resolve("a-ok"));
        TestPluginJar.plugin("fixture.dup").version("1.0.0").writeTo(pluginPath.resolve("b-old"));
        TestPluginJar.plugin("fixture.dup").version("1.0.1").writeTo(pluginPath.resolve("c-new"));
        TestPluginJar.plugin("fixture.mute").version("1.0.0").failingActions().writeTo(pluginPath.resolve("d-noactions"));
        Files.createDirectories(pluginPath.resolve("e-empty")); // no module: ERROR

        PluginReport report = PluginReports.of(tempDir.resolve("config.json"), pluginPath, true, List.of(), load(pluginPath, List.of()));

        assertEquals(PluginStatus.ERROR, entry(report, "e-empty").status());
        PluginEntry mute = entry(report, "fixture.mute");
        assertEquals(PluginStatus.ACTIONS_FAILED, mute.status());
        assertTrue(mute.message().contains("no actions"), mute.message());
        assertEquals(List.of(), mute.actions());
        assertEquals(List.of(PluginStatus.LOADED, PluginStatus.IGNORED),
                report.plugins().stream().filter(p -> p.name().equals("fixture.dup")).map(PluginEntry::status).sorted().toList());

        List<PluginStatus> order = report.plugins().stream().map(PluginEntry::status).toList();
        assertEquals(List.of(PluginStatus.ERROR, PluginStatus.ACTIONS_FAILED, PluginStatus.IGNORED, PluginStatus.LOADED),
                order.stream().distinct().toList());
    }

    @Test
    public void aDevPluginIsReportedDev() throws Exception {
        Path pluginPath = Files.createDirectories(tempDir.resolve("plugins"));
        Path dev = TestPluginJar.plugin("fixture.devplug").version("2.0.0").writeDevDir(tempDir.resolve("dev-target"));

        PluginReport report = PluginReports.of(tempDir.resolve("config.json"), pluginPath, false, List.of(dev), load(pluginPath, List.of(dev)));

        assertEquals(PluginSource.DEV, entry(report, "fixture.devplug").source());
        assertEquals(List.of(), report.warnings());
        assertFalse(report.pluginPathConfigured());
    }

    @Test
    public void missingDirectoriesAreWarned() {
        Path pluginPath = tempDir.resolve("no-plugins");
        Path dev = tempDir.resolve("no-dev");

        PluginReport report = PluginReports.of(tempDir.resolve("config.json"), pluginPath, true, List.of(dev), load(tempDir, List.of()));

        assertEquals(2, report.warnings().size(), report.warnings().toString());
        assertTrue(report.warnings().get(0).contains(pluginPath.toAbsolutePath().normalize().toString()));
        assertTrue(report.warnings().get(1).contains(dev.toAbsolutePath().normalize().toString()));
        assertEquals(List.of(dev.toAbsolutePath().normalize()), report.devPluginPaths());
    }

    @Test
    public void theEmbeddedPluginIsInTheReport() {
        PluginReport report = PluginReports.of(tempDir.resolve("config.json"), tempDir, false, List.of(), load(tempDir, List.of()));

        PluginEntry embedded = report.plugins().stream().filter(p -> p.source() == PluginSource.EMBEDDED).findFirst().orElseThrow();
        assertEquals(PluginStatus.LOADED, embedded.status());
        assertNull(embedded.version());
        assertNull(embedded.path());
        assertFalse(embedded.actions().isEmpty());
    }
}

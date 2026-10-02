package com.copybot.engine.plugin.report;

import com.copybot.engine.pipeline.StepType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PluginReportTextTest {

    @Test
    public void theTextHoldsEveryPartOfTheReport() {
        Path config = Path.of("/app/config.json").toAbsolutePath();
        PluginEntry ok = new PluginEntry("com.example.ok", "1.2.0", PluginSource.DEV, Path.of("/dev/ok").toAbsolutePath(),
                PluginStatus.LOADED, null,
                List.of(new ModuleEntry("com.example.ok", "1.2.0", Path.of("/dev/ok/classes").toAbsolutePath(), true, false),
                        new ModuleEntry("lib.auto", null, Path.of("/dev/ok/lib/auto.jar").toAbsolutePath(), false, true)),
                List.of("com.example.base 1.0"), List.of(),
                List.of(new ActionEntry(StepType.ANALYZE, "scan", "Scan")));
        PluginEntry ko = new PluginEntry("broken", null, PluginSource.PLUGIN_PATH, Path.of("/plugins/broken").toAbsolutePath(),
                PluginStatus.ERROR, "Missing dependencies: x:1", List.of(), List.of(), List.of("x:1"), List.of());
        PluginReport report = new PluginReport(config, Path.of("/plugins").toAbsolutePath(), false,
                List.of(Path.of("/dev/ok").toAbsolutePath()), List.of("a warning"), List.of(ko, ok));

        String text = report.toText();

        for (String expected : List.of(config.toString(), "a warning", "com.example.ok 1.2.0", "LOADED", "DEV",
                "lib.auto", "com.example.base 1.0", "ANALYZE", "scan", "Scan", "broken", "ERROR",
                "Missing dependencies: x:1", "x:1")) {
            assertTrue(text.contains(expected), expected + " missing from:\n" + text);
        }
        assertTrue(text.indexOf("broken") < text.indexOf("com.example.ok 1.2.0"), "the plugins keep the report order");
    }
}

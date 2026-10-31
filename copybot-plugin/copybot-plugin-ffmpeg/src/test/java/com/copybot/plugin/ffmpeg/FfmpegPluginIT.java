package com.copybot.plugin.ffmpeg;

import com.copybot.engine.plugin.PluginDefinition;
import com.copybot.engine.plugin.PluginEngine;
import com.copybot.plugin.api.action.ActionDefinition;
import com.copybot.plugin.api.action.IProcessAction;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The packaged plugin loaded by the engine over the plugin exec it needs, as the application loads them in
 * development: the directories {@code target} of both modules (hence an integration test, run by failsafe after the
 * packaging). exec exports only its process package to every module; its other packages are opened or exported to
 * the engine alone, which must still create the actions of both.
 */
public class FfmpegPluginIT {

    @Test
    public void thePackagedPluginLoadsOverExecAndItsActionsAndThoseOfExecAreCreated() throws Exception {
        Path target = Path.of("target").toAbsolutePath();
        Path execTarget = Path.of("..", "copybot-plugin-exec", "target").toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(execTarget.resolve("classes")), "exec built before: " + execTarget);

        PluginEngine.load(Files.createTempDirectory("copybot-no-plugins"), List.of(execTarget, target));

        PluginDefinition exec = plugin("com.copybot.plugin.exec");
        PluginDefinition ffmpeg = plugin("com.copybot.plugin.ffmpeg");
        assertNull(exec.getErrorMessage(), "exec loaded");
        assertNull(ffmpeg.getErrorMessage(), "ffmpeg loaded over exec");
        ModuleLayer execLayer = exec.getPluginInstance().getClass().getModule().getLayer();
        ModuleLayer ffmpegLayer = ffmpeg.getPluginInstance().getClass().getModule().getLayer();
        assertNotSame(execLayer, ffmpegLayer);
        assertTrue(ffmpegLayer.parents().contains(execLayer), "ffmpeg's layer over exec's");

        Module execModule = execLayer.findModule("com.copybot.plugin.exec").orElseThrow();
        assertTrue(execModule.isExported("com.copybot.plugin.exec.process"), "the shared API");
        assertFalse(execModule.isExported("com.copybot.plugin.exec.actions"), "not an API");
        assertFalse(execModule.isExported("com.copybot.plugin.exec"), "not an API");

        for (PluginDefinition plugin : List.of(exec, ffmpeg)) {
            for (ActionDefinition<? extends IProcessAction> definition : plugin.getPluginInstance().getProcessActions()) {
                IProcessAction action = definition.getInstance();
                assertNotNull(action, plugin.getName() + " " + definition.actionCode());
                // the configuration record read by Gson and introspected for the editor
                action.loadConfig(JsonParser.parseString("{\"timeoutSeconds\": 5}"));
                assertTrue(action.configSchema().isPresent(), definition.actionCode());
            }
        }
        assertTrue(PluginEngine.catalog().actions().stream()
                        .anyMatch(a -> a.pluginCode().equals("ffmpeg") && a.actionCode().equals("ffmpeg.video")),
                "ffmpeg.video in the catalog");
        assertTrue(PluginEngine.catalog().actions().stream()
                        .anyMatch(a -> a.pluginCode().equals("exec") && a.actionCode().equals("exec.process")),
                "exec.process in the catalog");
    }

    private static PluginDefinition plugin(String module) {
        return PluginEngine.getAllPlugins().stream().filter(p -> module.equals(p.getName())).findFirst()
                .orElseThrow(() -> new AssertionError("not reported: " + PluginEngine.getAllPlugins()));
    }
}

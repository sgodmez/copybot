package com.copybot.plugin.notifyemail;

import com.copybot.engine.plugin.PluginDefinition;
import com.copybot.engine.plugin.PluginEngine;
import com.copybot.plugin.api.action.ActionDefinition;
import com.copybot.plugin.api.action.IRunEndAction;
import com.copybot.plugin.api.action.RunCounts;
import com.copybot.plugin.api.action.RunEndContext;
import com.copybot.plugin.api.action.RunOutcome;
import com.copybot.plugin.api.action.RunReport;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The packaged plugin loaded by the engine as the application loads it in development: the directory
 * {@code target} of this module, its {@code classes} and its {@code lib} (copied at package, hence an integration
 * test, run by failsafe after it). Jakarta Mail must then find Angus Mail, its implementation, and Angus
 * Activation among the modules of the plugin layer, from a thread of the engine whose context class loader is the
 * application's.
 */
public class EmailPluginIT {

    @Test
    public void thePackagedPluginLoadsAndSendsAnEmailFromItsLayer() throws Exception {
        Path target = Path.of("target").toAbsolutePath();
        assertTrue(Files.isDirectory(target.resolve("lib")), "packaged: target/lib");

        PluginEngine.load(Files.createTempDirectory("copybot-no-plugins"), List.of(target));

        PluginDefinition email = PluginEngine.getAllPlugins().stream()
                .filter(p -> "com.copybot.plugin.notifyemail".equals(p.getName()))
                .findFirst().orElseThrow(() -> new AssertionError("not reported: " + PluginEngine.getAllPlugins()));
        assertNull(email.getErrorMessage(), "loaded");
        assertTrue(email.isActive());
        ModuleLayer layer = email.getPluginInstance().getClass().getModule().getLayer();
        assertNotSame(ModuleLayer.boot(), layer, "the plugin of target/, in its own layer");
        assertTrue(layer.findModule("org.eclipse.angus.mail").isPresent(), "Angus Mail in the plugin layer");

        ActionDefinition<? extends IRunEndAction> send = email.getPluginInstance().getRunEndActions().stream()
                .filter(a -> a.actionCode().equals("email.send")).findFirst().orElseThrow();
        IRunEndAction action = send.getInstance();
        action.setPlugin(email.getPluginInstance());
        assertSame(layer, action.getClass().getModule().getLayer());

        try (TestSmtpServer server = new TestSmtpServer()) {
            action.loadConfig(JsonParser.parseString("{\"host\": \"127.0.0.1\", \"port\": " + server.port()
                    + ", \"security\": \"none\", \"from\": \"copybot@example.org\", \"to\": [\"me@example.org\"]}"));
            Instant start = Instant.parse("2026-10-30T10:00:00Z");
            RunReport report = new RunReport("holidays", Path.of("holidays.json"), RunOutcome.SUCCESS, start, start,
                    start.plusSeconds(5), new RunCounts(1, 0, 0, 0), List.of(), List.of(), List.of(), List.of(),
                    null, "run-1", Map.of());
            RunEndContext context = new RunEndContext() {
                @Override
                public void publish(String key, String value) {
                }

                @Override
                public void warn(String message) {
                }
            };

            // as the engine runs it: a virtual thread, the application's class loader as context class loader
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread thread = Thread.ofVirtual().start(() -> {
                try {
                    action.runEnded(report, context);
                } catch (Throwable t) {
                    failure.set(t);
                }
            });
            thread.join(30_000);

            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertEquals(1, server.messages.size());
            String data = server.messages.get(0).data();
            assertTrue(data.contains("multipart/alternative"), data);
            assertTrue(data.contains("text/html"), data);
        }
    }
}

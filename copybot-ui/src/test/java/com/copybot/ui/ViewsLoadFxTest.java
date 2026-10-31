package com.copybot.ui;

import com.copybot.engine.pipeline.StepType;
import com.copybot.engine.plugin.report.ActionEntry;
import com.copybot.engine.plugin.report.ModuleEntry;
import com.copybot.engine.plugin.report.PluginEntry;
import com.copybot.engine.plugin.report.PluginReport;
import com.copybot.engine.plugin.report.PluginSource;
import com.copybot.engine.plugin.report.PluginStatus;
import com.copybot.ui.util.Views;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The FXML views load with their controller (an FXML error only shows when the view is opened); skipped without display. */
public class ViewsLoadFxTest {

    private static final AtomicBoolean TOOLKIT = new AtomicBoolean();

    @BeforeAll
    static void startToolkit() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
            TOOLKIT.set(started.await(10, TimeUnit.SECONDS));
        } catch (IllegalStateException alreadyStarted) {
            TOOLKIT.set(true);
        } catch (RuntimeException | UnsatisfiedLinkError noDisplay) {
            TOOLKIT.set(false);
        }
        if (TOOLKIT.get()) {
            Platform.setImplicitExit(false);
        }
    }

    private static void assertLoads(String fxml) throws Exception {
        assumeTrue(TOOLKIT.get(), "no JavaFX toolkit (no display)");
        CompletableFuture<Object> controller = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                controller.complete(Views.load(fxml).controller());
            } catch (Throwable t) {
                controller.completeExceptionally(t);
            }
        });
        assertNotNull(controller.get(10, TimeUnit.SECONDS), fxml);
    }

    @Test
    public void thePlanViewLoads() throws Exception {
        assertLoads("plan-view.fxml");
    }

    @Test
    public void theEditorViewLoads() throws Exception {
        assertLoads("editor-view.fxml");
    }

    /** The Updates window of the application and its plugins, before anything is shown in it. */
    @Test
    public void thePluginsUpdatesViewLoads() throws Exception {
        assertLoads("updates-view.fxml");
    }

    /** The Plugins window, before a report is shown in it. */
    @Test
    public void thePluginsViewLoads() throws Exception {
        assertLoads("plugins-view.fxml");
    }

    /** The Plugins window shows the cards of a report: a working plugin, one that fails, the embedded one. */
    @Test
    public void thePluginsViewShowsAReport() throws Exception {
        assumeTrue(TOOLKIT.get(), "no JavaFX toolkit (no display)");
        PluginReport report = new PluginReport(Path.of("c.json"), Path.of("plugins"), List.of(Path.of("dev")),
                List.of("A warning"), List.of(
                new PluginEntry("com.example.broken", "1.0", PluginSource.DEV, Path.of("dev/broken"),
                        PluginStatus.ERROR, "It broke", List.of(new ModuleEntry("com.example.broken", "1.0", null,
                        true, false)), List.of("other 1.0"), List.of("com.lib"), List.of(), null),
                new PluginEntry("com.example.ok", "2.0", PluginSource.PLUGIN_PATH, Path.of("plugins/ok"),
                        PluginStatus.LOADED, null, List.of(), List.of(), List.of(),
                        List.of(new ActionEntry(StepType.ANALYZE, "check", "Check")), null),
                new PluginEntry("embedded", null, PluginSource.EMBEDDED, null, PluginStatus.LOADED, null, List.of(),
                        List.of(), List.of(), List.of(new ActionEntry(StepType.IN, "file.read", "Read")), null)));
        CompletableFuture<Object> shown = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                Views.Loaded<PluginsController> plugins = Views.load("plugins-view.fxml");
                plugins.controller().init(report);
                plugins.controller().showOfficial(Set.of(), false);
                shown.complete(plugins.root());
            } catch (Throwable t) {
                shown.completeExceptionally(t);
            }
        });
        assertNotNull(shown.get(10, TimeUnit.SECONDS));
    }

    /** Its Updates section, the button of the banner included, outside the packaged application. */
    @Test
    public void thePreferencesViewLoads() throws Exception {
        assertLoads("preferences-view.fxml");
    }
}

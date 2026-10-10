package com.copybot.ui;

import com.copybot.config.ConfigFiles;
import com.copybot.engine.plugin.report.PluginReport;
import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.util.PopinUtil;
import com.copybot.ui.util.UiPreferences;
import com.copybot.ui.util.Views;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.BorderPane;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;

/** The main window: the menu, and the home screen or the plan view of one pipeline in the center. */
public class MainController {

    /** The pipeline of the plan view, null on the home screen: kept when the view is reloaded (language). */
    private static Path shownPipeline;

    /** The plugins window, at most one, non modal: kept to bring it to front and refresh it. */
    private static Stage pluginsWindow;
    private static PluginsController pluginsController;

    @FXML
    private BorderPane root;

    @FXML
    private MenuItem preferencesItem;

    @FXML
    public void initialize() {
        // no file check here (JavaFX thread): the plan view reads the file in the background and reports a failure
        if (shownPipeline != null) {
            openPlan(shownPipeline); // a rebuild (language change): the recents are not touched again
        } else {
            showHome();
        }
    }

    public void showHome() {
        shownPipeline = null;
        setBusy(false);
        Views.Loaded<HomeController> home = Views.load("home-view.fxml");
        home.controller().init(this);
        root.setCenter(home.root());
    }

    /** Opens the plan view of the pipeline, nothing prepared (spec desktop-ui §1), and makes it the most recent. */
    public void showPlan(Path pipeline) {
        UiPreferences.updateRecents(recents -> recents.touch(pipeline, Instant.now()));
        openPlan(pipeline);
    }

    private void openPlan(Path pipeline) {
        shownPipeline = pipeline.toAbsolutePath().normalize();
        setBusy(false);
        Views.Loaded<PlanController> plan = Views.load("plan-view.fxml");
        plan.controller().init(this, shownPipeline);
        root.setCenter(plan.root());
    }

    /** While a preparation or a copy runs the language cannot change (the view would be rebuilt). */
    public void setBusy(boolean busy) {
        preferencesItem.setDisable(busy);
    }

    @FXML
    protected void onExitClick() {
        Platform.exit(); // triggers Application.stop(): executor shutdown + engine close
    }

    @FXML
    protected void onPreferencesClick() {
        try {
            Views.Loaded<PreferencesController> preferences = Views.load("preferences-view.fxml");
            Stage dialog = new Stage();
            dialog.setTitle(ResourcesEngine.getString("pref.title"));
            dialog.setScene(new Scene(preferences.root()));
            dialog.initModality(Modality.APPLICATION_MODAL);
            dialog.initOwner(CopybotMainUi.STAGE);
            dialog.showAndWait();
        } catch (RuntimeException e) {
            PopinUtil.showError(e);
        }
    }

    /**
     * The resources window: the disks, their capacity and their groups (taken from the next plan), with those of the
     * pipeline open in the plan view.
     */
    @FXML
    protected void onResourcesClick() {
        try {
            Views.Loaded<ResourcesController> resources = Views.load("resources-view.fxml");
            resources.controller().open(shownPipeline); // its disks shown too, when a pipeline is open
            Stage dialog = new Stage();
            dialog.setTitle(ResourcesEngine.getString("resources.title"));
            dialog.setScene(new Scene(resources.root()));
            dialog.initModality(Modality.APPLICATION_MODAL);
            dialog.initOwner(CopybotMainUi.STAGE);
            dialog.showAndWait();
        } catch (RuntimeException e) {
            PopinUtil.showError(e);
        }
    }

    /** Opens the plugins window, or refreshes it when open (the folder may have changed in the preferences). */
    @FXML
    protected void onPluginsClick() {
        if (pluginsWindow != null && pluginsWindow.isShowing()) {
            pluginsWindow.toFront();
        }
        try {
            // the report checks directories and the configuration file is read: off the JavaFX thread
            CopybotMainUi.executor.submit(() -> {
                PluginReport report;
                try {
                    report = CopybotMainUi.ENGINE.pluginReport();
                } catch (Throwable t) { // an Error too: the click must not be lost silently
                    Platform.runLater(() -> PopinUtil.showError(t instanceof Exception e ? e : new IllegalStateException(t.toString(), t)));
                    return;
                }
                Optional<Path> next = nextPluginPath();
                Platform.runLater(() -> showPlugins(report, next));
            });
        } catch (RejectedExecutionException e) {
            // the application is stopping
        }
    }

    /** The directory the next start would load, empty when the configuration file cannot be read now. */
    private static Optional<Path> nextPluginPath() {
        try {
            // a relative path (and the default ./plugins) resolves against the working directory
            return Optional.of(ConfigFiles.readPluginPath(CopybotMainUi.ENGINE.configFile())
                    .map(p -> p.toAbsolutePath().normalize())
                    .orElse(Path.of("plugins").toAbsolutePath().normalize()));
        } catch (RuntimeException e) {
            return Optional.empty(); // the file changed under us since startup: unknown, no banner
        }
    }

    private void showPlugins(PluginReport report, Optional<Path> next) {
        if (pluginsWindow != null && pluginsWindow.isShowing()) {
            pluginsController.init(report, next);
            return;
        }
        try {
            Views.Loaded<PluginsController> plugins = Views.load("plugins-view.fxml");
            plugins.controller().init(report, next);
            Stage window = new Stage();
            window.setTitle(ResourcesEngine.getString("plugins.title"));
            window.getIcons().setAll(CopybotMainUi.STAGE.getIcons());
            window.setScene(new Scene(plugins.root()));
            window.initOwner(CopybotMainUi.STAGE);
            window.show();
            pluginsWindow = window;
            pluginsController = plugins.controller();
        } catch (RuntimeException e) {
            PopinUtil.showError(e);
        }
    }
}

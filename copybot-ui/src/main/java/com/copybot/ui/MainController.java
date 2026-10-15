package com.copybot.ui;

import com.copybot.config.ConfigFiles;
import com.copybot.engine.plugin.report.PluginReport;
import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.util.AppInfo;
import com.copybot.ui.util.Background;
import com.copybot.ui.util.PopinUtil;
import com.copybot.ui.util.UiPreferences;
import com.copybot.ui.util.Views;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/** The main window: the menu, and the home screen or the plan view of one pipeline in the center. */
public class MainController {

    /** The pipeline of the plan view, null on the home screen: kept when the view is reloaded (language). */
    private static Path shownPipeline;

    /** The plugins window, at most one, non modal: kept to bring it to front and refresh it. */
    private static Stage pluginsWindow;
    private static PluginsController pluginsController;
    /** The language the plugins window was built in: built again after a change. */
    private static Locale pluginsLocale;

    /** A preparation or a copy runs in the plan view (see {@link #setBusy}). */
    private boolean busy;

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
        if (pluginsWindow != null && pluginsWindow.isShowing() && !Locale.getDefault().equals(pluginsLocale)) {
            pluginsWindow.close(); // a language change: the window is built again in the new language
            pluginsWindow = null;
            pluginsController = null;
            onPluginsClick();
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
        this.busy = busy;
        preferencesItem.setDisable(busy);
    }

    /** Exiting then asks first: the operation would be stopped. */
    boolean isBusy() {
        return busy;
    }

    @FXML
    protected void onExitClick() {
        CopybotMainUi.requestExit(); // the same way out as closing the window
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

    /** What the plugins window shows. */
    private record PluginsInfo(PluginReport report, Optional<Path> next) {
    }

    /** Opens the plugins window, or refreshes it when open (the folder may have changed in the preferences). */
    @FXML
    protected void onPluginsClick() {
        if (pluginsWindow != null && pluginsWindow.isShowing()) {
            pluginsWindow.toFront();
        }
        // the report checks directories and the configuration file is read: off the JavaFX thread
        Background.run(() -> new PluginsInfo(CopybotMainUi.ENGINE.pluginReport(), nextPluginPath()),
                info -> showPlugins(info.report(), info.next()),
                PopinUtil::showError); // an Error too: the click must not be lost silently
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
            pluginsLocale = Locale.getDefault();
        } catch (RuntimeException e) {
            PopinUtil.showError(e);
        }
    }

    /** Help > About: the versions of Copybot, Java and JavaFX, the licence and the source code. */
    @FXML
    protected void onAboutClick() {
        Label name = new Label(AppInfo.NAME + " " + AppInfo.version());
        name.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        Label versions = new Label(ResourcesEngine.getString("about.java", AppInfo.javaVersion()) + "\n"
                + ResourcesEngine.getString("about.javafx", AppInfo.javafxVersion()));
        Label license = new Label(ResourcesEngine.getString("about.license"));
        license.setWrapText(true);
        Hyperlink source = new Hyperlink(AppInfo.SOURCE_URL);
        source.setOnAction(e -> CopybotMainUi.HOST_SERVICES.showDocument(AppInfo.SOURCE_URL));
        VBox content = new VBox(8, name, versions, license, source);
        content.setPrefWidth(420);

        Alert about = new Alert(Alert.AlertType.NONE, "", ButtonType.CLOSE);
        about.initOwner(CopybotMainUi.STAGE);
        about.setTitle(ResourcesEngine.getString("about.title"));
        about.setHeaderText(null);
        ImageView icon = new ImageView(CopybotMainUi.STAGE.getIcons().getFirst());
        icon.setFitWidth(64);
        icon.setPreserveRatio(true);
        about.setGraphic(icon);
        about.getDialogPane().setContent(content);
        about.showAndWait();
    }
}

package com.copybot.ui;

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
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;

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
    private volatile boolean busy;

    @FXML
    private BorderPane root;

    @FXML
    private MenuItem preferencesItem;

    @FXML
    private HBox updateBanner;
    @FXML
    private Label updateLabel;
    @FXML
    private Button updateInstallButton;
    @FXML
    private Label updateReason;

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
        refreshUpdateBanner(); // no update while an operation runs
    }

    /** Exiting then asks first: the operation would be stopped. */
    boolean isBusy() {
        return busy;
    }

    @FXML
    protected void onExitClick() {
        CopybotMainUi.requestExit(); // the same way out as closing the window
    }

    /**
     * Shows the banner of a newer release (spec app-update §3), unless closed this session; its "Update" button is
     * disabled with the reason while an operation runs, and outside Windows.
     */
    void refreshUpdateBanner() {
        var release = UpdateUi.offered();
        updateBanner.setVisible(release.isPresent());
        updateBanner.setManaged(release.isPresent());
        if (release.isEmpty()) {
            return;
        }
        updateLabel.setText(ResourcesEngine.getString("update.available", release.get().version()));
        var blocked = UpdateUi.service().flatMap(service -> service.installBlocked());
        updateInstallButton.setDisable(blocked.isPresent());
        updateReason.setText(blocked.map(key -> ResourcesEngine.getString(key)).orElse(""));
    }

    @FXML
    protected void onUpdateInstallClick() {
        UpdateUi.offered().ifPresent(UpdateUi::install);
    }

    @FXML
    protected void onUpdateNotesClick() {
        UpdateUi.offered().ifPresent(release ->
                CopybotMainUi.HOST_SERVICES.showDocument(release.notesUrl().toString()));
    }

    @FXML
    protected void onUpdateDismissClick() {
        UpdateUi.dismiss();
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

    /** Opens the plugins window, or refreshes it when open. */
    @FXML
    protected void onPluginsClick() {
        if (pluginsWindow != null && pluginsWindow.isShowing()) {
            pluginsWindow.toFront();
        }
        // the report checks directories: off the JavaFX thread
        Background.run(CopybotMainUi.ENGINE::pluginReport,
                this::showPlugins,
                PopinUtil::showError); // an Error too: the click must not be lost silently
    }

    private void showPlugins(PluginReport report) {
        if (pluginsWindow != null && pluginsWindow.isShowing()) {
            pluginsController.init(report);
            return;
        }
        try {
            Views.Loaded<PluginsController> plugins = Views.load("plugins-view.fxml");
            plugins.controller().init(report);
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

    /** Help > About: the versions of Copybot, Java and JavaFX, the licence, the data attribution and the source code. */
    @FXML
    protected void onAboutClick() {
        Label name = new Label(AppInfo.NAME + " " + AppInfo.version());
        name.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        Label versions = new Label(ResourcesEngine.getString("about.java", AppInfo.javaVersion()) + "\n"
                + ResourcesEngine.getString("about.javafx", AppInfo.javafxVersion()));
        Label license = new Label(ResourcesEngine.getString("about.license"));
        license.setWrapText(true);
        // ODbL attribution of the time zone data of the gps-zone plugin (see NOTICE)
        Label gpsZoneData = new Label(ResourcesEngine.getString("about.gps-zone-data"));
        gpsZoneData.setWrapText(true);
        Hyperlink source = new Hyperlink(AppInfo.SOURCE_URL);
        source.setOnAction(e -> CopybotMainUi.HOST_SERVICES.showDocument(AppInfo.SOURCE_URL));
        VBox content = new VBox(8, name, versions, license, gpsZoneData, source);
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

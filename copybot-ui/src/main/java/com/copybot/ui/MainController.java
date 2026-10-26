package com.copybot.ui;

import com.copybot.engine.plugin.report.PluginReport;
import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.plugins.PluginChoices;
import com.copybot.ui.plugins.PluginService;
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
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;

/** The main window: the menu, and the home screen or the plan view of one pipeline in the center. */
public class MainController {

    /** The pipeline of the plan view, null on the home screen: kept when the view is reloaded (language). */
    private static Path shownPipeline;

    /** The plugins window, at most one, non modal: kept to bring it to front and refresh it. */
    private static Stage pluginsWindow;
    private static PluginsController pluginsController;
    /** The language the plugins window was built in: built again after a change. */
    private static Locale pluginsLocale;
    /** The choices of a Plugins window closed to be opened again (over an editor, in a new language). */
    private static PluginChoices carriedChoices;

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
    private Hyperlink updateNotesLink;

    @FXML
    public void initialize() {
        // no file check here (JavaFX thread): the plan view reads the file in the background and reports a failure
        if (shownPipeline != null) {
            openPlan(shownPipeline); // a rebuild (language change): the recents are not touched again
        } else {
            showHome();
        }
        if (pluginsWindow != null && pluginsWindow.isShowing() && !Locale.getDefault().equals(pluginsLocale)) {
            carriedChoices = pluginsController.choices();
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
        if (pluginsController != null) {
            pluginsController.refreshActions(); // nor plugin changes
        }
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
     * Shows the banner of a newer release (spec app-update §3) and of the plugin updates (spec plugin-install §5),
     * unless closed this session. With a release its button updates Copybot (the application first), disabled with
     * the reason while an operation runs, and outside Windows; with plugin updates only, it opens the Plugins window
     * with these updates chosen.
     */
    void refreshUpdateBanner() {
        var release = UpdateUi.offered();
        long pluginUpdates = UpdateUi.offeredPluginUpdates();
        boolean shown = release.isPresent() || pluginUpdates > 0;
        updateBanner.setVisible(shown);
        updateBanner.setManaged(shown);
        if (!shown) {
            return;
        }
        updateLabel.setText(UpdateUi.checkText(new PluginService.Check(release, pluginUpdates)));
        updateNotesLink.setVisible(release.isPresent());
        updateNotesLink.setManaged(release.isPresent());
        if (release.isEmpty()) {
            updateInstallButton.setText(ResourcesEngine.getString("plugins.show-updates"));
            updateInstallButton.setDisable(false);
            updateReason.setText("");
            return;
        }
        updateInstallButton.setText(ResourcesEngine.getString("update.install"));
        var blocked = UpdateUi.service().flatMap(service -> service.installBlocked());
        updateInstallButton.setDisable(blocked.isPresent());
        updateReason.setText(blocked.map(key -> ResourcesEngine.getString(key)).orElse(""));
    }

    @FXML
    protected void onUpdateInstallClick() {
        var release = UpdateUi.offered();
        if (release.isPresent()) {
            UpdateUi.install(release.get());
        } else if (UpdateUi.offeredPluginUpdates() > 0) {
            openPlugins(Set.of(), true, null);
        }
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
        openPlugins(Set.of(), false, null);
    }

    /**
     * Opens the plugins window, or refreshes it when open, with the install or update of these modules chosen
     * (a plugin a pipeline needs) and, with {@code updates}, every update chosen (the banner).
     *
     * @param modalOwner a modal window the Plugins window is opened from (an editor): the Plugins window is then
     *                   opened over it, modal too (a window under a modal one takes no input)
     */
    void openPlugins(Collection<String> modules, boolean updates, Window modalOwner) {
        if (modalOwner != null && pluginsWindow != null && pluginsWindow.isShowing()
                && pluginsWindow.getOwner() != modalOwner) {
            carriedChoices = pluginsController.choices(); // the choices not applied yet follow the window
            pluginsWindow.close(); // under the modal window: opened again over it
            pluginsWindow = null;
            pluginsController = null;
        }
        if (pluginsWindow != null && pluginsWindow.isShowing()) {
            pluginsWindow.toFront();
        }
        // the report checks directories: off the JavaFX thread
        Background.run(CopybotMainUi.ENGINE::pluginReport,
                report -> showPlugins(report, modules, updates, modalOwner),
                PopinUtil::showError); // an Error too: the click must not be lost silently
    }

    private void showPlugins(PluginReport report, Collection<String> modules, boolean updates, Window modalOwner) {
        if (pluginsWindow != null && pluginsWindow.isShowing()) {
            pluginsController.init(report);
            pluginsController.showOfficial(modules, updates);
            return;
        }
        try {
            Views.Loaded<PluginsController> plugins = Views.load("plugins-view.fxml");
            plugins.controller().init(report);
            if (carriedChoices != null) {
                plugins.controller().useChoices(carriedChoices); // the request below is added to them
                carriedChoices = null;
            }
            if (plugins.root() instanceof Region region) { // a small screen: the window fits it
                double available = Screen.getPrimary().getVisualBounds().getHeight() - 60;
                region.setPrefHeight(Math.min(region.getPrefHeight(), Math.max(400, available)));
            }
            Stage window = new Stage();
            window.setTitle(ResourcesEngine.getString("plugins.title"));
            window.getIcons().setAll(CopybotMainUi.STAGE.getIcons());
            window.setScene(new Scene(plugins.root()));
            if (modalOwner != null) {
                window.initOwner(modalOwner);
                window.initModality(Modality.WINDOW_MODAL);
            } else {
                window.initOwner(CopybotMainUi.STAGE);
            }
            window.setOnHidden(e -> {
                if (pluginsWindow == window) {
                    pluginsWindow = null;
                    pluginsController = null;
                }
            });
            window.show();
            pluginsWindow = window;
            pluginsController = plugins.controller();
            pluginsLocale = Locale.getDefault();
            plugins.controller().showOfficial(modules, updates);
        } catch (RuntimeException e) {
            PopinUtil.showError(e);
        }
    }

    /**
     * Help > About: the versions of Copybot, Java, JavaFX and the plugin API, the licence, the data attribution and
     * the source code.
     */
    @FXML
    protected void onAboutClick() {
        Label name = new Label(AppInfo.NAME + " " + AppInfo.version());
        name.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        Label versions = new Label(ResourcesEngine.getString("about.java", AppInfo.javaVersion()) + "\n"
                + ResourcesEngine.getString("about.javafx", AppInfo.javafxVersion()) + "\n"
                + ResourcesEngine.getString("about.plugin-api", AppInfo.pluginApiVersion()));
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

package com.copybot.ui;

import com.copybot.engine.plugin.report.PluginReport;
import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.model.PipelineDocument;
import com.copybot.ui.plugins.PluginChoices;
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
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;

/** The main window: the menu, and the home screen or the plan view of one pipeline in the center. */
public class MainController {

    /** The pipeline of the plan view, null on the home screen: kept when the view is reloaded (language). */
    private static Path shownPipeline;
    /**
     * The plan view shown, null on the home screen: told when it is replaced, also by the view of a rebuilt window
     * (language), so that its plan is discarded in the engine.
     */
    private static PlanController shownPlan;

    /** The plugins window, at most one, non modal: kept to bring it to front and refresh it. */
    private static Stage pluginsWindow;
    private static PluginsController pluginsController;
    /** The language the plugins window was built in: built again after a change. */
    private static Locale pluginsLocale;
    /** The choices of a Plugins window closed to be opened again (over an editor, in a new language). */
    private static PluginChoices carriedChoices;

    /** The Updates window, at most one, non modal: kept to bring it to front and refresh it. */
    private static Stage updatesWindow;
    private static UpdatesController updatesController;
    /** The language the Updates window was built in: built again after a change. */
    private static Locale updatesLocale;

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
        if (updatesWindow != null && updatesWindow.isShowing() && !Locale.getDefault().equals(updatesLocale)) {
            updatesWindow.close(); // a language change: the window is built again in the new language
            openUpdates();
        }
    }

    public void showHome() {
        shownPipeline = null;
        leavePlan();
        setBusy(false);
        Views.Loaded<HomeController> home = Views.load("home-view.fxml");
        home.controller().init(this);
        root.setCenter(home.root());
    }

    /**
     * Asks for a pipeline file (*.json) and opens its plan view: the "Open a pipeline" button of the home screen and
     * the File menu item. A resume cursor (.state.json, listed by the same filter) is refused with a message.
     */
    void openPipelineChooser() {
        FileChooser chooser = new FileChooser();
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                ResourcesEngine.getString("home.file-filter"), "*.json"));
        File file = chooser.showOpenDialog(CopybotMainUi.STAGE);
        if (file == null) {
            return;
        }
        if (PipelineDocument.isResumeCursor(file.toPath())) { // "*.json" lists the engine's resume cursors too
            Alert alert = new Alert(Alert.AlertType.WARNING,
                    ResourcesEngine.getString("home.state-file", file.getName()), ButtonType.OK);
            alert.initOwner(CopybotMainUi.STAGE);
            alert.showAndWait();
            return;
        }
        showPlan(file.toPath());
    }

    /** "Open a pipeline" of the File menu: from any screen, as the button of the home screen. */
    @FXML
    protected void onOpenPipelineClick() {
        openPipelineChooser();
    }

    /** Opens the plan view of the pipeline, nothing prepared (spec desktop-ui §1), and makes it the most recent. */
    public void showPlan(Path pipeline) {
        UiPreferences.updateRecents(recents -> recents.touch(pipeline, Instant.now()));
        openPlan(pipeline);
    }

    private void openPlan(Path pipeline) {
        shownPipeline = pipeline.toAbsolutePath().normalize();
        leavePlan();
        setBusy(false);
        Views.Loaded<PlanController> plan = Views.load("plan-view.fxml");
        plan.controller().init(this, shownPipeline);
        shownPlan = plan.controller();
        root.setCenter(plan.root());
    }

    /** The plan view shown, if any, is replaced: its plan is discarded. */
    private static void leavePlan() {
        if (shownPlan != null) {
            shownPlan.leave();
            shownPlan = null;
        }
    }

    /** While a preparation or a copy runs the language cannot change (the view would be rebuilt). */
    public void setBusy(boolean busy) {
        this.busy = busy;
        preferencesItem.setDisable(busy);
        refreshUpdateBanner(); // no update while an operation runs
        if (pluginsController != null) {
            pluginsController.refreshActions(); // nor plugin changes
        }
        if (updatesController != null) {
            updatesController.refreshActions();
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
     * unless closed this session: what is available, and "View", which opens the Updates window where it is chosen
     * and applied.
     */
    void refreshUpdateBanner() {
        var release = UpdateUi.offered();
        long pluginUpdates = UpdateUi.offeredPluginUpdates();
        boolean shown = release.isPresent() || pluginUpdates > 0;
        updateBanner.setVisible(shown);
        updateBanner.setManaged(shown);
        if (shown) {
            updateLabel.setText(UpdateUi.bannerText(release, pluginUpdates));
        }
    }

    /** "View" of the banner and the Edit menu item: the Updates window. */
    @FXML
    protected void onUpdatesClick() {
        openUpdates();
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
     * Opens the Updates window (spec updates-window), or brings it to front and shows it again when open: everything
     * updatable is ticked again with what the last check found.
     */
    void openUpdates() {
        openUpdates(null);
    }

    /**
     * Same, from a modal window (the Plugins window opened over an editor): the Updates window is then opened over
     * it, modal too (a window under a modal one takes no input).
     */
    void openUpdates(Window modalOwner) {
        if (modalOwner != null && updatesWindow != null && updatesWindow.isShowing()
                && updatesWindow.getOwner() != modalOwner) {
            updatesWindow.close(); // under the modal window: opened again over it
        }
        if (updatesWindow != null && updatesWindow.isShowing()) {
            updatesWindow.toFront();
            updatesController.show();
            return;
        }
        try {
            Views.Loaded<UpdatesController> updates = Views.load("updates-view.fxml");
            Stage window = new Stage();
            window.setTitle(ResourcesEngine.getString("updates.title"));
            window.getIcons().setAll(CopybotMainUi.STAGE.getIcons());
            window.setScene(new Scene(updates.root()));
            if (modalOwner != null) {
                window.initOwner(modalOwner);
                window.initModality(Modality.WINDOW_MODAL);
            } else {
                window.initOwner(CopybotMainUi.STAGE);
            }
            window.setOnHidden(e -> {
                if (updatesWindow == window) {
                    updatesWindow = null;
                    updatesController = null;
                }
            });
            window.show();
            updatesWindow = window;
            updatesController = updates.controller();
            updatesLocale = Locale.getDefault();
            updates.controller().show();
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

package com.copybot.ui;

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

/** The main window: the menu, and the home screen or the plan view of one pipeline in the center. */
public class MainController {

    /** The pipeline of the plan view, null on the home screen: kept when the view is reloaded (language). */
    private static Path shownPipeline;

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
}

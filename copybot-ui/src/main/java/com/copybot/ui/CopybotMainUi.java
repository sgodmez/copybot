package com.copybot.ui;

import com.copybot.config.AppFolders;
import com.copybot.engine.CopybotEngine;
import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.util.AppLogging;
import com.copybot.ui.util.Background;
import com.copybot.ui.util.PopinUtil;
import com.copybot.ui.util.UiPreferences;
import com.copybot.ui.util.Views;
import javafx.application.Application;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

public class CopybotMainUi extends Application {
    private static final System.Logger LOG = System.getLogger(CopybotMainUi.class.getName());

    public static Stage STAGE;

    /** The engine of this window: created at startup, closed on exit (one pipeline at a time). */
    public static CopybotEngine ENGINE;

    /** For opening folders from the views; set at startup. */
    public static HostServices HOST_SERVICES;

    /** The controller of the main view shown: tells whether a preparation or a copy runs. */
    private static MainController mainController;

    /** Set once the exit is decided (JavaFX thread); read by the error popups from any thread. */
    private static volatile boolean exiting;


    @Override
    public void start(Stage stage) {
        HOST_SERVICES = getHostServices();
        var params = getParameters();
        Optional<Path> pathArg = Optional.ofNullable(params.getNamed().get("config-file")).map(Path::of);

        try {
            AppFolders folders = AppFolders.resolve(pathArg);
            AppLogging.install(folders.logsDir());
            UiPreferences.use(folders.prefsFile());
            ResourcesEngine.registerBundle("com.copybot.ui.i18n.uiBundle");
            ResourcesEngine.addSupportedLocale(Locale.ITALIAN); // the UI ships an it bundle
            UiPreferences.savedLanguage().ifPresent(ResourcesEngine::loadLanguage);
            ENGINE = CopybotEngine.create(folders);
        } catch (Exception e) {
            PopinUtil.showError(e);
            System.exit(1);
        }

        STAGE = stage;
        Scene scene = new Scene(loadMainView()); // the home screen: nothing runs at startup (spec desktop-ui §1)
        stage.setMaximized(true);
        stage.setTitle("Copybot");
        stage.getIcons().add(new Image(CopybotMainUi.class.getResourceAsStream("Copybot.png")));
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> {
            e.consume(); // the window stays until the exit is decided, and done
            requestExit();
        });
        stage.show();
    }

    /**
     * Rebuilds the main view with the current resource bundle (e.g. after a language change): the home
     * screen, or the plan view of the same pipeline, not prepared.
     */
    public static void reloadMainView() {
        try {
            STAGE.getScene().setRoot(loadMainView());
        } catch (RuntimeException e) {
            PopinUtil.showError(e);
        }
    }

    private static Parent loadMainView() {
        Views.Loaded<MainController> main = Views.load("main-view.fxml");
        mainController = main.controller();
        return main.root();
    }

    /** The exit is decided: the failures of the operations being stopped are logged, not shown. */
    public static boolean isExiting() {
        return exiting;
    }

    /**
     * The one way out (the window closed, File > Exit), on the JavaFX thread: the open editors ask about their
     * unsaved changes, a running preparation or copy is stopped only once the user confirms it. The engine is then
     * closed in the background (the operation is cancelled and waited for, within its grace period) while a small
     * window says so, then the application ends.
     */
    static void requestExit() {
        if (exiting || !EditorController.closeAllForExit()) {
            return;
        }
        boolean busy = mainController != null && mainController.isBusy();
        if (busy && !confirmStop()) {
            return;
        }
        exiting = true;
        STAGE.getScene().getRoot().setDisable(true);
        if (busy) {
            showStopping();
        }
        Background.run(() -> {
                    if (ENGINE != null) {
                        ENGINE.close(); // cancels a running preparation or copy and waits for it to release its files
                    }
                    return null;
                },
                closed -> Platform.exit(),
                failure -> {
                    LOG.log(System.Logger.Level.WARNING, "the engine did not close cleanly", failure);
                    Platform.exit();
                });
    }

    private static boolean confirmStop() {
        ButtonType stop = new ButtonType(ResourcesEngine.getString("exit.stop"), ButtonBar.ButtonData.OK_DONE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, ResourcesEngine.getString("exit.running"),
                stop, ButtonType.CANCEL);
        alert.initOwner(STAGE);
        alert.setTitle(ResourcesEngine.getString("exit.title"));
        alert.setHeaderText(null);
        return alert.showAndWait().filter(choice -> choice == stop).isPresent();
    }

    /** Not modal, cannot be closed: it goes away with the application. */
    private static void showStopping() {
        HBox content = new HBox(12, new ProgressIndicator(), new Label(ResourcesEngine.getString("exit.stopping")));
        content.setAlignment(Pos.CENTER_LEFT);
        content.setPadding(new Insets(16));
        Stage stopping = new Stage(StageStyle.UTILITY);
        stopping.initOwner(STAGE);
        stopping.setTitle(ResourcesEngine.getString("exit.title"));
        stopping.setResizable(false);
        stopping.setScene(new Scene(content));
        stopping.setOnCloseRequest(e -> e.consume());
        stopping.show();
    }

    /**
     * After {@link #requestExit()} the engine is already closed; on another way out (the session of the system
     * ending) it is closed here, on the JavaFX thread.
     */
    @Override
    public void stop() {
        exiting = true;
        Background.shutdown();
        if (ENGINE != null) {
            ENGINE.close(); // idempotent: returns at once when already closed
        }
    }

    public static void main(String[] args) {
        launch(args);
    }


}

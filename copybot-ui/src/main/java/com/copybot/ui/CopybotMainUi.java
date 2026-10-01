package com.copybot.ui;

import com.copybot.engine.CopybotEngine;
import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.util.PopinUtil;
import com.copybot.ui.util.UiPreferences;
import com.copybot.ui.util.Views;
import javafx.application.Application;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CopybotMainUi extends Application {
    public static Stage STAGE;

    /** The engine of this window: created at startup, closed on exit (one pipeline at a time). */
    public static CopybotEngine ENGINE;

    /**
     * The background work of the views (engine operations, file checks), never on the JavaFX thread.
     * Daemon threads: a file check stuck on a disconnected share must not keep the application alive;
     * the engine operations are waited for by {@link CopybotEngine#close()} in {@link #stop()}.
     */
    public static ExecutorService executor;


    @Override
    public void start(Stage stage) {
        var params = getParameters();
        Optional<Path> pathArg = Optional.ofNullable(params.getNamed().get("config-file")).map(Path::of);

        try {
            ResourcesEngine.registerBundle("com.copybot.ui.i18n.uiBundle");
            ResourcesEngine.addSupportedLocale(Locale.ITALIAN); // the UI ships an it bundle
            UiPreferences.savedLanguage().ifPresent(ResourcesEngine::loadLanguage);
            ENGINE = CopybotEngine.create(pathArg);
        } catch (Exception e) {
            PopinUtil.showError(e);
            System.exit(1);
        }

        STAGE = stage;
        executor = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "copybot-ui-background");
            thread.setDaemon(true);
            return thread;
        });
        Scene scene = new Scene(loadMainView()); // the home screen: nothing runs at startup (spec desktop-ui §1)
        stage.setMaximized(true);
        stage.setTitle("Copybot");
        stage.getIcons().add(new Image(CopybotMainUi.class.getResourceAsStream("Copybot.png")));
        stage.setScene(scene);
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
        return Views.load("main-view.fxml").root();
    }

    @Override
    public void stop() {
        if (executor != null) {
            executor.shutdown();
        }
        if (ENGINE != null) {
            ENGINE.close(); // cancels a running preparation or copy and waits for it to release its files
        }
    }

    public static void main(String[] args) {
        launch(args);
    }


}

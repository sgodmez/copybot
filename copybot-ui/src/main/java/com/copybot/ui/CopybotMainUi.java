package com.copybot.ui;

import com.copybot.engine.CopybotEngine;
import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.util.PopinUtil;
import com.copybot.ui.util.UiPreferences;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CopybotMainUi extends Application {
    public static Stage STAGE;

    public static ExecutorService executor;


    @Override
    public void start(Stage stage) throws IOException {
        var params = getParameters();
        Optional<Path> pathArg = Optional.ofNullable(params.getNamed().get("config-file")).map(Path::of);

        try {
            ResourcesEngine.registerBundle("com.copybot.ui.i18n.uiBundle");
            ResourcesEngine.addSupportedLocale(Locale.ITALIAN); // the UI ships an it bundle
            UiPreferences.savedLanguage().ifPresent(ResourcesEngine::loadLanguage);
            CopybotEngine.init(pathArg);
        } catch (Exception e) {
            PopinUtil.showError(e);
            System.exit(1);
        }

        STAGE = stage;
        Scene scene = new Scene(loadMainView());
        stage.setMaximized(true);
        stage.setTitle("Copybot");
        stage.getIcons().add(new Image(CopybotMainUi.class.getResourceAsStream("Copybot.png")));
        stage.setScene(scene);
        stage.show();

        executor = Executors.newCachedThreadPool();

    }

    /** Rebuilds the main view with the current resource bundle (e.g. after a language change). */
    public static void reloadMainView() {
        try {
            STAGE.getScene().setRoot(loadMainView());
        } catch (IOException e) {
            PopinUtil.showError(e);
        }
    }

    private static Parent loadMainView() throws IOException {
        FXMLLoader fxmlLoader = new FXMLLoader(CopybotMainUi.class.getResource("views/hello-view.fxml"));
        fxmlLoader.setResources(ResourcesEngine.getResourceBundle());
        return fxmlLoader.load();
    }

    @Override
    public void stop() {
        if (executor != null) {
            executor.shutdown();
        }
        CopybotEngine.destroy();
    }

    public static void main(String[] args) {
        launch(args);
    }


}

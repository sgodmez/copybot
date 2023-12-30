package com.copybot.ui;

import com.copybot.engine.CopybotEngine;
import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.util.PopinUtil;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Path;
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
            //Locale.setDefault(Locale.ENGLISH);
            ResourcesEngine.registerBundle("com.copybot.ui.i18n.uiBundle");
            //ResourcesEngine.loadLanguage(Locale.ENGLISH);
            CopybotEngine.init(pathArg);
        } catch (Exception e) {
            PopinUtil.showError(e);
            System.exit(1);
        }

        STAGE = stage;
        //var plugin = PluginEngine.getLoadedPlugins().get(1).getPluginInstance();
        //FXMLLoader fxmlLoader = new FXMLLoader(plugin.getClass().getResource("views/test-view.fxml"));
        FXMLLoader fxmlLoader = new FXMLLoader(CopybotMainUi.class.getResource("views/hello-view.fxml"));
        fxmlLoader.setResources(ResourcesEngine.getResourceBundle());
        Scene scene = new Scene(fxmlLoader.load());
        stage.setMaximized(true);
        stage.setTitle("Copybot");
        stage.getIcons().add(new Image(CopybotMainUi.class.getResourceAsStream("Copybot.png")));
        stage.setScene(scene);
        stage.show();

        executor = Executors.newCachedThreadPool();

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
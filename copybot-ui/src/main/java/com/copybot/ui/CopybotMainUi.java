package com.copybot.ui;

import com.copybot.engine.CopybotEngine;
import com.copybot.engine.resources.ResourcesEngine;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CopybotMainUi extends Application {
    public static Stage STAGE;

    public static ExecutorService executor;

    //public String getSizeHR() {
    //       return FileUtil.toAutoUnitSize(getSize(),2);
    //    }

    @Override
    public void start(Stage stage) throws IOException {
        STAGE = stage;
        FXMLLoader fxmlLoader = new FXMLLoader(CopybotMainUi.class.getResource("views/hello-view.fxml"));
        //Locale.setDefault(Locale.ENGLISH);
        ResourcesEngine.registerBundle("com.copybot.ui.i18n.uiBundle");
        ResourcesEngine.loadLanguage(Locale.GERMAN);
        fxmlLoader.setResources(ResourcesEngine.getResourceBundle());
        Scene scene = new Scene(fxmlLoader.load(), 320, 240);
        stage.setTitle("Hello!");
        stage.setScene(scene);
        stage.show();

        executor = Executors.newCachedThreadPool();
        CopybotEngine.test();
    }

    @Override
    public void stop() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    public static void main(String[] args) {
        launch();
    }


}
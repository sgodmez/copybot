package com.copybot.ui;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.IOException;

public class HelloController2 {

    @FXML
    public void initialize() {
    }

    @FXML
    protected void onTestButtonClick() throws IOException {
        FXMLLoader fxmlLoader = new FXMLLoader(CopybotMainUi.class.getResource("hello-view2.fxml"));

        Scene secondScene = new Scene(fxmlLoader.load(), 230, 100);

        // New window (Stage)
        Stage newWindow = new Stage();
        newWindow.setTitle("Second Stage");
        newWindow.setScene(secondScene);

        // Specifies the modality for new window.
        newWindow.initModality(Modality.APPLICATION_MODAL);

        var primaryStage = CopybotMainUi.STAGE;
        // Specifies the owner Window (parent) for new window
        newWindow.initOwner(primaryStage);

        // Set position of second window, related to primary window.
        newWindow.setX(primaryStage.getX() + 200);
        newWindow.setY(primaryStage.getY() + 100);

        newWindow.show();
    }

}
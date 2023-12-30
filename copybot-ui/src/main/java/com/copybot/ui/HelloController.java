package com.copybot.ui;

import com.copybot.engine.CopybotEngine;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.plugin.api.action.WorkItem;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class HelloController {
    @FXML
    private Label fileCount;

    @FXML
    private TableView<WorkItem> fileListView;

    private ObservableList<WorkItem> fileListObservable;

    @FXML
    public void initialize() {
        fileListObservable = FXCollections.observableArrayList();
        fileListView.setItems(fileListObservable);

        fileCount.setText("aze");
        fileListView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        TableColumn<WorkItem, String> column = new TableColumn<>(
               "test"
        );
        column.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().getMetadatas().getSizeHr())
        );
        fileListView.getColumns().add(column);
    }


    @FXML
    protected void onTestButtonClick() throws IOException {
        FXMLLoader fxmlLoader = new FXMLLoader(CopybotMainUi.class.getResource("views/hello-view2.fxml"));

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

    @FXML
    protected void onHelloButtonClick() throws IOException {
        /*
        StartExp s = new StartExp();
        CopybotMainUi.executor.submit(s);
*/
        /*
        CopybotEngine.run(null, null, wi -> {
            Platform.runLater(() -> {
                fileListObservable.add(wi);
                fileCount.setText(String.valueOf(fileListObservable.size()));
            });
        });
*/
        CopybotEngine.run(Path.of("C:\\Users\\Steven\\IdeaProjects\\copybot\\copybot-engine\\src\\test\\resources\\com\\copybot\\engine\\test-pipeline.json"),state -> {
            Platform.runLater(() -> {
                List<WorkItem> list = new ArrayList<>(state.getWorkItems().stream().map(WorkItemExecution::getWorkItem).toList()); // copy to prevent list to evolve while setting to fileListObservable
                fileListObservable.setAll(list);
                fileCount.setText(String.valueOf(fileListObservable.size()));
            });
        });

//        welcomeText.setText("Welcome to JavaFX Application!");
    }


}
package com.copybot.ui;

import com.copybot.engine.CopybotEngine;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.plugin.api.action.WorkStatus;
import com.copybot.ui.util.PopinUtil;
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
import java.util.List;

public class HelloController {

    /** Dev pipeline, relative to the project root — same convention as CopybotMainUiDev's config path. */
    private static final Path TEST_PIPELINE = Path.of("copybot-ui", "src", "dev", "test-pipeline.json");

    @FXML
    private Label fileCount;

    @FXML
    private TableView<WorkItemExecution> fileListView;

    @FXML
    private TableColumn<WorkItemExecution, String> nameColumn;

    @FXML
    private TableColumn<WorkItemExecution, String> locationColumn;

    @FXML
    private TableColumn<WorkItemExecution, String> sizeColumn;

    @FXML
    private TableColumn<WorkItemExecution, String> statusColumn;

    private ObservableList<WorkItemExecution> fileListObservable;

    @FXML
    public void initialize() {
        fileListObservable = FXCollections.observableArrayList();
        fileListView.setItems(fileListObservable);
        fileListView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        nameColumn.setCellValueFactory(cell ->
                new SimpleStringProperty(cell.getValue().getWorkItem().getNameDisplay()));
        locationColumn.setCellValueFactory(cell ->
                new SimpleStringProperty(cell.getValue().getWorkItem().getSourceLocationDisplay()));
        sizeColumn.setCellValueFactory(cell ->
                new SimpleStringProperty(cell.getValue().getWorkItem().getMetadatas().getSizeHr()));
        statusColumn.setCellValueFactory(cell ->
                new SimpleStringProperty(statusText(cell.getValue())));

        fileCount.setText("");
    }

    private static String statusText(WorkItemExecution exec) {
        return switch (exec.getStatus()) {
            case RUNNING -> {
                WorkStatus ws = exec.getWorkStatus();
                yield ws != null && ws.actionPercent() >= 0 ? "RUNNING " + ws.actionPercent() + "%" : "RUNNING";
            }
            case WAITING_RESOURCES -> "WAITING " + exec.getWaitingFor();
            case ERROR -> exec.getError() != null ? "ERROR: " + exec.getError().getMessage() : "ERROR";
            default -> exec.getStatus().toString();
        };
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
    protected void onHelloButtonClick() {
        try {
            CopybotEngine.run(TEST_PIPELINE, state -> {
                List<WorkItemExecution> list = List.copyOf(state.getWorkItems()); // snapshot outside the FX thread; the queue may evolve concurrently
                String summary = state.getStatus() + " — " + list.size() + " items"
                        + (state.isListingInProgress() ? " (listing…)" : "");
                Platform.runLater(() -> {
                    if (list.size() != fileListObservable.size()) {
                        fileListObservable.setAll(list);
                    } else {
                        fileListView.refresh(); // same rows, but their status/percent evolved
                    }
                    fileCount.setText(summary);
                });
            });
        } catch (Exception e) {
            // e.g. "Engine already running" or unreadable pipeline file
            PopinUtil.showError(e);
        }
    }
}

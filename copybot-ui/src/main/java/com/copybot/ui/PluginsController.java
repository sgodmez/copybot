package com.copybot.ui;

import com.copybot.engine.plugin.report.ActionEntry;
import com.copybot.engine.plugin.report.ModuleEntry;
import com.copybot.engine.plugin.report.PluginEntry;
import com.copybot.engine.plugin.report.PluginReport;
import com.copybot.engine.plugin.report.PluginStatus;
import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.util.PopinUtil;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** The plugins of this JVM as they were loaded, read-only (spec plugins-view §2). */
public class PluginsController {

    private static final Map<PluginStatus, Color> STATUS_COLORS = Map.of(
            PluginStatus.LOADED, Color.web("#2e7d32"),
            PluginStatus.ACTIONS_FAILED, Color.web("#ef6c00"),
            PluginStatus.ERROR, Color.web("#c62828"),
            PluginStatus.IGNORED, Color.web("#9e9e9e"));

    @FXML private Label configFileLabel;
    @FXML private Label pluginPathLabel;
    @FXML private VBox warningsBox;
    @FXML private ListView<PluginEntry> pluginList;
    @FXML private VBox detailBox;
    @FXML private Label detailTitle;
    @FXML private Label detailPath;
    @FXML private Button openFolderButton;
    @FXML private Label detailMessage;
    @FXML private TableView<ModuleEntry> moduleTable;
    @FXML private TableColumn<ModuleEntry, String> moduleNameCol;
    @FXML private TableColumn<ModuleEntry, String> moduleVersionCol;
    @FXML private TableColumn<ModuleEntry, String> moduleMainCol;
    @FXML private TableColumn<ModuleEntry, String> moduleAutomaticCol;
    @FXML private TableColumn<ModuleEntry, String> moduleLocationCol;
    @FXML private ListView<String> dependencyList;
    @FXML private TableView<ActionEntry> actionTable;
    @FXML private TableColumn<ActionEntry, String> actionTypeCol;
    @FXML private TableColumn<ActionEntry, String> actionCodeCol;
    @FXML private TableColumn<ActionEntry, String> actionNameCol;
    @FXML private Label copiedLabel;

    private PluginReport report;

    @FXML
    public void initialize() {
        moduleNameCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().name()));
        moduleVersionCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(Optional.ofNullable(c.getValue().version()).orElse("")));
        moduleMainCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().main() ? "✔" : ""));
        moduleAutomaticCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().automatic() ? "✔" : ""));
        moduleLocationCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(Optional.ofNullable(c.getValue().location()).map(Path::toString).orElse("")));
        actionTypeCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().type().name()));
        actionCodeCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().code()));
        actionNameCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().name()));
        dependencyList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                setStyle(!empty && item != null && item.startsWith(missingPrefix()) ? "-fx-text-fill: #c62828;" : "");
            }
        });
        pluginList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(PluginEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    setTooltip(null);
                    return;
                }
                String source = ResourcesEngine.getString("plugins.source." + item.source().name());
                setText(item.name() + (item.version() != null ? " " + item.version() : "")
                        + (source.isBlank() ? "" : "  [" + source + "]"));
                Circle dot = new Circle(5, STATUS_COLORS.get(item.status()));
                setGraphic(dot);
                setTooltip(new Tooltip(ResourcesEngine.getString("plugins.status." + item.status().name())));
            }
        });
        pluginList.getSelectionModel().selectedItemProperty().addListener((obs, old, entry) -> showDetail(entry));
    }

    /** @param nextPluginPath the absolute, normalized directory the next start would load, empty when unknown (no restart banner) */
    public void init(PluginReport report, Optional<Path> nextPluginPath) {
        this.report = report;
        configFileLabel.setText(report.configFile().toString());
        pluginPathLabel.setText(report.pluginPath() + (report.pluginPathConfigured() ? "" : " " + ResourcesEngine.getString("plugins.default")));
        warningsBox.getChildren().clear();
        for (String warning : report.warnings()) {
            warningsBox.getChildren().add(banner(warning, "#fff3e0", "#e65100"));
        }
        if (nextPluginPath.isPresent() && !nextPluginPath.get().equals(report.pluginPath())) {
            warningsBox.getChildren().add(banner(ResourcesEngine.getString("plugins.restart-needed", nextPluginPath.get()), "#e3f2fd", "#0d47a1"));
        }
        PluginEntry selected = pluginList.getSelectionModel().getSelectedItem(); // a refresh keeps the selection
        pluginList.getItems().setAll(report.plugins());
        if (report.plugins().isEmpty()) {
            pluginList.setPlaceholder(new Label(ResourcesEngine.getString("plugins.none")));
            detailBox.setVisible(false);
        } else {
            int index = 0;
            for (int i = 0; selected != null && i < report.plugins().size(); i++) {
                PluginEntry entry = report.plugins().get(i);
                if (entry.name().equals(selected.name()) && Objects.equals(entry.path(), selected.path())) {
                    index = i;
                    break;
                }
            }
            pluginList.getSelectionModel().select(index);
        }
    }

    private static Label banner(String text, String background, String foreground) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        label.setStyle("-fx-background-color: " + background + "; -fx-text-fill: " + foreground + "; -fx-padding: 4 8 4 8;");
        return label;
    }

    private void showDetail(PluginEntry entry) {
        detailBox.setVisible(entry != null);
        if (entry == null) {
            return;
        }
        detailTitle.setText(entry.name() + (entry.version() != null ? " " + entry.version() : "")
                + " — " + ResourcesEngine.getString("plugins.status." + entry.status().name()));
        detailPath.setText(entry.path() != null ? entry.path().toString() : "—");
        openFolderButton.setDisable(entry.path() == null);
        detailMessage.setText(entry.message() != null ? entry.message() : "");
        detailMessage.setManaged(entry.message() != null);
        detailMessage.setVisible(entry.message() != null);
        moduleTable.getItems().setAll(entry.modules());
        dependencyList.getItems().setAll(entry.pluginDependencies().stream()
                .map(d -> ResourcesEngine.getString("plugins.dep.plugin", d)).toList());
        dependencyList.getItems().addAll(entry.missingRequires().stream()
                .map(m -> ResourcesEngine.getString("plugins.dep.missing", m)).toList());
        actionTable.getItems().setAll(entry.actions());
    }

    private static String missingPrefix() {
        String sample = ResourcesEngine.getString("plugins.dep.missing", "\u0000");
        return sample.substring(0, sample.indexOf('\u0000'));
    }

    @FXML
    protected void onOpenFolderClick() {
        PluginEntry entry = pluginList.getSelectionModel().getSelectedItem();
        if (entry == null || entry.path() == null) {
            return;
        }
        try {
            CopybotMainUi.HOST_SERVICES.showDocument(entry.path().toUri().toString());
        } catch (RuntimeException e) {
            PopinUtil.showError(e);
        }
    }

    @FXML
    protected void onCopyReportClick() {
        ClipboardContent content = new ClipboardContent();
        content.putString(report.toText());
        Clipboard.getSystemClipboard().setContent(content);
        copiedLabel.setText(ResourcesEngine.getString("plugins.copied"));
    }

    @FXML
    protected void onCloseClick() {
        ((Stage) pluginList.getScene().getWindow()).close();
    }
}

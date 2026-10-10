package com.copybot.ui;

import com.copybot.config.ConfigFiles;
import com.copybot.engine.resources.DiskInventory;
import com.copybot.engine.resources.DiskResolver;
import com.copybot.engine.resources.ResourceSettings;
import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.model.DiskSettingsModel;
import com.copybot.ui.model.RecentPipelines;
import com.copybot.ui.util.PopinUtil;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.util.StringConverter;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.IntStream;

/**
 * The resources window (Edit menu): the disks of the machine, their capacity and their groups, saved to the
 * configuration file and taken by the engine from the next plan.
 */
public class ResourcesController {

    /** The capacities offered for a disk, after its default one. */
    private static final int MAX_CAPACITY = 16;

    @FXML
    private TableView<DiskSettingsModel.Row> disksTable;
    @FXML
    private TableColumn<DiskSettingsModel.Row, DiskSettingsModel.Row> volumesColumn;
    @FXML
    private TableColumn<DiskSettingsModel.Row, String> diskColumn;
    @FXML
    private TableColumn<DiskSettingsModel.Row, String> kindColumn;
    @FXML
    private TableColumn<DiskSettingsModel.Row, DiskSettingsModel.Row> capacityColumn;
    @FXML
    private TableColumn<DiskSettingsModel.Row, String> groupColumn;
    @FXML
    private Label disksPlaceholder;
    @FXML
    private Button addFolderButton;
    @FXML
    private Button groupButton;
    @FXML
    private Button ungroupButton;
    @FXML
    private Button forgetButton;
    @FXML
    private Button saveButton;

    /** Null until the drives and the configuration are read, or when they cannot be. */
    private DiskSettingsModel disks;

    @FXML
    public void initialize() {
        volumesColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        volumesColumn.setCellFactory(c -> new VolumesCell());
        diskColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(diskText(c.getValue().resource())));
        kindColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                ResourcesEngine.getString("pref.disks.kind." + c.getValue().kind().name())));
        capacityColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        capacityColumn.setCellFactory(c -> new CapacityCell());
        groupColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(String.join(", ",
                c.getValue().groupedWith().stream().map(this::labelOf).toList())));
        disksTable.getSelectionModel().selectedItemProperty().addListener((o, old, row) -> updateButtons());
        updateButtons();
    }

    /**
     * Reads the drives, the configuration and, when a pipeline is open (null: none), the folders it reads and
     * writes: its disks are shown even when they are not there, and written only once they are set.
     */
    void open(Path pipeline) {
        Path configFile = CopybotMainUi.ENGINE.configFile();
        try {
            // the drives (a share may not answer) and the files: off the JavaFX thread
            CopybotMainUi.executor.submit(() -> {
                DiskSettingsModel model;
                String error = null;
                try {
                    model = new DiskSettingsModel(DiskInventory.volumes(), ConfigFiles.readResources(configFile),
                            DiskResolver::resourceForConfigName);
                    if (pipeline != null) {
                        addPipeline(model, pipeline);
                    }
                } catch (Throwable t) {
                    model = null;
                    error = t.getMessage();
                }
                DiskSettingsModel read = model;
                String failure = error;
                Platform.runLater(() -> {
                    disks = read;
                    if (read == null) {
                        disksPlaceholder.setText(ResourcesEngine.getString("pref.disks.unreadable", failure));
                    }
                    showDisks();
                });
            });
        } catch (RejectedExecutionException e) {
            // the application is stopping
        }
    }

    /** A pipeline that cannot be read now (moved, invalid) adds nothing: the disks of the machine are still shown. */
    private static void addPipeline(DiskSettingsModel model, Path pipeline) {
        List<Path> paths;
        try {
            paths = CopybotMainUi.ENGINE.pipelinePaths(pipeline);
        } catch (RuntimeException e) {
            return;
        }
        String name = RecentPipelines.displayName(pipeline);
        for (Path path : paths) {
            model.addPipelinePath(name, DiskInventory.use(path));
        }
    }

    /** Shows the rows again after a change, the same disk still selected. */
    private void showDisks() {
        DiskSettingsModel.Row selected = disksTable.getSelectionModel().getSelectedItem();
        disksTable.getItems().setAll(disks == null ? List.of() : disks.rows());
        if (selected != null) {
            disksTable.getItems().stream()
                    .filter(r -> r.resource().equals(selected.resource()))
                    .findFirst()
                    .ifPresent(r -> disksTable.getSelectionModel().select(r));
        }
        updateButtons();
    }

    private void updateButtons() {
        DiskSettingsModel.Row row = disksTable.getSelectionModel().getSelectedItem();
        addFolderButton.setDisable(disks == null);
        groupButton.setDisable(disks == null || row == null || disksTable.getItems().size() < 2);
        ungroupButton.setDisable(disks == null || row == null || row.groupedWith().isEmpty());
        forgetButton.setDisable(disks == null || row == null || row.present());
        saveButton.setDisable(disks == null);
    }

    /** The volumes of a disk (its name for a disk of the configuration only), and whether it is there now. */
    private static String volumesText(DiskSettingsModel.Row row) {
        String volumes = row.volumes().isEmpty()
                ? row.resource().substring(ResourceSettings.DISK_PREFIX.length())
                : String.join("  ", row.volumes());
        return row.present() ? volumes : volumes + "  " + ResourcesEngine.getString("pref.disks.absent");
    }

    /** The physical disk, or a word saying that the volume is its own resource (share, RAID...). */
    private static String diskText(String resource) {
        String id = resource.substring(ResourceSettings.DISK_PREFIX.length());
        boolean volume = id.startsWith("/") || id.startsWith("\\\\") || id.matches("[A-Za-z]:\\\\.*");
        return volume ? ResourcesEngine.getString("pref.disks.not-detected") : id;
    }

    /** How a disk is named to the user: its volumes, else its resource. */
    private String labelOf(String resource) {
        return disksTable.getItems().stream()
                .filter(r -> r.resource().equals(resource) && !r.volumes().isEmpty())
                .findFirst()
                .map(r -> String.join(" ", r.volumes()))
                .orElse(resource.substring(ResourceSettings.DISK_PREFIX.length()));
    }

    /** The volumes of a disk, after a blue dot when the open pipeline reads or writes on it (named in the tooltip). */
    private static final class VolumesCell extends TableCell<DiskSettingsModel.Row, DiskSettingsModel.Row> {
        private final Circle dot = new Circle(4, Color.web("#2563eb"));
        private final Tooltip tip = new Tooltip();

        @Override
        protected void updateItem(DiskSettingsModel.Row row, boolean empty) {
            super.updateItem(row, empty);
            if (empty || row == null) {
                setText(null);
                setGraphic(null);
                setTooltip(null);
                return;
            }
            setText(volumesText(row));
            boolean used = !row.usedBy().isEmpty();
            setGraphic(used ? dot : null);
            if (used) {
                tip.setText(ResourcesEngine.getString("pref.disks.used-by", String.join(", ", row.usedBy())));
            }
            setTooltip(used ? tip : null);
        }
    }

    /** The capacity of a disk, chosen in a list: its default one, or a number of actions at once. */
    private final class CapacityCell extends TableCell<DiskSettingsModel.Row, DiskSettingsModel.Row> {
        private final ComboBox<Integer> combo = new ComboBox<>();
        private boolean showing;

        CapacityCell() {
            List<Integer> choices = new ArrayList<>();
            choices.add(null);
            IntStream.rangeClosed(1, MAX_CAPACITY).forEach(choices::add);
            combo.getItems().setAll(choices);
            combo.setMaxWidth(Double.MAX_VALUE);
            combo.setOnAction(e -> {
                DiskSettingsModel.Row row = getItem();
                if (!showing && row != null && disks != null) {
                    disks.setCapacity(row.resource(), combo.getValue());
                    Platform.runLater(ResourcesController.this::showDisks); // not inside the cell's own event
                }
            });
        }

        @Override
        protected void updateItem(DiskSettingsModel.Row row, boolean empty) {
            super.updateItem(row, empty);
            if (empty || row == null || disks == null) {
                setGraphic(null);
                return;
            }
            showing = true;
            int byDefault = disks.defaultCapacity(row.resource());
            combo.setConverter(new StringConverter<>() {
                @Override
                public String toString(Integer capacity) {
                    return capacity == null
                            ? ResourcesEngine.getString("pref.disks.capacity.default", byDefault)
                            : String.valueOf(capacity);
                }

                @Override
                public Integer fromString(String s) {
                    return null; // not editable
                }
            });
            combo.setValue(row.defaultCapacity() ? null : row.capacity());
            showing = false;
            setGraphic(combo);
        }
    }

    @FXML
    protected void onAddFolderClick() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(ResourcesEngine.getString("pref.disks.add-folder"));
        File dir = chooser.showDialog(disksTable.getScene().getWindow());
        if (dir == null || disks == null) {
            return;
        }
        try {
            CopybotMainUi.executor.submit(() -> {
                DiskInventory.Volume volume = DiskInventory.of(dir.toPath()); // may be a share: off the thread
                Platform.runLater(() -> {
                    disks.addVolume(volume);
                    showDisks();
                    disksTable.getItems().stream()
                            .filter(r -> r.resource().equals(volume.resource()))
                            .findFirst()
                            .ifPresent(r -> disksTable.getSelectionModel().select(r));
                });
            });
        } catch (RejectedExecutionException e) {
            // the application is stopping
        }
    }

    @FXML
    protected void onGroupClick() {
        DiskSettingsModel.Row row = disksTable.getSelectionModel().getSelectedItem();
        if (row == null || disks == null) {
            return;
        }
        Map<String, String> others = new LinkedHashMap<>(); // label -> resource
        for (DiskSettingsModel.Row other : disksTable.getItems()) {
            if (!other.resource().equals(row.resource()) && !row.groupedWith().contains(other.resource())) {
                others.put(labelOf(other.resource()) + "  (" + diskText(other.resource()) + ")", other.resource());
            }
        }
        if (others.isEmpty()) {
            return;
        }
        ChoiceDialog<String> dialog = new ChoiceDialog<>(others.keySet().iterator().next(), others.keySet());
        dialog.setTitle(ResourcesEngine.getString("pref.disks.group-with.title"));
        dialog.setHeaderText(null);
        dialog.setContentText(ResourcesEngine.getString("pref.disks.group-with.text", labelOf(row.resource())));
        dialog.initOwner(disksTable.getScene().getWindow());
        dialog.showAndWait().map(others::get).ifPresent(with -> {
            disks.group(row.resource(), with);
            showDisks();
        });
    }

    @FXML
    protected void onUngroupClick() {
        DiskSettingsModel.Row row = disksTable.getSelectionModel().getSelectedItem();
        if (row != null && disks != null) {
            disks.ungroup(row.resource());
            showDisks();
        }
    }

    @FXML
    protected void onForgetClick() {
        DiskSettingsModel.Row row = disksTable.getSelectionModel().getSelectedItem();
        if (row != null && disks != null && !row.present()) {
            disks.forget(row.resource());
            showDisks();
        }
    }

    @FXML
    protected void onSaveClick() {
        if (disks != null && disks.changed()) {
            Path configFile = CopybotMainUi.ENGINE.configFile();
            try {
                // synchronous: a user-initiated rewrite of the local configuration file
                if (ConfigFiles.rewriteLosesContent(configFile) && !confirmLossyRewrite(configFile)) {
                    return; // nothing written, the window stays open
                }
                ConfigFiles.Resources resources = disks.resources();
                ConfigFiles.writeResources(configFile, resources);
                CopybotMainUi.ENGINE.updateResources(resources.capacities(), resources.groups());
            } catch (RuntimeException e) {
                PopinUtil.showError(e);
                return; // the window stays open
            }
        }
        close();
    }

    /** Comments, lenient syntax or repeated keys of the file would be lost: true when the user accepts it. */
    private boolean confirmLossyRewrite(Path configFile) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, ResourcesEngine.getString("pref.save.lossy", configFile),
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(null);
        confirm.initOwner(disksTable.getScene().getWindow());
        return confirm.showAndWait().filter(ButtonType.OK::equals).isPresent();
    }

    @FXML
    protected void onCancelClick() {
        close();
    }

    private void close() {
        ((Stage) disksTable.getScene().getWindow()).close();
    }
}

package com.copybot.ui;

import com.copybot.engine.plugin.report.ActionEntry;
import com.copybot.engine.plugin.report.ModuleEntry;
import com.copybot.engine.plugin.report.PluginEntry;
import com.copybot.engine.plugin.report.PluginReport;
import com.copybot.engine.plugin.report.PluginStatus;
import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.plugins.PluginCatalog;
import com.copybot.ui.plugins.PluginChoices;
import com.copybot.ui.plugins.PluginChoices.Action;
import com.copybot.ui.plugins.PluginRow;
import com.copybot.ui.plugins.PluginService;
import com.copybot.ui.plugins.PluginState;
import com.copybot.ui.util.Background;
import com.copybot.ui.util.PopinUtil;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The plugins of this JVM as they were loaded (spec plugins-view §2), and the official plugins of the catalogue with
 * their install, update and removal, applied together with a restart (spec plugin-install §3).
 */
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
    @FXML private TitledPane officialPane;
    @FXML private Label officialStatus;
    @FXML private VBox officialRows;
    @FXML private Label pendingLabel;
    @FXML private Label applyReason;
    @FXML private Button updateAllButton;
    @FXML private Button applyButton;

    private PluginReport report;
    /** The official plugins shown, empty until the catalogue is read. */
    private List<PluginRow> rows = List.of();
    /** The actions chosen in the official section, kept across refreshes. */
    private PluginChoices choices = new PluginChoices();
    /** The last read of the catalogue started: an older one that ends later is ignored. */
    private Object officialToken;

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

    public void init(PluginReport report) {
        this.report = report;
        configFileLabel.setText(report.configFile().toString());
        pluginPathLabel.setText(report.pluginPath().toString());
        warningsBox.getChildren().clear();
        for (String warning : report.warnings()) {
            warningsBox.getChildren().add(banner(warning, "#fff3e0", "#e65100"));
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

    // ---- the official plugins ----

    /** The choices not applied yet, handed to the window that replaces this one. */
    PluginChoices choices() {
        return choices;
    }

    /** Takes over the choices of the window this one replaces; the next {@link #showOfficial} adds to them. */
    void useChoices(PluginChoices carried) {
        choices = carried;
    }

    /**
     * Shows the official plugins: the catalogue in memory, else read in the background (offline: a message, the
     * diagnostic stays). The install or update of {@code modules} is chosen, and every update with {@code updates};
     * the choices already made are kept when they still fit.
     */
    void showOfficial(Collection<String> modules, boolean updates) {
        Optional<PluginService> service = UpdateUi.plugins();
        officialPane.setVisible(service.isPresent());
        officialPane.setManaged(service.isPresent());
        if (service.isEmpty()) {
            return;
        }
        Object token = new Object();
        officialToken = token;
        PluginReport loaded = report;
        if (rows.isEmpty()) {
            officialStatus.setText(ResourcesEngine.getString("plugins.official.loading"));
        }
        renderOfficial();
        Background.run(() -> service.get().rows(service.get().catalog(), loaded),
                found -> {
                    if (token != officialToken) {
                        return;
                    }
                    rows = found;
                    choices.retain(rows);
                    choices.preselect(rows, modules);
                    if (updates) {
                        choices.updateAll(rows);
                    }
                    officialStatus.setText(rows.isEmpty() ? ResourcesEngine.getString("plugins.official.empty") : "");
                    renderOfficial();
                },
                failure -> {
                    if (token != officialToken) {
                        return;
                    }
                    officialStatus.setText(ResourcesEngine.getString("plugins.official.unreadable",
                            UpdateUi.message(failure)));
                    renderOfficial();
                });
    }

    private void renderOfficial() {
        officialRows.getChildren().clear();
        Optional<String> blocked = UpdateUi.plugins().flatMap(PluginService::applyBlocked);
        for (PluginRow row : rows) {
            officialRows.getChildren().add(officialRow(row, blocked.isPresent()));
        }
        officialStatus.setManaged(!officialStatus.getText().isEmpty());
        officialStatus.setVisible(!officialStatus.getText().isEmpty());
        refreshFooter();
    }

    /** One plugin: its name, description, licence, versions, state, and its actions or the one chosen. */
    private HBox officialRow(PluginRow row, boolean blocked) {
        PluginCatalog.Entry entry = row.entry();
        Label title = new Label(entry.code() + "  " + entry.version());
        title.setStyle("-fx-font-weight: bold;");
        Label module = new Label(entry.module());
        module.setStyle("-fx-text-fill: #757575;");
        HBox head = new HBox(8, title, module);
        head.setAlignment(Pos.BASELINE_LEFT);
        VBox info = new VBox(2, head);
        String description = UpdateUi.description(entry);
        if (!description.isBlank()) {
            Label text = new Label(description);
            text.setWrapText(true);
            info.getChildren().add(text);
        }
        if (entry.license() != null) {
            Label license = new Label(ResourcesEngine.getString("plugins.license", entry.license().name(),
                    UpdateUi.localized(entry.license().notice())));
            license.setWrapText(true);
            license.setStyle("-fx-text-fill: #616161; -fx-font-size: 0.9em;");
            info.getChildren().add(license);
        }
        List<String> versions = new ArrayList<>();
        if (row.installedVersion() != null) {
            versions.add(ResourcesEngine.getString("plugins.official.installed", row.installedVersion()));
        }
        versions.add(ResourcesEngine.getString("plugins.official.available", entry.version()));
        Label state = new Label(stateText(row) + "  ·  " + String.join("  ·  ", versions));
        state.setWrapText(true);
        state.setStyle(switch (row.state()) {
            case UPDATE_AVAILABLE -> "-fx-text-fill: #0d47a1;";
            case INSTALLED_INCOMPATIBLE, INCOMPATIBLE -> "-fx-text-fill: #c62828;";
            default -> "-fx-text-fill: #424242;";
        });
        info.getChildren().add(state);
        HBox.setHgrow(info, Priority.ALWAYS);
        info.setMaxWidth(Double.MAX_VALUE);

        HBox actions = new HBox(6);
        actions.setAlignment(Pos.CENTER_RIGHT);
        Optional<Action> chosen = choices.chosen(row);
        if (chosen.isPresent()) {
            Label label = new Label(ResourcesEngine.getString("plugins.chosen." + chosen.get().name()));
            label.setStyle("-fx-font-weight: bold; -fx-text-fill: #0d47a1;");
            Button undo = new Button(ResourcesEngine.getString("plugins.action.undo"));
            undo.setOnAction(e -> {
                choices.cancel(row);
                renderOfficial();
            });
            actions.getChildren().addAll(label, undo);
        } else {
            for (Action action : PluginChoices.offered(row)) {
                Button button = new Button(ResourcesEngine.getString("plugins.action." + action.name()));
                button.setDisable(blocked);
                button.setOnAction(e -> {
                    choices.choose(row, action);
                    renderOfficial();
                });
                actions.getChildren().add(button);
            }
        }
        HBox line = new HBox(12, info, actions);
        line.setAlignment(Pos.CENTER_LEFT);
        line.setStyle("-fx-border-color: transparent transparent #e0e0e0 transparent; -fx-padding: 4 0 6 0;");
        return line;
    }

    /**
     * The state in words; an update that fixes a plugin refused for its API says so, and so does a newer version that
     * needs a newer Copybot (the installed one goes on working).
     */
    private String stateText(PluginRow row) {
        String text = row.state() == PluginState.INCOMPATIBLE
                ? ResourcesEngine.getString("plugins.state.INCOMPATIBLE", row.entry().engine())
                : ResourcesEngine.getString("plugins.state." + row.state().name());
        if (row.state() == PluginState.UPDATE_AVAILABLE && refusedForItsApi(row)) {
            text += " (" + ResourcesEngine.getString("plugins.state.update-fixes") + ")";
        }
        if (row.newerVersionNeedsANewerEngine()) {
            text += "  ·  " + ResourcesEngine.getString("plugins.state.needs-newer-engine", row.entry().engine(),
                    row.entry().version());
        }
        return text;
    }

    private boolean refusedForItsApi(PluginRow row) {
        return report != null && report.plugins().stream()
                .anyMatch(p -> p.name().equals(row.entry().module()) && p.status() == PluginStatus.ERROR
                        && "plugin.load.api-incompatible".equals(p.errorKey()));
    }

    /** Shown again when an operation starts or ends: the actions are disabled while it runs. */
    void refreshActions() {
        renderOfficial();
    }

    /**
     * The buttons of the section and the reason they are disabled (not the packaged release, not Windows, an
     * operation running).
     */
    private void refreshFooter() {
        Optional<String> blocked = UpdateUi.plugins().flatMap(PluginService::applyBlocked);
        int pending = choices.toInstallOrUpdate(rows).size() + choices.toRemove(rows).size();
        pendingLabel.setText(ResourcesEngine.getString("plugins.pending", pending));
        applyReason.setText(blocked.map(key -> ResourcesEngine.getString(key)).orElse(""));
        updateAllButton.setDisable(blocked.isPresent()
                || rows.stream().noneMatch(r -> r.state() == PluginState.UPDATE_AVAILABLE));
        applyButton.setDisable(blocked.isPresent() || pending == 0);
    }

    @FXML
    protected void onUpdateAllClick() {
        choices.updateAll(rows);
        renderOfficial();
    }

    @FXML
    protected void onApplyClick() {
        UpdateUi.applyPlugins(choices.toInstallOrUpdate(rows), choices.toRemove(rows),
                applyButton.getScene().getWindow());
    }

    @FXML
    protected void onCloseClick() {
        ((Stage) pluginList.getScene().getWindow()).close();
    }
}

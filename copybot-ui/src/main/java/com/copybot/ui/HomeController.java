package com.copybot.ui;

import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.model.PipelineDocument;
import com.copybot.ui.model.PlanViewModel;
import com.copybot.ui.model.RecentPipelines;
import com.copybot.ui.util.UiPreferences;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** The home screen (spec desktop-ui §1): the recent pipelines; nothing runs until one is prepared. */
public class HomeController {

    /**
     * A recent pipeline and whether its file is gone. The check is file I/O (it may block on a
     * disconnected network share): it runs in the background, at most one pending check per file, and the row is
     * not clickable until it is known.
     */
    private static final class Row {
        final RecentPipelines.Entry entry;
        /** null while the check runs; only touched on the JavaFX thread */
        Boolean missing;

        Row(RecentPipelines.Entry entry) {
            this.entry = entry;
        }
    }

    @FXML
    private ListView<Row> recentList;

    private MainController main;

    @FXML
    public void initialize() {
        recentList.setPlaceholder(new Label(ResourcesEngine.getString("home.empty")));
        recentList.setCellFactory(list -> new RecentCell());
    }

    void init(MainController main) {
        this.main = main;
        refresh();
    }

    /**
     * The file checks: two daemon threads, so that checks stuck on unreachable shares never pile up
     * threads; at most one pending check per file (JavaFX thread only), whatever the number of refreshes.
     */
    private static final ExecutorService CHECKS = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "copybot-ui-file-check");
        thread.setDaemon(true);
        return thread;
    });
    private static final Set<Path> PENDING_CHECKS = new HashSet<>();
    /** The home screen shown (JavaFX thread only): a check ending after a refresh updates its rows. */
    private static HomeController shown;

    private void refresh() {
        shown = this;
        recentList.getItems().setAll(UiPreferences.recents().entries().stream().map(Row::new).toList());
        for (Row row : recentList.getItems()) {
            Path file = row.entry.path();
            if (!PENDING_CHECKS.add(file)) {
                continue; // still being checked: its result will update this row too
            }
            try {
                CHECKS.submit(() -> {
                    boolean missing;
                    try {
                        missing = row.entry.isMissing();
                    } catch (RuntimeException e) { // e.g. a SecurityException: the file cannot be reached
                        missing = true;
                    }
                    boolean result = missing;
                    Platform.runLater(() -> {
                        PENDING_CHECKS.remove(file);
                        if (shown != null) {
                            shown.checked(file, result);
                        }
                    });
                });
            } catch (RejectedExecutionException e) {
                PENDING_CHECKS.remove(file);
                return;
            }
        }
    }

    /** The rows of this file (from the latest refresh) learn whether it is gone. */
    private void checked(Path file, boolean missing) {
        boolean changed = false;
        for (Row row : recentList.getItems()) {
            if (row.entry.path().equals(file)) {
                row.missing = missing;
                changed = true;
            }
        }
        if (changed) {
            recentList.refresh();
        }
    }

    @FXML
    protected void onOpenClick() {
        FileChooser chooser = new FileChooser();
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(ResourcesEngine.getString("home.file-filter"), "*.json"));
        File file = chooser.showOpenDialog(CopybotMainUi.STAGE);
        if (file == null) {
            return;
        }
        if (PipelineDocument.isResumeCursor(file.toPath())) { // "*.json" lists the engine's resume cursors too
            Alert alert = new Alert(Alert.AlertType.WARNING,
                    ResourcesEngine.getString("home.state-file", file.getName()), ButtonType.OK);
            alert.initOwner(CopybotMainUi.STAGE);
            alert.showAndWait();
            return;
        }
        main.showPlan(file.toPath());
    }

    @FXML
    protected void onNewClick() {
        // returns at once: the editor opens later, the list is refreshed after each save; this screen may have
        // been replaced meanwhile (a recent opened, Back, a language change): the one shown is refreshed, if any
        EditorController.open(CopybotMainUi.STAGE, null, saved -> {
            UiPreferences.updateRecents(recents -> recents.touch(saved, Instant.now()));
            if (shown != null && shown.isShown()) {
                shown.refresh();
            }
        });
    }

    /** This screen is still the one of the main window (not replaced by a plan view or a rebuild). */
    private boolean isShown() {
        return recentList.getScene() != null && recentList.getScene() == CopybotMainUi.STAGE.getScene();
    }

    /** Name, path and last run; greyed "not found" when the file is gone (not clickable); right click: remove. */
    private final class RecentCell extends ListCell<Row> {
        private final Label name = new Label();
        private final Label path = new Label();
        private final Label lastRun = new Label();
        private final VBox box = new VBox(2, name, path, lastRun);
        private final ContextMenu menu = new ContextMenu();

        RecentCell() {
            name.setStyle("-fx-font-weight: bold;");
            path.setStyle("-fx-font-size: 11px;");
            MenuItem remove = new MenuItem(ResourcesEngine.getString("home.remove"));
            remove.setOnAction(e -> {
                if (getItem() != null) {
                    UiPreferences.updateRecents(recents -> recents.remove(getItem().entry.path()));
                    refresh();
                }
            });
            menu.getItems().add(remove);
            setOnMouseClicked(e -> {
                Row row = getItem();
                if (e.getButton() == MouseButton.PRIMARY && row != null && Boolean.FALSE.equals(row.missing)) {
                    main.showPlan(row.entry.path());
                }
            });
        }

        @Override
        protected void updateItem(Row row, boolean empty) {
            super.updateItem(row, empty);
            if (empty || row == null) {
                setGraphic(null);
                setContextMenu(null);
                return;
            }
            boolean missing = Boolean.TRUE.equals(row.missing);
            RecentPipelines.Entry entry = row.entry;
            name.setText(entry.displayName() + (missing ? " (" + ResourcesEngine.getString("home.missing") + ")" : ""));
            path.setText(entry.path().toString());
            lastRun.setText(PlanViewModel.lastRunText(entry.lastRun()));
            box.setOpacity(missing ? 0.5 : 1.0);
            setGraphic(box);
            setContextMenu(menu);
        }
    }
}

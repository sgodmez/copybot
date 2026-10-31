package com.copybot.ui;

import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.plugins.PluginRow;
import com.copybot.ui.plugins.PluginService;
import com.copybot.ui.plugins.UpdatesModel;
import com.copybot.ui.plugins.UpdatesModel.PluginLine;
import com.copybot.ui.plugins.UpdatesTexts;
import com.copybot.ui.update.Release;
import com.copybot.ui.update.UpdateService;
import com.copybot.ui.util.AppInfo;
import com.copybot.ui.util.Background;
import com.copybot.utils.VersionUtil;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The Updates window (spec updates-window): the application and the official plugins installed in its plugins
 * folder, their versions and what is new, a tick for each update, and one button applying the ticked ones with one
 * restart. The rules of the ticks are {@link UpdatesModel}'s; this controller only shows them and hands the choice to
 * {@link UpdateUi}.
 */
public class UpdatesController {

    /** The key of the application in {@link #expanded} (a module name is never empty). */
    private static final String APP = "";
    private static final String GREY = "-fx-text-fill: #757575;";

    @FXML private Label headerLabel;
    @FXML private Button checkButton;
    @FXML private Label errorLabel;
    @FXML private VBox rowsBox;
    @FXML private Label summaryLabel;
    @FXML private Label applyReason;
    @FXML private Button applyButton;

    private UpdatesModel model = new UpdatesModel(Optional.empty(), List.of(), null);
    /** The plugin lines of the last good read of the catalogue: kept when a later one fails. */
    private List<PluginRow> rows = List.of();
    /** Reading the catalogue, or why it could not be read; empty otherwise. */
    private String pluginStatus = "";
    /** The size of the application file of the offered release for this copy, 0 when unknown. */
    private long appSize;
    /** The lines whose "What's new" is unfolded (the application, plugin modules): kept across refreshes. */
    private final Set<String> expanded = new HashSet<>();
    /** Puts the ticks back from the model after any click (a tick can change others: the linked plugins). */
    private final List<Runnable> tickRefreshers = new ArrayList<>();
    /** The last read started: an older one that ends later is ignored. */
    private Object readToken;
    private boolean checking;

    @FXML
    public void initialize() {
        show(errorLabel, false);
    }

    /**
     * Shows the last known state (the release offered this session, the catalogue in memory) at once, then reads the
     * plugin lines in the background. Called at each opening: everything updatable is ticked again.
     */
    void show() {
        Optional<String> unavailable = unavailable();
        if (unavailable.isPresent()) {
            // not a packaged release: nothing can be checked nor applied, the reason is all there is to say
            headerLabel.setText("Copybot " + AppInfo.version());
            setError(ResourcesEngine.getString(unavailable.get()));
            rowsBox.getChildren().clear();
            show(checkButton, false);
            show(summaryLabel, false);
            show(applyButton, false);
            show(applyReason, false);
            return;
        }
        read(false);
    }

    /** Why there is nothing to check here: not a released, packaged Copybot. */
    private static Optional<String> unavailable() {
        if (UpdateUi.plugins().isEmpty()) {
            return Optional.of("update.unavailable-dev");
        }
        return UpdateUi.service().map(UpdateService::unavailableReason).orElse(Optional.of("update.unavailable-dev"));
    }

    /**
     * Rebuilds the lines from the offered release and the plugin lines known, then reads the plugin lines again (the
     * catalogue in memory, or read now; with {@code fetch} always read now) and the size of the application file.
     * A failure keeps the lines of the last good read and tells why under the "Plugins" title.
     */
    private void read(boolean fetch) {
        PluginService plugins = UpdateUi.plugins().orElseThrow();
        Optional<Release> release = UpdateUi.knownRelease();
        Object token = new Object();
        readToken = token;
        if (rows.isEmpty()) {
            pluginStatus = ResourcesEngine.getString("plugins.official.loading");
        }
        rebuild(release);
        Background.run(() -> plugins.rowsForUpdates(fetch ? plugins.fetchCatalog() : plugins.catalog()),
                found -> {
                    if (token != readToken) {
                        return;
                    }
                    rows = found;
                    pluginStatus = "";
                    rebuild(release);
                },
                failure -> {
                    if (token != readToken) {
                        return;
                    }
                    pluginStatus = ResourcesEngine.getString("plugins.official.unreadable",
                            UpdateUi.message(failure));
                    render();
                });
        release.ifPresent(offered -> UpdateUi.service().ifPresent(service ->
                // which file applies reads the installation folder: off the JavaFX thread
                Background.run(() -> offered.asset(service.assetName(offered)).map(Release.Asset::size).orElse(0L),
                        size -> {
                            if (token == readToken) {
                                appSize = size;
                                model.setAppSize(size);
                                refreshTicks();
                            }
                        },
                        failure -> { })));
    }

    /** A new model: everything updatable ticked again. */
    private void rebuild(Optional<Release> release) {
        model = new UpdatesModel(release, rows, VersionUtil.engineVersion().orElse(null));
        model.setAppSize(release.isPresent() ? appSize : 0);
        render();
    }

    private void render() {
        headerLabel.setText(UpdatesTexts.header(AppInfo.version(), UpdatesTexts.lastCheck(UpdateUi.lastCheck(),
                Instant.now(), ZoneId.systemDefault(), Locale.getDefault())));
        show(checkButton, true);
        show(summaryLabel, true);
        show(applyButton, true);
        tickRefreshers.clear();
        rowsBox.getChildren().clear();
        rowsBox.getChildren().addAll(appLine());
        if (!rows.isEmpty() || !pluginStatus.isEmpty()) {
            Label title = new Label(ResourcesEngine.getString("updates.plugins"));
            title.setStyle("-fx-font-weight: bold; -fx-padding: 8 0 0 0;");
            rowsBox.getChildren().add(title);
        }
        if (!pluginStatus.isEmpty()) {
            Label status = new Label(pluginStatus);
            status.setWrapText(true);
            status.setStyle("-fx-text-fill: #616161;");
            rowsBox.getChildren().add(status);
        }
        for (UpdatesModel.Plugin plugin : model.plugins()) {
            rowsBox.getChildren().addAll(pluginLine(plugin));
        }
        refreshTicks();
    }

    /** "Copybot  current \u2192 offered" with its tick and its release notes, or "Copybot is up to date". */
    private List<Node> appLine() {
        Optional<Release> release = model.release();
        if (release.isEmpty()) {
            return List.of(new Label(ResourcesEngine.getString("update.up-to-date")));
        }
        CheckBox tick = new CheckBox("Copybot");
        tick.setStyle("-fx-font-weight: bold;");
        tick.setOnAction(e -> {
            model.setAppChecked(tick.isSelected());
            refreshTicks();
        });
        tickRefreshers.add(() -> tick.setSelected(model.appChecked()));
        Label versions = new Label(AppInfo.version() + "  \u2192  " + UpdatesTexts.version(release.get()));
        VBox notes = new VBox(4);
        if (!release.get().notes().isBlank()) {
            notes.getChildren().add(wrapped(release.get().notes()));
        }
        Hyperlink github = new Hyperlink(ResourcesEngine.getString("updates.see-on-github"));
        github.setOnAction(e -> CopybotMainUi.HOST_SERVICES.showDocument(release.get().notesUrl().toString()));
        notes.getChildren().add(github);
        return line(APP, tick, versions, null, notes);
    }

    /**
     * A plugin: a tick (none when up to date), its versions, what links it to Copybot, and its changes when the
     * catalogue gives some.
     */
    private List<Node> pluginLine(UpdatesModel.Plugin plugin) {
        PluginRow row = plugin.row();
        String code = row.entry().code();
        Node lead;
        Label versions;
        if (plugin.line() == PluginLine.UP_TO_DATE) {
            Label name = new Label(code);
            name.setStyle(GREY + " -fx-padding: 0 0 0 22;"); // under the names of the ticked lines
            lead = name;
            versions = new Label(row.installedVersion());
            versions.setStyle(GREY);
        } else {
            CheckBox tick = new CheckBox(code);
            tick.setOnAction(e -> {
                model.setChecked(row, tick.isSelected());
                refreshTicks();
            });
            tickRefreshers.add(() -> {
                tick.setSelected(model.checked(row));
                tick.setDisable(!model.enabled(row));
            });
            lead = tick;
            versions = new Label(row.installedVersion() + "  \u2192  " + row.entry().version());
        }
        Label note = new Label();
        note.setStyle(GREY);
        tickRefreshers.add(() -> note.setText(UpdatesTexts.note(model, plugin)));
        String changes = UpdateUi.localized(row.entry().changes());
        VBox details = null;
        if (!changes.isBlank()) {
            details = new VBox(wrapped(changes));
        }
        return line(row.entry().module(), lead, versions, note, details);
    }

    /**
     * One line, and below it its "What's new" folded or not; {@code details} null: no "What's new" link.
     *
     * @param key the key of the line in {@link #expanded}
     */
    private List<Node> line(String key, Node lead, Label versions, Label note, VBox details) {
        if (lead instanceof Region region) {
            region.setMinWidth(180);
            region.setPrefWidth(180);
        }
        versions.setMinWidth(Region.USE_PREF_SIZE);
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox line = new HBox(12, lead, versions);
        line.setAlignment(Pos.CENTER_LEFT);
        if (note != null) {
            line.getChildren().add(note);
        }
        line.getChildren().add(gap);
        if (details == null) {
            return List.of(line);
        }
        details.setStyle("-fx-padding: 0 0 6 22;");
        Hyperlink toggle = new Hyperlink();
        Runnable apply = () -> {
            boolean open = expanded.contains(key);
            toggle.setText((open ? "\u25be " : "\u25b8 ") + ResourcesEngine.getString("updates.whats-new"));
            show(details, open);
        };
        toggle.setOnAction(e -> {
            if (!expanded.remove(key)) {
                expanded.add(key);
            }
            apply.run();
        });
        apply.run();
        line.getChildren().add(toggle);
        return List.of(line, details);
    }

    /** A text shown as it is (the notes of GitHub, the changes of the catalogue), wrapped to the window. */
    private static Label wrapped(String text) {
        Label label = new Label(text.strip());
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    /** The ticks from the model, then the summary and the button. */
    private void refreshTicks() {
        tickRefreshers.forEach(Runnable::run);
        summaryLabel.setText(UpdatesTexts.summary(model));
        refreshActions();
    }

    /**
     * The button, disabled while checking, when nothing is ticked, and with the reason when the update cannot be
     * applied now (an operation runs, not Windows): also called when an operation starts or ends.
     */
    void refreshActions() {
        Optional<String> blocked = model.appChecked()
                ? UpdateUi.service().flatMap(UpdateService::installBlocked)
                : UpdateUi.plugins().flatMap(PluginService::applyBlocked);
        applyReason.setText(blocked.map(key -> ResourcesEngine.getString(key)).orElse(""));
        show(applyReason, blocked.isPresent() && applyButton.isVisible());
        applyButton.setDisable(checking || blocked.isPresent() || model.nothingToApply());
    }

    /**
     * Checks now, like the startup check but told here: the banner gets what it found, then the catalogue is read
     * again and everything updatable is ticked again. A failure is told under the header, the lines stay.
     */
    @FXML
    protected void onCheckClick() {
        PluginService plugins = UpdateUi.plugins().orElseThrow(); // the button is hidden without it
        checking = true;
        checkButton.setDisable(true);
        checkButton.setText(ResourcesEngine.getString("update.checking"));
        setError("");
        refreshActions();
        Background.run(plugins::checkNow,
                found -> {
                    endCheck();
                    UpdateUi.offer(found);
                    read(true);
                },
                failure -> {
                    endCheck();
                    setError(UpdateUi.message(failure));
                    render(); // the date of the check is recorded even when it fails
                });
    }

    private void endCheck() {
        checking = false;
        checkButton.setDisable(false);
        checkButton.setText(ResourcesEngine.getString("updates.check"));
    }

    /**
     * Applies the ticks with one restart: with Copybot, the update of the application carrying exactly the ticked
     * plugins; without it, the ticked plugins alone (the plugin mode of the update script).
     */
    @FXML
    protected void onApplyClick() {
        var window = applyButton.getScene().getWindow();
        if (model.appChecked()) {
            UpdateUi.install(model.release().orElseThrow(), model.checkedModules(), window);
            return;
        }
        List<PluginRow> ticked = model.plugins().stream().map(UpdatesModel.Plugin::row).filter(model::checked)
                .toList();
        UpdateUi.applyPlugins(ticked, List.of(), window);
    }

    @FXML
    protected void onLaterClick() {
        ((Stage) applyButton.getScene().getWindow()).close();
    }

    private void setError(String text) {
        errorLabel.setText(text);
        show(errorLabel, !text.isEmpty());
    }

    private static void show(Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }
}

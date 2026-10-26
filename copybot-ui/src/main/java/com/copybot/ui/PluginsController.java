package com.copybot.ui;

import com.copybot.engine.plugin.report.ModuleEntry;
import com.copybot.engine.plugin.report.PluginEntry;
import com.copybot.engine.plugin.report.PluginReport;
import com.copybot.engine.plugin.report.PluginStatus;
import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.plugins.PluginCard;
import com.copybot.ui.plugins.PluginCard.ActionLine;
import com.copybot.ui.plugins.PluginCard.Filter;
import com.copybot.ui.plugins.PluginCard.Status;
import com.copybot.ui.plugins.PluginCatalog;
import com.copybot.ui.plugins.PluginChoices;
import com.copybot.ui.plugins.PluginChoices.Action;
import com.copybot.ui.plugins.PluginRow;
import com.copybot.ui.plugins.PluginService;
import com.copybot.ui.plugins.PluginState;
import com.copybot.ui.util.AppInfo;
import com.copybot.ui.util.Background;
import com.copybot.ui.util.PopinUtil;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The plugins of this JVM as they were loaded (spec plugins-view §2) and the official plugins of the catalogue with
 * their install and removal, applied together with a restart (spec plugin-install §3), as one card per plugin (spec
 * updates-window, "Refonte de l'écran Plugins"). What a card shows is decided by {@link PluginCard}; this class
 * builds its nodes. The updates of installed plugins are chosen in the Updates window.
 */
public class PluginsController {

    /** The colour of the state of a card, as the dots of the former list. */
    private static final Map<Status, String> STATUS_COLORS = Map.of(
            Status.UP_TO_DATE, "#2e7d32",
            Status.LOADED, "#2e7d32",
            Status.UPDATE_AVAILABLE, "#0d47a1",
            Status.AVAILABLE, "#616161",
            Status.INCOMPATIBLE, "#c62828",
            Status.FAILED, "#c62828",
            Status.IGNORED, "#9e9e9e");
    private static final String RED = "-fx-text-fill: #c62828;";
    private static final String GREY = "-fx-text-fill: #616161;";
    private static final String CARD = "-fx-border-color: #d0d0d0; -fx-border-radius: 4; -fx-padding: 8 10 8 10;";

    @FXML private VBox warningsBox;
    @FXML private ToggleButton allFilter;
    @FXML private ToggleButton installedFilter;
    @FXML private ToggleButton availableFilter;
    @FXML private Label officialStatus;
    @FXML private VBox cardsBox;
    @FXML private TitledPane embeddedPane;
    @FXML private VBox embeddedBox;
    @FXML private GridPane technicalGrid;
    @FXML private HBox applyBar;
    @FXML private Label pendingLabel;
    @FXML private Label applyReason;
    @FXML private Button applyButton;
    @FXML private Label copiedLabel;
    @FXML private Button closeButton;

    private PluginReport report;
    /** The official plugins shown, empty until the catalogue is read. */
    private List<PluginRow> rows = List.of();
    /** The actions chosen, kept across refreshes. */
    private PluginChoices choices = new PluginChoices();
    /** The last read of the catalogue started: an older one that ends later is ignored. */
    private Object officialToken;
    /**
     * The modules a pipeline asked for while this window is open (a missing or refused plugin): their update is
     * chosen here, as their install, instead of leading to the Updates window.
     */
    private final Set<String> repairModules = new LinkedHashSet<>();
    private Filter filter = Filter.ALL;
    /** The catalogue was read: a plugin it does not list is then known not to be official. */
    private boolean catalogueRead;

    @FXML
    public void initialize() {
        ToggleGroup filters = new ToggleGroup();
        Map<ToggleButton, Filter> byButton = Map.of(allFilter, Filter.ALL, installedFilter, Filter.INSTALLED,
                availableFilter, Filter.AVAILABLE);
        byButton.keySet().forEach(button -> button.setToggleGroup(filters));
        filters.selectedToggleProperty().addListener((obs, old, selected) -> {
            if (selected == null) {
                filters.selectToggle(old); // one filter always applies
                return;
            }
            filter = byButton.get(selected);
            render();
        });
        show(officialStatus, false);
        show(applyBar, false);
    }

    /** Shows the report of a (re)loading of the plugins: warnings, cards, technical details; the choices stay. */
    public void init(PluginReport report) {
        this.report = report;
        warningsBox.getChildren().clear();
        for (String warning : report.warnings()) {
            warningsBox.getChildren().add(banner(warning, "#fff3e0", "#e65100"));
        }
        technicalGrid.getChildren().clear();
        int line = 0;
        technicalGrid.addRow(line++, new Label(ResourcesEngine.getString("plugins.config-file")),
                wrapped(report.configFile().toString()));
        technicalGrid.addRow(line++, new Label(ResourcesEngine.getString("plugins.plugin-path")),
                wrapped(report.pluginPath().toString()));
        for (Path dev : report.devPluginPaths()) {
            technicalGrid.addRow(line++, new Label(ResourcesEngine.getString("plugins.dev-path")),
                    wrapped(dev.toString()));
        }
        render();
    }

    private static Label banner(String text, String background, String foreground) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        label.setStyle("-fx-background-color: " + background + "; -fx-text-fill: " + foreground + "; -fx-padding: 4 8 4 8;");
        return label;
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
     * loaded plugins stay shown). The install or update of {@code modules} is chosen (a plugin a pipeline needs), and
     * every update with {@code updates}; the choices already made are kept when they still fit.
     */
    void showOfficial(Collection<String> modules, boolean updates) {
        repairModules.addAll(modules);
        Optional<PluginService> service = UpdateUi.plugins();
        show(applyBar, service.isPresent());
        if (service.isEmpty()) {
            return;
        }
        Object token = new Object();
        officialToken = token;
        PluginReport loaded = report;
        if (rows.isEmpty()) {
            officialStatus.setText(ResourcesEngine.getString("plugins.official.loading"));
        }
        render();
        Background.run(() -> service.get().rows(service.get().catalog(), loaded),
                found -> {
                    if (token != officialToken) {
                        return;
                    }
                    rows = found;
                    catalogueRead = true;
                    choices.retain(rows);
                    choices.preselect(rows, modules);
                    if (updates) {
                        choices.updateAll(rows);
                    }
                    officialStatus.setText(rows.isEmpty() ? ResourcesEngine.getString("plugins.official.empty") : "");
                    render();
                },
                failure -> {
                    if (token != officialToken) {
                        return;
                    }
                    officialStatus.setText(ResourcesEngine.getString("plugins.official.unreadable",
                            UpdateUi.message(failure)));
                    render();
                });
    }

    /** Builds the cards again: after a report, a read of the catalogue, a choice, a filter, an operation. */
    private void render() {
        if (report == null) {
            return;
        }
        boolean blocked = UpdateUi.plugins().flatMap(PluginService::applyBlocked).isPresent();
        cardsBox.getChildren().clear();
        for (PluginCard card : PluginCard.of(rows, report)) {
            if (card.shownBy(filter)) {
                cardsBox.getChildren().add(card(card, blocked));
            }
        }
        if (cardsBox.getChildren().isEmpty()) {
            Label none = new Label(ResourcesEngine.getString("plugins.card.none"));
            none.setStyle(GREY);
            cardsBox.getChildren().add(none);
        }
        Optional<PluginCard> embedded = PluginCard.embedded(report);
        show(embeddedPane, embedded.isPresent());
        embedded.ifPresent(card -> {
            List<String> codes = card.actions().stream().map(ActionLine::code).toList();
            String shown = codes.stream().limit(3).collect(Collectors.joining(", "))
                    + (codes.size() > 3 ? ", …" : "");
            embeddedPane.setText(ResourcesEngine.getString("plugins.embedded", shown));
            embeddedBox.getChildren().setAll(card(card, blocked));
        });
        show(officialStatus, !officialStatus.getText().isEmpty());
        refreshFooter();
    }

    /** One plugin: its header (title, version, state, buttons) and its body. */
    private VBox card(PluginCard card, boolean blocked) {
        Label title = new Label(card.title());
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 1.15em;");
        HBox head = new HBox(8, title);
        head.setAlignment(Pos.CENTER_LEFT);
        if (card.version() != null) {
            head.getChildren().add(new Label(card.version()));
        }
        String color = STATUS_COLORS.get(card.status());
        Label state = new Label(stateText(card), new Circle(5, Color.web(color)));
        state.setStyle("-fx-text-fill: " + color + ";");
        state.setWrapText(true);
        head.getChildren().add(state);
        card.loaded().stream().map(p -> ResourcesEngine.getString("plugins.source." + p.source().name()))
                .filter(source -> !source.isBlank()).distinct()
                .forEach(source -> head.getChildren().add(grey("[" + source + "]")));
        if (catalogueRead && !card.official() && !card.isEmbedded()) {
            head.getChildren().add(grey(ResourcesEngine.getString("plugins.card.unofficial")));
        }
        Region space = new Region();
        HBox.setHgrow(space, Priority.ALWAYS);
        head.getChildren().addAll(space, buttons(card, blocked));

        VBox box = new VBox(4);
        if (!card.isEmbedded()) {
            box.getChildren().add(head); // the embedded card is the content of its own section
        }
        for (String error : card.loadErrors()) {
            Label label = wrapped(error);
            label.setStyle(RED);
            box.getChildren().add(label);
        }
        if (card.official()) {
            String description = UpdateUi.description(card.row().entry());
            if (!description.isBlank()) {
                box.getChildren().add(wrapped(description));
            }
        }
        if (!card.actions().isEmpty()) {
            box.getChildren().add(actions(card));
        }
        if (!card.dependsOn().isEmpty()) {
            box.getChildren().add(labelled("plugins.card.depends-on", String.join(", ", card.dependsOn()), ""));
        }
        if (!card.missing().isEmpty()) {
            box.getChildren().add(labelled("plugins.card.missing", String.join(", ", card.missing()), RED));
        }
        if (card.official() && card.row().entry().license() != null) {
            PluginCatalog.License license = card.row().entry().license();
            box.getChildren().add(folded(ResourcesEngine.getString("plugins.card.license", license.name()),
                    wrapped(UpdateUi.localized(license.notice()))));
        }
        if (card.loaded().stream().anyMatch(p -> p.path() != null || !p.modules().isEmpty())) {
            VBox details = new VBox(8);
            card.loaded().forEach(entry -> details.getChildren().add(technical(entry, card.loaded().size() > 1)));
            box.getChildren().add(folded(ResourcesEngine.getString("plugins.card.details"), details));
        }
        if (!card.isEmbedded()) {
            box.setStyle(CARD);
        }
        return box;
    }

    /**
     * The state in words. An official plugin says what its row says (an update that fixes a plugin refused for its
     * API, a newer version that needs a newer plugin API); one that does not load says so; a plugin the catalogue
     * does not list says how it was loaded.
     */
    private String stateText(PluginCard card) {
        Status status = card.status();
        if (status == Status.FAILED) {
            return ResourcesEngine.getString("plugins.card.failed");
        }
        if (!card.official()) {
            PluginStatus loaded = card.loaded().stream().map(PluginEntry::status)
                    .filter(s -> s == PluginStatus.LOADED).findFirst().orElse(card.loaded().get(0).status());
            return ResourcesEngine.getString("plugins.status." + loaded.name());
        }
        PluginRow row = card.row();
        String text = switch (row.state()) {
            case INCOMPATIBLE -> ResourcesEngine.getString("plugins.state.INCOMPATIBLE", row.entry().engine(),
                    AppInfo.pluginApiVersion());
            case UPDATE_AVAILABLE -> ResourcesEngine.getString("plugins.state.update-to", row.entry().version());
            default -> ResourcesEngine.getString("plugins.state." + row.state().name());
        };
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

    /** The choice made (with Cancel), else the buttons the card offers; disabled while changes cannot be applied. */
    private HBox buttons(PluginCard card, boolean blocked) {
        HBox buttons = new HBox(6);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        if (!card.official()) {
            return buttons;
        }
        PluginRow row = card.row();
        Optional<Action> chosen = choices.chosen(row);
        if (chosen.isPresent()) {
            Label label = new Label(ResourcesEngine.getString("plugins.chosen." + chosen.get().name()));
            label.setStyle("-fx-font-weight: bold; -fx-text-fill: #0d47a1;");
            Button undo = new Button(ResourcesEngine.getString("plugins.action.undo"));
            undo.setOnAction(e -> {
                choices.cancel(row);
                render();
            });
            buttons.getChildren().addAll(label, undo);
            return buttons;
        }
        for (PluginCard.Action action : card.offered(repairModules.contains(row.entry().module()))) {
            Optional<Action> choice = action.choice();
            Button button = new Button(choice
                    .map(c -> ResourcesEngine.getString("plugins.action." + c.name()))
                    .orElseGet(() -> ResourcesEngine.getString("menu.edit.updates")));
            if (choice.isPresent()) {
                button.setDisable(blocked);
                button.setOnAction(e -> {
                    choices.choose(row, choice.get());
                    render();
                });
            } else {
                // the Updates window: the update of an installed plugin goes with that of Copybot when it must
                // over this window when it is modal (opened from an editor): otherwise it would take no input
                button.setOnAction(e -> {
                    Window here = button.getScene().getWindow();
                    Window modalOwner = here instanceof Stage stage && stage.getModality() != Modality.NONE
                            ? here : null;
                    CopybotMainUi.mainController().ifPresent(main -> main.openUpdates(modalOwner));
                });
            }
            buttons.getChildren().add(button);
        }
        return buttons;
    }

    /** The actions provided, one per line: name, type (the section of the editor) and the code of a step. */
    private Node actions(PluginCard card) {
        VBox lines = new VBox(2);
        for (ActionLine action : card.actions()) {
            String type = ResourcesEngine.getString("editor.section." + action.type().name());
            Label name = new Label(action.named() ? action.name() + " (" + type + ")" : "(" + type + ")");
            Label code = new Label(action.code());
            code.setStyle("-fx-font-family: monospace;");
            HBox line = action.named() ? new HBox(6, name, grey("·"), code) : new HBox(6, code, name);
            line.setAlignment(Pos.BASELINE_LEFT);
            lines.getChildren().add(line);
        }
        card.pipelinePlugin().ifPresent(plugin -> {
            Label hint = grey(ResourcesEngine.getString("plugins.card.pipeline-plugin", plugin));
            hint.setStyle(GREY + " -fx-font-size: 0.9em;");
            lines.getChildren().add(hint);
        });
        return labelled("plugins.actions", lines);
    }

    /** One plugin directory found: its folder (opened from here), how it was loaded and its modules. */
    private Node technical(PluginEntry entry, boolean several) {
        VBox box = new VBox(4);
        if (several) {
            Label status = new Label(entry.name() + (entry.version() != null ? " " + entry.version() : "")
                    + " — " + ResourcesEngine.getString("plugins.status." + entry.status().name()));
            status.setStyle("-fx-font-weight: bold;");
            box.getChildren().add(status);
            if (entry.status() == PluginStatus.IGNORED && entry.message() != null) {
                box.getChildren().add(wrapped(entry.message())); // why this one is not the one loaded
            }
        }
        if (entry.path() != null) {
            Label path = wrapped(entry.path().toString());
            HBox.setHgrow(path, Priority.ALWAYS);
            Button open = new Button(ResourcesEngine.getString("plugins.open-folder"));
            open.setOnAction(e -> openFolder(entry.path()));
            HBox folder = new HBox(8, new Label(ResourcesEngine.getString("plugins.folder")), path, open);
            folder.setAlignment(Pos.CENTER_LEFT);
            box.getChildren().add(folder);
        }
        if (!entry.modules().isEmpty()) {
            GridPane modules = new GridPane();
            modules.setHgap(12);
            modules.setVgap(2);
            String[] columns = {"plugins.col.name", "plugins.col.version", "plugins.col.main", "plugins.col.automatic",
                    "plugins.col.location"};
            for (int i = 0; i < columns.length; i++) {
                Label header = new Label(ResourcesEngine.getString(columns[i]));
                header.setStyle("-fx-font-weight: bold;");
                modules.add(header, i, 0);
            }
            int line = 1;
            for (ModuleEntry module : entry.modules()) {
                modules.addRow(line++, new Label(module.name()),
                        new Label(Optional.ofNullable(module.version()).orElse("")),
                        new Label(module.main() ? "✔" : ""), new Label(module.automatic() ? "✔" : ""),
                        wrapped(Optional.ofNullable(module.location()).map(Path::toString).orElse("")));
            }
            box.getChildren().add(labelled("plugins.modules", modules));
        }
        return box;
    }

    private static void openFolder(Path path) {
        try {
            CopybotMainUi.HOST_SERVICES.showDocument(path.toUri().toString());
        } catch (RuntimeException e) {
            PopinUtil.showError(e);
        }
    }

    /** Shown again when an operation starts or ends: the buttons are disabled while it runs. */
    void refreshActions() {
        render();
    }

    /**
     * What is chosen and the button that applies it, with the reason it is disabled (not the packaged release, not
     * Windows, an operation running).
     */
    private void refreshFooter() {
        Optional<String> blocked = UpdateUi.plugins().flatMap(PluginService::applyBlocked);
        int pending = choices.toInstallOrUpdate(rows).size() + choices.toRemove(rows).size();
        pendingLabel.setText(ResourcesEngine.getString("plugins.pending", pending));
        applyReason.setText(blocked.map(key -> ResourcesEngine.getString(key)).orElse(""));
        applyButton.setDisable(blocked.isPresent() || pending == 0);
    }

    @FXML
    protected void onApplyClick() {
        UpdateUi.applyPlugins(choices.toInstallOrUpdate(rows), choices.toRemove(rows),
                applyButton.getScene().getWindow());
    }

    @FXML
    protected void onCloseClick() {
        ((Stage) closeButton.getScene().getWindow()).close();
    }

    // ---- small nodes ----

    private static Label wrapped(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    private static Label grey(String text) {
        Label label = new Label(text);
        label.setStyle(GREY);
        return label;
    }

    /** A bold label before a value, on one line; the value wraps. */
    private static Node labelled(String key, String value, String style) {
        Label text = wrapped(value);
        text.setStyle(style);
        return labelled(key, text);
    }

    private static Node labelled(String key, Node value) {
        Label label = new Label(ResourcesEngine.getString(key));
        label.setStyle("-fx-font-weight: bold;");
        label.setMinWidth(Region.USE_PREF_SIZE);
        HBox.setHgrow(value, Priority.ALWAYS);
        HBox line = new HBox(10, label, value);
        line.setAlignment(Pos.TOP_LEFT);
        return line;
    }

    /** A section closed until clicked: the licence, the technical details. */
    private static TitledPane folded(String title, Node content) {
        TitledPane pane = new TitledPane(title, content);
        pane.setExpanded(false);
        pane.setAnimated(false);
        return pane;
    }

    private static void show(Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }
}

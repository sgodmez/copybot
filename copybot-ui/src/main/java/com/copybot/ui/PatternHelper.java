package com.copybot.ui;

import com.copybot.engine.sample.Sample;
import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.model.PatternHelperModel;
import com.copybot.ui.model.PatternHelperModel.KeyRow;
import com.copybot.ui.model.PatternHelperModel.Row;
import com.copybot.ui.model.PatternHelperModel.Summary;
import javafx.application.Platform;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.FileChooser;
import javafx.stage.Popup;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The helper of the editor's output pattern field (spec pattern-helper §5): a {+} button beside the field opening
 * the key picker, then under the field one summary line (counts, sample, or loading / failure / syntax error), one
 * example line, and on demand the table of the computed paths with the files the user chose to test.
 * Display only: the computations are in {@link PatternHelperModel}, the sample and the tested files are taken by
 * the editor, whose {@link State} outlives the form.
 */
final class PatternHelper extends VBox {

    /** A file the user chose to test; its analysis, null while it runs. */
    record Pinned(Path file, Sample result) {
    }

    /** What outlives a rebuilt form while the editor is open: the panel expanded, the tested files (newest first). */
    static final class State {
        boolean expanded;
        final List<Pinned> pinned = new ArrayList<>();
    }

    /** What the helper asks the editor. */
    interface Actions {
        /** Takes the sample again (after a failure or a cancel). */
        void retry();

        /** Cancels the sample being taken. */
        void cancel();

        /** Analyses this file and pins it on top of the table. */
        void test(Path file);

        void unpin(Path file);

        /** The folder the file chooser opens in, null for the default. */
        Path sourceFolder();
    }

    private static final String RED = "#d9534f";
    private static final String GREY_STYLE = "-fx-font-size: 11px; -fx-text-fill: #666666;";
    private static final String PILL_STYLE = "-fx-font-size: 11px; -fx-background-radius: 8; -fx-padding: 0 6 0 6;";
    private static final String CHIP_STYLE = "-fx-font-family: monospace; -fx-font-size: 11px; -fx-padding: 0 4 0 4;"
            + " -fx-background-color: #f4f4f4; -fx-border-color: #d0d0d0; -fx-border-radius: 3; -fx-background-radius: 3;";
    private static final String ROW_HOVER_STYLE = "-fx-background-color: #e8f0fe;";
    /** The table of the panel shows about this many rows, then scrolls. */
    private static final double TABLE_MAX_HEIGHT = 6 * 22 + 24;
    static final String KEY_ROW_CLASS = "pattern-helper-key-row";
    static final String TEST_FILE_CLASS = "pattern-helper-test-file";

    private final TextInputControl input;
    private final Supplier<String> onMissingKey;
    private final State state;
    private final Actions actions;

    private final Button pickerButton = new Button("{+}");
    private final HBox summaryLine = new HBox(6);
    private final TextFlow exampleLine = new TextFlow();
    private final VBox panel = new VBox(4);
    private final Hyperlink toggle = new Hyperlink();
    private final Popup picker = new Popup();
    private final TextField filter = new TextField();
    private final VBox keyRows = new VBox();
    private final GridPane table = new GridPane();
    private final ScrollPane tableScroll = new ScrollPane(table);

    /** The last sample shown, null before the first one. */
    private Sample sample;
    private boolean loading;
    /** Where the picker inserts: the caret when it opened if the field had the focus, else -1 (at the end). */
    private int pickerCaret = -1;
    private List<KeyRow> pickerKeys = List.of();

    PatternHelper(TextInputControl input, Supplier<String> onMissingKey, State state, Actions actions) {
        super(3);
        this.input = input;
        this.onMissingKey = onMissingKey;
        this.state = state;
        this.actions = actions;
        summaryLine.setAlignment(Pos.CENTER_LEFT);
        // not focusable: a click must leave the focus, hence the caret, in the field (a TextField losing the
        // focus moves its caret to 0, and one gaining it by requestFocus selects all its text)
        pickerButton.setFocusTraversable(false);
        pickerButton.setTooltip(new Tooltip(ResourcesEngine.getString("helper.picker")));
        pickerButton.setOnAction(e -> openPicker());
        toggle.setFocusTraversable(false);
        toggle.setOnAction(e -> {
            state.expanded = !state.expanded;
            toggle.setVisited(false);
            refresh();
        });
        buildPicker();
        buildTable();
        getChildren().addAll(summaryLine, exampleLine, panel);
        input.textProperty().addListener((obs, old, text) -> refresh());
        refresh();
    }

    /** The {+} button, shown right of the field. */
    Button pickerButton() {
        return pickerButton;
    }

    /** The key picker (tests). */
    Popup picker() {
        return picker;
    }

    /** A sample is being taken: the last one stays shown until it arrives. */
    void showLoading() {
        loading = true;
        refresh();
    }

    void showSample(Sample sample) {
        this.sample = sample;
        loading = false;
        refresh();
    }

    private boolean usable() {
        return sample != null && sample.failure().isEmpty();
    }

    private String pattern() {
        return input.getText() == null ? "" : input.getText();
    }

    /** Recomputes everything from the field's text, the last sample and the tested files. */
    void refresh() {
        Summary summary = usable() ? PatternHelperModel.summary(pattern(), onMissingKey.get(), sample) : null;
        Optional<String> syntaxError = PatternHelperModel.syntaxError(pattern());
        summaryLine(summary, syntaxError);
        exampleLine.getChildren().clear();
        boolean example = summary != null && syntaxError.isEmpty() && summary.example().isPresent();
        if (example) {
            exampleLine.getChildren().setAll(pathTexts(summary.example().get(), true));
        }
        visible(exampleLine, example);
        // without a usable sample (listing failed or cancelled) the tested files still show: they need no listing
        boolean expanded = usable() ? state.expanded : !state.pinned.isEmpty();
        panel.getChildren().clear();
        if (expanded) {
            fillPanel(summary);
        }
        visible(panel, expanded);
        if (picker.isShowing()) {
            fillKeys();
        }
    }

    private void summaryLine(Summary summary, Optional<String> syntaxError) {
        List<Node> nodes = new ArrayList<>();
        if (loading) {
            ProgressIndicator indicator = new ProgressIndicator();
            indicator.setPrefSize(14, 14);
            indicator.setMaxSize(14, 14);
            Button cancel = smallButton(ResourcesEngine.getString("helper.cancel"), actions::cancel);
            nodes.addAll(List.of(grey(ResourcesEngine.getString("helper.loading")), indicator, cancel));
        } else if (sample != null && sample.failure().isPresent()) {
            // a file can be tested without the listing (no card, NAS asleep...)
            nodes.addAll(List.of(grey(sample.failure().get()),
                    smallButton(ResourcesEngine.getString("helper.retry"), actions::retry), testFileButton()));
        }
        if (syntaxError.isPresent()) {
            Label error = new Label(syntaxError.get());
            error.setWrapText(true);
            error.setStyle("-fx-font-size: 11px; -fx-text-fill: " + RED + ";");
            nodes.add(error);
        } else if (!loading && summary != null) {
            nodes.add(pill(ResourcesEngine.getString("helper.ok", summary.ok()), "#dff0d8", "#2e7d32"));
            if (summary.missing() > 0) {
                nodes.add(pill(ResourcesEngine.getString("helper.missing", summary.missing(), summary.effect()),
                        "#f8d7da", "#b52a2a"));
            }
        }
        if (!loading && usable()) {
            nodes.add(grey(PatternHelperModel.sampleLine(sample)));
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            toggle.setText(state.expanded ? ResourcesEngine.getString("helper.collapse")
                    : ResourcesEngine.getString("helper.expand", PatternHelperModel.sampledFiles(sample)));
            nodes.addAll(List.of(spacer, toggle));
        }
        summaryLine.getChildren().setAll(nodes);
    }

    // ---- panel ----

    /** Built once: refilled on every refresh (each keystroke), so its scroll position stays. */
    private void buildTable() {
        table.setHgap(12);
        table.setVgap(2);
        ColumnConstraints file = new ColumnConstraints();
        ColumnConstraints path = new ColumnConstraints();
        path.setHgrow(Priority.ALWAYS);
        table.getColumnConstraints().addAll(file, path);
        tableScroll.setFitToWidth(true);
        tableScroll.setMaxHeight(TABLE_MAX_HEIGHT);
    }

    /**
     * The table (tested files on top, then the sample's rows when it is usable), the notes, and the test button
     * (on the failure line instead when the sample is not usable).
     */
    private void fillPanel(Summary summary) {
        double scrolled = tableScroll.getVvalue();
        table.getChildren().clear();
        Label fileHeader = grey(ResourcesEngine.getString("helper.column.file"));
        Label pathHeader = grey(ResourcesEngine.getString("helper.column.path"));
        fileHeader.setStyle(GREY_STYLE + " -fx-font-weight: bold;");
        pathHeader.setStyle(GREY_STYLE + " -fx-font-weight: bold;");
        table.addRow(0, fileHeader, pathHeader);
        int line = 1;
        Set<String> notes = new LinkedHashSet<>();
        for (Pinned pinned : state.pinned) {
            line = pinnedRows(table, line, pinned, notes);
        }
        if (summary != null) {
            for (Row row : summary.rows()) {
                table.addRow(line++, new Label(row.name()), new TextFlow(pathTexts(row, false).toArray(Node[]::new)));
            }
        }
        panel.getChildren().add(tableScroll);
        tableScroll.setVvalue(scrolled);
        if (usable()) {
            notes.addAll(sample.notes());
        }
        for (String note : notes) {
            panel.getChildren().add(grey(note));
        }
        if (usable()) {
            panel.getChildren().add(testFileButton());
        }
    }

    private Button testFileButton() {
        Button test = smallButton(ResourcesEngine.getString("helper.test-file"), this::chooseFile);
        test.getStyleClass().add(TEST_FILE_CLASS);
        return test;
    }

    /** The rows of a tested file: "analysing…", its failure, its notes when it gives no item, or its items. */
    private int pinnedRows(GridPane table, int line, Pinned pinned, Set<String> notes) {
        String name = String.valueOf(pinned.file().getFileName());
        Sample result = pinned.result();
        if (result == null) {
            table.addRow(line, pinnedCell(pinned, name), grey(ResourcesEngine.getString("helper.analysing")));
            return line + 1;
        }
        if (result.failure().isPresent()) {
            table.addRow(line, pinnedCell(pinned, name), new TextFlow(red(result.failure().get())));
            return line + 1;
        }
        if (result.items().isEmpty()) {
            table.addRow(line, pinnedCell(pinned, name), grey(String.join(" · ", result.notes())));
            return line + 1;
        }
        notes.addAll(result.notes());
        List<Row> rows = PatternHelperModel.rows(pattern(), result);
        if (rows.isEmpty()) { // an invalid pattern: the file stays listed, removable
            table.addRow(line, pinnedCell(pinned, name));
            return line + 1;
        }
        boolean first = true;
        for (Row row : rows) {
            Node cell = first ? pinnedCell(pinned, row.name()) : new Label("📌 " + row.name());
            table.addRow(line++, cell, new TextFlow(pathTexts(row, false).toArray(Node[]::new)));
            first = false;
        }
        return line;
    }

    private HBox pinnedCell(Pinned pinned, String name) {
        Button remove = smallButton("✕", () -> actions.unpin(pinned.file()));
        remove.setTooltip(new Tooltip(ResourcesEngine.getString("helper.unpin")));
        remove.setStyle("-fx-font-size: 9px; -fx-padding: 0 3 0 3;");
        HBox cell = new HBox(4, new Label("📌 " + name), remove);
        cell.setAlignment(Pos.CENTER_LEFT);
        return cell;
    }

    private void chooseFile() {
        FileChooser chooser = new FileChooser();
        Path folder = actions.sourceFolder();
        if (folder != null && Files.isDirectory(folder)) {
            chooser.setInitialDirectory(folder.toFile());
        }
        File chosen = chooser.showOpenDialog(getScene() == null ? null : getScene().getWindow());
        if (chosen != null) {
            actions.test(chosen.toPath());
        }
    }

    /** "name → " (on the example line) then the path, each missing expression in red; or the error in red. */
    private static List<Node> pathTexts(Row row, boolean withName) {
        List<Node> texts = new ArrayList<>();
        if (withName) {
            texts.add(new Text(row.name() + " → "));
        }
        if (row.error() != null) {
            texts.add(red(row.error()));
            return texts;
        }
        String path = row.path();
        int from = 0;
        for (String missing : row.missing()) {
            int at = path.indexOf(missing, from);
            if (at < 0) {
                continue;
            }
            texts.add(new Text(path.substring(from, at)));
            texts.add(red(missing));
            from = at + missing.length();
        }
        texts.add(new Text(path.substring(from)));
        return texts;
    }

    // ---- key picker ----

    private void buildPicker() {
        filter.setPromptText(ResourcesEngine.getString("helper.filter"));
        filter.textProperty().addListener((obs, old, text) -> fillKeys());
        filter.setOnAction(e -> { // Enter after typing a filter: its first key
            List<KeyRow> shown = filtered();
            if (!filter.getText().isBlank() && !shown.isEmpty()) {
                insertKey(shown.getFirst().key());
            }
        });
        ScrollPane scroll = new ScrollPane(keyRows);
        scroll.setFitToWidth(true);
        scroll.setMaxHeight(300);
        scroll.setPrefWidth(460);
        VBox content = new VBox(6, filter, scroll);
        content.setStyle("-fx-background-color: white; -fx-border-color: #b0b0b0; -fx-padding: 6;"
                + " -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.25), 8, 0, 0, 2);");
        picker.getContent().add(content);
        picker.setAutoHide(true);
        picker.setHideOnEscape(true);
    }

    private void openPicker() {
        boolean focused = input.getScene() != null && input.getScene().getFocusOwner() == input;
        pickerCaret = focused ? input.getCaretPosition() : -1;
        pickerKeys = PatternHelperModel.keys(usable() ? sample : null);
        filter.clear();
        fillKeys();
        Point2D at = pickerButton.localToScreen(0, pickerButton.getHeight());
        if (at == null) {
            return; // not on a shown window
        }
        picker.show(pickerButton, at.getX(), at.getY());
        Platform.runLater(filter::requestFocus);
    }

    private List<KeyRow> filtered() {
        String text = filter.getText() == null ? "" : filter.getText().trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return pickerKeys;
        }
        return pickerKeys.stream().filter(row -> row.key().toLowerCase(Locale.ROOT).contains(text)
                || row.examples().stream().anyMatch(v -> v.toLowerCase(Locale.ROOT).contains(text))).toList();
    }

    private void fillKeys() {
        List<Node> rows = new ArrayList<>();
        Label keyHeader = grey(ResourcesEngine.getString("helper.column.key"));
        keyHeader.setMinWidth(180);
        keyHeader.setPrefWidth(180);
        rows.add(new HBox(8, keyHeader, grey(ResourcesEngine.getString("helper.column.examples"))));
        for (KeyRow row : filtered()) {
            rows.add(keyRow(row));
        }
        keyRows.getChildren().setAll(rows);
    }

    /** One key: a single click inserts it. */
    private HBox keyRow(KeyRow row) {
        Label key = new Label("{" + row.key() + "}");
        HBox keyCell = new HBox(4, key);
        keyCell.setAlignment(Pos.CENTER_LEFT);
        if (row.partial()) {
            Label warning = new Label("⚠");
            warning.setStyle("-fx-text-fill: #c87f0a;");
            Tooltip.install(warning, new Tooltip(PatternHelperModel.missingTooltip(row)));
            keyCell.getChildren().add(warning);
        }
        keyCell.setMinWidth(180);
        keyCell.setPrefWidth(180);
        HBox chips = new HBox(4);
        for (String example : row.examples()) {
            Label chip = new Label(example);
            chip.setStyle(CHIP_STYLE);
            chips.getChildren().add(chip);
        }
        HBox line = new HBox(8, keyCell, chips);
        line.setAlignment(Pos.CENTER_LEFT);
        line.setStyle("-fx-padding: 2 4 2 4;");
        line.getStyleClass().add(KEY_ROW_CLASS);
        line.setUserData(row.key());
        line.setOnMouseEntered(e -> line.setStyle("-fx-padding: 2 4 2 4; " + ROW_HOVER_STYLE));
        line.setOnMouseExited(e -> line.setStyle("-fx-padding: 2 4 2 4;"));
        line.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                insertKey(row.key());
            }
        });
        return line;
    }

    /** Inserts {key} where the caret was when the picker opened (at the end if the field had not the focus). */
    private void insertKey(String key) {
        String text = pattern();
        int caret = pickerCaret >= 0 ? Math.min(pickerCaret, text.length()) : text.length();
        picker.hide();
        input.setText(PatternHelperModel.insert(text, caret, key));
        if (input.getScene() == null || input.getScene().getFocusOwner() != input) {
            input.requestFocus();
        }
        input.positionCaret(caret + key.length() + 2);
    }

    // ---- small nodes ----

    /** Hidden nodes take no room. */
    private static void visible(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private static Text red(String text) {
        Text node = new Text(text);
        node.setFill(Color.web(RED));
        return node;
    }

    private static Label grey(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setStyle(GREY_STYLE);
        return label;
    }

    private static Label pill(String text, String background, String foreground) {
        Label label = new Label(text);
        label.setStyle(PILL_STYLE + " -fx-background-color: " + background + "; -fx-text-fill: " + foreground + ";");
        label.setMinWidth(Region.USE_PREF_SIZE);
        return label;
    }

    private static Button smallButton(String text, Runnable action) {
        Button button = new Button(text);
        button.setStyle("-fx-font-size: 11px; -fx-padding: 1 6 1 6;");
        button.setFocusTraversable(false);
        button.setOnAction(e -> action.run());
        return button;
    }
}

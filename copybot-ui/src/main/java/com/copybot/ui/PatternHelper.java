package com.copybot.ui;

import com.copybot.engine.sample.Sample;
import com.copybot.plugin.api.config.ConfigSchema;
import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.model.PatternHelperModel;
import com.copybot.ui.model.PatternHelperModel.KeyRow;
import com.copybot.ui.model.PatternHelperModel.Preview;
import com.copybot.ui.model.PatternHelperModel.PreviewRow;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The helper shown under the output pattern field of the editor (spec pattern-helper §5): the state of the sample,
 * its notes, the keys it offers (a click inserts one at the caret) and the preview of the pattern over its items.
 * Display only: the computations are in {@link PatternHelperModel}, the sample is taken by the editor.
 */
final class PatternHelper extends VBox {

    private static final String RED = "#d9534f";
    private static final String SMALL_STYLE = "-fx-font-size: 11px; -fx-text-fill: #555555;";

    private final TextInputControl input;
    private final Supplier<String> onMissingKey;

    private final Label status = small("");
    private final ProgressIndicator indicator = new ProgressIndicator();
    private final Button cancel = new Button(ResourcesEngine.getString("helper.cancel"));
    private final Button retry = new Button(ResourcesEngine.getString("helper.retry"));
    private final VBox notes = new VBox(2);
    /** One line per key: the link, its example values, its presence. */
    private final GridPane keys = new GridPane();
    private final Label syntaxError = small("");
    private final Label previewTitle = new Label(ResourcesEngine.getString("helper.preview"));
    private final VBox preview = new VBox(2);

    /** The last sample shown, null before the first one. */
    private Sample sample;

    /**
     * @param onMissingKey the current "onMissingKey" of the step (null or "" for the default)
     * @param retry        takes the sample again (after a failure or a cancel)
     * @param cancel       cancels the sample being taken
     */
    PatternHelper(TextInputControl input, Supplier<String> onMissingKey, Runnable retry, Runnable cancel) {
        super(4);
        this.input = input;
        this.onMissingKey = onMissingKey;
        keys.setHgap(10);
        indicator.setPrefSize(16, 16);
        indicator.setMaxSize(16, 16);
        this.cancel.setOnAction(e -> cancel.run());
        this.retry.setOnAction(e -> retry.run());
        syntaxError.setStyle("-fx-font-size: 11px; -fx-text-fill: " + RED + ";");
        HBox statusRow = new HBox(6, status, indicator, this.cancel, this.retry);
        statusRow.setAlignment(Pos.CENTER_LEFT);
        Label keysTitle = new Label(ResourcesEngine.getString("helper.keys"));
        getChildren().addAll(statusRow, notes, keysTitle, keys, syntaxError, previewTitle, preview);
        setLoadingVisible(false);
        visible(this.retry, false);
        input.textProperty().addListener((obs, old, text) -> refresh());
        refresh();
    }

    /** A sample is being taken: what is shown stays until it arrives. */
    void showLoading() {
        status.setText(ResourcesEngine.getString("helper.loading"));
        setLoadingVisible(true);
        visible(retry, false);
    }

    void showSample(Sample sample) {
        this.sample = sample;
        setLoadingVisible(false);
        visible(retry, sample.failure().isPresent());
        status.setText(PatternHelperModel.status(sample));
        notes.getChildren().clear();
        for (String note : sample.notes()) {
            notes.getChildren().add(small(note));
        }
        refresh();
    }

    /** Recomputes the keys and the preview from the field's current text and the last sample. */
    void refresh() {
        keys.getChildren().clear();
        preview.getChildren().clear();
        String text = input.getText() == null ? "" : input.getText();
        // the syntax error is shown whatever the state of the sample (spec §5)
        Optional<String> error = PatternHelperModel.syntaxError(text);
        syntaxError.setText(error.orElse(""));
        visible(syntaxError, error.isPresent());
        if (sample == null || sample.failure().isPresent()) {
            // no sample (yet, or it failed): the fixed list of known variables, as before the helper
            int line = 0;
            for (String variable : ConfigSchema.PATTERN_VARIABLES) {
                keys.add(small("{" + variable + "}"), 0, line++);
            }
            visible(previewTitle, false);
            visible(preview, false);
            return;
        }
        int line = 0;
        for (KeyRow row : PatternHelperModel.keys(sample)) {
            Label examples = small(String.join(" · ", row.examples()));
            Label presence = small("(" + row.present() + "/" + row.total() + ")");
            keys.add(keyLink(row), 0, line);
            keys.add(examples, 1, line);
            keys.add(presence, 2, line);
            line++;
        }
        Preview result = PatternHelperModel.preview(text, onMissingKey.get(), sample);
        visible(previewTitle, error.isEmpty());
        visible(preview, error.isEmpty());
        for (PreviewRow row : result.rows()) {
            preview.getChildren().add(previewRow(row));
        }
    }

    private Hyperlink keyLink(KeyRow row) {
        Hyperlink link = new Hyperlink("{" + row.key() + "}");
        link.setTooltip(new Tooltip(String.join(" · ", row.examples())
                + "  (" + row.present() + "/" + row.total() + ")"));
        if (row.partial()) {
            link.setStyle("-fx-text-fill: #c87f0a;");
        }
        // not focusable: a click must leave the focus, hence the caret, in the field (a TextField losing the
        // focus moves its caret to 0, and one gaining it by requestFocus selects all its text)
        link.setFocusTraversable(false);
        link.setOnAction(e -> {
            String text = input.getText() == null ? "" : input.getText();
            boolean focused = input.getScene() != null && input.getScene().getFocusOwner() == input;
            int caret = focused ? input.getCaretPosition() : text.length(); // never clicked in: at the end
            input.setText(PatternHelperModel.insert(text, caret, row.key()));
            if (!focused) {
                input.requestFocus();
            }
            input.positionCaret(caret + row.key().length() + 2);
        });
        return link;
    }

    /** "name → path", each missing expression of the path in red, then the effect of the missing key. */
    private static Node previewRow(PreviewRow row) {
        if (row.error() != null) {
            return new TextFlow(red(row.name() + " : " + row.error()));
        }
        List<Text> texts = new ArrayList<>();
        texts.add(new Text(row.name() + " → "));
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
        if (row.effect() != null) {
            texts.add(red(" " + row.effect()));
        }
        return new TextFlow(texts.toArray(Node[]::new));
    }

    private void setLoadingVisible(boolean loading) {
        visible(indicator, loading);
        visible(cancel, loading);
    }

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

    private static Label small(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setStyle(SMALL_STYLE);
        return label;
    }
}

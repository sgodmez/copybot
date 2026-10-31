package com.copybot.ui;

import com.copybot.plugin.api.action.WorkItem;
import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.model.OutPatternModel;
import com.copybot.ui.model.PatternHelperModel;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Window;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The "Output pattern" dialog of the plan view (spec own-out-pattern §3): the pattern of the chosen files, prefilled
 * with their shared own pattern or the pipeline's, a {+} key picker, the pipeline pattern as a reminder, and the
 * live preview (target of the first files, files lacking a key with the step's consequence, syntax error). Display
 * only: the computations are in {@link OutPatternModel}.
 */
final class OutPatternDialog {

    private static final String GREY_STYLE = "-fx-font-size: 11px; -fx-text-fill: #666666;";

    private final List<WorkItem> items;
    private final String onMissingKey;
    private final Dialog<String> dialog = new Dialog<>();
    private final TextField field = new TextField();
    private final MenuButton picker = new MenuButton("{+}");
    private final Label pipeline = new Label();
    private final Label error = new Label();
    private final Label missing = new Label();
    private final VBox rows = new VBox(2);
    /** Where the picker inserts: the caret when it opened if the field had the focus, else -1 (at the end). */
    private int pickerCaret = -1;

    /** The pattern chosen for these files, empty on Cancel. */
    static Optional<String> show(Window owner, List<WorkItem> items, String defaultPattern, String onMissingKey) {
        return new OutPatternDialog(owner, items, defaultPattern, onMissingKey).dialog.showAndWait();
    }

    OutPatternDialog(Window owner, List<WorkItem> items, String defaultPattern, String onMissingKey) {
        this.items = List.copyOf(items);
        this.onMissingKey = onMissingKey;
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setTitle(ResourcesEngine.getString("out-pattern.title"));
        dialog.setHeaderText(ResourcesEngine.getString("out-pattern.files", items.size()));
        dialog.setResizable(true);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        field.setText(OutPatternModel.initialPattern(this.items, defaultPattern));
        field.setPrefColumnCount(40);
        HBox.setHgrow(field, Priority.ALWAYS);
        // not focusable: the focus, hence the caret, stays in the field (see PatternHelper)
        picker.setFocusTraversable(false);
        picker.setTooltip(new Tooltip(ResourcesEngine.getString("helper.picker")));
        for (String key : OutPatternModel.keys(this.items)) {
            MenuItem item = new MenuItem("{" + key + "}");
            item.setMnemonicParsing(false);
            item.setOnAction(e -> insertKey(key));
            picker.getItems().add(item);
        }
        picker.setOnShowing(e -> {
            boolean focused = field.getScene() != null && field.getScene().getFocusOwner() == field;
            pickerCaret = focused ? field.getCaretPosition() : -1;
        });
        HBox line = new HBox(4, field, picker);
        line.setAlignment(Pos.CENTER_LEFT);
        pipeline.setText(ResourcesEngine.getString("out-pattern.pipeline", defaultPattern));
        pipeline.setStyle(GREY_STYLE);
        error.setWrapText(true);
        error.setStyle("-fx-font-size: 11px; -fx-text-fill: #d9534f;");
        missing.setStyle("-fx-font-size: 11px; -fx-background-radius: 8; -fx-padding: 0 6 0 6;"
                + " -fx-background-color: #f8d7da; -fx-text-fill: #b52a2a;");
        VBox content = new VBox(6, line, pipeline, error, missing, rows);
        content.setPrefWidth(560);
        dialog.getDialogPane().setContent(content);
        dialog.setResultConverter(button -> button == ButtonType.OK ? field.getText() : null);

        field.textProperty().addListener((obs, old, text) -> refresh());
        refresh();
    }

    /** Recomputes the preview from the field and enables OK unless the pattern does not parse. */
    private void refresh() {
        OutPatternModel.Preview preview = OutPatternModel.preview(text(), items, onMissingKey);
        error.setText(preview.syntaxError().orElse(""));
        visible(error, preview.syntaxError().isPresent());
        missing.setText(ResourcesEngine.getString("helper.missing", preview.missing(), preview.effect()));
        visible(missing, preview.missing() > 0);
        rows.getChildren().setAll(preview.rows().stream()
                .map(row -> (Node) new TextFlow(PatternHelper.pathTexts(row, true).toArray(Node[]::new))).toList());
        dialog.getDialogPane().lookupButton(ButtonType.OK).setDisable(!OutPatternModel.canApply(preview));
    }

    /** Inserts {key} where the caret was when the picker opened (at the end if the field had not the focus). */
    private void insertKey(String key) {
        String text = text();
        int caret = pickerCaret >= 0 ? Math.min(pickerCaret, text.length()) : text.length();
        field.setText(PatternHelperModel.insert(text, caret, key));
        field.requestFocus();
        field.positionCaret(caret + key.length() + 2);
        pickerCaret = -1;
    }

    private String text() {
        return field.getText() == null ? "" : field.getText();
    }

    private static void visible(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    // ---- tests ----

    Dialog<String> dialog() {
        return dialog;
    }

    TextField field() {
        return field;
    }

    MenuButton picker() {
        return picker;
    }

    Label pipeline() {
        return pipeline;
    }

    Label error() {
        return error;
    }

    Label missing() {
        return missing;
    }

    /** The preview rows as shown, "name → target". */
    List<String> previewTexts() {
        return rows.getChildren().stream().map(row -> ((TextFlow) row).getChildren().stream()
                .map(text -> ((Text) text).getText()).collect(Collectors.joining())).toList();
    }
}

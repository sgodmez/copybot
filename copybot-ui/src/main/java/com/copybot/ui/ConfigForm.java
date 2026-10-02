package com.copybot.ui;

import com.copybot.plugin.api.config.ConfigField;
import com.copybot.plugin.api.config.ConfigSchema;
import com.copybot.plugin.api.config.FieldHint;
import com.copybot.plugin.api.config.FieldKind;
import com.copybot.resources.ResourcesEngine;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The form generated from configuration fields (spec desktop-ui §3, §4): one control per field kind, the
 * values read and written through an {@link Access} (the document), nothing kept here but the controls whose
 * text does not parse ({@link Invalid}).
 */
final class ConfigForm {

    /** How the form reads and writes the fields (the actionConfig of a step, or the step itself). */
    interface Access {
        String text(ConfigField field);

        /** @throws IllegalArgumentException an invalid value (not a number...): the control turns red */
        void setText(ConfigField field, String text);

        String label(ConfigField field);

        String description(ConfigField field);

        /** The JSON of a value that is not edited as text (a list of records). */
        String json(ConfigField field);

        /**
         * True: the text is written on every edit; false: only on Enter or when the control loses focus (see
         * {@link #COMMIT}).
         */
        default boolean commitOnEdit(ConfigField field) {
            return true;
        }

        /**
         * The helper of a {@link FieldHint#PATTERN} field, built on its text control (spec pattern-helper §5);
         * null: the fixed line of known variables.
         */
        default Helper patternHelper(ConfigField field, TextInputControl input) {
            return null;
        }
    }

    /** What a pattern helper adds to its field: a node right of the control (null for none), one under it. */
    record Helper(Node beside, Node below) {
    }

    /**
     * The controls whose current text does not parse, with the label of their field: the document still holds
     * the last valid value, so a save must be refused while this is not empty. A control leaves it when its
     * text parses again, or when the focus moves to another control (its text is then reset to the document's).
     */
    static final class Invalid {
        private final Map<Control, String> labels = new LinkedHashMap<>();

        boolean isEmpty() {
            return labels.isEmpty();
        }

        List<String> labels() {
            return List.copyOf(labels.values());
        }

        /** The form is rebuilt: its controls are gone. */
        void clear() {
            labels.clear();
        }
    }

    /**
     * Node property of a control that writes only on Enter or focus loss: the {@link Runnable} that writes its
     * pending text (run before a save started while it has the focus).
     */
    static final String COMMIT = "copybot.config-form.commit";

    private static final String INVALID_STYLE = "-fx-border-color: #d9534f;";

    private final Access access;
    private final Window owner;
    private final Invalid invalid;

    private ConfigForm(Access access, Window owner, Invalid invalid) {
        this.access = access;
        this.owner = owner;
        this.invalid = invalid;
    }

    static Node build(List<ConfigField> fields, Access access, Window owner, Invalid invalid) {
        return new ConfigForm(access, owner, invalid).fields(fields);
    }

    private Node fields(List<ConfigField> fields) {
        VBox box = new VBox(10);
        for (ConfigField field : fields) {
            box.getChildren().add(field(field));
        }
        return box;
    }

    private Node field(ConfigField field) {
        if (field.kind() == FieldKind.RECORD) {
            TitledPane pane = new TitledPane(access.label(field), fields(field.children()));
            pane.setExpanded(true);
            return withDescription(pane, access.description(field));
        }
        VBox box = new VBox(3);
        box.getChildren().add(new Label(access.label(field) + (field.required() ? " *" : "")));
        Node control = input(field);
        TextInputControl text = field.hasHint(FieldHint.PATTERN) ? textControl(control) : null;
        Helper helper = text != null ? access.patternHelper(field, text) : null;
        if (helper != null && helper.beside() != null) {
            HBox row = new HBox(5, control, helper.beside());
            row.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(control, Priority.ALWAYS);
            box.getChildren().add(row);
        } else {
            box.getChildren().add(control);
        }
        String description = access.description(field);
        if (!description.isEmpty()) {
            box.getChildren().add(small(description));
        }
        if (field.hasHint(FieldHint.PATTERN)) {
            if (helper != null) {
                box.getChildren().add(helper.below());
            } else {
                String variables = ConfigSchema.PATTERN_VARIABLES.stream().map(v -> "{" + v + "}").collect(Collectors.joining(" "));
                box.getChildren().add(small(ResourcesEngine.getString("editor.pattern-variables", variables)));
            }
        }
        return box;
    }

    /** The text control of an input: the input itself, or the text field of a path input (with its browse button). */
    private static TextInputControl textControl(Node input) {
        if (input instanceof TextInputControl text) {
            return text;
        }
        if (input instanceof HBox row) {
            for (Node child : row.getChildren()) {
                if (child instanceof TextInputControl text) {
                    return text;
                }
            }
        }
        return null;
    }

    private static Node withDescription(Node node, String description) {
        return description.isEmpty() ? node : new VBox(3, node, small(description));
    }

    private static Label small(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setStyle("-fx-font-size: 11px; -fx-text-fill: #555555;");
        return label;
    }

    private Node input(ConfigField field) {
        return switch (field.kind()) {
            case BOOLEAN -> choice(field, List.of("true", "false"));
            case ENUM -> choice(field, field.enumValues());
            case LIST -> list(field);
            case PATH -> path(field);
            default -> text(new TextField(), field);
        };
    }

    /**
     * The values only: an absent field shows its default selected (nothing is written until another choice).
     * "" (absent) is offered only for an optional field without default; a value outside them is kept and shown.
     */
    private Node choice(ConfigField field, List<String> values) {
        ComboBox<String> combo = new ComboBox<>();
        List<String> items = new ArrayList<>();
        if (field.defaultValue() == null && !field.required()) {
            items.add("");
        }
        items.addAll(values);
        combo.getItems().setAll(items);
        combo.setConverter(new StringConverter<>() {
            @Override
            public String toString(String value) {
                return value == null ? "" : value;
            }

            @Override
            public String fromString(String s) {
                return s; // not editable
            }
        });
        boolean[] showing = {true}; // a selection made by show() is not an edit: the default stays absent
        show(combo, field);
        showing[0] = false;
        combo.valueProperty().addListener((obs, old, value) -> {
            if (!showing[0]) {
                apply(combo, field, value == null ? "" : value);
            }
        });
        combo.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused && invalid.labels.containsKey(combo) && focusMovedAway(combo)) {
                showing[0] = true;
                show(combo, field);
                showing[0] = false;
                markValid(combo);
            }
        });
        return combo;
    }

    /** Selects the value the document holds, or the default when it is absent (null: no selection). */
    private void show(ComboBox<String> combo, ConfigField field) {
        String text = access.text(field);
        if (text.isEmpty() && field.defaultValue() != null) {
            text = field.defaultValue();
        }
        if (text.isEmpty() && !combo.getItems().contains(text)) {
            combo.setValue(null);
            return;
        }
        if (!combo.getItems().contains(text)) {
            combo.getItems().add(text);
        }
        combo.setValue(text);
    }

    /** One element per line; a list of records is shown as JSON, read-only (kept as is). */
    private Node list(ConfigField field) {
        TextArea area = new TextArea();
        area.setPrefRowCount(3);
        FieldKind element = field.elementSchema() == null ? FieldKind.STRING : field.elementSchema().kind();
        if (element == FieldKind.RECORD || element == FieldKind.LIST) {
            area.setText(access.json(field));
            area.setEditable(false);
            return area;
        }
        return text(area, field);
    }

    private Node path(ConfigField field) {
        TextField textField = new TextField();
        text(textField, field);
        Button browse = new Button(ResourcesEngine.getString("editor.browse"));
        browse.setOnAction(e -> {
            File chosen;
            if (field.hasHint(FieldHint.DIRECTORY)) {
                chosen = new DirectoryChooser().showDialog(owner);
            } else {
                chosen = new FileChooser().showOpenDialog(owner);
            }
            if (chosen != null) {
                textField.setText(chosen.getPath());
                Object commit = textField.getProperties().get(COMMIT);
                if (commit instanceof Runnable pending) {
                    pending.run();
                }
            }
        });
        HBox.setHgrow(textField, Priority.ALWAYS);
        return new HBox(6, textField, browse);
    }

    private Node text(TextInputControl input, ConfigField field) {
        input.setText(access.text(field));
        if (field.defaultValue() != null) {
            input.setPromptText(ResourcesEngine.getString("editor.default", field.defaultValue()));
        }
        Runnable commit = null;
        if (access.commitOnEdit(field)) {
            input.textProperty().addListener((obs, old, text) -> apply(input, field, text));
        } else {
            commit = () -> apply(input, field, input.getText());
            input.getProperties().put(COMMIT, commit);
            if (input instanceof TextField textField) {
                Runnable onEnter = commit;
                textField.setOnAction(e -> onEnter.run());
            }
        }
        Runnable pending = commit;
        input.focusedProperty().addListener((obs, was, focused) -> {
            if (focused) {
                return;
            }
            if (pending != null) {
                pending.run();
            }
            if (invalid.labels.containsKey(input) && focusMovedAway(input)) {
                input.setText(access.text(field)); // the value the document holds
                markValid(input);
            }
        });
        return input;
    }

    /**
     * The focus went to another control of the window, not just to another window (the "invalid fields"
     * warning, another application): only then is an invalid text reset, so that it can still be corrected.
     */
    private static boolean focusMovedAway(Control control) {
        return control.getScene() == null || control.getScene().getFocusOwner() != control;
    }

    private void apply(Control control, ConfigField field, String text) {
        try {
            access.setText(field, text);
            markValid(control);
        } catch (IllegalArgumentException e) {
            control.setStyle(INVALID_STYLE);
            control.setTooltip(new Tooltip(ResourcesEngine.getString("editor.invalid-value", text)));
            invalid.labels.put(control, access.label(field));
        }
    }

    private void markValid(Control control) {
        control.setStyle("");
        control.setTooltip(null);
        invalid.labels.remove(control);
    }
}

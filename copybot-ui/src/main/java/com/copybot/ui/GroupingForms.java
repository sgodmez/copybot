package com.copybot.ui;

import com.copybot.engine.listing.GroupingPresets;
import com.copybot.engine.pipeline.ArchivesConfig;
import com.copybot.engine.plugin.CatalogPreset;
import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.model.PipelineDocument;
import com.copybot.ui.model.PipelineDocument.StreamingNote;
import com.copybot.ui.model.StepCatalog;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The editor forms of the "Regroupements" and "Archives" nodes (spec grouping-archives, IHM): the list of the
 * grouping rules with its warnings, and the options of the archive unfolding. They edit the {@link PipelineDocument}.
 */
final class GroupingForms {

    private static final List<String> TYPES = List.of("sameBase", "doubleExtension", "folder", "regex");
    private static final List<String> ROLES = List.of("copy", "read", "ignore");
    private static final List<String> LINKS = List.of("name", "base");
    private static final String WARNING_STYLE = "-fx-text-fill: #c87f0a;";
    private static final String INFO_STYLE = "-fx-text-fill: #555555;";

    private GroupingForms() {
    }

    // ---- Regroupements ----

    /**
     * @param catalog the presets of the loaded plugins (offered, labelled and expanded for the warnings)
     * @param install what can be done about a plugin that is not loaded ("Install"), empty when nothing
     * @param refresh rebuilds the form (the warnings follow the document)
     */
    static Node groupingForm(PipelineDocument document, StepCatalog catalog, Function<String, Optional<Node>> install,
                             Window owner, Runnable refresh) {
        ListView<JsonObject> list = new ListView<>();
        list.getItems().setAll(document.groupingRules());
        list.setPlaceholder(new Label(ResourcesEngine.getString("editor.grouping.empty")));
        list.setPrefHeight(220);
        list.setCellFactory(l -> new ListCell<>() {
            @Override
            protected void updateItem(JsonObject rule, boolean empty) {
                super.updateItem(rule, empty);
                setText(empty || rule == null ? null : ruleLabel(catalog, rule));
            }
        });

        MenuButton addPreset = new MenuButton(ResourcesEngine.getString("editor.grouping.add-preset"));
        for (String preset : GroupingPresets.names()) {
            JsonObject rule = new JsonObject();
            rule.addProperty("preset", preset);
            addPreset.getItems().add(presetItem(ResourcesEngine.getString("editor.grouping.preset." + preset), rule,
                    document, refresh));
        }
        for (CatalogPreset preset : catalog.groupingPresets()) {
            addPreset.getItems().add(presetItem(menuLabel(preset), citation(preset), document, refresh));
        }
        Button addRule = new Button(ResourcesEngine.getString("editor.grouping.add-rule"));
        addRule.setOnAction(e -> ruleDialog(owner).ifPresent(rule -> {
            document.addGroupingRule(rule);
            refresh.run();
        }));
        Button remove = new Button(ResourcesEngine.getString("editor.grouping.remove"));
        Button up = new Button(ResourcesEngine.getString("editor.grouping.up"));
        Button down = new Button(ResourcesEngine.getString("editor.grouping.down"));
        Runnable buttons = () -> {
            int index = list.getSelectionModel().getSelectedIndex();
            remove.setDisable(index < 0);
            up.setDisable(index <= 0);
            down.setDisable(index < 0 || index >= list.getItems().size() - 1);
        };
        list.getSelectionModel().selectedIndexProperty().addListener((obs, old, index) -> buttons.run());
        buttons.run();
        remove.setOnAction(e -> {
            document.removeGroupingRule(list.getSelectionModel().getSelectedIndex());
            refresh.run();
        });
        up.setOnAction(e -> move(document, list, -1, refresh));
        down.setOnAction(e -> move(document, list, 1, refresh));

        Label help = wrapped(ResourcesEngine.getString("editor.grouping.help"));
        VBox form = new VBox(6, title(ResourcesEngine.getString("editor.grouping")), help, list,
                new HBox(6, addPreset, addRule, remove, up, down));
        missingPlugins(document, catalog, install).forEach(form.getChildren()::add);
        warnings(document, catalog, refresh).forEach(form.getChildren()::add);
        return form;
    }

    private static MenuItem presetItem(String label, JsonObject rule, PipelineDocument document, Runnable refresh) {
        MenuItem item = new MenuItem(label);
        item.setOnAction(e -> {
            document.addGroupingRule(rule.deepCopy());
            refresh.run();
        });
        return item;
    }

    /** The entry of a plugin preset in "Add a preset": its name and its plugin. */
    static String menuLabel(CatalogPreset preset) {
        return ResourcesEngine.getString("editor.grouping.preset.of-plugin", preset.name(), preset.pluginName());
    }

    /** How the pipeline cites a plugin preset: its code and the module name of its plugin. */
    static JsonObject citation(CatalogPreset preset) {
        JsonObject rule = new JsonObject();
        rule.addProperty("preset", preset.code());
        rule.addProperty("plugin", preset.pluginName());
        return rule;
    }

    /** One line per plugin of a cited preset that is not loaded, with what can be done ("Install"). */
    private static List<Node> missingPlugins(PipelineDocument document, StepCatalog catalog,
                                             Function<String, Optional<Node>> install) {
        Set<String> plugins = new LinkedHashSet<>();
        for (JsonObject rule : document.groupingRules()) {
            StepCatalog.presetPlugin(rule).filter(plugin -> !catalog.isLoaded(plugin)).ifPresent(plugins::add);
        }
        List<Node> nodes = new ArrayList<>();
        for (String plugin : plugins) {
            VBox box = new VBox(4, warning(ResourcesEngine.getString("editor.grouping.plugin-missing", plugin)));
            install.apply(plugin).ifPresent(box.getChildren()::add);
            nodes.add(box);
        }
        return nodes;
    }

    private static void move(PipelineDocument document, ListView<JsonObject> list, int delta, Runnable refresh) {
        int index = list.getSelectionModel().getSelectedIndex();
        document.moveGroupingRule(index, index + delta);
        refresh.run();
    }

    /** The warnings of the grouping rules (spec grouping-archives, Filtres de l'entrée and Mode continu). */
    private static List<Node> warnings(PipelineDocument document, StepCatalog catalog, Runnable refresh) {
        List<Node> nodes = new ArrayList<>();
        List<String> excluded = document.companionExtensionsExcludedByFileRead(catalog);
        if (!excluded.isEmpty()) {
            Button allow = new Button(ResourcesEngine.getString("editor.grouping.warning.allow"));
            allow.setOnAction(e -> {
                document.allowCompanionExtensions(catalog);
                refresh.run();
            });
            nodes.add(new VBox(4, warning(ResourcesEngine.getString("editor.grouping.warning.excluded",
                    String.join(", ", excluded))), allow));
        }
        if (document.folderRuleWithNonRecursiveFileRead(catalog)) {
            nodes.add(warning(ResourcesEngine.getString("editor.grouping.warning.folder")));
        }
        document.streamingGroupingWarning().ifPresent(note -> {
            Label label = wrapped("");
            showStreamingNote(label, note);
            nodes.add(label);
        });
        return nodes;
    }

    /**
     * The streaming note ({@link PipelineDocument#streamingGroupingWarning()}), also shown by the pipeline form
     * where the execution is chosen: hidden when there is none.
     */
    static void showStreamingWarning(Label label, PipelineDocument document) {
        Optional<StreamingNote> note = document.streamingGroupingWarning();
        note.ifPresentOrElse(n -> showStreamingNote(label, n), () -> label.setText(""));
        label.setWrapText(true);
        label.setVisible(note.isPresent());
        label.setManaged(note.isPresent());
    }

    /** The fallback as a warning, folder by folder as an information. */
    private static void showStreamingNote(Label label, StreamingNote note) {
        label.setText((note.fallback() ? "⚠ " : "ℹ ") + note.text());
        label.setStyle(note.fallback() ? WARNING_STYLE : INFO_STYLE);
    }

    private static Label warning(String text) {
        Label label = wrapped("⚠ " + text);
        label.setStyle(WARNING_STYLE);
        return label;
    }

    private static Label title(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        return label;
    }

    private static Label wrapped(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        return label;
    }

    /**
     * A preset by its localized name (one of a plugin that is not loaded by its code and "plugin missing", an unknown
     * one by its code and "?"); an own rule by its type, its main extensions and what it attaches.
     */
    static String ruleLabel(StepCatalog catalog, JsonObject rule) {
        String preset = text(rule, "preset");
        if (!preset.isEmpty()) {
            Optional<String> plugin = StepCatalog.presetPlugin(rule);
            if (plugin.isEmpty()) {
                return GroupingPresets.isKnown(preset) ? ResourcesEngine.getString("editor.grouping.preset." + preset)
                        : preset + " (?)";
            }
            if (!catalog.isLoaded(plugin.get())) {
                return ResourcesEngine.getString("editor.grouping.preset.plugin-missing", preset);
            }
            return catalog.groupingPreset(plugin.get(), preset).map(CatalogPreset::name).orElse(preset + " (?)");
        }
        StringBuilder label = new StringBuilder(text(rule, "type"));
        String main = String.join(", ", list(rule.get("main")));
        if (!main.isEmpty()) {
            label.append(": ").append(main);
        }
        List<String> attached = new ArrayList<>();
        JsonElement companions = rule.get("companions");
        if (companions != null && companions.isJsonObject()) {
            companions.getAsJsonObject().entrySet().forEach(entry ->
                    attached.add(entry.getKey() + " (" + entry.getValue().getAsString() + ")"));
        }
        String role = text(rule, "role");
        String target = text(rule, "folder").isEmpty() ? text(rule, "companion") : text(rule, "folder");
        if (!target.isEmpty()) {
            attached.add(role.isEmpty() ? target : target + " (" + role + ")");
        }
        if (!attached.isEmpty()) {
            label.append(" + ").append(String.join(", ", attached));
        }
        return label.toString();
    }

    private static String text(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static List<String> list(JsonElement element) {
        List<String> items = new ArrayList<>();
        if (element != null && element.isJsonPrimitive()) {
            items.add(element.getAsString());
        } else if (element != null && element.isJsonArray()) {
            element.getAsJsonArray().forEach(item -> items.add(item.getAsString()));
        }
        return items;
    }

    /** The type first, then the fields that type needs; OK stays disabled until they are filled. */
    private static Optional<JsonObject> ruleDialog(Window owner) {
        ComboBox<String> type = new ComboBox<>();
        type.getItems().addAll(TYPES);
        type.setConverter(labels("editor.grouping.type."));
        type.setValue(TYPES.getFirst());
        TextField main = new TextField();
        TextArea companions = new TextArea();
        companions.setPrefRowCount(4);
        TextField folder = new TextField("{base}_files");
        TextField companion = new TextField();
        ComboBox<String> role = new ComboBox<>();
        role.getItems().addAll(ROLES);
        role.setConverter(labels("editor.grouping.role."));
        ComboBox<String> link = new ComboBox<>();
        link.getItems().addAll(LINKS);
        link.setConverter(labels("editor.grouping.link."));
        link.setValue(LINKS.getFirst());

        Label mainLabel = new Label();
        VBox mainBox = new VBox(3, mainLabel, main);
        VBox companionsBox = new VBox(3, new Label(ResourcesEngine.getString("editor.grouping.field.companions")),
                companions);
        VBox folderBox = new VBox(3, new Label(ResourcesEngine.getString("editor.grouping.field.folder")), folder);
        VBox companionBox = new VBox(3, new Label(ResourcesEngine.getString("editor.grouping.field.companion")),
                companion);
        VBox roleBox = new VBox(3, new Label(ResourcesEngine.getString("editor.grouping.field.role")), role);
        VBox linkBox = new VBox(3, new Label(ResourcesEngine.getString("editor.grouping.field.link")), link);
        Runnable sync = () -> {
            String t = type.getValue();
            boolean regex = "regex".equals(t);
            show(mainBox, !"doubleExtension".equals(t));
            mainLabel.setText(ResourcesEngine.getString(regex ? "editor.grouping.field.main-regex"
                    : "editor.grouping.field.main"));
            show(companionsBox, "sameBase".equals(t) || "doubleExtension".equals(t));
            show(folderBox, "folder".equals(t));
            show(companionBox, regex);
            show(roleBox, "folder".equals(t) || regex);
            show(linkBox, regex);
            role.setValue(regex ? "read" : "copy");
        };
        type.valueProperty().addListener((obs, old, t) -> sync.run());
        sync.run();

        Dialog<JsonObject> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(ResourcesEngine.getString("editor.grouping.add-rule"));
        VBox content = new VBox(8, new VBox(3, new Label(ResourcesEngine.getString("editor.grouping.field.type")),
                type), mainBox, companionsBox, folderBox, companionBox, roleBox, linkBox);
        content.setPrefWidth(440);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResultConverter(button -> button == ButtonType.OK
                ? rule(type.getValue(), main.getText(), companions.getText(), folder.getText(), companion.getText(),
                role.getValue(), link.getValue()) : null);
        // a rule that lacks what its type needs is not accepted: the dialog stays open
        dialog.getDialogPane().lookupButton(ButtonType.OK).addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            if (rule(type.getValue(), main.getText(), companions.getText(), folder.getText(), companion.getText(),
                    role.getValue(), link.getValue()) == null) {
                e.consume();
            }
        });
        return dialog.showAndWait();
    }

    private static void show(Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }

    private static StringConverter<String> labels(String prefix) {
        return new StringConverter<>() {
            @Override
            public String toString(String value) {
                return value == null ? "" : ResourcesEngine.getString(prefix + value);
            }

            @Override
            public String fromString(String s) {
                return s; // not editable
            }
        };
    }

    /**
     * The rule of the dialog's fields, null when one the type needs is blank. Extensions are separated by commas or
     * spaces, the companions are one "extension=role" per line (role copy when absent).
     */
    static JsonObject rule(String type, String main, String companions, String folder, String companion, String role,
                           String link) {
        JsonObject rule = new JsonObject();
        rule.addProperty("type", type);
        List<String> extensions = List.of(main.strip().split("[,\\s]+")).stream().filter(s -> !s.isEmpty()).toList();
        boolean regex = "regex".equals(type);
        if (!"doubleExtension".equals(type)) {
            if (main.isBlank()) {
                return null;
            }
            if (regex) {
                rule.addProperty("main", main.strip());
            } else {
                JsonArray array = new JsonArray();
                extensions.forEach(array::add);
                rule.add("main", array);
            }
        }
        if ("sameBase".equals(type) || "doubleExtension".equals(type)) {
            Map<String, String> parsed = parseCompanions(companions);
            if (parsed.isEmpty()) {
                return null;
            }
            JsonObject object = new JsonObject();
            parsed.forEach(object::addProperty);
            rule.add("companions", object);
        } else if ("folder".equals(type)) {
            if (folder.isBlank()) {
                return null;
            }
            rule.addProperty("folder", folder.strip());
            rule.addProperty("role", role);
        } else if (regex) {
            if (companion.isBlank()) {
                return null;
            }
            rule.addProperty("companion", companion.strip());
            rule.addProperty("role", role);
            rule.addProperty("link", link);
        }
        return rule;
    }

    /** One "extension=role" (or "extension") per line, in order; a role other than copy / read / ignore is copy. */
    static Map<String, String> parseCompanions(String text) {
        Map<String, String> companions = new LinkedHashMap<>();
        text.lines().map(String::strip).filter(line -> !line.isEmpty()).forEach(line -> {
            int equals = line.indexOf('=');
            String extension = (equals < 0 ? line : line.substring(0, equals)).strip();
            String role = equals < 0 ? "" : line.substring(equals + 1).strip();
            if (!extension.isEmpty()) {
                companions.put(extension.startsWith(".") ? extension.substring(1) : extension,
                        ROLES.contains(role) ? role : "copy");
            }
        });
        return companions;
    }

    // ---- Archives ----

    static Node archivesForm(PipelineDocument document) {
        ArchivesConfig current = document.archives();
        CheckBox unfold = new CheckBox(ResourcesEngine.getString("editor.archives.unfold"));
        unfold.setSelected(current.isUnfold());
        CheckBox nested = new CheckBox(ResourcesEngine.getString("editor.archives.nested"));
        nested.setSelected(current.isNested());
        CheckBox merge = new CheckBox(ResourcesEngine.getString("editor.archives.merge-folder"));
        merge.setSelected(current.isMergeFolder());
        TextArea include = patterns(current.include());
        TextArea exclude = patterns(current.exclude());
        Runnable apply = () -> {
            nested.setDisable(!unfold.isSelected());
            merge.setDisable(!unfold.isSelected());
            include.setDisable(!unfold.isSelected());
            exclude.setDisable(!unfold.isSelected());
            document.setArchives(new ArchivesConfig(unfold.isSelected(), nested.isSelected(), merge.isSelected(),
                    include.getText().lines().toList(), exclude.getText().lines().toList()));
        };
        for (CheckBox box : Set.of(unfold, nested, merge)) {
            box.selectedProperty().addListener((obs, old, selected) -> apply.run());
        }
        include.textProperty().addListener((obs, old, text) -> apply.run());
        exclude.textProperty().addListener((obs, old, text) -> apply.run());
        nested.setDisable(!unfold.isSelected());
        merge.setDisable(!unfold.isSelected());
        include.setDisable(!unfold.isSelected());
        exclude.setDisable(!unfold.isSelected());
        return new VBox(6, title(ResourcesEngine.getString("editor.archives")),
                wrapped(ResourcesEngine.getString("editor.archives.help")), unfold, nested, merge,
                new Label(ResourcesEngine.getString("editor.archives.include")), include,
                new Label(ResourcesEngine.getString("editor.archives.exclude")), exclude);
    }

    private static TextArea patterns(List<String> patterns) {
        TextArea area = new TextArea(patterns == null ? "" : String.join("\n", patterns));
        area.setPrefRowCount(3);
        return area;
    }
}

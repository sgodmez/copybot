package com.copybot.ui;

import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.model.SecretsModel;
import com.copybot.ui.util.PopinUtil;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Edit > Secrets… (spec run-end, Secrets): the names of the secrets of this user, to add one, change its value or
 * delete it. A value is typed masked, written to the store at once and never shown nor read back; nothing here logs it.
 */
final class SecretsDialog {

    private static final System.Logger LOG = System.getLogger(SecretsDialog.class.getName());

    private SecretsDialog() {
    }

    private static SecretsModel model() {
        return new SecretsModel(SecretsModel.Store.ofCurrentUser());
    }

    /** The names of the secrets, for a secret field; empty when the store cannot be read (logged, without value). */
    static List<String> names() {
        try {
            return model().names();
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "the secrets cannot be listed: {0}", e.getMessage());
            return List.of();
        }
    }

    /** The dialog that manages the secrets, modal over the owner. */
    static void show(Window owner) {
        SecretsModel model = model();
        ListView<String> list = new ListView<>();
        list.setPlaceholder(new Label(ResourcesEngine.getString("secrets.empty")));
        list.setPrefSize(360, 220);
        Runnable reload = () -> {
            try {
                list.getItems().setAll(model.names());
            } catch (IOException e) {
                list.getItems().clear();
                PopinUtil.showError(owner, new IOException(
                        ResourcesEngine.getString("secrets.unreadable", e.getMessage()), e));
            }
        };
        reload.run();

        Button add = new Button(ResourcesEngine.getString("secrets.add"));
        Button change = new Button(ResourcesEngine.getString("secrets.change"));
        Button delete = new Button(ResourcesEngine.getString("secrets.delete"));
        change.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        delete.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        add.setOnAction(e -> edit(list.getScene().getWindow(), model, null).ifPresent(name -> {
            reload.run();
            list.getSelectionModel().select(name);
        }));
        change.setOnAction(e -> {
            String name = list.getSelectionModel().getSelectedItem();
            edit(list.getScene().getWindow(), model, name).ifPresent(saved -> list.getSelectionModel().select(saved));
        });
        delete.setOnAction(e -> {
            String name = list.getSelectionModel().getSelectedItem();
            if (confirm(list.getScene().getWindow(), ResourcesEngine.getString("secrets.delete.confirm", name))) {
                try {
                    model.delete(name);
                } catch (IOException failure) {
                    PopinUtil.showError(list.getScene().getWindow(), failure);
                }
                reload.run();
            }
        });

        Label help = new Label(ResourcesEngine.getString("secrets.help"));
        help.setWrapText(true);
        help.setMaxWidth(360);
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(ResourcesEngine.getString("secrets.title"));
        dialog.getDialogPane().setContent(new VBox(8, help, list, new HBox(6, add, change, delete)));
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.showAndWait();
    }

    /** "New secret…" of a secret field: its name once saved, empty when cancelled. */
    static Optional<String> newSecret(Window owner) {
        return edit(owner, model(), null);
    }

    /**
     * "Put in the vault" of a secret field whose value the pipeline holds in clear: asks the name (proposed, editable)
     * and saves the value under it; the value itself is not shown.
     *
     * @return the name saved, empty when cancelled or failed (the failure shown)
     */
    static Optional<String> putInStore(Window owner, String proposedName, String clearValue) {
        SecretsModel model = model();
        TextField name = new TextField(proposedName);
        Label refusal = new Label();
        refusal.setWrapText(true);
        refusal.setMaxWidth(320);
        refusal.setStyle("-fx-text-fill: #d9534f;");
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(ResourcesEngine.getString("editor.secret.to-store"));
        dialog.getDialogPane().setContent(new VBox(4, new Label(ResourcesEngine.getString("secrets.name")), name,
                refusal));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Runnable check = () -> {
            Optional<String> why = model.refusal(name.getText(), clearValue);
            refusal.setText(why.orElse(""));
            dialog.getDialogPane().lookupButton(ButtonType.OK).setDisable(why.isPresent());
        };
        name.textProperty().addListener((obs, old, text) -> check.run());
        check.run();
        dialog.setOnShown(e -> name.requestFocus());
        try {
            if (dialog.showAndWait().filter(button -> button == ButtonType.OK).isEmpty()) {
                return Optional.empty();
            }
            String saved = name.getText().strip();
            if (model.exists(saved) && !confirm(owner, ResourcesEngine.getString("secrets.replace", saved))) {
                return Optional.empty();
            }
            model.putInStore(saved, clearValue);
            return Optional.of(saved);
        } catch (IOException e) {
            PopinUtil.showError(owner, e);
            return Optional.empty();
        }
    }

    /**
     * Asks a name (unless the value of this one is changed) and a value, masked, then saves them; an existing name
     * typed for a new secret asks before its value is replaced.
     *
     * @return the name saved, empty when cancelled or failed (the failure shown)
     */
    private static Optional<String> edit(Window owner, SecretsModel model, String existing) {
        TextField name = new TextField(existing == null ? "" : existing);
        name.setDisable(existing != null);
        PasswordField value = new PasswordField();
        Label refusal = new Label();
        refusal.setWrapText(true);
        refusal.setMaxWidth(320);
        refusal.setStyle("-fx-text-fill: #d9534f;");
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(ResourcesEngine.getString(existing == null ? "secrets.add" : "secrets.change"));
        dialog.getDialogPane().setContent(new VBox(4, new Label(ResourcesEngine.getString("secrets.name")), name,
                new Label(ResourcesEngine.getString("secrets.value")), value, refusal));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Runnable check = () -> {
            // no message for what is still empty, only for a name typed wrong; OK waits for both
            refusal.setText(name.getText().isBlank() ? "" : model.nameRefusal(name.getText()).orElse(""));
            dialog.getDialogPane().lookupButton(ButtonType.OK)
                    .setDisable(model.refusal(name.getText(), value.getText()).isPresent());
        };
        name.textProperty().addListener((obs, old, text) -> check.run());
        value.textProperty().addListener((obs, old, text) -> check.run());
        check.run();
        dialog.setOnShown(e -> (existing == null ? name : value).requestFocus());
        try {
            if (dialog.showAndWait().filter(button -> button == ButtonType.OK).isEmpty()) {
                return Optional.empty();
            }
            String saved = name.getText().strip();
            if (existing == null && model.exists(saved)
                    && !confirm(owner, ResourcesEngine.getString("secrets.replace", saved))) {
                return Optional.empty();
            }
            model.save(saved, value.getText());
            return Optional.of(saved);
        } catch (IOException e) {
            PopinUtil.showError(owner, e);
            return Optional.empty();
        } finally {
            value.clear(); // the typed value is not kept in the control
        }
    }

    private static boolean confirm(Window owner, String question) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, question, ButtonType.OK, ButtonType.CANCEL);
        alert.initOwner(owner);
        return alert.showAndWait().filter(button -> button == ButtonType.OK).isPresent();
    }
}

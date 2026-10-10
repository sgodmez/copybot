package com.copybot.ui;

import com.copybot.config.ConfigFiles;
import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.util.PopinUtil;
import com.copybot.ui.util.UiPreferences;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.util.StringConverter;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;

public class PreferencesController {

    @FXML
    private ComboBox<Locale> languageCombo;
    @FXML
    private ComboBox<UiPreferences.ResourcesLayout> resourcesLayoutCombo;
    @FXML
    private TextField pluginPathField;
    @FXML
    private Label pluginPathResolved;
    @FXML
    private Button browsePluginPathButton;
    @FXML
    private Button defaultPluginPathButton;
    @FXML
    private Button okButton;

    /** The pluginPath of the file when the dialog opened, and the one chosen (null: default). */
    private Path savedPluginPath;
    private Path chosenPluginPath;

    @FXML
    public void initialize() {
        List<Locale> locales = ResourcesEngine.getSupportedLocales().stream()
                .sorted(Comparator.comparing(l -> l.getDisplayLanguage(l)))
                .toList();
        languageCombo.getItems().setAll(locales);
        languageCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(Locale locale) {
                if (locale == null) {
                    return "";
                }
                String name = locale.getDisplayLanguage(locale); // each language in its own tongue
                return name.substring(0, 1).toUpperCase(locale) + name.substring(1);
            }

            @Override
            public Locale fromString(String s) {
                return null; // combo is not editable
            }
        });
        languageCombo.getSelectionModel().select(locales.stream()
                .filter(l -> l.getLanguage().equals(Locale.getDefault().getLanguage()))
                .findFirst()
                .orElse(Locale.ENGLISH));
        resourcesLayoutCombo.getItems().setAll(UiPreferences.ResourcesLayout.values());
        resourcesLayoutCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(UiPreferences.ResourcesLayout layout) {
                return layout == null ? "" : ResourcesEngine.getString("pref.resources-layout." + layout.name());
            }

            @Override
            public UiPreferences.ResourcesLayout fromString(String s) {
                return null; // combo is not editable
            }
        });
        resourcesLayoutCombo.getSelectionModel().select(UiPreferences.resourcesLayout());
        setPluginPathLoaded(false);
        Path configFile = CopybotMainUi.ENGINE.configFile();
        try {
            // a file read (it may sit on a disconnected share): off the JavaFX thread
            CopybotMainUi.executor.submit(() -> {
                Path saved;
                try {
                    saved = ConfigFiles.readPluginPath(configFile).orElse(null);
                } catch (Throwable t) {
                    saved = null; // unreadable now: shown as default, and OK rewrites it only if the user picks one
                }
                Path read = saved;
                Platform.runLater(() -> {
                    savedPluginPath = read;
                    chosenPluginPath = read;
                    showPluginPath();
                    setPluginPathLoaded(true);
                });
            });
        } catch (RejectedExecutionException e) {
            // the application is stopping
        }
    }

    /** Until the configuration file is read, the folder cannot be changed nor the dialog validated. */
    private void setPluginPathLoaded(boolean loaded) {
        browsePluginPathButton.setDisable(!loaded);
        defaultPluginPathButton.setDisable(!loaded);
        okButton.setDisable(!loaded);
    }

    private void showPluginPath() {
        if (chosenPluginPath == null) {
            pluginPathField.setText(ResourcesEngine.getString("pref.plugin-path.default-value"));
        } else {
            pluginPathField.setText(chosenPluginPath.toString());
        }
        Path effective = (chosenPluginPath != null ? chosenPluginPath : Path.of("plugins")).toAbsolutePath().normalize();
        boolean showResolved = chosenPluginPath == null || !chosenPluginPath.isAbsolute();
        pluginPathResolved.setText(showResolved ? ResourcesEngine.getString("pref.plugin-path.resolved", effective) : "");
    }

    @FXML
    protected void onBrowsePluginPathClick() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(ResourcesEngine.getString("pref.plugin-path"));
        Path start = (chosenPluginPath != null ? chosenPluginPath : Path.of("plugins")).toAbsolutePath().normalize();
        if (Files.isDirectory(start)) {
            chooser.setInitialDirectory(start.toFile());
        }
        File dir = chooser.showDialog(pluginPathField.getScene().getWindow());
        if (dir != null) {
            chosenPluginPath = dir.toPath();
            showPluginPath();
        }
    }

    @FXML
    protected void onDefaultPluginPathClick() {
        chosenPluginPath = null;
        showPluginPath();
    }

    @FXML
    protected void onOkClick() {
        if (!Objects.equals(chosenPluginPath, savedPluginPath)) {
            Path configFile = CopybotMainUi.ENGINE.configFile();
            try {
                // synchronous: a user-initiated rewrite of the local configuration file
                if (ConfigFiles.rewriteLosesContent(configFile) && !confirmLossyRewrite(configFile)) {
                    return; // nothing written, the dialog stays open
                }
                ConfigFiles.writePluginPath(configFile, chosenPluginPath);
            } catch (RuntimeException e) {
                PopinUtil.showError(e);
                return; // the dialog stays open
            }
            Alert info = new Alert(Alert.AlertType.INFORMATION, ResourcesEngine.getString("pref.plugin-path.saved", configFile));
            info.setHeaderText(null);
            info.initOwner(pluginPathField.getScene().getWindow());
            info.showAndWait();
        }
        UiPreferences.ResourcesLayout layout = resourcesLayoutCombo.getValue();
        if (layout != null && layout != UiPreferences.resourcesLayout()) {
            UiPreferences.saveResourcesLayout(layout); // the plan view reads it at each refresh
        }
        Locale chosen = languageCombo.getValue();
        if (chosen != null && !chosen.getLanguage().equals(Locale.getDefault().getLanguage())) {
            ResourcesEngine.loadLanguage(chosen);
            UiPreferences.saveLanguage(chosen);
            CopybotMainUi.reloadMainView();
        }
        close();
    }

    /** Comments, lenient syntax or repeated keys of the file would be lost: true when the user accepts it. */
    private boolean confirmLossyRewrite(Path configFile) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, ResourcesEngine.getString("pref.save.lossy", configFile),
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(null);
        confirm.initOwner(pluginPathField.getScene().getWindow());
        return confirm.showAndWait().filter(ButtonType.OK::equals).isPresent();
    }

    @FXML
    protected void onCancelClick() {
        close();
    }

    private void close() {
        ((Stage) languageCombo.getScene().getWindow()).close();
    }
}

package com.copybot.ui;

import com.copybot.config.ConfigFiles;
import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.util.Background;
import com.copybot.ui.util.Choosers;
import com.copybot.ui.util.ConfigRewrite;
import com.copybot.ui.util.UiPreferences;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.io.File;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class PreferencesController {

    private static final System.Logger LOG = System.getLogger(PreferencesController.class.getName());

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
        // a file read (it may sit on a disconnected share): off the JavaFX thread
        Background.run(() -> ConfigFiles.readPluginPath(configFile).orElse(null),
                this::pluginPathLoaded,
                failure -> {
                    // unreadable now: shown as default, and OK rewrites it only if the user picks one
                    LOG.log(System.Logger.Level.WARNING, "the plugin directory of " + configFile + " cannot be read", failure);
                    pluginPathLoaded(null);
                });
    }

    private void pluginPathLoaded(Path read) {
        savedPluginPath = read;
        chosenPluginPath = read;
        showPluginPath();
        setPluginPathLoaded(true);
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
        Path start = (chosenPluginPath != null ? chosenPluginPath : Path.of("plugins")).toAbsolutePath().normalize();
        Choosers.inFolderOf(browsePluginPathButton, start.toString(), folder -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle(ResourcesEngine.getString("pref.plugin-path"));
            chooser.setInitialDirectory(folder);
            File dir = chooser.showDialog(pluginPathField.getScene().getWindow());
            if (dir != null) {
                chosenPluginPath = dir.toPath();
                showPluginPath();
            }
        });
    }

    @FXML
    protected void onDefaultPluginPathClick() {
        chosenPluginPath = null;
        showPluginPath();
    }

    @FXML
    protected void onOkClick() {
        if (Objects.equals(chosenPluginPath, savedPluginPath)) {
            applyAndClose();
            return;
        }
        Path configFile = CopybotMainUi.ENGINE.configFile();
        Path chosen = chosenPluginPath;
        Window window = pluginPathField.getScene().getWindow();
        setPluginPathLoaded(false); // nothing changes while the file is written
        ConfigRewrite.rewrite(window, configFile, file -> ConfigFiles.writePluginPath(file, chosen),
                () -> {
                    Alert info = new Alert(Alert.AlertType.INFORMATION,
                            ResourcesEngine.getString("pref.plugin-path.saved", configFile));
                    info.setHeaderText(null);
                    info.initOwner(window);
                    info.showAndWait();
                    applyAndClose();
                },
                () -> setPluginPathLoaded(true)); // nothing written: the dialog stays open
    }

    /** The other preferences, then the dialog closes. */
    private void applyAndClose() {
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

    @FXML
    protected void onCancelClick() {
        close();
    }

    private void close() {
        ((Stage) languageCombo.getScene().getWindow()).close();
    }
}

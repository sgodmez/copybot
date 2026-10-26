package com.copybot.ui;

import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.update.UpdateService;
import com.copybot.ui.util.UiPreferences;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import javafx.util.StringConverter;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class PreferencesController {

    @FXML
    private ComboBox<Locale> languageCombo;
    @FXML
    private ComboBox<UiPreferences.ResourcesLayout> resourcesLayoutCombo;
    @FXML
    private CheckBox updateAutoCheck;
    @FXML
    private Label updateUnavailable;

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
        initUpdates();
    }


    /** The automatic check is possible only for a released, packaged Copybot: otherwise disabled, with why. */
    private void initUpdates() {
        updateAutoCheck.setSelected(UiPreferences.updateCheck());
        Optional<String> unavailable = UpdateUi.service().map(UpdateService::unavailableReason)
                .orElse(Optional.of("update.unavailable-dev"));
        updateAutoCheck.setDisable(unavailable.isPresent());
        unavailable.ifPresent(key -> {
            updateUnavailable.setText(ResourcesEngine.getString(key));
            updateUnavailable.setVisible(true);
            updateUnavailable.setManaged(true);
        });
    }

    /** Saves the preferences as OK does, closes them, then opens the Updates window. */
    @FXML
    protected void onUpdatesClick() {
        save();
        close();
        CopybotMainUi.mainController().ifPresent(MainController::openUpdates);
    }

    @FXML
    protected void onOkClick() {
        save();
        close();
    }

    private void save() {
        if (!updateAutoCheck.isDisabled() && updateAutoCheck.isSelected() != UiPreferences.updateCheck()) {
            UiPreferences.saveUpdateCheck(updateAutoCheck.isSelected());
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
    }

    @FXML
    protected void onCancelClick() {
        close();
    }

    private void close() {
        ((Stage) languageCombo.getScene().getWindow()).close();
    }
}

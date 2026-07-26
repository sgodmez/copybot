package com.copybot.ui;

import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.util.UiPreferences;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.stage.Stage;
import javafx.util.StringConverter;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class PreferencesController {

    @FXML
    private ComboBox<Locale> languageCombo;

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
    }

    @FXML
    protected void onOkClick() {
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

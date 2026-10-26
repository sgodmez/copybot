package com.copybot.ui;

import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.plugins.PluginService;
import com.copybot.ui.update.UpdateService;
import com.copybot.ui.util.AppInfo;
import com.copybot.ui.util.Background;
import com.copybot.ui.util.UiPreferences;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
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
    private Label updateVersionLabel;
    @FXML
    private CheckBox updateAutoCheck;
    @FXML
    private Button updateCheckButton;
    @FXML
    private Label updateResultLabel;
    @FXML
    private Button updateInstallButton;
    @FXML
    private Label updateReasonLabel;

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

    /** The Updates section (spec app-update §3): the check is possible only for a released, packaged Copybot. */
    private void initUpdates() {
        updateVersionLabel.setText(ResourcesEngine.getString("update.current", AppInfo.version()));
        updateAutoCheck.setSelected(UiPreferences.updateCheck());
        Optional<String> unavailable = UpdateUi.service().map(UpdateService::unavailableReason)
                .orElse(Optional.of("update.unavailable-dev"));
        updateAutoCheck.setDisable(unavailable.isPresent());
        updateCheckButton.setDisable(unavailable.isPresent());
        updateResultLabel.setText(unavailable.map(key -> ResourcesEngine.getString(key))
                .or(UpdateUi::lastResult).orElse(""));
        refreshInstallButton();
    }

    /**
     * The button of the banner, when an update is offered (spec update-with-plugins §3): same text, disabled for the
     * same reason, told below it.
     */
    private void refreshInstallButton() {
        var release = UpdateUi.offered();
        boolean shown = release.isPresent() || UpdateUi.offeredPluginUpdates() > 0;
        updateInstallButton.setText(ResourcesEngine.getString(release.isPresent() ? "update.install"
                : "plugins.show-updates"));
        Optional<String> blocked = release.isEmpty() ? Optional.empty()
                : UpdateUi.service().flatMap(UpdateService::installBlocked);
        updateInstallButton.setDisable(blocked.isPresent());
        updateReasonLabel.setText(blocked.map(key -> ResourcesEngine.getString(key)).orElse(""));
        show(updateInstallButton, shown);
        show(updateReasonLabel, shown && blocked.isPresent());
    }

    private static void show(Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }

    /** Saves the preferences as OK does, closes them, then does what the button of the banner does. */
    @FXML
    protected void onUpdateInstallClick() {
        save();
        close();
        CopybotMainUi.mainController().ifPresent(MainController::installOffered);
    }

    /**
     * Always tells the outcome: up to date, the newer release and the plugin updates (then offered in the banner
     * too), or the error.
     */
    @FXML
    protected void onUpdateCheckClick() {
        PluginService service = UpdateUi.plugins().orElseThrow(); // the button is disabled without it
        updateCheckButton.setDisable(true);
        updateResultLabel.setText(ResourcesEngine.getString("update.checking"));
        Background.run(service::checkNow,
                found -> {
                    UpdateUi.offer(found);
                    showUpdateResult(UpdateUi.checkText(found));
                },
                failure -> showUpdateResult(UpdateUi.message(failure)));
    }

    private void showUpdateResult(String text) {
        UpdateUi.setLastResult(text);
        updateResultLabel.setText(text);
        updateCheckButton.setDisable(false);
        refreshInstallButton();
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

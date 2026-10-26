package com.copybot.ui;

import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.plugins.PluginCatalog;
import com.copybot.ui.plugins.PluginRow;
import com.copybot.ui.plugins.PluginService;
import com.copybot.ui.plugins.PluginState;
import com.copybot.ui.update.Release;
import com.copybot.ui.update.UpdateException;
import com.copybot.ui.update.UpdateService;
import com.copybot.ui.util.Background;
import com.copybot.ui.util.PopinUtil;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;
import java.util.stream.Collectors;

/**
 * The JavaFX side of the self-update (spec app-update §2-§5) and of the official plugins (spec plugin-install §3-§5):
 * the check at startup, the release and the plugin updates offered in the banner of the main window, the update itself
 * and the application of the plugin changes (exit confirmation, download window, launch, exit). Everything here runs
 * on the JavaFX thread; the network and the disk are left to {@link UpdateService} and {@link PluginService} in the
 * background.
 */
final class UpdateUi {

    private static final System.Logger LOG = System.getLogger(UpdateUi.class.getName());

    /** Null until the startup has resolved the folders. */
    private static UpdateService service;
    /** Null until the startup has resolved the folders. */
    private static PluginService plugins;
    /** The newer release found this session, null when none. */
    private static Release offered;
    /** The number of installed plugins with an update found by the last check. */
    private static long pluginUpdates;
    /** The banner was closed: hidden until the next start. */
    private static boolean dismissed;

    private UpdateUi() {
    }

    static void init(UpdateService updateService, PluginService pluginService) {
        service = updateService;
        plugins = pluginService;
    }

    static Optional<UpdateService> service() {
        return Optional.ofNullable(service);
    }

    static Optional<PluginService> plugins() {
        return Optional.ofNullable(plugins);
    }

    /**
     * The startup: the update folders left in the temporary directory more than a day ago are deleted, then the
     * check runs when due, with the catalogue of the plugins. A failure is only logged, nothing is shown.
     */
    static void startupCheck() {
        if (service == null || plugins == null) {
            return;
        }
        Background.run(() -> {
                    service.deleteOldUpdates();
                    return plugins.automaticCheck();
                },
                UpdateUi::offer,
                failure -> LOG.log(System.Logger.Level.WARNING, "automatic update check failed", failure));
    }

    /** Shows the banner of what this check found (a release, plugin updates), even if an older one was closed. */
    static void offer(PluginService.Check check) {
        if (check.release().isEmpty() && check.pluginUpdates() == 0) {
            pluginUpdates = 0; // nothing more to announce; a release found before stays offered
            CopybotMainUi.mainController().ifPresent(MainController::refreshUpdateBanner);
            return;
        }
        check.release().ifPresent(release -> offered = release);
        pluginUpdates = check.pluginUpdates();
        dismissed = false;
        CopybotMainUi.mainController().ifPresent(MainController::refreshUpdateBanner);
    }

    /** The release of the banner, empty when there is none or when it was closed. */
    static Optional<Release> offered() {
        return dismissed ? Optional.empty() : Optional.ofNullable(offered);
    }

    /**
     * The newer release found this session, even when the banner was closed: closing the banner hides the
     * announcement, not the update the Updates window offers.
     */
    static Optional<Release> knownRelease() {
        return Optional.ofNullable(offered);
    }

    /** When this version last checked for updates, empty when never (or outside the packaged application). */
    static Optional<Instant> lastCheck() {
        return service == null ? Optional.empty() : service.lastCheck();
    }

    /** The plugin updates of the banner, 0 when there are none or when it was closed. */
    static long offeredPluginUpdates() {
        return dismissed ? 0 : pluginUpdates;
    }

    static void dismiss() {
        dismissed = true;
        CopybotMainUi.mainController().ifPresent(MainController::refreshUpdateBanner);
    }

    /** The text of the banner: the release, the plugin updates, or both. */
    static String bannerText(Optional<Release> release, long pluginUpdates) {
        if (release.isEmpty()) {
            return ResourcesEngine.getString("update.banner.plugins", pluginUpdates);
        }
        return ResourcesEngine.getString(pluginUpdates > 0 ? "update.banner.app-and-plugins" : "update.banner.app",
                release.get().version(), pluginUpdates);
    }

    /** The text of a failed check or update: the translated key of an {@link UpdateException}, else its message. */
    static String message(Throwable failure) {
        if (failure instanceof UpdateException e) {
            return ResourcesEngine.getString(e.key(), e.args());
        }
        return PopinUtil.messageOf(failure);
    }

    /**
     * "Update and restart" of the Updates window with Copybot ticked: the application and exactly these plugins (a
     * module not installed in the plugins folder, or without a newer version in the catalogue of the release, is
     * ignored).
     *
     * @param owner the window the confirmations and the download window belong to
     */
    static void install(Release release, Set<String> modules, Window owner) {
        Set<String> chosen = Set.copyOf(modules);
        install(release, () -> chosen, owner);
    }

    /**
     * Refused while an operation runs, and with a message while another Copybot runs from the same folder; a warning
     * when Windows will ask for an administrator's approval; then the usual exit questions (unsaved pipelines, a
     * refusal cancels everything), the download with its window (the application, then the chosen installed plugins
     * with a newer version: spec update-with-plugins), the launch of the installer or of the script, and the usual
     * exit, which only then closes the editors. A failure or a cancel deletes the download and Copybot goes on with
     * its editors untouched.
     *
     * @param modules the plugins to update, asked for in the background
     */
    private static void install(Release release, Callable<Set<String>> modules, Window owner) {
        if (service == null) {
            return;
        }
        Optional<String> blocked = service.installBlocked();
        if (blocked.isPresent()) {
            // e.g. a run started while the window offering the update was open
            inform(owner, "update.title", ResourcesEngine.getString(blocked.get()));
            return;
        }
        Optional<String> otherInstance = service.otherInstance();
        if (otherInstance.isPresent()) {
            inform(owner, "update.title", ResourcesEngine.getString(otherInstance.get()));
            return;
        }
        if (service.adminApprovalNeeded()
                && !confirmAdminApproval(owner, "update.title", "update.admin")) {
            return;
        }
        if (!CopybotMainUi.confirmExitForUpdate()) {
            return;
        }
        ProgressWindow progress = new ProgressWindow(owner, "update.title",
                ResourcesEngine.getString("update.downloading", release.version()));

        // the progress of the download thread, handed to the JavaFX thread at most once at a time
        AtomicLong received = new AtomicLong();
        AtomicBoolean pending = new AtomicBoolean();
        Background.run(() -> {
                    long size = release.asset(service.assetName(release)).map(Release.Asset::size).orElse(0L);
                    LongConsumer appProgress = bytes -> {
                        received.set(bytes);
                        if (pending.compareAndSet(false, true)) {
                            Platform.runLater(() -> {
                                pending.set(false);
                                progress.bar.setProgress(size > 0 ? (double) received.get() / size : -1);
                            });
                        }
                    };
                    // the chosen plugins with a newer version go with it: one stop, one relaunch
                    UpdateService.Prepared prepared = plugins == null
                            ? service.prepare(release, appProgress, progress.cancelled::get)
                            : plugins.prepareUpdate(release, modules.call(), appProgress,
                            (index, total, code, bytes) -> {
                                if (bytes == 0) {
                                    Platform.runLater(() -> {
                                        progress.label.setText(ResourcesEngine.getString("plugins.downloading", code,
                                                Integer.toString(index + 1), Integer.toString(total)));
                                        progress.bar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                                    });
                                }
                            }, progress.cancelled::get);
                    service.launch(prepared, progress.cancelled::get); // a cancel during the extraction stops it too
                    return prepared;
                },
                prepared -> {
                    progress.window.hide();
                    LOG.log(System.Logger.Level.INFO, "update to {0} started: {1}", release.version(),
                            prepared.command());
                    // only now are the editors closed: a failed or cancelled update leaves them as they were
                    CopybotMainUi.exitForUpdate(); // the installer or the script waits for this process to end
                },
                failure -> {
                    progress.window.hide();
                    if (failure instanceof UpdateException e && e.key().equals("update.error.cancelled")) {
                        return; // the user's own choice: nothing to tell
                    }
                    LOG.log(System.Logger.Level.WARNING, "update to " + release.version() + " failed", failure);
                    showError(owner, "update.title", failure);
                });
    }

    /**
     * "Apply and restart" of the Plugins window (spec plugin-install §3): like the update, refused while an operation
     * runs or while another Copybot runs from the same folder; the changes and the licences confirmed; a warning
     * when Windows will ask for an administrator; the unsaved editors asked about; every archive downloaded and
     * unpacked with a window that can cancel; the script launched and Copybot quits. A failure or a cancel changes
     * nothing, deletes the download and Copybot goes on.
     *
     * @param owner the Plugins window
     */
    static void applyPlugins(List<PluginRow> toInstallOrUpdate, List<PluginRow> toRemove, Window owner) {
        if (plugins == null || toInstallOrUpdate.isEmpty() && toRemove.isEmpty()) {
            return;
        }
        Optional<String> blocked = plugins.applyBlocked().or(plugins::otherInstance);
        if (blocked.isPresent()) {
            inform(owner, "plugins.confirm.title", ResourcesEngine.getString(blocked.get()));
            return;
        }
        if (!confirmPluginChanges(toInstallOrUpdate, toRemove, owner)) {
            return;
        }
        if (plugins.adminApprovalNeeded() && !confirmAdminApproval(owner, "plugins.confirm.title", "plugins.admin")) {
            return;
        }
        if (!CopybotMainUi.confirmExitForUpdate()) {
            return;
        }
        int count = toInstallOrUpdate.size();
        ProgressWindow progress = new ProgressWindow(owner, "plugins.confirm.title",
                count == 0 ? ResourcesEngine.getString("plugins.confirm.intro") // removals only: nothing to download
                        : ResourcesEngine.getString("plugins.downloading", toInstallOrUpdate.getFirst().entry().code(),
                        "1", Integer.toString(count)));
        // the size of an archive is only known once its release is read: the bar keeps moving, the text counts
        progress.bar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        AtomicBoolean pending = new AtomicBoolean();
        Background.run(() -> {
                    UpdateService.Prepared prepared = plugins.prepare(toInstallOrUpdate, toRemove,
                            (index, total, code, bytes) -> {
                                if (bytes == 0 || pending.compareAndSet(false, true)) {
                                    Platform.runLater(() -> {
                                        pending.set(false);
                                        progress.label.setText(ResourcesEngine.getString("plugins.downloading", code,
                                                Integer.toString(index + 1), Integer.toString(total)));
                                    });
                                }
                            }, progress.cancelled::get);
                    plugins.launch(prepared, progress.cancelled::get);
                    return prepared;
                },
                prepared -> {
                    progress.window.hide();
                    LOG.log(System.Logger.Level.INFO, "plugin changes started: {0}", prepared.command());
                    CopybotMainUi.exitForUpdate(); // the script waits for this process to end
                },
                failure -> {
                    progress.window.hide();
                    if (failure instanceof UpdateException e && e.key().equals("update.error.cancelled")) {
                        return;
                    }
                    LOG.log(System.Logger.Level.WARNING, "plugin changes failed", failure);
                    showError(owner, "plugins.confirm.title", failure);
                });
    }

    /** The changes in words, with the licences of the plugins to install; false when cancelled. */
    private static boolean confirmPluginChanges(List<PluginRow> toInstallOrUpdate, List<PluginRow> toRemove,
            Window owner) {
        List<String> lines = new ArrayList<>();
        List<PluginRow> installs = toInstallOrUpdate.stream()
                .filter(row -> row.state() == PluginState.NOT_INSTALLED).toList();
        List<PluginRow> updates = toInstallOrUpdate.stream().filter(row -> !installs.contains(row)).toList();
        addLine(lines, "plugins.confirm.install", installs);
        addLine(lines, "plugins.confirm.update", updates);
        addLine(lines, "plugins.confirm.remove", toRemove);
        List<String> licences = toInstallOrUpdate.stream().filter(row -> row.entry().license() != null)
                .map(row -> ResourcesEngine.getString("plugins.license", row.entry().license().name(),
                        localized(row.entry().license().notice())) + " (" + row.entry().code() + ")")
                .toList();
        if (!licences.isEmpty()) {
            lines.add("");
            lines.add(ResourcesEngine.getString("plugins.confirm.licenses"));
            lines.addAll(licences);
        }
        lines.add("");
        lines.add(ResourcesEngine.getString("plugins.confirm.intro"));
        ButtonType apply = new ButtonType(ResourcesEngine.getString("plugins.apply"), ButtonBar.ButtonData.OK_DONE);
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, String.join("\n", lines), apply, ButtonType.CANCEL);
        confirm.initOwner(owner);
        confirm.setTitle(ResourcesEngine.getString("plugins.confirm.title"));
        confirm.setHeaderText(null);
        confirm.getDialogPane().setMinWidth(480);
        return confirm.showAndWait().filter(choice -> choice == apply).isPresent();
    }

    private static void addLine(List<String> lines, String key, List<PluginRow> rows) {
        if (!rows.isEmpty()) {
            lines.add(ResourcesEngine.getString(key, rows.stream().map(row -> row.entry().code())
                    .collect(Collectors.joining(", "))));
        }
    }

    /** The text of the language of the UI, else the English one, else any. */
    static String localized(Map<String, String> texts) {
        String language = Locale.getDefault().getLanguage();
        if (texts.containsKey(language)) {
            return texts.get(language);
        }
        return texts.getOrDefault("en", texts.values().stream().findFirst().orElse(""));
    }

    /** The description of a catalogue entry in the language of the UI. */
    static String description(PluginCatalog.Entry entry) {
        return localized(entry.description());
    }

    private static void inform(Window owner, String titleKey, String text) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, text);
        alert.initOwner(owner);
        alert.setTitle(ResourcesEngine.getString(titleKey));
        alert.setHeaderText(null);
        alert.showAndWait();
    }

    private static void showError(Window owner, String titleKey, Throwable failure) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message(failure));
        alert.initOwner(owner);
        alert.setTitle(ResourcesEngine.getString(titleKey));
        alert.setHeaderText(null);
        alert.show();
    }

    /** Tells, before anything is downloaded, that Windows will ask for an administrator; false when cancelled. */
    private static boolean confirmAdminApproval(Window owner, String titleKey, String textKey) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, ResourcesEngine.getString(textKey),
                ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(owner);
        confirm.setTitle(ResourcesEngine.getString(titleKey));
        confirm.setHeaderText(null);
        return confirm.showAndWait().filter(choice -> choice == ButtonType.OK).isPresent();
    }

    /** The modal window of a download: a text, a bar and "Cancel" (closing it cancels too). */
    private static final class ProgressWindow {
        final AtomicBoolean cancelled = new AtomicBoolean();
        final ProgressBar bar = new ProgressBar(0);
        final Label label;
        final Stage window = new Stage(StageStyle.UTILITY);

        ProgressWindow(Window owner, String titleKey, String text) {
            label = new Label(text);
            bar.setPrefWidth(360);
            Button cancel = new Button(ResourcesEngine.getString("update.cancel"));
            cancel.setCancelButton(true);
            cancel.setOnAction(e -> {
                cancelled.set(true);
                cancel.setDisable(true);
            });
            HBox buttons = new HBox(cancel);
            buttons.setAlignment(Pos.CENTER_RIGHT);
            VBox content = new VBox(10, label, bar, buttons);
            content.setPadding(new Insets(16));
            window.initOwner(owner);
            window.initModality(Modality.APPLICATION_MODAL);
            window.setTitle(ResourcesEngine.getString(titleKey));
            window.setResizable(false);
            window.setScene(new Scene(content));
            window.setOnCloseRequest(e -> {
                e.consume(); // closing the window is cancelling
                cancel.fire();
            });
            window.show();
        }
    }
}

package com.copybot.ui;

import com.copybot.resources.ResourcesEngine;
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
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The JavaFX side of the self-update (spec app-update §2-§5): the check at startup, the release offered in the banner
 * of the main window, and the update itself (exit confirmation, download window, launch, exit). Everything here runs on
 * the JavaFX thread; the network and the disk are left to {@link UpdateService} in the background.
 */
final class UpdateUi {

    private static final System.Logger LOG = System.getLogger(UpdateUi.class.getName());

    /** Null until the startup has resolved the folders. */
    private static UpdateService service;
    /** The newer release found this session, null when none. */
    private static Release offered;
    /** The banner was closed: hidden until the next start. */
    private static boolean dismissed;
    /** The text of the last check of the preferences, shown again when they are reopened. */
    private static String lastResult;

    private UpdateUi() {
    }

    static void init(UpdateService updateService) {
        service = updateService;
    }

    static Optional<UpdateService> service() {
        return Optional.ofNullable(service);
    }

    /**
     * The startup: the update folders left in the temporary directory more than a day ago are deleted, then the
     * check runs when due. A failure is only logged, nothing is shown.
     */
    static void startupCheck() {
        if (service == null) {
            return;
        }
        Background.run(() -> {
                    service.deleteOldUpdates();
                    return service.automaticCheck();
                },
                found -> found.ifPresent(UpdateUi::offer),
                failure -> LOG.log(System.Logger.Level.WARNING, "automatic update check failed", failure));
    }

    /** Shows the banner of this release, even if an older one was closed. */
    static void offer(Release release) {
        offered = release;
        dismissed = false;
        CopybotMainUi.mainController().ifPresent(MainController::refreshUpdateBanner);
    }

    /** The release of the banner, empty when there is none or when it was closed. */
    static Optional<Release> offered() {
        return dismissed ? Optional.empty() : Optional.ofNullable(offered);
    }

    static void dismiss() {
        dismissed = true;
        CopybotMainUi.mainController().ifPresent(MainController::refreshUpdateBanner);
    }

    static Optional<String> lastResult() {
        return Optional.ofNullable(lastResult);
    }

    static void setLastResult(String text) {
        lastResult = text;
    }

    /** The text of a failed check or update: the translated key of an {@link UpdateException}, else its message. */
    static String message(Throwable failure) {
        if (failure instanceof UpdateException e) {
            return ResourcesEngine.getString(e.key(), e.args());
        }
        return PopinUtil.messageOf(failure);
    }

    /**
     * The "Update" button: refused while an operation runs, and with a message while another Copybot runs from the
     * same folder; a warning when Windows will ask for an administrator's approval; then the usual exit questions
     * (unsaved pipelines, a refusal cancels everything), the download with its window, the launch of the installer
     * or of the script, and the usual exit, which only then closes the editors. A failure or a cancel deletes the
     * download and Copybot goes on with its editors untouched.
     */
    static void install(Release release) {
        if (service == null || service.installBlocked().isPresent()) {
            return;
        }
        Optional<String> otherInstance = service.otherInstance();
        if (otherInstance.isPresent()) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION, ResourcesEngine.getString(otherInstance.get()));
            alert.initOwner(CopybotMainUi.STAGE);
            alert.setTitle(ResourcesEngine.getString("update.title"));
            alert.setHeaderText(null);
            alert.showAndWait();
            return;
        }
        if (service.adminApprovalNeeded() && !confirmAdminApproval()) {
            return;
        }
        if (!CopybotMainUi.confirmExitForUpdate()) {
            return;
        }
        AtomicBoolean cancelled = new AtomicBoolean();
        ProgressBar bar = new ProgressBar(0);
        bar.setPrefWidth(360);
        Button cancel = new Button(ResourcesEngine.getString("update.cancel"));
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> {
            cancelled.set(true);
            cancel.setDisable(true);
        });
        HBox buttons = new HBox(cancel);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        VBox content = new VBox(10, new Label(ResourcesEngine.getString("update.downloading", release.version())),
                bar, buttons);
        content.setPadding(new Insets(16));
        Stage window = new Stage(StageStyle.UTILITY);
        window.initOwner(CopybotMainUi.STAGE);
        window.initModality(Modality.APPLICATION_MODAL);
        window.setTitle(ResourcesEngine.getString("update.title"));
        window.setResizable(false);
        window.setScene(new Scene(content));
        window.setOnCloseRequest(e -> {
            e.consume(); // closing the window is cancelling
            cancel.fire();
        });
        window.show();

        // the progress of the download thread, handed to the JavaFX thread at most once at a time
        AtomicLong received = new AtomicLong();
        AtomicBoolean pending = new AtomicBoolean();
        Background.run(() -> {
                    long size = release.asset(service.assetName(release)).map(Release.Asset::size).orElse(0L);
                    UpdateService.Prepared prepared = service.prepare(release, bytes -> {
                        received.set(bytes);
                        if (pending.compareAndSet(false, true)) {
                            Platform.runLater(() -> {
                                pending.set(false);
                                bar.setProgress(size > 0 ? (double) received.get() / size : -1);
                            });
                        }
                    }, cancelled::get);
                    service.launch(prepared, cancelled::get); // a cancel during the extraction stops it too
                    return prepared;
                },
                prepared -> {
                    window.hide();
                    LOG.log(System.Logger.Level.INFO, "update to {0} started: {1}", release.version(),
                            prepared.command());
                    // only now are the editors closed: a failed or cancelled update leaves them as they were
                    CopybotMainUi.exitForUpdate(); // the installer or the script waits for this process to end
                },
                failure -> {
                    window.hide();
                    if (failure instanceof UpdateException e && e.key().equals("update.error.cancelled")) {
                        return; // the user's own choice: nothing to tell
                    }
                    LOG.log(System.Logger.Level.WARNING, "update to " + release.version() + " failed", failure);
                    Alert alert = new Alert(Alert.AlertType.ERROR, message(failure));
                    alert.initOwner(CopybotMainUi.STAGE);
                    alert.setTitle(ResourcesEngine.getString("update.title"));
                    alert.setHeaderText(null);
                    alert.show();
                });
    }

    /** Tells, before anything is downloaded, that Windows will ask for an administrator; false when cancelled. */
    private static boolean confirmAdminApproval() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, ResourcesEngine.getString("update.admin"),
                ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(CopybotMainUi.STAGE);
        confirm.setTitle(ResourcesEngine.getString("update.title"));
        confirm.setHeaderText(null);
        return confirm.showAndWait().filter(choice -> choice == ButtonType.OK).isPresent();
    }
}

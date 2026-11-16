package com.copybot.ui;

import com.copybot.engine.plugin.ConfirmationHandler;
import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.util.PopinUtil;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Modality;
import javafx.stage.Window;

import java.util.concurrent.CompletableFuture;

/**
 * The questions of the plugins during a run (spec remote §2): a modal dialog over the focused window, the thread of
 * the run waiting for the answer. A question the engine withdraws (its run is cancelled) closes its dialog. The
 * JavaFX thread never waits: a question asked there is answered no without asking, since the dialog could not be
 * answered while that thread waits (and another question may hold the turn). No is the default button.
 */
final class ConfirmationDialogs implements ConfirmationHandler {

    /** The dialog shown, null when none (visible for tests). */
    private volatile Alert showing;

    @Override
    public CompletableFuture<Boolean> ask(String question) {
        CompletableFuture<Boolean> answer = new CompletableFuture<>();
        try {
            Platform.runLater(() -> show(question, answer));
        } catch (IllegalStateException toolkitGone) {
            answer.complete(false); // no window any more to ask in
        }
        return answer;
    }

    @Override
    public boolean canWaitOnCurrentThread() {
        return !Platform.isFxApplicationThread();
    }

    // visible for tests
    Alert showing() {
        return showing;
    }

    private void show(String question, CompletableFuture<Boolean> answer) {
        if (answer.isDone() || CopybotMainUi.isExiting()) {
            answer.complete(false); // withdrawn before it was shown, or the app is closing
            return;
        }
        ButtonType yes = new ButtonType(ResourcesEngine.getString("confirmation.yes"), ButtonBar.ButtonData.YES);
        ButtonType no = new ButtonType(ResourcesEngine.getString("confirmation.no"), ButtonBar.ButtonData.NO);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, question, yes, no);
        Window owner = PopinUtil.defaultOwner();
        if (owner != null) {
            alert.initOwner(owner);
        }
        alert.initModality(Modality.APPLICATION_MODAL);
        alert.setTitle(ResourcesEngine.getString("confirmation.title"));
        alert.setHeaderText(null);
        ((Button) alert.getDialogPane().lookupButton(yes)).setDefaultButton(false);
        ((Button) alert.getDialogPane().lookupButton(no)).setDefaultButton(true); // Enter never agrees by accident
        alert.setOnHidden(e -> {
            showing = null;
            answer.complete(alert.getResult() == yes); // closed by its window: no (the cancel button)
        });
        answer.whenComplete((value, failure) -> {
            try {
                Platform.runLater(() -> {
                    if (alert.isShowing()) { // withdrawn by the engine while shown
                        alert.setResult(no);
                        alert.close();
                    }
                });
            } catch (IllegalStateException toolkitGone) {
                // no window any more
            }
        });
        showing = alert;
        alert.show();
    }
}

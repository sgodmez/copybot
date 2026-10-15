package com.copybot.ui.util;

import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.CopybotMainUi;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.Window;

import java.io.PrintWriter;
import java.io.StringWriter;

/** The error popup of the views: every error shown is logged too. */
public class PopinUtil {

    private static final System.Logger LOG = System.getLogger(PopinUtil.class.getName());

    /** Over the focused window of the application, else the main window. */
    public static void showError(Throwable failure) {
        showError(null, null, failure);
    }

    /** Over this window (null: the focused one, else the main window). */
    public static void showError(Window owner, Throwable failure) {
        showError(owner, null, failure);
    }

    /**
     * Logs the failure, then shows it on the JavaFX thread (from any thread), unless the application is stopping:
     * its message (the name of its class when it has none), its stack trace on demand.
     *
     * @param header a line above the message, null for none
     */
    public static void showError(Window owner, String header, Throwable failure) {
        LOG.log(System.Logger.Level.WARNING, messageOf(failure), failure);
        show(owner, header, failure);
    }

    /** {@link #showError(Window, String, Throwable)} without the log (the caller logged it). */
    static void show(Window owner, String header, Throwable failure) {
        if (Platform.isFxApplicationThread()) {
            display(owner, header, failure);
            return;
        }
        try {
            Platform.runLater(() -> display(owner, header, failure));
        } catch (IllegalStateException toolkitGone) {
            // no JavaFX any more (or not yet): the log keeps it
        }
    }

    /** The message of the failure, or the name of its class when it has none (a NullPointerException...). */
    public static String messageOf(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getName() : message;
    }

    private static void display(Window owner, String header, Throwable failure) {
        if (CopybotMainUi.isExiting()) {
            return; // a late failure of an operation being stopped: logged only
        }
        Alert alert = new Alert(Alert.AlertType.ERROR);
        Window parent = owner != null ? owner : defaultOwner();
        if (parent != null) {
            alert.initOwner(parent);
        }
        alert.setTitle(ResourcesEngine.getString("error.title"));
        alert.setHeaderText(header);
        alert.setContentText(messageOf(failure));

        // the stack trace, expandable
        StringWriter sw = new StringWriter();
        failure.printStackTrace(new PrintWriter(sw));
        Label label = new Label(ResourcesEngine.getString("error.stacktrace"));

        TextArea textArea = new TextArea(sw.toString());
        textArea.setEditable(false);
        textArea.setWrapText(true);
        textArea.setMaxWidth(Double.MAX_VALUE);
        textArea.setMaxHeight(Double.MAX_VALUE);
        GridPane.setVgrow(textArea, Priority.ALWAYS);
        GridPane.setHgrow(textArea, Priority.ALWAYS);

        GridPane expContent = new GridPane();
        expContent.setMaxWidth(Double.MAX_VALUE);
        expContent.add(label, 0, 0);
        expContent.add(textArea, 0, 1);
        alert.getDialogPane().setExpandableContent(expContent);

        if (Platform.canStartNestedEventLoop()) {
            alert.showAndWait();
        } else {
            alert.show(); // during an animation or a layout (an uncaught failure there): no nested loop
        }
    }

    /** The focused window of the application (a dialog over the main window), else the main window when shown. */
    private static Window defaultOwner() {
        return Window.getWindows().stream()
                .filter(w -> w.isShowing() && w.isFocused())
                .findFirst()
                .orElse(CopybotMainUi.STAGE != null && CopybotMainUi.STAGE.isShowing() ? CopybotMainUi.STAGE : null);
    }
}

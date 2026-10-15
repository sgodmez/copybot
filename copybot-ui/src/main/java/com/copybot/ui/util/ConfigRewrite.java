package com.copybot.ui.util;

import com.copybot.config.ConfigFiles;
import com.copybot.resources.ResourcesEngine;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * A rewrite of the configuration file asked by the user (preferences, resources): the file is read and written off
 * the JavaFX thread (it may sit on a share), after a confirmation when the rewrite would lose some of its content.
 */
public final class ConfigRewrite {

    private ConfigRewrite() {
    }

    /**
     * @param write     writes the file (background thread)
     * @param onWritten once written (JavaFX thread)
     * @param onNotDone when the user declines or the rewrite fails, the failure shown (JavaFX thread)
     */
    public static void rewrite(Window owner, Path configFile, Consumer<Path> write, Runnable onWritten,
                               Runnable onNotDone) {
        Background.run(() -> ConfigFiles.rewriteLosesContent(configFile),
                losesContent -> {
                    if (losesContent && !confirmLossyRewrite(owner, configFile)) {
                        onNotDone.run();
                        return;
                    }
                    Background.run(() -> {
                                write.accept(configFile);
                                return null;
                            },
                            written -> onWritten.run(),
                            failure -> failed(owner, failure, onNotDone));
                },
                failure -> failed(owner, failure, onNotDone));
    }

    private static void failed(Window owner, Throwable failure, Runnable onNotDone) {
        onNotDone.run();
        PopinUtil.showError(owner, failure);
    }

    /** Comments, lenient syntax or repeated keys of the file would be lost: true when the user accepts it. */
    private static boolean confirmLossyRewrite(Window owner, Path configFile) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, ResourcesEngine.getString("pref.save.lossy", configFile),
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(null);
        confirm.initOwner(owner);
        return confirm.showAndWait().filter(ButtonType.OK::equals).isPresent();
    }
}

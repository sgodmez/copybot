package com.copybot.ui.util;

import javafx.scene.Node;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

/** The file and folder choosers of the views open in the folder of the current value, when it is there. */
public final class Choosers {

    private Choosers() {
    }

    /**
     * The value when it is a folder, else its parent when that is one; empty when there is none (blank, invalid
     * path, gone). File I/O: off the JavaFX thread.
     */
    public static Optional<File> initialFolder(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            Path path = Path.of(value.strip()).toAbsolutePath();
            if (Files.isDirectory(path)) {
                return Optional.of(path.toFile());
            }
            Path parent = path.getParent();
            return parent != null && Files.isDirectory(parent) ? Optional.of(parent.toFile()) : Optional.empty();
        } catch (InvalidPathException | SecurityException e) {
            return Optional.empty();
        }
    }

    /**
     * Looks for the folder of the value in the background (a share may not answer), then opens the chooser with
     * it on the JavaFX thread (null: the chooser's default folder). The trigger (null: none) is disabled meanwhile,
     * so that a double click opens one chooser.
     */
    public static void inFolderOf(Node trigger, String value, Consumer<File> open) {
        boolean disable = trigger != null && !trigger.disableProperty().isBound() && !trigger.isDisable();
        if (disable) {
            trigger.setDisable(true);
        }
        Background.run(() -> initialFolder(value).orElse(null),
                folder -> {
                    if (disable) {
                        trigger.setDisable(false);
                    }
                    open.accept(folder);
                },
                failure -> {
                    if (disable) {
                        trigger.setDisable(false);
                    }
                    open.accept(null);
                });
    }
}

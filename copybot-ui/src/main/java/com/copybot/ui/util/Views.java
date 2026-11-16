package com.copybot.ui.util;

import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.CopybotMainUi;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;

import java.io.IOException;
import java.io.UncheckedIOException;

/** Loads the FXML views of com/copybot/ui/views with the current resource bundle. */
public final class Views {

    private Views() {
    }

    public record Loaded<C>(Parent root, C controller) {
    }

    public static <C> Loaded<C> load(String fxml) {
        FXMLLoader loader = new FXMLLoader(CopybotMainUi.class.getResource("views/" + fxml));
        loader.setResources(ResourcesEngine.getResourceBundle());
        try {
            Parent root = loader.load();
            return new Loaded<>(root, loader.getController());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

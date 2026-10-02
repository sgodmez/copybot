package com.copybot.ui;

import com.copybot.ui.util.Views;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The FXML views load with their controller (an FXML error only shows when the view is opened); skipped without display. */
public class ViewsLoadFxTest {

    private static final AtomicBoolean TOOLKIT = new AtomicBoolean();

    @BeforeAll
    static void startToolkit() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
            TOOLKIT.set(started.await(10, TimeUnit.SECONDS));
        } catch (IllegalStateException alreadyStarted) {
            TOOLKIT.set(true);
        } catch (RuntimeException | UnsatisfiedLinkError noDisplay) {
            TOOLKIT.set(false);
        }
        if (TOOLKIT.get()) {
            Platform.setImplicitExit(false);
        }
    }

    private static void assertLoads(String fxml) throws Exception {
        assumeTrue(TOOLKIT.get(), "no JavaFX toolkit (no display)");
        CompletableFuture<Object> controller = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                controller.complete(Views.load(fxml).controller());
            } catch (Throwable t) {
                controller.completeExceptionally(t);
            }
        });
        assertNotNull(controller.get(10, TimeUnit.SECONDS), fxml);
    }

    @Test
    public void thePlanViewLoads() throws Exception {
        assertLoads("plan-view.fxml");
    }

    @Test
    public void theEditorViewLoads() throws Exception {
        assertLoads("editor-view.fxml");
    }
}

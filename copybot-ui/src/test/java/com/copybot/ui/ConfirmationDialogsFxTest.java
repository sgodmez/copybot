package com.copybot.ui;

import com.copybot.engine.CopybotEngine;
import com.copybot.plugin.api.UserConfirmation;
import com.copybot.resources.ResourcesEngine;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The dialog of the questions of the plugins (spec remote §2); skipped without display. */
@Timeout(60)
public class ConfirmationDialogsFxTest {

    private static final AtomicBoolean TOOLKIT = new AtomicBoolean();

    private final ConfirmationDialogs dialogs = new ConfirmationDialogs();

    @BeforeAll
    static void startToolkit() throws InterruptedException {
        ResourcesEngine.registerBundle("com.copybot.ui.i18n.uiBundle");
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

    @BeforeEach
    void needsDisplay() {
        assumeTrue(TOOLKIT.get(), "no display");
    }

    @AfterEach
    void noHandler() throws Exception {
        CopybotEngine.setConfirmationHandler(null);
        onFx(() -> {
            Alert left = dialogs.showing();
            if (left != null) {
                left.close();
            }
            return null;
        });
    }

    private static <T> T onFx(Supplier<T> action) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(action.get());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result.get(10, TimeUnit.SECONDS);
    }

    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < end, "condition not met within 10 s");
            Thread.sleep(10);
        }
    }

    private Alert shown() throws InterruptedException {
        awaitTrue(() -> dialogs.showing() != null);
        return dialogs.showing();
    }

    private static Button button(Alert alert, ButtonBar.ButtonData data) {
        ButtonType type = alert.getButtonTypes().stream().filter(t -> t.getButtonData() == data).findFirst().orElseThrow();
        return (Button) alert.getDialogPane().lookupButton(type);
    }

    @Test
    void theQuestionIsShownAndYesAnswersYes() throws Exception {
        CompletableFuture<Boolean> answer = dialogs.ask("Trust nas.local?");
        Alert alert = shown();

        assertEquals("Trust nas.local?", onFx(alert::getContentText));
        assertEquals(ResourcesEngine.getString("confirmation.title"), onFx(alert::getTitle));
        assertTrue(onFx(() -> button(alert, ButtonBar.ButtonData.NO).isDefaultButton()), "no is the default button");
        assertFalse(onFx(() -> button(alert, ButtonBar.ButtonData.YES).isDefaultButton()));
        assertFalse(answer.isDone(), "the answer waits for the user");

        onFx(() -> {
            button(alert, ButtonBar.ButtonData.YES).fire();
            return null;
        });
        assertTrue(answer.get(10, TimeUnit.SECONDS));
        assertNull(dialogs.showing());
    }

    @Test
    void noAnswersNo() throws Exception {
        CompletableFuture<Boolean> answer = dialogs.ask("Trust nas.local?");
        Alert alert = shown();
        onFx(() -> {
            button(alert, ButtonBar.ButtonData.NO).fire();
            return null;
        });
        assertFalse(answer.get(10, TimeUnit.SECONDS));
    }

    @Test
    void aWithdrawnQuestionClosesItsDialog() throws Exception {
        CompletableFuture<Boolean> answer = dialogs.ask("Trust nas.local?");
        Alert alert = shown();

        answer.cancel(true); // what the engine does when the run is cancelled

        awaitTrue(() -> dialogs.showing() == null);
        assertFalse(onFx(alert::isShowing));
    }

    @Test
    void theJavaFxThreadNeverWaits() throws Exception {
        assertTrue(dialogs.canWaitOnCurrentThread());
        assertFalse(onFx(dialogs::canWaitOnCurrentThread));
        CopybotEngine.setConfirmationHandler(dialogs);
        assertFalse(onFx(() -> UserConfirmation.ask("Trust nas.local?")), "answered no at once");
        assertNull(dialogs.showing(), "nothing was asked");
    }

    @Test
    void aCancelledRunClosesTheDialogAndThePluginGetsNo() throws Exception {
        CopybotEngine.setConfirmationHandler(dialogs);
        AtomicBoolean answer = new AtomicBoolean(true);
        Thread run = Thread.ofVirtual().start(() -> answer.set(UserConfirmation.ask("Trust nas.local?")));
        Alert alert = shown();

        run.interrupt(); // the engine interrupts the threads of a cancelled run
        run.join(TimeUnit.SECONDS.toMillis(10));

        assertFalse(run.isAlive());
        assertFalse(answer.get());
        awaitTrue(() -> dialogs.showing() == null);
        assertFalse(onFx(alert::isShowing));
    }
}

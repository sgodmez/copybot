package com.copybot.ui;

import com.copybot.engine.sample.Sample;
import com.copybot.engine.sample.SampleItem;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The pattern helper on a real JavaFX scene; skipped where no display can start the toolkit. */
public class PatternHelperFxTest {

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
    }

    private static <T> T onFx(java.util.concurrent.Callable<T> work) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(work.call());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result.get(10, TimeUnit.SECONDS);
    }

    /** What a user's click does: the press (where a focusable button takes the focus), then the release. */
    private static void click(Hyperlink link) {
        for (var type : List.of(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED)) {
            Event.fireEvent(link, new MouseEvent(type, 1, 1, 1, 1, MouseButton.PRIMARY, 1,
                    false, false, false, false, type == MouseEvent.MOUSE_PRESSED, false, false,
                    false, false, true, null));
        }
    }

    @Test
    public void clickingAKeyInsertsItAtTheCaretAndKeepsTheFieldFocused() throws Exception {
        assumeTrue(TOOLKIT.get(), "no JavaFX toolkit (no display)");
        String[] result = onFx(() -> {
            TextField field = new TextField("/nas//x");
            PatternHelper helper = new PatternHelper(field, () -> "error", () -> { }, () -> { });
            helper.showSample(new Sample(List.of(new SampleItem("a.JPG", "a.JPG", Map.of("name", "a.JPG"), Optional.empty())),
                    1, false, List.of(), Optional.empty()));
            Stage stage = new Stage();
            stage.setScene(new Scene(new VBox(field, helper)));
            stage.show();
            try {
                field.requestFocus();
                field.positionCaret(5);
                Hyperlink link = (Hyperlink) helper.lookupAll(".hyperlink").iterator().next();

                click(link);
                String afterFirst = field.getText();
                click(link); // the caret must have stayed after the first insertion

                return new String[]{afterFirst, field.getText(), String.valueOf(field.getCaretPosition()),
                        String.valueOf(stage.getScene().getFocusOwner() == field), field.getSelectedText()};
            } finally {
                stage.close();
            }
        });

        assertEquals("/nas/{name}/x", result[0]);
        assertEquals("/nas/{name}{name}/x", result[1]);
        assertEquals("17", result[2]);
        assertEquals("true", result[3], "the field keeps the focus");
        assertEquals("", result[4], "nothing is selected (typing must not replace the pattern)");
    }

    @Test
    public void aKeyClickedBeforeAnyClickInTheFieldGoesAtTheEnd() throws Exception {
        assumeTrue(TOOLKIT.get(), "no JavaFX toolkit (no display)");
        String[] result = onFx(() -> {
            TextField field = new TextField("/nas/");
            PatternHelper helper = new PatternHelper(field, () -> "error", () -> { }, () -> { });
            helper.showSample(new Sample(List.of(new SampleItem("a.JPG", "a.JPG", Map.of("name", "a.JPG"), Optional.empty())),
                    1, false, List.of(), Optional.empty()));
            Stage stage = new Stage();
            VBox root = new VBox(helper, field); // the helper first: nothing gives the field the focus
            stage.setScene(new Scene(root));
            stage.show();
            try {
                root.requestFocus();
                click((Hyperlink) helper.lookupAll(".hyperlink").iterator().next());
                return new String[]{field.getText(), String.valueOf(field.getCaretPosition()), field.getSelectedText()};
            } finally {
                stage.close();
            }
        });

        assertEquals("/nas/{name}", result[0]);
        assertEquals("11", result[1]);
        assertEquals("", result[2]);
    }
}

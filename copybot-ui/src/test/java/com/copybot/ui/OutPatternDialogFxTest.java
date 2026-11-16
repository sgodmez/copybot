package com.copybot.ui;

import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import com.copybot.resources.ResourcesEngine;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.MenuItem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The "Output pattern" dialog of the plan view (spec own-out-pattern §3); skipped without display. */
public class OutPatternDialogFxTest {

    private static final AtomicBoolean TOOLKIT = new AtomicBoolean();
    private static final String DEFAULT = "{lastModified.Y}/{name}";

    @TempDir
    Path dir;

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

    private static <T> T onFx(Callable<T> work) throws Exception {
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

    private WorkItem item(String name, boolean dated) throws IOException {
        WorkItem item = new WorkItem(dir.resolve(name));
        item.getMetadatas().display().put("name", name); // as an analysis gives it
        if (dated) {
            item.getMetadatas().setTime(WorkItemMetadata.LAST_MODIFIED, Instant.parse("2026-09-29T08:00:00Z"));
        }
        return item;
    }

    private static Button button(OutPatternDialog dialog, ButtonType type) {
        return (Button) dialog.dialog().getDialogPane().lookupButton(type);
    }

    @Test
    public void prefilledThenPreviewedLiveOkDisabledOnASyntaxErrorAndReturnsThePattern() throws Exception {
        assumeTrue(TOOLKIT.get(), "no JavaFX toolkit (no display)");
        List<WorkItem> items = List.of(item("a.jpg", true), item("b.jpg", true));
        Object[] seen = onFx(() -> {
            OutPatternDialog dialog = new OutPatternDialog(null, items, DEFAULT, "error");
            Button ok = button(dialog, ButtonType.OK);
            String prefilled = dialog.field().getText();
            boolean okAtFirst = !ok.isDisable();

            dialog.field().setText("x/{name");
            boolean disabledOnError = ok.isDisable();
            boolean errorShown = dialog.error().isVisible() && !dialog.error().getText().isBlank();
            List<String> rowsOnError = dialog.previewTexts();

            dialog.field().setText("y/{name}");
            boolean enabledAgain = !ok.isDisable();
            boolean errorHidden = !dialog.error().isVisible();
            List<String> rows = dialog.previewTexts();

            dialog.dialog().show();
            ok.fire();
            return new Object[]{prefilled, okAtFirst, disabledOnError, errorShown, rowsOnError, enabledAgain,
                    errorHidden, rows, dialog.dialog().getResult(), dialog.dialog().isShowing()};
        });
        assertEquals(DEFAULT, seen[0], "prefilled with the pipeline pattern");
        assertEquals(true, seen[1]);
        assertEquals(true, seen[2], "OK disabled on a syntax error");
        assertEquals(true, seen[3], "the syntax error shown");
        assertEquals(List.of(), seen[4], "no row on a syntax error");
        assertEquals(true, seen[5]);
        assertEquals(true, seen[6]);
        assertEquals(List.of("a.jpg \u2192 y/a.jpg", "b.jpg \u2192 y/b.jpg"), seen[7], "the target of each file");
        assertEquals("y/{name}", seen[8], "OK returns the pattern");
        assertEquals(false, seen[9]);
    }

    @Test
    public void cancelReturnsNothing() throws Exception {
        assumeTrue(TOOLKIT.get(), "no JavaFX toolkit (no display)");
        List<WorkItem> items = List.of(item("a.jpg", true));
        Optional<String> result = onFx(() -> {
            OutPatternDialog dialog = new OutPatternDialog(null, items, DEFAULT, "error");
            dialog.field().setText("z/{name}");
            dialog.dialog().show();
            button(dialog, ButtonType.CANCEL).fire();
            return Optional.ofNullable(dialog.dialog().getResult());
        });
        assertEquals(Optional.empty(), result);
    }

    @Test
    public void theMissingKeysAreCountedWithTheStepsConsequenceAndThePickerInsertsAKey() throws Exception {
        assumeTrue(TOOLKIT.get(), "no JavaFX toolkit (no display)");
        List<WorkItem> items = List.of(item("a.jpg", true), item("b.jpg", false));
        Object[] seen = onFx(() -> {
            OutPatternDialog dialog = new OutPatternDialog(null, items, DEFAULT, "skip");
            String missing = dialog.missing().isVisible() ? dialog.missing().getText() : "";
            String pipeline = dialog.pipeline().getText();
            dialog.field().setText("x/");
            boolean missingHidden = !dialog.missing().isVisible();
            MenuItem name = dialog.picker().getItems().stream().filter(i -> "{name}".equals(i.getText()))
                    .findFirst().orElseThrow();
            name.fire();
            return new Object[]{missing, pipeline, missingHidden, dialog.field().getText()};
        });
        assertEquals(ResourcesEngine.getString("helper.missing", 1, ResourcesEngine.getString("helper.effect.skip")),
                seen[0]);
        assertEquals(ResourcesEngine.getString("out-pattern.pipeline", DEFAULT), seen[1]);
        assertTrue((Boolean) seen[2], "no missing key, no warning");
        assertEquals("x/{name}", seen[3], "the field had not the focus: the key goes at the end");
        assertFalse(((String) seen[0]).isBlank());
    }
}

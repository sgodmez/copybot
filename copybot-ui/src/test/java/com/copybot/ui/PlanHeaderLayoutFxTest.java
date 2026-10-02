package com.copybot.ui;

import com.copybot.ui.util.Views;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The layout of the plan view's header card (real JavaFX layout, no events); skipped without display. */
public class PlanHeaderLayoutFxTest {

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

    /** {view width, card width, gap source→destination, details link right edge} for these paths in a window of the width. */
    private static double[] measure(double width, String source, String destination) throws Exception {
        CompletableFuture<double[]> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            Stage stage = new Stage();
            try {
                Parent root = Views.load("plan-view.fxml").root();
                Label sourceLabel = (Label) root.lookup("#sourceLabel");
                Label destinationLabel = (Label) root.lookup("#destinationLabel");
                Hyperlink details = (Hyperlink) root.lookup("#detailsLink");
                sourceLabel.setText(source);
                destinationLabel.setText(destination);
                details.setText("Détails ▾");
                stage.setScene(new Scene(root, width, 400));
                stage.show();
                root.applyCss();
                root.layout();
                Region card = (Region) root.lookup("#summaryCard");
                // where the source text ends: a stretched label draws its text on the left of its bounds
                double sourceRight = sourceLabel.localToScene(sourceLabel.getLayoutBounds()).getMinX()
                        + Math.min(sourceLabel.getWidth(), sourceLabel.prefWidth(-1));
                double destinationLeft = destinationLabel.localToScene(destinationLabel.getLayoutBounds()).getMinX();
                double detailsRight = details.localToScene(details.getLayoutBounds()).getMaxX();
                result.complete(new double[]{((Region) root).getWidth(), card.getWidth(), destinationLeft - sourceRight, detailsRight});
            } catch (Throwable t) {
                result.completeExceptionally(t);
            } finally {
                stage.close();
            }
        });
        return result.get(10, TimeUnit.SECONDS);
    }

    @Test
    public void sourceAndDestinationStayCloseTogether() throws Exception {
        assumeTrue(TOOLKIT.get(), "no JavaFX toolkit (no display)");
        double[] m = measure(1200, "📂 D:\\photos\\in", "💾 D:\\photos\\out\\{name}");

        assertTrue(m[2] < 40, "gap between source and destination: " + m[2]);
    }

    /** {name, date, target, size, status} column widths of the plan table in a window of this width. */
    private static double[] columnWidths(double width) throws Exception {
        CompletableFuture<double[]> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            Stage stage = new Stage();
            try {
                Parent root = Views.load("plan-view.fxml").root();
                stage.setScene(new Scene(root, width, 500));
                stage.show();
                root.applyCss();
                root.layout();
                javafx.scene.control.TableView<?> table = (javafx.scene.control.TableView<?>) root.lookup("#itemsTable");
                result.complete(table.getColumns().stream().mapToDouble(javafx.scene.control.TableColumnBase::getWidth).toArray());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            } finally {
                stage.close();
            }
        });
        return result.get(10, TimeUnit.SECONDS);
    }

    @Test
    public void aWideTableGivesTheSpaceToTheTargetNotToTheShortColumns() throws Exception {
        assumeTrue(TOOLKIT.get(), "no JavaFX toolkit (no display)");
        double[] w = columnWidths(1800);
        String widths = java.util.Arrays.toString(w);

        assertTrue(w[1] <= 150, "date stays narrow: " + widths);
        assertTrue(w[3] <= 110, "size stays narrow: " + widths);
        assertTrue(w[2] > w[0] && w[2] > w[4], "the target is the widest: " + widths);
    }

    @Test
    public void longPathsShrinkInsteadOfWideningTheView() throws Exception {
        assumeTrue(TOOLKIT.get(), "no JavaFX toolkit (no display)");
        String longPath = "D:\\photos - Copie\\out\\{captureDate.Y}\\{captureDate.Y}-{captureDate.m}-{captureDate.D}\\{name}";
        double[] m = measure(800, "📂 " + longPath, "💾 " + longPath);

        assertTrue(m[0] <= 800, "view width: " + m[0]);
        assertTrue(m[1] <= 800, "card width: " + m[1]);
        assertTrue(m[3] <= 800, "the details link stays in view, right edge: " + m[3]);
    }
}

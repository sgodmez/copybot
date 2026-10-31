package com.copybot.ui;

import com.copybot.engine.sample.Sample;
import com.copybot.engine.sample.SampleItem;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
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

    private static final Sample SAMPLE = new Sample(List.of(
            new SampleItem("a.JPG", "a.JPG", Map.of("name", "a.JPG", "captureDate.Y", "2026"), Optional.empty()),
            new SampleItem("b.MP4", "b.MP4", Map.of("name", "b.MP4"), Optional.empty())),
            2, false, List.of(), Optional.empty());

    private static final PatternHelper.Actions NO_ACTIONS = new PatternHelper.Actions() {
        @Override
        public void retry() {
        }

        @Override
        public void cancel() {
        }

        @Override
        public void test(Path file) {
        }

        @Override
        public void unpin(Path file) {
        }

        @Override
        public Path sourceFolder() {
            return null;
        }
    };

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
            Platform.setImplicitExit(false); // the stages closed by a test must not stop the toolkit
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

    /** What a user's click does: the press (where a focusable control takes the focus), the release, the click. */
    private static void click(Node node) {
        for (var type : List.of(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED)) {
            Event.fireEvent(node, new MouseEvent(type, 1, 1, 1, 1, MouseButton.PRIMARY, 1,
                    false, false, false, false, type == MouseEvent.MOUSE_PRESSED, false, false,
                    false, false, true, null));
        }
    }

    private static PatternHelper helper(TextField field, PatternHelper.State state) {
        PatternHelper helper = new PatternHelper(field, () -> "error", state, NO_ACTIONS);
        helper.showSample(SAMPLE);
        return helper;
    }

    /** Opens the picker by a click on {+}, then clicks the row of the key. */
    private static void pick(PatternHelper helper, String key) {
        click(helper.pickerButton());
        assertTrue(helper.picker().isShowing(), "the picker opened");
        Node row = helper.picker().getContent().getFirst().lookupAll("." + PatternHelper.KEY_ROW_CLASS).stream()
                .filter(node -> key.equals(node.getUserData())).findFirst().orElseThrow();
        click(row);
    }

    @Test
    public void aSingleClickInThePickerInsertsTheKeyAtTheCaretAndClosesIt() throws Exception {
        assumeTrue(TOOLKIT.get(), "no JavaFX toolkit (no display)");
        String[] result = onFx(() -> {
            TextField field = new TextField("/nas//x");
            PatternHelper helper = helper(field, new PatternHelper.State());
            Stage stage = new Stage();
            stage.setScene(new Scene(new VBox(new HBox(field, helper.pickerButton()), helper)));
            stage.show();
            try {
                field.requestFocus();
                field.positionCaret(5);

                pick(helper, "name");
                String afterFirst = field.getText();
                boolean closed = !helper.picker().isShowing();
                pick(helper, "captureDate.Y"); // the caret must have stayed after the first insertion

                return new String[]{afterFirst, field.getText(), String.valueOf(field.getCaretPosition()),
                        String.valueOf(stage.getScene().getFocusOwner() == field), field.getSelectedText(),
                        String.valueOf(closed), String.valueOf(helper.picker().isShowing())};
            } finally {
                helper.picker().hide();
                stage.close();
            }
        });

        assertEquals("/nas/{name}/x", result[0]);
        assertEquals("/nas/{name}{captureDate.Y}/x", result[1]);
        assertEquals("26", result[2]);
        assertEquals("true", result[3], "the field keeps the focus");
        assertEquals("", result[4], "nothing is selected (typing must not replace the pattern)");
        assertEquals("true", result[5], "the picker closed after the click");
        assertEquals("false", result[6]);
    }

    @Test
    public void aKeyPickedBeforeAnyClickInTheFieldGoesAtTheEnd() throws Exception {
        assumeTrue(TOOLKIT.get(), "no JavaFX toolkit (no display)");
        String[] result = onFx(() -> {
            TextField field = new TextField("/nas/");
            PatternHelper helper = helper(field, new PatternHelper.State());
            Stage stage = new Stage();
            VBox root = new VBox(helper, new HBox(helper.pickerButton(), field)); // nothing gives the field the focus
            stage.setScene(new Scene(root));
            stage.show();
            try {
                root.requestFocus();
                pick(helper, "name");
                return new String[]{field.getText(), String.valueOf(field.getCaretPosition()), field.getSelectedText()};
            } finally {
                helper.picker().hide();
                stage.close();
            }
        });

        assertEquals("/nas/{name}", result[0]);
        assertEquals("11", result[1]);
        assertEquals("", result[2]);
    }

    @Test
    public void showTheRowsExpandsThePanelAndARebuiltHelperKeepsItExpanded() throws Exception {
        assumeTrue(TOOLKIT.get(), "no JavaFX toolkit (no display)");
        boolean[] result = onFx(() -> {
            PatternHelper.State state = new PatternHelper.State();
            TextField field = new TextField("/nas/{captureDate.Y}/{name}");
            PatternHelper helper = helper(field, state);
            Stage stage = new Stage();
            stage.setScene(new Scene(new VBox(field, helper)));
            stage.show();
            try {
                boolean collapsedFirst = !panel(helper).isVisible();
                click(helper.lookupAll(".hyperlink").stream().findFirst().orElseThrow());
                boolean expanded = panel(helper).isVisible();

                // the form is rebuilt (another node selected then the out step again): a new helper, same state
                PatternHelper rebuilt = helper(new TextField("/nas/{name}"), state);
                stage.getScene().setRoot(new VBox(rebuilt));
                return new boolean[]{collapsedFirst, expanded, state.expanded, panel(rebuilt).isVisible()};
            } finally {
                stage.close();
            }
        });

        assertTrue(result[0], "collapsed by default");
        assertTrue(result[1], "the click expanded the panel");
        assertTrue(result[2]);
        assertTrue(result[3], "the rebuilt helper is expanded");
    }

    @Test
    public void whenTheListingFailedAFileCanStillBeTestedAndItsRowsShowAndRemove() throws Exception {
        assumeTrue(TOOLKIT.get(), "no JavaFX toolkit (no display)");
        List<Path> unpinned = new java.util.ArrayList<>();
        String[] result = onFx(() -> {
            PatternHelper.State state = new PatternHelper.State();
            PatternHelper.Actions actions = new PatternHelper.Actions() {
                @Override public void retry() { }
                @Override public void cancel() { }
                @Override public void test(Path file) { }
                @Override public void unpin(Path file) { unpinned.add(file); }
                @Override public Path sourceFolder() { return null; }
            };
            TextField field = new TextField("/nas/{name}");
            PatternHelper helper = new PatternHelper(field, () -> "error", state, actions);
            helper.showSample(Sample.failed("no card"));
            Stage stage = new Stage();
            stage.setScene(new Scene(new VBox(field, helper)));
            stage.show();
            try {
                Node test = helper.lookup("." + PatternHelper.TEST_FILE_CLASS);
                boolean testReachable = test != null && test.isVisible() && test.getParent().isVisible();
                boolean panelWithoutPinned = panel(helper).isVisible();

                Path file = Path.of("x.JPG").toAbsolutePath();
                state.pinned.add(new PatternHelper.Pinned(file, null));
                helper.refresh();
                helper.applyCss();
                helper.layout();
                boolean analysing = texts(panel(helper)).contains(com.copybot.resources.ResourcesEngine.getString("helper.analysing"));
                state.pinned.set(0, new PatternHelper.Pinned(file, new Sample(List.of(
                        new SampleItem("x.JPG", "x.JPG", Map.of("name", "x.JPG"), Optional.empty())),
                        1, false, List.of(), Optional.empty())));
                helper.refresh();
                helper.applyCss();
                helper.layout();
                String shown = texts(panel(helper));
                javafx.scene.control.Button remove = panel(helper).lookupAll(".button").stream()
                        .map(javafx.scene.control.Button.class::cast).filter(b -> "✕".equals(b.getText()))
                        .findFirst().orElseThrow();
                click(remove);
                return new String[]{String.valueOf(testReachable), String.valueOf(panelWithoutPinned),
                        String.valueOf(panel(helper).isVisible()), String.valueOf(analysing), shown};
            } finally {
                stage.close();
            }
        });

        assertEquals("true", result[0], "\"Test a file\" is on the failure line");
        assertEquals("false", result[1], "no panel while nothing is pinned");
        assertEquals("true", result[2], "the panel shows the tested files");
        assertEquals("true", result[3], "a file being analysed says so");
        assertTrue(result[4].contains("/nas/x.JPG"), result[4]);
        assertEquals(List.of(Path.of("x.JPG").toAbsolutePath()), unpinned);
    }

    @Test
    public void typingKeepsTheScrollOfTheTable() throws Exception {
        assumeTrue(TOOLKIT.get(), "no JavaFX toolkit (no display)");
        double[] result = onFx(() -> {
            List<SampleItem> items = new java.util.ArrayList<>();
            for (int i = 0; i < 30; i++) {
                items.add(new SampleItem("f" + i + ".JPG", "f" + i + ".JPG", Map.of("name", "f" + i + ".JPG"), Optional.empty()));
            }
            PatternHelper.State state = new PatternHelper.State();
            state.expanded = true;
            TextField field = new TextField("/nas/{name}");
            PatternHelper helper = new PatternHelper(field, () -> "error", state, NO_ACTIONS);
            helper.showSample(new Sample(items, 30, false, List.of(), Optional.empty()));
            Stage stage = new Stage();
            stage.setScene(new Scene(new VBox(field, helper), 600, 500));
            stage.show();
            try {
                javafx.scene.control.ScrollPane before = (javafx.scene.control.ScrollPane) panel(helper).lookup(".scroll-pane");
                before.setVvalue(0.7);
                field.appendText("/x");
                javafx.scene.control.ScrollPane after = (javafx.scene.control.ScrollPane) panel(helper).lookup(".scroll-pane");
                return new double[]{before == after ? 1 : 0, after.getVvalue()};
            } finally {
                stage.close();
            }
        });

        assertEquals(1, result[0], "the same scroll pane");
        assertEquals(0.7, result[1], 1e-9);
    }

    /** The texts of the labels and text nodes under a node, joined. */
    private static String texts(Node root) {
        StringBuilder all = new StringBuilder();
        if (root instanceof javafx.scene.control.Labeled labeled) {
            all.append(labeled.getText()).append('|');
        } else if (root instanceof javafx.scene.text.Text text) {
            all.append(text.getText());
        }
        if (root instanceof javafx.scene.Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> all.append(texts(child)));
        }
        return all.toString();
    }

    /** The panel: the last child of the helper. */
    private static Node panel(PatternHelper helper) {
        return helper.getChildren().getLast();
    }
}

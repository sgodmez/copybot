package com.copybot.ui;

import com.copybot.engine.pipeline.SecondaryOutputConfig;
import com.copybot.engine.pipeline.StepType;
import com.copybot.engine.plugin.CatalogAction;
import com.copybot.engine.plugin.PluginCatalog;
import com.copybot.plugin.api.config.ConfigSchema;
import com.copybot.plugin.api.config.PatternField;
import com.copybot.plugin.api.config.Required;
import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.EditorController.EditorNode;
import com.copybot.ui.EditorController.Kind;
import com.copybot.ui.model.PipelineDocument;
import com.copybot.ui.model.PipelineDocument.Section;
import com.copybot.ui.model.PipelineDocument.StepAddress;
import com.copybot.ui.model.StepCatalog;
import com.copybot.ui.util.Views;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The secondary outputs in the editor tree and their forms (spec secondary-outputs §8); skipped without display. */
public class EditorSecondaryOutputsFxTest {

    private static final AtomicBoolean TOOLKIT = new AtomicBoolean();

    record WriteConfig(@Required @PatternField String outPattern, Boolean deleteSource) {
    }

    record ThumbConfig(Integer size) {
    }

    private static final StepCatalog CATALOG = new StepCatalog(new PluginCatalog(List.of(
            action(CatalogAction.EMBEDDED_PLUGIN, null, "file.write", StepType.OUT, WriteConfig.class),
            action("com.acme.thumbs", "1.0.0", "thumb.make", StepType.PROCESS, ThumbConfig.class)), List.of()));

    private static final String PIPELINE = """
            {
              "outStep": { "action": "file.write", "actionConfig": { "outPattern": "/nas/{name}" } },
              "secondaryOutputs": [
                { "name": "Thumbs",
                  "actionSteps": [ { "plugin": "com.acme.thumbs", "action": "thumb.make", "actionConfig": { } } ],
                  "outStep": { "action": "file.write",
                               "actionConfig": { "outPattern": "/thumbs/{name}", "deleteSource": true } } },
                { }
              ]
            }
            """;

    /** The stages of the test, hidden after it so that EditorController.OPEN does not keep their editors. */
    private static final List<Stage> STAGES = new java.util.concurrent.CopyOnWriteArrayList<>();

    @AfterEach
    void closeStages() throws Exception {
        if (TOOLKIT.get()) {
            onFx(() -> {
                STAGES.forEach(Stage::hide);
                STAGES.clear();
                return null;
            });
        }
    }

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

    private static CatalogAction action(String plugin, String version, String code, StepType type,
                                        Class<? extends Record> config) {
        return new CatalogAction(plugin, plugin, version, code, type, code + " name", code + " description",
                ConfigSchema.of(config).withKeyPrefix("plugin." + plugin + "." + code), Map.of());
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

    /** An editor of the document, not shown. */
    private record Editor(EditorController controller, Parent root, PipelineDocument document) {

        static Editor of(PipelineDocument document) {
            Views.Loaded<EditorController> view = Views.load("editor-view.fxml");
            Stage stage = new Stage();
            STAGES.add(stage);
            stage.setScene(new Scene(view.root()));
            view.controller().init(stage, document, CATALOG, null, null);
            view.root().applyCss(); // the skins: the split pane and the scroll pane show their content
            view.root().layout();
            return new Editor(view.controller(), view.root(), document);
        }

        @SuppressWarnings("unchecked")
        TreeView<EditorNode> tree() {
            return (TreeView<EditorNode>) root.lookup(".tree-view");
        }

        void select(EditorNode node) {
            tree().getSelectionModel().select(find(tree().getRoot(), node));
        }

        EditorNode selected() {
            return tree().getSelectionModel().getSelectedItem().getValue();
        }

        Button button(String key) {
            return root.lookupAll(".button").stream().map(Button.class::cast)
                    .filter(b -> ResourcesEngine.getString(key).equals(b.getText())).findFirst().orElseThrow();
        }

        /** The nodes of the form, depth first. */
        List<Node> form() {
            List<Node> nodes = new ArrayList<>();
            collect(((ScrollPane) root.lookup(".scroll-pane")).getContent(), nodes);
            return nodes;
        }

        List<String> labels() {
            return form().stream().filter(Label.class::isInstance).map(n -> ((Label) n).getText()).toList();
        }

        <T extends Node> T formNode(Class<T> type) {
            return form().stream().filter(type::isInstance).map(type::cast).findFirst().orElseThrow();
        }

        private static void collect(Node node, List<Node> nodes) {
            nodes.add(node);
            if (node instanceof Parent parent) {
                parent.getChildrenUnmodifiable().forEach(child -> collect(child, nodes));
            }
        }
    }

    private static TreeItem<EditorNode> find(TreeItem<EditorNode> item, EditorNode node) {
        if (node.equals(item.getValue())) {
            return item;
        }
        for (TreeItem<EditorNode> child : item.getChildren()) {
            TreeItem<EditorNode> found = find(child, node);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static final EditorNode SECONDARY = new EditorNode(Kind.SECONDARY, StepAddress.MAIN, null, -1);

    private static String sourceKept(String label) {
        return "⚠ " + ResourcesEngine.getString("editor.secondary.source-kept",
                ResourcesEngine.getString("editor.secondary.quoted", label));
    }

    @Test
    public void theSecondaryOutputsFollowTheOutputWithTheirStepsUnderEachBranch() throws Exception {
        assumeTrue(TOOLKIT.get(), "no JavaFX toolkit (no display)");
        onFx(() -> {
            Editor editor = Editor.of(PipelineDocument.parse(PIPELINE));
            List<EditorNode> top = editor.tree().getRoot().getChildren().stream().map(TreeItem::getValue).toList();
            int out = top.indexOf(EditorNode.section(StepAddress.MAIN, Section.OUT));
            assertEquals(SECONDARY, top.get(out + 1));
            assertEquals(EditorNode.section(StepAddress.MAIN, Section.RUN_END), top.get(out + 2));

            TreeItem<EditorNode> secondary = editor.tree().getRoot().getChildren().get(out + 1);
            assertEquals(ResourcesEngine.getString("editor.secondary"), editor.controller().label(SECONDARY));
            assertEquals(List.of("Thumbs", "#2"), secondary.getChildren().stream()
                    .map(item -> editor.controller().label(item.getValue())).toList());
            TreeItem<EditorNode> thumbs = secondary.getChildren().getFirst();
            assertEquals(List.of(EditorNode.section(0, Section.PROCESS), EditorNode.section(0, Section.OUT)),
                    thumbs.getChildren().stream().map(TreeItem::getValue).toList());
            assertEquals(List.of(EditorNode.step(StepAddress.branch(0, Section.PROCESS, 0))),
                    thumbs.getChildren().get(0).getChildren().stream().map(TreeItem::getValue).toList());
            assertEquals(List.of(EditorNode.step(StepAddress.branch(0, Section.OUT, 0))),
                    thumbs.getChildren().get(1).getChildren().stream().map(TreeItem::getValue).toList());
            assertEquals("thumb.make name", editor.controller().label(EditorNode.step(
                    StepAddress.branch(0, Section.PROCESS, 0))));
            return null;
        });
    }

    @Test
    public void theSecondaryOutputsNodeAddsANamedBranch() throws Exception {
        assumeTrue(TOOLKIT.get(), "no JavaFX toolkit (no display)");
        onFx(() -> {
            Editor editor = Editor.of(PipelineDocument.parse(PIPELINE));
            editor.select(SECONDARY);
            assertFalse(editor.button("editor.add").isDisabled());
            assertTrue(editor.button("editor.up").isDisabled());
            assertTrue(editor.button("editor.remove").isDisabled());
            assertTrue(editor.labels().contains(ResourcesEngine.getString("editor.secondary.help")));

            editor.button("editor.add").fire();

            assertEquals(3, editor.document().branchCount());
            assertEquals(ResourcesEngine.getString("editor.secondary.new-name", "3"), editor.document().branchName(2));
            assertEquals(EditorNode.branch(2), editor.selected());
            return null;
        });
    }

    @Test
    public void theBranchFormRenamesAndSetsWhatAnErrorDoes() throws Exception {
        assumeTrue(TOOLKIT.get(), "no JavaFX toolkit (no display)");
        onFx(() -> {
            Editor editor = Editor.of(PipelineDocument.parse(PIPELINE));
            editor.select(EditorNode.branch(0));
            assertTrue(editor.labels().contains(sourceKept("Thumbs")), editor.labels().toString());
            assertTrue(editor.button("editor.up").isDisabled());
            assertFalse(editor.button("editor.down").isDisabled());

            editor.formNode(TextField.class).setText("Small");
            @SuppressWarnings("unchecked")
            ComboBox<SecondaryOutputConfig.OnError> onError = editor.formNode(ComboBox.class);
            onError.setValue(SecondaryOutputConfig.OnError.WARN);

            assertEquals("Small", editor.document().branchName(0));
            assertEquals(SecondaryOutputConfig.OnError.WARN, editor.document().onError(0));
            assertEquals("Small", editor.controller().label(EditorNode.branch(0)));
            assertTrue(editor.labels().contains(sourceKept("Small")), "the problems follow the name");
            return null;
        });
    }

    /** The branch without name nor output: what the engine would refuse, in its form. */
    @Test
    public void theBranchFormShowsItsBlockingProblems() throws Exception {
        assumeTrue(TOOLKIT.get(), "no JavaFX toolkit (no display)");
        onFx(() -> {
            Editor editor = Editor.of(PipelineDocument.parse(PIPELINE));
            editor.select(EditorNode.branch(1));
            List<String> expected = editor.document().branchProblems().stream().filter(p -> p.branch() == 1)
                    .map(p -> "⚠ " + p.message()).toList();
            assertEquals(2, expected.size(), expected.toString());
            assertTrue(editor.labels().containsAll(expected), editor.labels().toString());
            assertFalse(editor.labels().contains(sourceKept("Thumbs")), "only the problems of this branch");

            editor.select(SECONDARY);
            assertTrue(editor.labels().containsAll(expected));
            assertTrue(editor.labels().contains(sourceKept("Thumbs")), "all of them on the secondary outputs node");
            return null;
        });
    }

    @Test
    public void theOutFormOfABranchWarnsWhileItDeletesTheSource() throws Exception {
        assumeTrue(TOOLKIT.get(), "no JavaFX toolkit (no display)");
        onFx(() -> {
            Editor editor = Editor.of(PipelineDocument.parse(PIPELINE));
            editor.select(EditorNode.step(StepAddress.branch(0, Section.OUT, 0)));
            assertTrue(editor.labels().contains(sourceKept("Thumbs")), editor.labels().toString());
            assertTrue(editor.button("editor.add").isDisabled(), "one out step");

            @SuppressWarnings("unchecked")
            ComboBox<String> deleteSource = editor.formNode(ComboBox.class); // the only choice of the form
            deleteSource.setValue("false");

            assertFalse(editor.labels().contains(sourceKept("Thumbs")), editor.labels().toString());
            return null;
        });
    }

    /** An unknown stored onError selects nothing, so that choosing fail (the default) writes it over the bad value. */
    @Test
    public void anUnknownOnErrorCanBeClearedByChoosingFail() throws Exception {
        assumeTrue(TOOLKIT.get(), "no JavaFX toolkit (no display)");
        onFx(() -> {
            Editor editor = Editor.of(PipelineDocument.parse("""
                    { "outStep": { "action": "file.write", "actionConfig": { "outPattern": "/nas/{name}" } },
                      "secondaryOutputs": [ { "name": "Thumbs", "onError": "bogus",
                        "outStep": { "action": "file.write", "actionConfig": { "outPattern": "/t/{name}" } } } ] }
                    """));
            editor.select(EditorNode.branch(0));
            @SuppressWarnings("unchecked")
            ComboBox<SecondaryOutputConfig.OnError> onError = editor.formNode(ComboBox.class);
            assertNull(onError.getValue(), "the bad value is not shown as fail");
            assertTrue(editor.document().branchProblems().stream()
                    .anyMatch(p -> p.kind() == PipelineDocument.BranchProblem.Kind.ON_ERROR_UNKNOWN));

            onError.setValue(SecondaryOutputConfig.OnError.FAIL);

            assertTrue(editor.document().branchProblems().stream()
                    .noneMatch(p -> p.kind() == PipelineDocument.BranchProblem.Kind.ON_ERROR_UNKNOWN));
            return null;
        });
    }

    /** The read-only form of a step whose plugin is missing still shows the problems of its secondary output. */
    @Test
    public void theReadOnlyFormOfAMissingPluginStepOfABranchShowsTheBranchProblems() throws Exception {
        assumeTrue(TOOLKIT.get(), "no JavaFX toolkit (no display)");
        onFx(() -> {
            Editor editor = Editor.of(PipelineDocument.parse("""
                    { "outStep": { "action": "file.write", "actionConfig": { "outPattern": "/nas/{name}" } },
                      "secondaryOutputs": [ { "name": "Thumbs",
                        "actionSteps": [ { "plugin": "com.missing", "action": "x" } ] } ] }
                    """));
            editor.select(EditorNode.step(StepAddress.branch(0, Section.PROCESS, 0)));
            List<String> expected = editor.document().branchProblems().stream().map(p -> "⚠ " + p.message()).toList();
            assertFalse(expected.isEmpty());
            assertTrue(editor.labels().containsAll(expected), editor.labels().toString());
            return null;
        });
    }

    /** The branch is quoted with the quotes of the language, in the step labels as in the problems. */
    @Test
    public void theBranchIsQuotedWithTheQuotesOfTheLanguage() throws Exception {
        assumeTrue(TOOLKIT.get(), "no JavaFX toolkit (no display)");
        onFx(() -> {
            Editor editor = Editor.of(PipelineDocument.parse(PIPELINE));
            String quoted = ResourcesEngine.getString("editor.secondary.quoted", "Thumbs");
            assertEquals(quoted, editor.controller().quotedBranch(0));
            assertEquals("#2", editor.controller().quotedBranch(1));
            assertEquals(quoted + " / " + ResourcesEngine.getString("editor.section.PROCESS") + " #1",
                    editor.controller().stepLabel(StepAddress.branch(0, Section.PROCESS, 0)));
            return null;
        });
    }

    @Test
    public void theStepsAndTheBranchesMoveAndGo() throws Exception {
        assumeTrue(TOOLKIT.get(), "no JavaFX toolkit (no display)");
        onFx(() -> {
            Editor editor = Editor.of(PipelineDocument.parse(PIPELINE));
            editor.select(EditorNode.step(StepAddress.branch(0, Section.PROCESS, 0)));
            assertFalse(editor.button("editor.remove").isDisabled());
            assertFalse(editor.button("editor.add").isDisabled());
            editor.button("editor.remove").fire();
            assertEquals(List.of(), editor.document().steps(0, Section.PROCESS));
            assertEquals(EditorNode.section(0, Section.PROCESS), editor.selected());

            editor.select(EditorNode.branch(1));
            editor.button("editor.up").fire();
            assertEquals("Thumbs", editor.document().branchName(1));
            assertEquals(EditorNode.branch(0), editor.selected());

            editor.button("editor.remove").fire(); // no step: removed without question
            assertEquals(1, editor.document().branchCount());
            assertEquals("Thumbs", editor.document().branchName(0));
            assertEquals(SECONDARY, editor.selected());
            return null;
        });
    }
}

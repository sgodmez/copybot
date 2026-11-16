package com.copybot.ui;

import com.copybot.plugin.api.config.ConfigField;
import com.copybot.plugin.api.config.ConfigSchema;
import com.copybot.plugin.api.config.Secret;
import com.copybot.resources.ResourcesEngine;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** A @Secret field of an action form: a secret's name written as its reference (spec run-end); skipped without display. */
public class ConfigFormSecretFxTest {

    private static final AtomicBoolean TOOLKIT = new AtomicBoolean();

    record MailConfig(@Secret String password) {
    }

    private static final ConfigField PASSWORD = ConfigSchema.of(MailConfig.class).field("password").orElseThrow();

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

    /** The value of the field in a "document", and what the form wrote. */
    private static final class Field implements ConfigForm.Access {
        String value;
        final List<String> written = new ArrayList<>();

        Field(String value) {
            this.value = value;
        }

        @Override
        public String text(ConfigField field) {
            return value;
        }

        @Override
        public void setText(ConfigField field, String text) {
            if (!text.equals(value)) {
                written.add(text);
                value = text;
            }
        }

        @Override
        public String label(ConfigField field) {
            return field.name();
        }

        @Override
        public String description(ConfigField field) {
            return "";
        }

        @Override
        public String json(ConfigField field) {
            return value;
        }

        /** The store of this "machine": never the user's real one. */
        @Override
        public List<String> secretNames() {
            return List.of("smtp");
        }
    }

    private record Form(Parent root, ComboBox<String> combo, ConfigForm.Invalid invalid) {

        /** The warning under the field, when shown. */
        Optional<String> warning() {
            Label label = (Label) ((VBox) combo.getParent()).getChildren().get(1);
            return label.isVisible() ? Optional.of(label.getText()) : Optional.empty();
        }

        Button toStore() {
            return (Button) ((VBox) combo.getParent()).getChildren().get(2);
        }
    }

    /** The form of the field in a scene, its skin built (the skin shows the value in the editor). */
    private static Form form(Field field) {
        ConfigForm.Invalid invalid = new ConfigForm.Invalid();
        Parent root = (Parent) ConfigForm.build(List.of(PASSWORD), field, null, invalid);
        new Scene(new VBox(root));
        root.applyCss();
        root.layout();
        return new Form(root, find(root), invalid);
    }

    @SuppressWarnings("unchecked")
    private static ComboBox<String> find(Node node) {
        if (node instanceof ComboBox<?> combo) {
            return (ComboBox<String>) combo;
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                ComboBox<String> found = find(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Test
    public void aSecretReferenceIsShownByItsNameAndANameTypedIsWrittenAsAReference() throws Exception {
        assumeTrue(TOOLKIT.get(), "no display");
        Field field = new Field("${secret:smtp}");

        onFx(() -> {
            Form form = form(field);
            assertTrue(form.combo().isEditable());
            assertEquals("smtp", form.combo().getEditor().getText());
            assertEquals(List.of(), field.written, "showing writes nothing");

            form.combo().getEditor().setText("api.token");
            assertEquals("${secret:api.token}", field.value);
            form.combo().getEditor().setText("${env:SMTP_PASSWORD}");
            assertEquals("${env:SMTP_PASSWORD}", field.value, "a reference typed is kept");
            assertTrue(form.invalid().isEmpty());

            form.combo().getEditor().setText("my password");
            assertEquals("${env:SMTP_PASSWORD}", field.value, "a value in clear is not written");
            assertFalse(form.invalid().isEmpty());
            return null;
        });
    }

    @Test
    public void aValueInClearOfTheFileIsShownAndKeptUntilChanged() throws Exception {
        assumeTrue(TOOLKIT.get(), "no display");
        Field field = new Field("hunter 2");

        onFx(() -> {
            Form form = form(field);
            assertEquals("hunter 2", form.combo().getEditor().getText());
            assertTrue(form.invalid().isEmpty(), "not refused while untouched");
            assertEquals(List.of(), field.written);
            assertEquals(List.of("smtp", ResourcesEngine.getString("editor.secret.new")), form.combo().getItems(),
                    "the names of the store given to the form");
            assertEquals(Optional.of("⚠ " + ResourcesEngine.getString("editor.secret.plain")), form.warning());
            assertTrue(form.toStore().isVisible(), "Put in the vault");

            form.combo().getEditor().setText("smtp");
            assertEquals("${secret:smtp}", field.value);
            assertEquals(Optional.empty(), form.warning());
            assertFalse(form.toStore().isVisible());
            return null;
        });
    }

    /** A password typed as a name (the natural mistake): written, but the field warns at once. */
    @Test
    public void aNameNotDefinedOnThisMachineIsWrittenWithAWarning() throws Exception {
        assumeTrue(TOOLKIT.get(), "no display");
        Field field = new Field("");

        onFx(() -> {
            Form form = form(field);
            assertEquals(Optional.empty(), form.warning());

            form.combo().getEditor().setText("Hunter2");
            assertEquals("${secret:Hunter2}", field.value, "a name of another machine stays accepted");
            assertEquals(Optional.of("⚠ " + ResourcesEngine.getString("editor.secret.unknown", "Hunter2",
                    ResourcesEngine.getString("editor.secret.new"))), form.warning());
            assertTrue(form.combo().getStyle().contains("border"), "the field is marked");
            assertTrue(form.invalid().isEmpty(), "not blocking");

            form.combo().getEditor().setText("smtp");
            assertEquals(Optional.empty(), form.warning());
            assertEquals("", form.combo().getStyle());
            return null;
        });
    }
}

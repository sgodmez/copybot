package com.copybot.ui.model;

import com.copybot.plugin.api.config.ConfigField;
import com.copybot.plugin.api.config.FieldHint;
import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.model.PipelineDocument.Section;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.copybot.ui.model.TestCatalog.MAIL;
import static org.junit.jupiter.api.Assertions.*;

/** A @Secret field of an action form: a secret's name, written as its reference (spec run-end, Secrets). */
public class SecretFieldTest {

    @BeforeAll
    public static void registerUiBundle() {
        ResourcesEngine.registerBundle("com.copybot.ui.i18n.uiBundle");
    }

    @Test
    public void aSecretNameIsWrittenAsItsReference() {
        assertEquals("${secret:smtp}", SecretField.value("smtp"));
        assertEquals("${secret:smtp.main-2}", SecretField.value("  smtp.main-2 "));
        assertEquals("", SecretField.value("  "), "blank: the field is removed");
    }

    @Test
    public void aTypedReferenceIsKeptAsItIs() {
        assertEquals("${env:SMTP_PASSWORD}", SecretField.value("${env:SMTP_PASSWORD}"));
        assertEquals("${secret:smtp}", SecretField.value("${secret:smtp}"));
        assertEquals("Bearer ${secret:token}-$${literal}", SecretField.value("Bearer ${secret:token}-$${literal}"),
                "around references, with an escape");
    }

    /** The engine's grammar: only real references; "$${" is an escape, not a reference. */
    @Test
    public void aTextWithADollarBraceThatIsNoReferenceIsRefused() {
        for (String text : List.of("p@ss${x", "${secret:bad name}", "${env:}", "$${env:X}", "${env:X}${y",
                "${other:x}", "pass$${")) {
            assertThrows(IllegalArgumentException.class, () -> SecretField.value(text), text);
        }
    }

    @Test
    public void aValueWithoutReferenceIsInClear() {
        assertTrue(SecretField.isInClear("hunter2"));
        assertTrue(SecretField.isInClear("a$${env:X}"), "an escape is not a reference");
        assertFalse(SecretField.isInClear("${secret:smtp}"));
        assertFalse(SecretField.isInClear("x ${env:X}"));
        assertFalse(SecretField.isInClear(""));
        assertFalse(SecretField.isInClear("  "));
    }

    @Test
    public void theSecretsAValueRefersToAreListed() {
        assertEquals(List.of("a", "b"), SecretField.secretNames("${secret:a}:${env:X}:${secret:b}:$${secret:c}"));
        assertEquals(List.of(), SecretField.secretNames("${secret:bad name}"));
    }

    @Test
    public void theProposedNameOfAFieldIsAValidName() {
        assertEquals("smtp.password", SecretField.proposedName("smtp.password"));
        assertEquals("my-password", SecretField.proposedName("my password"));
        assertEquals("secret", SecretField.proposedName(""));
    }

    /** Anything else would put a password in clear in the pipeline. */
    @Test
    public void aValueThatIsNeitherANameNorAReferenceIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> SecretField.value("my password"));
        assertThrows(IllegalArgumentException.class, () -> SecretField.value("p@ss"));
    }

    @Test
    public void aLoneSecretReferenceIsShownAsTheName() {
        assertEquals("smtp", SecretField.text("${secret:smtp}"));
        assertEquals("${env:SMTP_PASSWORD}", SecretField.text("${env:SMTP_PASSWORD}"));
        assertEquals("${secret:a}${secret:b}", SecretField.text("${secret:a}${secret:b}"));
        assertEquals("${secret:bad name}", SecretField.text("${secret:bad name}"));
        assertEquals("", SecretField.text(""));
        for (String text : new String[]{"smtp", "${env:SMTP_PASSWORD}", ""}) {
            assertEquals(text, SecretField.text(SecretField.value(text)), "round trip of " + text);
        }
    }

    @Test
    public void theDocumentHoldsTheReferenceNeverAValue() {
        PipelineDocument document = PipelineDocument.parse(
                "{ \"runEnd\": [ { \"plugin\": \"notify-email\", \"action\": \"email.send\" } ] }");
        JsonObject step = document.steps(Section.RUN_END).getFirst();
        ConfigField password = MAIL.configSchema().orElseThrow().field("password").orElseThrow();
        assertTrue(password.hasHint(FieldHint.SECRET));

        document.setConfigText(step, password, SecretField.value("smtp"));

        assertEquals("${secret:smtp}", step.getAsJsonObject("actionConfig").get("password").getAsString());
        assertEquals("smtp", SecretField.text(document.configText(step, password)));
    }
}

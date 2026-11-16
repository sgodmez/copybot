package com.copybot.config;

import com.copybot.exception.CopybotException;
import com.copybot.resources.ResourcesEngine;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

public class SecretReferencesTest {

    private static final UnaryOperator<String> ENV =
            Map.of("SMTP_USER", "me@example.org", "EMPTY", "", "SMTP_PORT", "25")::get;

    @TempDir
    Path tempDir;

    SecretStore store;

    @BeforeEach
    public void fillStore() throws Exception {
        store = SecretStore.at(tempDir.resolve("secrets.json"));
        store.set("smtp", "hunter2");
        store.set("tg", "123:ABC$1\\x");
    }

    private SecretReferences.Resolved resolve(String json) {
        return SecretReferences.resolve(JsonParser.parseString(json), () -> store, ENV);
    }

    @Test
    public void referencesAreReplacedInEveryTextValueOfObjectsAndLists() {
        JsonElement config = JsonParser.parseString("""
                { "host": "smtp.example.org", "user": "${env:SMTP_USER}", "password": "${secret:smtp}",
                  "port": 25, "tls": true, "none": null,
                  "url": "https://api.telegram.org/bot${secret:tg}/send?u=${env:SMTP_USER}&e=${env:EMPTY}",
                  "to": [ "a@b.c", "${env:SMTP_USER}", [ { "deep": "${secret:smtp}" } ] ] }
                """);
        String before = config.toString();

        SecretReferences.Resolved resolved = SecretReferences.resolve(config, () -> store, ENV);

        assertEquals(JsonParser.parseString("""
                { "host": "smtp.example.org", "user": "me@example.org", "password": "hunter2",
                  "port": 25, "tls": true, "none": null,
                  "url": "https://api.telegram.org/bot123:ABC$1\\\\x/send?u=me@example.org&e=",
                  "to": [ "a@b.c", "me@example.org", [ { "deep": "hunter2" } ] ] }
                """), resolved.config());
        assertEquals(before, config.toString(), "the pipeline's own configuration keeps its references");
    }

    @Test
    public void aConfigurationWithoutReferenceNeverOpensTheStore() {
        JsonElement config = JsonParser.parseString(
                "{ \"outPattern\": \"/nas/{date:yyyy}/{name}\", \"price\": \"$5 {x}\", \"other\": \"${unknown:x}\" }");
        Supplier<SecretStore> forbidden = () -> fail("the store is opened only for a secret reference");

        SecretReferences.Resolved resolved = SecretReferences.resolve(config, forbidden, ENV);

        assertSame(config, resolved.config());
        assertSame(JsonNull.INSTANCE, SecretReferences.resolve(JsonNull.INSTANCE, forbidden, ENV).config());
        assertNull(SecretReferences.resolve(null, forbidden, ENV).config());
        assertEquals("{\"user\":\"me@example.org\"}",
                SecretReferences.resolve(JsonParser.parseString("{\"user\":\"${env:SMTP_USER}\"}"), forbidden, ENV)
                        .config().toString(), "an environment variable needs no store");
    }

    @Test
    public void referencesResolvedToEmptyValuesAreReplacedToo() {
        SecretReferences.Resolved resolved = resolve("{ \"outPattern\": \"${env:EMPTY}{name}\" }");

        assertEquals("{\"outPattern\":\"{name}\"}", resolved.config().toString(),
                "never the literal reference: the action would write into a folder named after it");
    }

    @Test
    public void aDoubledDollarIsALiteralReference() {
        Supplier<SecretStore> forbidden = () -> fail("an escaped reference is not resolved");

        SecretReferences.Resolved resolved = SecretReferences.resolve(JsonParser.parseString(
                "{ \"args\": [ \"-Command\", \"echo $${env:PATH} $${secret:x} $$5\", \"${env:SMTP_USER}\" ] }"),
                forbidden, ENV);

        assertEquals("{\"args\":[\"-Command\",\"echo ${env:PATH} ${secret:x} $$5\",\"me@example.org\"]}",
                resolved.config().toString());
    }

    @Test
    public void anInvalidSecretNameIsRefusedByName() {
        CopybotException e = assertThrows(CopybotException.class, () -> resolve("{ \"a\": \"${secret:a b}\" }"));

        assertEquals(ResourcesEngine.getString("secret.name.invalid", "a b"), e.getMessage());
    }

    @Test
    public void aProtectionUnavailableIsRefusedWithItsOwnMessage() {
        SecretStore unprotected = SecretStoreFakes.withoutProtection(tempDir.resolve("secrets.json"));

        CopybotException e = assertThrows(CopybotException.class, () -> SecretReferences.resolve(
                JsonParser.parseString("{ \"a\": \"${secret:smtp}\" }"), () -> unprotected, ENV));

        assertEquals(SecretStoreFakes.UNAVAILABLE, e.getMessage());
    }

    @Test
    public void aMessageShowingAShortValueIsHiddenAsAWhole() throws Exception {
        store.set("pin", "42");
        SecretReferences.Resolved resolved = resolve("{ \"pin\": \"${secret:pin}\", \"p\": \"${secret:smtp}\" }");

        assertEquals(ResourcesEngine.getString("secret.redacted", "${secret:pin}"),
                resolved.text("error 4242 at line 42"), "never garbled, never telling where the value is");
        assertEquals("login ${secret:smtp} refused", resolved.text("login hunter2 refused"));
        assertEquals("nothing to hide", resolved.text("nothing to hide"));
        RuntimeException redacted = resolved.redact(new IllegalStateException("Expected an int but was 42"));
        assertEquals(ResourcesEngine.getString("secret.redacted", "${secret:pin}"), redacted.getMessage());
    }

    @Test
    public void aShortEnvironmentValueIsNoSecretAndHidesNothing() {
        SecretReferences.Resolved resolved = resolve(
                "{ \"port\": \"${env:SMTP_PORT}\", \"user\": \"${env:SMTP_USER}\", \"p\": \"${secret:smtp}\" }");
        RuntimeException error = new IllegalStateException("IMG_2025.jpg: copy failed");

        assertEquals("IMG_2025.jpg: copy failed", resolved.text("IMG_2025.jpg: copy failed"));
        assertSame(error, resolved.redact(error));
        assertEquals("IMG_2025.jpg sent by ${env:SMTP_USER} with ${secret:smtp}",
                resolved.text("IMG_2025.jpg sent by me@example.org with hunter2"), "the long ones are still hidden");
    }

    @Test
    public void aValueInASuppressedErrorIsHiddenToo() {
        SecretReferences.Resolved resolved = resolve("{ \"p\": \"${secret:smtp}\" }");
        RuntimeException error = new IllegalStateException("write failed");
        error.addSuppressed(new IllegalStateException("cleanup: hunter2"));

        RuntimeException redacted = resolved.redact(error);

        assertNotSame(error, redacted);
        assertEquals("write failed", redacted.getMessage());
        assertEquals(0, redacted.getSuppressed().length);
        assertSame(SecretReferences.Redactor.NONE.redact(error), error);
    }

    @Test
    public void aMissingSecretIsRefusedByName() {
        CopybotException e = assertThrows(CopybotException.class,
                () -> resolve("{ \"a\": \"${secret:smtp}\", \"b\": [\"${secret:nope}\"] }"));

        assertEquals(ResourcesEngine.getString("secret.missing", "nope"), e.getMessage());
        assertFalse(e.getMessage().contains("hunter2"));
    }

    @Test
    public void aMissingEnvironmentVariableIsRefusedByName() {
        CopybotException e = assertThrows(CopybotException.class, () -> resolve("{ \"a\": \"${env:NOPE}\" }"));

        assertEquals(ResourcesEngine.getString("secret.env.missing", "NOPE"), e.getMessage());
    }

    @Test
    public void anUndecryptableSecretHasItsOwnMessage() throws Exception {
        Files.writeString(store.file(), "{ \"smtp\": \"%%%\" }");

        CopybotException e = assertThrows(CopybotException.class, () -> resolve("{ \"a\": \"${secret:smtp}\" }"));

        assertEquals(ResourcesEngine.getString("secret.unreadable", "smtp"), e.getMessage());
        assertNotEquals(ResourcesEngine.getString("secret.missing", "smtp"), e.getMessage());
    }

    @Test
    public void anUnreadableStoreIsRefusedWithItsPath() throws Exception {
        Files.writeString(store.file(), "not json {");

        CopybotException e = assertThrows(CopybotException.class, () -> resolve("{ \"a\": \"${secret:smtp}\" }"));

        assertTrue(e.getMessage().contains(store.file().toString()), e.getMessage());
    }

    @Test
    public void theResolvedConfigurationNeverShowsInItsText() {
        SecretReferences.Resolved resolved = resolve("{ \"password\": \"${secret:smtp}\", \"u\": \"${env:SMTP_USER}\" }");

        assertFalse(resolved.toString().contains("hunter2"), resolved.toString());
        assertFalse(resolved.toString().contains("me@example.org"), resolved.toString());
    }

    @Test
    public void anErrorShowingAResolvedValueShowsTheReferenceInstead() {
        SecretReferences.Resolved resolved = resolve("{ \"port\": \"${secret:smtp}\", \"u\": \"${env:SMTP_USER}\" }");
        RuntimeException leaking = new IllegalStateException("Expected an int but was hunter2 (me@example.org)",
                new NumberFormatException("For input string: \"hunter2\""));

        RuntimeException redacted = resolved.redact(leaking);

        assertEquals("Expected an int but was ${secret:smtp} (${env:SMTP_USER})", redacted.getMessage());
        assertNull(redacted.getCause(), "the cause shows the value too");
        assertFalse(redacted instanceof CopybotException, "an error of the configuration stays one");
        assertArrayEquals(leaking.getStackTrace(), redacted.getStackTrace());

        RuntimeException onlyInCause = new IllegalStateException("bad", new RuntimeException("hunter2"));
        RuntimeException redactedCause = resolved.redact(onlyInCause);
        assertEquals("bad", redactedCause.getMessage());
        assertNull(redactedCause.getCause());

        CopybotException told = CopybotException.of("refused: hunter2");
        assertInstanceOf(CopybotException.class, resolved.redact(told), "a refusal stays a refusal");
        assertEquals("refused: ${secret:smtp}", resolved.redact(told).getMessage());

        RuntimeException harmless = new IllegalStateException("bad port", new RuntimeException("cause"));
        assertSame(harmless, resolved.redact(harmless), "nothing to hide: the error as it is, with its cause");
    }
}

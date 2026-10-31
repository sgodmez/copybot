package com.copybot.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class SecretStoreTest {

    @TempDir
    Path tempDir;

    private SecretStore store() {
        return SecretStore.at(tempDir.resolve("profile").resolve("secrets.json"));
    }

    @Test
    public void onWindowsTheStoreIsInAppData() {
        assertEquals(Path.of("C:/Users/me/AppData/Roaming/Copybot/secrets.json").toAbsolutePath(),
                SecretStore.defaultFile(true, "C:/Users/me/AppData/Roaming", null, "C:/Users/me"));
    }

    @Test
    public void onWindowsWithoutAppDataTheStoreIsInTheRoamingProfile() {
        assertEquals(Path.of("C:/Users/me/AppData/Roaming/Copybot/secrets.json").toAbsolutePath(),
                SecretStore.defaultFile(true, " ", null, "C:/Users/me"));
    }

    @Test
    public void elsewhereTheStoreIsInTheXdgConfigFolder() {
        Path xdg = tempDir.resolve("xdg").toAbsolutePath();
        assertEquals(xdg.resolve("copybot").resolve("secrets.json"),
                SecretStore.defaultFile(false, "ignored", xdg.toString(), "/home/me"));
    }

    @Test
    public void elsewhereWithoutXdgTheStoreIsInDotConfig() {
        Path home = tempDir.resolve("home").toAbsolutePath();
        Path expected = home.resolve(".config").resolve("copybot").resolve("secrets.json");
        assertEquals(expected, SecretStore.defaultFile(false, null, null, home.toString()));
        assertEquals(expected, SecretStore.defaultFile(false, null, "", home.toString()));
        assertEquals(expected, SecretStore.defaultFile(false, null, "relative/xdg", home.toString()),
                "a relative XDG_CONFIG_HOME is invalid and ignored (XDG base directory specification)");
    }

    @Test
    public void aSecretIsSetReadListedAndDeleted() throws Exception {
        SecretStore store = store();
        assertEquals(List.of(), store.names(), "no file: no secret");

        store.set("smtp", "p\u00e4ss w\u00f6rd \u20ac");
        store.set("a-token", "t0k3n");

        assertEquals(Optional.of("p\u00e4ss w\u00f6rd \u20ac"), store.get("smtp"));
        assertEquals(List.of("a-token", "smtp"), store.names(), "sorted names");
        assertEquals(Optional.of("t0k3n"), SecretStore.at(store.file()).get("a-token"), "persisted");

        store.set("smtp", "changed");
        assertEquals(Optional.of("changed"), store.get("smtp"));

        assertTrue(store.delete("smtp"));
        assertFalse(store.delete("smtp"), "already deleted");
        assertEquals(Optional.empty(), store.get("smtp"));
        assertEquals(List.of("a-token"), store.names());
    }

    @Test
    public void anUnknownSecretIsEmpty() throws Exception {
        SecretStore store = store();
        store.set("known", "x");

        assertEquals(Optional.empty(), store.get("unknown"));
    }

    @Test
    public void theFileHoldsOneProtectedValuePerName() throws Exception {
        SecretStore store = store();
        store.set("smtp", "hunter2");

        String content = Files.readString(store.file());
        assertTrue(content.contains("\"smtp\""), content);
        assertFalse(content.contains("hunter2"), "never the clear value");
        assertEquals(List.of(), leftovers(), "no temporary file left");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    public void onWindowsTheStoredValueIsEncryptedForTheUser() throws Exception {
        SecretStore store = store();
        store.set("smtp", "hunter2");

        String content = Files.readString(store.file());
        String plainBase64 = Base64.getEncoder().encodeToString("hunter2".getBytes(StandardCharsets.UTF_8));
        assertFalse(content.contains(plainBase64), "DPAPI, not a mere base64 of the value: " + content);
        assertEquals(Optional.of("hunter2"), store.get("smtp"));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void elsewhereTheFileIsReadableByItsOwnerOnly() throws Exception {
        SecretStore store = store();
        store.set("smtp", "hunter2");

        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(store.file()));
        store.set("other", "x"); // replaced: still owner-only
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(store.file()));
    }

    @Test
    public void aValueThatCannotBeDecryptedIsUnreadableNotAbsent() throws Exception {
        SecretStore store = store();
        Files.createDirectories(store.file().getParent());
        // not base64 at all, and base64 of bytes that are no DPAPI blob of this user
        Files.writeString(store.file(), "{ \"broken\": \"%%%\", \"foreign\": \"AAECAwQFBgcICQ==\", \"number\": 3 }");

        assertThrows(SecretStore.UnreadableSecretException.class, () -> store.get("broken"));
        assertThrows(SecretStore.UnreadableSecretException.class, () -> store.get("number"));
        if (System.getProperty("os.name").startsWith("Windows")) {
            SecretStore.UnreadableSecretException e =
                    assertThrows(SecretStore.UnreadableSecretException.class, () -> store.get("foreign"));
            assertEquals("foreign", e.getMessage(), "the name only");
        }
        assertEquals(List.of("broken", "foreign", "number"), store.names());
    }

    @Test
    public void aMalformedStoreIsAnErrorAndIsNotOverwritten() throws Exception {
        SecretStore store = store();
        Files.createDirectories(store.file().getParent());
        Files.writeString(store.file(), "[ \"not an object\" ]");

        assertThrows(IOException.class, store::names);
        assertThrows(IOException.class, () -> store.get("x"));
        assertThrows(IOException.class, () -> store.set("x", "y"));
        assertEquals("[ \"not an object\" ]", Files.readString(store.file()), "left as it was");
    }

    @Test
    public void aNameIsMadeOfLettersDigitsDotsDashesAndUnderscores() throws Exception {
        assertTrue(SecretStore.isValidName("smtp.Main_2-b"));
        for (String name : new String[]{"", "with space", "a}b", "a:b", "\u00e9", "a/b"}) {
            assertFalse(SecretStore.isValidName(name), name);
            assertThrows(IllegalArgumentException.class, () -> store().set(name, "x"), name);
        }
    }

    @Test
    public void aFileHasOneStoreInTheJvm() {
        Path file = tempDir.resolve("profile").resolve("secrets.json");

        assertSame(SecretStore.at(file), SecretStore.at(tempDir.resolve("profile").resolve("..").resolve("profile")
                .resolve("secrets.json")), "the dialog and the resolutions are serialised on the same instance");
        assertSame(SecretStore.forCurrentUser(), SecretStore.forCurrentUser());
    }

    @Test
    public void aProtectionThatFailsIsAnErrorOfItsOwnAndSavesNothing() throws Exception {
        Path file = tempDir.resolve("secrets.json");
        Files.writeString(file, "{ \"smtp\": \"AAAA\" }");
        SecretStore store = SecretStoreFakes.withoutProtection(file);

        assertEquals(SecretStoreFakes.UNAVAILABLE,
                assertThrows(SecretStore.ProtectionException.class, () -> store.get("smtp")).getMessage());
        assertThrows(SecretStore.ProtectionException.class, () -> store.set("other", "x"));
        assertEquals("{ \"smtp\": \"AAAA\" }", Files.readString(file));
    }

    private List<Path> leftovers() throws IOException {
        try (var files = Files.list(store().file().getParent())) {
            return files.filter(p -> !p.getFileName().toString().equals("secrets.json")).toList();
        }
    }
}

package com.copybot.ui.model;

import com.copybot.resources.ResourcesEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/** The Secrets dialog's model: names only, a value is written and never read back (spec run-end, Secrets). */
public class SecretsModelTest {

    @BeforeAll
    public static void registerUiBundle() {
        ResourcesEngine.registerBundle("com.copybot.ui.i18n.uiBundle");
    }

    /** A store in memory that records what it was asked. */
    private static final class MemoryStore implements SecretsModel.Store {
        final TreeMap<String, String> values = new TreeMap<>();
        final List<String> calls = new ArrayList<>();

        @Override
        public List<String> names() {
            return List.copyOf(values.keySet());
        }

        @Override
        public void set(String name, String value) {
            calls.add("set " + name);
            values.put(name, value);
        }

        @Override
        public boolean delete(String name) {
            calls.add("delete " + name);
            return values.remove(name) != null;
        }
    }

    @Test
    public void aSecretIsSavedUnderItsTrimmedNameAndListedByName() throws IOException {
        MemoryStore store = new MemoryStore();
        SecretsModel model = new SecretsModel(store);

        model.save(" smtp ", " p4ss word ");
        model.save("api.token", "x");

        assertEquals(List.of("api.token", "smtp"), model.names());
        assertEquals(" p4ss word ", store.values.get("smtp"), "the value as typed");
        assertTrue(model.exists("smtp"));
        assertFalse(model.exists("other"));
    }

    @Test
    public void anInvalidNameOrAnEmptyValueIsRefusedWithAMessage() throws IOException {
        MemoryStore store = new MemoryStore();
        SecretsModel model = new SecretsModel(store);

        assertEquals(Optional.of(ResourcesEngine.getString("secrets.invalid-name")), model.refusal("my secret", "v"));
        assertEquals(Optional.of(ResourcesEngine.getString("secrets.invalid-name")), model.refusal(" ", "v"));
        assertEquals(Optional.of(ResourcesEngine.getString("secrets.empty-value")), model.refusal("smtp", ""));
        assertEquals(Optional.empty(), model.refusal("smtp", "v"));

        assertThrows(IllegalArgumentException.class, () -> model.save("my secret", "v"));
        assertThrows(IllegalArgumentException.class, () -> model.save("smtp", ""));
        assertEquals(List.of(), store.calls, "nothing written");
    }

    /** "Put in the vault": a value found in clear in a pipeline is saved, the pipeline then holds its reference. */
    @Test
    public void aValueInClearIsPutInTheStoreAndReplacedByItsReference() throws IOException {
        MemoryStore store = new MemoryStore();
        SecretsModel model = new SecretsModel(store);

        assertEquals("${secret:smtp.password}", model.putInStore(" smtp.password ", "hunter2"));
        assertEquals("hunter2", store.values.get("smtp.password"));
        assertThrows(IllegalArgumentException.class, () -> model.putInStore("bad name", "hunter2"));
        assertEquals(List.of("set smtp.password"), store.calls);
    }

    @Test
    public void aSecretIsDeleted() throws IOException {
        MemoryStore store = new MemoryStore();
        SecretsModel model = new SecretsModel(store);
        model.save("smtp", "v");

        assertTrue(model.delete("smtp"));
        assertFalse(model.delete("smtp"));
        assertEquals(List.of(), model.names());
    }
}

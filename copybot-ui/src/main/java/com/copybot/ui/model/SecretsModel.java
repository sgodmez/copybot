package com.copybot.ui.model;

import com.copybot.config.SecretStore;
import com.copybot.resources.ResourcesEngine;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * What the Secrets dialog does (spec run-end, Secrets): it lists the names, saves a value typed (masked) and deletes;
 * a value is never read back nor shown.
 */
public final class SecretsModel {

    /** The secrets of this user ({@link SecretStore}), or a fake in the tests. */
    public interface Store {
        /** The names, sorted. */
        List<String> names() throws IOException;

        void set(String name, String value) throws IOException;

        boolean delete(String name) throws IOException;

        /** The store of the current user. */
        static Store ofCurrentUser() {
            SecretStore store = SecretStore.forCurrentUser();
            return new Store() {
                @Override
                public List<String> names() throws IOException {
                    return store.names();
                }

                @Override
                public void set(String name, String value) throws IOException {
                    store.set(name, value);
                }

                @Override
                public boolean delete(String name) throws IOException {
                    return store.delete(name);
                }
            };
        }
    }

    private final Store store;

    public SecretsModel(Store store) {
        this.store = store;
    }

    /** The names of the secrets, sorted. */
    public List<String> names() throws IOException {
        return store.names();
    }

    public boolean exists(String name) throws IOException {
        return store.names().contains(name.strip());
    }

    /**
     * Why this name and value cannot be saved, localised; empty when they can. The name is taken without its
     * surrounding spaces; the value is taken as typed, but cannot be empty.
     */
    public Optional<String> refusal(String name, String value) {
        Optional<String> nameRefusal = nameRefusal(name);
        if (nameRefusal.isPresent()) {
            return nameRefusal;
        }
        if (value == null || value.isEmpty()) {
            return Optional.of(ResourcesEngine.getString("secrets.empty-value"));
        }
        return Optional.empty();
    }

    /** Why this name cannot be a secret's, localised; empty when it can. */
    public Optional<String> nameRefusal(String name) {
        return SecretStore.isValidName(name == null ? null : name.strip())
                ? Optional.empty() : Optional.of(ResourcesEngine.getString("secrets.invalid-name"));
    }

    /**
     * Saves the secret, replacing its value if any.
     *
     * @throws IllegalArgumentException a {@link #refusal}
     * @throws IOException              the store cannot be read or written (a localised message for the protection)
     */
    public void save(String name, String value) throws IOException {
        Optional<String> refusal = refusal(name, value);
        if (refusal.isPresent()) {
            throw new IllegalArgumentException(refusal.get());
        }
        store.set(name.strip(), value);
    }

    /**
     * Moves a value found in clear in a pipeline to the store ("Put in the vault"): saved under this name, replacing
     * any value of it.
     *
     * @return the reference the pipeline holds instead
     * @throws IllegalArgumentException a {@link #refusal}
     */
    public String putInStore(String name, String clearValue) throws IOException {
        save(name, clearValue);
        return SecretField.reference(name.strip());
    }

    /** False when there was no such secret. */
    public boolean delete(String name) throws IOException {
        return store.delete(name);
    }
}

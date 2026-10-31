package com.copybot.config;

import com.copybot.exception.CopybotException;
import com.copybot.resources.ResourcesEngine;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces the references to secrets and environment variables in every text value of an action configuration,
 * anywhere in its objects and lists, just before the action loads it (spec run-end, Secrets). The configuration of
 * the pipeline keeps its references: only the copy given to the action holds the values. A reference that cannot be
 * resolved refuses the step, by its name; no value ever appears in a message.
 * <p>
 * Syntax, anywhere in a text value, several per value:
 * <ul>
 *   <li>{@code ${secret:name}}: the secret of the {@link SecretStore}; {@code name} is made of letters, digits,
 *       '.', '_' and '-' ({@link SecretStore#isValidName}), any other name is refused;</li>
 *   <li>{@code ${env:NAME}}: the environment variable (an empty value is a value); an empty name is a variable
 *       that is not defined;</li>
 *   <li><code>$${</code> is a literal <code>${</code>, never resolved: {@code $${env:PATH}} gives {@code ${env:PATH}} (e.g.
 *       PowerShell syntax in the arguments of a program);</li>
 *   <li>references are not nested, and a resolved value is never resolved again; any other {@code ${...}} (or
 *       {@code {name}} of a pattern) is left as it is.</li>
 * </ul>
 */
public final class SecretReferences {

    private static final Pattern REFERENCE = Pattern.compile("\\$\\$\\{|\\$\\{(secret|env):([^}]*)}");
    private static final String ESCAPE = "$${";
    /**
     * A secret shorter than this is never replaced inside a message: the whole message is hidden. An environment value
     * shorter than this is not hidden at all.
     */
    static final int SHORT_VALUE = 4;

    private SecretReferences() {
    }

    /**
     * Hides the resolved values in what the action of a step says: its errors, its warnings. The values are shown
     * as their references; a message showing a value too short to be replaced reliably is replaced as a whole.
     */
    public interface Redactor {

        /** Nothing to hide: a configuration without reference. */
        Redactor NONE = new Redactor() {
            @Override
            public String text(String text) {
                return text;
            }

            @Override
            public Throwable redact(Throwable error) {
                return error;
            }
        };

        /** The text without the values. */
        String text(String text);

        /**
         * The error itself unless a value shows in its message, a cause's or a suppressed one's; then an error of the
         * same kind (a {@link CopybotException} stays one, another {@link RuntimeException} becomes an
         * {@link IllegalArgumentException}, anything else an {@link IllegalStateException}) whose message is
         * {@link #text}, without cause, with the original stack trace.
         */
        Throwable redact(Throwable error);

        /** {@link #redact(Throwable)} of a runtime exception. */
        default RuntimeException redact(RuntimeException error) {
            Throwable redacted = redact((Throwable) error);
            return redacted instanceof RuntimeException runtime ? runtime : error;
        }
    }

    /** The configuration with its references resolved, the store of the current user opened only if needed. */
    public static Resolved resolve(JsonElement config, Supplier<SecretStore> store) {
        return resolve(config, store, System::getenv);
    }

    /**
     * @throws CopybotException a secret not defined ({@code secret.missing}), defined but undecryptable
     *                          ({@code secret.unreadable}), an invalid secret name ({@code secret.name.invalid}), a
     *                          store that cannot be read ({@code secret.store.failed}, or the localised message of a
     *                          {@link SecretStore.ProtectionException}), an environment variable not defined
     *                          ({@code secret.env.missing})
     */
    static Resolved resolve(JsonElement config, Supplier<SecretStore> store, UnaryOperator<String> env) {
        if (config == null || !config.toString().contains("${")) {
            return new Resolved(config, Map.of());
        }
        Resolver resolver = new Resolver(store, env);
        JsonElement resolved = resolver.element(config.deepCopy());
        return resolver.replaced ? new Resolved(resolved, resolver.values) : new Resolved(config, Map.of());
    }

    private static final class Resolver {
        private final Supplier<SecretStore> storeSupplier;
        private final UnaryOperator<String> env;
        private SecretStore store;
        /** True once a reference or an escape was replaced, whatever its value (an empty one too). */
        private boolean replaced;
        /** Each non-empty value put in the configuration, with the reference it replaced. */
        private final Map<String, String> values = new LinkedHashMap<>();

        Resolver(Supplier<SecretStore> store, UnaryOperator<String> env) {
            this.storeSupplier = store;
            this.env = env;
        }

        JsonElement element(JsonElement element) {
            if (element.isJsonObject()) {
                JsonObject object = element.getAsJsonObject();
                for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                    entry.setValue(element(entry.getValue()));
                }
            } else if (element.isJsonArray()) {
                JsonArray array = element.getAsJsonArray();
                for (int i = 0; i < array.size(); i++) {
                    array.set(i, element(array.get(i)));
                }
            } else if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                String text = element.getAsString();
                String result = text(text);
                return result.equals(text) ? element : new JsonPrimitive(result);
            }
            return element;
        }

        private String text(String text) {
            Matcher matcher = REFERENCE.matcher(text);
            StringBuilder result = new StringBuilder();
            while (matcher.find()) {
                replaced = true;
                String value;
                if (matcher.group().equals(ESCAPE)) {
                    value = "${";
                } else {
                    value = value(matcher.group(1), matcher.group(2));
                    if (!value.isEmpty() && (matcher.group(1).equals("secret") || !values.containsKey(value))) {
                        values.put(value, matcher.group()); // a value that is also a secret is hidden as one
                    }
                }
                matcher.appendReplacement(result, Matcher.quoteReplacement(value));
            }
            matcher.appendTail(result);
            return result.toString();
        }

        private String value(String kind, String name) {
            if (kind.equals("env")) {
                String value = env.apply(name);
                if (value == null) {
                    throw CopybotException.ofResource("secret.env.missing", name);
                }
                return value;
            }
            if (!SecretStore.isValidName(name)) {
                throw CopybotException.ofResource("secret.name.invalid", name);
            }
            if (store == null) {
                store = storeSupplier.get();
            }
            try {
                Optional<String> value = store.get(name);
                return value.orElseThrow(() -> CopybotException.ofResource("secret.missing", name));
            } catch (SecretStore.UnreadableSecretException e) {
                throw CopybotException.ofResource("secret.unreadable", name);
            } catch (SecretStore.ProtectionException e) {
                throw CopybotException.of(e, e.getMessage()); // localised already
            } catch (IOException e) {
                throw CopybotException.ofResource(e, "secret.store.failed", store.file(), e.getMessage());
            }
        }
    }

    /** A configuration with its references resolved, and the {@link Redactor} of its values. Its text shows none. */
    public static final class Resolved implements Redactor {
        private final JsonElement config;
        /** Value to the reference it replaced, the longest values first. */
        private final Map<String, String> values;

        private Resolved(JsonElement config, Map<String, String> values) {
            this.config = config;
            Map<String, String> longestFirst = new LinkedHashMap<>();
            values.entrySet().stream()
                    // a short environment value (a port, a flag) is no secret: hiding every message holding "25"
                    // would hide the ordinary ones ("IMG_2025.jpg"); a short secret hides the whole message
                    .filter(entry -> isSecret(entry.getValue()) || entry.getKey().length() >= SHORT_VALUE)
                    .sorted(Map.Entry.<String, String>comparingByKey(Comparator.comparingInt(String::length)).reversed())
                    .forEach(entry -> longestFirst.put(entry.getKey(), entry.getValue()));
            this.values = longestFirst;
        }

        private static boolean isSecret(String reference) {
            return reference.startsWith("${secret:");
        }

        /** The configuration to load: the very same element when nothing was replaced. */
        public JsonElement config() {
            return config;
        }

        @Override
        public String text(String text) {
            if (text == null || values.isEmpty()) {
                return text;
            }
            Set<String> shown = new LinkedHashSet<>();
            boolean tooShort = false;
            for (Map.Entry<String, String> entry : values.entrySet()) {
                if (text.contains(entry.getKey())) {
                    shown.add(entry.getValue());
                    tooShort |= entry.getKey().length() < SHORT_VALUE;
                }
            }
            if (shown.isEmpty()) {
                return text;
            }
            if (tooShort) { // replacing a few characters would garble the message and tell where the value is
                return ResourcesEngine.getString("secret.redacted", String.join(", ", shown));
            }
            String result = text;
            for (Map.Entry<String, String> entry : values.entrySet()) {
                result = result.replace(entry.getKey(), entry.getValue());
            }
            return result;
        }

        @Override
        public Throwable redact(Throwable error) {
            if (error == null || !shows(error, Collections.newSetFromMap(new IdentityHashMap<>()))) {
                return error;
            }
            String message = text(error.getMessage() != null ? error.getMessage() : error.getClass().getName());
            Throwable redacted;
            if (error instanceof CopybotException) {
                redacted = CopybotException.of(message);
            } else if (error instanceof RuntimeException) {
                redacted = new IllegalArgumentException(message);
            } else {
                redacted = new IllegalStateException(message);
            }
            redacted.setStackTrace(error.getStackTrace());
            return redacted;
        }

        private boolean shows(Throwable error, Set<Throwable> seen) {
            if (error == null || !seen.add(error)) {
                return false;
            }
            String text = error.getMessage();
            if (text != null && values.keySet().stream().anyMatch(text::contains)) {
                return true;
            }
            for (Throwable suppressed : error.getSuppressed()) {
                if (shows(suppressed, seen)) {
                    return true;
                }
            }
            return shows(error.getCause(), seen);
        }

        @Override
        public String toString() {
            return "Resolved[" + values.size() + " references]";
        }
    }
}

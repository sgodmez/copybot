package com.copybot.ui.model;

import com.copybot.config.SecretStore;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The text of a {@code @Secret} field of an action form (spec run-end, Secrets): the name of a secret, which the
 * pipeline holds as its reference {@code ${secret:name}}, or a text holding references typed as it is
 * ({@code ${env:NAME}}). A value typed in clear is refused: the pipeline never holds a secret.
 */
public final class SecretField {

    /** The grammar of the engine's {@code SecretReferences}: "$${" is an escape (a literal "${"), not a reference. */
    private static final Pattern TOKEN = Pattern.compile("\\$\\$\\{|\\$\\{(secret|env):([^}]*)}");
    private static final String ESCAPE = "$${";
    private static final String SECRET_PREFIX = "${secret:";
    private static final String REFERENCE_START = "${";

    private SecretField() {
    }

    /** The reference to the secret of this name. */
    public static String reference(String name) {
        return SECRET_PREFIX + name + "}";
    }

    /** The text shown for the value of the pipeline: the name for a lone reference to a secret, the value otherwise. */
    public static String text(String value) {
        if (value.startsWith(SECRET_PREFIX) && value.endsWith("}")) {
            String name = value.substring(SECRET_PREFIX.length(), value.length() - 1);
            if (SecretStore.isValidName(name)) {
                return name;
            }
        }
        return value;
    }

    /**
     * The value written for a text: "" (the field removed) for a blank one, the reference of a secret's name, a text
     * holding references ({@link #holdsReferences}) as it is.
     *
     * @throws IllegalArgumentException neither a secret's name nor a text of references
     */
    public static String value(String text) {
        String stripped = text == null ? "" : text.strip();
        if (stripped.isEmpty() || holdsReferences(stripped)) {
            return stripped;
        }
        if (!stripped.contains(REFERENCE_START) && SecretStore.isValidName(stripped)) {
            return reference(stripped);
        }
        throw new IllegalArgumentException("not a secret name");
    }

    /**
     * The text holds at least one reference the engine resolves ({@code ${secret:name}} with a valid name,
     * {@code ${env:NAME}} with a name), and every other "${" in it is an escaped one ("$${").
     */
    public static boolean holdsReferences(String text) {
        Matcher matcher = TOKEN.matcher(text);
        StringBuilder rest = new StringBuilder();
        int references = 0;
        while (matcher.find()) {
            if (!matcher.group().equals(ESCAPE)) {
                String name = matcher.group(2);
                boolean valid = matcher.group(1).equals("secret") ? SecretStore.isValidName(name) : !name.isEmpty();
                if (!valid) {
                    return false;
                }
                references++;
            }
            matcher.appendReplacement(rest, "");
        }
        matcher.appendTail(rest);
        return references > 0 && !rest.toString().contains(REFERENCE_START);
    }

    /** The names of the secrets the value refers to ({@code ${secret:name}}, valid names only), in order. */
    public static List<String> secretNames(String value) {
        List<String> names = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(value);
        while (matcher.find()) {
            if ("secret".equals(matcher.group(1)) && SecretStore.isValidName(matcher.group(2))) {
                names.add(matcher.group(2));
            }
        }
        return names;
    }

    /** A value of the pipeline in clear: not blank and without any reference (a secret saved in the pipeline). */
    public static boolean isInClear(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        Matcher matcher = TOKEN.matcher(value);
        while (matcher.find()) {
            if (!matcher.group().equals(ESCAPE)) {
                return false;
            }
        }
        return true;
    }

    /** A secret name proposed for a field (its path), the characters a name cannot hold replaced by '-'. */
    public static String proposedName(String fieldPath) {
        String name = fieldPath == null ? "" : fieldPath.strip().replaceAll("[^A-Za-z0-9._-]", "-");
        return name.isEmpty() ? "secret" : name;
    }
}

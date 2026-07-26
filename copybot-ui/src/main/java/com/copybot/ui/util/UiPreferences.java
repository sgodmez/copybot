package com.copybot.ui.util;

import java.util.Locale;
import java.util.Optional;
import java.util.prefs.Preferences;

/** User preferences persisted between launches (backed by the OS user store, e.g. the registry on Windows). */
public final class UiPreferences {

    private static final Preferences PREFS = Preferences.userNodeForPackage(UiPreferences.class);
    private static final String LANGUAGE_KEY = "language";

    private UiPreferences() {
    }

    public static Optional<Locale> savedLanguage() {
        String tag = PREFS.get(LANGUAGE_KEY, null);
        return tag == null ? Optional.empty() : Optional.of(Locale.forLanguageTag(tag));
    }

    public static void saveLanguage(Locale locale) {
        PREFS.put(LANGUAGE_KEY, locale.toLanguageTag());
    }
}

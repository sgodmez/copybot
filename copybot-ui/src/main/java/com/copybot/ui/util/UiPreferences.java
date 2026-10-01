package com.copybot.ui.util;

import com.copybot.ui.model.RecentPipelines;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.prefs.Preferences;

/** User preferences persisted between launches (backed by the OS user store, e.g. the registry on Windows). */
public final class UiPreferences {

    private static final String LANGUAGE_KEY = "language";
    static final String RECENTS_KEY = "recentPipelines";

    private UiPreferences() {
    }

    /** The real user node, resolved on first use only (tests inject their own node and never reach it). */
    private static final class Real {
        private static final Preferences NODE = Preferences.userNodeForPackage(UiPreferences.class);
    }

    public static Optional<Locale> savedLanguage() {
        String tag = Real.NODE.get(LANGUAGE_KEY, null);
        return tag == null ? Optional.empty() : Optional.of(Locale.forLanguageTag(tag));
    }

    public static void saveLanguage(Locale locale) {
        Real.NODE.put(LANGUAGE_KEY, locale.toLanguageTag());
    }

    /** The recent pipelines and their last run (spec desktop-ui section 6); empty when none or unreadable. */
    public static RecentPipelines recents() {
        return recents(Real.NODE);
    }

    /**
     * Reads the recent pipelines, applies the change and saves them (the oldest dropped if too long).
     *
     * @return what was persisted (without the entries dropped to fit)
     */
    public static RecentPipelines updateRecents(Consumer<RecentPipelines> change) {
        return updateRecents(Real.NODE, change);
    }

    static RecentPipelines recents(Preferences node) {
        return RecentPipelines.fromJson(node.get(RECENTS_KEY, null));
    }

    static RecentPipelines updateRecents(Preferences node, Consumer<RecentPipelines> change) {
        RecentPipelines recents = recents(node);
        change.accept(recents);
        String json = recents.toJsonWithin(Preferences.MAX_VALUE_LENGTH);
        node.put(RECENTS_KEY, json);
        return RecentPipelines.fromJson(json);
    }
}

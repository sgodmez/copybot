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

    /** Where the plan view shows the resources while a plan runs. */
    public enum ResourcesLayout {
        /** Compact gauges on the line of the progress bar (the default). */
        PROGRESS_LINE,
        /** A panel on the right of the table, always there so that nothing moves. */
        SIDE_PANEL
    }

    private static final String RESOURCES_LAYOUT_KEY = "resourcesLayout";
    /** Read every second by the plan view: kept here rather than read from the OS store each time. */
    private static volatile ResourcesLayout resourcesLayout;

    public static ResourcesLayout resourcesLayout() {
        ResourcesLayout layout = resourcesLayout;
        if (layout == null) {
            layout = resourcesLayout(Real.NODE);
            resourcesLayout = layout;
        }
        return layout;
    }

    public static void saveResourcesLayout(ResourcesLayout layout) {
        Real.NODE.put(RESOURCES_LAYOUT_KEY, layout.name());
        resourcesLayout = layout;
    }

    static ResourcesLayout resourcesLayout(Preferences node) {
        try {
            return ResourcesLayout.valueOf(node.get(RESOURCES_LAYOUT_KEY, ResourcesLayout.PROGRESS_LINE.name()));
        } catch (IllegalArgumentException e) {
            return ResourcesLayout.PROGRESS_LINE; // a value of another version
        }
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

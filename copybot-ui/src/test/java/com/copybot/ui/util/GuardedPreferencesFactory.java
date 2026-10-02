package com.copybot.ui.util;

import java.util.prefs.Preferences;
import java.util.prefs.PreferencesFactory;

/**
 * Installed by surefire (system property java.util.prefs.PreferencesFactory) so that no test can ever write
 * the user's real preferences: asking for a root fails the test that asks, whatever the test order. Tests use
 * their own {@link MemoryPreferences} node instead.
 */
public final class GuardedPreferencesFactory implements PreferencesFactory {

    @Override
    public Preferences userRoot() {
        // an Error, so that no catch (Exception) on the way can swallow it
        throw new AssertionError("a test reached the real user preferences: inject a MemoryPreferences node");
    }

    @Override
    public Preferences systemRoot() {
        throw new AssertionError("a test reached the real system preferences: inject a MemoryPreferences node");
    }
}

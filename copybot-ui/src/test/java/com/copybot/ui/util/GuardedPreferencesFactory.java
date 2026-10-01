package com.copybot.ui.util;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import java.util.prefs.PreferencesFactory;

/**
 * Installed by surefire (system property java.util.prefs.PreferencesFactory) so that no test can ever write
 * the user's real preferences: the roots are in-memory, and every request for the user root is counted.
 */
public final class GuardedPreferencesFactory implements PreferencesFactory {

    private static final AtomicInteger USER_ROOT_REQUESTS = new AtomicInteger();
    private static final Preferences USER = new Memory();
    private static final Preferences SYSTEM = new Memory();

    public static int userRootRequests() {
        return USER_ROOT_REQUESTS.get();
    }

    @Override
    public Preferences userRoot() {
        USER_ROOT_REQUESTS.incrementAndGet();
        return USER;
    }

    @Override
    public Preferences systemRoot() {
        return SYSTEM;
    }

    private static final class Memory extends AbstractPreferences {
        private final java.util.Map<String, String> values = new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.Map<String, Memory> children = new java.util.concurrent.ConcurrentHashMap<>();

        Memory() {
            super(null, "");
        }

        private Memory(Memory parent, String name) {
            super(parent, name);
        }

        @Override
        protected void putSpi(String key, String value) {
            values.put(key, value);
        }

        @Override
        protected String getSpi(String key) {
            return values.get(key);
        }

        @Override
        protected void removeSpi(String key) {
            values.remove(key);
        }

        @Override
        protected void removeNodeSpi() {
        }

        @Override
        protected String[] keysSpi() {
            return values.keySet().toArray(String[]::new);
        }

        @Override
        protected String[] childrenNamesSpi() {
            return children.keySet().toArray(String[]::new);
        }

        @Override
        protected AbstractPreferences childSpi(String name) {
            return children.computeIfAbsent(name, n -> new Memory(this, n));
        }

        @Override
        protected void syncSpi() throws BackingStoreException {
        }

        @Override
        protected void flushSpi() throws BackingStoreException {
        }
    }
}

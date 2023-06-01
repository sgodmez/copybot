package com.copybot.engine.resources;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class ResourcesEngine {

    private static CombinedResourceBundle resourceBundle;
    private static Set<Locale> supportedLocales;

    static {
        resourceBundle = new CombinedResourceBundle();
        supportedLocales = new HashSet<>();

        registerBundle("com.copybot.engine.engineBundle");
        addSupportedLocale(Locale.ENGLISH);
        addSupportedLocale(Locale.FRENCH);
    }

    public static void registerBundle(String baseName) {
        resourceBundle.addBundle(baseName);
    }

    public static void addSupportedLocale(Locale locale) {
        supportedLocales.add(locale);
    }

    public static void loadLanguage(Locale locale) {
        Locale.setDefault(locale);
        resourceBundle.load(locale);
    }

    public static CombinedResourceBundle getResourceBundle() {
        return resourceBundle;
    }

}

package com.copybot.resources;

import com.copybot.plugin.api.definition.IPlugin;

import java.util.*;

public final class ResourcesEngine {

    private static CombinedResourceBundle resourceBundle;
    private static List<CombinedResourceBundle> pluginResourceBundles;
    private static Set<Locale> supportedLocales;

    static {
        resourceBundle = new CombinedResourceBundle();
        pluginResourceBundles = new ArrayList<>();
        supportedLocales = new HashSet<>();

        registerBundle("com.copybot.engine.i18n.engineBundle");
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
        Locale.setDefault(locale); // could be used to set secondary language preference before "root" fallback
        resourceBundle.load(locale);
        pluginResourceBundles.forEach(p -> p.load(locale));
    }

    public static CombinedResourceBundle getResourceBundle() {
        return resourceBundle;
    }

    public static CombinedResourceBundle buildPluginResourceBundle(Iterable<String> baseNames, IPlugin context) {
        var pluginResourceBundle = new CombinedResourceBundle(context, resourceBundle);
        baseNames.forEach(pluginResourceBundle::addBundle);
        pluginResourceBundles.add(pluginResourceBundle);
        return pluginResourceBundle;
    }

}

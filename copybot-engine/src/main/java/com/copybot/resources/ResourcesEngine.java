package com.copybot.resources;

import com.copybot.plugin.api.action.IAction;

import java.util.*;

public final class ResourcesEngine {
    private static CombinedResourceBundle resourceBundle;
    private static Map<Module, CombinedResourceBundle> pluginResourceBundles;
    private static Set<Locale> supportedLocales;

    private static StackWalker walker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    static {
        resourceBundle = new CombinedResourceBundle();
        pluginResourceBundles = new HashMap<>();
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
        pluginResourceBundles.values().forEach(p -> p.load(locale));
    }

    public static CombinedResourceBundle getResourceBundle() {
        // determine which plugin called us to serve the corresponding ResourceBundle
        Module callerPluginModule = walker
                .walk(s -> s.map(StackWalker.StackFrame::getDeclaringClass)
                        .filter(c -> IAction.class.isAssignableFrom(c))
                        .map(c -> c.getModule())
                        .findFirst())
                .orElse(null);

        return pluginResourceBundles.getOrDefault(callerPluginModule, resourceBundle);
    }

    public static String getString(String key, Object... args) {
        return getResourceBundle().getString(key, args);
    }

    public static CombinedResourceBundle buildPluginResourceBundle(Iterable<String> baseNames, Module context) {
        var pluginResourceBundle = new CombinedResourceBundle(context, resourceBundle);
        baseNames.forEach(pluginResourceBundle::addBundle);
        pluginResourceBundles.put(context, pluginResourceBundle);
        return pluginResourceBundle;
    }

}

package com.copybot.plugin.demo.lib;

/**
 * Version 1.0 of the demo library, not a module. Version 2.0 replaced {@link #legacyDescribe()} by
 * {@code describe()}: a plugin linked against one version and given the other fails (NoSuchMethodError) instead
 * of silently running on it.
 */
public final class DemoLib {

    private DemoLib() {
    }

    /** Which library answered: version, module and jar. */
    public static String legacyDescribe() {
        return "demo-lib 1.0, module " + DemoLib.class.getModule().getName()
                + ", from " + DemoLib.class.getProtectionDomain().getCodeSource().getLocation();
    }
}

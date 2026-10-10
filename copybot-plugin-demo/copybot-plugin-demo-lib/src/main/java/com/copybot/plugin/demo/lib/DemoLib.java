package com.copybot.plugin.demo.lib;

/**
 * Version 2.0 of the demo library, a named module. {@link #describe()} replaced {@code legacyDescribe()} of
 * version 1.0: a plugin linked against one version and given the other fails (NoSuchMethodError) instead of
 * silently running on it.
 */
public final class DemoLib {

    private DemoLib() {
    }

    /** Which library answered: version, module and jar. */
    public static String describe() {
        return "demo-lib 2.0, module " + DemoLib.class.getModule().getName()
                + ", from " + DemoLib.class.getProtectionDomain().getCodeSource().getLocation();
    }
}

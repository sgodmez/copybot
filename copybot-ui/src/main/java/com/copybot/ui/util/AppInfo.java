package com.copybot.ui.util;

import com.copybot.utils.VersionUtil;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * What the About dialog tells: the version of Copybot (written by the build), of Java, of JavaFX and of the plugin
 * API.
 */
public final class AppInfo {

    public static final String NAME = "Copybot";
    public static final String SOURCE_URL = "https://github.com/sgodmez/copybot";

    /** Shown when a version is not known (a build that did not filter the resource). */
    static final String UNKNOWN = "?";

    private AppInfo() {
    }

    /** The version of the project (e.g. 1.0-SNAPSHOT), from version.properties filtered by Maven. */
    public static String version() {
        try (InputStream in = AppInfo.class.getResourceAsStream("version.properties")) {
            return version(in);
        } catch (IOException e) {
            return UNKNOWN;
        }
    }

    /**
     * The version of the plugin API (X.Y, the version of the engine module), distinct from the version of
     * Copybot: a plugin built for API X.Y runs on any Copybot whose API has the same major and a minor at least Y.
     */
    public static String pluginApiVersion() {
        return VersionUtil.engineVersion().orElse(UNKNOWN);
    }

    static String version(InputStream in) throws IOException {
        if (in == null) {
            return UNKNOWN;
        }
        Properties properties = new Properties();
        properties.load(in);
        String version = properties.getProperty("version", "").strip();
        return version.isEmpty() || version.contains("${") ? UNKNOWN : version;
    }

    /** "25.0.1 (Eclipse Adoptium)". */
    public static String javaVersion() {
        String vendor = System.getProperty("java.vendor");
        return Runtime.version() + (vendor == null ? "" : " (" + vendor + ")");
    }

    public static String javafxVersion() {
        return System.getProperty("javafx.runtime.version", UNKNOWN);
    }
}

package com.copybot.ui.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** What the About dialog tells: the version of Copybot (written by the build), of Java and of JavaFX. */
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

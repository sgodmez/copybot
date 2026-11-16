package com.copybot.plugin.api;

import com.copybot.engine.plugin.PluginDataRoot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/** The folders the engine gives a plugin. */
public final class PluginFolders {

    private static final Pattern CODE = Pattern.compile("[a-z0-9-]+");
    private static final String FALLBACK = "copybot-plugin-data";

    private PluginFolders() {
    }

    /**
     * The folder where a plugin keeps its own data across runs (e.g. a tool it downloaded):
     * {@code <data folder of Copybot>/plugin-data/<code>}, or {@code <java.io.tmpdir>/copybot-plugin-data/<code>}
     * outside the engine (a test). Created when missing.
     *
     * @param pluginCode the code of the plugin ({@code IPlugin.getPluginCode()}): lower case letters, digits and dashes
     * @return the folder, absolute
     * @throws IllegalArgumentException the code is not made of lower case letters, digits and dashes
     * @throws IOException              the folder cannot be created
     */
    public static Path dataDirectory(String pluginCode) throws IOException {
        if (pluginCode == null || !CODE.matcher(pluginCode).matches()) {
            throw new IllegalArgumentException("not a plugin code (a-z, 0-9, -): " + pluginCode);
        }
        Path root = PluginDataRoot.get()
                .orElseGet(() -> Path.of(System.getProperty("java.io.tmpdir"), FALLBACK).toAbsolutePath());
        return Files.createDirectories(root.resolve(pluginCode));
    }
}

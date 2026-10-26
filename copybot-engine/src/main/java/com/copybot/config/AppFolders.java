package com.copybot.config;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The folders of a self-contained Copybot. Three rules:
 * <ol>
 *   <li>The app folder (code and {@code plugins/}) is the parent of {@code jpackage.app-path} when the
 *       application is packaged, else the working directory.</li>
 *   <li>The data folder (config, preferences, logs) is the parent of the config file chosen with
 *       {@code --config-file}; otherwise the app folder when it is writable; otherwise
 *       {@code %APPDATA%/Copybot}, or {@code ~/.copybot} when there is no APPDATA.</li>
 *   <li>The config file is the chosen one, else {@code config.json} in the data folder.</li>
 * </ol>
 * Resolving creates no folder.
 *
 * @param appDir       folder holding the code and the plugins
 * @param dataDir      folder holding the config file, the preferences and the logs
 * @param configFile   the configuration file
 * @param configChosen true when {@code --config-file} was given
 */
public record AppFolders(Path appDir, Path dataDir, Path configFile, boolean configChosen) {

    /** System property holding the dev plugin folders (a path-separated list), unused when packaged. */
    public static final String DEV_PLUGIN_PATHS_PROPERTY = "copybot.devPluginPaths";

    public static AppFolders resolve(Optional<Path> chosenConfig) {
        return resolve(chosenConfig, System.getProperty("jpackage.app-path"), Path.of(System.getProperty("user.dir")),
                System.getenv("APPDATA"), System.getProperty("user.home"), AppFolders::isWritable);
    }

    static AppFolders resolve(Optional<Path> chosenConfig, String jpackageAppPath, Path workingDir,
                              String appData, String userHome, Predicate<Path> writable) {
        Path appDir = normalize(jpackageAppPath != null ? Path.of(jpackageAppPath).getParent() : workingDir);
        Optional<Path> chosen = chosenConfig.map(AppFolders::normalize);
        Path dataDir;
        if (chosen.isPresent()) {
            dataDir = chosen.get().getParent();
        } else if (writable.test(appDir)) {
            dataDir = appDir;
        } else if (appData != null && !appData.isBlank()) {
            dataDir = normalize(Path.of(appData).resolve("Copybot"));
        } else {
            dataDir = normalize(Path.of(userHome).resolve(".copybot"));
        }
        return new AppFolders(appDir, dataDir, chosen.orElseGet(() -> dataDir.resolve("config.json")), chosen.isPresent());
    }

    public Path pluginsDir() {
        return appDir.resolve("plugins");
    }

    public Path prefsFile() {
        return dataDir.resolve("prefs.json");
    }

    public Path logsDir() {
        return dataDir.resolve("logs");
    }

    /** The dev plugin folders of the IDE; always empty in the packaged application. */
    public static List<Path> devPluginPaths() {
        return devPluginPaths(System.getProperty(DEV_PLUGIN_PATHS_PROPERTY), System.getProperty("jpackage.app-path"));
    }

    static List<Path> devPluginPaths(String property, String jpackageAppPath) {
        if (jpackageAppPath != null || property == null || property.isBlank()) {
            return List.of();
        }
        return Arrays.stream(property.split(File.pathSeparator))
                .filter(part -> !part.isBlank())
                .map(Path::of)
                .toList();
    }

    /** True when a file can be created in the folder; the probe file is removed straight away. */
    public static boolean isWritable(Path dir) {
        Path probe = null;
        try {
            probe = Files.createTempFile(dir, ".copybot-write-test", null);
            return true;
        } catch (IOException | SecurityException e) {
            return false;
        } finally {
            if (probe != null) {
                try {
                    Files.deleteIfExists(probe);
                } catch (IOException | SecurityException ignored) {
                    // the probe is harmless if it stays
                }
            }
        }
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }
}

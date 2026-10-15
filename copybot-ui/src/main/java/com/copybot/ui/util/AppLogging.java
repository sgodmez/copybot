package com.copybot.ui.util;

import com.copybot.resources.ResourcesEngine;
import javafx.application.Platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * The log of the application: System.Logger writes through java.util.logging, which here also writes to a rotating
 * file in the user's folder (the packaged application has no console). The uncaught failures are logged, and shown
 * when they happen on the JavaFX thread.
 */
public final class AppLogging {

    private static final System.Logger LOG = System.getLogger(AppLogging.class.getName());

    /** A few files of a few MB: enough to hold the last sessions. */
    private static final int FILE_LIMIT = 2 * 1024 * 1024;
    private static final int FILE_COUNT = 3;

    private AppLogging() {
    }

    /** {@code %LOCALAPPDATA%\Copybot\logs} on Windows, {@code ~/.copybot/logs} elsewhere. */
    public static Path logDirectory() {
        return logDirectory(System.getenv("LOCALAPPDATA"), System.getProperty("user.home"));
    }

    static Path logDirectory(String localAppData, String userHome) {
        return localAppData != null && !localAppData.isBlank()
                ? Path.of(localAppData, "Copybot", "logs")
                : Path.of(userHome, ".copybot", "logs");
    }

    /**
     * Adds the log file (copybot0.log, the most recent) to the root logger and handles the uncaught failures of
     * every thread; call it once, on the JavaFX thread. A folder that cannot be written leaves the console only.
     */
    public static void install() {
        Path directory = logDirectory();
        try {
            Files.createDirectories(directory);
            FileHandler file = new FileHandler(directory.resolve("copybot%g.log").toString(), FILE_LIMIT, FILE_COUNT, true);
            file.setEncoding("UTF-8");
            file.setFormatter(new SimpleFormatter());
            Logger.getLogger("").addHandler(file);
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "no log file in " + directory, e);
        }
        Thread.setDefaultUncaughtExceptionHandler(AppLogging::uncaught);
        Thread.currentThread().setUncaughtExceptionHandler(AppLogging::uncaught); // the JavaFX thread
    }

    /** Logged; on the JavaFX thread (an event handler, a runLater) also shown, the view may be left half updated. */
    static void uncaught(Thread thread, Throwable failure) {
        LOG.log(System.Logger.Level.ERROR, "uncaught failure in the thread " + thread.getName(), failure);
        if (Platform.isFxApplicationThread()) {
            try {
                PopinUtil.show(null, ResourcesEngine.getString("error.unexpected"), failure);
            } catch (Throwable t) { // never a loop of failing popups
                LOG.log(System.Logger.Level.ERROR, "the error popup failed", t);
            }
        }
    }
}

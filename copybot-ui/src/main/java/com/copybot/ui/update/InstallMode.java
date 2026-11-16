package com.copybot.ui.update;

import java.nio.file.Files;
import java.nio.file.Path;

/** How this copy of the application was put on the machine, which decides how an update is applied. */
public enum InstallMode {
    /** Set up by the installer (the uninstaller sits next to the executable). */
    INSTALLED,
    /** Unzipped from the portable archive. */
    PORTABLE;

    /** The mode of the application living in {@code appDir}. */
    public static InstallMode of(Path appDir) {
        return Files.exists(appDir.resolve("unins000.exe")) ? INSTALLED : PORTABLE;
    }
}

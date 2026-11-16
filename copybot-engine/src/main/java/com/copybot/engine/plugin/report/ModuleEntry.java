package com.copybot.engine.plugin.report;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A module found in a plugin directory.
 *
 * @param version   null when the module declares none
 * @param location  the jar or directory it comes from, null when unknown
 * @param main      the module providing the plugin
 * @param automatic a plain jar without module-info, turned into an automatic module
 */
public record ModuleEntry(String name, String version, Path location, boolean main, boolean automatic) {

    public ModuleEntry {
        Objects.requireNonNull(name, "name");
    }
}

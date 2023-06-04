package com.copybot.engine.utils;

import java.lang.module.ModuleDescriptor;
import java.util.Optional;
import java.util.regex.Pattern;

public final class VersionUtil {

    private static final Pattern versionExtractPattern = Pattern.compile("^([0-9]+)\\.([0-9]+).*");

    public static boolean moduleCompatible(ModuleDescriptor module, ModuleDescriptor.Requires requirement) {
        return module.name().equals(requirement.name())
                && moduleVersionCompatible(module.version(), requirement.compiledVersion(), false);
    }

    public static boolean sameMajorMinorModule(ModuleDescriptor module1, ModuleDescriptor module2) {
        return module1.name().equals(module2.name())
                && moduleVersionCompatible(module1.version(), module2.version(), true);
    }

    public static boolean moduleVersionCompatible(Optional<ModuleDescriptor.Version> version, Optional<ModuleDescriptor.Version> versionRequire, boolean strict) {
        return version.isEmpty() || versionRequire.isEmpty()
                || isCompatible(version.get().toString(), versionRequire.get().toString(), strict);
    }

    /**
     * Check if version is compatible.
     * Same major version and same minor or above. If strict = true, only same minor.
     */
    public static boolean isCompatible(String version, String require, boolean strict) {
        var matcherVersion = versionExtractPattern.matcher(version);
        var matcherRequire = versionExtractPattern.matcher(require);
        if (!matcherVersion.matches() || !matcherRequire.matches()) {
            return version.equals(require); // fallback if no minor version
        }

        boolean sameMajor = matcherVersion.group(1).equals(matcherRequire.group(1));

        if (strict) {
            return sameMajor
                    && matcherVersion.group(2).equals(matcherRequire.group(2)); // same minor
        }
        return sameMajor
                && Integer.valueOf(matcherVersion.group(2)) >= Integer.valueOf(matcherRequire.group(2)); // same minor or above
    }
}

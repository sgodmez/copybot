package com.copybot.engine.utils;

import java.lang.module.ModuleDescriptor;
import java.util.Optional;
import java.util.regex.Pattern;

public final class ModuleUtil {

    private static final Pattern versionExtractPattern = Pattern.compile("^([0-9]+)\\.([0-9]+).*");

    public static boolean moduleCompatible(ModuleDescriptor module, ModuleDescriptor.Requires requirement) {
        return module.name().equals(requirement.name())
                && moduleVersionCompatible(module.version(), requirement.compiledVersion(), false);
    }

    public static boolean sameMajorMinorModule(ModuleDescriptor module1, ModuleDescriptor module2) {
        return module1.name().equals(module2.name())
                && moduleVersionCompatible(module1.version(), module2.version(), true);
    }

    private static boolean moduleVersionCompatible(Optional<ModuleDescriptor.Version> version, Optional<ModuleDescriptor.Version> versionRequire, boolean strict) {
        return version.isEmpty() || versionRequire.isEmpty()
                || isCompatible(version.get(), versionRequire.get(), strict);
    }

    private static boolean isCompatible(ModuleDescriptor.Version version, ModuleDescriptor.Version require, boolean strict) {
        var matcherVersion = versionExtractPattern.matcher(version.toString());
        var matcherRequire = versionExtractPattern.matcher(version.toString());
        if (!matcherVersion.matches() || !matcherRequire.matches()) {
            return version.toString().equals(require.toString()); // fallback if no minor version
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

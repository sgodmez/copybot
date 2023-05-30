package com.copybot.engine.utils;

import java.lang.module.ModuleDescriptor;
import java.util.Optional;
import java.util.regex.Pattern;

public class ModuleUtil {

    private static final Pattern versionExtractPattern = Pattern.compile("^([0-9]+\\.[0-9]+).*");

    public static boolean moduleMatches(ModuleDescriptor.Requires requirement, ModuleDescriptor module) {
        return module.name().equals(requirement.name())
                && moduleVersionMatches(module.version(), requirement.compiledVersion());
    }

    public static boolean moduleMatches(ModuleDescriptor module1, ModuleDescriptor module2) {
        return module1.name().equals(module2.name())
                && moduleVersionMatches(module1.version(), module2.version());
    }

    public static boolean moduleVersionMatches(Optional<ModuleDescriptor.Version> version1Opt, Optional<ModuleDescriptor.Version> version2Opt) {
        return version1Opt.isEmpty() || version2Opt.isEmpty()
                || sameMinorVersion(version1Opt.get(), version2Opt.get());
    }

    public static boolean sameMinorVersion(ModuleDescriptor.Version v1, ModuleDescriptor.Version v2) {
        return extractMajorMinor(v1).equals(extractMajorMinor(v2));
    }

    private static String extractMajorMinor(ModuleDescriptor.Version version) {
        var matcher = versionExtractPattern.matcher(version.toString());
        if (!matcher.matches()) {
            return version.toString(); // fallback if no minor version
        }
        return matcher.group(1);
    }
}

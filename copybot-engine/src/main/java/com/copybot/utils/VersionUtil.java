package com.copybot.utils;

import java.lang.module.ModuleDescriptor;
import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Pattern;

public final class VersionUtil {

    // major, then an optional minor; anything after ("1.", ".3", "-SNAPSHOT", "+9") is ignored
    private static final Pattern versionExtractPattern = Pattern.compile("^([0-9]+)(?:\\.([0-9]+))?(?:[.+-].*)?$");

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
        if (require == null || require.isBlank()) {
            return true;
        }

        var matcherVersion = versionExtractPattern.matcher(version);
        var matcherRequire = versionExtractPattern.matcher(require);
        if (!matcherVersion.matches() || !matcherRequire.matches()) {
            return version.equals(require); // fallback if uncommon pattern
        }

        if (!matcherVersion.group(1).equals(matcherRequire.group(1))) {
            return false;
        }

        if (matcherRequire.group(2) == null) {
            // no minor required version
            return true;
        }

        int requiredMinor = Integer.parseInt(matcherRequire.group(2));
        int minor = matcherVersion.group(2) == null ? 0 : Integer.parseInt(matcherVersion.group(2)); // "2" is "2.0"
        return strict ? minor == requiredMinor : minor >= requiredMinor; // same minor, or above when not strict
    }

    /**
     * Version order, oldest first: numeric ({@link ModuleDescriptor.Version}, so "1.10" is after "1.9"), the
     * versions it cannot parse before every parsable one (between themselves in string order), null first.
     */
    public static final Comparator<String> VERSION_ORDER = Comparator.nullsFirst((a, b) -> {
        Optional<ModuleDescriptor.Version> va = parse(a);
        Optional<ModuleDescriptor.Version> vb = parse(b);
        if (va.isPresent() && vb.isPresent()) {
            return va.get().compareTo(vb.get());
        }
        if (va.isPresent() != vb.isPresent()) {
            return va.isPresent() ? 1 : -1;
        }
        return a.compareTo(b);
    });

    private static Optional<ModuleDescriptor.Version> parse(String version) {
        try {
            return Optional.of(ModuleDescriptor.Version.parse(version));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}

package com.copybot.ui.update;

import java.util.Arrays;

/**
 * Version numbers as {@code X.Y.Z} with an optional qualifier ({@code 1.0.0-beta.2}); a qualifier sorts before the
 * release, qualifiers sort as in SemVer ({@code beta.2 < beta.10 < rc.1}).
 */
public final class AppVersion {

    private AppVersion() {
    }

    /** False for what is not a published release: the unknown version ({@code ?}), empty, null or a snapshot. */
    public static boolean isRelease(String version) {
        return version != null && !version.isBlank() && !version.equals("?") && !version.contains("SNAPSHOT");
    }

    /** Negative when {@code a} is older than {@code b}, zero when they are the same version, positive when newer. */
    public static int compare(String a, String b) {
        String[] left = a.trim().split("-", 2);
        String[] right = b.trim().split("-", 2);
        int[] x = numbers(left[0]);
        int[] y = numbers(right[0]);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int c = Integer.compare(i < x.length ? x[i] : 0, i < y.length ? y[i] : 0);
            if (c != 0) {
                return c;
            }
        }
        boolean qualifiedLeft = left.length > 1;
        boolean qualifiedRight = right.length > 1;
        if (qualifiedLeft != qualifiedRight) {
            return qualifiedLeft ? -1 : 1;
        }
        return qualifiedLeft ? compareQualifiers(left[1], right[1]) : 0;
    }

    /**
     * The SemVer order of pre-release qualifiers: dot-separated segments one by one, numbers numerically, words
     * alphabetically, a number before a word, and fewer segments first when all the shared ones are equal
     * ({@code alpha < beta < beta.2 < beta.10 < rc.1}).
     */
    private static int compareQualifiers(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            int c = compareSegments(x[i], y[i]);
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(x.length, y.length);
    }

    private static int compareSegments(String a, String b) {
        boolean numberA = isNumber(a);
        boolean numberB = isNumber(b);
        if (numberA && numberB) {
            // no overflow on long runs of digits: the longer number (without leading zeros) is the greater
            String p = stripZeros(a);
            String q = stripZeros(b);
            return p.length() != q.length() ? Integer.compare(p.length(), q.length()) : p.compareTo(q);
        }
        if (numberA != numberB) {
            return numberA ? -1 : 1;
        }
        return a.compareTo(b);
    }

    private static boolean isNumber(String segment) {
        return !segment.isEmpty() && segment.chars().allMatch(c -> c >= '0' && c <= '9');
    }

    private static String stripZeros(String digits) {
        int i = 0;
        while (i < digits.length() - 1 && digits.charAt(i) == '0') {
            i++;
        }
        return digits.substring(i);
    }

    private static int[] numbers(String dotted) {
        return Arrays.stream(dotted.split("\\."))
                .mapToInt(part -> {
                    try {
                        return Integer.parseInt(part);
                    } catch (NumberFormatException e) {
                        return 0;
                    }
                }).toArray();
    }
}

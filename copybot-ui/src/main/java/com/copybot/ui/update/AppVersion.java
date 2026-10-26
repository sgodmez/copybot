package com.copybot.ui.update;

import java.util.Arrays;

/** Version numbers as {@code X.Y.Z} with an optional qualifier ({@code 1.0.0-beta}); a qualifier sorts before the release. */
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
        return qualifiedLeft ? left[1].compareTo(right[1]) : 0;
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

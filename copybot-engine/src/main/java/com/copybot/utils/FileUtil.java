package com.copybot.utils;

import com.copybot.resources.ResourcesEngine;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class FileUtil {
    public static final long ONE_KB = 1024;
    public static final BigDecimal ONE_KB_BD = BigDecimal.valueOf(ONE_KB);

    /**
     * The number of bytes in a megabyte.
     */
    public static final long ONE_MB = ONE_KB * ONE_KB;
    public static final BigDecimal ONE_MB_BD = BigDecimal.valueOf(ONE_MB);

    /**
     * The number of bytes in a gigabyte.
     */
    public static final long ONE_GB = ONE_KB * ONE_MB;
    public static final BigDecimal ONE_GB_BD = BigDecimal.valueOf(ONE_GB);

    private static final BigDecimal[] UNIT_SIZES = {BigDecimal.ONE, ONE_KB_BD, ONE_MB_BD, ONE_GB_BD};
    private static final String[] UNIT_KEYS = {"size.b", "size.kb", "size.mb", "size.gb"};

    /**
     * The size in the largest unit it reaches (bytes up to gigabytes), rounded half up to these decimals
     * (bytes: none), for a display: the decimal separator of the default locale and the unit in the current
     * language ("292,97 Ko" in French). A value that rounds to 1024 is told in the next unit ("1,0 Mo", never
     * "1024,0 Ko"). Not a pattern variable: a file name never depends on the language.
     */
    public static String toAutoUnitSize(long size, int decimals) {
        return toAutoUnitSize(size, decimals, Locale.getDefault());
    }

    /** {@link #toAutoUnitSize(long, int)} with the decimal separator of this locale. */
    public static String toAutoUnitSize(long size, int decimals, Locale locale) {
        int unit = unitOf(size, decimals);
        return format(size, unit, decimals, locale) + " "
                + ResourcesEngine.getResourceBundle().getString(UNIT_KEYS[unit]);
    }

    /** The index of the unit: the largest one the size reaches, the next one when the value rounds to 1024. */
    private static int unitOf(long size, int decimals) {
        int unit = 0;
        while (unit < UNIT_SIZES.length - 1 && size >= UNIT_SIZES[unit + 1].longValue()) {
            unit++;
        }
        if (unit > 0 && unit < UNIT_SIZES.length - 1 && rounded(size, unit, decimals).compareTo(ONE_KB_BD) >= 0) {
            unit++;
        }
        return unit;
    }

    private static String format(long size, int unit, int decimals, Locale locale) {
        NumberFormat format = NumberFormat.getNumberInstance(locale);
        format.setGroupingUsed(false);
        int fractionDigits = unit == 0 ? 0 : decimals;
        format.setMinimumFractionDigits(fractionDigits);
        format.setMaximumFractionDigits(fractionDigits);
        return format.format(rounded(size, unit, decimals));
    }

    private static BigDecimal rounded(long size, int unit, int decimals) {
        return unit == 0
                ? BigDecimal.valueOf(size)
                : BigDecimal.valueOf(size).divide(UNIT_SIZES[unit], decimals, RoundingMode.HALF_UP);
    }

    public static List<Path> listDirectory(Path dirPath) {
        List<Path> paths = new ArrayList<>();
        doListDirectory(dirPath, paths, false);
        return paths;
    }

    public static List<Path> listDirectoryRecur(Path dirPath, boolean withRoot) {
        List<Path> paths = new ArrayList<>();
        if (withRoot) {
            paths.add(dirPath);
        }
        doListDirectory(dirPath, paths, true);
        return paths;
    }

    private static void doListDirectory(Path dirPath, List<Path> pathList, boolean recur) {
        File[] files = dirPath.toFile().listFiles();
        if (files != null) {
            for (File aFile : files) {
                if (aFile.isDirectory()) {
                    Path aFilePath = aFile.toPath();
                    pathList.add(aFilePath);
                    if (recur) {
                        doListDirectory(aFilePath, pathList, recur);
                    }
                }
            }
        }
    }
}

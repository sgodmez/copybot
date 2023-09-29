package com.copybot.utils;

import com.copybot.resources.ResourcesEngine;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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

    public static String toAutoUnitSize(long size, int decimals) {
        String displaySize;
        BigDecimal sizeBd = BigDecimal.valueOf(size);

        if (size / ONE_GB > 0) {
            displaySize = sizeBd.divide(ONE_KB_BD, decimals, BigDecimal.ROUND_CEILING) + " " + ResourcesEngine.getResourceBundle().getString("size.gb");
        } else if (size / ONE_MB > 0) {
            displaySize = new BigDecimal(size).divide(ONE_MB_BD, decimals, BigDecimal.ROUND_CEILING) + " " + ResourcesEngine.getResourceBundle().getString("size.mb");
        } else if (size / ONE_KB > 0) {
            displaySize = new BigDecimal(size).divide(ONE_GB_BD, decimals, BigDecimal.ROUND_CEILING) + " " + ResourcesEngine.getResourceBundle().getString("size.kb");
        } else {
            displaySize = size + " " + ResourcesEngine.getResourceBundle().getString("size.b");
        }
        return displaySize;
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

package com.copybot.engine.utils;

import com.copybot.engine.CopybotEngine;

import java.math.BigDecimal;

public class FileUtil {
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
            displaySize = sizeBd.divide(ONE_KB_BD, decimals, BigDecimal.ROUND_CEILING) + " " + CopybotEngine.resourceBundle.getString("size.gb");
        } else if (size / ONE_MB > 0) {
            displaySize = new BigDecimal(size).divide(ONE_MB_BD, decimals, BigDecimal.ROUND_CEILING) + " " + CopybotEngine.resourceBundle.getString("size.mb");
        } else if (size / ONE_KB > 0) {
            displaySize = new BigDecimal(size).divide(ONE_GB_BD, decimals, BigDecimal.ROUND_CEILING) + " " + CopybotEngine.resourceBundle.getString("size.kb");
        } else {
            displaySize = size + " " + CopybotEngine.resourceBundle.getString("size.b");
        }
        return displaySize;
    }
}

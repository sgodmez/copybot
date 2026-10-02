package com.copybot.utils;

import com.copybot.resources.ResourcesEngine;
import org.junit.jupiter.api.Test;

import java.text.DecimalFormatSymbols;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Human-readable sizes for the display, rounded half up (spec desktop-ui §2, §5), in the locale and language.
 */
public class FileUtilTest {

    /** The number before the unit, with the "." separator. */
    private static String number(long size, int decimals) {
        String text = FileUtil.toAutoUnitSize(size, decimals, Locale.ROOT);
        return text.substring(0, text.indexOf(' '));
    }

    private static String unit(long size) {
        String text = FileUtil.toAutoUnitSize(size, 1);
        return text.substring(text.indexOf(' '));
    }

    /** Runs with this language loaded (bundles and default locale), then restores the previous one. */
    private static String inLanguage(Locale language, java.util.function.Supplier<String> work) {
        Locale previous = Locale.getDefault();
        try {
            ResourcesEngine.loadLanguage(language);
            return work.get();
        } finally {
            ResourcesEngine.loadLanguage(previous);
        }
    }

    @Test
    public void eachUnitDividesByItsOwnPower() {
        assertEquals("999", number(999, 1));
        assertEquals("1.5", number(1536, 1));
        assertEquals("5.5", number(5 * FileUtil.ONE_MB + FileUtil.ONE_MB / 2, 1));
        assertEquals("3.0", number(3 * FileUtil.ONE_GB, 1));
        assertEquals("2.25", number(2 * FileUtil.ONE_GB + FileUtil.ONE_GB / 4, 2));
    }

    @Test
    public void theUnitsDiffer() {
        assertNotEquals(unit(999), unit(1536));
        assertNotEquals(unit(1536), unit(3 * FileUtil.ONE_GB));
        assertFalse(FileUtil.toAutoUnitSize(3 * FileUtil.ONE_GB, 1).contains("%"));
    }

    @Test
    public void roundsHalfUp() {
        assertEquals("1.0", number(1075, 1)); // 1.0498 KB
        assertEquals("1.1", number(1076, 1)); // 1.0508 KB
        assertEquals("292.97", number(300_000, 2));
    }

    @Test
    public void theBoundariesChangeUnit() {
        assertEquals("1023", number(1023, 1));
        assertEquals("1.0", number(FileUtil.ONE_KB, 1));
        assertNotEquals(unit(1023), unit(FileUtil.ONE_KB));
        assertEquals("1.0", number(FileUtil.ONE_MB, 1));
        assertEquals(unit(FileUtil.ONE_MB), unit(5 * FileUtil.ONE_MB));
        assertEquals("1.0", number(FileUtil.ONE_GB, 1));
        assertEquals(unit(FileUtil.ONE_GB), unit(3 * FileUtil.ONE_GB));
    }

    @Test
    public void aValueRoundedTo1024IsPromotedToTheNextUnit() {
        assertEquals("1.0", number(FileUtil.ONE_MB - 1, 1)); // 1023.999 KB
        assertEquals(unit(FileUtil.ONE_MB), unit(FileUtil.ONE_MB - 1));
        assertEquals("1.0", number(FileUtil.ONE_GB - 1, 1)); // 1023.999 MB
        assertEquals(unit(FileUtil.ONE_GB), unit(FileUtil.ONE_GB - 1));
    }

    @Test
    public void theDisplayFormUsesTheLocaleSeparator() {
        String french = inLanguage(Locale.FRENCH, () -> FileUtil.toAutoUnitSize(300_000, 2, Locale.FRENCH));
        String english = inLanguage(Locale.ENGLISH, () -> FileUtil.toAutoUnitSize(300_000, 2, Locale.ENGLISH));

        assertTrue(french.startsWith("292,97 "), french);
        assertTrue(english.startsWith("292.97 "), english);
        assertFalse(french.contains("%"), french);
        char separator = DecimalFormatSymbols.getInstance(Locale.FRENCH).getDecimalSeparator();
        String promoted = FileUtil.toAutoUnitSize(FileUtil.ONE_MB - 1, 1, Locale.FRENCH);
        assertTrue(promoted.startsWith("1" + separator + "0 "), "same rounding and promotion: " + promoted);
    }
}

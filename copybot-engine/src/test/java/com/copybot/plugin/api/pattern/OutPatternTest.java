package com.copybot.plugin.api.pattern;

import com.copybot.exception.CopybotException;
import com.copybot.resources.ResourcesEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class OutPatternTest {

    private static final Map<String, String> PHOTO = Map.of("name", "DSC_1.NEF", "captureDate.Y", "2026",
            "lastModified.Y", "2025", "blank", "  ");

    private static String resolve(String pattern, Map<String, String> display) {
        return OutPattern.parse(pattern).resolve(display).text();
    }

    @Test
    public void textWithoutBracesIsCopiedAsIs() {
        assertEquals("//nas/photo/x.jpg", resolve("//nas/photo/x.jpg", PHOTO));
    }

    @Test
    public void aKeyIsReplacedByItsValue() {
        assertEquals("//nas/2026/DSC_1.NEF", resolve("//nas/{captureDate.Y}/{name}", PHOTO));
    }

    @Test
    public void theFirstAlternativeWithAValueWins() {
        assertEquals("2025", resolve("{missing|lastModified.Y|captureDate.Y}", PHOTO));
        assertEquals("2026", resolve("{captureDate.Y|lastModified.Y}", PHOTO));
    }

    @Test
    public void aFixedValueAlwaysHasAValue() {
        assertEquals("sans-date", resolve("{missing|'sans-date'}", PHOTO));
    }

    @Test
    public void aDoubledQuoteInAFixedValueIsOneQuote() {
        assertEquals("l'été", resolve("{missing|'l''été'}", PHOTO));
    }

    @Test
    public void spacesAroundTheAlternativesAreIgnored() {
        assertEquals("2025", resolve("{ missing | lastModified.Y }", PHOTO));
    }

    @Test
    public void aBlankValueCountsAsMissing() {
        assertEquals("2026", resolve("{blank|captureDate.Y}", PHOTO));
    }

    @Test
    public void anExpressionWithoutValueIsKeptAsWrittenAndReported() {
        OutPattern.Resolution resolution = OutPattern.parse("/{captureDate.Y | other}/{name}").resolve(Map.of("name", "a"));

        assertEquals("/{captureDate.Y | other}/a", resolution.text());
        assertEquals(List.of("{captureDate.Y | other}"), resolution.missing());
        assertFalse(resolution.complete());
    }

    @Test
    public void aCompleteResolutionHasNoMissingExpression() {
        assertTrue(OutPattern.parse("{name}").resolve(PHOTO).complete());
    }

    @Test
    public void keysListsTheKeysInOrderWithoutDuplicatesNorFixedValues() {
        assertEquals(List.of("captureDate.Y", "lastModified.Y", "name"),
                OutPattern.parse("{captureDate.Y|lastModified.Y|'x'}/{name}/{captureDate.Y}").keys());
    }

    @Test
    public void theStaticPrefixIsTheTextBeforeTheFirstBrace() {
        assertEquals("//nas/photo/", OutPattern.parse("//nas/photo/{captureDate.Y}/{name}").staticPrefix());
        assertEquals("//nas/x.jpg", OutPattern.parse("//nas/x.jpg").staticPrefix());
    }

    private static void assertSyntaxError(String pattern, int position, String reasonKey) {
        CopybotException e = assertThrows(CopybotException.class, () -> OutPattern.parse(pattern), pattern);
        String reason = ResourcesEngine.getString(reasonKey);
        assertEquals(ResourcesEngine.getString("pattern.syntax", pattern, position, reason), e.getMessage(), pattern);
    }

    @Test
    public void syntaxErrorsGiveTheirPosition() {
        assertSyntaxError("/a/{name", 4, "pattern.syntax.unclosed-brace");
        assertSyntaxError("/a/name}", 8, "pattern.syntax.unopened-brace");
        assertSyntaxError("/a/{}", 4, "pattern.syntax.empty-expression");
        assertSyntaxError("{ }", 1, "pattern.syntax.empty-expression");
        assertSyntaxError("{a||b}", 4, "pattern.syntax.empty-alternative");
        assertSyntaxError("{a|}", 4, "pattern.syntax.empty-alternative");
        assertSyntaxError("{a|'x}", 4, "pattern.syntax.unclosed-quote");
        assertSyntaxError("{'a'b}", 5, "pattern.syntax.text-after-quote");
        assertSyntaxError("{a{b}", 1, "pattern.syntax.unclosed-brace");
    }
}

package com.copybot.plugin.api.pattern;

import com.copybot.exception.CopybotException;
import com.copybot.resources.ResourcesEngine;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * An output pattern (spec pattern-helper §1): text copied as is, and {expressions} replaced by a metadata
 * value. An expression is a list of alternatives separated by "|", each a display key or a fixed value
 * between quotes ('' for a quote); the first alternative with a non blank value wins. Parsed once, resolved
 * per item; immutable and thread-safe.
 */
public final class OutPattern {

    /** A piece of the pattern: literal text, or an expression with its source text (braces included). */
    private sealed interface Part permits Text, Expression {
    }

    private record Text(String text) implements Part {
    }

    /** @param alternatives a key, or a fixed value (FixedValue) */
    private record Expression(String source, List<Object> alternatives) implements Part {
    }

    private record FixedValue(String value) {
    }

    /**
     * @param text    the resolved text; an expression without value is copied as written, braces included
     * @param missing the expressions without value, as written (e.g. "{captureDate.Y|lastModified.Y}")
     */
    public record Resolution(String text, List<String> missing) {
        public Resolution {
            missing = List.copyOf(missing);
        }

        public boolean complete() {
            return missing.isEmpty();
        }
    }

    private final String pattern;
    private final List<Part> parts;

    private OutPattern(String pattern, List<Part> parts) {
        this.pattern = pattern;
        this.parts = List.copyOf(parts);
    }

    /** @throws CopybotException pattern.syntax (the pattern, the 1-based position, the reason) */
    public static OutPattern parse(String pattern) {
        List<Part> parts = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c == '}') {
                throw syntax(pattern, i, "pattern.syntax.unopened-brace");
            }
            if (c != '{') {
                text.append(c);
                i++;
                continue;
            }
            if (!text.isEmpty()) {
                parts.add(new Text(text.toString()));
                text.setLength(0);
            }
            int end = parseExpression(pattern, i, parts);
            i = end + 1;
        }
        if (!text.isEmpty()) {
            parts.add(new Text(text.toString()));
        }
        return new OutPattern(pattern, parts);
    }

    /** Parses the expression opened at index open, adds it to parts; returns the index of its closing brace. */
    private static int parseExpression(String pattern, int open, List<Part> parts) {
        List<Object> alternatives = new ArrayList<>();
        int i = open + 1;
        while (true) {
            i = skipSpaces(pattern, i);
            if (i >= pattern.length()) {
                throw syntax(pattern, open, "pattern.syntax.unclosed-brace");
            }
            char c = pattern.charAt(i);
            if (c == '{') {
                throw syntax(pattern, open, "pattern.syntax.unclosed-brace");
            }
            if (c == '\'') {
                int quote = i;
                StringBuilder value = new StringBuilder();
                i++;
                while (true) {
                    if (i >= pattern.length()) {
                        throw syntax(pattern, quote, "pattern.syntax.unclosed-quote");
                    }
                    if (pattern.charAt(i) == '\'') {
                        if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '\'') {
                            value.append('\'');
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    value.append(pattern.charAt(i++));
                }
                alternatives.add(new FixedValue(value.toString()));
                i = skipSpaces(pattern, i);
                if (i < pattern.length() && pattern.charAt(i) != '|' && pattern.charAt(i) != '}') {
                    throw syntax(pattern, i, "pattern.syntax.text-after-quote");
                }
            } else if (c == '|' || c == '}') {
                if (alternatives.isEmpty() && c == '}') {
                    throw syntax(pattern, open, "pattern.syntax.empty-expression");
                }
                throw syntax(pattern, i, "pattern.syntax.empty-alternative");
            } else {
                int start = i;
                while (i < pattern.length() && "|}{'".indexOf(pattern.charAt(i)) < 0) {
                    i++;
                }
                alternatives.add(pattern.substring(start, i).strip());
            }
            if (i >= pattern.length()) {
                throw syntax(pattern, open, "pattern.syntax.unclosed-brace");
            }
            char separator = pattern.charAt(i);
            if (separator == '}') {
                parts.add(new Expression(pattern.substring(open, i + 1), List.copyOf(alternatives)));
                return i;
            }
            if (separator != '|') { // '{' or a quote right after a key
                throw syntax(pattern, open, "pattern.syntax.unclosed-brace");
            }
            i++;
        }
    }

    private static int skipSpaces(String pattern, int i) {
        while (i < pattern.length() && pattern.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    private static CopybotException syntax(String pattern, int index, String reasonKey) {
        return CopybotException.ofResource("pattern.syntax", pattern, index + 1, ResourcesEngine.getString(reasonKey));
    }

    public String pattern() {
        return pattern;
    }

    /** The keys used, in order, without duplicates (fixed values excluded). */
    public List<String> keys() {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (Part part : parts) {
            if (part instanceof Expression expression) {
                for (Object alternative : expression.alternatives()) {
                    if (alternative instanceof String key) {
                        keys.add(key);
                    }
                }
            }
        }
        return List.copyOf(keys);
    }

    /** The text before the first expression (the whole pattern without expression). */
    public String staticPrefix() {
        return !parts.isEmpty() && parts.getFirst() instanceof Text text ? text.text() : "";
    }

    public Resolution resolve(Map<String, String> display) {
        StringBuilder out = new StringBuilder();
        List<String> missing = new ArrayList<>();
        for (Part part : parts) {
            switch (part) {
                case Text text -> out.append(text.text());
                case Expression expression -> {
                    String value = valueOf(expression, display);
                    if (value == null) {
                        missing.add(expression.source());
                        out.append(expression.source());
                    } else {
                        out.append(value);
                    }
                }
            }
        }
        return new Resolution(out.toString(), missing);
    }

    private static String valueOf(Expression expression, Map<String, String> display) {
        for (Object alternative : expression.alternatives()) {
            if (alternative instanceof FixedValue fixed) {
                return fixed.value();
            }
            String value = display.get((String) alternative);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}

package com.copybot.plugin.remote.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The include and exclude globs of a remote input, read as file.read reads them (spec remote §4): relative to the
 * listed folder, case-insensitive (globs and paths lower-cased), "**&#47;x" also matching x at the first level, an
 * exclude "x/**" leaving the folders matching x unlisted. A remote path is not a path of this computer: the glob
 * syntax is the one of {@link java.nio.file.FileSystem#getPathMatcher} on Unix, '/' between the names, matched here
 * on the text of the path (a remote name may hold characters a Windows path refuses).
 */
final class RemoteGlobs {

    private static final String REGEX_META = ".^$+{[]|()";
    private static final String GLOB_META = "\\*?[{";
    private static final char EOL = 0;

    private final List<Pattern> includes;
    private final List<Pattern> excludes;
    private final List<Pattern> excludedFolders;

    /** @throws PatternSyntaxException a glob is not valid; its {@link PatternSyntaxException#getPattern()} is the glob */
    RemoteGlobs(List<String> include, List<String> exclude) {
        includes = matchers(include);
        excludes = matchers(exclude);
        excludedFolders = folderMatchers(exclude);
    }

    /** Whether the file at this path (relative to the listed folder) is listed. */
    boolean selected(String relative) {
        String lowerCase = relative.toLowerCase(Locale.ROOT);
        boolean included = includes.isEmpty() || includes.stream().anyMatch(p -> p.matcher(lowerCase).matches());
        return included && excludes.stream().noneMatch(p -> p.matcher(lowerCase).matches());
    }

    /** Whether the folder at this path (relative to the listed folder) is left unlisted. */
    boolean excludedFolder(String relative) {
        String lowerCase = relative.toLowerCase(Locale.ROOT);
        return excludedFolders.stream().anyMatch(p -> p.matcher(lowerCase).matches());
    }

    private static List<Pattern> matchers(List<String> globs) {
        if (globs == null) {
            return List.of();
        }
        List<Pattern> patterns = new ArrayList<>();
        for (String glob : globs) {
            if (glob == null) {
                continue;
            }
            String lowerCase = glob.toLowerCase(Locale.ROOT);
            try {
                patterns.add(Pattern.compile(toRegex(lowerCase)));
                if (lowerCase.startsWith("**/")) {
                    patterns.add(Pattern.compile(toRegex(lowerCase.substring(3))));
                }
            } catch (PatternSyntaxException e) {
                throw new PatternSyntaxException(e.getDescription(), glob, e.getIndex());
            }
        }
        return List.copyOf(patterns);
    }

    /** For each exclude "x/**", a matcher of x (as file.read: what lies under such a folder is all excluded). */
    private static List<Pattern> folderMatchers(List<String> globs) {
        if (globs == null) {
            return List.of();
        }
        List<Pattern> folders = new ArrayList<>();
        for (String glob : globs) {
            if (glob != null && glob.endsWith("/**") && glob.length() > 3) {
                try {
                    folders.addAll(matchers(List.of(glob.substring(0, glob.length() - 3))));
                } catch (PatternSyntaxException e) {
                    // a valid glob whose part before "/**" is not one: no shortcut, listed
                }
            }
        }
        return List.copyOf(folders);
    }

    private static char next(String glob, int i) {
        return i < glob.length() ? glob.charAt(i) : EOL;
    }

    /** The regular expression of a glob, as the JDK's Unix file system makes it (sun.nio.fs.Globs). */
    static String toRegex(String glob) {
        boolean inGroup = false;
        StringBuilder regex = new StringBuilder("^");
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i++);
            switch (c) {
                case '\\' -> {
                    if (i == glob.length()) {
                        throw new PatternSyntaxException("No character to escape", glob, i - 1);
                    }
                    char escaped = glob.charAt(i);
                    if (GLOB_META.indexOf(escaped) >= 0 || REGEX_META.indexOf(escaped) >= 0) {
                        regex.append('\\');
                    }
                    regex.append(escaped);
                    i++;
                }
                case '/' -> regex.append(c);
                case '[' -> {
                    // a class never matches the name separator
                    regex.append("[[^/]&&[");
                    if (next(glob, i) == '^') {
                        regex.append("\\^");
                        i++;
                    } else {
                        if (next(glob, i) == '!') {
                            regex.append('^');
                            i++;
                        }
                        if (next(glob, i) == '-') {
                            regex.append('-');
                            i++;
                        }
                    }
                    boolean hasRangeStart = false;
                    char last = 0;
                    while (i < glob.length()) {
                        c = glob.charAt(i++);
                        if (c == ']') {
                            break;
                        }
                        if (c == '/') {
                            throw new PatternSyntaxException("Explicit 'name separator' in class", glob, i - 1);
                        }
                        if (c == '\\' || c == '[' || c == '&' && next(glob, i) == '&') {
                            regex.append('\\');
                        }
                        regex.append(c);
                        if (c == '-') {
                            if (!hasRangeStart) {
                                throw new PatternSyntaxException("Invalid range", glob, i - 1);
                            }
                            if ((c = next(glob, i++)) == EOL || c == ']') {
                                break;
                            }
                            if (c < last) {
                                throw new PatternSyntaxException("Invalid range", glob, i - 3);
                            }
                            regex.append(c);
                            hasRangeStart = false;
                        } else {
                            hasRangeStart = true;
                            last = c;
                        }
                    }
                    if (c != ']') {
                        throw new PatternSyntaxException("Missing ']", glob, i - 1);
                    }
                    regex.append("]]");
                }
                case '{' -> {
                    if (inGroup) {
                        throw new PatternSyntaxException("Cannot nest groups", glob, i - 1);
                    }
                    regex.append("(?:(?:");
                    inGroup = true;
                }
                case '}' -> {
                    if (inGroup) {
                        regex.append("))");
                        inGroup = false;
                    } else {
                        regex.append('}');
                    }
                }
                case ',' -> regex.append(inGroup ? ")|(?:" : ",");
                case '*' -> {
                    if (next(glob, i) == '*') {
                        regex.append(".*");
                        i++;
                    } else {
                        regex.append("[^/]*");
                    }
                }
                case '?' -> regex.append("[^/]");
                default -> {
                    if (REGEX_META.indexOf(c) >= 0) {
                        regex.append('\\');
                    }
                    regex.append(c);
                }
            }
        }
        if (inGroup) {
            throw new PatternSyntaxException("Missing '}", glob, i - 1);
        }
        return regex.append('$').toString();
    }
}

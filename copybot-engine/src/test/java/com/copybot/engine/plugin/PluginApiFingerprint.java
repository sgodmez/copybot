package com.copybot.engine.plugin;

import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * What plugins see of the engine: the plugin API version and one line per public or protected element (type, field,
 * constructor, method, with its signature) of the packages the engine module exports to everyone. The committed
 * copy, {@value #FILE}, is the reference that {@link #check} compares the current API with.
 *
 * @param version the plugin API version, X.Y
 * @param lines   the elements, sorted, without duplicates
 */
public record PluginApiFingerprint(String version, List<String> lines) {

    /** The committed fingerprint, from the engine module folder. */
    public static final String FILE = "src/test/resources/plugin-api.txt";
    /** The command that rewrites the committed fingerprint. */
    public static final String UPDATE_COMMAND = "mvn clean verify -pl copybot-engine -Dplugin.api.update=true";
    /** The same, whatever the comparison says: only after checking the diff it prints. */
    public static final String FORCE_COMMAND = "mvn clean verify -pl copybot-engine -Dplugin.api.update=force";

    private static final String VERSION_PREFIX = "api ";
    private static final Pattern VERSION = Pattern.compile("^(\\d+)\\.(\\d+)$");
    /** The elements a failure message lists at most. */
    private static final int SHOWN = 10;

    public PluginApiFingerprint {
        lines = List.copyOf(new TreeSet<>(lines));
    }

    /** The rewrite asked with -Dplugin.api.update: none, "true" (when the version fits) or "force" (always). */
    public enum Update {
        NONE, CHECKED, FORCE;

        public static Update of(String property) {
            if (property == null || property.isBlank() || property.equalsIgnoreCase("false")) {
                return NONE;
            }
            return switch (property.strip().toLowerCase(java.util.Locale.ROOT)) {
                case "true" -> CHECKED;
                case "force" -> FORCE;
                default -> throw new IllegalArgumentException("plugin.api.update is true or force, not " + property);
            };
        }

        /**
         * True when the file may be rewritten: forced, or asked and the verdict keeps the guard. A missing file
         * (status null) is never recreated unless forced: deleting it must not bypass the guard.
         */
        public boolean allows(Verdict.Status status) {
            return this == FORCE || (this == CHECKED && status != null && status.mayRewrite());
        }
    }

    /**
     * The verdict of the current API against the committed fingerprint.
     *
     * @param message what to do, null when up to date
     */
    public record Verdict(Status status, String message) {

        public enum Status {
            /** The file is the current API at the current version. */
            UP_TO_DATE,
            /** The version fits the changes (or was raised on purpose): the file must be regenerated. */
            REGENERATE,
            /** Only added elements: the minor must be raised. */
            RAISE_MINOR,
            /** Removed or changed elements: the major must be raised. */
            RAISE_MAJOR,
            /** The version is lower than the file's: an API version never goes back. */
            VERSION_LOWERED;

            /** True when rewriting the file keeps the guard: never to record a change without its version. */
            public boolean mayRewrite() {
                return this == UP_TO_DATE || this == REGENERATE;
            }
        }
    }

    /**
     * Compares the current API with the committed one. Removed or changed lines, and abstract members added to a
     * type that already existed (an old plugin implementing it lacks them), need a major above the file's; other
     * added lines need at least a minor above (a new major will do); a version raised without change is allowed.
     * Whenever the version or the lines differ from the file, the file must be regenerated, so that it always
     * records the API of its version (otherwise a later change would be compared with an older version).
     */
    public static Verdict check(PluginApiFingerprint file, PluginApiFingerprint current) {
        int[] was = parseVersion(file.version());
        int[] now = parseVersion(current.version());
        Set<String> removed = new TreeSet<>(file.lines());
        current.lines().forEach(removed::remove);
        Set<String> added = new TreeSet<>(current.lines());
        file.lines().forEach(added::remove);
        Set<String> existingTypes = file.lines().stream().map(l -> l.split(" ", 3))
                .filter(parts -> parts.length == 3 && parts[1].equals("type")).map(parts -> parts[0])
                .collect(Collectors.toSet());
        Set<String> breaking = new TreeSet<>(removed);
        added.stream().filter(l -> existingTypes.contains(l.substring(0, l.indexOf(' '))) && abstractMethod(l))
                .forEach(breaking::add);

        if (now[0] < was[0] || (now[0] == was[0] && now[1] < was[1])) {
            return new Verdict(Verdict.Status.VERSION_LOWERED, "copybot.plugin.api.version (" + current.version()
                    + ") is lower than the version of " + FILE + " (" + file.version()
                    + "): a plugin API version never goes back.");
        }
        if (!breaking.isEmpty() && now[0] <= was[0]) {
            String target = (was[0] + 1) + ".0";
            return new Verdict(Verdict.Status.RAISE_MAJOR, "The plugin API lost, changed or made abstract "
                    + breaking.size() + " element(s) since API " + file.version()
                    + ", plugins built against it may break (an added abstract member is a removed or changed line"
                    + " for the plugins implementing its type):" + listed(breaking)
                    + "\nRaise the major of copybot.plugin.api.version in the root pom.xml to "
                    + target + " (it is " + current.version() + "), then " + regenerate());
        }
        if (!added.isEmpty() && now[0] == was[0] && now[1] <= was[1]) {
            String target = was[0] + "." + (was[1] + 1);
            return new Verdict(Verdict.Status.RAISE_MINOR, "The plugin API gained " + added.size()
                    + " element(s) since API " + file.version() + ", a plugin using them needs a newer Copybot:"
                    + listed(added) + "\nRaise the minor of copybot.plugin.api.version in the root pom.xml to "
                    + target + " (it is " + current.version() + "), then " + regenerate());
        }
        if (removed.isEmpty() && added.isEmpty() && current.version().equals(file.version())) {
            return new Verdict(Verdict.Status.UP_TO_DATE, null);
        }
        return new Verdict(Verdict.Status.REGENERATE, "The plugin API " + current.version() + " ("
                + added.size() + " element(s) added, " + removed.size() + " removed or changed since API "
                + file.version() + ") is not the one recorded: " + regenerate());
    }

    /**
     * A method line a plugin must implement: "abstract" among its modifiers (an interface method without default,
     * an abstract method), except an annotation element with a default value (" default ..." after its parameters).
     */
    static boolean abstractMethod(String line) {
        int kind = line.indexOf(" method ");
        int open = line.indexOf('(');
        if (kind < 0 || open < 0 || kind > open) {
            return false;
        }
        boolean isAbstract = List.of(line.substring(kind + " method ".length(), open).split(" ")).contains("abstract");
        int close = line.indexOf(')', open);
        return isAbstract && !line.substring(close + 1).startsWith(" default ");
    }

    private static String regenerate() {
        return "regenerate " + FILE + " of copybot-engine with `" + UPDATE_COMMAND + "`, commit it, then run a full"
                + " `mvn clean verify` (the plugins record the new API version in their requires).";
    }

    /** The lines of the file that are gone ("- ") and the new ones ("+ "), one per line, empty when the same. */
    public static String diff(PluginApiFingerprint file, PluginApiFingerprint current) {
        StringBuilder diff = new StringBuilder();
        if (!file.version().equals(current.version())) {
            diff.append("- api ").append(file.version()).append("\n+ api ").append(current.version()).append('\n');
        }
        file.lines().stream().filter(l -> !current.lines().contains(l)).forEach(l -> diff.append("- ").append(l).append('\n'));
        current.lines().stream().filter(l -> !file.lines().contains(l)).forEach(l -> diff.append("+ ").append(l).append('\n'));
        return diff.toString();
    }

    private static String listed(Set<String> lines) {
        String shown = lines.stream().limit(SHOWN).map(l -> "\n  " + l).collect(Collectors.joining());
        return lines.size() > SHOWN ? shown + "\n  ... and " + (lines.size() - SHOWN) + " more" : shown;
    }

    /** {major, minor} of an X.Y version. */
    static int[] parseVersion(String version) {
        Matcher m = version == null ? null : VERSION.matcher(version);
        if (m == null || !m.matches()) {
            throw new IllegalArgumentException("The plugin API version must be X.Y, not " + version);
        }
        return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
    }

    // ---- the file ----

    /** The file content: "api X.Y", then the lines, each ending with a newline. */
    public String text() {
        StringBuilder text = new StringBuilder(VERSION_PREFIX).append(version).append('\n');
        lines.forEach(l -> text.append(l).append('\n'));
        return text.toString();
    }

    public static PluginApiFingerprint parse(String text) {
        List<String> all = text.lines().filter(l -> !l.isBlank()).toList();
        if (all.isEmpty() || !all.getFirst().startsWith(VERSION_PREFIX)) {
            throw new IllegalArgumentException("A plugin API fingerprint starts with \"" + VERSION_PREFIX + "X.Y\"");
        }
        return new PluginApiFingerprint(all.getFirst().substring(VERSION_PREFIX.length()).strip(),
                all.subList(1, all.size()));
    }

    // ---- the current API ----

    /**
     * The API of the module that holds this class, read from the classes of its own location (the main classes
     * only, not the test classes patched into the module): the packages it exports without restriction.
     */
    public static PluginApiFingerprint of(Class<?> anchor, String version) throws IOException {
        Path location;
        try {
            location = Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }
        ModuleReference reference = ModuleFinder.of(location).findAll().stream().findFirst()
                .orElseThrow(() -> new IOException("No module at " + location));
        Set<String> packages = reference.descriptor().exports().stream()
                .filter(e -> !e.isQualified()).map(ModuleDescriptor.Exports::source).collect(Collectors.toSet());

        List<String> lines = new ArrayList<>();
        try (ModuleReader reader = reference.open(); Stream<String> entries = reader.list()) {
            for (String entry : entries.filter(e -> e.endsWith(".class") && !e.endsWith("module-info.class")).toList()) {
                String className = entry.substring(0, entry.length() - ".class".length()).replace('/', '.');
                int dot = className.lastIndexOf('.');
                if (dot < 0 || !packages.contains(className.substring(0, dot))) {
                    continue;
                }
                Class<?> type;
                try {
                    type = Class.forName(className, false, anchor.getClassLoader());
                } catch (ClassNotFoundException e) {
                    throw new IOException(e);
                }
                if (visible(type)) {
                    lines.addAll(describe(type));
                }
            }
        }
        return new PluginApiFingerprint(version, lines);
    }

    /** A type a plugin can name: public or protected, as are the types around it; neither local nor anonymous. */
    private static boolean visible(Class<?> type) {
        for (Class<?> t = type; t != null; t = t.getDeclaringClass()) {
            if (t.isSynthetic() || t.isAnonymousClass() || t.isLocalClass() || !exposed(t.getModifiers())) {
                return false;
            }
        }
        return true;
    }

    private static boolean exposed(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }

    /** "type.Name kind text" lines of a type and of its exposed members, the name of the type left out of them. */
    private static List<String> describe(Class<?> type) {
        String name = type.getTypeName();
        String prefix = type.getName() + " ";
        List<String> lines = new ArrayList<>();

        StringBuilder header = new StringBuilder(type.toGenericString());
        Type superclass = type.getGenericSuperclass();
        if (superclass != null && superclass != Object.class) {
            header.append(" extends ").append(superclass.getTypeName());
        }
        Type[] interfaces = type.getGenericInterfaces();
        if (interfaces.length > 0) {
            header.append(type.isInterface() ? " extends " : " implements ").append(
                    Arrays.stream(interfaces).map(Type::getTypeName).collect(Collectors.joining(", ")));
        }
        lines.add(prefix + "type " + header);

        for (Field field : type.getDeclaredFields()) {
            if (shown(field)) {
                lines.add(prefix + "field " + field.toGenericString().replace(" " + name + "." + field.getName(),
                        " " + field.getName()));
            }
        }
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (shown(constructor)) {
                lines.add(prefix + "constructor " + constructor.toGenericString().replace(" " + name + "(", " <init>("));
            }
        }
        for (Method method : type.getDeclaredMethods()) {
            if (shown(method) && !method.isBridge()) {
                Object defaultValue = method.getDefaultValue(); // an annotation element with a default
                lines.add(prefix + "method " + method.toGenericString().replace(" " + name + "." + method.getName() + "(",
                        " " + method.getName() + "(") + (defaultValue == null ? "" : " default " + valueText(defaultValue)));
            }
        }
        return lines;
    }

    /** The default value of an annotation element, readable and stable (arrays by content, strings quoted). */
    private static String valueText(Object value) {
        if (value instanceof String s) {
            return "\"" + s + "\"";
        }
        if (value instanceof Class<?> c) {
            return c.getTypeName() + ".class";
        }
        if (value.getClass().isArray()) {
            List<String> items = new ArrayList<>();
            for (int i = 0; i < java.lang.reflect.Array.getLength(value); i++) {
                items.add(valueText(java.lang.reflect.Array.get(value, i)));
            }
            return "{" + String.join(", ", items) + "}";
        }
        return String.valueOf(value);
    }

    private static boolean shown(Member member) {
        return !member.isSynthetic() && exposed(member.getModifiers());
    }
}

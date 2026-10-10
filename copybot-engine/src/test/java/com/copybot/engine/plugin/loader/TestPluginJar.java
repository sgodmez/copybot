package com.copybot.engine.plugin.loader;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.spi.ToolProvider;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Builds a real module for the plugin loader tests: compiled by javac against the engine module, so that the
 * loader resolves it as it resolves a shipped plugin. A plugin module provides IPlugin (code: the module name,
 * one ANALYZE action "act"); a library module only exports a package, or is an old style jar without
 * module-info ({@link #automatic}). Written as a jar of a plugin directory
 * ({@link #writeTo}) or as a development directory ({@link #writeDevDir}).
 */
public final class TestPluginJar {

    private static Path testRoot;

    private final String moduleName;
    private final String packageName;
    private boolean plugin;
    private boolean automatic;
    private String version;
    private boolean failingConstructor;
    private boolean failingActions;
    private boolean missingI18nBundle;
    private final List<String> requires = new ArrayList<>();
    private final List<Path> compileAgainst = new ArrayList<>();

    private TestPluginJar(String moduleName, String packageName) {
        this.moduleName = moduleName;
        this.packageName = packageName;
    }

    /**
     * A fresh directory for a test that loads plugins, under one root per JVM. Never deleted (no @TempDir): the
     * JVM keeps the jars of a module layer open (layers cannot be unloaded), and Windows refuses to delete an
     * open file. The OS temp cleanup takes care of it.
     */
    public static synchronized Path newRoot(String prefix) {
        try {
            if (testRoot == null) {
                testRoot = Files.createTempDirectory("copybot-test-plugins");
            }
            return Files.createTempDirectory(testRoot, prefix);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A plugin module, its classes in a package named after the module. */
    public static TestPluginJar plugin(String moduleName) {
        TestPluginJar jar = new TestPluginJar(moduleName, moduleName);
        jar.plugin = true;
        return jar;
    }

    /** A library module exporting this package (two libraries may share one, to make a layer fail). */
    public static TestPluginJar library(String moduleName, String packageName) {
        return new TestPluginJar(moduleName, packageName);
    }

    public TestPluginJar version(String version) {
        this.version = version;
        return this;
    }

    /**
     * A library without module-info: the loader sees an automatic module, its name and version taken from the jar
     * name ({@code <module>-<version>.jar}).
     */
    public TestPluginJar automatic() {
        this.automatic = true;
        return this;
    }

    /** The plugin constructor throws: its service provider cannot be instantiated. */
    public TestPluginJar failingConstructor() {
        this.failingConstructor = true;
        return this;
    }

    /** The plugin loads, but listing its actions throws. */
    public TestPluginJar failingActions() {
        this.failingActions = true;
        return this;
    }

    /** The plugin declares an i18n bundle, "&lt;module&gt;.missing", that the jar does not hold. */
    public TestPluginJar missingI18nBundle() {
        this.missingI18nBundle = true;
        return this;
    }

    /**
     * Requires this module, compiled against these jars or directories (which the test may leave out of the
     * plugin folder).
     */
    public TestPluginJar requires(String module, Path... jars) {
        requires.add(module);
        compileAgainst.addAll(List.of(jars));
        return this;
    }

    /** Compiles and jars the module into this directory, created if needed; returns the jar. */
    public Path writeTo(Path dir) {
        try {
            Files.createDirectories(dir);
            Path classes = compile();
            Path jarFile = dir.resolve(moduleName + (version != null ? "-" + version : "") + ".jar");
            List<String> jar = new ArrayList<>(List.of("--create", "--file", jarFile.toString()));
            if (version != null && !automatic) {
                jar.addAll(List.of("--module-version", version));
            }
            jar.addAll(List.of("-C", classes.toString(), "."));
            run("jar", jar);
            return jarFile;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Compiles the module as a development plugin directory: {@code devDir/classes} (and an empty
     * {@code devDir/lib}), like a Maven target directory; returns {@code devDir}.
     */
    public Path writeDevDir(Path devDir) {
        try {
            Path classes = compile();
            Path target = Files.createDirectories(devDir.resolve("classes"));
            Files.createDirectories(devDir.resolve("lib"));
            try (Stream<Path> files = Files.walk(classes)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    Path copy = target.resolve(classes.relativize(file).toString());
                    Files.createDirectories(copy.getParent());
                    Files.copy(file, copy);
                }
            }
            return devDir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Compiles the module in a work directory of its own (never next to the plugins); returns its classes. */
    private Path compile() throws IOException {
        Path work = Files.createTempDirectory(Files.createDirectories(Path.of("target", "plugin-loader-build")), moduleName);
        Path src = work.resolve("src");
        Path classes = work.resolve("classes");
        Path pkgDir = src.resolve(packageName.replace('.', '/'));
        Files.createDirectories(pkgDir);

        List<Path> sources = new ArrayList<>();
        if (!automatic) {
            sources.add(write(src.resolve("module-info.java"), moduleInfo()));
        }
        if (plugin) {
            sources.add(write(pkgDir.resolve("TestPlugin.java"), pluginClass()));
            sources.add(write(pkgDir.resolve("TestAction.java"), actionClass()));
        } else {
            sources.add(write(pkgDir.resolve("Lib.java"), "package " + packageName + "; public class Lib {}"));
        }

        List<String> javac = new ArrayList<>(List.of("-d", classes.toString(), "--module-path", modulePath()));
        if (version != null && !automatic) {
            javac.addAll(List.of("--module-version", version));
        }
        sources.forEach(s -> javac.add(s.toString()));
        run("javac", javac);

        if (plugin) {
            Path bundle = classes.resolve(packageName.replace('.', '/')).resolve("i18n/pluginBundle.properties");
            Files.createDirectories(bundle.getParent());
            Files.writeString(bundle, "plugin.test.name=Test\n");
        }
        return classes;
    }

    private String moduleInfo() {
        StringBuilder sb = new StringBuilder("module ").append(moduleName).append(" {\n");
        if (plugin) {
            sb.append("  requires com.copybot.engine;\n");
        }
        requires.forEach(r -> sb.append("  requires ").append(r).append(";\n"));
        sb.append("  exports ").append(packageName).append(";\n");
        if (plugin) {
            sb.append("  opens ").append(packageName).append(".i18n;\n");
            sb.append("  provides com.copybot.plugin.api.definition.IPlugin with ")
                    .append(packageName).append(".TestPlugin;\n");
        }
        return sb.append("}\n").toString();
    }

    private String pluginClass() {
        String actions = failingActions
                ? "throw new IllegalStateException(\"no actions\");"
                : "return java.util.List.of(new com.copybot.plugin.api.action.ActionDefinition(\"act\", TestAction.class, true));";
        String bundles = missingI18nBundle
                ? "  @Override public Iterable<String> getI18nBundleNames() { return java.util.List.of(\"" + moduleName + ".missing\"); }\n"
                : "";
        return "package " + packageName + ";\n"
                + "public class TestPlugin extends com.copybot.plugin.api.definition.AbstractPlugin {\n"
                + "  public TestPlugin() {" + (failingConstructor ? " throw new IllegalStateException(\"boom\"); " : "") + "}\n"
                + "  @Override public String getPluginCode() { return \"" + moduleName + "\"; }\n"
                + bundles
                + "  @Override @SuppressWarnings({\"rawtypes\", \"unchecked\"}) public java.util.List getAnalyzeActions() { " + actions + " }\n"
                + "}\n";
    }

    private String actionClass() {
        return "package " + packageName + ";\n"
                + "public class TestAction extends com.copybot.plugin.api.action.AbstractAction\n"
                + "    implements com.copybot.plugin.api.action.IAnalyzeAction {\n"
                + "  @Override public void doAnalyze(com.copybot.plugin.api.action.WorkItem item) { }\n"
                + "}\n";
    }

    /** The engine module and its dependencies, as the running tests see them, plus the extra jars. */
    private String modulePath() {
        Stream<String> runtime = Stream.of(System.getProperty("jdk.module.path", ""), System.getProperty("java.class.path", ""))
                .flatMap(p -> Stream.of(p.split(java.io.File.pathSeparator)))
                .filter(p -> !p.isBlank())
                .filter(p -> p.endsWith(".jar") || Files.exists(Path.of(p, "module-info.class")));
        return Stream.concat(runtime, compileAgainst.stream().map(Path::toString))
                .distinct()
                .collect(Collectors.joining(java.io.File.pathSeparator));
    }

    private static Path write(Path file, String content) throws IOException {
        Files.writeString(file, content);
        return file;
    }

    private static void run(String tool, List<String> args) {
        StringWriter out = new StringWriter();
        PrintWriter pw = new PrintWriter(out);
        int code = ToolProvider.findFirst(tool).orElseThrow().run(pw, pw, args.toArray(String[]::new));
        if (code != 0) {
            throw new IllegalStateException(tool + " failed (" + code + "): " + out);
        }
    }
}

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
 * Builds a real module jar for the plugin loader tests: compiled by javac against the engine module, so that
 * the loader resolves it as it resolves a shipped plugin. A plugin module provides IPlugin; a library module
 * only exports a package.
 */
final class TestPluginJar {

    private final String moduleName;
    private final String packageName;
    private boolean plugin;
    private String version;
    private boolean failingConstructor;
    private final List<String> requires = new ArrayList<>();
    private final List<Path> compileAgainst = new ArrayList<>();

    private TestPluginJar(String moduleName, String packageName) {
        this.moduleName = moduleName;
        this.packageName = packageName;
    }

    /** A plugin module, its classes in a package named after the module. */
    static TestPluginJar plugin(String moduleName) {
        TestPluginJar jar = new TestPluginJar(moduleName, moduleName);
        jar.plugin = true;
        return jar;
    }

    /** A library module exporting this package (two libraries may share one, to make a layer fail). */
    static TestPluginJar library(String moduleName, String packageName) {
        return new TestPluginJar(moduleName, packageName);
    }

    TestPluginJar version(String version) {
        this.version = version;
        return this;
    }

    /** The plugin constructor throws: its service provider cannot be instantiated. */
    TestPluginJar failingConstructor() {
        this.failingConstructor = true;
        return this;
    }

    /** Requires this module, compiled against these jars (which the test may leave out of the plugin folder). */
    TestPluginJar requires(String module, Path... jars) {
        requires.add(module);
        compileAgainst.addAll(List.of(jars));
        return this;
    }

    /** Compiles and jars the module into this directory, created if needed; returns the jar. */
    Path writeTo(Path dir) {
        try {
            Files.createDirectories(dir);
            Path work = Files.createTempDirectory(Files.createDirectories(Path.of("target", "plugin-loader-build")), moduleName);
            Path src = work.resolve("src");
            Path classes = work.resolve("classes");
            Path pkgDir = src.resolve(packageName.replace('.', '/'));
            Files.createDirectories(pkgDir);

            List<Path> sources = new ArrayList<>();
            sources.add(write(src.resolve("module-info.java"), moduleInfo()));
            if (plugin) {
                sources.add(write(pkgDir.resolve("TestPlugin.java"), pluginClass()));
            } else {
                sources.add(write(pkgDir.resolve("Lib.java"), "package " + packageName + "; public class Lib {}"));
            }

            List<String> javac = new ArrayList<>(List.of("-d", classes.toString(), "--module-path", modulePath()));
            if (version != null) {
                javac.addAll(List.of("--module-version", version));
            }
            sources.forEach(s -> javac.add(s.toString()));
            run("javac", javac);

            if (plugin) {
                Path bundle = classes.resolve(packageName.replace('.', '/')).resolve("i18n/pluginBundle.properties");
                Files.createDirectories(bundle.getParent());
                Files.writeString(bundle, "plugin.test.name=Test\n");
            }

            Path jarFile = dir.resolve(moduleName + (version != null ? "-" + version : "") + ".jar");
            List<String> jar = new ArrayList<>(List.of("--create", "--file", jarFile.toString()));
            if (version != null) {
                jar.addAll(List.of("--module-version", version));
            }
            jar.addAll(List.of("-C", classes.toString(), "."));
            run("jar", jar);
            return jarFile;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
        return "package " + packageName + ";\n"
                + "public class TestPlugin extends com.copybot.plugin.api.definition.AbstractPlugin {\n"
                + "  public TestPlugin() {" + (failingConstructor ? " throw new IllegalStateException(\"boom\"); " : "") + "}\n"
                + "  @Override public String getPluginCode() { return \"" + moduleName + "\"; }\n"
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

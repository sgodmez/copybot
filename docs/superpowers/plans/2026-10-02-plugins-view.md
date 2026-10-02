# Plugins View Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A read-only diagnostic window of the loaded plugins (status, modules, dependencies, actions, causes of failure) and a "plugin folder" preference that rewrites `pluginPath` in `config.json`.

**Architecture:** The plugin loader records, per plugin directory, its source, its modules, its parent plugins and its unresolved requirements on `PluginDefinition`; load failures that used to abort the whole load become per-plugin errors. `PluginReport` (immutable records, engine side) is built from those definitions by `CopybotEngine.pluginReport()`. The JavaFX UI shows it in a non-modal window (menu Tools → Plugins…) and edits `pluginPath` through `ConfigFiles` (engine side, testable without JavaFX).

**Tech Stack:** Java 25, JPMS (`ModuleFinder`, `ModuleLayer`, `ServiceLoader`), Gson, JavaFX/FXML, JUnit Jupiter 6, Maven.

**Spec:** `docs/superpowers/specs/2026-10-02-plugins-view-design.md`

## Global Constraints

- Build with JDK 25 (`C:\Program Files\Eclipse Adoptium\jdk-25*`): set `JAVA_HOME` before `mvn`; the `java` of the PATH may be older.
- Run Maven from the repository root, e.g. `mvn -q -pl copybot-engine test -Dtest=PluginReportTest`; the UI module needs the engine installed or built in the same reactor: `mvn -q -pl copybot-engine,copybot-ui -am test`.
- Line endings: keep each file's existing ones (`file <path>` shows "with CRLF line terminators" or not). New Java files: CRLF in `copybot-engine` (its sources are CRLF), match neighbours elsewhere. Never let a tool rewrite a whole file's endings.
- Engine bundles (`copybot-engine/src/main/resources/com/copybot/engine/i18n/engineBundle.properties`, `engineBundle_fr.properties`) are ASCII with `\uXXXX` escapes for non-ASCII; UI bundles (`copybot-ui/src/main/resources/com/copybot/ui/i18n/uiBundle*.properties`, en/fr/it) are UTF-8 with literal accents. In MessageFormat strings a single quote is written `''`.
- Messages stored in the report are already translated (language of the startup).
- Comments and identifiers in English, like the surrounding code; Javadoc on public types and non-obvious methods only, in the existing terse style.
- `PluginEngine.load` stays once per JVM; tests needing a real load use `PluginLoader` directly (an instance), never the static engine, except where noted.
- Commit after each task with a message in the repository's style (imperative sentence, no prefix) ending with the line `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.

## Review Focus

1. A plugin folder holding a corrupt jar (`FindException`) must not prevent the other plugins from loading — Task 2 test `aCorruptJarIsAnErrorOfItsPluginOnly`.
2. A plugin whose constructor throws must not hide the plugins iterated after it — Task 2 test `aPluginWhoseConstructorThrowsIsAnErrorOfItsOwn`.
3. A configured `devPluginPaths` or `pluginPath` that does not exist must be reported, not silently ignored (the bug that started this) — Task 3 test `missingDirectoriesAreWarned`.
4. Rewriting `pluginPath` must not lose the other keys of `config.json` (`devPluginPaths`, `resources`, `resourceGroups`) nor turn a CRLF file into LF — Task 4 tests `otherKeysArePreserved`, `crlfIsKept`.
5. A `config.json` the UI cannot write (read-only) must fail loudly and leave the file untouched — Task 4 test `aReadOnlyFileIsRefusedAndLeftUntouched`.

---

## File Structure

Engine (`copybot-engine/src/main/java/...`):
- Modify `com/copybot/engine/plugin/loader/LayerLoader.java` — remembers its source (dev or not), its module references, its parent loaders; catches `FindException`; `load` reports resolution failures.
- Modify `com/copybot/engine/plugin/loader/PluginLoader.java` — passes the source, catches load and instantiation failures per plugin, instantiates per layer, marks ignored plugins.
- Modify `com/copybot/engine/plugin/PluginDefinition.java` — new load details (source, ignored, modules, dependencies, missing requires).
- Modify `com/copybot/engine/plugin/PluginEngine.java` — keeps every definition (loaded + errors) in load order; exposes the directories of the load.
- Create `com/copybot/engine/plugin/report/PluginReport.java`, `PluginEntry.java`, `ModuleEntry.java`, `ActionEntry.java`, `PluginSource.java`, `PluginStatus.java` — the immutable report and `toText()`.
- Create `com/copybot/engine/plugin/report/PluginReports.java` — builds a report from definitions (status, ordering, actions).
- Modify `com/copybot/engine/CopybotEngine.java` — keeps the config file; `pluginReport()`.
- Create `com/copybot/config/ConfigFiles.java` — read / rewrite `pluginPath`.
- Modify `module-info.java` — exports `com.copybot.config` and `com.copybot.engine.plugin.report` to `com.copybot.ui`.
- Modify engine bundles (en, fr).

Engine tests (`copybot-engine/src/test/java/...`):
- Create `com/copybot/engine/plugin/loader/PluginFixtures.java` — compiles tiny plugin modules into a temp dir at test time.
- Create `com/copybot/engine/plugin/loader/PluginLoaderDetailsTest.java`, `PluginLoaderRobustnessTest.java`.
- Create `com/copybot/engine/plugin/report/PluginReportsTest.java`, `PluginReportTextTest.java`.
- Create `com/copybot/config/ConfigFilesTest.java`.

UI (`copybot-ui/src/main/...`):
- Create `java/com/copybot/ui/PluginsController.java`, `resources/com/copybot/ui/views/plugins-view.fxml`.
- Modify `resources/com/copybot/ui/views/main-view.fxml`, `java/com/copybot/ui/MainController.java` — menu Tools → Plugins….
- Modify `java/com/copybot/ui/CopybotMainUi.java` — keeps `HostServices` (to open a folder).
- Modify `java/com/copybot/ui/PreferencesController.java`, `resources/com/copybot/ui/views/preferences-view.fxml` — plugin folder row.
- Modify UI bundles (en, fr, it).

---

### Task 1: Load details on PluginDefinition (+ test fixtures)

**Files:**
- Create: `copybot-engine/src/test/java/com/copybot/engine/plugin/loader/PluginFixtures.java`
- Create: `copybot-engine/src/test/java/com/copybot/engine/plugin/loader/PluginLoaderDetailsTest.java`
- Create: `copybot-engine/src/main/java/com/copybot/engine/plugin/report/PluginSource.java`
- Create: `copybot-engine/src/main/java/com/copybot/engine/plugin/report/ModuleEntry.java`
- Modify: `copybot-engine/src/main/java/com/copybot/engine/plugin/loader/LayerLoader.java`
- Modify: `copybot-engine/src/main/java/com/copybot/engine/plugin/loader/PluginLoader.java`
- Modify: `copybot-engine/src/main/java/com/copybot/engine/plugin/PluginDefinition.java`
- Possibly modify: `copybot-engine/pom.xml` (surefire `argLine`, see Step 2)

**Interfaces:**
- Produces:
  - `enum PluginSource { PLUGIN_PATH, DEV, EMBEDDED }` (package `com.copybot.engine.plugin.report`)
  - `record ModuleEntry(String name, String version, Path location, boolean main, boolean automatic)` — `version` null when the module has none; `location` null when unknown.
  - `PluginDefinition` getters: `PluginSource getSource()`, `boolean isIgnored()`, `List<ModuleEntry> getModules()`, `List<String> getPluginDependencies()` (`"name version"`, or `"name"` without version), `List<String> getMissingRequires()` (`"module:version"` or `"module"`). Lists never null (empty).
  - `LayerLoader.isDev()`, `LayerLoader.getModuleEntries()`, `LayerLoader.getParentLoaders()`.
  - Test helper `PluginFixtures` (see Step 1), reused by Tasks 2 and 3.

- [ ] **Step 1: Write the fixture helper**

`PluginFixtures` compiles a plugin module with the in-process `javac` and `jar` tools (`java.util.spi.ToolProvider`, in `java.base`), against the engine classes (the module path entry holding `com.copybot.engine`).

```java
package com.copybot.engine.plugin.loader;

import com.copybot.plugin.api.definition.IPlugin;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.spi.ToolProvider;

/**
 * Tiny plugin modules compiled at test time: module "fixture.&lt;name&gt;", one IPlugin class whose code is
 * "fixture-&lt;name&gt;", with one ANALYZE action "act" unless told otherwise.
 */
final class PluginFixtures {

    private PluginFixtures() {
    }

    /** What a fixture plugin is made of. */
    record Spec(String name, String version, List<String> requires, String constructorBody, boolean actionsThrow) {

        static Spec of(String name, String version) {
            return new Spec(name, version, List.of(), "", false);
        }

        Spec requiring(String... moduleNames) {
            return new Spec(name, version, List.of(moduleNames), constructorBody, actionsThrow);
        }

        Spec throwingInConstructor() {
            return new Spec(name, version, requires, "throw new IllegalStateException(\"boom\");", actionsThrow);
        }

        Spec throwingOnActions() {
            return new Spec(name, version, requires, constructorBody, true);
        }

        String module() {
            return "fixture." + name;
        }
    }

    /**
     * Compiles the fixture into a jar in {@code pluginDir} (created), as a plugin directory of the plugin path
     * expects it. The module path of the compilation holds the engine and {@code extraModulePath} (other
     * fixtures' directories, for a plugin requiring another one).
     */
    static Path jar(Path pluginDir, Spec spec, Path... extraModulePath) throws IOException {
        Path classes = compile(pluginDir.resolveSibling(pluginDir.getFileName() + "-build"), spec, extraModulePath);
        Files.createDirectories(pluginDir);
        Path jar = pluginDir.resolve(spec.module() + ".jar");
        run("jar", "--create", "--file", jar.toString(), "--module-version", spec.version(), "-C", classes.toString(), ".");
        return jar;
    }

    /**
     * Compiles the fixture as a dev plugin directory: {@code devDir/classes} (and an empty {@code devDir/lib}),
     * like a Maven target directory.
     */
    static Path devDir(Path devDir, Spec spec) throws IOException {
        Path classes = compile(devDir.resolveSibling(devDir.getFileName() + "-build"), spec);
        Path target = Files.createDirectories(devDir.resolve("classes"));
        Files.createDirectories(devDir.resolve("lib"));
        try (var files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path copy = target.resolve(classes.relativize(file).toString());
                Files.createDirectories(copy.getParent());
                Files.copy(file, copy);
            }
        }
        return devDir;
    }

    private static Path compile(Path buildDir, Spec spec, Path... extraModulePath) throws IOException {
        Path src = buildDir.resolve("src");
        Path out = buildDir.resolve("classes");
        String pkg = "fixture." + spec.name();
        Path pkgDir = Files.createDirectories(src.resolve(pkg.replace('.', '/')));
        StringBuilder requires = new StringBuilder("    requires com.copybot.engine;\n");
        for (String r : spec.requires()) {
            requires.append("    requires ").append(r).append(";\n");
        }
        Files.writeString(src.resolve("module-info.java"), """
                module %s {
                %s    exports %s;
                    provides com.copybot.plugin.api.definition.IPlugin with %s.FixturePlugin;
                }
                """.formatted(spec.module(), requires, pkg, pkg));
        String actions = spec.actionsThrow()
                ? "throw new IllegalStateException(\"no actions\");"
                : "return java.util.List.of(new com.copybot.plugin.api.action.ActionDefinition(\"act\", FixtureAction.class, true));";
        Files.writeString(pkgDir.resolve("FixturePlugin.java"), """
                package %s;

                public class FixturePlugin extends com.copybot.plugin.api.definition.AbstractPlugin {
                    public FixturePlugin() {
                        %s
                    }

                    @Override
                    public String getPluginCode() {
                        return "fixture-%s";
                    }

                    @Override
                    @SuppressWarnings({"rawtypes", "unchecked"})
                    public java.util.List getAnalyzeActions() {
                        %s
                    }
                }
                """.formatted(pkg, spec.constructorBody(), spec.name(), actions));
        Files.writeString(pkgDir.resolve("FixtureAction.java"), """
                package %s;

                public class FixtureAction extends com.copybot.plugin.api.action.AbstractAction
                        implements com.copybot.plugin.api.action.IAnalyzeAction {
                }
                """.formatted(pkg));
        List<String> modulePath = new ArrayList<>();
        modulePath.add(engineLocation().toString());
        for (Path p : extraModulePath) {
            modulePath.add(p.toString());
        }
        List<String> args = new ArrayList<>(List.of("-d", out.toString(),
                "--module-path", String.join(java.io.File.pathSeparator, modulePath),
                "--module-version", spec.version()));
        try (var files = Files.walk(src)) {
            files.filter(f -> f.toString().endsWith(".java")).forEach(f -> args.add(f.toString()));
        }
        run("javac", args.toArray(new String[0]));
        return out;
    }

    /** Where the engine classes are: target/classes (or the engine jar). */
    static Path engineLocation() {
        try {
            return Path.of(IPlugin.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void run(String tool, String... args) {
        ToolProvider provider = ToolProvider.findFirst(tool)
                .orElseThrow(() -> new IllegalStateException(tool + " tool not found: run the tests on a JDK with jdk.compiler / jdk.jartool"));
        StringWriter out = new StringWriter();
        PrintWriter writer = new PrintWriter(out);
        int code = provider.run(writer, writer, args);
        writer.flush();
        if (code != 0) {
            throw new IllegalStateException(tool + " failed (" + code + "):\n" + out);
        }
    }
}
```

`AbstractAction` and `IAnalyzeAction` are the engine's `com.copybot.plugin.api.action` types; check `AbstractAction` has no abstract method the fixture would have to implement (`Read` `copybot-engine/src/main/java/com/copybot/plugin/api/action/AbstractAction.java` and `IAction.java`), and add the missing overrides to `FixtureAction` if it has (returning neutral values). Likewise check `AbstractPlugin` (`com/copybot/plugin/api/definition/AbstractPlugin.java`) for abstract methods beyond `getPluginCode`.

- [ ] **Step 2: Write a fixture smoke test and make the toolchain work**

In `PluginLoaderDetailsTest` (package `com.copybot.engine.plugin.loader`):

```java
@TempDir
Path tempDir;

@Test
public void aFixturePluginLoads() throws Exception {
    Path pluginPath = Files.createDirectories(tempDir.resolve("plugins"));
    PluginFixtures.jar(pluginPath.resolve("alpha"), PluginFixtures.Spec.of("alpha", "1.0.0"));

    PluginLoader loader = new PluginLoader();
    loader.resolve(FileUtil.listDirectory(pluginPath), false);
    List<PluginDefinition> all = loader.load();

    PluginDefinition alpha = byName(all, "fixture.alpha");
    assertTrue(alpha.isActive(), String.valueOf(alpha.getErrorMessage()));
    assertEquals("1.0.0", alpha.getVersion());
}

static PluginDefinition byName(List<PluginDefinition> all, String name) {
    return all.stream().filter(p -> p.getName().equals(name)).findFirst()
            .orElseThrow(() -> new AssertionError(name + " not in " + all.stream().map(PluginDefinition::getName).toList()));
}
```

Run: `mvn -q -pl copybot-engine test -Dtest=PluginLoaderDetailsTest`
Expected: PASS (the loader already loads such a plugin). If it fails with "javac tool not found", add to `copybot-engine/pom.xml` a surefire configuration in `<build><plugins>` (create `<build>` if absent):

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <!-- PluginFixtures compiles plugin modules at test time -->
        <argLine>--add-modules jdk.compiler,jdk.jartool</argLine>
    </configuration>
</plugin>
```

If compilation fails on `AbstractAction`/`AbstractPlugin` abstract methods, fix the fixture sources (Step 1 note). Do not continue before this test passes.

- [ ] **Step 3: Write the failing details tests**

Add to `PluginLoaderDetailsTest`:

```java
@Test
public void aLoadedPluginKnowsItsSourceAndModules() throws Exception {
    Path pluginPath = Files.createDirectories(tempDir.resolve("plugins"));
    Path jar = PluginFixtures.jar(pluginPath.resolve("alpha"), PluginFixtures.Spec.of("alpha", "1.0.0"));

    PluginLoader loader = new PluginLoader();
    loader.resolve(FileUtil.listDirectory(pluginPath), false);
    PluginDefinition alpha = byName(loader.load(), "fixture.alpha");

    assertEquals(PluginSource.PLUGIN_PATH, alpha.getSource());
    assertFalse(alpha.isIgnored());
    assertEquals(List.of(new ModuleEntry("fixture.alpha", "1.0.0", jar.toAbsolutePath().normalize(), true, false)),
            alpha.getModules());
    assertEquals(List.of(), alpha.getPluginDependencies());
    assertEquals(List.of(), alpha.getMissingRequires());
}

@Test
public void aDevPluginIsMarkedDev() throws Exception {
    Path dev = PluginFixtures.devDir(tempDir.resolve("dev-target"), PluginFixtures.Spec.of("devplug", "2.0.0"));

    PluginLoader loader = new PluginLoader();
    loader.resolve(List.of(dev), true);
    PluginDefinition devplug = byName(loader.load(), "fixture.devplug");

    assertTrue(devplug.isActive(), String.valueOf(devplug.getErrorMessage()));
    assertEquals(PluginSource.DEV, devplug.getSource());
    assertEquals(dev.resolve("classes").toAbsolutePath().normalize(), devplug.getModules().getFirst().location());
}

@Test
public void aPluginKnowsThePluginsItDependsOn() throws Exception {
    Path pluginPath = Files.createDirectories(tempDir.resolve("plugins"));
    PluginFixtures.jar(pluginPath.resolve("base"), PluginFixtures.Spec.of("base", "1.0.0"));
    PluginFixtures.jar(pluginPath.resolve("top"), PluginFixtures.Spec.of("top", "1.0.0").requiring("fixture.base"),
            pluginPath.resolve("base"));

    PluginLoader loader = new PluginLoader();
    loader.resolve(FileUtil.listDirectory(pluginPath), false);
    PluginDefinition top = byName(loader.load(), "fixture.top");

    assertTrue(top.isActive(), String.valueOf(top.getErrorMessage()));
    assertEquals(List.of("fixture.base 1.0.0"), top.getPluginDependencies());
}

@Test
public void aPluginWithAMissingDependencyListsIt() throws Exception {
    Path build = Files.createDirectories(tempDir.resolve("elsewhere"));
    PluginFixtures.jar(build.resolve("base"), PluginFixtures.Spec.of("base", "1.0.0"));
    Path pluginPath = Files.createDirectories(tempDir.resolve("plugins"));
    PluginFixtures.jar(pluginPath.resolve("top"), PluginFixtures.Spec.of("top", "1.0.0").requiring("fixture.base"),
            build.resolve("base")); // compiled against base, which the plugin path does not hold

    PluginLoader loader = new PluginLoader();
    loader.resolve(FileUtil.listDirectory(pluginPath), false);
    PluginDefinition top = byName(loader.load(), "fixture.top");

    assertFalse(top.isActive());
    assertFalse(top.isIgnored());
    assertEquals(List.of("fixture.base:1.0.0"), top.getMissingRequires());
}

@Test
public void anOlderRevisionAndADuplicateAreIgnored() throws Exception {
    Path pluginPath = Files.createDirectories(tempDir.resolve("plugins"));
    PluginFixtures.jar(pluginPath.resolve("a-old"), PluginFixtures.Spec.of("same", "1.0.0"));
    PluginFixtures.jar(pluginPath.resolve("b-new"), PluginFixtures.Spec.of("same", "1.0.1"));
    PluginFixtures.jar(pluginPath.resolve("c-dup"), PluginFixtures.Spec.of("same", "1.0.1"));

    PluginLoader loader = new PluginLoader();
    loader.resolve(FileUtil.listDirectory(pluginPath), false);
    List<PluginDefinition> same = loader.load().stream().filter(p -> p.getName().equals("fixture.same")).toList();

    assertEquals(1, same.stream().filter(PluginDefinition::isActive).count());
    assertEquals(2, same.stream().filter(PluginDefinition::isIgnored).count());
    assertTrue(same.stream().filter(PluginDefinition::isIgnored).allMatch(p -> p.getErrorMessage() != null));
}

@Test
public void theEmbeddedPluginIsEmbedded() {
    PluginLoader loader = new PluginLoader();
    loader.resolve(List.of(), false);
    PluginDefinition embedded = byName(loader.load(), CBEmbeddedPlugin.EMBEDDED_PLUGN_NAME);

    assertEquals(PluginSource.EMBEDDED, embedded.getSource());
    assertEquals(List.of(), embedded.getModules());
}
```

Note on `aPluginWithAMissingDependencyListsIt`: the missing-requires text comes from `ModuleDescriptor.Requires.compiledVersion()`; javac records the compiled version of `fixture.base` (1.0.0) because it was compiled with `--module-version`. If the JDK does not record it, the expected value is `"fixture.base"`: keep whichever the JDK produces, consistently with `PluginLoader.missingDependencies`.

Imports: `com.copybot.engine.plugin.PluginDefinition`, `com.copybot.engine.plugin.report.ModuleEntry`, `com.copybot.engine.plugin.report.PluginSource`, `com.copybot.plugin.embedded.CBEmbeddedPlugin`, `com.copybot.utils.FileUtil`, `org.junit.jupiter.api.io.TempDir`, `java.nio.file.*`, `java.util.List`, static `org.junit.jupiter.api.Assertions.*`.

- [ ] **Step 4: Run them to verify they fail**

Run: `mvn -q -pl copybot-engine test -Dtest=PluginLoaderDetailsTest`
Expected: compilation FAILS (`PluginSource`, `ModuleEntry`, `getSource()`… do not exist).

- [ ] **Step 5: Implement**

`PluginSource.java`:

```java
package com.copybot.engine.plugin.report;

/** Where a plugin was found. */
public enum PluginSource {
    /** A sub-directory of the configured plugin path. */
    PLUGIN_PATH,
    /** A development directory (devPluginPaths): its classes/ and lib/ sub-directories. */
    DEV,
    /** The plugin built into the engine. */
    EMBEDDED
}
```

`ModuleEntry.java`:

```java
package com.copybot.engine.plugin.report;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A module found in a plugin directory.
 *
 * @param version   null when the module declares none
 * @param location  the jar or directory it comes from, null when unknown
 * @param main      the module providing the plugin
 * @param automatic a plain jar without module-info, turned into an automatic module
 */
public record ModuleEntry(String name, String version, Path location, boolean main, boolean automatic) {

    public ModuleEntry {
        Objects.requireNonNull(name, "name");
    }
}
```

`LayerLoader` changes:
- new field `private final boolean dev;`, set by `of` (false) and `ofDev` (true) through the constructor; getter `public boolean isDev()`.
- keep the `ModuleReference`s: field `private Set<ModuleReference> moduleReferences = Set.of();` assigned from `pluginsFinder.findAll()`.
- new field `private List<LayerLoader> parentLoaders = List.of();` with getter `getParentLoaders()`; the dependency-aware load takes loaders instead of layers so it can remember them:

```java
public void load() {
    load(List.of());
}

/** Loads the layer over the boot layer and the layers of these already loaded plugins. */
public void load(List<LayerLoader> parents) {
    List<ModuleLayer> parentLayers = parents.isEmpty()
            ? List.of(ModuleLayer.boot())
            : parents.stream().map(LayerLoader::getModuleLayer).toList();
    // ... existing body of load(List<ModuleLayer>) using parentLayers ...
    parentLoaders = List.copyOf(parents);
}
```

  Keep `canBeLoaded(List<ModuleLayer>)` as is.
- `getModuleEntries()`:

```java
/** The modules of the directory, main module first then by name; empty when it could not be read. */
public List<ModuleEntry> getModuleEntries() {
    return moduleReferences.stream()
            .map(ref -> new ModuleEntry(ref.descriptor().name(),
                    ref.descriptor().version().map(Object::toString).orElse(null),
                    ref.location().map(Path::of).map(p -> p.toAbsolutePath().normalize()).orElse(null),
                    mainModuleDescriptor != null && ref.descriptor().name().equals(mainModuleDescriptor.name()),
                    ref.descriptor().isAutomatic()))
            .sorted(Comparator.comparing(ModuleEntry::main).reversed().thenComparing(ModuleEntry::name))
            .toList();
}
```

`PluginLoader` changes:
- `getCandidates(ll)` returns `List<LayerLoader>` (the matching `loadedLayers`, not their layers); `resolvePluginWithDependencies` calls `ll.canBeLoaded(candidates.stream().map(LayerLoader::getModuleLayer).toList())` then `ll.load(candidates)`.
- `markUnresolvedPlugin` passes the missing requirements as a list to the definition: compute `List<String> missing = missingDependencyList(ll.getRequires(), loadedModules)` and keep `missingDependencies(...)` as `String.join(", ", missingDependencyList(...))` so the existing test still passes:

```java
static List<String> missingDependencyList(List<ModuleDescriptor.Requires> requires, List<ModuleDescriptor> loadedModules) {
    return requires.stream()
            .filter(r -> loadedModules.stream().noneMatch(m -> VersionUtil.moduleCompatible(m, r)))
            .map(r -> r.name() + r.compiledVersion().map(v -> ":" + v).orElse(""))
            .toList();
}

static String missingDependencies(List<ModuleDescriptor.Requires> requires, List<ModuleDescriptor> loadedModules) {
    return String.join(", ", missingDependencyList(requires, loadedModules));
}
```

  then `pluginDefinitions.add(PluginDefinition.ofError(ll, message).withMissingRequires(missing))`.
- In `resolve`, the two "newer revision" / "duplicate" branches build ignored definitions: replace `PluginDefinition.ofError(x, msg)` by `PluginDefinition.ofIgnored(x, msg)` in those three places (the previous loader replaced by a newer one, the duplicate, the older new one).

`PluginDefinition` changes (keep existing factories and getters):

```java
private PluginSource source;
private boolean ignored;
private List<ModuleEntry> modules = List.of();
private List<String> pluginDependencies = List.of();
private List<String> missingRequires = List.of();
```

- `ofError(ll, msg)`: also `source = ll.isDev() ? DEV : PLUGIN_PATH`, `modules = ll.getModuleEntries()`.
- `ofIgnored(LayerLoader ll, String msg)`: like `ofError` with `ignored = true`.
- `ofSuccess(ll, instance)`: source and modules as above, `pluginDependencies = ll.getParentLoaders().stream().map(PluginDefinition::describe).toList()` with

```java
private static String describe(LayerLoader ll) {
    String version = ll.getVersion();
    return ll.getMainModuleDescriptor().name() + (version != null ? " " + version : "");
}
```

- `ofEmbedded(instance)`: `source = EMBEDDED`.
- `ofLoaded(...)` (tests): `source = PLUGIN_PATH`.
- `PluginDefinition withMissingRequires(List<String> missing)`: sets `missingRequires = List.copyOf(missing)` and returns `this`.
- getters `getSource()`, `isIgnored()`, `getModules()`, `getPluginDependencies()`, `getMissingRequires()`.

Simplest wiring: make the private constructor take `source` too and set lists after construction in the factories.

- [ ] **Step 6: Run the tests**

Run: `mvn -q -pl copybot-engine test -Dtest='PluginLoaderDetailsTest,PluginLoaderTest,PluginEngineTest,PluginCatalogTest'`
Expected: PASS.

- [ ] **Step 7: Run the whole engine suite**

Run: `mvn -q -pl copybot-engine test`
Expected: PASS (no regression in the pipelines, which resolve steps through these definitions).

- [ ] **Step 8: Commit**

```bash
git add copybot-engine
git commit -m "Record where each plugin comes from, its modules and its plugin dependencies" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: A broken plugin no longer breaks the whole load

**Files:**
- Create: `copybot-engine/src/test/java/com/copybot/engine/plugin/loader/PluginLoaderRobustnessTest.java`
- Modify: `copybot-engine/src/main/java/com/copybot/engine/plugin/loader/LayerLoader.java`
- Modify: `copybot-engine/src/main/java/com/copybot/engine/plugin/loader/PluginLoader.java`
- Modify: `copybot-engine/src/main/resources/com/copybot/engine/i18n/engineBundle.properties`, `engineBundle_fr.properties`
- Modify: `copybot-engine/src/test/java/com/copybot/engine/plugin/loader/PluginLoaderTest.java` (key list)

**Interfaces:**
- Consumes: `PluginFixtures`, `PluginLoaderDetailsTest.byName` (Task 1; copy it as a private helper if preferred).
- Produces: bundle keys `plugin.load.invalid-module` ({0} directory, {1} cause), `plugin.load.resolution` ({0} cause), `plugin.load.instantiation` ({0} cause), `plugin.load.found` ({0} plugin code, {1} directory; debug log).

- [ ] **Step 1: Write the failing tests**

```java
package com.copybot.engine.plugin.loader;

import com.copybot.engine.plugin.PluginDefinition;
import com.copybot.utils.FileUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PluginLoaderRobustnessTest {

    @TempDir
    Path tempDir;

    @Test
    public void aCorruptJarIsAnErrorOfItsPluginOnly() throws Exception {
        Path pluginPath = Files.createDirectories(tempDir.resolve("plugins"));
        PluginFixtures.jar(pluginPath.resolve("good"), PluginFixtures.Spec.of("good", "1.0.0"));
        Path broken = Files.createDirectories(pluginPath.resolve("broken"));
        Files.writeString(broken.resolve("broken.jar"), "not a zip");

        List<PluginDefinition> all = load(pluginPath);

        assertTrue(PluginLoaderDetailsTest.byName(all, "fixture.good").isActive());
        PluginDefinition error = PluginLoaderDetailsTest.byName(all, "broken"); // named after its directory
        assertFalse(error.isActive());
        assertFalse(error.isIgnored());
        assertNotNull(error.getErrorMessage());
    }

    @Test
    public void aDuplicateModuleIsAnErrorOfItsPluginOnly() throws Exception {
        Path pluginPath = Files.createDirectories(tempDir.resolve("plugins"));
        PluginFixtures.jar(pluginPath.resolve("good"), PluginFixtures.Spec.of("good", "1.0.0"));
        Path split = pluginPath.resolve("split");
        PluginFixtures.jar(split, PluginFixtures.Spec.of("split", "1.0.0"));
        Path other = tempDir.resolve("other-build");
        PluginFixtures.jar(other, PluginFixtures.Spec.of("split", "1.0.0"));
        Files.copy(other.resolve("fixture.split.jar"), split.resolve("copy.jar"));
        // same module name twice in one directory: ModuleFinder.of refuses it with a FindException

        List<PluginDefinition> all = load(pluginPath);

        assertTrue(PluginLoaderDetailsTest.byName(all, "fixture.good").isActive());
        assertTrue(all.stream().anyMatch(p -> !p.isActive() && p.getPath() != null && p.getPath().endsWith("split")),
                all.stream().map(p -> p.getName() + " " + p.getErrorMessage()).toList().toString());
    }

    @Test
    public void aPluginWhoseConstructorThrowsIsAnErrorOfItsOwn() throws Exception {
        Path pluginPath = Files.createDirectories(tempDir.resolve("plugins"));
        PluginFixtures.jar(pluginPath.resolve("a-bad"), PluginFixtures.Spec.of("bad", "1.0.0").throwingInConstructor());
        PluginFixtures.jar(pluginPath.resolve("b-good"), PluginFixtures.Spec.of("good", "1.0.0"));

        List<PluginDefinition> all = load(pluginPath);

        assertTrue(PluginLoaderDetailsTest.byName(all, "fixture.good").isActive());
        PluginDefinition bad = PluginLoaderDetailsTest.byName(all, "fixture.bad");
        assertFalse(bad.isActive());
        assertFalse(bad.isIgnored());
        assertTrue(bad.getErrorMessage().contains("boom"), bad.getErrorMessage());
    }

    private static List<PluginDefinition> load(Path pluginPath) {
        PluginLoader loader = new PluginLoader();
        loader.resolve(FileUtil.listDirectory(pluginPath), false);
        return loader.load();
    }
}
```

Also add the three new keys to the `keys` list of `PluginLoaderTest.theLoadMessagesExistInBothEngineBundles` (`plugin.load.invalid-module`, `plugin.load.resolution`, `plugin.load.instantiation`, `plugin.load.found`).

- [ ] **Step 2: Run them to verify they fail**

Run: `mvn -q -pl copybot-engine test -Dtest='PluginLoaderRobustnessTest,PluginLoaderTest'`
Expected: FAIL — the corrupt jar / duplicate module throws `FindException` out of `resolve`, the constructor throws `ServiceConfigurationError` out of `load`, the keys are missing.

- [ ] **Step 3: Add the messages**

`engineBundle.properties` (next to the other `plugin.load.*`):

```properties
plugin.load.invalid-module=Unreadable plugin module in {0}: {1}
plugin.load.resolution=The plugin modules cannot be resolved: {0}
plugin.load.instantiation=The plugin cannot be created: {0}
plugin.load.found=Plugin {0} loaded from {1}
```

`engineBundle_fr.properties` (ASCII + `\u` escapes, like its neighbours):

```properties
plugin.load.invalid-module=Module de plugin illisible dans {0} : {1}
plugin.load.resolution=Les modules du plugin ne peuvent pas \u00eatre r\u00e9solus : {0}
plugin.load.instantiation=Le plugin ne peut pas \u00eatre cr\u00e9\u00e9 : {0}
plugin.load.found=Plugin {0} charg\u00e9 depuis {1}
```

- [ ] **Step 4: Catch the failures**

`LayerLoader` constructor: wrap the finder creation and `findAll()`:

```java
try {
    pluginsFinder = ModuleFinder.of(pluginDirRecur.toArray(new Path[0]));
    moduleReferences = pluginsFinder.findAll();
} catch (FindException e) {
    error = ResourcesEngine.getString("plugin.load.invalid-module", path, e.getMessage());
    return;
}
moduleDescriptors = moduleReferences.stream().map(ModuleReference::descriptor).collect(Collectors.toSet());
```

`LayerLoader.load(List<LayerLoader>)`: let `Configuration.resolve` / `defineModulesWithOneLoader` throw; the caller catches (keeps `LayerLoader` free of the loaded/not-loaded bookkeeping).

`PluginLoader`:
- a helper used by both `loadNoDepPlugins` and `resolvePluginWithDependencies`:

```java
/** Loads the layer; a resolution failure makes it an error of this plugin only. */
private boolean tryLoad(LayerLoader ll, List<LayerLoader> parents) {
    try {
        ll.load(parents);
        loadedLayers.add(ll);
        return true;
    } catch (ResolutionException | LayerInstantiationException | SecurityException e) {
        pluginDefinitions.add(PluginDefinition.ofError(ll, ResourcesEngine.getString("plugin.load.resolution", e.getMessage())));
        return false;
    }
}
```

  In `loadNoDepPlugins`: `tryLoad(ll, List.of())` instead of `ll.load(); loadedLayers.add(ll);`. In `resolvePluginWithDependencies`: when `canBeLoaded`, `it.remove()` and `hasResolvedPlugin |= tryLoad(ll, candidates)` (a failed one is removed too, so it is not also reported as "missing dependencies").
- `instanciateResolved`: replace the merged-layer ServiceLoader by one per layer, so a failure is attributed to its plugin and does not stop the others:

```java
private void instanciateResolved() {
    instantiate(ModuleLayer.boot(), null); // the embedded plugin
    for (LayerLoader ll : loadedLayers) {
        instantiate(ll.getModuleLayer(), ll);
    }
}

/** Instantiates the plugins provided by modules of this very layer (its parents are done on their own). */
private void instantiate(ModuleLayer layer, LayerLoader ll) {
    List<ServiceLoader.Provider<IPlugin>> providers;
    try {
        providers = ServiceLoader.load(layer, IPlugin.class).stream()
                .filter(p -> p.type().getModule().getLayer() == layer)
                .toList();
    } catch (ServiceConfigurationError e) {
        if (ll != null) {
            pluginDefinitions.add(PluginDefinition.ofError(ll, ResourcesEngine.getString("plugin.load.instantiation", e.getMessage())));
        } else {
            LOG.error(e, "plugin.load.instantiation", e.getMessage());
        }
        return;
    }
    for (ServiceLoader.Provider<IPlugin> provider : providers) {
        IPlugin service;
        try {
            service = provider.get();
        } catch (ServiceConfigurationError e) {
            String cause = e.getCause() != null ? String.valueOf(e.getCause()) : e.getMessage();
            if (ll != null) {
                pluginDefinitions.add(PluginDefinition.ofError(ll, ResourcesEngine.getString("plugin.load.instantiation", cause)));
            } else {
                LOG.error(e, "plugin.load.instantiation", cause);
            }
            continue;
        }
        service.setResourceBundle(ResourcesEngine.buildPluginResourceBundle(service.getI18nBundleNames(), service.getClass().getModule()));
        if (ll == null) {
            pluginDefinitions.add(PluginDefinition.ofEmbedded(service));
        } else {
            LOG.debug("plugin.load.found", service.getPluginCode(), ll.getPath());
            pluginDefinitions.add(PluginDefinition.ofSuccess(ll, service));
        }
    }
}
```

  Add `private static final CopybotLogger LOG = CopybotLogger.getLogger(PluginLoader.class);` (`com.copybot.logger.CopybotLogger`). Remove the `System.out.println`, the `moduleLayerToLayerLoaderMap` and the merged `Configuration`/layer, and the now unused imports. The boot layer filter keeps the embedded plugin only (`CBEmbeddedPlugin` lives in `com.copybot.engine`, a boot module), so the `service.getClass().equals(CBEmbeddedPlugin.class)` test is no longer needed.

  Note: `provider.get()` of a plugin whose constructor throws raises `ServiceConfigurationError` whose cause is the `IllegalStateException("boom")` — hence the cause in the message.

- [ ] **Step 5: Run the tests**

Run: `mvn -q -pl copybot-engine test -Dtest='PluginLoaderRobustnessTest,PluginLoaderDetailsTest,PluginLoaderTest'`
Expected: PASS. `aDuplicateModuleIsAnErrorOfItsPluginOnly` goes through the `FindException` path (two jars of one directory holding the same module); the resolution path (`plugin.load.resolution`) has no fixture: leave it covered by review, do not build a contrived one.

- [ ] **Step 6: Run the whole engine suite**

Run: `mvn -q -pl copybot-engine test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add copybot-engine
git commit -m "Keep loading the other plugins when one is corrupt, unresolvable or fails to start" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: PluginReport

**Files:**
- Create: `copybot-engine/src/main/java/com/copybot/engine/plugin/report/PluginStatus.java`, `ActionEntry.java`, `PluginEntry.java`, `PluginReport.java`, `PluginReports.java`
- Create: `copybot-engine/src/test/java/com/copybot/engine/plugin/report/PluginReportsTest.java`, `PluginReportTextTest.java`
- Modify: `copybot-engine/src/main/java/com/copybot/engine/plugin/PluginEngine.java`
- Modify: `copybot-engine/src/main/java/com/copybot/engine/CopybotEngine.java`
- Modify: `copybot-engine/src/main/java/module-info.java`
- Modify: engine bundles (en, fr), `PluginLoaderTest` key list

**Interfaces:**
- Consumes: Task 1 getters on `PluginDefinition`; `PluginFixtures` (package `com.copybot.engine.plugin.loader`, package-private: make the class and its `Spec`, `jar`, `devDir` **public** so this test package can use them).
- Produces:
  - `enum PluginStatus { LOADED, ACTIONS_FAILED, ERROR, IGNORED }`
  - `record ActionEntry(StepType type, String code, String name)`
  - `record PluginEntry(String name, String version, PluginSource source, Path path, PluginStatus status, String message, List<ModuleEntry> modules, List<String> pluginDependencies, List<String> missingRequires, List<ActionEntry> actions)`
  - `record PluginReport(Path configFile, Path pluginPath, boolean pluginPathConfigured, List<Path> devPluginPaths, List<String> warnings, List<PluginEntry> plugins)` with `String toText()`
  - `PluginReports.of(Path configFile, Path pluginPath, boolean pluginPathConfigured, List<Path> devPluginPaths, List<PluginDefinition> definitions)` → `PluginReport`
  - `PluginEngine.getAllPlugins()` → every definition of the load (loaded and not), `List<PluginDefinition>`
  - `CopybotEngine.pluginReport()` → `PluginReport`; `CopybotEngine.configFile()` → `Path` (absolute, normalized)
  - bundle keys `plugin.report.path-missing` ({0} path), `plugin.report.dev-path-missing` ({0} path), `plugin.report.actions-failed` ({0} cause), and the text labels of Step 7.

- [ ] **Step 1: Write the failing report tests**

```java
package com.copybot.engine.plugin.report;

import com.copybot.engine.pipeline.StepType;
import com.copybot.engine.plugin.PluginDefinition;
import com.copybot.engine.plugin.loader.PluginFixtures;
import com.copybot.engine.plugin.loader.PluginLoader;
import com.copybot.utils.FileUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PluginReportsTest {

    @TempDir
    Path tempDir;

    private List<PluginDefinition> load(Path pluginPath, List<Path> devDirs) {
        PluginLoader loader = new PluginLoader();
        loader.resolve(FileUtil.listDirectory(pluginPath), false);
        loader.resolve(devDirs, true);
        return new ArrayList<>(loader.load());
    }

    private static PluginEntry entry(PluginReport report, String name) {
        return report.plugins().stream().filter(p -> p.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(name + " not in " + report.plugins()));
    }

    @Test
    public void aLoadedPluginHasItsModulesAndActions() throws Exception {
        Path pluginPath = Files.createDirectories(tempDir.resolve("plugins"));
        PluginFixtures.jar(pluginPath.resolve("alpha"), PluginFixtures.Spec.of("alpha", "1.0.0"));

        PluginReport report = PluginReports.of(tempDir.resolve("config.json"), pluginPath, true, List.of(), load(pluginPath, List.of()));

        PluginEntry alpha = entry(report, "fixture.alpha");
        assertEquals(PluginStatus.LOADED, alpha.status());
        assertNull(alpha.message());
        assertEquals(PluginSource.PLUGIN_PATH, alpha.source());
        assertEquals("1.0.0", alpha.version());
        assertEquals(1, alpha.modules().size());
        assertEquals(List.of(new ActionEntry(StepType.ANALYZE, "act", "act")), alpha.actions());
    }

    @Test
    public void statusesAndOrder() throws Exception {
        Path pluginPath = Files.createDirectories(tempDir.resolve("plugins"));
        PluginFixtures.jar(pluginPath.resolve("a-ok"), PluginFixtures.Spec.of("zeta", "1.0.0"));
        PluginFixtures.jar(pluginPath.resolve("b-old"), PluginFixtures.Spec.of("dup", "1.0.0"));
        PluginFixtures.jar(pluginPath.resolve("c-new"), PluginFixtures.Spec.of("dup", "1.0.1"));
        PluginFixtures.jar(pluginPath.resolve("d-noactions"), PluginFixtures.Spec.of("mute", "1.0.0").throwingOnActions());
        Files.createDirectories(pluginPath.resolve("e-empty")); // no module: ERROR

        PluginReport report = PluginReports.of(tempDir.resolve("config.json"), pluginPath, true, List.of(), load(pluginPath, List.of()));

        assertEquals(PluginStatus.ERROR, entry(report, "e-empty").status());
        PluginEntry mute = entry(report, "fixture.mute");
        assertEquals(PluginStatus.ACTIONS_FAILED, mute.status());
        assertTrue(mute.message().contains("no actions"), mute.message());
        assertEquals(List.of(), mute.actions());
        assertEquals(List.of(PluginStatus.LOADED, PluginStatus.IGNORED),
                report.plugins().stream().filter(p -> p.name().equals("fixture.dup")).map(PluginEntry::status).sorted().toList());

        List<PluginStatus> order = report.plugins().stream().map(PluginEntry::status).toList();
        assertEquals(List.of(PluginStatus.ERROR, PluginStatus.ACTIONS_FAILED, PluginStatus.IGNORED, PluginStatus.LOADED),
                order.stream().distinct().toList());
    }

    @Test
    public void aDevPluginIsReportedDev() throws Exception {
        Path pluginPath = Files.createDirectories(tempDir.resolve("plugins"));
        Path dev = PluginFixtures.devDir(tempDir.resolve("dev-target"), PluginFixtures.Spec.of("devplug", "2.0.0"));

        PluginReport report = PluginReports.of(tempDir.resolve("config.json"), pluginPath, false, List.of(dev), load(pluginPath, List.of(dev)));

        assertEquals(PluginSource.DEV, entry(report, "fixture.devplug").source());
        assertEquals(List.of(), report.warnings());
        assertFalse(report.pluginPathConfigured());
    }

    @Test
    public void missingDirectoriesAreWarned() {
        Path pluginPath = tempDir.resolve("no-plugins");
        Path dev = tempDir.resolve("no-dev");

        PluginReport report = PluginReports.of(tempDir.resolve("config.json"), pluginPath, true, List.of(dev), load(tempDir, List.of()));

        assertEquals(2, report.warnings().size(), report.warnings().toString());
        assertTrue(report.warnings().get(0).contains(pluginPath.toAbsolutePath().normalize().toString()));
        assertTrue(report.warnings().get(1).contains(dev.toAbsolutePath().normalize().toString()));
        assertEquals(List.of(dev.toAbsolutePath().normalize()), report.devPluginPaths());
    }

    @Test
    public void theEmbeddedPluginIsInTheReport() {
        PluginReport report = PluginReports.of(tempDir.resolve("config.json"), tempDir, false, List.of(), load(tempDir, List.of()));

        PluginEntry embedded = report.plugins().stream().filter(p -> p.source() == PluginSource.EMBEDDED).findFirst().orElseThrow();
        assertEquals(PluginStatus.LOADED, embedded.status());
        assertNull(embedded.version());
        assertNull(embedded.path());
        assertFalse(embedded.actions().isEmpty());
    }
}
```

In `missingDirectoriesAreWarned`, `load(tempDir, …)` lists `tempDir`'s sub-directories; it holds none yet besides what JUnit created, so the result is the embedded plugin plus nothing that matters here.

- [ ] **Step 2: Run them to verify they fail**

Run: `mvn -q -pl copybot-engine test -Dtest=PluginReportsTest`
Expected: compilation FAILS (report types missing; `PluginFixtures` not public).

- [ ] **Step 3: Make `PluginFixtures` public**

`public final class PluginFixtures`, `public record Spec`, its factory and builder methods `public`, `public static Path jar(...)`, `public static Path devDir(...)`.

- [ ] **Step 4: Implement the records and the builder**

`PluginStatus.java`:

```java
package com.copybot.engine.plugin.report;

/** The outcome of loading one plugin directory. */
public enum PluginStatus {
    /** Loaded, actions listed. */
    LOADED,
    /** Loaded, but listing its actions fails: a step naming it fails too. */
    ACTIONS_FAILED,
    /** Not loaded: see the message. */
    ERROR,
    /** Sound but not loaded: a duplicate, or an older revision of a loaded plugin. */
    IGNORED
}
```

`ActionEntry.java`:

```java
package com.copybot.engine.plugin.report;

import com.copybot.engine.pipeline.StepType;

/** An action of a loaded plugin; name localized, the code when the plugin has none. */
public record ActionEntry(StepType type, String code, String name) {
}
```

`PluginEntry.java`:

```java
package com.copybot.engine.plugin.report;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * One plugin directory as loaded (spec plugins-view §1).
 *
 * @param version            null for the embedded plugin or a module without version
 * @param path               the plugin directory, null for the embedded plugin
 * @param message            the cause, translated; null when LOADED
 * @param pluginDependencies "name version" of the plugins its layer is built on
 * @param missingRequires    "module:version" (or "module") nothing loaded provides
 * @param actions            empty unless LOADED
 */
public record PluginEntry(String name, String version, PluginSource source, Path path, PluginStatus status,
                          String message, List<ModuleEntry> modules, List<String> pluginDependencies,
                          List<String> missingRequires, List<ActionEntry> actions) {

    public PluginEntry {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(status, "status");
        modules = List.copyOf(modules);
        pluginDependencies = List.copyOf(pluginDependencies);
        missingRequires = List.copyOf(missingRequires);
        actions = List.copyOf(actions);
    }
}
```

`PluginReport.java` (records + `toText()`, Step 7 adds `toText`):

```java
package com.copybot.engine.plugin.report;

import java.nio.file.Path;
import java.util.List;

/**
 * The plugins of this JVM as they were loaded, for diagnosis (spec plugins-view §1).
 *
 * @param configFile           the configuration file read at startup
 * @param pluginPath           the plugin directory of the load, absolute
 * @param pluginPathConfigured false when the configuration declares none (default ./plugins)
 * @param devPluginPaths       the configured development directories, absolute, existing or not
 * @param warnings             translated header warnings (a directory that does not exist)
 * @param plugins              errors first, then actions failed, ignored, loaded; then by name, most recent first
 */
public record PluginReport(Path configFile, Path pluginPath, boolean pluginPathConfigured, List<Path> devPluginPaths,
                           List<String> warnings, List<PluginEntry> plugins) {

    public PluginReport {
        devPluginPaths = List.copyOf(devPluginPaths);
        warnings = List.copyOf(warnings);
        plugins = List.copyOf(plugins);
    }
}
```

`PluginReports.java`:

```java
package com.copybot.engine.plugin.report;

import com.copybot.engine.pipeline.StepType;
import com.copybot.engine.plugin.PluginDefinition;
import com.copybot.plugin.api.action.ActionDefinition;
import com.copybot.plugin.api.definition.IPlugin;
import com.copybot.resources.CombinedResourceBundle;
import com.copybot.resources.ResourcesEngine;
import com.copybot.utils.VersionUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Builds the {@link PluginReport} of loaded plugin definitions. */
public final class PluginReports {

    private static final Map<PluginStatus, Integer> RANK = Map.of(
            PluginStatus.ERROR, 0, PluginStatus.ACTIONS_FAILED, 1, PluginStatus.IGNORED, 2, PluginStatus.LOADED, 3);

    private PluginReports() {
    }

    public static PluginReport of(Path configFile, Path pluginPath, boolean pluginPathConfigured,
                                  List<Path> devPluginPaths, List<PluginDefinition> definitions) {
        Path plugins = absolute(pluginPath);
        List<Path> devs = devPluginPaths.stream().map(PluginReports::absolute).toList();
        List<String> warnings = new ArrayList<>();
        if (!Files.isDirectory(plugins)) {
            warnings.add(ResourcesEngine.getString("plugin.report.path-missing", plugins));
        }
        for (Path dev : devs) {
            if (!Files.isDirectory(dev)) {
                warnings.add(ResourcesEngine.getString("plugin.report.dev-path-missing", dev));
            }
        }
        List<PluginEntry> entries = definitions.stream()
                .map(PluginReports::entry)
                .sorted(Comparator.<PluginEntry>comparingInt(e -> RANK.get(e.status()))
                        .thenComparing(PluginEntry::name)
                        .thenComparing(PluginEntry::version, Comparator.nullsLast(VersionUtil.VERSION_ORDER.reversed())))
                .toList();
        return new PluginReport(absolute(configFile), plugins, pluginPathConfigured, devs, warnings, entries);
    }

    private static Path absolute(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static PluginEntry entry(PluginDefinition definition) {
        PluginStatus status;
        String message = definition.getErrorMessage();
        List<ActionEntry> actions = List.of();
        if (definition.isIgnored()) {
            status = PluginStatus.IGNORED;
        } else if (!definition.isActive()) {
            status = PluginStatus.ERROR;
        } else {
            try {
                actions = actions(definition.getPluginInstance());
                status = PluginStatus.LOADED;
            } catch (LinkageError | RuntimeException e) {
                status = PluginStatus.ACTIONS_FAILED;
                message = ResourcesEngine.getString("plugin.report.actions-failed", String.valueOf(e));
            }
        }
        return new PluginEntry(definition.getName(), definition.getVersion(), definition.getSource(), definition.getPath(),
                status, message, definition.getModules(), definition.getPluginDependencies(),
                definition.getMissingRequires(), actions);
    }

    private static List<ActionEntry> actions(IPlugin plugin) {
        List<ActionEntry> actions = new ArrayList<>();
        add(actions, plugin, StepType.IN, plugin.getInActions());
        add(actions, plugin, StepType.ANALYZE, plugin.getAnalyzeActions());
        add(actions, plugin, StepType.PROCESS, plugin.getProcessActions());
        add(actions, plugin, StepType.OUT, plugin.getOutActions());
        return actions;
    }

    private static void add(List<ActionEntry> actions, IPlugin plugin, StepType type, List<? extends ActionDefinition<?>> definitions) {
        CombinedResourceBundle bundle = plugin.getResourceBundle();
        for (ActionDefinition<?> definition : definitions) {
            String key = "plugin." + plugin.getPluginCode() + "." + definition.actionCode() + ".name";
            String name = bundle != null ? bundle.getString(key) : null;
            actions.add(new ActionEntry(type, definition.actionCode(),
                    name == null || name.equals("%" + key) ? definition.actionCode() : name));
        }
    }
}
```

Check the exact type of `VersionUtil.VERSION_ORDER` (`Comparator<String>` expected, as used by `PluginEngine.load`); if it is not, compare versions as `PluginEngine` does.

- [ ] **Step 5: Add the messages**

`engineBundle.properties`:

```properties
plugin.report.path-missing=The plugin directory {0} does not exist: no plugin loaded from it
plugin.report.dev-path-missing=The development plugin directory {0} does not exist: ignored
plugin.report.actions-failed=Its actions cannot be listed: {0}
```

`engineBundle_fr.properties`:

```properties
plugin.report.path-missing=Le dossier des plugins {0} n''existe pas : aucun plugin charg\u00e9 depuis ce dossier
plugin.report.dev-path-missing=Le dossier de plugins de d\u00e9veloppement {0} n''existe pas : ignor\u00e9
plugin.report.actions-failed=Ses actions ne peuvent pas \u00eatre list\u00e9es : {0}
```

Add the three keys to `PluginLoaderTest`'s key list.

- [ ] **Step 6: Run the report tests**

Run: `mvn -q -pl copybot-engine test -Dtest='PluginReportsTest,PluginLoaderTest'`
Expected: PASS.

- [ ] **Step 7: Write the failing text test, then `toText()`**

`PluginReportTextTest`:

```java
package com.copybot.engine.plugin.report;

import com.copybot.engine.pipeline.StepType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PluginReportTextTest {

    @Test
    public void theTextHoldsEveryPartOfTheReport() {
        Path config = Path.of("/app/config.json").toAbsolutePath();
        PluginEntry ok = new PluginEntry("com.example.ok", "1.2.0", PluginSource.DEV, Path.of("/dev/ok").toAbsolutePath(),
                PluginStatus.LOADED, null,
                List.of(new ModuleEntry("com.example.ok", "1.2.0", Path.of("/dev/ok/classes").toAbsolutePath(), true, false),
                        new ModuleEntry("lib.auto", null, Path.of("/dev/ok/lib/auto.jar").toAbsolutePath(), false, true)),
                List.of("com.example.base 1.0"), List.of(),
                List.of(new ActionEntry(StepType.ANALYZE, "scan", "Scan")));
        PluginEntry ko = new PluginEntry("broken", null, PluginSource.PLUGIN_PATH, Path.of("/plugins/broken").toAbsolutePath(),
                PluginStatus.ERROR, "Missing dependencies: x:1", List.of(), List.of(), List.of("x:1"), List.of());
        PluginReport report = new PluginReport(config, Path.of("/plugins").toAbsolutePath(), false,
                List.of(Path.of("/dev/ok").toAbsolutePath()), List.of("a warning"), List.of(ko, ok));

        String text = report.toText();

        for (String expected : List.of(config.toString(), "a warning", "com.example.ok 1.2.0", "LOADED", "DEV",
                "lib.auto", "com.example.base 1.0", "ANALYZE", "scan", "Scan", "broken", "ERROR",
                "Missing dependencies: x:1", "x:1")) {
            assertTrue(text.contains(expected), expected + " missing from:\n" + text);
        }
        assertTrue(text.indexOf("broken") < text.indexOf("com.example.ok 1.2.0"), "the plugins keep the report order");
    }
}
```

Run: `mvn -q -pl copybot-engine test -Dtest=PluginReportTextTest` — Expected: compilation FAILS (`toText` missing).

Then add to `PluginReport` (labels from the engine bundle, so a pasted report reads in the user's language; enum names stay as is, they are what a developer searches for):

```java
/** The whole report as plain text, to paste in a ticket. */
public String toText() {
    StringBuilder out = new StringBuilder();
    String nl = System.lineSeparator();
    out.append(ResourcesEngine.getString("plugin.report.text.config", configFile)).append(nl);
    out.append(ResourcesEngine.getString(pluginPathConfigured ? "plugin.report.text.plugin-path" : "plugin.report.text.plugin-path-default", pluginPath)).append(nl);
    for (Path dev : devPluginPaths) {
        out.append(ResourcesEngine.getString("plugin.report.text.dev-path", dev)).append(nl);
    }
    for (String warning : warnings) {
        out.append("! ").append(warning).append(nl);
    }
    for (PluginEntry p : plugins) {
        out.append(nl).append("== ").append(p.name()).append(p.version() != null ? " " + p.version() : "")
                .append(" [").append(p.status()).append(", ").append(p.source()).append("]").append(nl);
        if (p.path() != null) {
            out.append("   ").append(p.path()).append(nl);
        }
        if (p.message() != null) {
            out.append("   ").append(p.message()).append(nl);
        }
        for (ModuleEntry m : p.modules()) {
            out.append("   ").append(ResourcesEngine.getString("plugin.report.text.module")).append(' ')
                    .append(m.name()).append(m.version() != null ? " " + m.version() : "")
                    .append(m.main() ? " *" : "").append(m.automatic() ? " (automatic)" : "")
                    .append(m.location() != null ? "  " + m.location() : "").append(nl);
        }
        for (String dependency : p.pluginDependencies()) {
            out.append("   ").append(ResourcesEngine.getString("plugin.report.text.depends-on", dependency)).append(nl);
        }
        for (String missing : p.missingRequires()) {
            out.append("   ").append(ResourcesEngine.getString("plugin.report.text.missing", missing)).append(nl);
        }
        for (ActionEntry a : p.actions()) {
            out.append("   ").append(a.type()).append(' ').append(a.code())
                    .append(a.name().equals(a.code()) ? "" : " (" + a.name() + ")").append(nl);
        }
    }
    return out.toString();
}
```

Bundles — `engineBundle.properties`:

```properties
plugin.report.text.config=Configuration: {0}
plugin.report.text.plugin-path=Plugin directory: {0}
plugin.report.text.plugin-path-default=Plugin directory: {0} (default)
plugin.report.text.dev-path=Development plugins: {0}
plugin.report.text.module=module
plugin.report.text.depends-on=depends on {0}
plugin.report.text.missing=missing {0}
```

`engineBundle_fr.properties`:

```properties
plugin.report.text.config=Configuration : {0}
plugin.report.text.plugin-path=Dossier des plugins : {0}
plugin.report.text.plugin-path-default=Dossier des plugins : {0} (par d\u00e9faut)
plugin.report.text.dev-path=Plugins de d\u00e9veloppement : {0}
plugin.report.text.module=module
plugin.report.text.depends-on=d\u00e9pend de {0}
plugin.report.text.missing=manquant {0}
```

Run: `mvn -q -pl copybot-engine test -Dtest='PluginReportTextTest,PluginReportsTest'` — Expected: PASS.

- [ ] **Step 8: Wire PluginEngine and CopybotEngine**

`PluginEngine`: keep every definition of the load, in the sorted order already computed:

```java
/** Every definition of the load (loaded or not), empty before it. Guarded by the class lock for writes. */
private static volatile List<PluginDefinition> allPlugins = List.of();

public static List<PluginDefinition> getAllPlugins() {
    return allPlugins;
}
```

  set `allPlugins = Collections.unmodifiableList(new ArrayList<>(allPluginsSorted));` in `load` next to `loadedPlugins`, reset it in `resetForTest`. (Mirror how `loadedPlugins` is declared — if it is not volatile, follow the existing pattern.)

`CopybotEngine`:
- field `private final Path configFile;` — the package-private test constructor `CopybotEngine(CopybotConfig config)` delegates to a new `CopybotEngine(CopybotConfig config, Path configFile)` with `Path.of("config.json")`; `create` passes `configPath.orElse(DEFAULT_CONFIG_PATH).toAbsolutePath().normalize()`.
- methods:

```java
/** The configuration file read at startup, absolute. */
public Path configFile() {
    return configFile;
}

/** How the plugins of this JVM were loaded (spec plugins-view §1). */
public PluginReport pluginReport() {
    Path pluginPath = config.pluginPath() != null ? config.pluginPath() : DEFAULT_PLUGIN_PATH;
    List<Path> devPluginPaths = config.devPluginPaths() != null ? List.of(config.devPluginPaths()) : List.of();
    return PluginReports.of(configFile, pluginPath, config.pluginPath() != null, devPluginPaths, PluginEngine.getAllPlugins());
}
```

`module-info.java`: add `exports com.copybot.engine.plugin.report to com.copybot.ui;`.

Add one test to `copybot-engine/src/test/java/com/copybot/engine/CopybotEngineTest.java` (read it first to reuse its way of building an engine on a `CopybotConfig`):

```java
@Test
public void thePluginReportNamesTheConfiguredDirectories() {
    Path devDir = Path.of("does-not-exist-dev");
    CopybotConfig config = new CopybotConfig(null, devDir, null, null);
    try (CopybotEngine engine = new CopybotEngine(config)) {
        PluginReport report = engine.pluginReport();

        assertFalse(report.pluginPathConfigured());
        assertEquals(Path.of("plugins").toAbsolutePath().normalize(), report.pluginPath());
        assertEquals(List.of(devDir.toAbsolutePath().normalize()), report.devPluginPaths());
        assertTrue(report.warnings().stream().anyMatch(w -> w.contains(devDir.toAbsolutePath().normalize().toString())));
    }
}
```

  (Adapt the construction if `CopybotEngine` is not `AutoCloseable`-friendly in tests; it implements `AutoCloseable`.)

- [ ] **Step 9: Run the whole engine suite**

Run: `mvn -q -pl copybot-engine test`
Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add copybot-engine
git commit -m "Report how the plugins were loaded: status, modules, dependencies, actions" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: ConfigFiles — read and rewrite pluginPath

**Files:**
- Create: `copybot-engine/src/main/java/com/copybot/config/ConfigFiles.java`
- Create: `copybot-engine/src/test/java/com/copybot/config/ConfigFilesTest.java`
- Modify: `copybot-engine/src/main/java/module-info.java`
- Modify: engine bundles (en, fr)

**Interfaces:**
- Produces:
  - `ConfigFiles.readPluginPath(Path configFile)` → `Optional<Path>` (the raw value, empty when absent or null); throws `CopybotException` (`config.not-json`) on unreadable JSON.
  - `ConfigFiles.writePluginPath(Path configFile, Path pluginPathOrNull)` → `void`; throws `CopybotException` (`config.write-failed`) on any I/O failure, the file left untouched.
  - bundle key `config.write-failed` ({0} file, {1} cause).

- [ ] **Step 1: Write the failing tests**

```java
package com.copybot.config;

import com.copybot.exception.CopybotException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class ConfigFilesTest {

    @TempDir
    Path tempDir;

    @Test
    public void otherKeysArePreserved() throws Exception {
        Path config = tempDir.resolve("config.json");
        Files.writeString(config, """
                {
                  "devPluginPaths": "dev/target",
                  "resources": {"cpu": 4, "disk:*": 2},
                  "resourceGroups": [["disk:D", "disk:E"]]
                }""");

        ConfigFiles.writePluginPath(config, Path.of("D:/copybot/plugins"));

        String text = Files.readString(config);
        assertEquals(Optional.of(Path.of("D:/copybot/plugins")), ConfigFiles.readPluginPath(config));
        assertTrue(text.contains("\"devPluginPaths\": \"dev/target\""), text);
        assertTrue(text.contains("\"cpu\": 4"), text);
        assertTrue(text.contains("\"disk:E\""), text);
    }

    @Test
    public void aNullPathRemovesTheKey() throws Exception {
        Path config = tempDir.resolve("config.json");
        Files.writeString(config, "{\"pluginPath\": \"somewhere\", \"devPluginPaths\": \"dev\"}");

        ConfigFiles.writePluginPath(config, null);

        assertEquals(Optional.empty(), ConfigFiles.readPluginPath(config));
        assertFalse(Files.readString(config).contains("pluginPath\""));
        assertTrue(Files.readString(config).contains("devPluginPaths"));
    }

    @Test
    public void crlfIsKept() throws Exception {
        Path config = tempDir.resolve("config.json");
        Files.writeString(config, "{\r\n  \"devPluginPaths\": \"dev\"\r\n}");

        ConfigFiles.writePluginPath(config, Path.of("p"));

        String text = Files.readString(config);
        assertTrue(text.contains("\r\n"), text);
        assertFalse(text.replace("\r\n", "").contains("\n"), "every line ends with CRLF: " + text);
    }

    @Test
    public void lfIsKept() throws Exception {
        Path config = tempDir.resolve("config.json");
        Files.writeString(config, "{\n  \"devPluginPaths\": \"dev\"\n}");

        ConfigFiles.writePluginPath(config, Path.of("p"));

        assertFalse(Files.readString(config).contains("\r"));
    }

    @Test
    public void anEmptyObjectGetsTheKey() throws Exception {
        Path config = tempDir.resolve("config.json");
        Files.writeString(config, "{}");

        ConfigFiles.writePluginPath(config, Path.of("p"));

        assertEquals(Optional.of(Path.of("p")), ConfigFiles.readPluginPath(config));
    }

    @Test
    public void aReadOnlyFileIsRefusedAndLeftUntouched() throws Exception {
        Path config = tempDir.resolve("config.json");
        String original = "{\"devPluginPaths\": \"dev\"}";
        Files.writeString(config, original);
        assertTrue(config.toFile().setWritable(false));
        try {
            assertThrows(CopybotException.class, () -> ConfigFiles.writePluginPath(config, Path.of("p")));
            assertEquals(original, Files.readString(config));
        } finally {
            config.toFile().setWritable(true);
        }
    }

    @Test
    public void invalidJsonIsRefused() throws Exception {
        Path config = tempDir.resolve("config.json");
        Files.writeString(config, "not json");

        assertThrows(CopybotException.class, () -> ConfigFiles.readPluginPath(config));
        assertThrows(CopybotException.class, () -> ConfigFiles.writePluginPath(config, Path.of("p")));
        assertEquals("not json", Files.readString(config));
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `mvn -q -pl copybot-engine test -Dtest=ConfigFilesTest`
Expected: compilation FAILS (`ConfigFiles` missing).

- [ ] **Step 3: Implement**

```java
package com.copybot.config;

import com.copybot.exception.CopybotException;
import com.copybot.utils.GsonUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/** Reads and rewrites single keys of the configuration file, the other keys left as they are. */
public final class ConfigFiles {

    private static final String PLUGIN_PATH = "pluginPath";

    private ConfigFiles() {
    }

    /** The pluginPath written in the file, empty when there is none. */
    public static Optional<Path> readPluginPath(Path configFile) {
        JsonElement value = read(configFile).get(PLUGIN_PATH);
        return value == null || value.isJsonNull() ? Optional.empty() : Optional.of(Path.of(value.getAsString()));
    }

    /**
     * Writes pluginPath (removes it when null), through a temporary file renamed over the configuration, with
     * the line endings of the original file. On failure the file is left as it was.
     */
    public static void writePluginPath(Path configFile, Path pluginPath) {
        String original = readText(configFile);
        JsonObject json = parse(configFile, original);
        if (pluginPath == null) {
            json.remove(PLUGIN_PATH);
        } else {
            json.addProperty(PLUGIN_PATH, pluginPath.toString());
        }
        String text = GsonUtil.getGson().toJson(json);
        if (original.contains("\r\n")) {
            text = text.replace("\r\n", "\n").replace("\n", "\r\n");
        }
        Path temp = null;
        try {
            if (!Files.isWritable(configFile)) {
                throw new IOException(configFile + " is read-only");
            }
            temp = Files.createTempFile(configFile.toAbsolutePath().getParent(), "config", ".tmp");
            Files.writeString(temp, text);
            try {
                Files.move(temp, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, configFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw CopybotException.ofResource(e, "config.write-failed", configFile, e.getMessage());
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // best effort: a leftover .tmp next to the configuration is harmless
                }
            }
        }
    }

    private static JsonObject read(Path configFile) {
        return parse(configFile, readText(configFile));
    }

    private static String readText(Path configFile) {
        try {
            return Files.readString(configFile);
        } catch (IOException e) {
            throw CopybotException.ofResource(e, "config.not-found", configFile.toAbsolutePath());
        }
    }

    private static JsonObject parse(Path configFile, String text) {
        try {
            JsonElement element = JsonParser.parseString(text);
            if (!element.isJsonObject()) {
                throw CopybotException.ofResource("config.not-json", configFile);
            }
            return element.getAsJsonObject();
        } catch (JsonParseException e) {
            throw CopybotException.ofResource(e, "config.not-json", configFile);
        }
    }
}
```

Check `CopybotException.ofResource` overloads (`copybot-engine/src/main/java/com/copybot/exception/CopybotException.java`): `CopybotEngine.readConfig` uses both `ofResource(key, args…)` and `ofResource(e, key, args…)`; use the same.

On Windows `File.setWritable(false)` sets the read-only attribute; `Files.isWritable` then returns false — that is the check the test relies on (a `Files.move` over a read-only file may otherwise succeed on some systems).

Bundles: `engineBundle.properties` `config.write-failed=The configuration {0} cannot be written: {1}`; `engineBundle_fr.properties` `config.write-failed=La configuration {0} ne peut pas \u00eatre \u00e9crite : {1}`.

`module-info.java`: `exports com.copybot.config to com.google.gson, com.copybot.ui;`.

- [ ] **Step 4: Run the tests**

Run: `mvn -q -pl copybot-engine test -Dtest=ConfigFilesTest`
Expected: PASS.

- [ ] **Step 5: Run the whole engine suite and commit**

Run: `mvn -q -pl copybot-engine test` — Expected: PASS.

```bash
git add copybot-engine
git commit -m "Rewrite pluginPath in the configuration file, the other keys kept" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: UI — Plugins window

**Files:**
- Create: `copybot-ui/src/main/java/com/copybot/ui/PluginsController.java`
- Create: `copybot-ui/src/main/resources/com/copybot/ui/views/plugins-view.fxml`
- Modify: `copybot-ui/src/main/resources/com/copybot/ui/views/main-view.fxml`
- Modify: `copybot-ui/src/main/java/com/copybot/ui/MainController.java`
- Modify: `copybot-ui/src/main/java/com/copybot/ui/CopybotMainUi.java`
- Modify: `copybot-ui/src/main/resources/com/copybot/ui/i18n/uiBundle.properties`, `uiBundle_fr.properties`, `uiBundle_it.properties`

**Interfaces:**
- Consumes: `CopybotMainUi.ENGINE.pluginReport()`, `CopybotMainUi.ENGINE.configFile()` (Task 3), `ConfigFiles.readPluginPath` (Task 4), records of `com.copybot.engine.plugin.report`.
- Produces: `CopybotMainUi.HOST_SERVICES` (`javafx.application.HostServices`, set in `start`); `MainController.onPluginsClick()`; `PluginsController.init(PluginReport report, Optional<Path> savedPluginPath)`.

No automated UI tests exist in this project (no TestFX); verification is manual (Step 6) plus the compile.

- [ ] **Step 1: i18n keys**

`uiBundle.properties`:

```properties
menu.tools=Tools
menu.tools.plugins=Plugins…

plugins.title=Plugins
plugins.config-file=Configuration file
plugins.plugin-path=Plugin directory
plugins.default=(default)
plugins.dev-paths=Development plugins
plugins.restart-needed=Plugin directory changed to {0}: restart Copybot to apply
plugins.none=No plugin loaded
plugins.folder=Folder
plugins.open-folder=Open folder
plugins.message=Cause
plugins.modules=Modules
plugins.dependencies=Dependencies
plugins.actions=Actions
plugins.copy-report=Copy report
plugins.copied=Report copied to the clipboard
plugins.close=Close
plugins.col.name=Name
plugins.col.version=Version
plugins.col.location=Location
plugins.col.main=Main
plugins.col.automatic=Automatic
plugins.col.type=Step
plugins.col.code=Code
plugins.dep.plugin=Plugin {0}
plugins.dep.missing=Missing: {0}
plugins.status.LOADED=Loaded
plugins.status.ACTIONS_FAILED=Actions unavailable
plugins.status.ERROR=Error
plugins.status.IGNORED=Ignored
plugins.source.DEV=DEV
plugins.source.EMBEDDED=EMBEDDED
plugins.source.PLUGIN_PATH=
```

`uiBundle_fr.properties` (UTF-8, literal accents):

```properties
menu.tools=Outils
menu.tools.plugins=Plugins…

plugins.title=Plugins
plugins.config-file=Fichier de configuration
plugins.plugin-path=Dossier des plugins
plugins.default=(par défaut)
plugins.dev-paths=Plugins de développement
plugins.restart-needed=Dossier des plugins changé pour {0} : redémarrer Copybot pour appliquer
plugins.none=Aucun plugin chargé
plugins.folder=Dossier
plugins.open-folder=Ouvrir le dossier
plugins.message=Cause
plugins.modules=Modules
plugins.dependencies=Dépendances
plugins.actions=Actions
plugins.copy-report=Copier le rapport
plugins.copied=Rapport copié dans le presse-papier
plugins.close=Fermer
plugins.col.name=Nom
plugins.col.version=Version
plugins.col.location=Emplacement
plugins.col.main=Principal
plugins.col.automatic=Automatique
plugins.col.type=Étape
plugins.col.code=Code
plugins.dep.plugin=Plugin {0}
plugins.dep.missing=Manquant : {0}
plugins.status.LOADED=Chargé
plugins.status.ACTIONS_FAILED=Actions indisponibles
plugins.status.ERROR=Erreur
plugins.status.IGNORED=Ignoré
plugins.source.DEV=DEV
plugins.source.EMBEDDED=EMBARQUÉ
plugins.source.PLUGIN_PATH=
```

`uiBundle_it.properties`:

```properties
menu.tools=Strumenti
menu.tools.plugins=Plugin…

plugins.title=Plugin
plugins.config-file=File di configurazione
plugins.plugin-path=Cartella dei plugin
plugins.default=(predefinita)
plugins.dev-paths=Plugin di sviluppo
plugins.restart-needed=Cartella dei plugin cambiata in {0}: riavviare Copybot per applicare
plugins.none=Nessun plugin caricato
plugins.folder=Cartella
plugins.open-folder=Apri la cartella
plugins.message=Causa
plugins.modules=Moduli
plugins.dependencies=Dipendenze
plugins.actions=Azioni
plugins.copy-report=Copia il rapporto
plugins.copied=Rapporto copiato negli appunti
plugins.close=Chiudi
plugins.col.name=Nome
plugins.col.version=Versione
plugins.col.location=Posizione
plugins.col.main=Principale
plugins.col.automatic=Automatico
plugins.col.type=Fase
plugins.col.code=Codice
plugins.dep.plugin=Plugin {0}
plugins.dep.missing=Mancante: {0}
plugins.status.LOADED=Caricato
plugins.status.ACTIONS_FAILED=Azioni non disponibili
plugins.status.ERROR=Errore
plugins.status.IGNORED=Ignorato
plugins.source.DEV=DEV
plugins.source.EMBEDDED=INTEGRATO
plugins.source.PLUGIN_PATH=
```

Check whether the project has a test that compares the key sets of the three UI bundles (search `copybot-ui/src/test` for `uiBundle`); if so it must still pass.

- [ ] **Step 2: Menu and host services**

`main-view.fxml`: a new menu between Edit and Help:

```xml
<Menu mnemonicParsing="false" text="%menu.tools">
   <items>
      <MenuItem mnemonicParsing="false" onAction="#onPluginsClick" text="%menu.tools.plugins" />
   </items>
</Menu>
```

`CopybotMainUi`: `public static HostServices HOST_SERVICES;` set at the top of `start(Stage)`: `HOST_SERVICES = getHostServices();` (`javafx.application.HostServices`).

`MainController`:

```java
/** The plugins window, at most one, non modal: kept to bring it to front. */
private static Stage pluginsWindow;

@FXML
protected void onPluginsClick() {
    if (pluginsWindow != null && pluginsWindow.isShowing()) {
        pluginsWindow.toFront();
        return;
    }
    try {
        Views.Loaded<PluginsController> plugins = Views.load("plugins-view.fxml");
        Optional<Path> saved = Optional.empty();
        try {
            saved = ConfigFiles.readPluginPath(CopybotMainUi.ENGINE.configFile());
        } catch (RuntimeException e) {
            // the file changed under us since startup: the window still shows the load, without the banner
        }
        plugins.controller().init(CopybotMainUi.ENGINE.pluginReport(), saved);
        Stage window = new Stage();
        window.setTitle(ResourcesEngine.getString("plugins.title"));
        window.setScene(new Scene(plugins.root()));
        window.initOwner(CopybotMainUi.STAGE);
        window.show();
        pluginsWindow = window;
    } catch (RuntimeException e) {
        PopinUtil.showError(e);
    }
}
```

  (`Stage.getIcons()`: copy the icon the main stage uses, `window.getIcons().setAll(CopybotMainUi.STAGE.getIcons())`.) The window is rebuilt on each opening after being closed; a language change does not touch it (it shows the startup texts until reopened).

- [ ] **Step 3: The view**

`plugins-view.fxml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>

<?import javafx.geometry.Insets?>
<?import javafx.scene.control.*?>
<?import javafx.scene.layout.*?>

<BorderPane prefHeight="600.0" prefWidth="1000.0" xmlns="http://javafx.com/javafx/null" xmlns:fx="http://javafx.com/fxml/1" fx:controller="com.copybot.ui.PluginsController">
   <top>
      <VBox spacing="4.0">
         <padding><Insets bottom="8.0" left="10.0" right="10.0" top="10.0" /></padding>
         <children>
            <GridPane hgap="10.0" vgap="2.0">
               <columnConstraints>
                  <ColumnConstraints />
                  <ColumnConstraints hgrow="ALWAYS" />
               </columnConstraints>
               <children>
                  <Label text="%plugins.config-file" GridPane.rowIndex="0" />
                  <Label fx:id="configFileLabel" GridPane.columnIndex="1" GridPane.rowIndex="0" />
                  <Label text="%plugins.plugin-path" GridPane.rowIndex="1" />
                  <Label fx:id="pluginPathLabel" GridPane.columnIndex="1" GridPane.rowIndex="1" />
                  <Label text="%plugins.dev-paths" GridPane.rowIndex="2" />
                  <Label fx:id="devPathsLabel" GridPane.columnIndex="1" GridPane.rowIndex="2" />
               </children>
            </GridPane>
            <VBox fx:id="warningsBox" spacing="2.0" />
         </children>
      </VBox>
   </top>
   <center>
      <SplitPane dividerPositions="0.3">
         <items>
            <ListView fx:id="pluginList" />
            <ScrollPane fitToWidth="true">
               <content>
                  <VBox fx:id="detailBox" spacing="8.0">
                     <padding><Insets bottom="10.0" left="10.0" right="10.0" top="10.0" /></padding>
                     <children>
                        <Label fx:id="detailTitle" style="-fx-font-size: 1.3em; -fx-font-weight: bold;" />
                        <HBox alignment="CENTER_LEFT" spacing="8.0">
                           <children>
                              <Label text="%plugins.folder" />
                              <Label fx:id="detailPath" HBox.hgrow="ALWAYS" maxWidth="1.7976931348623157E308" />
                              <Button fx:id="openFolderButton" onAction="#onOpenFolderClick" text="%plugins.open-folder" />
                           </children>
                        </HBox>
                        <Label fx:id="detailMessage" wrapText="true" style="-fx-text-fill: #b00020;" />
                        <Label text="%plugins.modules" style="-fx-font-weight: bold;" />
                        <TableView fx:id="moduleTable" prefHeight="150.0">
                           <columnResizePolicy><TableView fx:constant="CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN" /></columnResizePolicy>
                           <columns>
                              <TableColumn fx:id="moduleNameCol" text="%plugins.col.name" />
                              <TableColumn fx:id="moduleVersionCol" text="%plugins.col.version" />
                              <TableColumn fx:id="moduleMainCol" text="%plugins.col.main" />
                              <TableColumn fx:id="moduleAutomaticCol" text="%plugins.col.automatic" />
                              <TableColumn fx:id="moduleLocationCol" text="%plugins.col.location" />
                           </columns>
                        </TableView>
                        <Label text="%plugins.dependencies" style="-fx-font-weight: bold;" />
                        <ListView fx:id="dependencyList" prefHeight="80.0" />
                        <Label text="%plugins.actions" style="-fx-font-weight: bold;" />
                        <TableView fx:id="actionTable" prefHeight="150.0">
                           <columnResizePolicy><TableView fx:constant="CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN" /></columnResizePolicy>
                           <columns>
                              <TableColumn fx:id="actionTypeCol" text="%plugins.col.type" />
                              <TableColumn fx:id="actionCodeCol" text="%plugins.col.code" />
                              <TableColumn fx:id="actionNameCol" text="%plugins.col.name" />
                           </columns>
                        </TableView>
                     </children>
                  </VBox>
               </content>
            </ScrollPane>
         </items>
      </SplitPane>
   </center>
   <bottom>
      <HBox alignment="CENTER_RIGHT" spacing="10.0">
         <padding><Insets bottom="10.0" left="10.0" right="10.0" top="8.0" /></padding>
         <children>
            <Label fx:id="copiedLabel" />
            <Region HBox.hgrow="ALWAYS" />
            <Button onAction="#onCopyReportClick" text="%plugins.copy-report" />
            <Button cancelButton="true" onAction="#onCloseClick" text="%plugins.close" />
         </children>
      </HBox>
   </bottom>
</BorderPane>
```

Check `CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN` exists in the project's JavaFX version (`copybot-dependencies/pom.xml`; it exists since JavaFX 20); otherwise use `CONSTRAINED_RESIZE_POLICY`. Look at `plan-view.fxml` for the project's way of declaring tables and copy it if it differs.

- [ ] **Step 4: The controller**

```java
package com.copybot.ui;

import com.copybot.engine.plugin.report.ActionEntry;
import com.copybot.engine.plugin.report.ModuleEntry;
import com.copybot.engine.plugin.report.PluginEntry;
import com.copybot.engine.plugin.report.PluginReport;
import com.copybot.engine.plugin.report.PluginStatus;
import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.util.PopinUtil;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** The plugins of this JVM as they were loaded, read-only (spec plugins-view §2). */
public class PluginsController {

    private static final Map<PluginStatus, Color> STATUS_COLORS = Map.of(
            PluginStatus.LOADED, Color.web("#2e7d32"),
            PluginStatus.ACTIONS_FAILED, Color.web("#ef6c00"),
            PluginStatus.ERROR, Color.web("#c62828"),
            PluginStatus.IGNORED, Color.web("#9e9e9e"));

    @FXML private Label configFileLabel;
    @FXML private Label pluginPathLabel;
    @FXML private Label devPathsLabel;
    @FXML private VBox warningsBox;
    @FXML private ListView<PluginEntry> pluginList;
    @FXML private VBox detailBox;
    @FXML private Label detailTitle;
    @FXML private Label detailPath;
    @FXML private Button openFolderButton;
    @FXML private Label detailMessage;
    @FXML private TableView<ModuleEntry> moduleTable;
    @FXML private TableColumn<ModuleEntry, String> moduleNameCol;
    @FXML private TableColumn<ModuleEntry, String> moduleVersionCol;
    @FXML private TableColumn<ModuleEntry, String> moduleMainCol;
    @FXML private TableColumn<ModuleEntry, String> moduleAutomaticCol;
    @FXML private TableColumn<ModuleEntry, String> moduleLocationCol;
    @FXML private ListView<String> dependencyList;
    @FXML private TableView<ActionEntry> actionTable;
    @FXML private TableColumn<ActionEntry, String> actionTypeCol;
    @FXML private TableColumn<ActionEntry, String> actionCodeCol;
    @FXML private TableColumn<ActionEntry, String> actionNameCol;
    @FXML private Label copiedLabel;

    private PluginReport report;

    @FXML
    public void initialize() {
        moduleNameCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().name()));
        moduleVersionCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(Optional.ofNullable(c.getValue().version()).orElse("")));
        moduleMainCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().main() ? "✔" : ""));
        moduleAutomaticCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().automatic() ? "✔" : ""));
        moduleLocationCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(Optional.ofNullable(c.getValue().location()).map(Path::toString).orElse("")));
        actionTypeCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().type().name()));
        actionCodeCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().code()));
        actionNameCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().name()));
        dependencyList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                setStyle(!empty && item != null && item.startsWith(missingPrefix()) ? "-fx-text-fill: #c62828;" : "");
            }
        });
        pluginList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(PluginEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                String source = ResourcesEngine.getString("plugins.source." + item.source().name());
                setText(item.name() + (item.version() != null ? " " + item.version() : "")
                        + (source.isBlank() ? "" : "  [" + source + "]"));
                Circle dot = new Circle(5, STATUS_COLORS.get(item.status()));
                setGraphic(dot);
                setTooltip(new Tooltip(ResourcesEngine.getString("plugins.status." + item.status().name())));
            }
        });
        pluginList.getSelectionModel().selectedItemProperty().addListener((obs, old, entry) -> showDetail(entry));
    }

    /** @param savedPluginPath the pluginPath now written in the configuration file (for the restart banner) */
    public void init(PluginReport report, Optional<Path> savedPluginPath) {
        this.report = report;
        configFileLabel.setText(report.configFile().toString());
        pluginPathLabel.setText(report.pluginPath() + (report.pluginPathConfigured() ? "" : " " + ResourcesEngine.getString("plugins.default")));
        devPathsLabel.setText(report.devPluginPaths().isEmpty() ? "—"
                : report.devPluginPaths().stream().map(Path::toString).collect(Collectors.joining("; ")));
        warningsBox.getChildren().clear();
        for (String warning : report.warnings()) {
            warningsBox.getChildren().add(banner(warning, "#fff3e0", "#e65100"));
        }
        // a relative pluginPath (and the default ./plugins) is resolved against the working directory, as the engine does
        Path effectiveSaved = savedPluginPath.map(p -> p.toAbsolutePath().normalize()).orElse(Path.of("plugins").toAbsolutePath().normalize());
        if (!effectiveSaved.equals(report.pluginPath())) {
            warningsBox.getChildren().add(banner(ResourcesEngine.getString("plugins.restart-needed", effectiveSaved), "#e3f2fd", "#0d47a1"));
        }
        pluginList.getItems().setAll(report.plugins());
        if (report.plugins().isEmpty()) {
            pluginList.setPlaceholder(new Label(ResourcesEngine.getString("plugins.none")));
            detailBox.setVisible(false);
        } else {
            pluginList.getSelectionModel().selectFirst();
        }
    }

    private static Label banner(String text, String background, String foreground) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        label.setStyle("-fx-background-color: " + background + "; -fx-text-fill: " + foreground + "; -fx-padding: 4 8 4 8;");
        return label;
    }

    private void showDetail(PluginEntry entry) {
        detailBox.setVisible(entry != null);
        if (entry == null) {
            return;
        }
        detailTitle.setText(entry.name() + (entry.version() != null ? " " + entry.version() : "")
                + " — " + ResourcesEngine.getString("plugins.status." + entry.status().name()));
        detailPath.setText(entry.path() != null ? entry.path().toString() : "—");
        openFolderButton.setDisable(entry.path() == null);
        detailMessage.setText(entry.message() != null ? entry.message() : "");
        detailMessage.setManaged(entry.message() != null);
        detailMessage.setVisible(entry.message() != null);
        moduleTable.getItems().setAll(entry.modules());
        dependencyList.getItems().setAll(entry.pluginDependencies().stream()
                .map(d -> ResourcesEngine.getString("plugins.dep.plugin", d)).toList());
        dependencyList.getItems().addAll(entry.missingRequires().stream()
                .map(m -> ResourcesEngine.getString("plugins.dep.missing", m)).toList());
        actionTable.getItems().setAll(entry.actions());
    }

    private static String missingPrefix() {
        String sample = ResourcesEngine.getString("plugins.dep.missing", "\u0000");
        return sample.substring(0, sample.indexOf('\u0000'));
    }

    @FXML
    protected void onOpenFolderClick() {
        PluginEntry entry = pluginList.getSelectionModel().getSelectedItem();
        if (entry == null || entry.path() == null) {
            return;
        }
        try {
            CopybotMainUi.HOST_SERVICES.showDocument(entry.path().toUri().toString());
        } catch (RuntimeException e) {
            PopinUtil.showError(e);
        }
    }

    @FXML
    protected void onCopyReportClick() {
        ClipboardContent content = new ClipboardContent();
        content.putString(report.toText());
        Clipboard.getSystemClipboard().setContent(content);
        copiedLabel.setText(ResourcesEngine.getString("plugins.copied"));
    }

    @FXML
    protected void onCloseClick() {
        ((Stage) pluginList.getScene().getWindow()).close();
    }
}
```

Keep the imports to what is used (`HBox`, `ReadOnlyObjectWrapper` are likely not).

- [ ] **Step 5: Build**

Run: `mvn -q -pl copybot-engine,copybot-ui -am test`
Expected: BUILD SUCCESS, existing UI tests pass.

- [ ] **Step 6: Manual check**

Run `CopybotMainUiDev` (IntelliJ, working directory = repository root) after `mvn -q -pl copybot-plugin/copybot-plugin-metadata-extractor -am package -DskipTests`, open Tools → Plugins…:
- `copybot.plugin.metadataextractor` (or the module name of `copybot-plugin/copybot-plugin-metadata-extractor/src/main/java/module-info.java`) is `LOADED` with the `DEV` badge, its modules list `com.drew.metadata` / `xmpcore` jars of `target/lib`, its actions are listed;
- the embedded plugin shows `EMBEDDED`;
- the `pluginPath` line ends with "(default)" and a warning says `./plugins` does not exist (unless it does);
- "Copy report" puts the text in the clipboard.

If the implementer cannot run a GUI (headless agent), say so in the report and leave this check to the user.

- [ ] **Step 7: Commit**

```bash
git add copybot-ui
git commit -m "Desktop UI: plugins window with load status, modules, dependencies and actions" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: UI — plugin folder in Preferences

**Files:**
- Modify: `copybot-ui/src/main/java/com/copybot/ui/PreferencesController.java`
- Modify: `copybot-ui/src/main/resources/com/copybot/ui/views/preferences-view.fxml`
- Modify: `copybot-ui/src/main/resources/com/copybot/ui/i18n/uiBundle.properties`, `uiBundle_fr.properties`, `uiBundle_it.properties`

**Interfaces:**
- Consumes: `ConfigFiles.readPluginPath`, `ConfigFiles.writePluginPath` (Task 4); `CopybotMainUi.ENGINE.configFile()` (Task 3).

- [ ] **Step 1: i18n keys**

`uiBundle.properties`:

```properties
pref.plugin-path=Plugin directory
pref.plugin-path.browse=Browse…
pref.plugin-path.default=Default
pref.plugin-path.default-value=./plugins (default)
pref.plugin-path.resolved=→ {0}
pref.plugin-path.saved=The plugin directory is saved in {0}. It applies at the next start of Copybot.
```

`uiBundle_fr.properties`:

```properties
pref.plugin-path=Dossier des plugins
pref.plugin-path.browse=Parcourir…
pref.plugin-path.default=Par défaut
pref.plugin-path.default-value=./plugins (par défaut)
pref.plugin-path.resolved=→ {0}
pref.plugin-path.saved=Le dossier des plugins est enregistré dans {0}. Il s''applique au prochain démarrage de Copybot.
```

`uiBundle_it.properties`:

```properties
pref.plugin-path=Cartella dei plugin
pref.plugin-path.browse=Sfoglia…
pref.plugin-path.default=Predefinita
pref.plugin-path.default-value=./plugins (predefinita)
pref.plugin-path.resolved=→ {0}
pref.plugin-path.saved=La cartella dei plugin è salvata in {0}. Si applica al prossimo avvio di Copybot.
```

Check how the existing UI bundles escape single quotes in MessageFormat values (search `''` in `uiBundle_fr.properties`, e.g. `plan.menu.resume-from-here`) — `ResourcesEngine.getString(key, args)` formats with MessageFormat, so `''` is right when arguments are passed.

- [ ] **Step 2: The row**

`preferences-view.fxml`: widen the dialog (`prefWidth="520.0"`) and add, between the language row and the buttons:

```xml
<VBox spacing="4.0">
   <children>
      <Label text="%pref.plugin-path" />
      <HBox alignment="CENTER_LEFT" spacing="6.0">
         <children>
            <TextField fx:id="pluginPathField" editable="false" HBox.hgrow="ALWAYS" />
            <Button onAction="#onBrowsePluginPathClick" text="%pref.plugin-path.browse" />
            <Button onAction="#onDefaultPluginPathClick" text="%pref.plugin-path.default" />
         </children>
      </HBox>
      <Label fx:id="pluginPathResolved" style="-fx-text-fill: #666666;" />
   </children>
</VBox>
```

  (add `<?import javafx.scene.control.TextField?>`).

- [ ] **Step 3: The controller**

Add to `PreferencesController`:

```java
@FXML
private TextField pluginPathField;
@FXML
private Label pluginPathResolved;

/** The pluginPath of the file when the dialog opened, and the one chosen (null: default). */
private Path savedPluginPath;
private Path chosenPluginPath;
```

In `initialize()` (after the language part):

```java
try {
    savedPluginPath = ConfigFiles.readPluginPath(CopybotMainUi.ENGINE.configFile()).orElse(null);
} catch (RuntimeException e) {
    savedPluginPath = null; // unreadable now: shown as default, and OK rewrites it only if the user picks one
}
chosenPluginPath = savedPluginPath;
showPluginPath();
```

```java
private void showPluginPath() {
    if (chosenPluginPath == null) {
        pluginPathField.setText(ResourcesEngine.getString("pref.plugin-path.default-value"));
    } else {
        pluginPathField.setText(chosenPluginPath.toString());
    }
    Path effective = (chosenPluginPath != null ? chosenPluginPath : Path.of("plugins")).toAbsolutePath().normalize();
    boolean showResolved = chosenPluginPath == null || !chosenPluginPath.isAbsolute();
    pluginPathResolved.setText(showResolved ? ResourcesEngine.getString("pref.plugin-path.resolved", effective) : "");
}

@FXML
protected void onBrowsePluginPathClick() {
    DirectoryChooser chooser = new DirectoryChooser();
    chooser.setTitle(ResourcesEngine.getString("pref.plugin-path"));
    Path start = (chosenPluginPath != null ? chosenPluginPath : Path.of("plugins")).toAbsolutePath().normalize();
    if (Files.isDirectory(start)) {
        chooser.setInitialDirectory(start.toFile());
    }
    File dir = chooser.showDialog(pluginPathField.getScene().getWindow());
    if (dir != null) {
        chosenPluginPath = dir.toPath();
        showPluginPath();
    }
}

@FXML
protected void onDefaultPluginPathClick() {
    chosenPluginPath = null;
    showPluginPath();
}
```

`onOkClick()` becomes (the plugin folder first: a write failure keeps the dialog open, nothing else applied):

```java
@FXML
protected void onOkClick() {
    if (!Objects.equals(chosenPluginPath, savedPluginPath)) {
        Path configFile = CopybotMainUi.ENGINE.configFile();
        try {
            ConfigFiles.writePluginPath(configFile, chosenPluginPath);
        } catch (RuntimeException e) {
            PopinUtil.showError(e);
            return; // the dialog stays open
        }
        Alert info = new Alert(Alert.AlertType.INFORMATION, ResourcesEngine.getString("pref.plugin-path.saved", configFile));
        info.setHeaderText(null);
        info.initOwner(pluginPathField.getScene().getWindow());
        info.showAndWait();
    }
    Locale chosen = languageCombo.getValue();
    if (chosen != null && !chosen.getLanguage().equals(Locale.getDefault().getLanguage())) {
        ResourcesEngine.loadLanguage(chosen);
        UiPreferences.saveLanguage(chosen);
        CopybotMainUi.reloadMainView();
    }
    close();
}
```

Imports: `com.copybot.config.ConfigFiles`, `com.copybot.ui.util.PopinUtil`, `javafx.scene.control.Alert`, `javafx.scene.control.Label`, `javafx.scene.control.TextField`, `javafx.stage.DirectoryChooser`, `java.io.File`, `java.nio.file.Files`, `java.nio.file.Path`, `java.util.Objects`. `PopinUtil.showError(Exception)` takes an `Exception`: `RuntimeException` fits.

- [ ] **Step 4: Build**

Run: `mvn -q -pl copybot-engine,copybot-ui -am test`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Manual check**

With `CopybotMainUiDev`: Edit → Preferences…, Browse… a folder, OK → information message naming `copybot-ui/src/dev/config.json`; the file now holds `"pluginPath"` with the other keys and its CRLF endings; Tools → Plugins… shows the restart banner; Preferences → Default → OK removes the key and the banner disappears on reopening the plugins window. Restore `copybot-ui/src/dev/config.json` afterwards (`git checkout -- copybot-ui/src/dev/config.json`) so the check does not end up in a commit. If no GUI is available, say so in the report.

- [ ] **Step 6: Commit**

```bash
git add copybot-ui
git commit -m "Desktop UI: choose the plugin directory in the preferences" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

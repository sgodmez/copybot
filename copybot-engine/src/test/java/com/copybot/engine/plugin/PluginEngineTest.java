package com.copybot.engine.plugin;

import com.copybot.config.SecretStore;
import com.copybot.engine.CopybotEngine;
import com.copybot.engine.SecretRedactionTest;
import com.copybot.engine.pipeline.PipelineStep;
import com.copybot.engine.pipeline.PipelineStepConfig;
import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.AbstractAction;
import com.copybot.plugin.api.action.IInAction;
import com.copybot.plugin.api.action.IOutAction;
import com.copybot.plugin.api.action.IProcessAction;
import com.copybot.plugin.api.action.IRunEndAction;
import com.copybot.resources.ResourcesEngine;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PluginEngine.load runs once per JVM (JPMS layers cannot be unloaded). Another test class may already
 * have loaded the plugins: every assertion holds whichever load came first. The tests needing a first load forget
 * that load first ({@link PluginEngine#resetForTest()}), e.g. so that concurrent threads really race for it.
 */
public class PluginEngineTest {

    @TempDir
    Path tempDir;

    @Test
    public void aSecondLoadIsIgnoredWithAWarning() throws Exception {
        PluginEngine.load(Files.createDirectories(tempDir.resolve("plugins-a")), List.of());
        List<PluginDefinition> loaded = PluginEngine.getLoadedPlugins();

        Logger jul = Logger.getLogger(PluginEngine.class.getCanonicalName());
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        jul.addHandler(handler);
        try {
            PluginEngine.load(Files.createDirectories(tempDir.resolve("plugins-b")), List.of());
        } finally {
            jul.removeHandler(handler);
        }

        assertSame(loaded, PluginEngine.getLoadedPlugins(), "a second load must neither reload nor duplicate the plugins");
        assertTrue(records.stream().anyMatch(r -> r.getLevel() == Level.WARNING),
                "loading other plugin directories in the same JVM is reported");
    }

    @Test
    public void aResolvedActionKnowsItsPluginAndItsBundle() throws Exception {
        PluginEngine.load(Files.createDirectories(tempDir.resolve("plugins")), List.of()); // the embedded one at least
        PipelineStepConfig write = new PipelineStepConfig(null, "file.write", null, null, null, null, null,
                com.google.gson.JsonParser.parseString("{\"outPattern\":\"out/{name}\"}"));

        PipelineStep<IOutAction> step = PluginEngine.resolve(write, IOutAction.class);

        AbstractAction action = assertInstanceOf(AbstractAction.class, step.getAction());
        assertSame(step.getPlugin(), action.getPlugin(), "setPlugin is called when the action is created");
        assertNotNull(action.getResourceBundle());
    }

    @Test
    public void anEmbeddedStepMatchesWhateverTheEngineVersion() throws Exception {
        PluginEngine.load(Files.createDirectories(tempDir.resolve("plugins")), List.of());
        PipelineStepConfig read = new PipelineStepConfig("", "file.read", null, null, null, null, null,
                com.google.gson.JsonParser.parseString("{\"path\":\".\"}"));

        assertNotNull(PluginEngine.resolve(read, IInAction.class).getAction());
    }

    @Test
    public void aSecondLoadOfTheSameDirectoriesIsSilent() throws Exception {
        PluginEngine.resetForTest(); // the first load must be this test's, so that the second asks for the same dirs
        Path dir = Files.createDirectories(tempDir.resolve("plugins"));
        PluginEngine.load(dir, List.of());
        List<PluginDefinition> loaded = PluginEngine.getLoadedPlugins();

        List<LogRecord> records = recordLogs(() -> PluginEngine.load(dir.resolve("..").resolve("plugins"), List.of()));

        assertSame(loaded, PluginEngine.getLoadedPlugins());
        assertEquals(List.of(), records.stream().filter(r -> r.getLevel() == Level.WARNING).toList(),
                "the same directories (once normalized) are no reason to warn");
    }

    @Test
    public void aPluginInErrorIsWarnedOfInTheLog() throws Exception {
        PluginEngine.resetForTest(); // the load must be this test's
        Path dir = Files.createDirectories(tempDir.resolve("plugins"));
        Files.createDirectories(dir.resolve("not-a-plugin"));

        List<LogRecord> records = recordLogs(() -> PluginEngine.load(dir, List.of()));

        assertTrue(records.stream().anyMatch(r -> r.getLevel() == Level.WARNING && r.getMessage().contains("not-a-plugin")),
                "a plugin in error is reported in the log, not silently left out");
    }

    private static List<LogRecord> recordLogs(Runnable action) {
        Logger jul = Logger.getLogger(PluginEngine.class.getCanonicalName());
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        jul.addHandler(handler);
        try {
            action.run();
        } finally {
            jul.removeHandler(handler);
        }
        return records;
    }

    @Test
    public void aPluginDirectoryWithoutPluginModuleIsAnErrorPluginNamedAfterIt() throws Exception {
        PluginEngine.resetForTest(); // the load must be this test's
        Path dir = Files.createDirectories(tempDir.resolve("plugins"));
        Path broken = Files.createDirectories(dir.resolve("broken-plugin"));

        PluginEngine.load(dir, List.of());

        PluginDefinition error = PluginEngine.getErrorPlugins().stream()
                .filter(p -> broken.equals(p.getPath()))
                .findFirst().orElseThrow(() -> new AssertionError(PluginEngine.getErrorPlugins()));
        assertEquals("broken-plugin", error.getName());
        assertNull(error.getVersion());
        assertFalse(error.isActive());
        assertEquals(ResourcesEngine.getString("plugin.load.no-module", broken), error.getErrorMessage());
        assertFalse(PluginEngine.getLoadedPlugins().isEmpty(), "the embedded plugin is still loaded");
    }

    @Test
    public void concurrentLoadsLoadEachPluginOnce() throws Exception {
        PluginEngine.resetForTest(); // otherwise every thread may only hit an earlier load of the JVM
        Path dir = Files.createDirectories(tempDir.resolve("plugins"));
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    PluginEngine.load(dir, List.of());
                } catch (Throwable t) {
                    failures.add(t);
                }
            }));
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join(10_000);
            assertFalse(thread.isAlive(), "a load did not end within 10s");
        }

        assertEquals(List.of(), failures);
        List<String> ids = PluginEngine.getLoadedPlugins().stream().map(p -> p.getName() + ":" + p.getVersion()).toList();
        assertFalse(ids.isEmpty(), "at least the embedded plugin is loaded");
        assertEquals(ids.size(), new HashSet<>(ids).size(), "no plugin is loaded twice: " + ids);
    }

    private static PipelineStepConfig step(String action, String config) {
        return new PipelineStepConfig(null, action, null, null, null, null, null, JsonParser.parseString(config));
    }

    private SecretStore secrets() throws Exception {
        SecretStore store = SecretStore.at(tempDir.resolve("secrets.json"));
        store.set("src", tempDir.resolve("card").toString());
        store.set("gb", "hunter2");
        return store;
    }

    @Test
    public void aSecretReferenceIsResolvedJustBeforeLoadConfig() throws Exception {
        PluginEngine.load(Files.createDirectories(tempDir.resolve("plugins")), List.of());
        PluginEngine.useSecretsForTest(secrets());
        try {
            PipelineStepConfig read = step("file.read", "{\"path\":\"${secret:src}\"}");

            PipelineStep<IInAction> step = PluginEngine.resolve(read, IInAction.class);

            assertEquals(Set.of(tempDir.resolve("card")), step.getAction().touchedPaths(null));
            assertEquals("{\"path\":\"${secret:src}\"}", step.getConfig().actionConfig().toString(),
                    "the step keeps the reference, never the value");
        } finally {
            PluginEngine.useSecretsForTest(null);
        }
    }

    @Test
    public void aMissingSecretRefusesTheStepWhateverItsType() throws Exception {
        PluginEngine.load(Files.createDirectories(tempDir.resolve("plugins")), List.of());
        PluginEngine.useSecretsForTest(secrets());
        try {
            String refusal = ResourcesEngine.getString("secret.missing", "nope");
            assertEquals(refusal, assertThrows(CopybotException.class, () -> PluginEngine.resolve(
                    step("file.read", "{\"path\":\"${secret:nope}\"}"), IInAction.class)).getMessage());
            assertEquals(refusal, assertThrows(CopybotException.class, () -> PluginEngine.resolve(
                    step("file.buffer", "{\"folder\":\"${secret:nope}\"}"), IProcessAction.class)).getMessage());
            assertEquals(refusal, assertThrows(CopybotException.class, () -> PluginEngine.resolve(
                    step("file.write", "{\"outPattern\":\"${secret:nope}/{name}\"}"), IOutAction.class)).getMessage());
            assertEquals(refusal, assertThrows(CopybotException.class, () -> PluginEngine.resolve(
                    step("post.process", "{\"command\":\"${secret:nope}\"}"), IRunEndAction.class)).getMessage());
        } finally {
            PluginEngine.useSecretsForTest(null);
        }
    }

    @Test
    public void anInvalidConfigurationNeverShowsTheSecretValue() throws Exception {
        PluginEngine.load(Files.createDirectories(tempDir.resolve("plugins")), List.of());
        PluginEngine.useSecretsForTest(secrets());
        try {
            RuntimeException e = assertThrows(RuntimeException.class, () -> PluginEngine.resolve(
                    step("file.buffer", "{\"keepFreeGb\":\"${secret:gb}\"}"), IProcessAction.class));

            for (Throwable t = e; t != null; t = t.getCause()) {
                assertFalse(String.valueOf(t.getMessage()).contains("hunter2"), t.toString());
            }
            assertTrue(e.getMessage().contains("${secret:gb}"), "the reference tells which value: " + e.getMessage());
        } finally {
            PluginEngine.useSecretsForTest(null);
        }
    }

    @Test
    public void theFoldersOfAStepThatFailsToTellThemAreLoggedWithoutTheSecretValue() throws Exception {
        PluginEngine.load(Files.createDirectories(tempDir.resolve("plugins")), List.of());
        SecretStore store = SecretStore.at(tempDir.resolve("secrets.json"));
        String value = "hunter2\u0000pass"; // no valid path: the error of Path.of shows it
        store.set("src", value);
        PluginEngine.useSecretsForTest(store);
        Path pipeline = Files.writeString(tempDir.resolve("p.json"), """
                { "inSteps": [ { "action": "file.read", "actionConfig": { "path": "${secret:src}" } } ] }
                """);
        List<Path> paths = new ArrayList<>();
        try {
            // the plugins are already loaded: create() warns of its plugin directories, not printed
            List<LogRecord> records = SecretRedactionTest.recordLogs(List.of(CopybotEngine.class, PluginEngine.class),
                    () -> {
                        try (CopybotEngine engine = CopybotEngine.create(
                                Optional.of(Path.of("./src/test/resources/com/copybot/engine/config.json")))) {
                            paths.addAll(engine.pipelinePaths(pipeline));
                        }
                    });

            assertEquals(List.of(), paths, "the step tells nothing");
            assertTrue(records.stream().anyMatch(r -> r.getLoggerName().equals(CopybotEngine.class.getName())),
                    "the failure is logged");
            SecretRedactionTest.assertNoValueLogged(records, value);
        } finally {
            PluginEngine.useSecretsForTest(null);
        }
    }
}

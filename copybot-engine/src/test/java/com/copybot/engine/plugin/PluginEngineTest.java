package com.copybot.engine.plugin;

import com.copybot.resources.ResourcesEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
}

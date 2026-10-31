package com.copybot.plugin.exec.actions;

import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.exec.ExecPlugin;
import com.copybot.plugin.exec.process.FakeProgram;
import com.copybot.plugin.exec.process.ProgramResolver;
import com.copybot.resources.CombinedResourceBundle;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public class ExecProcessTest {

    private static Locale defaultLocale;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void english() {
        // the bundle starts from the default locale: the messages checked below are the English ones
        defaultLocale = Locale.getDefault();
        Locale.setDefault(Locale.ENGLISH);
    }

    @AfterAll
    static void restoreLocale() {
        Locale.setDefault(defaultLocale);
    }

    private Path work() {
        return tempDir.resolve("work");
    }

    private ExecProcess action(String outputExtension, Integer timeoutSeconds, List<Integer> successCodes,
                               String... arguments) {
        List<String> command = FakeProgram.command(arguments);
        JsonObject config = new JsonObject();
        config.addProperty("program", command.getFirst());
        JsonArray args = new JsonArray();
        command.subList(1, command.size()).forEach(args::add);
        config.add("arguments", args);
        if (outputExtension != null) {
            config.addProperty("outputExtension", outputExtension);
        }
        if (timeoutSeconds != null) {
            config.addProperty("timeoutSeconds", timeoutSeconds);
        }
        if (successCodes != null) {
            JsonArray codes = new JsonArray();
            successCodes.forEach(codes::add);
            config.add("successCodes", codes);
        }
        ExecProcess action = new ExecProcess(work(), ProgramResolver.Environment.current());
        action.setPlugin(plugin());
        action.loadConfig(config);
        return action;
    }

    private static ExecPlugin plugin() {
        ExecPlugin plugin = new ExecPlugin();
        CombinedResourceBundle bundle = new CombinedResourceBundle(ExecPlugin.class.getModule(), null);
        plugin.getI18nBundleNames().forEach(bundle::addBundle);
        bundle.load(Locale.ENGLISH);
        plugin.setResourceBundle(bundle);
        return plugin;
    }

    private WorkItem file(String name, String content) throws IOException {
        Path source = Files.writeString(Files.createDirectories(tempDir.resolve("in")).resolve(name), content);
        WorkItem item = new WorkItem(source);
        item.getMetadatas().display().put("name", name);
        item.getMetadatas().setSize(content.length());
        return item;
    }

    private long workFiles() throws IOException {
        if (!Files.exists(work())) {
            return 0;
        }
        try (Stream<Path> files = Files.walk(work())) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    @Test
    public void theFileTheProgramWritesReplacesTheContentUnderTheNewExtension() throws IOException {
        WorkItem item = file("photo.heic", "HEIC bytes and more");
        ExecProcess action = action("jpg", null, null, "copy", "{in}", "{out}");
        action.beforeAll();

        List<WorkItem> produced = action.doProcess(item);

        assertEquals(1, produced.size());
        WorkItem result = produced.getFirst();
        try (var in = result.openInputStream()) {
            assertEquals("HEIC bytes and more", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals("photo.jpg", result.getMetadatas().display().get("name"));
        assertEquals(19L, result.getMetadatas().getSize());
        assertEquals(item.getLocalLocation(), result.getLocalLocation(), "the source stays the original");
        assertTrue(result.getContentLocation().startsWith(work()), result.getContentLocation().toString());
        assertEquals("photo.jpg", result.getContentLocation().getFileName().toString());
        assertEquals("photo.heic", item.getMetadatas().display().get("name"), "the item handed is unchanged");
        assertTrue(Files.exists(item.getLocalLocation()), "the original is never touched");

        action.afterItem(item);
        assertFalse(Files.exists(result.getContentLocation()), "the working file goes once the item is finished");
        assertTrue(Files.exists(item.getLocalLocation()));
        action.afterAll();
        try (Stream<Path> left = Files.list(work())) {
            assertEquals(0, left.count(), "the folder of the run goes after the run");
        }
    }

    @Test
    public void noOutputExtensionKeepsTheExtensionOfTheItemAndTheSizeIsTheNewOne() throws IOException {
        Path script = Files.writeString(tempDir.resolve("other.txt"), "short");
        WorkItem item = file("notes.txt", "a much longer content");
        // the program copies another file to {out}: a content of another size
        ExecProcess action = action(null, null, null, "copy", script.toString(), "{out}");

        WorkItem result = action.doProcess(item).getFirst();

        assertEquals("notes.txt", result.getMetadatas().display().get("name"));
        assertEquals(5L, result.getMetadatas().getSize());
        action.afterAll();
    }

    @Test
    public void theVariablesAreReplacedInEveryArgument() {
        Path in = Path.of("in", "a.b.heic");
        Path out = Path.of("out", "a.b.jpg");

        assertEquals("-i=" + in + " -o=" + out + " a.b heic", ExecProcess.fill("-i={in} -o={out} {name} {ext}", in, out,
                "a.b.heic"));
        assertEquals("{other}", ExecProcess.fill("{other}", in, out, "a.b.heic"));
        assertEquals("README.", ExecProcess.fill("{name}.{ext}", in, out, "README"), "no extension: empty");
        Path odd = Path.of("in", "x{ext}{out}.mov");
        assertEquals(odd + " x{ext}{out}", ExecProcess.fill("{in} {name}", odd, out, "x{ext}{out}.mov"),
                "a value is never replaced again");
    }

    @Test
    public void aContentThatIsNotTheFileAsIsIsCopiedToAWorkingFileFirst() throws IOException {
        WorkItem item = file("a.txt", "original").withContent(
                () -> new ByteArrayInputStream("rewritten".getBytes(StandardCharsets.UTF_8)), 9);
        ExecProcess action = action("out", null, null, "copy", "{in}", "{out}");

        WorkItem result = action.doProcess(item).getFirst();

        try (var in = result.openInputStream()) {
            assertEquals("rewritten", new String(in.readAllBytes(), StandardCharsets.UTF_8),
                    "the program read the content, not the source");
        }
        assertEquals(1, workFiles(), "the copy of the content is deleted once the program ended");
        action.afterItem(item);
        assertEquals(0, workFiles());
        action.afterAll();
    }

    @Test
    public void aCodeOutsideTheSuccessCodesIsAnErrorWithTheEndOfTheErrorOutputButNoArgument() throws IOException {
        WorkItem item = file("a.txt", "x");
        ExecProcess action = action(null, null, null, "exit", "4", "bad input", "token-s3cret");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> action.doProcess(item));

        assertTrue(e.getMessage().contains("ended with code 4: bad input"), e.getMessage());
        assertFalse(e.getMessage().contains("s3cret"), e.getMessage());
        assertFalse(e.getMessage().contains("exit"), "no argument in the message: " + e.getMessage());
        assertEquals(0, workFiles(), "nothing kept for a failed item");
        action.afterAll();
    }

    @Test
    public void aCodeListedAsASuccessIsOne() throws IOException {
        WorkItem item = file("a.txt", "x");
        ExecProcess action = action(null, null, List.of(0, 1), "copy-exit", "{in}", "{out}", "1");

        assertEquals(1, action.doProcess(item).size());
        action.afterAll();
        ExecProcess strict = action(null, null, null, "copy-exit", "{in}", "{out}", "1");
        assertThrows(IllegalStateException.class, () -> strict.doProcess(item), "0 only by default");
        strict.afterAll();
    }

    @Test
    public void aProgramThatWritesNothingIsAnError() throws IOException {
        WorkItem item = file("a.txt", "x");
        ExecProcess action = action(null, null, null, "lines", "nothing done", "{out}");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> action.doProcess(item));

        assertTrue(e.getMessage().contains("wrote no file, or an empty one: nothing done"), e.getMessage());
        assertEquals(0, workFiles());
        action.afterAll();
    }

    @Test
    public void aProgramBeyondTheTimeLimitIsStoppedAndTheItemIsInError() throws IOException {
        WorkItem item = file("a.txt", "x");
        ExecProcess action = action(null, 1, null, "sleep", "60000", "{out}");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> action.doProcess(item));

        assertTrue(e.getMessage().contains("time limit of 1 s exceeded"), e.getMessage());
        action.afterAll();
    }

    @Test
    public void aCancelIsAnInterruptionNotAnItemError() throws Exception {
        WorkItem item = file("a.txt", "x");
        ExecProcess action = action(null, null, null, "sleep", "60000", "{out}");
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread runner = Thread.ofVirtual().start(() -> {
            try {
                action.doProcess(item);
            } catch (Throwable t) {
                thrown.set(t);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (workFolders() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        Thread.sleep(300); // the program is started

        runner.interrupt();
        assertTrue(runner.join(Duration.ofSeconds(30)));

        assertInstanceOf(CancellationException.class, thrown.get());
        assertTrue(interrupted.get(), "the interrupt status is set again");
        assertEquals(0, workFiles());
        action.afterAll();
    }

    private long workFolders() throws IOException {
        if (!Files.exists(work())) {
            return 0;
        }
        try (Stream<Path> files = Files.walk(work())) {
            return files.filter(Files::isDirectory).count();
        }
    }

    @Test
    public void aMissingProgramIsAnError() throws IOException {
        ExecProcess action = new ExecProcess(work(), ProgramResolver.Environment.current());
        action.setPlugin(plugin());
        action.loadConfig(new JsonObject());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> action.doProcess(file("a.txt", "x")));
        assertEquals("exec.process: the program is missing", e.getMessage());
    }

    @Test
    public void aProgramThatIsNotFoundIsAnErrorNamingIt() throws IOException {
        ExecProcess action = programAction(tempDir.resolve("no-such-program").toString(),
                ProgramResolver.Environment.current());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> action.doProcess(file("a.txt", "x")));
        assertTrue(e.getMessage().contains("no-such-program is not found"), e.getMessage());
        ExecProcess bare = programAction("no-such-program", new ProgramResolver.Environment(
                tempDir.toString(), null, true));
        assertThrows(IllegalArgumentException.class, () -> bare.doProcess(file("b.txt", "x")));
        assertFalse(Files.exists(work()), "nothing started, nothing written");
    }

    @Test
    public void aBatchFileIsRefusedOnWindows() throws IOException {
        Path batch = Files.writeString(Files.createDirectories(tempDir.resolve("bin")).resolve("convert.cmd"), "x");
        ExecProcess action = programAction("convert", new ProgramResolver.Environment(batch.getParent().toString(),
                ".EXE;.CMD", true));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> action.doProcess(file("a.txt", "x")));
        assertTrue(e.getMessage().startsWith("convert is a batch file (.bat, .cmd): not supported"), e.getMessage());
    }

    private ExecProcess programAction(String program, ProgramResolver.Environment environment) {
        JsonObject config = new JsonObject();
        config.addProperty("program", program);
        JsonArray args = new JsonArray();
        args.add("{in}");
        args.add("{out}");
        config.add("arguments", args);
        ExecProcess action = new ExecProcess(work(), environment);
        action.setPlugin(plugin());
        action.loadConfig(config);
        return action;
    }

    @Test
    public void theDryRunRenamesWithoutRunningAnything() throws IOException {
        WorkItem item = file("clip.mov", "x");
        ExecProcess action = action("mp4", null, null, "copy", "{in}", "{out}");

        Optional<List<WorkItem>> result = action.dryRun(item.copyForDryRun());

        assertTrue(result.isPresent());
        assertEquals("clip.mp4", result.get().getFirst().getMetadatas().display().get("name"));
        assertFalse(Files.exists(work()), "nothing run, nothing written");
    }

    @Test
    public void argumentsWithoutOutGiveAWarning() {
        assertEquals(List.of("exec.process: {out} is in none of the arguments, while the program must write the file"
                + " named by {out}"), action(null, null, null, "copy", "{in}", "x").configWarnings());
        assertEquals(List.of(), action(null, null, null, "copy", "{in}", "--out={out}").configWarnings());
    }

    @Test
    public void theRunFoldersLeftMoreThanADayAgoAreRemovedBeforeTheRun() throws IOException {
        Path old = Files.createDirectories(work().resolve("run-old").resolve("1"));
        Files.writeString(old.resolve("left.jpg"), "x");
        Files.setLastModifiedTime(work().resolve("run-old"),
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2)));
        Path recent = Files.createDirectories(work().resolve("run-recent"));
        Path other = Files.createDirectories(work().resolve("kept"));
        ExecProcess action = action(null, null, null, "copy", "{in}", "{out}");

        action.beforeAll();

        assertFalse(Files.exists(work().resolve("run-old")));
        assertTrue(Files.exists(recent), "maybe a run going on");
        assertTrue(Files.exists(other), "not a run folder");
    }

    @Test
    public void theConfigurationShowsNoArgument() {
        ExecConfig config = new ExecConfig("prog", new ArrayList<>(List.of("--token=s3cret")), ".JPG", null, null);

        assertFalse(config.toString().contains("s3cret"));
        assertEquals("JPG", config.outputExtension(), "the leading dot is dropped");
        assertEquals(List.of(0), config.successCodes());
        assertEquals(600, config.timeoutSeconds());
    }
}

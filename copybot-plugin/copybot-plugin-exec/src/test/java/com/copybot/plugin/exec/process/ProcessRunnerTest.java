package com.copybot.plugin.exec.process;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

public class ProcessRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    public void aProgramThatSucceedsGivesItsCodeAndTheEndOfItsErrorOutput() throws Exception {
        Path in = Files.writeString(tempDir.resolve("in.txt"), "content");
        Path out = tempDir.resolve("out.txt");

        ProcessRunner.Result result = ProcessRunner.run(FakeProgram.command("copy", in.toString(), out.toString()),
                Duration.ofSeconds(60), null);

        assertEquals(0, result.exitCode());
        assertFalse(result.timedOut());
        assertEquals("copied", result.stderrTail());
        assertEquals("content", Files.readString(out));
    }

    @Test
    public void aFailingProgramGivesItsCode() throws Exception {
        ProcessRunner.Result result = ProcessRunner.run(FakeProgram.command("exit", "7", "it went wrong"),
                Duration.ofSeconds(60), null);

        assertEquals(7, result.exitCode());
        assertEquals("it went wrong", result.stderrTail());
    }

    @Test
    public void theProgramReadsNoInput() throws Exception {
        ProcessRunner.Result result = ProcessRunner.run(FakeProgram.command("stdin"), Duration.ofSeconds(60), null);

        assertEquals(0, result.exitCode(), "its input is closed: it does not wait for one");
    }

    @Test
    public void onlyTheLastKilobytesOfTheErrorOutputAreKeptFromTheStartOfALine() throws Exception {
        ProcessRunner.Result result = ProcessRunner.run(FakeProgram.command("stderr", "100000"),
                Duration.ofSeconds(60), null);

        assertEquals(3, result.exitCode());
        String tail = result.stderrTail();
        assertTrue(tail.endsWith("THE END"), tail);
        assertTrue(tail.startsWith("..." + System.lineSeparator() + "line "), tail.substring(0, 20));
        assertTrue(tail.getBytes(StandardCharsets.UTF_8).length <= ProcessRunner.STDERR_TAIL + 10, "" + tail.length());
        assertFalse(tail.contains("line 0\n"), "the beginning is dropped");
    }

    @Test
    public void aCarriageReturnEndsALineForTheCutOfTheTail() throws Exception {
        ProcessRunner.Result result = ProcessRunner.run(FakeProgram.command("stderr-cr", "100000"),
                Duration.ofSeconds(60), null);

        String tail = result.stderrTail();
        assertTrue(tail.startsWith("..." + System.lineSeparator() + "frame "), tail.substring(0, 20));
        assertTrue(tail.endsWith("THE END"), tail);
    }

    @Test
    public void theErrorOutputIsReadAsUtf8() throws Exception {
        ProcessRunner.Result result = ProcessRunner.run(FakeProgram.command("utf8"), Duration.ofSeconds(60), null);

        assertEquals(2, result.exitCode());
        assertEquals("caf\u00e9 \u2713", result.stderrTail());
    }

    @Test
    public void aProgramBeyondItsTimeLimitIsKilledWithItsChildren() throws Exception {
        Path pids = tempDir.resolve("pids.txt");
        CompletableFuture<ProcessRunner.Result> run = CompletableFuture.supplyAsync(() -> {
            try {
                return ProcessRunner.run(FakeProgram.command("child", pids.toString()), Duration.ofSeconds(5), null);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        long[] family = waitForPids(pids);
        ProcessHandle child = ProcessHandle.of(family[0]).orElseThrow();
        ProcessHandle program = ProcessHandle.of(family[1]).orElseThrow();

        ProcessRunner.Result result = run.get(60, TimeUnit.SECONDS);

        assertTrue(result.timedOut());
        child.onExit().get(10, TimeUnit.SECONDS);
        program.onExit().get(10, TimeUnit.SECONDS);
        assertFalse(child.isAlive(), "the child of the program is killed too");
        assertFalse(program.isAlive());
    }

    @Test
    public void theLinesOfTheErrorOutputAreHandedOver() throws Exception {
        List<String> lines = Collections.synchronizedList(new ArrayList<>());

        ProcessRunner.run(FakeProgram.command("lines", "out_time_us=1000", "progress=end"), Duration.ofSeconds(60),
                lines::add);

        assertEquals(List.of("out_time_us=1000", "progress=end"), lines);
    }

    @Test
    public void theLinesOfTheStandardOutputAreHandedOverWhenAsked() throws Exception {
        List<String> out = Collections.synchronizedList(new ArrayList<>());
        List<String> err = Collections.synchronizedList(new ArrayList<>());

        ProcessRunner.Result result = ProcessRunner.run(FakeProgram.command("stdout", "first", "second"),
                Duration.ofSeconds(60), err::add, out::add);

        assertEquals(0, result.exitCode());
        assertEquals(List.of("first", "second"), out);
        assertEquals(List.of(), err, "the standard output is not mixed with the error output");
        assertEquals("", result.stderrTail());
    }

    @Test
    public void aProgramBeyondItsTimeLimitIsKilled() throws Exception {
        long start = System.nanoTime();

        ProcessRunner.Result result = ProcessRunner.run(FakeProgram.command("sleep", "60000"),
                Duration.ofMillis(1500), null);

        assertTrue(result.timedOut());
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(30), "it did not wait for the program");
    }

    @Test
    public void aCancelKillsTheProgramAndItsChildrenAndTheInterruptionGoesOn() throws Exception {
        Path pids = tempDir.resolve("pids.txt");
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread runner = Thread.ofVirtual().start(() -> {
            try {
                ProcessRunner.run(FakeProgram.command("child", pids.toString()), null, null);
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        long[] family = waitForPids(pids);
        ProcessHandle child = ProcessHandle.of(family[0]).orElseThrow();
        ProcessHandle program = ProcessHandle.of(family[1]).orElseThrow();

        runner.interrupt();
        assertTrue(runner.join(Duration.ofSeconds(30)), "the run returned");

        assertInstanceOf(InterruptedException.class, thrown.get());
        child.onExit().get(10, TimeUnit.SECONDS);
        program.onExit().get(10, TimeUnit.SECONDS);
        assertFalse(child.isAlive(), "the child of the program is killed too");
        assertFalse(program.isAlive());
    }

    @Test
    public void aProgramThatCannotStartIsAnIOException() {
        assertThrows(IOException.class, () -> ProcessRunner.run(
                List.of(tempDir.resolve("no-such-program").toString()), Duration.ofSeconds(5), null));
    }

    @Test
    public void theArgumentsAreNeverLogged() throws Exception {
        Logger logger = Logger.getLogger(ProcessRunner.class.getName());
        List<String> logged = Collections.synchronizedList(new ArrayList<>());
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record.getMessage() + " " + java.util.Arrays.toString(record.getParameters()));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Level level = logger.getLevel();
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
        try {
            ProcessRunner.run(FakeProgram.command("exit", "1", "token-s3cret"), Duration.ofSeconds(60), null);
            ProcessRunner.run(FakeProgram.command("sleep", "60000", "token-s3cret"), Duration.ofMillis(500), null);
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(level);
        }

        assertFalse(logged.isEmpty(), "the runs are logged");
        assertTrue(logged.stream().noneMatch(line -> line.contains("s3cret") || line.contains("sleep")),
                logged.toString());
    }

    private static long[] waitForPids(Path pids) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!Files.exists(pids)) {
            assertTrue(System.nanoTime() < deadline, "the program did not start its child");
            CompletableFuture.runAsync(() -> { }, CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS)).get();
        }
        String[] parts = Files.readString(pids).strip().split(" ");
        return new long[]{Long.parseLong(parts[0]), Long.parseLong(parts[1])};
    }
}

package com.copybot.plugin.exec.process;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Runs an external program on one item (design spec §2): its input closed, its output discarded, the end of its
 * error output kept for the message of a failure. A time limit or an interruption of the calling thread (a cancel)
 * kills the program and every process it started.
 * <p>
 * The arguments may hold resolved secrets: they are never logged nor put in a message, only the program and their
 * number.
 */
public final class ProcessRunner {

    /** The end of the error output kept: 8 KiB. */
    public static final int STDERR_TAIL = 8 * 1024;
    /** The time a killed process gets to end before it is killed forcibly. */
    static final Duration KILL_GRACE = Duration.ofSeconds(5);

    private static final System.Logger LOGGER = System.getLogger(ProcessRunner.class.getName());
    /** A longer line of the error output is cut before it is handed over. */
    private static final int MAX_LINE = 8 * 1024;
    /** The error output is read as UTF-8: what most programs write, on Windows too. */
    private static final Charset CHARSET = StandardCharsets.UTF_8;
    /** The null device: the input of the programs. */
    private static final File NULL_FILE = new File(
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows") ? "NUL" : "/dev/null");

    /**
     * How the program ended.
     *
     * @param exitCode   its exit code; meaningless when it timed out
     * @param timedOut   true when the time limit was reached: the program was killed
     * @param stderrTail the end of its error output (at most {@link #STDERR_TAIL} bytes, from the start of a line),
     *                   stripped; "" when it wrote none
     */
    public record Result(int exitCode, boolean timedOut, String stderrTail) {
    }

    private ProcessRunner() {
    }

    /**
     * Runs the program to its end.
     *
     * @param command     the program (a path, or a name looked up in the PATH) then its arguments
     * @param timeout     the time limit; null, zero or negative: none
     * @param stderrLines receives each line of the error output, on a thread of its own (an item's
     *                    {@code ItemProgress} must be captured before); null: none
     * @throws IOException          the program cannot be started
     * @throws InterruptedException the calling thread was interrupted: the program and its descendants were killed,
     *                              the interrupt status is cleared as usual
     */
    public static Result run(List<String> command, Duration timeout, Consumer<String> stderrLines)
            throws IOException, InterruptedException {
        return run(command, timeout, stderrLines, null);
    }

    /**
     * Runs the program to its end, handing over the lines of its standard output too (e.g. the version a program gives).
     *
     * @param stdoutLines receives each line of the standard output, on a thread of its own; null: the output is
     *                    discarded
     * @see #run(List, Duration, Consumer)
     */
    public static Result run(List<String> command, Duration timeout, Consumer<String> stderrLines,
                             Consumer<String> stdoutLines) throws IOException, InterruptedException {
        String program = command.getFirst();
        LOGGER.log(System.Logger.Level.DEBUG, "Running {0} with {1} arguments", program, command.size() - 1);
        Process process = new ProcessBuilder(command)
                .redirectInput(ProcessBuilder.Redirect.from(NULL_FILE)) // no input: a program reading one ends at once
                .redirectOutput(stdoutLines == null ? ProcessBuilder.Redirect.DISCARD : ProcessBuilder.Redirect.PIPE)
                .start();
        StderrReader reader = new StderrReader(process.getErrorStream(), stderrLines);
        Thread readerThread = Thread.ofVirtual().name("stderr-" + process.pid()).start(reader);
        Thread outThread = stdoutLines == null ? null : Thread.ofVirtual().name("stdout-" + process.pid())
                .start(new StderrReader(process.getInputStream(), stdoutLines));
        try {
            return wait(process, program, timeout, reader, readerThread);
        } finally {
            if (outThread != null) {
                finish(process.getInputStream(), outThread);
            }
        }
    }

    private static Result wait(Process process, String program, Duration timeout, StderrReader reader,
                               Thread readerThread) throws InterruptedException {
        boolean ended;
        try {
            if (timeout == null || timeout.isZero() || timeout.isNegative()) {
                process.waitFor();
                ended = true;
            } else {
                ended = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            LOGGER.log(System.Logger.Level.DEBUG, "{0} interrupted: killed", program);
            killTree(process);
            finish(process.getErrorStream(), readerThread);
            throw e;
        }
        if (!ended) {
            LOGGER.log(System.Logger.Level.DEBUG, "{0} reached its time limit: killed", program);
            killTree(process);
        }
        finish(process.getErrorStream(), readerThread);
        return new Result(ended ? process.exitValue() : -1, !ended, reader.tail());
    }

    /**
     * Waits a little for the end of an output of the program: a descendant still alive may hold it open, it is then
     * closed so that the reader ends.
     */
    private static void finish(InputStream stream, Thread readerThread) {
        boolean interrupted = Thread.interrupted();
        try {
            if (!readerThread.join(KILL_GRACE)) {
                stream.close();
                readerThread.join(KILL_GRACE);
            }
        } catch (InterruptedException e) {
            interrupted = true;
        } catch (IOException e) {
            // closing a pipe: nothing to do about it
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Kills the process and its descendants: asked to end first ({@code destroy}), killed forcibly the ones still
     * alive after {@link #KILL_GRACE}. Never interrupted: an interrupt during the wait kills forcibly at once.
     */
    static void killTree(Process process) {
        Set<ProcessHandle> family = new LinkedHashSet<>(process.descendants().toList());
        family.forEach(ProcessHandle::destroy); // the descendants first: the program cannot start new ones
        // the ones started meanwhile; once the program is gone its descendants are no longer found from it
        process.descendants().filter(family::add).forEach(ProcessHandle::destroy);
        family.add(process.toHandle());
        process.destroy();
        long deadline = System.nanoTime() + KILL_GRACE.toNanos();
        boolean interrupted = false;
        for (ProcessHandle member : family) {
            long left = deadline - System.nanoTime();
            if (interrupted || left <= 0) {
                break;
            }
            try {
                member.onExit().get(left, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                interrupted = true;
            } catch (ExecutionException | TimeoutException e) {
                break; // the rest is killed forcibly
            }
        }
        if (process.isAlive()) {
            family.addAll(process.descendants().toList()); // started while it was asked to end
        }
        family.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Reads an output (the error output, or the standard one when asked) to its end: keeps its last bytes, hands its
     * lines over.
     */
    private static final class StderrReader implements Runnable {

        private final InputStream in;
        private final Consumer<String> lines;
        private final byte[] ring = new byte[STDERR_TAIL];
        private long total;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();

        StderrReader(InputStream in, Consumer<String> lines) {
            this.in = in;
            this.lines = lines;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[8192];
            try (in) {
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    keep(buffer, read);
                }
            } catch (IOException e) {
                // the stream was closed (a descendant kept it open) or broke: what was read is kept
            }
            synchronized (this) {
                handOver();
            }
        }

        private synchronized void keep(byte[] buffer, int length) {
            for (int i = 0; i < length; i++) {
                byte b = buffer[i];
                ring[(int) (total++ % STDERR_TAIL)] = b;
                if (lines != null) {
                    if (b == '\n' || b == '\r') {
                        handOver();
                    } else if (line.size() < MAX_LINE) {
                        line.write(b);
                    }
                }
            }
        }

        private void handOver() {
            if (lines != null && line.size() > 0) {
                String text = line.toString(CHARSET);
                line.reset();
                try {
                    lines.accept(text);
                } catch (RuntimeException e) {
                    // a listener failing must not stop the reading (the program would block on a full pipe)
                    LOGGER.log(System.Logger.Level.DEBUG, "stderr listener failed", e);
                }
            }
        }

        /** The kept bytes, from the start of a line when the beginning was dropped. */
        synchronized String tail() {
            int length = (int) Math.min(total, STDERR_TAIL);
            byte[] bytes = new byte[length];
            int start = (int) (total <= STDERR_TAIL ? 0 : total % STDERR_TAIL);
            for (int i = 0; i < length; i++) {
                bytes[i] = ring[(start + i) % STDERR_TAIL];
            }
            int from = 0;
            if (total > STDERR_TAIL) {
                while (from < length && bytes[from] != '\n' && bytes[from] != '\r') {
                    from++;
                }
                from = Math.min(length, from + 1);
            }
            String text = new String(bytes, from, length - from, CHARSET).strip();
            return total > STDERR_TAIL ? "..." + System.lineSeparator() + text : text;
        }
    }
}

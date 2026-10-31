package com.copybot.plugin.exec.actions;

import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.exec.ffmpeg.Ffmpeg;
import com.copybot.plugin.exec.ffmpeg.FfmpegSettings;
import com.copybot.plugin.exec.ffmpeg.FfmpegSetup;
import com.copybot.plugin.exec.ffmpeg.FfmpegVersion;
import com.copybot.plugin.exec.process.ItemFiles;
import com.copybot.plugin.exec.process.ProcessRunner;
import com.copybot.plugin.exec.process.WorkFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * What ffmpeg.image and ffmpeg.thumbnail share: a JPEG written by ffmpeg from the file of the item into a working
 * file of the step (spec exec-ffmpeg §2), the item made from it; a HEIC photo refused before ffmpeg 7.1. The
 * processor is the resource of every item.
 *
 * @param <C> the configuration record
 */
public abstract class FfmpegJpegAction<C extends Record & FfmpegSettings> extends FfmpegAction<C> {

    private static final String WORK_FOLDER = "copybot-exec";
    /** The extensions of the HEIC photos (HEIF with HEVC pictures), read by ffmpeg 7.1 and later only. */
    private static final Set<String> HEIF_EXTENSIONS = Set.of("heic", "heif");

    private final WorkFolder work;

    FfmpegJpegAction(Path workFolder, Supplier<FfmpegSetup.Context> context) {
        super(context);
        this.work = new WorkFolder(workFolder);
    }

    FfmpegJpegAction() {
        this(Path.of(System.getProperty("java.io.tmpdir"), WORK_FOLDER), FfmpegSetup.Context::current);
    }

    /** The code of the action, at the start of its messages. */
    protected abstract String code();

    /** The time limit of one run of ffmpeg in seconds, 0: none. */
    protected abstract int timeoutSeconds();

    /** The arguments of ffmpeg writing the JPEG {@code out} from {@code in}. */
    @FunctionalInterface
    protected interface JpegArguments {
        List<String> of(Ffmpeg ffmpeg, Path in, Path out) throws IOException, InterruptedException;
    }

    @Override
    public Set<String> requiredResources(WorkItem item) {
        return Set.of("cpu");
    }

    @Override
    public Set<Path> touchedPaths(WorkItem item) {
        return Set.of(work.folder());
    }

    @Override
    public void beforeAll() {
        work.removeStaleRuns();
        super.beforeAll();
    }

    @Override
    public void afterItem(WorkItem item) {
        work.release(item);
    }

    @Override
    public void afterAll() {
        work.close();
        super.afterAll();
    }

    static boolean isHeif(String name) {
        return HEIF_EXTENSIONS.contains(ItemFiles.extension(name).toLowerCase(Locale.ROOT));
    }

    /**
     * The JPEG of the item, shown as {@code outName}; empty when ffmpeg found no video stream in a video (its map
     * {@code 0:V:0}).
     *
     * @throws IllegalStateException a HEIC photo with an ffmpeg before 7.1, ffmpeg failed or wrote nothing: the
     *                               localised reason
     * @throws CancellationException a cancel: ffmpeg was killed, the interrupt status is set again
     */
    protected Optional<WorkItem> toJpeg(WorkItem item, String outName, JpegArguments arguments) {
        String name = ItemFiles.name(item);
        Path itemFolder = null;
        boolean kept = false;
        try {
            Ffmpeg ffmpeg = ffmpeg();
            FfmpegVersion version = ffmpeg.version();
            if (isHeif(name) && !version.atLeast(7, 1)) {
                throw new IllegalStateException(message("ffmpeg.jpeg.error.heic-version", code(), name,
                        version.text().isEmpty() ? "?" : version.text()));
            }
            itemFolder = work.newItemFolder();
            Path in = ItemFiles.input(item, itemFolder);
            Path out = itemFolder.resolve(ItemFiles.fileName(outName));
            ProcessRunner.Result result = run(ffmpeg, arguments.of(ffmpeg, in, out), name, null);
            if (result.exitCode() != 0) {
                String stderr = result.stderrTail().strip();
                if (stderr.contains(FfmpegVideo.NO_VIDEO_STREAM)) {
                    return Optional.empty();
                }
                throw new IllegalStateException(stderr.isEmpty()
                        ? message("ffmpeg.jpeg.error.code", code(), name, String.valueOf(result.exitCode()))
                        : message("ffmpeg.jpeg.error.code-stderr", code(), name, String.valueOf(result.exitCode()),
                        stderr));
            }
            if (!hasOutput(out)) {
                Optional<List<String>> retry = retryArguments(ffmpeg, in, out, name);
                if (retry.isPresent()) {
                    result = run(ffmpeg, retry.get(), name, null);
                    if (result.exitCode() != 0) {
                        String stderr = result.stderrTail().strip();
                        throw new IllegalStateException(stderr.isEmpty()
                                ? message("ffmpeg.jpeg.error.code", code(), name, String.valueOf(result.exitCode()))
                                : message("ffmpeg.jpeg.error.code-stderr", code(), name,
                                String.valueOf(result.exitCode()), stderr));
                    }
                }
            }
            if (!hasOutput(out)) {
                throw new IllegalStateException(message("ffmpeg.jpeg.error.no-output", code(), name));
            }
            WorkItem produced = ItemFiles.produced(item, out, outName);
            written(item, out, produced);
            if (!in.equals(item.getContentFile().orElse(null))) {
                Files.deleteIfExists(in); // the copy of the content: no longer needed
            }
            work.keep(item, itemFolder);
            kept = true;
            return Optional.of(produced);
        } catch (InterruptedException e) {
            // a cancel: ffmpeg was killed; the interruption goes on to the engine, never an item error
            Thread.currentThread().interrupt();
            CancellationException cancelled = new CancellationException("ffmpeg interrupted");
            cancelled.initCause(e);
            throw cancelled;
        } catch (IOException e) {
            throw new IllegalStateException(message("ffmpeg.jpeg.error.io", code(), name,
                    e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage())));
        } finally {
            if (!kept && itemFolder != null) {
                WorkFolder.deleteTree(itemFolder);
            }
        }
    }

    private static boolean hasOutput(Path out) throws IOException {
        return Files.isRegularFile(out) && Files.size(out) > 0;
    }

    /**
     * The arguments of a second run when ffmpeg exited 0 without writing the JPEG (a seek past the last frame);
     * empty by default: that is a failure.
     */
    protected Optional<List<String>> retryArguments(Ffmpeg ffmpeg, Path in, Path out, String name)
            throws IOException, InterruptedException {
        return Optional.empty();
    }

    /** Once the JPEG {@code out} is written, before the item made from it is handed on: nothing by default. */
    protected void written(WorkItem item, Path out, WorkItem produced) throws IOException {
    }

    /**
     * Runs ffmpeg within the time limit of the action.
     *
     * @param name        the name of the item, for the messages
     * @param stderrLines each line of the error output, null: none
     * @throws IllegalStateException it cannot be started, or the time limit is exceeded: the localised reason
     */
    protected ProcessRunner.Result run(Ffmpeg ffmpeg, List<String> arguments, String name,
                                       Consumer<String> stderrLines) throws InterruptedException {
        int timeout = timeoutSeconds();
        ProcessRunner.Result result;
        try {
            result = ProcessRunner.run(ffmpeg.command(arguments), Duration.ofSeconds(timeout), stderrLines);
        } catch (IOException e) {
            throw new IllegalStateException(message("ffmpeg.error.start", ffmpeg.executable(),
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
        if (result.timedOut()) {
            throw new IllegalStateException(message("ffmpeg.jpeg.error.timeout", code(), name,
                    String.valueOf(timeout)));
        }
        return result;
    }
}

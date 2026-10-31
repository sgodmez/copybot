package com.copybot.plugin.exec.actions;

import com.copybot.plugin.api.action.ItemProgress;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.exec.ffmpeg.Ffmpeg;
import com.copybot.plugin.exec.ffmpeg.FfmpegSetup;
import com.copybot.plugin.exec.ffmpeg.VideoArguments;
import com.copybot.plugin.exec.ffmpeg.VideoArguments.Codec;
import com.copybot.plugin.exec.ffmpeg.VideoArguments.Encoder;
import com.copybot.plugin.exec.ffmpeg.VideoArguments.Quality;
import com.copybot.plugin.exec.ffmpeg.VideoProgress;
import com.copybot.plugin.exec.process.ItemFiles;
import com.copybot.plugin.exec.process.ProcessRunner;
import com.copybot.plugin.exec.process.WorkFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;

/**
 * ffmpeg.video: transcodes a video into an MP4, H.265 by default, by the processor or a hardware encoder (spec
 * exec-ffmpeg §5). An item that is no video passes unchanged: by its extension, then when ffmpeg finds no video
 * stream in it.
 * <p>
 * The encoder {@code auto} is the first hardware one whose probe works (one black frame), tried once per run in the
 * order of {@link Encoder#AUTO_ORDER}, else the processor; a hardware encoder named in the configuration is probed
 * too, the processor taking its place (with a warning in the log) when it does not work. A hardware encoder takes
 * the resource "gpu", the processor "cpu".
 */
public class FfmpegVideo extends FfmpegAction<FfmpegVideoConfig> {

    public static final String CODE = "ffmpeg.video";

    private static final System.Logger LOGGER = System.getLogger(FfmpegVideo.class.getName());
    private static final String WORK_FOLDER = "copybot-exec";
    /** The time a probe of an encoder gets. */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(30);
    /** The extensions of the files taken as videos; the others pass unchanged. */
    private static final Set<String> VIDEO_EXTENSIONS = Set.of("mp4", "m4v", "mov", "qt", "avi", "mkv", "webm",
            "wmv", "asf", "flv", "f4v", "mpg", "mpeg", "m2v", "mpe", "vob", "ts", "mts", "m2ts", "3gp", "3g2", "mxf",
            "ogv", "dv", "divx", "insv", "lrv");
    /** What ffmpeg says of an input without a video stream (the map 0:V:0). */
    static final String NO_VIDEO_STREAM = "matches no streams";

    private final WorkFolder work;

    public FfmpegVideo() {
        this(Path.of(System.getProperty("java.io.tmpdir"), WORK_FOLDER), FfmpegSetup.Context::current);
    }

    /** For the tests: the working files under this folder, ffmpeg looked for in this context. */
    FfmpegVideo(Path workFolder, Supplier<FfmpegSetup.Context> context) {
        super(context);
        this.work = new WorkFolder(workFolder);
    }

    @Override
    protected Class<FfmpegVideoConfig> getConfigClass() {
        return FfmpegVideoConfig.class;
    }

    private FfmpegVideoConfig config() {
        FfmpegVideoConfig config = getConfig();
        return config == null ? new FfmpegVideoConfig(null, null, null, null, null, null, null, null) : config;
    }

    /** The options of the configuration; the encoder may be auto. */
    private record Options(Codec codec, Quality quality, Integer maxHeight, boolean copyAudio, Encoder encoder) {
    }

    /**
     * @throws IllegalArgumentException a value of the configuration is unknown: the localised message
     */
    private Options options() {
        FfmpegVideoConfig config = config();
        Codec codec = value(Codec.class, "codec", config.codec(), Codec.H265);
        Quality quality = value(Quality.class, "quality", config.quality(), Quality.MEDIUM);
        Encoder encoder = value(Encoder.class, "encoder", config.encoder(), Encoder.AUTO);
        String audio = config.audio() == null || config.audio().isBlank() ? "aac"
                : config.audio().strip().toLowerCase(Locale.ROOT);
        if (!audio.equals("aac") && !audio.equals("copy")) {
            throw new IllegalArgumentException(message("ffmpeg.video.error.unknown", "audio", config.audio()));
        }
        return new Options(codec, quality, config.maxHeight(), audio.equals("copy"), encoder);
    }

    private <E extends Enum<E>> E value(Class<E> type, String field, String value, E absent) {
        if (value == null || value.isBlank()) {
            return absent;
        }
        return VideoArguments.parse(type, value).orElseThrow(
                () -> new IllegalArgumentException(message("ffmpeg.video.error.unknown", field, value)));
    }

    /** ffmpeg not found (see {@link FfmpegAction}), and a value of the configuration that is unknown. */
    @Override
    public List<String> configWarnings() {
        List<String> warnings = new ArrayList<>(super.configWarnings());
        try {
            options();
        } catch (IllegalArgumentException e) {
            warnings.add(e.getMessage());
        } catch (RuntimeException e) {
            // must not throw
        }
        return warnings;
    }

    /**
     * "gpu" for a hardware encoder, "cpu" for the processor or an item that is no video. The encoder is chosen here
     * when it is not yet (its probe, once per run): the resource of the item depends on it. ffmpeg may be downloaded
     * then: the engine binds the progress channel of the item here too, its percent is shown.
     */
    @Override
    public Set<String> requiredResources(WorkItem item) {
        if (!isVideo(ItemFiles.name(item))) {
            return Set.of("cpu");
        }
        try {
            Options options = options();
            return encoder(options).hardware() ? Set.of("gpu") : Set.of("cpu");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // the engine stops at the wait for the resources
            return Set.of("cpu");
        } catch (RuntimeException e) {
            return Set.of("cpu"); // the item fails in doProcess, with the reason
        }
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
    public List<WorkItem> doProcess(WorkItem item) {
        String name = ItemFiles.name(item);
        if (!isVideo(name)) {
            return List.of(item);
        }
        Options options = options();
        String outName = ItemFiles.withExtension(name, "mp4");
        Path itemFolder = null;
        boolean kept = false;
        try {
            Ffmpeg ffmpeg = ffmpeg();
            Encoder encoder = encoder(options);
            VideoArguments arguments = new VideoArguments(options.codec(), options.quality(), options.maxHeight(),
                    options.copyAudio(), encoder);
            itemFolder = work.newItemFolder();
            Path in = ItemFiles.input(item, itemFolder);
            Path out = itemFolder.resolve(ItemFiles.fileName(outName));
            int timeout = config().timeoutSeconds();
            VideoProgress progress = new VideoProgress(ItemProgress.current()); // captured on the item's thread
            ProcessRunner.Result result;
            try {
                result = ProcessRunner.run(ffmpeg.command(arguments.transcode(in, out)), Duration.ofSeconds(timeout),
                        progress);
            } catch (IOException e) {
                throw new IllegalStateException(message("ffmpeg.error.start", ffmpeg.executable(),
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
            if (result.timedOut()) {
                throw new IllegalStateException(message("ffmpeg.video.error.timeout", name, String.valueOf(timeout)));
            }
            String stderr = VideoProgress.withoutProgress(result.stderrTail());
            if (result.exitCode() != 0) {
                if (stderr.contains(NO_VIDEO_STREAM)) {
                    return List.of(item); // no video in it (a sound file): unchanged
                }
                throw new IllegalStateException(stderr.isEmpty()
                        ? message("ffmpeg.video.error.code", name, String.valueOf(result.exitCode()))
                        : message("ffmpeg.video.error.code-stderr", name, String.valueOf(result.exitCode()), stderr));
            }
            if (!Files.isRegularFile(out) || Files.size(out) == 0) {
                throw new IllegalStateException(message("ffmpeg.video.error.no-output", name));
            }
            WorkItem produced = ItemFiles.produced(item, out, outName);
            if (!in.equals(item.getContentFile().orElse(null))) {
                Files.deleteIfExists(in); // the copy of the content: no longer needed
            }
            work.keep(item, itemFolder);
            kept = true;
            return List.of(produced);
        } catch (InterruptedException e) {
            // a cancel: ffmpeg was killed; the interruption goes on to the engine, never an item error
            Thread.currentThread().interrupt();
            CancellationException cancelled = new CancellationException("ffmpeg interrupted");
            cancelled.initCause(e);
            throw cancelled;
        } catch (IOException e) {
            throw new IllegalStateException(message("ffmpeg.video.error.io", name, e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage())));
        } finally {
            if (!kept && itemFolder != null) {
                WorkFolder.deleteTree(itemFolder);
            }
        }
    }

    /** A video named in .mp4: the plan shows the right target; another item unchanged. Nothing is run. */
    @Override
    public Optional<List<WorkItem>> dryRun(WorkItem item) {
        String name = ItemFiles.name(item);
        if (isVideo(name)) {
            item.getMetadatas().display().put("name", ItemFiles.withExtension(name, "mp4"));
        }
        return Optional.of(List.of(item));
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

    static boolean isVideo(String name) {
        return VIDEO_EXTENSIONS.contains(ItemFiles.extension(name).toLowerCase(Locale.ROOT));
    }

    /**
     * The encoder of the run for these options: the processor as asked, else the hardware one chosen by its probe
     * (once per run for every ffmpeg.video step with the same ffmpeg settings).
     *
     * @throws IllegalStateException ffmpeg cannot be used in this run
     */
    private Encoder encoder(Options options) throws InterruptedException {
        if (options.encoder() == Encoder.CPU) {
            return Encoder.CPU;
        }
        Codec codec = options.codec();
        Encoder wanted = options.encoder();
        return oncePerRun("video.encoder." + codec.code() + "." + wanted.code(), () -> choose(ffmpeg(), codec, wanted));
    }

    private static Encoder choose(Ffmpeg ffmpeg, Codec codec, Encoder wanted) throws InterruptedException {
        if (wanted == Encoder.AUTO) {
            for (Encoder encoder : Encoder.AUTO_ORDER) {
                if (works(ffmpeg, codec, encoder)) {
                    LOGGER.log(System.Logger.Level.DEBUG, "ffmpeg.video: encoder {0}", encoder.ffmpegName(codec));
                    return encoder;
                }
            }
            LOGGER.log(System.Logger.Level.DEBUG, "ffmpeg.video: no hardware encoder works, encoder {0}",
                    Encoder.CPU.ffmpegName(codec));
            return Encoder.CPU;
        }
        if (works(ffmpeg, codec, wanted)) {
            return wanted;
        }
        LOGGER.log(System.Logger.Level.WARNING, "ffmpeg.video: the encoder {0} does not work here (its probe"
                + " failed), {1} is used instead", wanted.ffmpegName(codec), Encoder.CPU.ffmpegName(codec));
        return Encoder.CPU;
    }

    /** The probe of a hardware encoder: one black frame encoded to nothing. */
    private static boolean works(Ffmpeg ffmpeg, Codec codec, Encoder encoder) throws InterruptedException {
        try {
            ProcessRunner.Result result = ProcessRunner.run(ffmpeg.command(VideoArguments.probe(codec, encoder)),
                    PROBE_TIMEOUT, null);
            return !result.timedOut() && result.exitCode() == 0;
        } catch (IOException e) {
            return false;
        }
    }
}

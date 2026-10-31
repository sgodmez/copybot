package com.copybot.plugin.exec.ffmpeg;

import com.copybot.plugin.api.PluginFolders;
import com.copybot.plugin.api.action.ItemProgress;
import com.copybot.plugin.api.action.WorkStatus;
import com.copybot.plugin.exec.process.ProcessRunner;
import com.copybot.plugin.exec.process.ProgramResolver;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The ffmpeg of a run (spec exec-ffmpeg §4): found, downloaded when asked, and its version read once, for every
 * ffmpeg step and every item of the run.
 * <p>
 * The steps of a run share it: each ffmpeg action {@link #acquire}s it in its {@code beforeAll} and
 * {@link #release}s it in its {@code afterAll}. The plugin API has no run identity: the sharing is per phase (the
 * span between those calls) and keyed by the settings ({@code ffmpegPath}, {@code downloadFfmpeg}) and the
 * {@link Context}. The steps with the same settings get the same one while one of them holds it (two pipelines
 * running at once with the same settings share it too: the same ffmpeg); steps with other settings get their own;
 * once no step holds it, the next phase gets a new one (ffmpeg is looked for again). The work is done by the first
 * item asking for it, the other items wait for it: the progress of a download is shown in the status of every
 * waiting item and a cancel (an interruption) ends it, nothing kept. ffmpeg not found (a cheap lookup) is not
 * kept: the next item looks again, and finds the copy another step, with the download on, installed meanwhile. Any
 * other failure (the download, ffmpeg not giving its version) is kept: every item of the phase fails with it, without
 * trying again. Other works of the phase (the probe of the video encoders) are shared the same
 * way ({@link #once}).
 */
public final class FfmpegSetup {

    /** The time ffmpeg -version gets. */
    private static final Duration VERSION_TIMEOUT = Duration.ofSeconds(30);
    private static final System.Logger LOGGER = System.getLogger(FfmpegSetup.class.getName());
    /** The launcher of the real executables: the executable itself. */
    private static final Function<Path, List<String>> DIRECT = executable -> List.of(executable.toString());

    private static final Map<Key, FfmpegSetup> SHARED = new HashMap<>();
    private static Context current;

    /**
     * Where and how ffmpeg is looked for.
     *
     * @param environment       the PATH, and whether this is Windows
     * @param installFolder     gives the folder of the downloaded copies, null when there is none (no data folder)
     * @param downloadSupported true when ffmpeg can be downloaded here (Windows x64)
     * @param downloader        the download; null when not supported
     * @param launcher          the command running an executable, before its arguments (a fake one in the tests)
     */
    public record Context(ProgramResolver.Environment environment, Supplier<Path> installFolder,
                          boolean downloadSupported, FfmpegDownloader downloader,
                          Function<Path, List<String>> launcher) {

        /** A context whose folder of the downloaded copies is known. */
        public Context(ProgramResolver.Environment environment, Path installRoot, boolean downloadSupported,
                       FfmpegDownloader downloader, Function<Path, List<String>> launcher) {
            this(environment, () -> installRoot, downloadSupported, downloader, launcher);
        }

        /** The folder of the downloaded copies; null when there is none. */
        public Path installRoot() {
            return installFolder.get();
        }

        /** The one of this process: the data folder of the plugin, the real release. Always the same instance. */
        public static synchronized Context current() {
            if (current == null) {
                ProgramResolver.Environment environment = ProgramResolver.Environment.current();
                String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
                boolean supported = environment.windows() && (arch.equals("amd64") || arch.equals("x86_64"));
                current = new Context(environment, new DataFolder(), supported,
                        supported ? new FfmpegDownloader() : null, DIRECT);
            }
            return current;
        }
    }

    /**
     * {@code <data folder of the plugin>/ffmpeg}, resolved when first needed and kept once known: a failure (no data
     * folder yet) is tried again on the next call.
     */
    private static final class DataFolder implements Supplier<Path> {

        private volatile Path folder;

        @Override
        public Path get() {
            Path known = folder;
            if (known == null) {
                try {
                    known = PluginFolders.dataDirectory("exec").resolve("ffmpeg");
                    folder = known;
                } catch (IOException e) {
                    LOGGER.log(System.Logger.Level.WARNING, "No data folder for the exec plugin", e);
                }
            }
            return known;
        }
    }

    private record Key(String ffmpegPath, boolean download, Context context) {

        static Key of(FfmpegSettings settings, Context context) {
            String path = settings.ffmpegPath() == null ? "" : settings.ffmpegPath().strip();
            return new Key(path, Boolean.TRUE.equals(settings.downloadFfmpeg()), context);
        }
    }

    private final Key key;
    /** The steps holding it; guarded by {@link #SHARED}. */
    private int users;
    /** Held by the item doing the work, the others wait on it. */
    private final ReentrantLock lock = new ReentrantLock();
    private final List<ItemProgress> waiting = new CopyOnWriteArrayList<>();
    private volatile Ffmpeg found;
    private volatile FfmpegText failure;
    /** The results of {@link #once}, and the locks of their work, by key. */
    private final Map<String, Object> results = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    private FfmpegSetup(Key key) {
        this.key = key;
    }

    /** The one of the run for a step with these settings; to {@link #release} in its afterAll. */
    public static FfmpegSetup acquire(FfmpegSettings settings, Context context) {
        synchronized (SHARED) {
            FfmpegSetup setup = SHARED.computeIfAbsent(Key.of(settings, context), FfmpegSetup::new);
            setup.users++;
            return setup;
        }
    }

    /** One of its own, not shared (an action used outside a run). */
    public static FfmpegSetup unshared(FfmpegSettings settings, Context context) {
        return new FfmpegSetup(Key.of(settings, context));
    }

    /** The step no longer needs it: once no step holds it, the next run looks for ffmpeg again. */
    public void release() {
        synchronized (SHARED) {
            if (users > 0 && --users == 0) {
                SHARED.remove(key, this);
            }
        }
    }

    /**
     * ffmpeg, found (or downloaded) on the first call, then the same for every call.
     *
     * @param progress    the status of the calling item: shows the download while it waits
     * @param downloading the name of the download in the status ("Downloading ffmpeg")
     * @throws FfmpegUnavailableException ffmpeg cannot be used in this phase, the same failure for every call (but
     *                                    not found: looked for again on the next call)
     * @throws InterruptedException       the calling thread was interrupted (a cancel); nothing is kept, the next
     *                                    call tries again
     */
    public Ffmpeg get(ItemProgress progress, String downloading)
            throws FfmpegUnavailableException, InterruptedException {
        Ffmpeg ready = known();
        if (ready != null) {
            return ready;
        }
        ItemProgress status = progress == null ? ItemProgress.NONE : progress;
        waiting.add(status);
        try {
            lock.lockInterruptibly();
            try {
                ready = known();
                if (ready != null) {
                    return ready;
                }
                // a lookup only: not kept, the next item looks again (another step may download ffmpeg meanwhile)
                Optional<Path> executable = locate();
                try {
                    found = prepare(executable, downloading);
                    return found;
                } catch (FfmpegUnavailableException e) {
                    failure = e.text();
                    throw e;
                }
            } finally {
                lock.unlock();
            }
        } finally {
            waiting.remove(status);
        }
    }

    /** A work done once for the phase (e.g. the probe of the encoders). */
    @FunctionalInterface
    public interface Computation<T> {
        /** @return the result, not null */
        T compute() throws InterruptedException;
    }

    /**
     * The result of the work under this key, done by the first caller of the phase, the others waiting for it, then
     * the same for every call while the steps hold this setup (as ffmpeg itself). A failure (an exception) or an
     * interruption keeps nothing: the next call does it again.
     *
     * @throws InterruptedException the calling thread was interrupted (a cancel)
     */
    @SuppressWarnings("unchecked")
    public <T> T once(String key, Computation<T> computation) throws InterruptedException {
        Object known = results.get(key);
        if (known != null) {
            return (T) known;
        }
        ReentrantLock keyLock = locks.computeIfAbsent(key, k -> new ReentrantLock());
        keyLock.lockInterruptibly();
        try {
            known = results.get(key);
            if (known == null) {
                known = Objects.requireNonNull(computation.compute(), key);
                results.put(key, known);
            }
            return (T) known;
        } finally {
            keyLock.unlock();
        }
    }

    /** The result of a former call; null when there is none yet. */
    private Ffmpeg known() throws FfmpegUnavailableException {
        FfmpegText failed = failure;
        if (failed != null) {
            throw new FfmpegUnavailableException(failed);
        }
        return found;
    }

    /**
     * ffmpeg where it is looked for; empty when this step is to download it.
     *
     * @throws FfmpegUnavailableException not found, and not downloaded by this step
     */
    private Optional<Path> locate() throws FfmpegUnavailableException {
        Context context = key.context();
        FfmpegSettings settings = FfmpegSettings.of(key.ffmpegPath(), key.download());
        Optional<Path> executable = FfmpegLocator.locate(settings, context.environment(), context.installRoot());
        if (executable.isEmpty()
                && (!key.download() || !context.downloadSupported() || context.downloader() == null)) {
            throw new FfmpegUnavailableException(notFound(key.download(), context));
        }
        return executable;
    }

    /** ffmpeg found, or downloaded when {@code executable} is empty, and its version read. */
    private Ffmpeg prepare(Optional<Path> executable, String downloading)
            throws FfmpegUnavailableException, InterruptedException {
        Context context = key.context();
        if (executable.isEmpty()) {
            if (context.installRoot() == null) {
                throw new FfmpegUnavailableException(FfmpegText.of("ffmpeg.download.error.io", "plugin-data/exec",
                        "no data folder"));
            }
            report(downloading, -1);
            int[] shown = {-1};
            executable = Optional.of(context.downloader().install(context.installRoot(), (bytes, total) -> {
                int percent = total > 0 ? (int) Math.min(100, bytes * 100 / total) : -1;
                if (percent != shown[0]) { // one report per percent: the download reads 64 KiB at a time
                    shown[0] = percent;
                    report(downloading, percent);
                }
            }));
        }
        Path path = executable.get();
        List<String> launch = context.launcher().apply(path);
        FfmpegVersion version = version(path, launch);
        LOGGER.log(System.Logger.Level.DEBUG, "ffmpeg {0} found: {1}", version.text(), path);
        return new Ffmpeg(path, version, launch);
    }

    private void report(String downloading, int percent) {
        WorkStatus status = new WorkStatus(downloading, percent);
        for (ItemProgress progress : waiting) {
            progress.report(status);
        }
    }

    /** Runs {@code ffmpeg -version}. */
    private static FfmpegVersion version(Path executable, List<String> launch)
            throws FfmpegUnavailableException, InterruptedException {
        List<String> command = new ArrayList<>(launch);
        command.add("-version");
        List<String> output = Collections.synchronizedList(new ArrayList<>());
        ProcessRunner.Result result;
        try {
            result = ProcessRunner.run(command, VERSION_TIMEOUT, null, output::add);
        } catch (IOException e) {
            throw new FfmpegUnavailableException(FfmpegText.of("ffmpeg.error.start", executable,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), e);
        }
        if (result.timedOut() || result.exitCode() != 0) {
            throw new FfmpegUnavailableException(FfmpegText.of("ffmpeg.error.version", executable,
                    result.timedOut() ? "timeout" : "code " + result.exitCode()
                            + (result.stderrTail().isEmpty() ? "" : ": " + result.stderrTail())));
        }
        return FfmpegVersion.parse(String.join("\n", output));
    }

    /**
     * The warnings before a run (the {@code configWarnings} of an ffmpeg action): ffmpeg not found, and what will
     * happen. Nothing is run nor downloaded.
     */
    public static List<FfmpegText> warnings(FfmpegSettings settings, Context context) {
        try {
            if (FfmpegLocator.locate(settings, context.environment(), context.installRoot()).isPresent()) {
                return List.of();
            }
        } catch (FfmpegUnavailableException e) {
            return List.of(e.text());
        }
        boolean download = Boolean.TRUE.equals(settings.downloadFfmpeg());
        return List.of(download && context.downloadSupported()
                ? FfmpegText.of("ffmpeg.will-download") : notFound(download, context));
    }

    /** ffmpeg is not found, and is not downloaded: what to do. */
    private static FfmpegText notFound(boolean download, Context context) {
        return context.downloadSupported() && !download
                ? FfmpegText.of("ffmpeg.not-found.download") : FfmpegText.of("ffmpeg.not-found.install");
    }
}

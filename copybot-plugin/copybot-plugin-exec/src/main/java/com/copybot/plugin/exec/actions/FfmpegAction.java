package com.copybot.plugin.exec.actions;

import com.copybot.plugin.api.action.AbstractActionWithConfig;
import com.copybot.plugin.api.action.IProcessAction;
import com.copybot.plugin.api.action.ItemProgress;
import com.copybot.plugin.api.definition.IPlugin;
import com.copybot.plugin.exec.ffmpeg.Ffmpeg;
import com.copybot.plugin.exec.ffmpeg.FfmpegSettings;
import com.copybot.plugin.exec.ffmpeg.FfmpegSetup;
import com.copybot.plugin.exec.ffmpeg.FfmpegText;
import com.copybot.plugin.exec.ffmpeg.FfmpegUnavailableException;
import com.copybot.resources.CombinedResourceBundle;

import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

/**
 * What every ffmpeg action shares (spec exec-ffmpeg §4): its configuration record holds the common fields
 * ({@link FfmpegSettings}: {@code ffmpegPath}, {@code downloadFfmpeg}); ffmpeg is found once per run for all its
 * ffmpeg steps ({@link #ffmpeg()}); the warnings before the run say when it is not found.
 * <p>
 * A subclass overriding {@link #beforeAll}, {@link #afterAll} or {@link #configWarnings} calls the method of this
 * class.
 *
 * @param <C> the configuration record
 */
public abstract class FfmpegAction<C extends Record & FfmpegSettings> extends AbstractActionWithConfig<C>
        implements IProcessAction {

    private final Supplier<FfmpegSetup.Context> context;
    /** The ffmpeg of the run; null outside a run until an item asks for it. Guarded by this. */
    private FfmpegSetup setup;

    protected FfmpegAction() {
        this(FfmpegSetup.Context::current);
    }

    /** For the tests: ffmpeg looked for in this context. */
    FfmpegAction(Supplier<FfmpegSetup.Context> context) {
        this.context = context;
    }

    /** The common fields of the configuration; none set when there is no configuration. */
    protected FfmpegSettings settings() {
        C config = getConfig();
        return config == null ? FfmpegSettings.of(null, null) : config;
    }

    /** Takes the ffmpeg of the run, shared with its other ffmpeg steps. */
    @Override
    public void beforeAll() {
        FfmpegSetup taken = FfmpegSetup.acquire(settings(), context.get());
        FfmpegSetup former;
        synchronized (this) {
            former = setup;
            setup = taken;
        }
        if (former != null) {
            former.release();
        }
    }

    /** Lets the ffmpeg of the run go: the next run looks for it again. */
    @Override
    public void afterAll() {
        FfmpegSetup former;
        synchronized (this) {
            former = setup;
            setup = null;
        }
        if (former != null) {
            former.release();
        }
    }

    /** ffmpeg not found: what to do, or that it will be downloaded at the start of the run. */
    @Override
    public List<String> configWarnings() {
        try {
            return FfmpegSetup.warnings(settings(), context.get()).stream().map(this::message).toList();
        } catch (RuntimeException e) {
            return List.of(); // must not throw
        }
    }

    /**
     * ffmpeg, for the item of the calling thread: found (or downloaded, its progress in the status of the item) on
     * the first call of the run, the same afterwards.
     *
     * @throws IllegalStateException ffmpeg cannot be used in this run: the localised reason, the item fails
     * @throws InterruptedException  a cancel
     */
    protected Ffmpeg ffmpeg() throws InterruptedException {
        try {
            return setup().get(ItemProgress.current(), message("ffmpeg.download.status"));
        } catch (FfmpegUnavailableException e) {
            throw new IllegalStateException(message(e.text()), e);
        }
    }

    /**
     * The result of a work done once per run for the ffmpeg steps with the same ffmpeg settings (e.g. the probe of
     * the encoders), the first caller doing it, the others waiting: see {@link FfmpegSetup#once}.
     *
     * @throws InterruptedException a cancel; nothing kept
     */
    protected <T> T oncePerRun(String key, FfmpegSetup.Computation<T> computation) throws InterruptedException {
        return setup().once(key, computation);
    }

    private synchronized FfmpegSetup setup() {
        if (setup == null) {
            setup = FfmpegSetup.unshared(settings(), context.get()); // outside a run (no beforeAll)
        }
        return setup;
    }

    protected String message(FfmpegText text) {
        return message(text.key(), text.args().toArray());
    }

    /** A localised message of the plugin; its key and arguments when the action has no plugin (built by hand). */
    protected String message(String key, Object... args) {
        IPlugin plugin = getPlugin();
        CombinedResourceBundle bundle = plugin == null ? null : plugin.getResourceBundle();
        if (bundle == null) {
            return key + Arrays.toString(args);
        }
        String fullKey = "plugin.exec." + key;
        // a text without arguments is not a MessageFormat pattern: its braces are shown as written
        return args.length == 0 ? bundle.getString(fullKey) : bundle.getString(fullKey, args);
    }
}

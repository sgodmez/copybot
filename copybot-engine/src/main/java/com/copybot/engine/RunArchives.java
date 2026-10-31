package com.copybot.engine;

import com.copybot.engine.listing.ArchiveProgress;
import com.copybot.engine.listing.ArchiveSources;
import com.copybot.engine.listing.ArchiveWorkspace;
import com.copybot.engine.pipeline.ArchivesConfig;
import com.copybot.engine.pipeline.PipelineConfig;
import com.copybot.engine.pipeline.PipelineState;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/**
 * The archives of one execution (spec grouping/archives, "Archives distantes ou dans une archive"): their work
 * folder, opened at the first archive a listing meets, lives during the preparation and the copy (the entries are
 * read then), until the end of the copy, a cancel, a failed phase or the plan is abandoned ({@link #release()}).
 * Shared by the listings of a phase and by the engine (abandoned plan): thread-safe.
 */
final class RunArchives {

    /** Guards workspace, sources, released and releaseAtPhaseEnd. */
    private final Object lock = new Object();
    /** Null until the first archive met, and once released. */
    private ArchiveWorkspace workspace;
    /** Released: a late listing opens no work folder again, until the next listing phase starts afresh. */
    private boolean released;
    /** The phase under way leaves no copy to come: {@link #phaseEnded()} releases the archives. */
    private boolean releaseAtPhaseEnd;
    /** The archives unfolded by this execution, shared by its listings. */
    private ArchiveSources sources = new ArchiveSources();
    private volatile Path base = ArchiveWorkspace.defaultBase();

    private final Supplier<? extends RuntimeException> stopped;
    private final ArchiveProgress progress;

    /**
     * @param changed the state changed (archive counters)
     * @param stopped what a late listing throws when the archives are already released: the phase ended
     */
    RunArchives(PipelineState state, Runnable changed, Supplier<? extends RuntimeException> stopped) {
        this.stopped = stopped;
        this.progress = new ArchiveProgress() {
            @Override
            public void unfolded() {
                state.addArchiveUnfolded();
                changed.run();
            }

            @Override
            public void toDownload(long bytes) {
                state.addArchiveToDownload(bytes);
                changed.run();
            }
        };
    }

    /**
     * The archives block of the pipeline when unfolding is on (merge of the archive folders as configured); null:
     * no unfolding.
     */
    static ArchivesConfig unfoldConfig(PipelineConfig config) {
        ArchivesConfig archives = config == null ? null : config.archives();
        return archives == null || !archives.isUnfold() ? null : archives;
    }

    /** Where the work folders are made. */
    void setBase(Path base) {
        this.base = base;
    }

    /** The archive counters of the preparation. */
    ArchiveProgress progress() {
        return progress;
    }

    /**
     * The work folder, opened by the first archive met.
     *
     * @throws RuntimeException the one of {@code stopped} once released (a late listing unfolds nothing more), an
     *                          {@link UncheckedIOException} when the folder cannot be made
     */
    ArchiveWorkspace workspace() {
        synchronized (lock) {
            if (released) {
                throw stopped.get();
            }
            if (workspace == null) {
                try {
                    workspace = ArchiveWorkspace.open(base);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return workspace;
        }
    }

    ArchiveSources sources() {
        synchronized (lock) {
            return sources;
        }
    }

    /** The warnings of the unfolding (entries refused, archives too deep). */
    List<String> warnings() {
        return sources().warnings();
    }

    /** The warnings of the archives kept at the end of a copy in move mode. */
    List<String> keptWarnings() {
        return sources().keptWarnings();
    }

    /** At the start of a listing phase: the archives of a previous one are released, new ones are tracked. */
    void reset() {
        release();
        synchronized (lock) {
            sources = new ArchiveSources();
            released = false;
            releaseAtPhaseEnd = false;
        }
    }

    /** The phase under way leaves no copy to come: its archives are released once its tasks are over. */
    void releaseAtPhaseEnd() {
        synchronized (lock) {
            if (workspace == null) {
                released = true; // nothing to remove; a late listing opens nothing any more
                return;
            }
            releaseAtPhaseEnd = true;
        }
    }

    /**
     * At the end of a phase, once its tasks are shut down (no listing nor item reads an archive any more): the
     * archives are released when the phase asked for it ({@link #releaseAtPhaseEnd()}).
     */
    void phaseEnded() {
        boolean release;
        synchronized (lock) {
            release = releaseAtPhaseEnd;
            releaseAtPhaseEnd = false;
        }
        if (release) {
            release();
        }
    }

    /**
     * Removes the work folder of the archives (its archives closed): at the end of the copy, on a cancel or a
     * failure, or when the plan is abandoned without being executed (replaced by a new preparation, engine closed).
     * Idempotent, callable from any thread while no phase runs.
     */
    void release() {
        ArchiveWorkspace toClose;
        synchronized (lock) {
            toClose = workspace;
            workspace = null;
            released = true;
        }
        if (toClose != null) {
            toClose.close();
        }
    }
}

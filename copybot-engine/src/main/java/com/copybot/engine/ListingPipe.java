package com.copybot.engine;

import com.copybot.engine.listing.ArchiveUnfolder;
import com.copybot.engine.listing.Grouper;
import com.copybot.engine.listing.GroupingPresets;
import com.copybot.engine.listing.ListingFailures;
import com.copybot.engine.pipeline.ArchivesConfig;
import com.copybot.engine.pipeline.GroupingConfig;
import com.copybot.engine.pipeline.GroupingRuleConfig;
import com.copybot.engine.pipeline.PipelineConfig;
import com.copybot.engine.pipeline.PipelineState;
import com.copybot.engine.pipeline.PipelineStep;
import com.copybot.engine.pipeline.PipelineStepConfig;
import com.copybot.engine.resources.FootprintResolver;
import com.copybot.engine.resources.ResourceRegistry;
import com.copybot.plugin.api.action.IInAction;
import com.copybot.plugin.api.action.ListingSink;
import com.copybot.plugin.api.action.WorkItem;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The listings of an execution and their pipe (spec grouping/archives, "Où ça se place"): each listing holds its
 * footprint while it lists, its items go through the archives unfolded, then grouped, then emitted; a listing that
 * fails or stops short is remembered. Its grouping rules and archives block come from the pipeline configuration.
 */
final class ListingPipe {

    private final RunArchives archives;
    private final ResourceRegistry registry;
    private final PipelineState state;
    private final Runnable changed;
    private final Consumer<WorkItem> emit;
    private final ListingFailures failed;
    private final BooleanSupplier cancelled;

    /** Where the grouping rules and archives block are read; null: no pipe. */
    private volatile PipelineConfig config;

    /** Counted down by each listing of the phase as it ends. */
    private volatile CountDownLatch gate;
    /** A listing of the current preparation was stopped (cancel, interrupt) before its end. */
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicBoolean listingFailed = new AtomicBoolean(false);

    /**
     * @param changed   the state changed
     * @param emit      receives the items out of the pipe
     * @param failed    receives the listed items that failed in the pipe (e.g. an archive that cannot be unfolded)
     * @param cancelled the phase is being cancelled: what the pipe still holds is not emitted
     */
    ListingPipe(PipelineConfig config, RunArchives archives, ResourceRegistry registry, PipelineState state,
                Runnable changed, Consumer<WorkItem> emit, ListingFailures failed, BooleanSupplier cancelled) {
        this.config = config;
        this.archives = archives;
        this.registry = registry;
        this.state = state;
        this.changed = changed;
        this.emit = emit;
        this.failed = failed;
        this.cancelled = cancelled;
    }

    PipelineConfig config() {
        return config;
    }

    void setConfig(PipelineConfig config) {
        this.config = config;
    }

    /** At the start of the listings of a phase: the gate the items wait at until every listing is over. */
    CountDownLatch open(int listings) {
        gate = new CountDownLatch(listings);
        return gate;
    }

    /** A listing failed: the run is in error. */
    boolean failed() {
        return listingFailed.get();
    }

    /** At the start of a preparation: no listing stopped yet. */
    void clearStopped() {
        stopped.set(false);
    }

    /**
     * The listing calling the pipe stops because the phase is being stopped: it is marked incomplete now, even if the
     * plugin swallows the marker returned (to throw).
     */
    PhaseStopped stop() {
        stopped.set(true);
        return new PhaseStopped();
    }

    /** One listing of the phase ({@link #open} called), its footprint held while it lists. */
    void run(PipelineStep<IInAction> inStep) {
        try {
            Set<String> footprint = FootprintResolver.forListing(inStep.getAction(), inStep.getConfig());
            registry.acquireAll(footprint);
            try {
                list(inStep.getAction());
            } finally {
                registry.releaseAll(footprint);
            }
            if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
                stopped.set(true); // a plugin that ended its listing early on the stop: maybe incomplete
            }
        } catch (InterruptedException e) {
            stopped.set(true);
            Thread.currentThread().interrupt();
        } catch (PhaseStopped e) {
            // the phase is being stopped (see stop()): not a listing failure. Only this private marker is
            // ignored: any other exception, a plugin's own CancellationException included, fails the run.
            stopped.set(true);
        } catch (Throwable t) {
            // A failing listing (its footprint too) must not silently look like success: the whole run is marked
            // ERROR, the failure without the secret values of the input step. The first listing failure wins,
            // atomically.
            state.recordFailureIfAbsent(Redaction.hidden(inStep.getRedactor(), t));
            listingFailed.set(true);
        } finally {
            CountDownLatch listings = gate;
            listings.countDown();
            if (listings.getCount() == 0) {
                state.setListingComplete(!stopped.get() && !listingFailed.get());
                state.setListingInProgress(false);
                changed.run();
            }
        }
    }

    /**
     * One listing through the pipe: without grouping nor unfolding, no pipe: {@code listFiles(emit)} as before. One
     * pipe per listing (it is not thread-safe); what it still holds is emitted once the listing returns, nothing
     * more once the phase is stopped.
     */
    private void list(IInAction action) {
        List<GroupingRuleConfig> rules = groupingRules();
        ArchivesConfig unfold = RunArchives.unfoldConfig(config);
        if (rules.isEmpty() && unfold == null) {
            action.listFiles(emit);
            return;
        }
        ListingSink toEmit = new ListingSink() {
            @Override
            public void item(WorkItem item) {
                emit.accept(item);
            }

            @Override
            public void folderDone(String folder) {
                // emit holds nothing back
            }
        };
        Grouper grouper = rules.isEmpty() ? null : new Grouper(rules, toEmit);
        ListingSink grouped = grouper == null ? toEmit : grouper;
        ArchiveUnfolder unfolder = unfold == null ? null
                : new ArchiveUnfolder(unfold, archives::workspace, archives.sources(), grouped, failed, 1,
                archives.progress());
        action.listFiles(unfolder == null ? grouped : unfolder);
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
            return; // stopped: what the pipe holds is not emitted
        }
        if (unfolder != null) {
            unfolder.finish(); // first: its merged folders go through the grouping
        }
        if (grouper != null) {
            grouper.finish();
        }
    }

    /** The grouping rules of the pipeline, presets not expanded (the Grouper does it); empty: no grouping. */
    List<GroupingRuleConfig> groupingRules() {
        return groupingRules(config);
    }

    static List<GroupingRuleConfig> groupingRules(PipelineConfig config) {
        GroupingConfig grouping = config == null ? null : config.grouping();
        return GroupingPresets.expand(grouping).isEmpty() ? List.of() : grouping.rules();
    }

    /**
     * With grouping rules, the names of the inputs that signal no end of folder: their groups would be settled only
     * once they have listed everything, so a streaming run of the pipeline falls back to the automatic mode (spec
     * execution-mode §3). Empty without grouping rules.
     */
    static List<String> inputsWithoutFolderSignal(PipelineConfig config, List<PipelineStep<IInAction>> inSteps) {
        if (groupingRules(config).isEmpty()) {
            return List.of();
        }
        return inSteps.stream().filter(step -> !step.getAction().signalsFolders()).map(ListingPipe::stepName)
                .toList();
    }

    private static String stepName(PipelineStep<?> step) {
        PipelineStepConfig config = step.getConfig();
        return config == null || config.action() == null
                ? step.getAction().getClass().getSimpleName()
                : config.getDisplayName();
    }
}

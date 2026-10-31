package com.copybot.engine.sample;

import com.copybot.config.SecretReferences;
import com.copybot.engine.DryRunner;
import com.copybot.engine.Projection;
import com.copybot.engine.Redaction;
import com.copybot.engine.pipeline.PipelineConfig;
import com.copybot.engine.pipeline.PipelineStep;
import com.copybot.engine.pipeline.PipelineStepConfig;
import com.copybot.engine.plugin.Confirmations;
import com.copybot.plugin.api.action.IAnalyzeAction;
import com.copybot.plugin.api.action.IInAction;
import com.copybot.plugin.api.action.IProcessAction;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.embedded.CBEmbeddedPlugin;
import com.copybot.plugin.embedded.actions.FileReadAction;
import com.copybot.resources.ResourcesEngine;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * A sample of a pipeline being edited (spec pattern-helper §3): list once, analyse as often as the analyse or
 * process steps change. Not thread-safe except {@link #cancel()}; its operations block and are meant for one
 * background thread. Never throws: failures are in {@link Sample#failure()}. The questions its steps ask the user
 * ({@link com.copybot.plugin.api.UserConfirmation}) are answered no without asking, and a note says that a run asks
 * them: a preview trusts nothing (spec remote §2); a thread a plugin starts by itself is not covered.
 */
public final class SampleSession {

    /** Thrown from the listing consumer to stop it once the cap is reached. */
    private static final class StopSampling extends RuntimeException {
        StopSampling() {
            super(null, null, false, false);
        }
    }

    /** The embedded input action whose base metadata a chosen file gets. */
    private static final String FILE_READ = "file.read";

    private final SampleSteps steps;
    private final int maxListed;
    private final Duration listingTimeout;
    private final int maxKept;
    private volatile Thread worker;          // the thread running list/analyse, interrupted by cancel()
    private volatile boolean cancelled;
    private List<WorkItem> kept;             // pristine copies of the kept items, null before list()
    private int listed;
    private boolean truncated;

    public SampleSession(SampleSteps steps, int maxListed, Duration listingTimeout, int maxKept) {
        this.steps = steps;
        this.maxListed = maxListed;
        this.listingTimeout = listingTimeout;
        this.maxKept = maxKept;
    }

    /** Lists the items (capped, with a deadline), keeps a few of them, then analyses them. */
    public Sample list(PipelineConfig config) {
        return declining(() -> listDeclining(config));
    }

    private Sample listDeclining(PipelineConfig config) {
        begin();
        Thread lister = null;
        List<WorkItem> received = Collections.synchronizedList(new ArrayList<>());
        // set once the listing is over for this sample (timeout, cancel, failure): a lister that ignores its interrupt
        // and keeps emitting is refused from then on, nothing more is kept
        AtomicBoolean closed = new AtomicBoolean();
        try {
            AtomicBoolean full = new AtomicBoolean();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            List<PipelineStep<IInAction>> inSteps = steps.in(config); // resolution failure: caught below
            lister = Thread.ofPlatform().daemon().name("copybot-sample-listing").start(Confirmations.declining(() -> {
                SecretReferences.Redactor redactor = SecretReferences.Redactor.NONE; // of the step listing
                try {
                    for (PipelineStep<IInAction> step : inSteps) {
                        redactor = step.getRedactor();
                        step.getAction().listFiles(item -> {
                            synchronized (received) {
                                if (closed.get()) {
                                    throw new StopSampling(); // too late: the sample is taken
                                }
                                if (received.size() >= maxListed) {
                                    full.set(true);
                                    throw new StopSampling();
                                }
                                received.add(item);
                            }
                        });
                        if (full.get()) {
                            return;
                        }
                    }
                } catch (StopSampling e) {
                    // the cap is reached: normal end
                } catch (Throwable t) {
                    // a lister failing once the sample is taken (timeout, cancel) fails nothing
                    if (!full.get() && !closed.get() && !Thread.currentThread().isInterrupted()) {
                        failure.set(Redaction.hidden(redactor, t)); // without the secret values of the step
                    }
                }
            }));
            lister.join(listingTimeout.toMillis());
            close(received, closed);
            boolean timedOut = lister.isAlive();
            if (timedOut) {
                lister.interrupt(); // not waited for: a daemon, it ends when the plugin gives up
            }
            if (cancelled) {
                return Sample.failed(ResourcesEngine.getString("sample.cancelled"));
            }
            if (failure.get() != null) {
                return Sample.failed(message(failure.get()));
            }
            List<WorkItem> all;
            synchronized (received) {
                all = List.copyOf(received);
            }
            listed = all.size();
            truncated = timedOut || full.get();
            kept = select(all, maxKept).stream().map(WorkItem::copyForDryRun).toList();
            return analyseKept(config);
        } catch (InterruptedException e) {
            return Sample.failed(ResourcesEngine.getString("sample.cancelled"));
        } catch (Throwable e) {
            return Sample.failed(message(e));
        } finally {
            close(received, closed); // the cancel and failure paths
            if (lister != null && lister.isAlive()) {
                lister.interrupt(); // safety net: the cancel and failure paths
            }
            end();
        }
    }

    /**
     * Runs the operation answering no to the questions of its steps without asking (the listing thread included);
     * when one was declined, a note says that a run asks it (spec remote §2).
     */
    private static Sample declining(Supplier<Sample> operation) {
        AtomicBoolean declined = new AtomicBoolean();
        Sample sample = Confirmations.declineDuring(declined, operation::get);
        return declined.get() ? sample.withNote(ResourcesEngine.getString("sample.confirmation-declined")) : sample;
    }

    /** No item is kept any more: under the lock of received, so that no item is added once the sample is taken. */
    private static void close(List<WorkItem> received, AtomicBoolean closed) {
        synchronized (received) {
            closed.set(true);
        }
    }

    /** Analyses again the items kept by {@link #list}, with the current analyse and process steps. */
    public Sample analyse(PipelineConfig config) {
        return declining(() -> analyseDeclining(config));
    }

    private Sample analyseDeclining(PipelineConfig config) {
        begin();
        try {
            if (kept == null) {
                return Sample.failed(ResourcesEngine.getString("sample.not-listed"));
            }
            return analyseKept(config);
        } catch (InterruptedException e) {
            return Sample.failed(ResourcesEngine.getString("sample.cancelled"));
        } catch (Throwable e) {
            return Sample.failed(message(e));
        } finally {
            end();
        }
    }

    /**
     * Analyses and dry runs one file chosen by the user as if the pipeline had listed it: it gets the base
     * metadata of file.read (a note says so when the first input step is another action). Needs no
     * {@link #list}; cancellable like the other operations.
     */
    public Sample analyseFile(PipelineConfig config, Path file) {
        return declining(() -> analyseFileDeclining(config, file));
    }

    private Sample analyseFileDeclining(PipelineConfig config, Path file) {
        begin();
        try {
            List<String> notes = new ArrayList<>();
            if (!firstInIsFileRead(config)) {
                notes.add(ResourcesEngine.getString("sample.picked-base-metadata"));
            }
            WorkItem item = FileReadAction.workItemOf(file);
            List<SampleItem> items = new ArrayList<>();
            analyseItem(item, steps.analyse(config), steps.process(config), items, notes);
            if (cancelled) {
                return Sample.failed(ResourcesEngine.getString("sample.cancelled"));
            }
            return new Sample(items, 1, false, notes, Optional.empty());
        } catch (Throwable e) {
            return Sample.failed(cancelled ? ResourcesEngine.getString("sample.cancelled") : message(e));
        } finally {
            end();
        }
    }

    /** The first input step is the embedded file.read (a step without plugin names the embedded one). */
    private static boolean firstInIsFileRead(PipelineConfig config) {
        if (config.inSteps() == null || config.inSteps().isEmpty() || config.inSteps().getFirst() == null) {
            return false;
        }
        PipelineStepConfig first = config.inSteps().getFirst();
        boolean embedded = first.plugin() == null || first.plugin().isBlank()
                || CBEmbeddedPlugin.EMBEDDED_PLUGN_NAME.equals(first.plugin());
        return embedded && FILE_READ.equals(first.action());
    }

    /** Interrupts the current operation: it returns a "cancelled" sample. Any thread. */
    public synchronized void cancel() {
        cancelled = true;
        if (worker != null) {
            worker.interrupt();
        }
    }

    private Sample analyseKept(PipelineConfig config) throws InterruptedException {
        List<PipelineStep<IAnalyzeAction>> analyses = steps.analyse(config);
        List<PipelineStep<IProcessAction>> processes = steps.process(config);
        List<SampleItem> items = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (WorkItem pristine : kept) {
            if (cancelled || Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            analyseItem(pristine.copyForDryRun(), analyses, processes, items, notes);
        }
        return new Sample(items, listed, truncated, notes, Optional.empty());
    }

    /** Runs the analyses then the dry run of the process steps on the item, adding what it gives to items and notes. */
    private static void analyseItem(WorkItem item, List<PipelineStep<IAnalyzeAction>> analyses,
                                    List<PipelineStep<IProcessAction>> processes, List<SampleItem> items, List<String> notes) {
        String source = SampleItem.name(item);
        String error = null;
        WorkItem beforeFailure = item;
        for (PipelineStep<IAnalyzeAction> step : analyses) {
            WorkItem snapshot = item.copyForDryRun(); // spec §3.2: a failing analysis shows the item as it was before
            try {
                step.getAction().doAnalyze(item);
            } catch (Throwable e) {
                error = ResourcesEngine.getString("sample.analysis-failed", source, DryRunner.actionName(step),
                        step.getRedactor().text(message(e))); // without the secret values of the step
                beforeFailure = snapshot;
                break;
            }
        }
        if (error != null) {
            notes.add(error);
            items.add(SampleItem.of(source, beforeFailure, Optional.of(error)));
            return;
        }
        switch (DryRunner.project(item, processes)) {
            case Projection.Projected p -> p.items().forEach(produced -> items.add(SampleItem.of(source, produced, Optional.empty())));
            case Projection.Filtered f -> notes.add(ResourcesEngine.getString("sample.filtered", source, f.action()));
            case Projection.Unsupported u -> {
                notes.add(ResourcesEngine.getString("sample.unsupported", source, u.action()));
                u.before().forEach(produced -> items.add(SampleItem.of(source, produced, Optional.empty())));
            }
            case Projection.Failed f -> {
                String message = ResourcesEngine.getString("sample.dryrun-failed", source, f.action(), f.message());
                notes.add(message);
                items.add(SampleItem.of(source, item, Optional.of(message)));
            }
        }
    }

    /** At most max items, alternating the extensions in their order of first appearance (spec §3.2). */
    static List<WorkItem> select(List<WorkItem> items, int max) {
        LinkedHashMap<String, Deque<WorkItem>> byExtension = new LinkedHashMap<>();
        for (WorkItem item : items) {
            byExtension.computeIfAbsent(extension(SampleItem.name(item)), e -> new ArrayDeque<>()).add(item);
        }
        List<WorkItem> selected = new ArrayList<>();
        while (selected.size() < max && !byExtension.isEmpty()) {
            Iterator<Deque<WorkItem>> it = byExtension.values().iterator();
            while (it.hasNext() && selected.size() < max) {
                Deque<WorkItem> queue = it.next();
                selected.add(queue.poll());
                if (queue.isEmpty()) {
                    it.remove();
                }
            }
        }
        return selected;
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String message(Throwable t) {
        return t.getMessage() != null ? t.getMessage() : t.getClass().getName();
    }

    private synchronized void begin() {
        cancelled = false;
        worker = Thread.currentThread();
    }

    private void end() {
        synchronized (this) {
            worker = null; // from here cancel() no longer interrupts this thread
        }
        Thread.interrupted(); // clears an interrupt from a cancel() that came before the end
    }
}

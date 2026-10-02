package com.copybot.engine.sample;

import com.copybot.engine.DryRunner;
import com.copybot.engine.Projection;
import com.copybot.engine.pipeline.PipelineConfig;
import com.copybot.engine.pipeline.PipelineStep;
import com.copybot.plugin.api.action.IAnalyzeAction;
import com.copybot.plugin.api.action.IInAction;
import com.copybot.plugin.api.action.IProcessAction;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.resources.ResourcesEngine;

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

/**
 * A sample of a pipeline being edited (spec pattern-helper §3): list once, analyse as often as the analyse or
 * process steps change. Not thread-safe except {@link #cancel()}; its operations block and are meant for one
 * background thread. Never throws: failures are in {@link Sample#failure()}.
 */
public final class SampleSession {

    /** Thrown from the listing consumer to stop it once the cap is reached. */
    private static final class StopSampling extends RuntimeException {
        StopSampling() {
            super(null, null, false, false);
        }
    }

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
        begin();
        Thread lister = null;
        try {
            List<WorkItem> received = Collections.synchronizedList(new ArrayList<>());
            AtomicBoolean full = new AtomicBoolean();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            List<PipelineStep<IInAction>> inSteps = steps.in(config); // resolution failure: caught below
            lister = Thread.ofPlatform().daemon().name("copybot-sample-listing").start(() -> {
                try {
                    for (PipelineStep<IInAction> step : inSteps) {
                        step.getAction().listFiles(item -> {
                            synchronized (received) {
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
                    if (!full.get() && !Thread.currentThread().isInterrupted()) {
                        failure.set(t);
                    }
                }
            });
            lister.join(listingTimeout.toMillis());
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
            if (lister != null && lister.isAlive()) {
                lister.interrupt(); // safety net: the cancel and failure paths
            }
            end();
        }
    }

    /** Analyses again the items kept by {@link #list}, with the current analyse and process steps. */
    public Sample analyse(PipelineConfig config) {
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
            WorkItem item = pristine.copyForDryRun();
            String source = SampleItem.name(item);
            String error = null;
            WorkItem beforeFailure = item;
            for (PipelineStep<IAnalyzeAction> step : analyses) {
                WorkItem snapshot = item.copyForDryRun(); // spec §3.2: a failing analysis shows the item as it was before
                try {
                    step.getAction().doAnalyze(item);
                } catch (Throwable e) {
                    error = ResourcesEngine.getString("sample.analysis-failed", source, DryRunner.actionName(step), message(e));
                    beforeFailure = snapshot;
                    break;
                }
            }
            if (error != null) {
                notes.add(error);
                items.add(SampleItem.of(source, beforeFailure, Optional.of(error)));
                continue;
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
        return new Sample(items, listed, truncated, notes, Optional.empty());
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

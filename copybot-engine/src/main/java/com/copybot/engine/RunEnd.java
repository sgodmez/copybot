package com.copybot.engine;

import com.copybot.config.SecretReferences;
import com.copybot.engine.pipeline.PipelineStep;
import com.copybot.engine.pipeline.RunEndStepConfig;
import com.copybot.engine.plugin.PluginEngine;
import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.IRunEndAction;
import com.copybot.plugin.api.action.RunEndContext;
import com.copybot.plugin.api.action.RunOutcome;
import com.copybot.plugin.api.action.RunReport;
import com.copybot.plugin.api.definition.IPlugin;
import com.copybot.resources.CombinedResourceBundle;
import com.copybot.resources.ResourcesEngine;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs the end-of-run actions of a copy run (spec run-end, "Déroulé"): the ones whose conditions hold the outcome, in
 * order, one at a time, each on its own virtual thread within its time limit. A failure or a timeout is a warning of
 * the run and the next action runs; {@link #cancel()} interrupts the action under way and skips the next ones.
 */
final class RunEnd {

    private static final System.Logger LOGGER = System.getLogger(RunEnd.class.getName());

    /**
     * One end-of-run action of the pipeline.
     *
     * @param name     its localised name, shown while it runs and in its warnings
     * @param when     the outcomes it runs for
     * @param timeout  the time it may take; {@link Duration#ZERO}: no limit
     * @param redactor hides the secret values of its configuration in its failures and warnings
     */
    record Entry(String name, IRunEndAction action, Set<RunOutcome> when, Duration timeout,
                 SecretReferences.Redactor redactor) {

        /** An action configured without secret. */
        Entry(String name, IRunEndAction action, Set<RunOutcome> when, Duration timeout) {
            this(name, action, when, timeout, SecretReferences.Redactor.NONE);
        }
    }

    /** Where the actions tell the run about their work. */
    interface Run {
        /** The warnings of the run so far, given to each action in its report. */
        List<String> warnings();

        void warn(String warning);

        /** The action running now (its name), null once they are over. */
        void running(String name);
    }

    private final List<Entry> entries;
    /** Guarded by this. */
    private boolean cancelled;
    /** The action under way, null between two of them. Guarded by this. */
    private FutureTask<Void> current;

    RunEnd(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    List<Entry> entries() {
        return entries;
    }

    /** Counted by {@link #waitBound} for an action without time limit. */
    static final Duration UNLIMITED_WAIT = Duration.ofSeconds(120);
    /** Added by {@link #waitBound} to the time limits: the run publishing its status, the threads ending. */
    static final Duration WAIT_MARGIN = Duration.ofSeconds(5);

    /**
     * How long a wait for actions with these time limits may last: their sum, an action without limit (0) counted as
     * {@link #UNLIMITED_WAIT}, plus {@link #WAIT_MARGIN}; zero without action (nothing to wait for).
     */
    static Duration waitBound(List<Duration> timeouts) {
        if (timeouts.isEmpty()) {
            return Duration.ZERO;
        }
        Duration total = WAIT_MARGIN;
        for (Duration timeout : timeouts) {
            total = total.plus(timeout.isZero() || timeout.isNegative() ? UNLIMITED_WAIT : timeout);
        }
        return total;
    }

    /**
     * The time limits of the actions of this outcome still to run: from the one named running (included) when it is
     * one of them, all of them otherwise.
     */
    List<Duration> remainingTimeouts(RunOutcome outcome, String running) {
        List<Entry> of = entries.stream().filter(entry -> entry.when().contains(outcome)).toList();
        int from = 0;
        for (int i = 0; running != null && i < of.size(); i++) {
            if (of.get(i).name().equals(running)) {
                from = i;
                break;
            }
        }
        return of.subList(from, of.size()).stream().map(Entry::timeout).toList();
    }

    /**
     * Resolves the run-end entries of a pipeline, in order: all of them, or none.
     *
     * @throws CopybotException an action that cannot be found (plugin.not-found, action.not-found) or configured
     *                          (run-end.config.invalid)
     */
    static List<Entry> resolve(List<RunEndStepConfig> configs) {
        List<Entry> entries = new ArrayList<>();
        int index = 0;
        for (RunEndStepConfig config : configs) {
            index++;
            PipelineStep<IRunEndAction> step;
            try {
                step = PluginEngine.resolve(config.asStep(), IRunEndAction.class);
            } catch (CopybotException e) {
                throw e; // already told: plugin or action not found, or refused by the action itself
            } catch (RuntimeException e) {
                throw CopybotException.ofResource(e, "run-end.config.invalid", index, config.action(),
                        e.getMessage() != null ? e.getMessage() : e.getClass().getName());
            }
            IRunEndAction action = step.getAction();
            Integer seconds = config.timeoutSeconds();
            Duration timeout = seconds == null ? action.defaultTimeout() : Duration.ofSeconds(seconds);
            entries.add(new Entry(name(step.getPlugin(), config.action()), action, config.outcomes(), timeout,
                    step.getRedactor()));
        }
        return List.copyOf(entries);
    }

    /** The localised name of the action ({@code plugin.<code>.<action>.name}), its code when it has none. */
    private static String name(IPlugin plugin, String action) {
        CombinedResourceBundle bundle = plugin == null ? null : plugin.getResourceBundle();
        if (bundle == null) {
            return action;
        }
        String key = "plugin." + plugin.getPluginCode() + "." + action + ".name";
        String value = bundle.getString(key);
        return value.equals("%" + key) ? action : value; // "%key": no such key
    }

    /** Stops the action under way and skips the next ones; before {@link #run}, it runs none. Any thread. */
    void cancel() {
        synchronized (this) {
            cancelled = true;
            if (current != null) {
                current.cancel(true);
            }
        }
    }

    /**
     * Runs the actions of this outcome, on the calling thread (each one on a thread of its own). Never throws.
     *
     * @param report the report of the run, without outputs
     */
    void run(RunOutcome outcome, RunReport report, Run run) {
        Map<String, String> outputs = new LinkedHashMap<>();
        try {
            for (Entry entry : entries) {
                if (entry.when().contains(outcome) && !runOne(entry, report, outputs, run)) {
                    break;
                }
            }
        } finally {
            run.running(null);
        }
    }

    /** @return false when the actions are stopped: the next ones are skipped */
    private boolean runOne(Entry entry, RunReport report, Map<String, String> outputs, Run run) {
        Context context = new Context(entry.name(), outputs, run, entry.redactor());
        RunReport its = RunReports.with(report, run.warnings(), Map.copyOf(outputs));
        FutureTask<Void> task = new FutureTask<>(() -> {
            entry.action().runEnded(its, context);
            return null;
        });
        synchronized (this) {
            if (cancelled) {
                run.warn(ResourcesEngine.getString("run-end.cancelled", entry.name()));
                return false;
            }
            current = task;
        }
        run.running(entry.name());
        try {
            Thread.ofVirtual().name("copybot-run-end").start(task);
            if (entry.timeout().isZero() || entry.timeout().isNegative()) {
                task.get();
            } else {
                task.get(entry.timeout().toNanos(), TimeUnit.NANOSECONDS);
            }
            return true;
        } catch (TimeoutException e) {
            task.cancel(true);
            run.warn(ResourcesEngine.getString("run-end.timeout", entry.name(), seconds(entry.timeout())));
            return true;
        } catch (ExecutionException e) {
            Throwable cause = entry.redactor().redact(e.getCause() == null ? e : e.getCause());
            // a warning of the run: the trace for the developer only
            LOGGER.log(System.Logger.Level.DEBUG, "the end-of-run action " + entry.name() + " failed", cause);
            run.warn(ResourcesEngine.getString("run-end.failed", entry.name(),
                    cause.getMessage() != null ? cause.getMessage() : cause.getClass().getName()));
            return true;
        } catch (CancellationException e) {
            run.warn(ResourcesEngine.getString("run-end.cancelled", entry.name())); // cancel()
            return false;
        } catch (InterruptedException e) {
            // the thread running the run is stopped (e.g. the engine is closed): the actions too
            task.cancel(true);
            synchronized (this) {
                cancelled = true;
            }
            run.warn(ResourcesEngine.getString("run-end.cancelled", entry.name()));
            Thread.currentThread().interrupt();
            return false;
        } catch (RuntimeException | Error e) {
            // the thread could not be started
            task.cancel(true);
            run.warn(ResourcesEngine.getString("run-end.failed", entry.name(),
                    String.valueOf(entry.redactor().redact(e))));
            return true;
        } finally {
            context.close();
            synchronized (this) {
                current = null;
            }
        }
    }

    /** A time limit in seconds, as short as it can be written ("60", "0.2"). */
    private static String seconds(Duration timeout) {
        return BigDecimal.valueOf(timeout.toMillis()).movePointLeft(3).stripTrailingZeros().toPlainString();
    }

    /** What one action publishes and warns; nothing more once it is over (a late call of a stopped action). */
    private static final class Context implements RunEndContext {
        private final String name;
        private final Map<String, String> outputs;
        private final Run run;
        private final SecretReferences.Redactor redactor;
        private boolean closed;

        Context(String name, Map<String, String> outputs, Run run, SecretReferences.Redactor redactor) {
            this.name = name;
            this.outputs = outputs;
            this.run = run;
            this.redactor = redactor;
        }

        @Override
        public synchronized void publish(String key, String value) {
            if (key == null || value == null) {
                throw new IllegalArgumentException("publish(" + key + ", " + value + "): null key or value");
            }
            if (!closed) {
                outputs.put(key, value); // the last value wins
            }
        }

        @Override
        public synchronized void warn(String warning) {
            if (warning == null) {
                throw new IllegalArgumentException("warn(null)");
            }
            String message = redactor.text(warning); // a warning never shows a secret
            if (!closed) {
                run.warn(message);
            } else {
                LOGGER.log(System.Logger.Level.DEBUG, "late warning of " + name + " ignored: " + message);
            }
        }

        synchronized void close() {
            closed = true;
        }
    }
}

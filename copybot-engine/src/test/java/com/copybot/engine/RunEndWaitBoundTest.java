package com.copybot.engine;

import com.copybot.engine.ControlFakes.FakeAction;
import com.copybot.engine.pipeline.PipelineConfig;
import com.copybot.engine.pipeline.RunEndStepConfig;
import com.copybot.plugin.api.action.IRunEndAction;
import com.copybot.plugin.api.action.RunEndContext;
import com.copybot.plugin.api.action.RunOutcome;
import com.copybot.plugin.api.action.RunReport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** How long the Ctrl+C hook of the CLI waits for the end-of-run actions of a cancelled run. */
public class RunEndWaitBoundTest {

    static final class Act extends FakeAction implements IRunEndAction {
        @Override
        public void runEnded(RunReport report, RunEndContext context) {
        }
    }

    private static RunEnd.Entry entry(String name, EnumSet<RunOutcome> when, Duration timeout) {
        return new RunEnd.Entry(name, new Act(), when, timeout);
    }

    @Test
    public void theFiniteLimitsAddUpWithAMargin() {
        assertEquals(Duration.ofSeconds(30 + 60).plus(RunEnd.WAIT_MARGIN),
                RunEnd.waitBound(List.of(Duration.ofSeconds(30), Duration.ofSeconds(60))));
    }

    @Test
    public void anActionWithoutLimitCountsAsTwoMinutes() {
        assertEquals(Duration.ofSeconds(120 + 10).plus(RunEnd.WAIT_MARGIN),
                RunEnd.waitBound(List.of(Duration.ZERO, Duration.ofSeconds(10))));
    }

    @Test
    public void withoutActionThereIsNothingToWaitFor() {
        assertEquals(Duration.ZERO, RunEnd.waitBound(List.of()));
    }

    @Test
    public void onlyTheRemainingActionsOfTheOutcomeCount() {
        RunEnd actions = new RunEnd(List.of(
                entry("a", EnumSet.of(RunOutcome.CANCELLED), Duration.ofSeconds(1)),
                entry("b", EnumSet.of(RunOutcome.SUCCESS), Duration.ofSeconds(2)),
                entry("c", EnumSet.allOf(RunOutcome.class), Duration.ofSeconds(4)),
                entry("d", EnumSet.of(RunOutcome.CANCELLED), Duration.ZERO)));

        assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(4), Duration.ZERO),
                actions.remainingTimeouts(RunOutcome.CANCELLED, null), "none running: all of them");
        assertEquals(List.of(Duration.ofSeconds(4), Duration.ZERO),
                actions.remainingTimeouts(RunOutcome.CANCELLED, "c"), "from the one running");
    }

    @Test
    public void theExecutorCountsTheCancelledActionsOfThePipelineNotYetResolved() {
        PipelineConfig config = new PipelineConfig(List.of(), List.of(), List.of(), null, null, null, null, List.of(
                new RunEndStepConfig(null, "a", null, List.of("cancelled"), 7, null),
                new RunEndStepConfig(null, "b", null, null, 50, null), // success and error
                new RunEndStepConfig(null, "c", null, List.of("error", "cancelled"), null, null)));
        MainExecutor exec = new MainExecutor(config, null, ControlFakes.registry(java.util.Map.of()), null);

        assertEquals(Duration.ofSeconds(7 + 120).plus(RunEnd.WAIT_MARGIN), exec.cancelledRunEndWait(),
                "unknown default: counted as no limit");
    }

    @Test
    public void theExecutorCountsTheResolvedActions() {
        MainExecutor exec = new MainExecutor(new PipelineConfig(List.of(), List.of(), List.of(), null, null, null, null,
                List.of()), null, ControlFakes.registry(java.util.Map.of()), null);
        assertEquals(Duration.ZERO, exec.cancelledRunEndWait());

        exec.setRunEnd(List.of(entry("a", EnumSet.of(RunOutcome.CANCELLED), Duration.ofSeconds(3))));

        assertEquals(Duration.ofSeconds(3).plus(RunEnd.WAIT_MARGIN), exec.cancelledRunEndWait());
    }
}

package com.copybot.engine;

import com.copybot.engine.ControlFakes.DatedIn;
import com.copybot.engine.ControlFakes.FakeAction;
import com.copybot.engine.ControlFakes.GatedOut;
import com.copybot.engine.pipeline.PipelineStatus;
import com.copybot.plugin.api.UserConfirmation;
import com.copybot.plugin.api.action.IOutAction;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WriteContext;
import com.copybot.plugin.api.action.WriteResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.copybot.engine.ControlFakes.registry;
import static com.copybot.engine.ControlFakes.singlePhase;
import static org.junit.jupiter.api.Assertions.*;

/** A question of a plugin left unanswered does not hold a cancelled run (spec remote §2). */
@Timeout(60)
public class UserConfirmationRunTest {

    @TempDir
    Path tempDir;

    @AfterEach
    public void noHandler() {
        CopybotEngine.setConfirmationHandler(null);
    }

    /** Asks before its first write, keeps the answer. */
    static final class AskingOut extends FakeAction implements IOutAction {
        final AtomicReference<Boolean> answer = new AtomicReference<>();

        @Override
        public WriteResult write(WorkItem item, WriteContext context) {
            if (answer.get() == null) {
                answer.set(UserConfirmation.ask("Trust host?"));
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("interrupted");
            }
            return WriteResult.written(null);
        }
    }

    @Test
    public void cancellingTheRunWithdrawsTheQuestionAndTheAnswerIsNo() throws Exception {
        AtomicReference<CompletableFuture<Boolean>> pending = new AtomicReference<>();
        CountDownLatch asked = new CountDownLatch(1);
        CopybotEngine.setConfirmationHandler(question -> {
            CompletableFuture<Boolean> answer = new CompletableFuture<>(); // the user never answers
            pending.set(answer);
            asked.countDown();
            return answer;
        });
        AskingOut out = new AskingOut();
        MainExecutor exec = singlePhase(new DatedIn(tempDir, 1, null), out, registry(Map.of("disk:*", 1000)));
        Thread runner = Thread.ofVirtual().start(exec);
        assertTrue(asked.await(5, TimeUnit.SECONDS), "the plugin asked");

        exec.cancel();
        runner.join(TimeUnit.SECONDS.toMillis(20));

        assertFalse(runner.isAlive(), "the run is not held by the question");
        assertEquals(PipelineStatus.CANCELLED, exec.getState().getStatus());
        assertEquals(Boolean.FALSE, out.answer.get());
        assertTrue(pending.get().isCancelled(), "the question is withdrawn (its dialog closed)");
    }

    @Test
    public void cancellingWhileTheListingAsksWithdrawsTheQuestionAndTheAnswerIsNo() throws Exception {
        AtomicReference<CompletableFuture<Boolean>> pending = new AtomicReference<>();
        CountDownLatch asked = new CountDownLatch(1);
        CopybotEngine.setConfirmationHandler(question -> {
            CompletableFuture<Boolean> answer = new CompletableFuture<>(); // the user never answers
            pending.set(answer);
            asked.countDown();
            return answer;
        });
        AtomicReference<Boolean> answer = new AtomicReference<>();
        // the question comes from the listing thread, after its first item
        DatedIn in = new DatedIn(tempDir, 2, () -> answer.set(UserConfirmation.ask("Trust host?")));
        MainExecutor exec = singlePhase(in, new GatedOut(Set.of(), null), registry(Map.of("disk:*", 1000)));
        Thread runner = Thread.ofVirtual().start(exec);
        assertTrue(asked.await(5, TimeUnit.SECONDS), "the listing asked");

        exec.cancel();
        runner.join(TimeUnit.SECONDS.toMillis(20));

        assertFalse(runner.isAlive(), "the run is not held by the question");
        assertEquals(PipelineStatus.CANCELLED, exec.getState().getStatus());
        assertEquals(Boolean.FALSE, answer.get());
        assertTrue(pending.get().isCancelled(), "the question is withdrawn (its dialog closed)");
    }
}

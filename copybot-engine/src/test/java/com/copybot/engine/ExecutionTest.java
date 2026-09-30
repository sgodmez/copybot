package com.copybot.engine;

import com.copybot.engine.pipeline.PipelineStatus;
import com.copybot.engine.resources.ResourceRegistry;
import com.copybot.engine.resume.ResumeStateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.copybot.engine.ControlFakes.*;
import static org.junit.jupiter.api.Assertions.*;

/** The Execution returned by {@link CopybotEngine#execute} / {@link CopybotEngine#run} (spec engine-instance §3). */
public class ExecutionTest {

    @TempDir
    Path tempDir;

    private CopybotEngine engine;

    @BeforeEach
    public void createEngine() {
        engine = new CopybotEngine(config());
    }

    @AfterEach
    public void closeEngine() {
        engine.close();
    }

    private ResumeStateStore store() {
        return new ResumeStateStore(tempDir.resolve("p.state.json"));
    }

    @Test
    public void awaitReturnsTheFinalStatusOnceDone() throws Exception {
        MainExecutor pipeline = singlePhase(new DatedIn(tempDir, 2, null), new GatedOut(Set.of(), null),
                registry(Map.of("disk:*", 1000)));

        Execution execution = engine.submit(pipeline, pipeline::run);

        assertEquals(PipelineStatus.SUCCESS, awaitStatus(execution));
        assertTrue(execution.isDone());
        assertEquals(2, execution.getState().getWorkItems().size());
    }

    @Test
    public void cancelDuringThePreparationOfARunEndsCancelled() throws Exception {
        BlockingIn listing = new BlockingIn();
        ResourceRegistry reg = registry(Map.of("disk:*", 1000));
        MainExecutor pipeline = withResume(listing, new GatedOut(Set.of(), null), reg, store());
        Execution execution = engine.submit(pipeline, pipeline::run);
        assertTrue(listing.started.await(5, TimeUnit.SECONDS));

        execution.cancel();

        assertEquals(PipelineStatus.CANCELLED, awaitStatus(execution));
        assertTrue(allReleased(reg), "got " + reg.snapshot());
        assertFalse(Files.exists(store().getPath()), "a cancelled run writes no state");
    }

    @Test
    public void cancelDuringTheExecutionOfAPlanKeepsTheCursor() throws Exception {
        store().writeCursor(day(1));
        byte[] before = Files.readAllBytes(store().getPath());
        GatedOut out = new GatedOut(Set.of(), new CountDownLatch(1));
        ResourceRegistry reg = registry(Map.of("disk:*", 1000));
        Plan plan = engine.prepare(withResume(new DatedIn(tempDir, 3, null), out, reg, store()));
        assertEquals(PipelineStatus.PREPARED, plan.getState().getStatus());

        Execution execution = engine.execute(plan, null);
        assertTrue(out.firstEntered.await(5, TimeUnit.SECONDS));
        execution.cancel();

        assertEquals(PipelineStatus.CANCELLED, awaitStatus(execution));
        assertTrue(allReleased(reg), "got " + reg.snapshot());
        assertArrayEquals(before, Files.readAllBytes(store().getPath()), "the cursor is unchanged");
    }

    @Test
    public void pauseAndResumeThroughTheExecution() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        GatedOut out = new GatedOut(Set.of("proc"), gate);
        ResourceRegistry reg = registry(Map.of("proc", 1, "disk:*", 1000));
        MainExecutor pipeline = singlePhase(new DatedIn(tempDir, 3, null), out, reg);
        Execution execution = engine.submit(pipeline, pipeline::run);
        assertTrue(out.firstEntered.await(5, TimeUnit.SECONDS));

        execution.pause();
        assertEquals(PipelineStatus.PAUSED, execution.getState().getStatus());
        gate.countDown();
        awaitTrue(() -> used(reg, "proc") == 0);
        Thread.sleep(200);
        assertEquals(1, out.started.get(), "no new step starts while paused");
        assertFalse(execution.isDone());

        execution.resume();
        assertEquals(PipelineStatus.SUCCESS, awaitStatus(execution));
        assertEquals(3, out.started.get());
    }

    @Test
    public void cancelWhilePausedEndsCancelled() throws Exception {
        GatedOut out = new GatedOut(Set.of(), new CountDownLatch(1));
        ResourceRegistry reg = registry(Map.of("disk:*", 1000));
        MainExecutor pipeline = singlePhase(new DatedIn(tempDir, 2, null), out, reg);
        Execution execution = engine.submit(pipeline, pipeline::run);
        assertTrue(out.firstEntered.await(5, TimeUnit.SECONDS));
        execution.pause();

        execution.cancel();

        assertEquals(PipelineStatus.CANCELLED, awaitStatus(execution));
        assertFalse(reg.isPaused(), "cancel lifts the pause");
    }

    @Test
    public void cancelIsIdempotentAndHasNoEffectOnceDone() throws Exception {
        MainExecutor pipeline = singlePhase(new DatedIn(tempDir, 1, null), new GatedOut(Set.of(), null),
                registry(Map.of("disk:*", 1000)));
        Execution execution = engine.submit(pipeline, pipeline::run);
        assertEquals(PipelineStatus.SUCCESS, awaitStatus(execution));

        execution.cancel();
        execution.cancel();
        execution.pause();
        execution.resume();

        assertEquals(PipelineStatus.SUCCESS, execution.getState().getStatus());
        assertTrue(execution.await(1, TimeUnit.SECONDS));
    }
}

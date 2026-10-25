package com.copybot.engine;

import com.copybot.engine.ControlFakes.DatedIn;
import com.copybot.engine.ControlFakes.FakeAction;
import com.copybot.engine.pipeline.PipelineStatus;
import com.copybot.engine.pipeline.PostProcessConfig;
import com.copybot.engine.resources.ResourceRegistry;
import com.copybot.engine.resume.ResumeStateStore;
import com.copybot.plugin.api.action.IOutAction;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WriteContext;
import com.copybot.plugin.api.action.WriteResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The post-processing at the end of a copy phase (spec post-process). */
public class MainExecutorPostProcessTest {

    @TempDir
    Path tempDir;

    /** Every item "creates" the directory named after its day, under the same year; failOn fails that item. */
    static final class DirOut extends FakeAction implements IOutAction {
        final Path year;
        final String failOn;

        DirOut(Path year, String failOn) {
            this.year = year;
            this.failOn = failOn;
        }

        @Override
        public WriteResult write(WorkItem item, WriteContext context) {
            context.directoryCreated(year);
            context.directoryCreated(year.resolve(item.getNameDisplay().substring(4, 6)));
            if (item.getNameDisplay().equals(failOn)) {
                throw new IllegalStateException("disk full");
            }
            return WriteResult.written(null);
        }
    }

    private Path source() throws IOException {
        return Files.createDirectories(tempDir.resolve("card"));
    }

    private static ResourceRegistry registry() {
        return ControlFakes.registry(Map.of("disk:*", 1000));
    }

    private PostProcessTest.RecordingStarter postProcess(MainExecutor exec) {
        PostProcessTest.RecordingStarter starter = new PostProcessTest.RecordingStarter();
        exec.setPostProcess(new PostProcess(new PostProcessConfig("synoRight", List.of("{createdDirs}")), starter));
        return starter;
    }

    @Test
    public void aSinglePhaseRunLaunchesTheProgramWithTheCreatedDirectories() throws IOException {
        Path year = tempDir.resolve("nas").resolve("2026");
        MainExecutor exec = ControlFakes.singlePhase(new DatedIn(source(), 2, null), new DirOut(year, null), registry());
        PostProcessTest.RecordingStarter starter = postProcess(exec);

        exec.run();

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
        assertEquals(List.of(List.of("synoRight", year.toString(), year.resolve("01").toString(),
                year.resolve("02").toString())), starter.commandLines);
    }

    @Test
    public void thePreparationLaunchesNothingTheExecutionDoes() throws IOException {
        Path year = tempDir.resolve("nas").resolve("2026");
        MainExecutor exec = ControlFakes.withResume(new DatedIn(source(), 1, null), new DirOut(year, null), registry(),
                new ResumeStateStore(tempDir.resolve("p.state.json")));
        PostProcessTest.RecordingStarter starter = postProcess(exec);

        exec.prepare();
        assertEquals(List.of(), starter.commandLines, "a plan copies nothing");
        exec.execute(null);

        assertEquals(1, starter.commandLines.size());
    }

    @Test
    public void aFailedItemDoesNotPreventIt() throws IOException {
        Path year = tempDir.resolve("nas").resolve("2026");
        MainExecutor exec = ControlFakes.singlePhase(new DatedIn(source(), 2, null), new DirOut(year, "IMG_02.JPG"),
                registry());
        PostProcessTest.RecordingStarter starter = postProcess(exec);

        exec.run();

        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus());
        assertEquals(1, starter.commandLines.size(), "the directories created before the error still need it");
    }

    @Test
    public void aLaunchFailureIsAWarningOfASuccessfulCopy() throws IOException {
        MainExecutor exec = ControlFakes.singlePhase(new DatedIn(source(), 1, null),
                new DirOut(tempDir.resolve("nas"), null), registry());
        exec.setPostProcess(new PostProcess(new PostProcessConfig("missing.exe", List.of()), (commandLine, dir) -> {
            throw new IOException("Cannot run program");
        }));

        exec.run();

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
        assertEquals(1, exec.getState().getWarnings().size());
        assertTrue(exec.getState().getWarnings().getFirst().contains("missing.exe"));
    }
}

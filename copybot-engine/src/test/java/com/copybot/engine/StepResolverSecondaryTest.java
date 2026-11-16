package com.copybot.engine;

import com.copybot.engine.pipeline.PipelineConfig;
import com.copybot.engine.pipeline.SecondaryOutputConfig;
import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.IOutAction;
import com.copybot.plugin.api.action.IProcessAction;
import com.copybot.utils.GsonUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** The resolution of the secondary outputs: expected step types, errors naming the branch (spec secondary-outputs §7). */
public class StepResolverSecondaryTest {

    @BeforeAll
    static void loadPlugins() {
        CopybotEngine.create(Optional.of(Path.of("./src/test/resources/com/copybot/engine/config.json"))).close();
    }

    private static PipelineConfig config(String branches) {
        return GsonUtil.getGson().fromJson("{\"secondaryOutputs\":" + branches + "}", PipelineConfig.class);
    }

    private static final String BUFFER = "{\"action\":\"file.buffer\",\"actionConfig\":{\"folder\":\"x\"}}";
    private static final String WRITE = "{\"action\":\"file.write\",\"actionConfig\":{\"outPattern\":\"/tmp/{name}\"}}";

    @Test
    public void aPipelineWithoutBranchesResolvesNone() {
        assertEquals(List.of(), StepResolver.secondary(config("[]")));
        assertEquals(List.of(), StepResolver.secondary(new PipelineConfig(null, null, null, null, null, null)));
    }

    @Test
    public void theStepsAndTheOutputOfEachBranchAreResolvedInOrderWithTheirTypes() {
        List<StepResolver.Branch> branches = StepResolver.secondary(config(
                "[{\"name\":\"Thumbs\",\"onError\":\"warn\",\"actionSteps\":[" + BUFFER + "],\"outStep\":" + WRITE + "},"
                        + "{\"name\":\"Copy\",\"outStep\":" + WRITE + "}]"));

        assertEquals(List.of("Thumbs", "Copy"), branches.stream().map(StepResolver.Branch::name).toList());
        assertEquals(SecondaryOutputConfig.OnError.WARN, branches.get(0).onError());
        assertEquals(SecondaryOutputConfig.OnError.FAIL, branches.get(1).onError());
        assertEquals(1, branches.get(0).steps().size());
        assertInstanceOf(IProcessAction.class, branches.get(0).steps().getFirst().getAction());
        assertInstanceOf(IOutAction.class, branches.get(0).out().getAction());
        assertEquals(List.of(), branches.get(1).steps());
    }

    @Test
    public void anUnknownActionIsToldWithTheNameOfItsBranch() {
        CopybotException e = assertThrows(CopybotException.class, () -> StepResolver.secondary(config(
                "[{\"name\":\"Fine\",\"outStep\":" + WRITE + "},"
                        + "{\"name\":\"Broken\",\"actionSteps\":[{\"action\":\"no.such.action\"}],\"outStep\":" + WRITE + "}]")));

        assertTrue(e.getMessage().contains("Broken"), e.getMessage());
        assertFalse(e.getMessage().contains("Fine"), e.getMessage());
    }

    @Test
    public void anOutActionAsAProcessStepAndAProcessActionAsTheOutputAreRefused() {
        CopybotException asProcess = assertThrows(CopybotException.class, () -> StepResolver.secondary(config(
                "[{\"name\":\"B1\",\"actionSteps\":[" + WRITE + "],\"outStep\":" + WRITE + "}]")));
        assertTrue(asProcess.getMessage().contains("B1"), asProcess.getMessage());

        CopybotException asOut = assertThrows(CopybotException.class, () -> StepResolver.secondary(config(
                "[{\"name\":\"B2\",\"outStep\":" + BUFFER + "}]")));
        assertTrue(asOut.getMessage().contains("B2"), asOut.getMessage());
    }
}

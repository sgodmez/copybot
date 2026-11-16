package com.copybot.engine.pipeline;

import com.copybot.plugin.api.action.RunOutcome;
import com.copybot.utils.GsonUtil;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The pipeline's runEnd list and the conversion of the former postProcess block. */
public class RunEndConfigTest {

    private static JsonObject tree(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static PipelineConfig parse(String json) {
        return GsonUtil.getGson().fromJson(json, PipelineConfig.class);
    }

    @Test
    public void thePostProcessBlockBecomesTheFirstRunEndEntry() {
        JsonObject pipeline = tree("{\"postProcess\":{\"command\":\"D:\\\\progs\\\\synoRight.exe\","
                + "\"args\":[\"rights\",\"{createdDirs}\"]},"
                + "\"runEnd\":[{\"plugin\":\"notify-email\",\"action\":\"email.send\"}]}");

        assertTrue(RunEndMigration.migrate(pipeline));

        assertEquals(tree("{\"runEnd\":["
                + "{\"action\":\"post.process\",\"when\":[\"success\",\"error\"],"
                + "\"actionConfig\":{\"command\":\"D:\\\\progs\\\\synoRight.exe\",\"args\":[\"rights\",\"{createdDirs}\"]}},"
                + "{\"plugin\":\"notify-email\",\"action\":\"email.send\"}]}"), pipeline);
    }

    @Test
    public void anExistingPostProcessEntryWins() {
        String entry = "{\"plugin\":\"embedded\",\"action\":\"post.process\",\"when\":[\"success\"],"
                + "\"actionConfig\":{\"command\":\"new\"}}";
        JsonObject pipeline = tree("{\"postProcess\":{\"command\":\"old\"},\"runEnd\":[" + entry + "]}");

        assertTrue(RunEndMigration.migrate(pipeline));

        assertEquals(tree("{\"runEnd\":[" + entry + "]}"), pipeline, "no duplicate, the block dropped");
    }

    @Test
    public void aBlockWithoutCommandIsDropped() {
        for (String block : List.of("{}", "{\"command\":\" \"}", "{\"command\":null}", "\"x\"")) {
            JsonObject pipeline = tree("{\"postProcess\":" + block + "}");

            assertTrue(RunEndMigration.migrate(pipeline), block);

            assertEquals(new JsonObject(), pipeline, block);
        }
    }

    @Test
    public void aPipelineWithoutTheBlockIsLeftAsItIs() {
        JsonObject pipeline = tree("{\"runEnd\":[{\"action\":\"x\"}]}");

        assertFalse(RunEndMigration.migrate(pipeline));

        assertEquals(tree("{\"runEnd\":[{\"action\":\"x\"}]}"), pipeline);
    }

    @Test
    public void theEntriesAreReadWithTheirConditionsAndTimeLimit() {
        PipelineConfig config = parse("{\"runEnd\":[{\"plugin\":\"archive\",\"action\":\"archive.zip\","
                + "\"version\":\"1.0\",\"when\":[\"success\",\"cancelled\"],\"timeoutSeconds\":0,"
                + "\"actionConfig\":{\"target\":\"t\"}},{\"action\":\"post.process\"}]}");

        RunEndStepConfig archive = config.runEnd().getFirst();
        assertEquals(EnumSet.of(RunOutcome.SUCCESS, RunOutcome.CANCELLED), archive.outcomes());
        assertEquals(0, archive.timeoutSeconds());
        assertEquals(new PipelineStepConfig("archive", "archive.zip", "1.0", null, null, null, null,
                tree("{\"target\":\"t\"}")), archive.asStep());
        assertFalse(archive.isPostProcess());

        RunEndStepConfig postProcess = config.runEnd().get(1);
        assertEquals(Set.of(RunOutcome.SUCCESS, RunOutcome.ERROR), postProcess.outcomes(), "the default conditions");
        assertNull(postProcess.timeoutSeconds(), "the action's default");
        assertTrue(postProcess.isPostProcess());
    }

    @Test
    public void withoutRunEndTheListIsEmpty() {
        assertEquals(List.of(), parse("{}").runEnd());
        assertEquals(List.of(), parse("{\"runEnd\":null}").runEnd());
        assertEquals(List.of(), new PipelineConfig(null, null, null, null, null, null).runEnd());
    }

    @Test
    public void anEmptyOrUnknownConditionIsRefused() {
        String unknown = PipelineChecks.runEndRefusal(parse("{\"runEnd\":[{\"action\":\"post.process\"},"
                + "{\"action\":\"email.send\",\"when\":[\"success\",\"oops\"]}]}")).orElseThrow();
        assertTrue(unknown.contains("oops") && unknown.contains("email.send") && unknown.contains("2"), unknown);

        String empty = PipelineChecks.runEndRefusal(parse("{\"runEnd\":[{\"action\":\"email.send\",\"when\":[]}]}"))
                .orElseThrow();
        assertTrue(empty.contains("email.send"), empty);

        assertTrue(PipelineChecks.runEndRefusal(parse("{\"runEnd\":[{\"action\":\"x\",\"when\":[null]}]}")).isPresent());
        assertTrue(PipelineChecks.runEndRefusal(parse("{\"runEnd\":[{\"action\":\"x\","
                + "\"when\":[\"success\",\"error\",\"cancelled\"]},{\"action\":\"y\"}]}")).isEmpty());
    }
}

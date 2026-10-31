package com.copybot.ui.model;

import com.copybot.engine.pipeline.PipelineStatus;
import com.copybot.ui.model.RecentPipelines.Entry;
import com.copybot.ui.model.RecentPipelines.LastRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The recent pipelines of the home screen (spec desktop-ui §1, §6). */
public class RecentPipelinesTest {

    @TempDir
    Path tempDir;

    private static final Instant T0 = Instant.parse("2026-09-28T17:42:00Z");

    private static Instant at(int minutes) {
        return T0.plusSeconds(60L * minutes);
    }

    private Path pipeline(String name) throws IOException {
        return Files.writeString(tempDir.resolve(name), "{}");
    }

    private static List<String> names(RecentPipelines recents) {
        return recents.entries().stream().map(Entry::displayName).toList();
    }

    @Test
    public void theLastOpenedComesFirstAndAppearsOnce() throws IOException {
        RecentPipelines recents = new RecentPipelines();
        recents.touch(pipeline("sd-card.json"), at(0));
        recents.touch(pipeline("phone.json"), at(1));
        recents.touch(tempDir.resolve("sub/../sd-card.json"), at(2));

        assertEquals(List.of("sd-card", "phone"), names(recents));
        assertEquals(at(2), recents.entries().getFirst().openedAt());
    }

    @Test
    public void onlyTheTenLastAreKept() throws IOException {
        RecentPipelines recents = new RecentPipelines();
        for (int i = 0; i < 12; i++) {
            recents.touch(pipeline("p" + i + ".json"), at(i));
        }

        assertEquals(RecentPipelines.MAX, recents.entries().size());
        assertEquals("p11", names(recents).getFirst());
        assertFalse(names(recents).contains("p1"), "the oldest are dropped");
    }

    @Test
    public void aRunIsRecordedOnItsPipelineAndKeptWhenReopened() throws IOException {
        RecentPipelines recents = new RecentPipelines();
        Path sd = pipeline("sd-card.json");
        recents.touch(sd, at(0));
        recents.touch(pipeline("phone.json"), at(1));
        LastRun run = new LastRun(at(5), PipelineStatus.SUCCESS, 120, 3, 1);

        recents.recordRun(sd, run);

        assertEquals(List.of("phone", "sd-card"), names(recents), "recording a run does not reorder");
        assertEquals(run, recents.entries().get(1).lastRun());
        recents.touch(sd, at(9));
        assertEquals(run, recents.entries().getFirst().lastRun());
        assertNull(recents.entries().get(1).lastRun());
    }

    @Test
    public void aRunOfAPipelineNotInTheListAddsIt() throws IOException {
        RecentPipelines recents = new RecentPipelines();
        LastRun run = new LastRun(at(5), PipelineStatus.ERROR, 0, 0, 4);

        recents.recordRun(pipeline("new.json"), run);

        assertEquals(List.of("new"), names(recents));
        assertEquals(run, recents.entries().getFirst().lastRun());
    }

    @Test
    public void anEntryCanBeRemoved() throws IOException {
        RecentPipelines recents = new RecentPipelines();
        recents.touch(pipeline("a.json"), at(0));
        recents.touch(pipeline("b.json"), at(1));

        recents.remove(tempDir.resolve("a.json"));

        assertEquals(List.of("b"), names(recents));
    }

    @Test
    public void aDeletedFileIsReportedMissing() throws IOException {
        RecentPipelines recents = new RecentPipelines();
        Path gone = pipeline("gone.json");
        recents.touch(gone, at(0));
        recents.touch(pipeline("here.json"), at(1));

        Files.delete(gone);

        assertFalse(recents.entries().get(0).isMissing());
        assertTrue(recents.entries().get(1).isMissing());
    }

    @Test
    public void theDisplayNameIsTheFileNameWithoutExtension() {
        assertEquals("sd-card", RecentPipelines.displayName(Path.of("x", "sd-card.json")));
        assertEquals("a.b", RecentPipelines.displayName(Path.of("a.b.json")));
        assertEquals("noext", RecentPipelines.displayName(Path.of("noext")));
        assertEquals(".hidden", RecentPipelines.displayName(Path.of(".hidden")));
    }

    @Test
    public void theJsonRoundTripKeepsOrderDatesAndRuns() throws IOException {
        RecentPipelines recents = new RecentPipelines();
        recents.touch(pipeline("a.json"), at(0));
        recents.touch(pipeline("b.json"), at(1));
        recents.recordRun(tempDir.resolve("a.json"), new LastRun(at(3), PipelineStatus.CANCELLED, 2, 0, 0));

        RecentPipelines read = RecentPipelines.fromJson(recents.toJson());

        assertEquals(recents.entries(), read.entries());
    }

    @Test
    public void unreadablePreferencesGiveWhatCanBeRead() throws IOException {
        assertEquals(List.of(), RecentPipelines.fromJson(null).entries());
        assertEquals(List.of(), RecentPipelines.fromJson("not json {").entries());
        assertEquals(List.of(), RecentPipelines.fromJson("{\"path\":\"x\"}").entries());
        String path = pipeline("ok.json").toString().replace("\\", "\\\\");
        String json = "[{\"path\":\"" + path + "\",\"openedAt\":\"2026-09-28T17:42:00Z\"},"
                + "{\"path\":\"y.json\",\"openedAt\":\"yesterday\"},"
                + "{\"path\":\"z.json\",\"openedAt\":\"2026-09-28T17:42:00Z\",\"lastRun\":{\"status\":\"DONE?\"}},"
                + "42]";

        RecentPipelines read = RecentPipelines.fromJson(json);

        assertEquals(List.of("ok", "z"), names(read), "an unreadable last run never drops its entry");
        assertNull(read.entries().get(1).lastRun());
    }

    @Test
    public void anUnreadableLastRunIsForgottenAndTheEntryKept() {
        String json = "[{\"path\":\"a.json\",\"openedAt\":\"2026-09-28T17:42:00Z\",\"lastRun\":null},"
                + "{\"path\":\"b.json\",\"openedAt\":\"2026-09-28T17:42:00Z\",\"lastRun\":{\"at\":\"later\","
                + "\"status\":\"SUCCESS\",\"copied\":1,\"skipped\":0,\"errors\":0}},"
                + "{\"path\":\"c.json\",\"openedAt\":\"2026-09-28T17:42:00Z\",\"lastRun\":{\"at\":\"2026-09-28T17:42:00Z\","
                + "\"status\":\"NO_SUCH_STATUS\",\"copied\":1,\"skipped\":0,\"errors\":0}},"
                + "{\"path\":\"d.json\",\"openedAt\":\"2026-09-28T17:42:00Z\",\"lastRun\":\"yesterday\"}]";

        RecentPipelines read = RecentPipelines.fromJson(json);

        assertEquals(List.of("a", "b", "c", "d"), names(read));
        read.entries().forEach(e -> assertNull(e.lastRun(), e.displayName()));
    }

    @Test
    public void theJsonIsCutToFitAPreferenceValue() throws IOException {
        RecentPipelines recents = new RecentPipelines();
        RecentPipelines newestThree = new RecentPipelines();
        for (int i = 0; i < 5; i++) {
            recents.touch(pipeline("pipeline-" + i + ".json"), at(i));
            if (i >= 2) {
                newestThree.touch(tempDir.resolve("pipeline-" + i + ".json"), at(i));
            }
        }
        String threeJson = newestThree.toJson();
        assertTrue(threeJson.length() < recents.toJson().length(), "the limit really forces a cut");

        assertEquals(threeJson, recents.toJsonWithin(threeJson.length()), "exactly the three most recent");
        String two = recents.toJsonWithin(threeJson.length() - 1);
        assertEquals(List.of("pipeline-4", "pipeline-3"), names(RecentPipelines.fromJson(two)),
                "one character less: the third most recent goes too");
        assertEquals(recents.toJson(), recents.toJsonWithin(Integer.MAX_VALUE));
    }
}

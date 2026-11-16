package com.copybot.engine.pipeline;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The steps of the ffmpeg actions written when they were in the plugin exec 1.0.0 (0.8.0-beta.1). */
public class MovedActionsMigrationTest {

    private static JsonObject tree(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    public void anFfmpegStepOfExecMovesToTheFfmpegPluginInEverySectionAndSecondaryOutput() {
        String exec = "\"plugin\":\"com.copybot.plugin.exec\"";
        String ffmpeg = "\"plugin\":\"com.copybot.plugin.ffmpeg\"";
        JsonObject pipeline = tree("{"
                + "\"inSteps\":[{" + exec + ",\"action\":\"ffmpeg.in\"}],"
                + "\"analyseSteps\":[{" + exec + ",\"action\":\"ffmpeg.probe\",\"version\":\"1.0.0\"}],"
                + "\"actionSteps\":[{" + exec + ",\"action\":\"ffmpeg.video\",\"version\":\"1.0\","
                + "\"actionConfig\":{\"quality\":\"high\"}},{" + exec + ",\"action\":\"exec.process\",\"version\":\"1.0.0\"}],"
                + "\"outStep\":{" + exec + ",\"action\":\"ffmpeg.out\"},"
                + "\"secondaryOutputs\":[{\"name\":\"thumbs\",\"actionSteps\":[{" + exec + ",\"action\":\"ffmpeg.thumbnail\"}],"
                + "\"outStep\":{\"plugin\":\"embedded\",\"action\":\"file.copy\"}}],"
                + "\"runEnd\":[{" + exec + ",\"action\":\"ffmpeg.end\"}]}");

        assertTrue(MovedActionsMigration.migrate(pipeline));

        assertEquals(tree("{"
                + "\"inSteps\":[{" + ffmpeg + ",\"action\":\"ffmpeg.in\"}],"
                + "\"analyseSteps\":[{" + ffmpeg + ",\"action\":\"ffmpeg.probe\"}],"
                + "\"actionSteps\":[{" + ffmpeg + ",\"action\":\"ffmpeg.video\","
                + "\"actionConfig\":{\"quality\":\"high\"}},{" + exec + ",\"action\":\"exec.process\",\"version\":\"1.0.0\"}],"
                + "\"outStep\":{" + ffmpeg + ",\"action\":\"ffmpeg.out\"},"
                + "\"secondaryOutputs\":[{\"name\":\"thumbs\",\"actionSteps\":[{" + ffmpeg + ",\"action\":\"ffmpeg.thumbnail\"}],"
                + "\"outStep\":{\"plugin\":\"embedded\",\"action\":\"file.copy\"}}],"
                + "\"runEnd\":[{" + exec + ",\"action\":\"ffmpeg.end\"}]}"), pipeline,
                "the version of exec dropped; exec.process and the run-end list untouched");
    }

    @Test
    public void aPipelineWithoutSuchStepOrOfAnotherShapeIsLeftAsItIs() {
        for (String json : new String[]{
                "{}",
                "{\"actionSteps\":[{\"plugin\":\"com.copybot.plugin.ffmpeg\",\"action\":\"ffmpeg.video\"}]}",
                "{\"actionSteps\":[{\"plugin\":\"com.copybot.plugin.exec\",\"action\":\"exec.process\"}]}",
                "{\"actionSteps\":[{\"plugin\":\"com.copybot.plugin.exec\",\"action\":\"ffmpegx\"}]}",
                "{\"actionSteps\":[{\"plugin\":\"other\",\"action\":\"ffmpeg.video\"}]}",
                "{\"actionSteps\":{\"plugin\":\"com.copybot.plugin.exec\",\"action\":\"ffmpeg.video\"}}",
                "{\"actionSteps\":[1,null,{\"plugin\":1,\"action\":[\"ffmpeg.video\"]}],\"outStep\":[]}",
                "{\"secondaryOutputs\":{\"actionSteps\":[]}}",
                "{\"secondaryOutputs\":[1,{\"outStep\":\"x\"}]}"}) {
            JsonObject pipeline = tree(json);

            assertFalse(MovedActionsMigration.migrate(pipeline), json);

            assertEquals(tree(json), pipeline, json);
        }
    }
}

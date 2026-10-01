package com.copybot.ui.model;

import com.copybot.engine.pipeline.StepType;
import com.copybot.engine.plugin.CatalogAction;
import com.copybot.ui.model.PipelineDocument.Section;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static com.copybot.ui.model.TestCatalog.CATALOG;
import static com.copybot.ui.model.TestCatalog.EXIF_1;
import static com.copybot.ui.model.TestCatalog.EXIF_2;
import static com.copybot.ui.model.TestCatalog.READ;
import static com.copybot.ui.model.TestCatalog.WRITE;
import static org.junit.jupiter.api.Assertions.*;

/** Which actions a section offers and which one a step resolves to (spec desktop-ui §3). */
public class StepCatalogTest {

    private static JsonObject step(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    public void aSectionOffersTheActionsOfItsStepTypeInTheirMostRecentVersion() {
        assertEquals(List.of(READ), CATALOG.forSection(Section.IN));
        assertEquals(List.of(EXIF_2), CATALOG.forSection(Section.ANALYZE));
        assertEquals(List.of(), CATALOG.forSection(Section.PROCESS));
        assertEquals(List.of(WRITE), CATALOG.forSection(Section.OUT));
    }

    @Test
    public void aStepWithoutPluginIsAnEmbeddedAction() {
        assertEquals(Optional.of(READ), CATALOG.find(Section.IN, step("{\"action\":\"file.read\"}")));
        assertEquals(Optional.of(READ), CATALOG.find(Section.IN, step("{\"plugin\":\" \",\"action\":\"file.read\"}")));
        assertEquals(Optional.of(READ), CATALOG.find(Section.IN, step("{\"plugin\":\"embedded\",\"action\":\"file.read\"}")));
    }

    @Test
    public void theVersionPicksTheCompatiblePlugin() {
        assertEquals(Optional.of(EXIF_2), CATALOG.find(Section.ANALYZE, step("{\"plugin\":\"com.acme.exif\",\"action\":\"exif.read\"}")));
        assertEquals(Optional.of(EXIF_1), CATALOG.find(Section.ANALYZE,
                step("{\"plugin\":\"com.acme.exif\",\"action\":\"exif.read\",\"version\":\"1.4\"}")));
        assertEquals(Optional.empty(), CATALOG.find(Section.ANALYZE,
                step("{\"plugin\":\"com.acme.exif\",\"action\":\"exif.read\",\"version\":\"3.0\"}")));
    }

    @Test
    public void anUnknownPluginActionOrSectionFindsNothing() {
        assertEquals(Optional.empty(), CATALOG.find(Section.ANALYZE, step("{\"plugin\":\"com.other\",\"action\":\"exif.read\"}")));
        assertEquals(Optional.empty(), CATALOG.find(Section.IN, step("{\"action\":\"file.list\"}")));
        assertEquals(Optional.empty(), CATALOG.find(Section.OUT, step("{\"action\":\"file.read\"}")), "file.read is an input");
        assertEquals(Optional.empty(), CATALOG.find(Section.IN, step("{}")));
    }

    @Test
    public void thePluginVersionIsPickedFirstThenTheActionInIt() {
        CatalogAction legacy = TestCatalog.action("com.acme.exif", "1.4.0", "exif.legacy", StepType.ANALYZE,
                TestCatalog.ExifConfig.class);
        StepCatalog catalog = new StepCatalog(List.of(EXIF_2, EXIF_1, legacy, READ, WRITE));

        assertEquals(Optional.empty(), catalog.find(Section.ANALYZE,
                step("{\"plugin\":\"com.acme.exif\",\"action\":\"exif.legacy\"}")),
                "the engine picks 2.1.0, which has no exif.legacy: it would fail");
        assertEquals(Optional.of(legacy), catalog.find(Section.ANALYZE,
                step("{\"plugin\":\"com.acme.exif\",\"action\":\"exif.legacy\",\"version\":\"1.4\"}")));
        assertEquals(List.of(EXIF_2), catalog.forSection(Section.ANALYZE),
                "an action missing from the newest version is not offered");
    }
}

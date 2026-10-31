package com.copybot.ui;

import com.copybot.engine.pipeline.GroupingRuleConfig;
import com.copybot.engine.plugin.CatalogPreset;
import com.copybot.engine.plugin.PluginCatalog;
import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.model.StepCatalog;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The rules built by the "add a rule" dialog and their labels in the list. */
public class GroupingFormsTest {

    private static final StepCatalog NO_PLUGIN = new StepCatalog(new PluginCatalog(List.of(), List.of()));
    private static final CatalogPreset PRESET = new CatalogPreset("com.acme.takeout", "takeout", "1.0.0", "takeout",
            "Google Takeout", "", List.of(new GroupingRuleConfig(null, null, "sameBase", List.of("jpg"),
            Map.of("json", "read"), null, null, null, null)));
    private static final StepCatalog TAKEOUT = new StepCatalog(new PluginCatalog(List.of(), List.of(),
            List.of(PRESET)));

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    @Test
    public void companionsAreOneExtensionAndRolePerLine() {
        assertEquals(Map.of("xmp", "copy", "json", "read", "thm", "copy"),
                GroupingForms.parseCompanions(" .xmp\njson = read\n\nthm=unknown\n"));
        assertEquals(List.of("xmp", "json", "thm"),
                List.copyOf(GroupingForms.parseCompanions("xmp\njson=read\nthm").keySet()), "order kept");
    }

    @Test
    public void aRuleIsBuiltFromTheFieldsOfItsTypeAndRefusedWhenOneIsMissing() {
        assertEquals(json("{\"type\":\"sameBase\",\"main\":[\"cr3\",\"jpg\"],\"companions\":{\"xmp\":\"copy\"}}"),
                GroupingForms.rule("sameBase", "cr3, jpg", "xmp", "", "", "copy", "name"));
        assertEquals(json("{\"type\":\"doubleExtension\",\"companions\":{\"xmp\":\"copy\"}}"),
                GroupingForms.rule("doubleExtension", "", "xmp=copy", "", "", "copy", "name"));
        assertEquals(json("{\"type\":\"folder\",\"main\":[\"html\"],\"folder\":\"{base}_files\",\"role\":\"copy\"}"),
                GroupingForms.rule("folder", "html", "", "{base}_files", "", "copy", "name"));
        assertEquals(json("{\"type\":\"regex\",\"main\":\"(.+)\\\\.mp4\",\"companion\":\"\\\\1\\\\.json\","
                        + "\"role\":\"read\",\"link\":\"base\"}"),
                GroupingForms.rule("regex", "(.+)\\.mp4", "", "", "\\1\\.json", "read", "base"));
        assertNull(GroupingForms.rule("sameBase", "", "xmp", "", "", "copy", "name"), "no main extension");
        assertNull(GroupingForms.rule("sameBase", "cr3", " ", "", "", "copy", "name"), "no companion");
        assertNull(GroupingForms.rule("folder", "html", "", " ", "", "copy", "name"), "no folder");
        assertNull(GroupingForms.rule("regex", "(.+)", "", "", "", "read", "name"), "no companion expression");
    }

    @Test
    public void anOwnRuleIsLabelledByItsTypeMainAndCompanions() {
        assertEquals("sameBase: cr3, nef + xmp (copy), thm (read)", GroupingForms.ruleLabel(NO_PLUGIN,
                json("{\"type\":\"sameBase\",\"main\":[\"cr3\",\"nef\"],\"companions\":{\"xmp\":\"copy\",\"thm\":\"read\"}}")));
        assertEquals("folder: html + {base}_files (copy)", GroupingForms.ruleLabel(NO_PLUGIN,
                json("{\"type\":\"folder\",\"main\":[\"html\"],\"folder\":\"{base}_files\",\"role\":\"copy\"}")));
        assertEquals("doubleExtension + xmp (copy)", GroupingForms.ruleLabel(NO_PLUGIN,
                json("{\"type\":\"doubleExtension\",\"companions\":{\"xmp\":\"copy\"}}")));
    }

    @Test
    public void aPresetIsLabelledByItsNameAndAnUnavailableOneSaysWhy() {
        assertEquals(ResourcesEngine.getString("editor.grouping.preset.iphone"),
                GroupingForms.ruleLabel(TAKEOUT, json("{\"preset\":\"iphone\"}")));
        assertEquals("nope (?)", GroupingForms.ruleLabel(TAKEOUT, json("{\"preset\":\"nope\"}")));
        assertEquals("Google Takeout", GroupingForms.ruleLabel(TAKEOUT,
                json("{\"preset\":\"takeout\",\"plugin\":\"com.acme.takeout\"}")));
        assertEquals("other (?)", GroupingForms.ruleLabel(TAKEOUT,
                json("{\"preset\":\"other\",\"plugin\":\"com.acme.takeout\"}")), "unknown in a loaded plugin");
        assertEquals(ResourcesEngine.getString("editor.grouping.preset.plugin-missing", "takeout"),
                GroupingForms.ruleLabel(NO_PLUGIN, json("{\"preset\":\"takeout\",\"plugin\":\"com.acme.takeout\"}")));
        StepCatalog allDiscarded = new StepCatalog(new PluginCatalog(List.of(), List.of(), List.of(),
                Set.of("com.acme.takeout")));
        assertEquals("takeout (?)", GroupingForms.ruleLabel(allDiscarded,
                json("{\"preset\":\"takeout\",\"plugin\":\"com.acme.takeout\"}")),
                "its plugin is loaded, the preset was discarded: invalid, not missing");
    }

    @Test
    public void aPluginPresetIsOfferedWithItsPluginAndCitedByIt() {
        assertEquals(ResourcesEngine.getString("editor.grouping.preset.of-plugin", "Google Takeout",
                "com.acme.takeout"), GroupingForms.menuLabel(PRESET));
        assertEquals(json("{\"preset\":\"takeout\",\"plugin\":\"com.acme.takeout\"}"), GroupingForms.citation(PRESET));
    }
}

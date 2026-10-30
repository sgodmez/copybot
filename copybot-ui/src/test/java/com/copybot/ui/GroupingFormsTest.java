package com.copybot.ui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The rules built by the "add a rule" dialog and their labels in the list. */
public class GroupingFormsTest {

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
        assertEquals("sameBase: cr3, nef + xmp (copy), thm (read)", GroupingForms.ruleLabel(
                json("{\"type\":\"sameBase\",\"main\":[\"cr3\",\"nef\"],\"companions\":{\"xmp\":\"copy\",\"thm\":\"read\"}}")));
        assertEquals("folder: html + {base}_files (copy)", GroupingForms.ruleLabel(
                json("{\"type\":\"folder\",\"main\":[\"html\"],\"folder\":\"{base}_files\",\"role\":\"copy\"}")));
        assertEquals("doubleExtension + xmp (copy)", GroupingForms.ruleLabel(
                json("{\"type\":\"doubleExtension\",\"companions\":{\"xmp\":\"copy\"}}")));
    }
}

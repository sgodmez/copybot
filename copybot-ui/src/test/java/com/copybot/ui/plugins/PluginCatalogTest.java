package com.copybot.ui.plugins;

import com.copybot.ui.update.UpdateException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PluginCatalogTest {

    static final String FULL = """
            { "format": 1, "plugins": [
              { "module": "com.copybot.plugin.metadataextractor", "code": "metadata-extractor", "version": "1.0.0",
                "engine": "1.0", "tag": "v1.0.0", "asset": "copybot-plugin-metadata-extractor-1.0.0.zip",
                "description": { "en": "Metadata", "fr": "Metadonnees", "it": "Metadati" } },
              { "module": "com.copybot.plugin.gpszone", "code": "gps-zone", "version": "1.2.0",
                "engine": "1.1", "tag": "v1.1.0", "asset": "copybot-plugin-gps-zone-1.2.0.zip",
                "description": { "en": "Zones" },
                "license": { "name": "ODbL", "notice": { "en": "Data under ODbL", "fr": "Donnees sous ODbL" } } }
            ] }""";

    @Test
    public void aCompleteCatalogueGivesItsEntriesAndLicences() throws Exception {
        PluginCatalog catalog = PluginCatalog.parse(FULL);

        assertEquals(2, catalog.entries().size());
        PluginCatalog.Entry first = catalog.entries().get(0);
        assertEquals("com.copybot.plugin.metadataextractor", first.module());
        assertEquals("metadata-extractor", first.code());
        assertEquals("1.0.0", first.version());
        assertEquals("1.0", first.engine());
        assertEquals("v1.0.0", first.tag());
        assertEquals("copybot-plugin-metadata-extractor-1.0.0.zip", first.asset());
        assertEquals("Metadonnees", first.description().get("fr"));
        assertNull(first.license());
        PluginCatalog.License license = catalog.entries().get(1).license();
        assertEquals("ODbL", license.name());
        assertEquals("Data under ODbL", license.notice().get("en"));
    }

    @Test
    public void theChangesOfAnEntryAreReadPerLanguage() throws Exception {
        PluginCatalog catalog = PluginCatalog.parse("""
                { "format": 1, "plugins": [
                  { "module": "m.a", "code": "a", "version": "1.0.1", "engine": "1.0", "tag": "v1", "asset": "a.zip",
                    "changes": { "en": "Smaller", "fr": "Plus petit" } },
                  { "module": "m.b", "code": "b", "version": "1.0.0", "engine": "1.0", "tag": "v1", "asset": "b.zip" }
                ] }""");

        assertEquals("Plus petit", catalog.entries().get(0).changes().get("fr"));
        assertEquals("Smaller", catalog.entries().get(0).changes().get("en"));
        assertTrue(catalog.entries().get(1).changes().isEmpty());
    }

    @Test
    public void anEntryWithoutModuleIsIgnoredNotTheWholeCatalogue() throws Exception {
        PluginCatalog catalog = PluginCatalog.parse("""
                { "format": 1, "plugins": [
                  { "code": "x", "version": "1.0.0", "engine": "1.0", "tag": "v1", "asset": "x.zip" },
                  { "module": "m.ok", "code": "ok", "version": "1.0.0", "engine": "1.0", "tag": "v1", "asset": "ok.zip" },
                  { "module": "m.bad", "code": "../bad", "version": "1.0.0", "engine": "1.0", "tag": "v1", "asset": "b.zip" },
                  "nonsense" ] }""");

        assertEquals(1, catalog.entries().size());
        assertEquals("ok", catalog.entries().get(0).code());
    }

    @Test
    public void theRequiredPluginsOfAnEntryAreRead() throws Exception {
        PluginCatalog catalog = PluginCatalog.parse("""
                { "format": 1, "plugins": [
                  { "module": "m.exec", "code": "exec", "version": "2.0.0", "engine": "1.2", "tag": "v1", "asset": "e.zip" },
                  { "module": "m.ffmpeg", "code": "ffmpeg", "version": "1.0.0", "engine": "1.2", "tag": "v1",
                    "asset": "f.zip", "requires": [ { "code": "exec", "version": "2.0.0" } ] },
                  { "module": "m.bad", "code": "bad", "version": "1.0.0", "engine": "1.2", "tag": "v1",
                    "asset": "b.zip", "requires": [ { "code": "../exec", "version": "2.0.0" } ] },
                  { "module": "m.blank", "code": "blank", "version": "1.0.0", "engine": "1.2", "tag": "v1",
                    "asset": "b.zip", "requires": [ { "code": "exec" } ] }
                ] }""");

        assertEquals(List.of("exec", "ffmpeg"), catalog.entries().stream().map(PluginCatalog.Entry::code).toList());
        assertEquals(List.of(), catalog.entries().get(0).requires());
        assertEquals(List.of(new PluginCatalog.Requirement("exec", "2.0.0")), catalog.entries().get(1).requires());
    }

    @Test
    public void anUnreadableCatalogueIsAReadableError() {
        for (String json : new String[] {"not json", "[]", "{}", "{\"format\": 2, \"plugins\": []}",
                "{\"format\": 1, \"plugins\": 3}", ""}) {
            UpdateException e = assertThrows(UpdateException.class, () -> PluginCatalog.parse(json), json);
            assertEquals("update.error.unreadable", e.key());
        }
    }

    @Test
    public void anEmptyCatalogueIsValid() throws Exception {
        assertTrue(PluginCatalog.parse("{\"format\": 1, \"plugins\": []}").entries().isEmpty());
    }
}

package com.copybot.plugin.embedded.actions;

import com.copybot.exception.CopybotException;
import com.copybot.plugin.embedded.actions.FileWriteSettings.Compare;
import com.copybot.plugin.embedded.actions.FileWriteSettings.Policy;
import com.copybot.plugin.embedded.actions.FileWriteSettings.Verify;
import com.copybot.plugin.embedded.actions.FileWriteSettings.WriteMode;
import com.copybot.resources.ResourcesEngine;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The file.write configuration (spec safe-write §1, §5). */
public class FileWriteSettingsTest {

    private static FileWriteSettings settings(String actionConfig) {
        return FileWriteSettings.of(new Gson().fromJson(actionConfig, FileWriteConfig.class));
    }

    @Test
    public void everythingButTheOutPatternHasADefault() {
        FileWriteSettings settings = settings("{\"outPattern\":\"nas/{name}\"}");

        assertEquals("nas/{name}", settings.outPattern());
        assertEquals(Compare.PARTIAL_HASH, settings.compare());
        assertEquals(Policy.SKIP, settings.ifIdentical());
        assertEquals(Policy.RENAME, settings.ifDifferent());
        assertEquals(WriteMode.TEMP_AND_RENAME, settings.writeMode());
        assertEquals(Verify.SIZE, settings.verify());
        assertFalse(settings.deleteSource());
    }

    @Test
    public void everyValueCanBeSet() {
        FileWriteSettings settings = settings("""
                {"outPattern":"nas/{name}",
                 "onConflict":{"compare":"sizeAndDate","ifIdentical":"overwrite","ifDifferent":"error"},
                 "writeMode":"direct","verify":"readBack","deleteSource":true}""");

        assertEquals(Compare.SIZE_AND_DATE, settings.compare());
        assertEquals(Policy.OVERWRITE, settings.ifIdentical());
        assertEquals(Policy.ERROR, settings.ifDifferent());
        assertEquals(WriteMode.DIRECT, settings.writeMode());
        assertEquals(Verify.READ_BACK, settings.verify());
        assertTrue(settings.deleteSource());
    }

    @Test
    public void aPartialOnConflictKeepsTheOtherDefaults() {
        FileWriteSettings settings = settings("{\"outPattern\":\"x\",\"onConflict\":{\"compare\":\"fullHash\"}}");

        assertEquals(Compare.FULL_HASH, settings.compare());
        assertEquals(Policy.SKIP, settings.ifIdentical());
        assertEquals(Policy.RENAME, settings.ifDifferent());
    }

    @Test
    public void anUnknownValueIsRefusedAndQuoted() {
        assertRefused("write.config.unknown-value", "{\"outPattern\":\"x\",\"verify\":\"readback\"}",
                "verify", "readback", "none, size, readBack");
        assertRefused("write.config.unknown-value", "{\"outPattern\":\"x\",\"writeMode\":\"atomic\"}",
                "writeMode", "atomic", "tempAndRename, direct");
        assertRefused("write.config.unknown-value", "{\"outPattern\":\"x\",\"onConflict\":{\"compare\":\"md5\"}}",
                "onConflict.compare", "md5", "size, sizeAndDate, partialHash, fullHash");
        assertRefused("write.config.unknown-value", "{\"outPattern\":\"x\",\"onConflict\":{\"ifIdentical\":\"keep\"}}",
                "onConflict.ifIdentical", "keep", "skip, rename, overwrite, error");
        assertRefused("write.config.unknown-value", "{\"outPattern\":\"x\",\"onConflict\":{\"ifDifferent\":\"merge\"}}",
                "onConflict.ifDifferent", "merge", "skip, rename, overwrite, error");
    }

    @Test
    public void theOutPatternIsRequired() {
        assertRefused("write.config.no-out-pattern", "{}");
        assertRefused("write.config.no-out-pattern", "{\"outPattern\":\" \"}");
        CopybotException e = assertThrows(CopybotException.class, () -> FileWriteSettings.of(null));
        assertEquals(ResourcesEngine.getString("write.config.no-out-pattern"), e.getMessage());
    }

    private static void assertRefused(String key, String actionConfig, Object... args) {
        CopybotException e = assertThrows(CopybotException.class, () -> settings(actionConfig));
        assertEquals(ResourcesEngine.getString(key, args), e.getMessage());
    }

    @Test
    public void deletingTheSourcesWithoutReadBackIsWarned() {
        assertEquals(1, settings("{\"outPattern\":\"x\",\"deleteSource\":true}").warnings().size());
        assertEquals(1, settings("{\"outPattern\":\"x\",\"deleteSource\":true,\"verify\":\"none\"}").warnings().size());
        assertEquals(List.of(), settings("{\"outPattern\":\"x\",\"deleteSource\":true,\"verify\":\"readBack\"}").warnings());
        assertEquals(List.of(), settings("{\"outPattern\":\"x\"}").warnings());
    }

    @Test
    public void theActionRefusesAnInvalidConfigurationAndReportsItsWarnings() {
        FileWriteAction action = new FileWriteAction();
        assertThrows(CopybotException.class, () -> action.loadConfig(JsonParser.parseString("{\"verify\":\"size\"}")));

        action.loadConfig(JsonParser.parseString("{\"outPattern\":\"x\",\"deleteSource\":true}"));
        assertEquals(1, action.configWarnings().size());
        assertFalse(action.configWarnings().getFirst().startsWith("%"), "a translated message");
    }
}

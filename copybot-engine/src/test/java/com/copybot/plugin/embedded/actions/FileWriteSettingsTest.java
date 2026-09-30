package com.copybot.plugin.embedded.actions;

import com.copybot.exception.CopybotException;
import com.copybot.plugin.embedded.actions.FileWriteSettings.Compare;
import com.copybot.plugin.embedded.actions.FileWriteSettings.Policy;
import com.copybot.plugin.embedded.actions.FileWriteSettings.Verify;
import com.copybot.plugin.embedded.actions.FileWriteSettings.WriteMode;
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
    public void legacyOverwriteTrueOverwritesADifferentTargetAndSkipsAnIdenticalOne() {
        FileWriteSettings settings = settings("{\"outPattern\":\"x\",\"overwrite\":true}");

        assertEquals(Policy.OVERWRITE, settings.ifDifferent());
        assertEquals(Policy.SKIP, settings.ifIdentical());
    }

    @Test
    public void legacyOverwriteFalseFailsOnADifferentTargetAndSkipsAnIdenticalOne() {
        FileWriteSettings settings = settings("{\"outPattern\":\"x\",\"overwrite\":false}");

        assertEquals(Policy.ERROR, settings.ifDifferent());
        assertEquals(Policy.SKIP, settings.ifIdentical());
    }

    @Test
    public void overwriteAndOnConflictTogetherAreRefused() {
        assertThrows(CopybotException.class,
                () -> settings("{\"outPattern\":\"x\",\"overwrite\":true,\"onConflict\":{\"ifDifferent\":\"rename\"}}"));
    }

    @Test
    public void anUnknownValueIsRefusedAndQuoted() {
        CopybotException e = assertThrows(CopybotException.class,
                () -> settings("{\"outPattern\":\"x\",\"verify\":\"readback\"}"));
        assertTrue(e.getMessage().contains("readback"), e.getMessage());

        assertThrows(CopybotException.class, () -> settings("{\"outPattern\":\"x\",\"writeMode\":\"atomic\"}"));
        assertThrows(CopybotException.class, () -> settings("{\"outPattern\":\"x\",\"onConflict\":{\"compare\":\"md5\"}}"));
        assertThrows(CopybotException.class, () -> settings("{\"outPattern\":\"x\",\"onConflict\":{\"ifIdentical\":\"keep\"}}"));
        assertThrows(CopybotException.class, () -> settings("{\"outPattern\":\"x\",\"onConflict\":{\"ifDifferent\":\"merge\"}}"));
    }

    @Test
    public void theOutPatternIsRequired() {
        assertThrows(CopybotException.class, () -> settings("{}"));
        assertThrows(CopybotException.class, () -> settings("{\"outPattern\":\" \"}"));
        assertThrows(CopybotException.class, () -> FileWriteSettings.of(null));
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

package com.copybot.engine.resources;

import com.copybot.config.CopybotConfig;
import com.copybot.utils.GsonUtil;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ResourceSettingsTest {

    @Test
    public void exactCapacityWinsOverPrefix() {
        CopybotConfig config = new CopybotConfig(null, null,
                Map.of("disk:*", 4, "disk:C:\\", 1), null);
        ResourceSettings settings = ResourceSettings.from(config);
        assertEquals(1, settings.capacityFor("disk:C:\\"));
        assertEquals(4, settings.capacityFor("disk:D:\\"));
    }

    @Test
    public void builtinDefaults() {
        ResourceSettings settings = ResourceSettings.from(null);
        assertEquals(Runtime.getRuntime().availableProcessors(), settings.capacityFor("cpu"));
        assertEquals(1, settings.capacityFor("gpu"));
        assertEquals(2, settings.capacityFor("disk:X:\\"));
        assertEquals(1, settings.capacityFor("net:flickr"));
    }

    @Test
    public void groupMembersShareOneCanonicalName() {
        CopybotConfig config = new CopybotConfig(null, null, null,
                List.of(List.of("disk:D:\\", "disk:E:\\")));
        ResourceSettings settings = ResourceSettings.from(config);
        assertEquals("disk:D:\\", settings.canonical("disk:D:\\"));
        assertEquals("disk:D:\\", settings.canonical("disk:E:\\"));
        assertEquals("cpu", settings.canonical("cpu"));
    }

    @Test
    public void configRecordParsesFromJson() {
        CopybotConfig config = GsonUtil.getGson().fromJson(
                "{\"resources\":{\"cpu\":4,\"disk:*\":8},\"resourceGroups\":[[\"disk:D\",\"disk:E\"]]}",
                CopybotConfig.class);
        ResourceSettings settings = ResourceSettings.from(config);
        assertEquals(4, settings.capacityFor("cpu"));
        assertEquals(8, settings.capacityFor("disk:D"));
        assertEquals("disk:D", settings.canonical("disk:E"));
    }
}

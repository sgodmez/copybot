package com.copybot.config;

import com.copybot.utils.GsonUtil;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The configuration holds the resources only: an old file that still names plugin folders is read all the same. */
public class CopybotConfigTest {

    private static CopybotConfig read(String json) {
        return GsonUtil.getGson().fromJson(json, CopybotConfig.class);
    }

    @Test
    public void anOldConfigWithPluginFieldsIsStillRead() {
        CopybotConfig config = read("{\"pluginPath\":\"x\",\"devPluginPaths\":[\"y\"],\"resources\":{\"cpu\":2}}");

        assertEquals(Map.of("cpu", 2), config.resources());
        assertNull(config.resourceGroups());
    }
}

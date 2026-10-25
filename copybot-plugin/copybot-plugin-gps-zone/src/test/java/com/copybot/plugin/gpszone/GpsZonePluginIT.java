package com.copybot.plugin.gpszone;

import com.copybot.engine.plugin.PluginDefinition;
import com.copybot.engine.plugin.PluginEngine;
import com.copybot.plugin.api.action.ActionDefinition;
import com.copybot.plugin.api.action.IAnalyzeAction;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The packaged plugin loaded by the engine as the application loads it in development: the directory
 * {@code target} of this module, its {@code classes} and its {@code lib} (copied at package, hence an integration
 * test, run by failsafe after it). Its libraries are then those of its own layer, with their own requirements
 * (commons-compress and its optional codecs), as in {@code plugins/gps-zone/} next to the application.
 */
public class GpsZonePluginIT {

    @Test
    public void thePackagedPluginLoadsAndSuggestsParisForAParisPhotoNotedWithoutSummerTime() throws Exception {
        Path target = Path.of("target").toAbsolutePath();
        assertTrue(Files.isDirectory(target.resolve("lib")), "packaged: target/lib");

        PluginEngine.load(Files.createTempDirectory("copybot-no-plugins"), List.of(target));

        PluginDefinition gpsZone = PluginEngine.getAllPlugins().stream()
                .filter(p -> "com.copybot.plugin.gpszone".equals(p.getName()))
                .findFirst().orElseThrow(() -> new AssertionError("not reported: " + PluginEngine.getAllPlugins()));
        assertNull(gpsZone.getErrorMessage(), "loaded");
        assertTrue(gpsZone.isActive());
        ModuleLayer layer = gpsZone.getPluginInstance().getClass().getModule().getLayer();
        assertNotSame(ModuleLayer.boot(), layer, "the plugin of target/, in its own layer");
        assertTrue(layer.findModule("com.github.luben.zstd_jni").orElseThrow().isNativeAccessEnabled(),
                "zstd-jni of the plugin layer has native access (the engine has it: failsafe argLine)");
        assertTrue(PluginEngine.catalog().actions().stream()
                        .anyMatch(a -> a.pluginCode().equals("gps-zone") && a.actionCode().equals("check")),
                "gps-zone.check in the catalog");

        ActionDefinition<? extends IAnalyzeAction> check = gpsZone.getPluginInstance().getAnalyzeActions().stream()
                .filter(a -> a.actionCode().equals("check")).findFirst().orElseThrow();
        IAnalyzeAction action = check.getInstance();
        assertSame(layer, action.getClass().getModule().getLayer());

        // shot in Paris in July, the camera noting +01:00 (no summer time), the GPS fixed 2 min before
        OffsetDateTime shot = OffsetDateTime.parse("2026-07-10T13:00:00+01:00");
        WorkItem photo = new WorkItem(URI.create("file:///card/DSC00001.JPG").toURL(), () -> new ByteArrayInputStream(new byte[0]));
        Map<String, Object> raw = photo.getMetadatas().raw();
        photo.getMetadatas().setTime(WorkItemMetadata.CAPTURE_DATE, shot.toZonedDateTime());
        raw.put(WorkItemMetadata.RECORDED_OFFSET, shot.getOffset());
        raw.put(WorkItemMetadata.GPS_LATITUDE, 48.8566);
        raw.put(WorkItemMetadata.GPS_LONGITUDE, 2.3522);
        raw.put(WorkItemMetadata.GPS_VALID, true);
        raw.put(WorkItemMetadata.GPS_TIME, shot.toInstant().minus(Duration.ofMinutes(2)));

        action.doAnalyze(photo);

        assertEquals(ZoneId.of("Europe/Paris"), raw.get(WorkItemMetadata.SUGGESTED_ZONE));
    }
}

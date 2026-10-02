package com.copybot.ui.model;

import com.copybot.engine.pipeline.StepType;
import com.copybot.engine.plugin.CatalogAction;
import com.copybot.engine.plugin.PluginCatalog;
import com.copybot.plugin.api.config.ConfigSchema;
import com.copybot.plugin.api.config.DirectoryPath;
import com.copybot.plugin.api.config.PatternField;
import com.copybot.plugin.api.config.Required;

import java.util.List;
import java.util.Map;

/** Hand-made catalog actions for the model tests: no plugin is loaded. */
final class TestCatalog {

    private TestCatalog() {
    }

    record ReadConfig(@Required @DirectoryPath String path, Boolean recursive, List<String> include) {
    }

    record Conflict(@Required String compare, String ifIdentical) {
    }

    record WriteConfig(@Required @PatternField String outPattern, Conflict onConflict, Integer bufferSize) {
    }

    record ExifConfig(Boolean gps) {
    }

    static final CatalogAction READ = action(CatalogAction.EMBEDDED_PLUGIN, null, "file.read", StepType.IN, ReadConfig.class);
    static final CatalogAction WRITE = action(CatalogAction.EMBEDDED_PLUGIN, null, "file.write", StepType.OUT, WriteConfig.class);
    static final CatalogAction EXIF_2 = action("com.acme.exif", "2.1.0", "exif.read", StepType.ANALYZE, ExifConfig.class);
    static final CatalogAction EXIF_1 = action("com.acme.exif", "1.4.0", "exif.read", StepType.ANALYZE, ExifConfig.class);

    /** In PluginEngine order: plugin name, most recent version first. */
    static final StepCatalog CATALOG = catalog(EXIF_2, EXIF_1, READ, WRITE);

    /** A catalog of these actions, in PluginEngine order, without failed plugin. */
    static StepCatalog catalog(CatalogAction... actions) {
        return new StepCatalog(new PluginCatalog(List.of(actions), List.of()));
    }

    static CatalogAction action(String plugin, String version, String code, StepType type, Class<? extends Record> config) {
        String prefix = "plugin." + plugin + "." + code;
        return new CatalogAction(plugin, plugin, version, code, type, code + " name", code + " description",
                ConfigSchema.of(config).withKeyPrefix(prefix), Map.of());
    }
}

package com.copybot.ui.model;

import com.copybot.engine.pipeline.StepType;
import com.copybot.engine.pipeline.GroupingRuleConfig;
import com.copybot.engine.plugin.CatalogAction;
import com.copybot.engine.plugin.CatalogPreset;
import com.copybot.engine.plugin.PluginCatalog;
import com.copybot.plugin.api.config.ConfigSchema;
import com.copybot.plugin.api.config.DirectoryPath;
import com.copybot.plugin.api.config.FilePath;
import com.copybot.plugin.api.config.PatternField;
import com.copybot.plugin.api.config.Required;
import com.copybot.plugin.api.config.Secret;

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

    record LaunchConfig(@Required @FilePath String command, List<String> args) {
    }

    record MailConfig(@Required String host, @Secret String password) {
    }

    record ThumbConfig(@Required Integer size, @Secret String token) {
    }

    static final CatalogAction READ = action(CatalogAction.EMBEDDED_PLUGIN, null, "file.read", StepType.IN, ReadConfig.class);
    static final CatalogAction WRITE = action(CatalogAction.EMBEDDED_PLUGIN, null, "file.write", StepType.OUT, WriteConfig.class);
    static final CatalogAction EXIF_2 = action("com.acme.exif", "2.1.0", "exif.read", StepType.ANALYZE, ExifConfig.class);
    static final CatalogAction EXIF_1 = action("com.acme.exif", "1.4.0", "exif.read", StepType.ANALYZE, ExifConfig.class);
    static final CatalogAction LAUNCH = action(CatalogAction.EMBEDDED_PLUGIN, null, "post.process", StepType.RUN_END,
            LaunchConfig.class);
    static final CatalogAction MAIL = action("notify-email", "1.0.0", "email.send", StepType.RUN_END, MailConfig.class);
    /** A process action, for the steps of a secondary output; not in {@link #CATALOG}. */
    static final CatalogAction THUMB = action("com.acme.thumbs", "1.0.0", "thumb.make", StepType.PROCESS,
            ThumbConfig.class);

    /** In PluginEngine order: plugin name, most recent version first. */
    static final StepCatalog CATALOG = catalog(EXIF_2, EXIF_1, READ, WRITE, LAUNCH, MAIL);

    /** A catalog of these actions, in PluginEngine order, without failed plugin. */
    static StepCatalog catalog(CatalogAction... actions) {
        return new StepCatalog(new PluginCatalog(List.of(actions), List.of()));
    }

    /** A grouping preset of a plugin without action: a web page folder and the JSON read next to the photos. */
    static final CatalogPreset TAKEOUT = new CatalogPreset("com.acme.takeout", "takeout", "1.0.0", "takeout",
            "Google Takeout", "Photos and their JSON", List.of(
            new GroupingRuleConfig(null, null, "sameBase", List.of("jpg"), Map.of("json", "read"), null, null, null,
                    null),
            new GroupingRuleConfig(null, null, "folder", List.of("html"), null, "{base}_files", "copy", null, null)));

    /** {@link #CATALOG}'s actions and the {@link #TAKEOUT} preset. */
    static final StepCatalog WITH_PRESETS = new StepCatalog(new PluginCatalog(
            List.of(EXIF_2, EXIF_1, READ, WRITE, LAUNCH, MAIL), List.of(), List.of(TAKEOUT)));

    static CatalogAction action(String plugin, String version, String code, StepType type, Class<? extends Record> config) {
        String prefix = "plugin." + plugin + "." + code;
        return new CatalogAction(plugin, plugin, version, code, type, code + " name", code + " description",
                ConfigSchema.of(config).withKeyPrefix(prefix), Map.of());
    }
}

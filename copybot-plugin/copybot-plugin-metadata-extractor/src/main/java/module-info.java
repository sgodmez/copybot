import com.copybot.plugin.api.definition.IPlugin;
import com.copybot.plugin.metadataextractor.MetadataExtractorPlugin;

module com.copybot.plugin.metadataextractor {
    requires com.copybot.engine;
    requires com.drew.metadata;

    exports com.copybot.plugin.metadataextractor;
    exports com.copybot.plugin.metadataextractor.actions;

    opens com.copybot.plugin.metadataextractor.i18n;

    provides IPlugin with MetadataExtractorPlugin;
}
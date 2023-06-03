import com.copybot.plugin.definition.IPlugin;
import com.copybot.plugin.metadataextractor.MetadataExtractorPlugin;

module com.copybot.plugin.metadataextractor {
    requires com.copybot.plugin.api;
    requires metadata.extractor;

    exports com.copybot.plugin.metadataextractor;
    exports com.copybot.plugin.metadataextractor.actions;

    opens com.copybot.plugin.metadataextractor.i18n;
    opens com.copybot.plugin.metadataextractor.views;

    provides IPlugin with MetadataExtractorPlugin;
}
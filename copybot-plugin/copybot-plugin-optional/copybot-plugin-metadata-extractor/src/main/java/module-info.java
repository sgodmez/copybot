import com.copybot.plugin.definition.api.IPlugin;
import com.copybot.plugin.metadataextractor.MetadataExtractorPlugin;

module com.copybot.plugin.metadataextractor {
    requires com.copybot.plugin.api;
    requires metadata.extractor;

    exports com.copybot.plugin.metadataextractor;

    provides IPlugin with MetadataExtractorPlugin;
}
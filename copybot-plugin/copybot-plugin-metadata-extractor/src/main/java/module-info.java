import com.copybot.plugin.api.definition.IPlugin;
import com.copybot.plugin.metadataextractor.MetadataExtractorPlugin;

module com.copybot.plugin.metadataextractor {
    requires com.copybot.engine;
    requires com.drew.metadata;
    // needs java.desktop: the runtime image holds it; with a custom image or --limit-modules, add
    // --add-modules java.desktop
    requires org.apache.commons.imaging;

    exports com.copybot.plugin.metadataextractor;
    exports com.copybot.plugin.metadataextractor.actions;

    opens com.copybot.plugin.metadataextractor.i18n;

    provides IPlugin with MetadataExtractorPlugin;
}
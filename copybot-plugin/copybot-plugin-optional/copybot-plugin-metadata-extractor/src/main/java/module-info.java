import com.copybot.plugin.api.ICBPluginModule;
import com.copybot.plugin.metadataextractor.DummyPluginModule;

module com.copybot.plugin.embedded {
    requires com.copybot.plugin.api;
    requires metadata.extractor;

    exports com.copybot.plugin.metadataextractor;

    provides ICBPluginModule with DummyPluginModule;
}
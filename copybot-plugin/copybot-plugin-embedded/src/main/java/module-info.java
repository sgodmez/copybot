import com.copybot.plugin.api.ICBPluginModule;
import com.copybot.plugin.embedded.DummyPluginModule;

module com.copybot.plugin.embedded {
    requires com.copybot.plugin.api;

    exports com.copybot.plugin.embedded;

    provides ICBPluginModule with DummyPluginModule;
}
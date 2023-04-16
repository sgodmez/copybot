import com.copybot.plugin.api.ICBPluginModule;
import com.copybot.plugin.demo.module.DemoPluginModuleModule;

module com.copybot.plugin.demo.module {
    requires com.copybot.plugin.api;
    requires metadata.extractor;

    exports com.copybot.plugin.demo.module;

    provides ICBPluginModule with DemoPluginModuleModule;
}
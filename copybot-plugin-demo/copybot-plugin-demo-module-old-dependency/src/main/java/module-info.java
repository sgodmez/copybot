import com.copybot.plugin.api.ICBPluginModule;
import com.copybot.plugin.demo.module.old.DemoPluginModuleModuleOlder;

module com.copybot.plugin.demo.module.old {
    requires com.copybot.plugin.api;
    requires metadata.extractor;

    exports com.copybot.plugin.demo.module.old;

    provides ICBPluginModule with DemoPluginModuleModuleOlder;
}
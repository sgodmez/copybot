import com.copybot.plugin.api.ICBPluginModule;
import com.copybot.plugin.demo.inheritance.DemoPluginInhheritance;

module com.copybot.plugin.demo.inheritance {
    requires com.copybot.plugin.api;
    requires com.copybot.plugin.demo.module;
    requires com.copybot.plugin.demo.module.old;
    requires metadata.extractor;

    provides ICBPluginModule with DemoPluginInhheritance;
}
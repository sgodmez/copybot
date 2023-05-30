import com.copybot.plugin.definition.IPlugin;
import com.copybot.plugin.demo.module.DemoTransformModuleModule;

module com.copybot.plugin.demo.module {
    requires com.copybot.plugin.api;
    requires metadata.extractor;

    exports com.copybot.plugin.demo.module;

    provides IPlugin with DemoTransformModuleModule;
}
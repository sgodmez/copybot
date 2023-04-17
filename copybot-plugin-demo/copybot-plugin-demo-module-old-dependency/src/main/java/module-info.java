import com.copybot.plugin.definition.api.IPlugin;
import com.copybot.plugin.demo.module.old.DemoTransformModuleModuleOlder;

module com.copybot.plugin.demo.module.old {
    requires com.copybot.plugin.api;
    requires metadata.extractor;

    exports com.copybot.plugin.demo.module.old;

    provides IPlugin with DemoTransformModuleModuleOlder;
}
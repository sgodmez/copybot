import com.copybot.plugin.definition.api.IPlugin;
import com.copybot.plugin.demo.inheritance.DemoTransformInhheritance;

module com.copybot.plugin.demo.inheritance {
    requires com.copybot.plugin.api;
    requires com.copybot.plugin.demo.module;
    requires com.copybot.plugin.demo.module.old;
    requires metadata.extractor;

    provides IPlugin with DemoTransformInhheritance;
}
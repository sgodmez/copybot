import com.copybot.plugin.api.definition.IPlugin;
import com.copybot.plugin.demo.module.DemoModulePlugin;

module com.copybot.plugin.demo.module {
    requires com.copybot.engine;
    requires metadata.extractor;

    exports com.copybot.plugin.demo.module;
    exports com.copybot.plugin.demo.module.actions;

    opens com.copybot.plugin.demo.module.i18n;

    provides IPlugin with DemoModulePlugin;
}
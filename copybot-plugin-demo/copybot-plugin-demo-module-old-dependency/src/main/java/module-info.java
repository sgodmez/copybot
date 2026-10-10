import com.copybot.plugin.api.definition.IPlugin;
import com.copybot.plugin.demo.module.DemoModulePlugin;

module com.copybot.plugin.demo.module {
    requires com.copybot.engine;
    requires copybot.plugin.demo.lib; // version 1.0 is not a module: named after its jar

    exports com.copybot.plugin.demo.module;
    exports com.copybot.plugin.demo.module.actions;

    opens com.copybot.plugin.demo.module.i18n;

    provides IPlugin with DemoModulePlugin;
}
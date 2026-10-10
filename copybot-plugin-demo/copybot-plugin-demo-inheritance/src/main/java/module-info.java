import com.copybot.plugin.api.definition.IPlugin;
import com.copybot.plugin.demo.inheritance.DemoInheritancePlugin;

module com.copybot.plugin.demo.inheritance {
    requires com.copybot.engine;
    requires com.copybot.plugin.demo.module;
    requires com.copybot.plugin.demo.module2;
    requires com.copybot.plugin.demo.lib;

    exports com.copybot.plugin.demo.inheritance.actions;

    opens com.copybot.plugin.demo.inheritance.i18n;

    provides IPlugin with DemoInheritancePlugin;
}
import com.copybot.plugin.definition.IPlugin;
import com.copybot.plugin.demo.inheritance.DemoInheritancePlugin;

module com.copybot.plugin.demo.inheritance {
    requires com.copybot.plugin.api;
    requires com.copybot.plugin.demo.module;
    requires com.copybot.plugin.demo.module2;
    requires metadata.extractor;

    opens com.copybot.plugin.demo.inheritance.i18n;

    provides IPlugin with DemoInheritancePlugin;
}
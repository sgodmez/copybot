import com.copybot.plugin.api.definition.IPlugin;
import com.copybot.plugin.demo.module2.DemoModule2Plugin;

module com.copybot.plugin.demo.module2 {
    requires com.copybot.engine;
    requires metadata.extractor;

    exports com.copybot.plugin.demo.module2;
    exports com.copybot.plugin.demo.module2.actions;

    opens com.copybot.plugin.demo.module2.i18n;

    provides IPlugin with DemoModule2Plugin;
}
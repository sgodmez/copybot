import com.copybot.plugin.definition.ILanguagePack;
import com.copybot.plugin.definition.IPlugin;

module com.copybot.engine {
    requires com.copybot.plugin.api;
    requires info.picocli;
    requires com.google.gson;
    requires com.copybot.plugin.embedded;

    opens com.copybot.engine to javafx.base;
    exports com.copybot.engine to com.copybot.ui;
    exports com.copybot.engine.plugin to com.copybot.ui;
    exports com.copybot.engine.pipeline to com.google.gson;
    exports com.copybot.engine.resources to com.copybot.ui;

    uses IPlugin;
    uses ILanguagePack;
}
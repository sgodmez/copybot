import com.copybot.plugin.definition.api.IPlugin;

module com.copybot.engine {
    requires com.copybot.plugin.api;
    requires info.picocli;
    requires com.google.gson;

    opens com.copybot.engine to javafx.base;
    exports com.copybot.engine to com.copybot.ui;
    exports com.copybot.engine.plugin to com.copybot.ui;

    uses IPlugin;
}
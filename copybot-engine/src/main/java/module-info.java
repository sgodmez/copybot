import com.copybot.plugin.api.definition.ILanguagePack;
import com.copybot.plugin.api.definition.IPlugin;
import com.copybot.plugin.embedded.CBEmbeddedPlugin;

module com.copybot.engine {
    requires java.logging;
    requires info.picocli;
    requires com.google.gson;

    exports com.copybot.config to com.google.gson;
    exports com.copybot.exception;
    exports com.copybot.resources;
    exports com.copybot.utils;

    exports com.copybot.engine to com.copybot.ui;
    opens com.copybot.engine to javafx.base, info.picocli;

    exports com.copybot.engine.pipeline to com.google.gson, com.copybot.ui;

    exports com.copybot.plugin.api.action;
    exports com.copybot.plugin.api.definition;

    exports com.copybot.plugin.embedded.actions;
    exports com.copybot to com.copybot.ui;
    opens com.copybot to info.picocli, javafx.base;

    uses IPlugin;
    uses ILanguagePack;

    provides IPlugin with CBEmbeddedPlugin;
}
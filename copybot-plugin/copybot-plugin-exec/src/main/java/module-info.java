import com.copybot.plugin.api.definition.IPlugin;
import com.copybot.plugin.exec.ExecPlugin;

module com.copybot.plugin.exec {
    requires com.copybot.engine;

    // the API shared with the plugins that require this one, the only package exported to all: see its package-info
    exports com.copybot.plugin.exec.process;
    // the plugin itself, for the engine only (found through the provides below)
    exports com.copybot.plugin.exec to com.copybot.engine;

    opens com.copybot.plugin.exec.i18n;
    // the actions are created by the engine and their configuration records read by Gson: opened to them alone
    opens com.copybot.plugin.exec.actions to com.copybot.engine, com.google.gson;

    provides IPlugin with ExecPlugin;
}

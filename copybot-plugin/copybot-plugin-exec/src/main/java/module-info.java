import com.copybot.plugin.api.definition.IPlugin;
import com.copybot.plugin.exec.ExecPlugin;

module com.copybot.plugin.exec {
    requires com.copybot.engine;
    requires java.net.http;

    exports com.copybot.plugin.exec;
    exports com.copybot.plugin.exec.actions;

    opens com.copybot.plugin.exec.i18n;
    // the configuration records are read by Gson
    opens com.copybot.plugin.exec.actions;

    provides IPlugin with ExecPlugin;
}

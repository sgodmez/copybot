import com.copybot.plugin.api.ICBPluginModule;

module com.copybot.engine {
    requires com.copybot.plugin.api;

    exports com.copybot.engine  to com.copybot.ui;

    uses ICBPluginModule;
}
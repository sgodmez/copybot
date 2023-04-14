module com.copybot.engine {
    requires com.copybot.plugin;

    exports com.copybot.engine  to com.copybot.ui;

    uses com.copybot.plugin.MyService;
    //provides com.copybot.plugin.MyService with DummyService;
}
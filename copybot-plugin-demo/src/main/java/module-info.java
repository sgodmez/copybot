import com.copybot.plugin.demo.DummyService2;

module com.copybot.plugin.demo {
    requires com.copybot.plugin;
    requires metadata.extractor;

    uses com.copybot.plugin.MyService;
    provides com.copybot.plugin.MyService with DummyService2;
}
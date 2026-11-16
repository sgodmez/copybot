import com.copybot.plugin.api.definition.IPlugin;
import com.copybot.plugin.notifywebhook.NotifyWebhookPlugin;

module com.copybot.plugin.notifywebhook {
    requires com.copybot.engine;
    requires java.net.http;

    exports com.copybot.plugin.notifywebhook;
    exports com.copybot.plugin.notifywebhook.actions;

    opens com.copybot.plugin.notifywebhook.i18n;
    // the configuration record is read by Gson
    opens com.copybot.plugin.notifywebhook.actions;

    provides IPlugin with NotifyWebhookPlugin;
}

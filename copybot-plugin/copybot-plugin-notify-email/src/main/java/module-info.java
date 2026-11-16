import com.copybot.plugin.api.definition.IPlugin;
import com.copybot.plugin.notifyemail.NotifyEmailPlugin;

module com.copybot.plugin.notifyemail {
    requires com.copybot.engine;
    // the API; its implementation (org.eclipse.angus.mail, with Angus Activation) is a service provider of the
    // plugin layer, in target/lib
    requires jakarta.mail;

    exports com.copybot.plugin.notifyemail;
    exports com.copybot.plugin.notifyemail.actions;

    opens com.copybot.plugin.notifyemail.i18n;
    // the configuration record is read by Gson
    opens com.copybot.plugin.notifyemail.actions;

    provides IPlugin with NotifyEmailPlugin;
}

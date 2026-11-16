import com.copybot.plugin.api.definition.IPlugin;
import com.copybot.plugin.archive.ArchivePlugin;

module com.copybot.plugin.archive {
    requires com.copybot.engine;

    exports com.copybot.plugin.archive;
    exports com.copybot.plugin.archive.actions;

    opens com.copybot.plugin.archive.i18n;
    // the configuration record is read by Gson
    opens com.copybot.plugin.archive.actions;

    provides IPlugin with ArchivePlugin;
}

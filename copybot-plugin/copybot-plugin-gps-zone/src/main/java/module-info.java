import com.copybot.plugin.api.definition.IPlugin;
import com.copybot.plugin.gpszone.GpsZonePlugin;

module com.copybot.plugin.gpszone {
    requires com.copybot.engine;
    // automatic module (Automatic-Module-Name), with its libraries in target/lib
    requires net.iakovlev.timeshape;

    exports com.copybot.plugin.gpszone;
    exports com.copybot.plugin.gpszone.actions;

    opens com.copybot.plugin.gpszone.i18n;

    provides IPlugin with GpsZonePlugin;
}

import com.copybot.plugin.definition.IPlugin;
import com.copybot.plugin.embedded.CBEmbeddedPlugin;

module com.copybot.plugin.embedded {
    requires com.copybot.plugin.api;

    exports com.copybot.plugin.embedded;
    exports com.copybot.plugin.embedded.actions;

    provides IPlugin with CBEmbeddedPlugin;
}
import com.copybot.plugin.definition.api.IPlugin;
import com.copybot.plugin.embedded.CBEmbeddedPlugin;

module com.copybot.plugin.embedded {
    requires com.copybot.plugin.api;

    exports com.copybot.plugin.embedded;

    provides IPlugin with CBEmbeddedPlugin;
}
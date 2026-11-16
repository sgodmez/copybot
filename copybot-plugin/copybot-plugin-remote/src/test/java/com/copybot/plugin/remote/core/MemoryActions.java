package com.copybot.plugin.remote.core;

import com.copybot.plugin.api.config.DefaultValue;
import com.copybot.plugin.api.config.Required;
import com.copybot.plugin.remote.RemotePlugin;
import com.copybot.resources.CombinedResourceBundle;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/** The protocol "mem" of the tests: the generic read and write on an {@link InMemoryServer}. */
final class MemoryActions {

    private MemoryActions() {
    }

    record ReadConfig(@Required String host, @Required String folder, @DefaultValue("true") Boolean recursive,
                      List<String> include, List<String> exclude, Long minSize, Integer maxConnections)
            implements RemoteReadSettings {
    }

    record WriteConfig(@Required String host, @Required String outPattern, String onMissingKey,
                       RemoteOnConflict onConflict, Boolean deleteSource, Integer maxConnections)
            implements RemoteWriteSettings {
    }

    static class Read extends RemoteRead<ReadConfig> {
        final InMemoryServer server;

        Read(InMemoryServer server) {
            this.server = server;
        }

        @Override
        protected Class<ReadConfig> getConfigClass() {
            return ReadConfig.class;
        }

        @Override
        protected String protocol() {
            return "mem";
        }

        @Override
        protected String host(ReadConfig config) {
            return config.host();
        }

        @Override
        protected RemoteFs connect(ReadConfig config) throws IOException {
            return server.connect();
        }
    }

    static class Write extends RemoteWrite<WriteConfig> {
        final InMemoryServer server;

        Write(InMemoryServer server) {
            this.server = server;
        }

        @Override
        protected Class<WriteConfig> getConfigClass() {
            return WriteConfig.class;
        }

        @Override
        protected String protocol() {
            return "mem";
        }

        @Override
        protected String host(WriteConfig config) {
            return config.host();
        }

        @Override
        protected RemoteFs connect(WriteConfig config) throws IOException {
            return server.connect();
        }
    }

    static Read read(InMemoryServer server, String json) {
        Read action = new Read(server);
        action.setPlugin(plugin());
        action.loadConfig(JsonParser.parseString(json));
        return action;
    }

    static Write write(InMemoryServer server, String json) {
        Write action = new Write(server);
        action.setPlugin(plugin());
        action.loadConfig(JsonParser.parseString(json));
        return action;
    }

    static RemotePlugin plugin() {
        RemotePlugin plugin = new RemotePlugin();
        CombinedResourceBundle bundle = new CombinedResourceBundle(RemotePlugin.class.getModule(), null);
        plugin.getI18nBundleNames().forEach(bundle::addBundle);
        bundle.load(Locale.ROOT); // the English texts, whatever the language of this computer
        plugin.setResourceBundle(bundle);
        return plugin;
    }
}

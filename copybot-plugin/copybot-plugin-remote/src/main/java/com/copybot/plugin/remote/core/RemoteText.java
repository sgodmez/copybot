package com.copybot.plugin.remote.core;

import com.copybot.plugin.api.definition.IPlugin;
import com.copybot.resources.CombinedResourceBundle;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/** The texts and names shared by the remote actions (and their protocols). */
public final class RemoteText {

    private RemoteText() {
    }

    /** A localised message of the plugin; its key and arguments when the action has no plugin (built by hand). */
    public static String message(IPlugin plugin, String key, Object... args) {
        CombinedResourceBundle bundle = plugin == null ? null : plugin.getResourceBundle();
        if (bundle == null) {
            return key + Arrays.toString(args);
        }
        String fullKey = "plugin.remote." + key;
        return args.length == 0 ? bundle.getString(fullKey) : bundle.getString(fullKey, args);
    }

    /** "sftp://host/in/a.jpg": where a remote path is, for the user; never a user name or a password. */
    public static String display(String protocol, String host, String path) {
        return protocol + "://" + host + (path.startsWith("/") ? "" : "/") + path;
    }

    /** The resource of the server, bounding the steps' concurrency (spec remote §3): "net:sftp:host". */
    public static Set<String> resources(String protocol, String host) {
        return Set.of("net:" + protocol + ":" + (host == null ? "" : host.toLowerCase(Locale.ROOT)));
    }

    /** The message of a failure, or its class when it has none. */
    public static String detail(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    /** The connections of the step: the configured number, or the default; at least 1. */
    static int maxConnections(IPlugin plugin, String code, Integer configured) {
        int max = configured == null ? RemotePool.DEFAULT_MAX_CONNECTIONS : configured;
        if (max < 1) {
            throw new IllegalArgumentException(message(plugin, "error.max-connections", code));
        }
        return max;
    }
}

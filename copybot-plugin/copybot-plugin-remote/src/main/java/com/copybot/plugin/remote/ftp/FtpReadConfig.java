package com.copybot.plugin.remote.ftp;

import com.copybot.plugin.api.config.AllowedValues;
import com.copybot.plugin.api.config.DefaultValue;
import com.copybot.plugin.api.config.Required;
import com.copybot.plugin.api.config.Secret;
import com.copybot.plugin.remote.core.RemoteReadSettings;

import java.util.List;

/**
 * The "actionConfig" of ftp.read (spec remote §3, §4): the connection ({@link FtpSettings}) and the folder listed
 * ({@link RemoteReadSettings}).
 */
public record FtpReadConfig(
        @Required
        String host,

        Integer port,

        @Required
        String user,

        @Secret
        String password,

        @AllowedValues({"none", "explicit", "implicit"}) @DefaultValue("explicit")
        String security,

        @DefaultValue("true")
        Boolean passive,

        @Required
        String folder,

        @DefaultValue("true")
        Boolean recursive,

        List<String> include,

        List<String> exclude,

        Long minSize,

        @DefaultValue("4")
        Integer maxConnections
) implements FtpSettings, RemoteReadSettings {

    /** Neither the user nor the password. */
    @Override
    public String toString() {
        return "FtpReadConfig[host=" + host + ", port=" + port + ", security=" + security + ", passive=" + passive
                + ", folder=" + folder + ", recursive=" + recursive + ", include=" + include + ", exclude=" + exclude
                + ", minSize=" + minSize + ", maxConnections=" + maxConnections + "]";
    }
}

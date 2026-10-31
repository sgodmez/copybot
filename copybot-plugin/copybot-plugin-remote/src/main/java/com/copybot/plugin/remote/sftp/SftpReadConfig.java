package com.copybot.plugin.remote.sftp;

import com.copybot.plugin.api.config.DefaultValue;
import com.copybot.plugin.api.config.FilePath;
import com.copybot.plugin.api.config.Required;
import com.copybot.plugin.api.config.Secret;
import com.copybot.plugin.remote.core.RemoteReadSettings;

import java.util.List;

/**
 * The "actionConfig" of sftp.read (spec remote §3, §4): the connection ({@link SftpSettings}) and the folder listed
 * ({@link RemoteReadSettings}).
 */
public record SftpReadConfig(
        @Required
        String host,

        @DefaultValue("22")
        Integer port,

        @Required
        String user,

        @Secret
        String password,

        @FilePath
        String privateKey,

        @Secret
        String passphrase,

        @Required
        String folder,

        @DefaultValue("true")
        Boolean recursive,

        List<String> include,

        List<String> exclude,

        Long minSize,

        @DefaultValue("4")
        Integer maxConnections
) implements SftpSettings, RemoteReadSettings {

    /** Neither the user nor the secrets. */
    @Override
    public String toString() {
        return "SftpReadConfig[host=" + host + ", port=" + port + ", folder=" + folder + ", recursive=" + recursive
                + ", include=" + include + ", exclude=" + exclude + ", minSize=" + minSize
                + ", maxConnections=" + maxConnections + "]";
    }
}

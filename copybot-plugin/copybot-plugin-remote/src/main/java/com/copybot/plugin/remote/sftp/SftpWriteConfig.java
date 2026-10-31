package com.copybot.plugin.remote.sftp;

import com.copybot.plugin.api.config.AllowedValues;
import com.copybot.plugin.api.config.DefaultValue;
import com.copybot.plugin.api.config.FilePath;
import com.copybot.plugin.api.config.PatternField;
import com.copybot.plugin.api.config.Required;
import com.copybot.plugin.api.config.Secret;
import com.copybot.plugin.remote.core.RemoteOnConflict;
import com.copybot.plugin.remote.core.RemoteWriteSettings;

/**
 * The "actionConfig" of sftp.write (spec remote §3, §5): the connection ({@link SftpSettings}) and where the items
 * go ({@link RemoteWriteSettings}).
 */
public record SftpWriteConfig(
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

        @Required @PatternField
        String outPattern,

        @AllowedValues({"error", "skip", "literal"}) @DefaultValue("error")
        String onMissingKey,

        RemoteOnConflict onConflict,

        @DefaultValue("false")
        Boolean deleteSource,

        @DefaultValue("4")
        Integer maxConnections
) implements SftpSettings, RemoteWriteSettings {

    /** Neither the user nor the secrets. */
    @Override
    public String toString() {
        return "SftpWriteConfig[host=" + host + ", port=" + port + ", outPattern=" + outPattern + ", onMissingKey="
                + onMissingKey + ", onConflict=" + onConflict + ", deleteSource=" + deleteSource
                + ", maxConnections=" + maxConnections + "]";
    }
}

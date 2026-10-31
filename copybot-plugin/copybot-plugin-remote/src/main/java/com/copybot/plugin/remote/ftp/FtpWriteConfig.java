package com.copybot.plugin.remote.ftp;

import com.copybot.plugin.api.config.AllowedValues;
import com.copybot.plugin.api.config.DefaultValue;
import com.copybot.plugin.api.config.PatternField;
import com.copybot.plugin.api.config.Required;
import com.copybot.plugin.api.config.Secret;
import com.copybot.plugin.remote.core.RemoteOnConflict;
import com.copybot.plugin.remote.core.RemoteWriteSettings;

/**
 * The "actionConfig" of ftp.write (spec remote §3, §5): the connection ({@link FtpSettings}) and where the items go
 * ({@link RemoteWriteSettings}).
 */
public record FtpWriteConfig(
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

        @Required @PatternField
        String outPattern,

        @AllowedValues({"error", "skip", "literal"}) @DefaultValue("error")
        String onMissingKey,

        RemoteOnConflict onConflict,

        @DefaultValue("false")
        Boolean deleteSource,

        @DefaultValue("4")
        Integer maxConnections
) implements FtpSettings, RemoteWriteSettings {

    /** Neither the user nor the password. */
    @Override
    public String toString() {
        return "FtpWriteConfig[host=" + host + ", port=" + port + ", security=" + security + ", passive=" + passive
                + ", outPattern=" + outPattern + ", onMissingKey=" + onMissingKey + ", onConflict=" + onConflict
                + ", deleteSource=" + deleteSource + ", maxConnections=" + maxConnections + "]";
    }
}

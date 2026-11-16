package com.copybot.plugin.remote.s3;

import com.copybot.plugin.api.config.AllowedValues;
import com.copybot.plugin.api.config.DefaultValue;
import com.copybot.plugin.api.config.PatternField;
import com.copybot.plugin.api.config.Required;
import com.copybot.plugin.api.config.Secret;
import com.copybot.plugin.remote.core.RemoteOnConflict;
import com.copybot.plugin.remote.core.RemoteWriteSettings;

/**
 * The "actionConfig" of s3.write (spec remote §3, §5): the bucket ({@link S3Settings}) and where the items go
 * ({@link RemoteWriteSettings}).
 */
public record S3WriteConfig(
        String endpoint,

        @Required
        String region,

        @Required
        String bucket,

        String accessKey,

        @Secret
        String secretKey,

        @DefaultValue("false")
        Boolean pathStyle,

        @Required @PatternField
        String outPattern,

        @AllowedValues({"error", "skip", "literal"}) @DefaultValue("error")
        String onMissingKey,

        RemoteOnConflict onConflict,

        @DefaultValue("false")
        Boolean deleteSource,

        @DefaultValue("4")
        Integer maxConnections
) implements S3Settings, RemoteWriteSettings {

    /** Neither key. */
    @Override
    public String toString() {
        return "S3WriteConfig[endpoint=" + endpoint + ", region=" + region + ", bucket=" + bucket + ", pathStyle="
                + pathStyle + ", outPattern=" + outPattern + ", onMissingKey=" + onMissingKey + ", onConflict="
                + onConflict + ", deleteSource=" + deleteSource + ", maxConnections=" + maxConnections + "]";
    }
}

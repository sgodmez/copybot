package com.copybot.plugin.remote.s3;

import com.copybot.plugin.api.config.DefaultValue;
import com.copybot.plugin.api.config.Required;
import com.copybot.plugin.api.config.Secret;
import com.copybot.plugin.remote.core.RemoteReadSettings;

import java.util.List;

/**
 * The "actionConfig" of s3.read (spec remote §3, §4): the bucket ({@link S3Settings}) and the prefix listed
 * ({@link RemoteReadSettings}).
 */
public record S3ReadConfig(
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

        @Required
        String folder,

        @DefaultValue("true")
        Boolean recursive,

        List<String> include,

        List<String> exclude,

        Long minSize,

        @DefaultValue("4")
        Integer maxConnections
) implements S3Settings, RemoteReadSettings {

    /** Neither key. */
    @Override
    public String toString() {
        return "S3ReadConfig[endpoint=" + endpoint + ", region=" + region + ", bucket=" + bucket + ", pathStyle="
                + pathStyle + ", folder=" + folder + ", recursive=" + recursive + ", include=" + include
                + ", exclude=" + exclude + ", minSize=" + minSize + ", maxConnections=" + maxConnections + "]";
    }
}

package com.copybot.plugin.remote.s3;

import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.remote.core.RemoteFs;
import com.copybot.plugin.remote.core.RemoteRead;
import com.copybot.plugin.remote.core.RemoteText;
import com.google.gson.JsonElement;

import java.util.List;
import java.util.Set;

/**
 * s3.read (spec remote §4, §7): the objects under a prefix of an S3 bucket ("s3://bucket/key" as their sources),
 * dated by their LastModified. A recursive listing is one ListObjectsV2 of the prefix, in the order of the keys; S3 has
 * no folders to signal.
 */
public class S3Read extends RemoteRead<S3ReadConfig> {

    /** The sizes and delays of the requests; other in the tests only. */
    private volatile S3Fs.Tuning tuning = S3Fs.Tuning.DEFAULT;

    @Override
    protected Class<S3ReadConfig> getConfigClass() {
        return S3ReadConfig.class;
    }

    /**
     * @throws IllegalArgumentException also: no region or bucket, an endpoint not valid, one key without the other
     */
    @Override
    public void loadConfig(JsonElement json) {
        super.loadConfig(json);
        S3Connections.check(getPlugin(), code(), getConfig());
    }

    /** An http:// endpoint: the files cross the network in clear. */
    @Override
    public List<String> configWarnings() {
        return getConfig() == null ? List.of() : S3Connections.warnings(getPlugin(), code(), getConfig());
    }

    @Override
    protected String protocol() {
        return "s3";
    }

    /** The bucket, as the sources name it: "s3://bucket/key". */
    @Override
    protected String host(S3ReadConfig config) {
        return config.bucket().strip();
    }

    /** The endpoint's server ("net:s3:s3.eu-west-3.amazonaws.com"), whatever the bucket. */
    @Override
    public Set<String> requiredResources(WorkItem item) {
        return RemoteText.resources("s3", S3Connections.endpoint(getConfig()).getHost());
    }

    @Override
    public boolean signalsFolders() {
        return false;
    }

    @Override
    protected RemoteFs connect(S3ReadConfig config) {
        return S3Fs.connect(config, getPlugin(), tuning);
    }

    /** Visible for tests. */
    void tuning(S3Fs.Tuning tuning) {
        this.tuning = tuning;
    }

    /** Visible for tests. */
    S3ReadConfig config() {
        return getConfig();
    }
}

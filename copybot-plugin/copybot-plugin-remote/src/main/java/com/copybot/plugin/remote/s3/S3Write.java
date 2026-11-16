package com.copybot.plugin.remote.s3;

import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.remote.core.RemoteFs;
import com.copybot.plugin.remote.core.RemoteText;
import com.copybot.plugin.remote.core.RemoteWrite;
import com.google.gson.JsonElement;

import java.util.List;
import java.util.Set;

/**
 * s3.write (spec remote §5, §7): each item to an object of an S3 bucket, put under its final key (S3 makes it appear
 * whole or not at all), multipart above 16 MiB, its size checked and the item's date kept as {@code x-amz-meta-mtime}
 * (so that "sizeAndDate" finds it again).
 */
public class S3Write extends RemoteWrite<S3WriteConfig> {

    /** The sizes and delays of the requests; other in the tests only. */
    private volatile S3Fs.Tuning tuning = S3Fs.Tuning.DEFAULT;

    @Override
    protected Class<S3WriteConfig> getConfigClass() {
        return S3WriteConfig.class;
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

    /** The bucket, as the targets name it: "s3://bucket/key". */
    @Override
    protected String host(S3WriteConfig config) {
        return config.bucket().strip();
    }

    /** The endpoint's server ("net:s3:s3.eu-west-3.amazonaws.com"), whatever the bucket. */
    @Override
    public Set<String> requiredResources(WorkItem item) {
        return RemoteText.resources("s3", S3Connections.endpoint(getConfig()).getHost());
    }

    @Override
    protected RemoteFs connect(S3WriteConfig config) {
        return S3Fs.connect(config, getPlugin(), tuning);
    }

    /** Visible for tests. */
    void tuning(S3Fs.Tuning tuning) {
        this.tuning = tuning;
    }

    /** Visible for tests. */
    S3WriteConfig config() {
        return getConfig();
    }
}

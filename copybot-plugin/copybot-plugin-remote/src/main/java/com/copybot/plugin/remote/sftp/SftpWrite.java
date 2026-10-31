package com.copybot.plugin.remote.sftp;

import com.copybot.plugin.remote.core.RemoteFs;
import com.copybot.plugin.remote.core.RemoteWrite;
import com.google.gson.JsonElement;

import java.io.IOException;

/** sftp.write (spec remote §5): each item to a file of an SFTP server. */
public class SftpWrite extends RemoteWrite<SftpWriteConfig> {

    /** The answers about the server's key, for the run of this step. */
    private volatile HostTrust trust;

    @Override
    protected Class<SftpWriteConfig> getConfigClass() {
        return SftpWriteConfig.class;
    }

    /** @throws IllegalArgumentException also: no host or user, a port out of 1..65535 */
    @Override
    public void loadConfig(JsonElement json) {
        super.loadConfig(json);
        SftpConnections.check(getPlugin(), code(), getConfig());
        trust = new HostTrust(getPlugin());
    }

    @Override
    protected String protocol() {
        return "sftp";
    }

    @Override
    protected String host(SftpWriteConfig config) {
        return config.host().strip();
    }

    @Override
    protected RemoteFs connect(SftpWriteConfig config) throws IOException {
        return SftpFs.connect(config, trust, getPlugin());
    }

    /** Visible for tests. */
    SftpWriteConfig config() {
        return getConfig();
    }
}

package com.copybot.plugin.remote.sftp;

import com.copybot.plugin.remote.core.RemoteFs;
import com.copybot.plugin.remote.core.RemoteRead;
import com.google.gson.JsonElement;

import java.io.IOException;

/** sftp.read (spec remote §4): the files of a folder of an SFTP server. */
public class SftpRead extends RemoteRead<SftpReadConfig> {

    /** The answers about the server's key, for the run of this step. */
    private volatile HostTrust trust;

    @Override
    protected Class<SftpReadConfig> getConfigClass() {
        return SftpReadConfig.class;
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
    protected String host(SftpReadConfig config) {
        return config.host().strip();
    }

    @Override
    protected RemoteFs connect(SftpReadConfig config) throws IOException {
        return SftpFs.connect(config, trust, getPlugin());
    }

    /** Visible for tests. */
    SftpReadConfig config() {
        return getConfig();
    }
}

package com.copybot.plugin.remote.ftp;

import com.copybot.plugin.remote.core.RemoteFs;
import com.copybot.plugin.remote.core.RemoteWrite;
import com.google.gson.JsonElement;

import javax.net.ssl.X509ExtendedTrustManager;
import java.io.IOException;
import java.util.List;

/** ftp.write (spec remote §5): each item to a file of an FTP or FTPS server. */
public class FtpWrite extends RemoteWrite<FtpWriteConfig> {

    /** The certificates this computer trusts for FTPS; null: the system's (only tests set others). */
    private volatile X509ExtendedTrustManager trust;
    /** The answers about the certificates this computer does not trust, for the run of this step. */
    private volatile CertificateTrust certificates;

    @Override
    protected Class<FtpWriteConfig> getConfigClass() {
        return FtpWriteConfig.class;
    }

    /** @throws IllegalArgumentException also: no host or user, a port out of 1..65535, an unknown security */
    @Override
    public void loadConfig(JsonElement json) {
        super.loadConfig(json);
        FtpConnections.check(getPlugin(), code(), getConfig());
        certificates = new CertificateTrust(getPlugin());
    }

    /** A password sent in clear (security none). */
    @Override
    public List<String> configWarnings() {
        return getConfig() == null ? List.of() : FtpConnections.warnings(getPlugin(), code(), getConfig());
    }

    @Override
    protected String protocol() {
        return "ftp";
    }

    @Override
    protected String host(FtpWriteConfig config) {
        return config.host().strip();
    }

    @Override
    protected RemoteFs connect(FtpWriteConfig config) throws IOException {
        return FtpFs.connect(config, FtpConnections.security(getPlugin(), code(), config), trust, certificates,
                getPlugin());
    }

    /** Visible for tests: the certificates this computer trusts, instead of the system's. */
    void trust(X509ExtendedTrustManager trustManager) {
        trust = trustManager;
    }

    /** Visible for tests. */
    FtpWriteConfig config() {
        return getConfig();
    }
}

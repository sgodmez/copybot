package com.copybot.plugin.remote.ftp;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;
import java.io.IOException;
import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

/**
 * The certificates of one FTPS connection: trusted by this computer and naming the server (the system's check, the
 * name checked on the control and data connections alike), else trusted when the user trusted their public key for
 * this server ({@link CertificateTrust}), whatever their dates and names.
 * <p>
 * The handshake never waits for the user: a certificate not recorded fails it, what it showed kept here, so that the
 * connection asks once closed and connects again. Only the control connection asks; once it is up
 * ({@link #controlConnected()}), a data connection with a certificate not recorded is refused.
 */
final class PinningTrustManager extends X509ExtendedTrustManager {

    private final X509ExtendedTrustManager system;
    private final CertificateTrust trust;
    private final String key;
    private final String host;
    private final String server;

    /** The control connection is up: the handshakes that follow are data connections'. */
    private volatile boolean data;
    /** The certificate the control connection stopped on, and why; null when it did not. */
    private volatile CertificateTrust.Offered offered;
    private volatile CertificateTrust.Verdict verdict;
    /** The trust file could not be read. */
    private volatile IOException failure;

    /**
     * @param key    the server in the trust file ({@link FtpsTrustFile#key})
     * @param host   the host the user configured, which the certificate should name
     * @param server the server as the messages name it
     */
    PinningTrustManager(X509ExtendedTrustManager system, CertificateTrust trust, String key, String host,
                        String server) {
        this.system = system;
        this.trust = trust;
        this.key = key;
        this.host = host;
        this.server = server;
    }

    void controlConnected() {
        data = true;
    }

    CertificateTrust.Offered offered() {
        return offered;
    }

    CertificateTrust.Verdict verdict() {
        return verdict;
    }

    IOException failure() {
        return failure;
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        try {
            system.checkServerTrusted(chain, authType, socket);
        } catch (CertificateException e) {
            recorded(chain, e);
        }
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        try {
            system.checkServerTrusted(chain, authType, engine);
        } catch (CertificateException e) {
            recorded(chain, e);
        }
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        try {
            system.checkServerTrusted(chain, authType);
        } catch (CertificateException e) {
            recorded(chain, e);
        }
    }

    /** Accepts a certificate whose public key is recorded for the server; else fails, what it showed kept. */
    private void recorded(X509Certificate[] chain, CertificateException refusal) throws CertificateException {
        if (chain == null || chain.length == 0) {
            throw refusal;
        }
        CertificateTrust.Offered shown = CertificateTrust.offered(chain[0], host);
        CertificateTrust.Verdict found;
        try {
            found = trust.verdict(key, shown.pin());
        } catch (IOException e) {
            failure = e;
            throw new CertificateException(trust.fileFailure(e), e);
        }
        if (found == CertificateTrust.Verdict.TRUSTED) {
            return; // trusted by the user
        }
        if (data) {
            throw new CertificateException(trust.dataRefused(server, shown.pin()), refusal);
        }
        offered = shown;
        verdict = found;
        throw refusal;
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        system.checkClientTrusted(chain, authType, socket);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        system.checkClientTrusted(chain, authType, engine);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        system.checkClientTrusted(chain, authType);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return system.getAcceptedIssuers();
    }
}

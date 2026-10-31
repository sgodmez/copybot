package com.copybot.plugin.remote.ftp;

import com.copybot.plugin.api.definition.IPlugin;
import com.copybot.plugin.remote.core.RemoteText;

import java.util.List;

/** The checks of the connection fields shared by ftp.read and ftp.write. */
final class FtpConnections {

    /** How the connection is protected. */
    enum Security {
        /** Plain FTP. */
        NONE("none"),
        /** FTPS, AUTH TLS on the usual port. */
        EXPLICIT("explicit"),
        /** FTPS, TLS from the first byte (port 990). */
        IMPLICIT("implicit");

        final String jsonName;

        Security(String jsonName) {
            this.jsonName = jsonName;
        }
    }

    private FtpConnections() {
    }

    /**
     * @throws IllegalArgumentException no host or user, a port out of 1..65535, a security other than none, explicit
     *                                  or implicit
     */
    static void check(IPlugin plugin, String code, FtpSettings settings) {
        if (settings.host() == null || settings.host().isBlank()) {
            throw new IllegalArgumentException(RemoteText.message(plugin, "ftp.error.no-host", code));
        }
        if (settings.user() == null || settings.user().isBlank()) {
            throw new IllegalArgumentException(RemoteText.message(plugin, "ftp.error.no-user", code));
        }
        if (settings.port() != null && (settings.port() < 1 || settings.port() > 65535)) {
            throw new IllegalArgumentException(RemoteText.message(plugin, "ftp.error.port", code));
        }
        security(plugin, code, settings);
    }

    /** The security of the configuration; explicit when absent. */
    static Security security(IPlugin plugin, String code, FtpSettings settings) {
        if (settings.security() == null) {
            return Security.EXPLICIT;
        }
        for (Security security : Security.values()) {
            if (security.jsonName.equals(settings.security())) {
                return security;
            }
        }
        throw new IllegalArgumentException(RemoteText.message(plugin, "ftp.error.security", code,
                settings.security()));
    }

    /** The port of the configuration; else 990 with implicit TLS, 21 otherwise. */
    static int port(FtpSettings settings, Security security) {
        if (settings.port() != null) {
            return settings.port();
        }
        return security == Security.IMPLICIT ? 990 : 21;
    }

    /** A password sent in clear (security none). */
    static List<String> warnings(IPlugin plugin, String code, FtpSettings settings) {
        if (settings.security() != null && settings.security().equals(Security.NONE.jsonName)
                && settings.password() != null && !settings.password().isEmpty()) {
            return List.of(RemoteText.message(plugin, "ftp.warning.clear-text", code));
        }
        return List.of();
    }
}

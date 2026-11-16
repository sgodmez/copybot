package com.copybot.plugin.remote.sftp;

import com.copybot.plugin.api.definition.IPlugin;
import com.copybot.plugin.remote.core.RemoteText;

/** The checks of the connection fields shared by sftp.read and sftp.write. */
final class SftpConnections {

    private SftpConnections() {
    }

    /** @throws IllegalArgumentException no host or user, a port out of 1..65535 */
    static void check(IPlugin plugin, String code, SftpSettings settings) {
        if (settings.host() == null || settings.host().isBlank()) {
            throw new IllegalArgumentException(RemoteText.message(plugin, "sftp.error.no-host", code));
        }
        if (settings.user() == null || settings.user().isBlank()) {
            throw new IllegalArgumentException(RemoteText.message(plugin, "sftp.error.no-user", code));
        }
        if (settings.port() != null && (settings.port() < 1 || settings.port() > 65535)) {
            throw new IllegalArgumentException(RemoteText.message(plugin, "sftp.error.port", code));
        }
    }
}

package com.copybot.plugin.remote.sftp;

/** The connection fields of sftp.read and sftp.write (spec remote §3). */
public interface SftpSettings {

    /** The server, a name or an address; required. */
    String host();

    /** Its port; absent: 22. */
    Integer port();

    /** The login on the server; required. Never put in a message or a log. */
    String user();

    /** Its password, usually ${secret:name}; absent: none tried. Never put in a message or a log. */
    String password();

    /** A private key file (OpenSSH, PEM or PuTTY format); absent: none tried. */
    String privateKey();

    /** The passphrase of the private key, usually ${secret:name}. Never put in a message or a log. */
    String passphrase();
}

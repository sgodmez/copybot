package com.copybot.plugin.remote.ftp;

/** The connection fields of ftp.read and ftp.write (spec remote §3). */
public interface FtpSettings {

    /** The server, a name or an address; required. */
    String host();

    /** Its port; absent: 21, or 990 with implicit TLS. */
    Integer port();

    /** The login on the server; required. Never put in a message or a log. */
    String user();

    /** Its password, usually ${secret:name}; absent: an empty one. Never put in a message or a log. */
    String password();

    /**
     * "none" (plain FTP: the password and the files are sent in clear), "explicit" (the default: FTPS, the
     * connection made secure by AUTH TLS) or "implicit" (FTPS from the start, port 990).
     */
    String security();

    /** Passive mode (the default: the client opens the data connections), else active mode. */
    Boolean passive();
}

package com.copybot.plugin.remote.sftp;

import com.copybot.plugin.api.definition.IPlugin;
import com.copybot.plugin.remote.core.RemoteEntry;
import com.copybot.plugin.remote.core.RemoteFs;
import com.copybot.plugin.remote.core.RemotePaths;
import com.copybot.plugin.remote.core.RemoteText;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.UIKeyboardInteractive;
import com.jcraft.jsch.UserInfo;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * An SFTP connection (spec remote §3, §6), with JSch: one SSH session and its SFTP channel.
 * <ul>
 *     <li>The server's key is checked against the plugin's {@code known_hosts} ({@link HostTrust}) before any
 *     authentication: a server not recorded stops the connection, the user is asked outside it, then it connects
 *     again; a changed or revoked key is refused.</li>
 *     <li>Modern algorithms only: the server's key and the user's key are Ed25519, ECDSA or RSA signed with SHA-2
 *     ({@code ssh-rsa} with SHA-1 is refused); key exchanges, ciphers and MACs listed explicitly, none with SHA-1 or
 *     CBC; authentication by key, then keyboard-interactive or password.</li>
 *     <li>Timeouts: 20 s to connect. A server silent for 20 s is sent a keep-alive, and the connection is dropped after
 *     {@value #SERVER_ALIVE_COUNT_MAX} keep-alives left unanswered: a dead server is noticed within about 80 s
 *     ({@code (1 + count) x 20 s}); a slow one that still answers the keep-alives is waited for.</li>
 *     <li>Paths are given to JSch quoted (it reads '*' and '?' as wildcards), relative ones under the folder the
 *     server starts in.</li>
 * </ul>
 */
final class SftpFs implements RemoteFs {

    static final int CONNECT_TIMEOUT_MILLIS = 20_000;
    /** The silence after which a keep-alive is sent (JSch also makes it the socket's read timeout). */
    static final int SERVER_ALIVE_INTERVAL_MILLIS = 20_000;
    /** The keep-alives left unanswered before the connection is dropped. */
    static final int SERVER_ALIVE_COUNT_MAX = 3;

    /** The keys accepted for the server and for the user: no certificate, no SHA-1 ("ssh-rsa"). */
    static final String KEY_ALGORITHMS =
            "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,rsa-sha2-512,rsa-sha2-256";
    /** Key exchanges: no SHA-1 group; those JSch cannot run on this JDK (ML-KEM...) are left out by JSch itself. */
    static final String KEX_ALGORITHMS = "mlkem768x25519-sha256,curve25519-sha256,curve25519-sha256@libssh.org,"
            + "ecdh-sha2-nistp256,ecdh-sha2-nistp384,ecdh-sha2-nistp521,diffie-hellman-group-exchange-sha256,"
            + "diffie-hellman-group16-sha512,diffie-hellman-group18-sha512,diffie-hellman-group14-sha256";
    /** Ciphers: AES GCM or CTR, no CBC. */
    static final String CIPHERS = "aes128-gcm@openssh.com,aes256-gcm@openssh.com,aes128-ctr,aes192-ctr,aes256-ctr";
    /** MACs (unused with GCM): SHA-2 only. */
    static final String MACS = "hmac-sha2-256-etm@openssh.com,hmac-sha2-512-etm@openssh.com,hmac-sha2-256,hmac-sha2-512";

    private static final String POSIX_RENAME = "posix-rename@openssh.com";

    private final IPlugin plugin;
    private final String host;
    private final Session session;
    private final String home;
    private ChannelSftp channel;
    /** The channel could not be opened again after a failed call: every call fails, the pool closes it. */
    private boolean broken;

    private SftpFs(IPlugin plugin, String host, Session session, ChannelSftp channel) throws SftpException {
        this.plugin = plugin;
        this.host = host;
        this.session = session;
        this.channel = channel;
        this.home = channel.getHome();
    }

    /**
     * Connects and authenticates, the server's key checked first (asking the user when the server is not recorded).
     *
     * @throws IOException the server is not trusted or its key changed, the connection or the authentication failed;
     *                     never with a secret in its message
     */
    static SftpFs connect(SftpSettings settings, HostTrust trust, IPlugin plugin) throws IOException {
        if (Thread.currentThread().isInterrupted()) { // a cancelled run: JSch would clear the interrupt
            throw new InterruptedIOException("interrupted");
        }
        String host = settings.host().strip();
        int port = settings.port() == null ? 22 : settings.port();
        String name = KnownHosts.name(host, port);
        String server = port == 22 ? host : host + ":" + port;
        for (int attempt = 1; ; attempt++) {
            HostKeys keys = new HostKeys(trust, name);
            Session session;
            try {
                session = open(settings, host, port, keys, plugin);
            } catch (JSchException e) {
                if (keys.failure != null) { // known_hosts not read
                    throw new IOException(message(plugin, "sftp.error.known-hosts", trust.knownHosts().file(),
                            RemoteText.detail(keys.failure)), keys.failure);
                }
                if (keys.verdict == HostTrust.Verdict.REVOKED) {
                    throw trust.revoked(server, keys.offered);
                }
                if (keys.verdict == HostTrust.Verdict.CHANGED) {
                    throw trust.changed(name, server, keys.offered);
                }
                if (keys.verdict == HostTrust.Verdict.UNKNOWN) {
                    if (attempt == 1) {
                        trust.approve(name, server, keys.offered); // throws unless trusted now
                        continue;
                    }
                    // trusted, then another key offered at once: not asked twice
                    throw new IOException(message(plugin, "sftp.error.not-trusted", server,
                            KnownHosts.type(keys.offered), KnownHosts.fingerprint(keys.offered)));
                }
                throw failure(plugin, server, e);
            }
            try {
                ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
                channel.connect(CONNECT_TIMEOUT_MILLIS);
                return new SftpFs(plugin, host, session, channel);
            } catch (JSchException | SftpException | RuntimeException e) {
                session.disconnect();
                throw new IOException(message(plugin, "sftp.error.connect", server, RemoteText.detail(e)), e);
            }
        }
    }

    private static Session open(SftpSettings settings, String host, int port, HostKeys keys, IPlugin plugin)
            throws JSchException, IOException {
        JSch jsch = new JSch();
        jsch.setHostKeyRepository(keys);
        String privateKey = settings.privateKey();
        if (privateKey != null && !privateKey.isBlank()) {
            String passphrase = settings.passphrase();
            try {
                jsch.addIdentity(privateKey.strip(), passphrase == null || passphrase.isEmpty() ? null
                        : passphrase.getBytes(StandardCharsets.UTF_8));
            } catch (JSchException e) {
                // JSch names the file and the problem, never the passphrase
                throw new IOException(message(plugin, "sftp.error.private-key", privateKey.strip(),
                        RemoteText.detail(e)), e);
            }
        }
        Session session = jsch.getSession(settings.user(), host, port);
        session.setConfig("StrictHostKeyChecking", "yes");
        session.setConfig("HashKnownHosts", "no");
        session.setConfig("server_host_key", KEY_ALGORITHMS);
        session.setConfig("PubkeyAcceptedAlgorithms", KEY_ALGORITHMS);
        session.setConfig("kex", KEX_ALGORITHMS);
        session.setConfig("cipher.c2s", CIPHERS);
        session.setConfig("cipher.s2c", CIPHERS);
        session.setConfig("mac.c2s", MACS);
        session.setConfig("mac.s2c", MACS);
        session.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password");
        String password = settings.password();
        if (password != null && !password.isEmpty()) {
            session.setPassword(password);
        }
        session.setUserInfo(new Credentials(password));
        session.setDaemonThread(true); // never keeps the application alive
        session.setServerAliveInterval(SERVER_ALIVE_INTERVAL_MILLIS); // also the socket's read timeout
        session.setServerAliveCountMax(SERVER_ALIVE_COUNT_MAX);
        session.connect(CONNECT_TIMEOUT_MILLIS);
        return session;
    }

    /** A failure to connect, without the details of an authentication (which never hold the secrets anyway). */
    private static IOException failure(IPlugin plugin, String server, JSchException e) {
        String detail = RemoteText.detail(e);
        if (detail.startsWith("Auth ")) { // "Auth fail", "Auth cancel": the methods tried, nothing else
            return new IOException(message(plugin, "sftp.error.auth", server), e);
        }
        return new IOException(message(plugin, "sftp.error.connect", server, detail), e);
    }

    // ---- RemoteFs

    @Override
    public List<RemoteEntry> list(String folder) throws IOException {
        String path = absolute(folder);
        List<ChannelSftp.LsEntry> entries = new ArrayList<>();
        call(() -> {
            channel.ls(quote(path), entry -> {
                entries.add(entry);
                return ChannelSftp.LsEntrySelector.CONTINUE;
            });
            return null;
        });
        List<RemoteEntry> result = new ArrayList<>();
        for (ChannelSftp.LsEntry entry : entries) {
            String name = entry.getFilename();
            if (name.equals(".") || name.equals("..")) {
                continue;
            }
            SftpATTRS attrs = entry.getAttrs();
            if (attrs.isLink()) { // a link to a file is followed; a link to a folder (it may loop) or broken is not
                attrs = statOrNull(RemotePaths.join(path, name));
                if (attrs == null || attrs.isDir()) {
                    continue;
                }
            }
            if (attrs.isDir()) {
                result.add(RemoteEntry.folder(name));
            } else if (attrs.isReg()) {
                result.add(RemoteEntry.file(name, attrs.getSize(), lastModified(attrs)));
            } // a device, a socket... is not a file to copy
        }
        return result;
    }

    @Override
    public InputStream open(String path) throws IOException {
        String absolute = absolute(path);
        return call(() -> channel.get(quote(absolute)));
    }

    @Override
    public Optional<RemoteEntry> stat(String path) throws IOException {
        String absolute = absolute(path);
        SftpATTRS attrs = statOrNull(absolute);
        if (attrs == null) {
            return Optional.empty();
        }
        String name = RemotePaths.name(absolute);
        return Optional.of(attrs.isDir() ? RemoteEntry.folder(name)
                : RemoteEntry.file(name, attrs.getSize(), lastModified(attrs)));
    }

    @Override
    public void mkdirs(String folder) throws IOException {
        String absolute = absolute(folder);
        Optional<RemoteEntry> existing = stat(absolute);
        if (existing.isPresent()) {
            if (!existing.get().directory()) {
                throw new IOException(message(plugin, "sftp.error.not-a-folder", display(absolute)));
            }
            return;
        }
        String parent = RemotePaths.parent(absolute);
        if (!parent.isEmpty() && !parent.equals("/")) {
            mkdirs(parent);
        }
        try {
            call(() -> {
                channel.mkdir(absolute); // not read as a pattern by JSch: not quoted
                return null;
            });
        } catch (InterruptedIOException e) {
            throw e; // a cancel: not checked again
        } catch (IOException e) {
            // created meanwhile by another connection
            if (stat(absolute).map(RemoteEntry::directory).orElse(false)) {
                return;
            }
            throw e;
        }
    }

    /** {@code setMtime} after the transfer: the date the write was told. */
    @Override
    public boolean keepsLastModified() {
        return true;
    }

    /**
     * Sends the content, then sets the date. A failure leaves the SFTP channel with answers it did not read: the
     * channel is replaced (the session kept), so that the caller can delete what was sent.
     * <p>
     * Unlike the other calls, the thread stays interrupted while the content is sent: the caller's stream stops a
     * cancelled upload at its next read. A cancel seen by that stream is kept on the thread, even when JSch cleared it
     * meanwhile. JSch itself swallows an interrupt while it waits to send (the server's window full, a key
     * re-exchange): on a slow link, a cancel arriving then is only seen at the next read of the content.
     */
    @Override
    public void upload(String path, InputStream content, long size, Instant lastModified) throws IOException {
        String absolute = quote(absolute(path));
        SourceStream source = new SourceStream(content);
        try {
            run(() -> {
                channel.put(source, absolute, ChannelSftp.OVERWRITE);
                return null;
            }, true);
        } catch (IOException | RuntimeException e) {
            if (source.failure != null) {
                if (source.failure instanceof InterruptedIOException) {
                    Thread.currentThread().interrupt();
                }
                throw source.failure; // the source failed (or the run was cancelled), not the server
            }
            throw e;
        }
        if (lastModified != null && lastModified.getEpochSecond() >= 0
                && lastModified.getEpochSecond() <= 0xFFFF_FFFFL) {
            call(() -> {
                channel.setMtime(absolute, (int) lastModified.getEpochSecond()); // unsigned 32 bits
                return null;
            });
        }
    }

    /**
     * Renames, keeping the date. Replacing: {@code posix-rename@openssh.com} when the server has it (JSch then uses
     * it for every rename), else the target deleted first. Not replacing: an existing target is refused first, as
     * the posix rename would replace it.
     */
    @Override
    public void rename(String from, String to, boolean replace) throws IOException {
        String source = quote(absolute(from));
        String target = absolute(to);
        if (stat(target).isPresent()) {
            if (!replace) {
                throw new IOException(message(plugin, "sftp.error.exists", display(target)));
            }
            if (!"1".equals(channel.getExtension(POSIX_RENAME))) {
                delete(target);
            }
        }
        call(() -> {
            channel.rename(source, quote(target));
            return null;
        });
    }

    @Override
    public void delete(String path) throws IOException {
        String absolute = quote(absolute(path));
        try {
            call(() -> {
                channel.rm(absolute);
                return null;
            });
        } catch (NoSuchFile e) {
            // nothing to delete
        }
    }

    /** JSch's own check, without a call to the server. */
    @Override
    public boolean isUsable() {
        return !broken && session.isConnected() && channel.isConnected();
    }

    /** Also on the interrupted thread of a cancel, whose interrupt is kept (JSch would clear it). */
    @Override
    public void close() {
        boolean interrupted = Thread.interrupted();
        try {
            channel.disconnect();
        } catch (RuntimeException e) {
            // closed anyway with the session
        } finally {
            session.disconnect();
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ---- helpers

    @FunctionalInterface
    private interface SftpCall<T> {
        T run() throws SftpException;
    }

    /** Thrown for a path that does not exist ({@code SSH_FX_NO_SUCH_FILE}). */
    private static final class NoSuchFile extends IOException {
        NoSuchFile(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Runs a call of JSch on a thread not interrupted, the interrupt of a cancel set again afterwards: JSch waits
     * for the server in loops that clear an interrupt and go on, which would lose the cancel.
     */
    private <T> T call(SftpCall<T> call) throws IOException {
        boolean interrupted = Thread.interrupted();
        try {
            return run(call);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private <T> T run(SftpCall<T> call) throws IOException {
        return run(call, false);
    }

    /**
     * Runs a call of JSch, its {@link SftpException} made an {@link IOException}.
     * <p>
     * A failure of the transport (an {@link IOException} under the SFTP one), or any failure when
     * {@code replaceOnFailure}, may leave the server's answer unread on the channel, which the next call would take
     * for its own (JSch does not check the request ids): the channel is replaced first. A cancel interrupting the wait
     * for the answer becomes an {@link InterruptedIOException} inside JSch, the interrupt cleared: it is set again on
     * the thread, and an {@link InterruptedIOException} is thrown.
     */
    private <T> T run(SftpCall<T> call, boolean replaceOnFailure) throws IOException {
        if (broken) {
            throw new IOException(message(plugin, "sftp.error.broken"));
        }
        try {
            return call.run();
        } catch (SftpException e) {
            boolean interrupted = interrupted(e);
            if (replaceOnFailure || interrupted || e.getCause() instanceof IOException) {
                reopenChannel();
            }
            String detail = e.getMessage() != null && !e.getMessage().isBlank() ? e.getMessage()
                    : e.getCause() != null ? RemoteText.detail(e.getCause()) : "SFTP status " + e.id;
            if (interrupted) {
                Thread.currentThread().interrupt();
                InterruptedIOException cancel = new InterruptedIOException(detail);
                cancel.initCause(e);
                throw cancel;
            }
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                throw new NoSuchFile(detail, e);
            }
            throw new IOException(detail, e);
        } catch (RuntimeException e) {
            if (replaceOnFailure) {
                reopenChannel();
            }
            throw e;
        }
    }

    /** Whether a failure of JSch comes from an interrupt of this thread (not a timeout of the socket). */
    private static boolean interrupted(Throwable failure) {
        for (Throwable e = failure; e != null; e = e.getCause()) {
            if (e instanceof InterruptedException
                    || (e instanceof InterruptedIOException && !(e instanceof SocketTimeoutException))) {
                return true;
            }
        }
        return false;
    }

    private SftpATTRS statOrNull(String absolute) throws IOException {
        try {
            return call(() -> channel.stat(quote(absolute)));
        } catch (NoSuchFile e) {
            return null;
        }
    }

    /** A new SFTP channel on the session, the interrupt of a cancel set aside while it opens. */
    private void reopenChannel() {
        boolean interrupted = Thread.interrupted();
        try {
            channel.disconnect();
            ChannelSftp fresh = (ChannelSftp) session.openChannel("sftp");
            fresh.connect(CONNECT_TIMEOUT_MILLIS);
            channel = fresh;
        } catch (JSchException | RuntimeException e) {
            broken = true;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** "sftp://host/path", for the messages. */
    private String display(String absolute) {
        return RemoteText.display("sftp", host, absolute);
    }

    /** The path from the server's root: a relative one under the folder the server started in. */
    private String absolute(String path) {
        if (path.startsWith("/")) {
            return path;
        }
        return path.isEmpty() ? home : RemotePaths.join(home, path);
    }

    /** The path as JSch reads it: '\', '*' and '?' escaped (it reads them as a pattern). */
    static String quote(String path) {
        StringBuilder quoted = new StringBuilder(path.length());
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '\\' || c == '*' || c == '?') {
                quoted.append('\\');
            }
            quoted.append(c);
        }
        return quoted.toString();
    }

    /** The date of modification, unsigned 32 bits seconds; null when the server gave none. */
    private static Instant lastModified(SftpATTRS attrs) {
        if ((attrs.getFlags() & SftpATTRS.SSH_FILEXFER_ATTR_ACMODTIME) == 0) {
            return null;
        }
        return Instant.ofEpochSecond(Integer.toUnsignedLong(attrs.getMTime()));
    }

    private static String message(IPlugin plugin, String key, Object... args) {
        return RemoteText.message(plugin, key, args);
    }

    /** The content of an upload: keeps its own failure, which JSch wraps in an {@link SftpException}. */
    private static final class SourceStream extends FilterInputStream {
        IOException failure;

        SourceStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            try {
                return in.read();
            } catch (IOException e) {
                failure = e;
                throw e;
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            try {
                return in.read(b, off, len);
            } catch (IOException e) {
                failure = e;
                throw e;
            }
        }

        @Override
        public void close() {
            // closed by the caller, who opened it
        }
    }

    /**
     * The server's key checked against the plugin's known_hosts: OK when recorded, else what was offered and why is
     * kept for {@link #connect}, which asks outside the connection; the connection stops there, before any
     * authentication. Also tells JSch the types recorded for the server, which it prefers.
     */
    private static final class HostKeys implements HostKeyRepository {
        private final HostTrust trust;
        private final String name;
        volatile byte[] offered;
        volatile HostTrust.Verdict verdict;
        volatile IOException failure;

        HostKeys(HostTrust trust, String name) {
            this.trust = trust;
            this.name = name;
        }

        @Override
        public int check(String host, byte[] key) {
            offered = key.clone();
            try {
                verdict = trust.verdict(name, key);
            } catch (IOException e) {
                failure = e;
                return NOT_INCLUDED;
            }
            return switch (verdict) {
                case TRUSTED -> OK;
                case UNKNOWN -> NOT_INCLUDED;
                case CHANGED, REVOKED -> CHANGED; // either way the connection stops
            };
        }

        @Override
        public void add(HostKey hostkey, UserInfo ui) {
            // recorded by HostTrust only
        }

        @Override
        public void remove(String host, String type) {
            // never removed by a connection
        }

        @Override
        public void remove(String host, String type, byte[] key) {
            // never removed by a connection
        }

        @Override
        public String getKnownHostsRepositoryID() {
            return "known_hosts";
        }

        @Override
        public HostKey[] getHostKey() {
            return getHostKey(name, null);
        }

        @Override
        public HostKey[] getHostKey(String host, String type) {
            List<HostKey> keys = new ArrayList<>();
            try {
                for (KnownHosts.Key key : trust.recorded(name)) {
                    if (type == null || type.equals(key.type())) {
                        try {
                            keys.add(new HostKey(name, key.blob()));
                        } catch (JSchException e) {
                            // a type JSch does not know: not preferred
                        }
                    }
                }
            } catch (IOException e) {
                // read again by check, which reports it
            }
            return keys.toArray(HostKey[]::new);
        }
    }

    /**
     * The password, given to keyboard-interactive once (a single hidden prompt); never prompted for again, nor a
     * passphrase (given with the key).
     */
    private static final class Credentials implements UserInfo, UIKeyboardInteractive {
        private final String password;
        private boolean answered;

        Credentials(String password) {
            this.password = password;
        }

        @Override
        public String getPassphrase() {
            return null;
        }

        @Override
        public String getPassword() {
            return null;
        }

        @Override
        public boolean promptPassword(String message) {
            return false;
        }

        @Override
        public boolean promptPassphrase(String message) {
            return false;
        }

        @Override
        public boolean promptYesNo(String message) {
            return false;
        }

        @Override
        public void showMessage(String message) {
            // a banner of the server: not shown
        }

        @Override
        public String[] promptKeyboardInteractive(String destination, String name, String instruction,
                                                  String[] prompt, boolean[] echo) {
            if (answered || password == null || password.isEmpty() || prompt.length != 1 || echo[0]) {
                return null;
            }
            answered = true;
            return new String[]{password};
        }
    }
}

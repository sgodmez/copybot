package com.copybot.plugin.remote.ftp;

import com.copybot.plugin.api.definition.IPlugin;
import com.copybot.plugin.remote.core.RemoteEntry;
import com.copybot.plugin.remote.core.RemoteFs;
import com.copybot.plugin.remote.core.RemotePaths;
import com.copybot.plugin.remote.core.RemoteText;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPClientConfig;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPSClient;
import org.apache.commons.net.io.CopyStreamException;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * An FTP or FTPS connection (spec remote §3), with Apache Commons Net: one control connection, one data transfer at a
 * time on it (the pool gives each open content its own connection).
 * <ul>
 *     <li>FTPS (explicit by AUTH TLS, or implicit): the server's certificate is checked against the system's trusted
 *     certificates and the server's name, else against the public key the user trusted for the server
 *     ({@link CertificateTrust}: asked once, outside the handshake, by the control connection); the data connections
 *     are protected too ({@code PBSZ 0}, {@code PROT P}), checked the same way but never asking, and offer to resume
 *     the TLS session of the control connection, which many servers require.</li>
 *     <li>Binary transfers, passive mode unless configured active; UTF-8 names ({@code OPTS UTF8 ON} when the server
 *     offers it).</li>
 *     <li>Listing by {@code MLSD} (exact dates, UTC), else {@code LIST} with each file's date asked by
 *     {@code MDTM} (offered in FEAT, else tried once at connect); the LIST format is the one the server names
 *     ({@code SYST}), Unix when it names none known.</li>
 *     <li>Dates written by {@code MFMT}, else {@code SITE UTIME} when the server lists it; kept
 *     ({@link #keepsLastModified()}) only when they can also be read again ({@code MLST} or {@code MDTM}).</li>
 *     <li>Timeouts: 20 s to connect, 60 s of silence on the control connection or while receiving data; an upload
 *     of which the server takes nothing for 60 s is abandoned (a watchdog closes its data connection: the read
 *     timeout does not bound a write).</li>
 *     <li>A path is absent only when the server says so (550; a 501 to MLST is checked by LIST); any other refusal
 *     fails the call, so that a busy server (450) is never taken for an empty folder and its files overwritten.</li>
 *     <li>A transfer that failed or was cut short: the server's answer to it is read (5 s at most), so that the
 *     connection is in step again; when it does not come, the next call connects again (the caller can then delete
 *     what was sent).</li>
 * </ul>
 */
final class FtpFs implements RemoteFs {

    static final int CONNECT_TIMEOUT_MILLIS = 20_000;
    /** The silence after which a control or data connection fails. */
    static final int READ_TIMEOUT_MILLIS = 60_000;
    /** The wait for the server's answer to a transfer that failed or was cut short. */
    static final int SETTLE_TIMEOUT_MILLIS = 5_000;
    /** The time an upload may go without the server taking any of its content; visible for tests. */
    static volatile int uploadStallMillis = READ_TIMEOUT_MILLIS;

    private static final System.Logger LOGGER = System.getLogger(FtpFs.class.getName());

    /** Watches the uploads that stall (one daemon thread for all the connections). */
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "copybot-ftp-upload-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    /** The SYST keywords Commons Net has a LIST parser for, in the order it tries them; "TYPE: L8" is Unix. */
    private static final List<String> SYSTEMS = List.of(FTPClientConfig.SYST_UNIX_TRIM_LEADING,
            FTPClientConfig.SYST_UNIX, FTPClientConfig.SYST_VMS, FTPClientConfig.SYST_NT, FTPClientConfig.SYST_OS2,
            FTPClientConfig.SYST_OS400, FTPClientConfig.SYST_AS400, FTPClientConfig.SYST_MVS,
            FTPClientConfig.SYST_NETWARE, FTPClientConfig.SYST_MACOS_PETER, FTPClientConfig.SYST_L8);

    private static final DateTimeFormatter FTP_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.ROOT).withZone(ZoneOffset.UTC);

    private final FtpSettings settings;
    private final FtpConnections.Security security;
    /** The certificates trusted by this computer; null: the system's. */
    private final X509ExtendedTrustManager system;
    /** The certificates the user trusted besides. */
    private final CertificateTrust certificates;
    private final IPlugin plugin;
    private final String host;
    private final int port;

    private FTPClient client;
    /** The control connection whose LIST format is chosen. */
    private FTPClient listFormatChosen;
    /** The folder the server starts in, absolute. */
    private String home;
    /** The server answers MLSD and MLST (exact dates). */
    private boolean mlst;
    /** The server answers MDTM (the date of a file listed by LIST): offered in FEAT, else tried. */
    private boolean mdtm;
    /** The server sets dates by MFMT, else by SITE UTIME. */
    private boolean mfmt;
    private boolean siteUtime;
    /** The control connection may be out of step: connected again before the next call. */
    private boolean stale;

    private FtpFs(FtpSettings settings, FtpConnections.Security security, X509ExtendedTrustManager system,
                  CertificateTrust certificates, IPlugin plugin) {
        this.settings = settings;
        this.security = security;
        this.system = system;
        this.certificates = certificates;
        this.plugin = plugin;
        this.host = settings.host().strip();
        this.port = FtpConnections.port(settings, security);
    }

    /**
     * Connects and logs in; with FTPS, a certificate this computer does not trust is put to the user, once, when its
     * public key is not recorded for the server.
     *
     * @param system       the certificates trusted for FTPS by this computer; null: the system's
     * @param certificates the certificates the user trusted besides (the step's)
     * @throws IOException the connection, the TLS negotiation or the login failed, the certificate is not trusted or
     *                     changed; never with the password in its message
     */
    static FtpFs connect(FtpSettings settings, FtpConnections.Security security, X509ExtendedTrustManager system,
                         CertificateTrust certificates, IPlugin plugin) throws IOException {
        if (Thread.currentThread().isInterrupted()) { // a cancelled run
            throw new InterruptedIOException("interrupted");
        }
        FtpFs fs = new FtpFs(settings, security, system, certificates, plugin);
        fs.client = fs.open();
        return fs;
    }

    /** A certificate neither this computer nor the user trusts: the handshake failed on it. */
    private static final class Untrusted extends IOException {
        final CertificateTrust.Verdict verdict;
        final CertificateTrust.Offered offered;

        Untrusted(CertificateTrust.Verdict verdict, CertificateTrust.Offered offered, SSLException cause) {
            super(cause);
            this.verdict = verdict;
            this.offered = offered;
        }
    }

    /**
     * A new control connection, logged in and set up. A certificate not recorded stops the handshake (which does not
     * wait for the user); the user is asked with the connection closed, and on yes it connects again, once.
     */
    private FTPClient open() throws IOException {
        String key = FtpsTrustFile.key(host, port);
        for (int attempt = 1; ; attempt++) {
            try {
                return open(security == FtpConnections.Security.NONE ? null
                        : new PinningTrustManager(system == null ? systemTrust() : system, certificates, key, host,
                        server()));
            } catch (Untrusted e) {
                if (e.verdict == CertificateTrust.Verdict.CHANGED) {
                    throw certificates.changed(key, server(), e.offered.pin());
                }
                if (attempt > 1) { // trusted, then another certificate shown at once: not asked twice
                    throw certificates.notTrusted(server(), e.offered);
                }
                certificates.approve(key, server(), host, e.offered); // throws unless trusted now
            }
        }
    }

    /** A new control connection, logged in and set up, its certificates checked by {@code pinning} (FTPS). */
    private FTPClient open(PinningTrustManager pinning) throws IOException {
        FTPClient c = pinning == null ? new PlainClient()
                : new TlsClient(security == FtpConnections.Security.IMPLICIT, tls(pinning));
        c.setControlEncoding(StandardCharsets.UTF_8.name());
        c.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        c.setDefaultTimeout(READ_TIMEOUT_MILLIS); // the control connection's
        c.setDataTimeout(Duration.ofMillis(READ_TIMEOUT_MILLIS));
        if (c instanceof FTPSClient tls) {
            tls.setEndpointCheckingEnabled(true); // the certificate must name the server
        }
        String server = server();
        try {
            c.connect(host, port); // with FTPS, the control connection's handshake
            if (pinning != null) {
                pinning.controlConnected(); // the data connections never ask
            }
            if (!FTPReply.isPositiveCompletion(c.getReplyCode())) {
                throw new Failure(message("ftp.error.connect", server, reply(c)));
            }
            String password = settings.password() == null ? "" : settings.password();
            if (!c.login(settings.user(), password)) {
                // the server's answer is left out: some repeat the user name
                throw new Failure(message("ftp.error.auth", server));
            }
            if (c instanceof TlsClient tls) {
                tls.execPBSZ(0);
                tls.execPROT("P");
                tls.plainDataSockets();
            }
            if (!c.setFileType(FTP.BINARY_FILE_TYPE)) {
                throw new Failure(message("ftp.error.connect", server, reply(c)));
            }
            if (settings.passive() == null || settings.passive()) {
                c.enterLocalPassiveMode();
            } else {
                c.enterLocalActiveMode();
            }
            mlst = c.hasFeature("MLST");
            mfmt = c.hasFeature("MFMT");
            if (c.hasFeature("UTF8")) {
                c.sendCommand("OPTS", "UTF8 ON"); // the names are sent in UTF-8 either way
            }
            siteUtime = !mfmt && FTPReply.isPositiveCompletion(c.sendCommand("SITE", "HELP"))
                    && c.getReplyString().toUpperCase(Locale.ROOT).contains("UTIME");
            String pwd = c.printWorkingDirectory();
            home = pwd == null || !pwd.startsWith("/") ? "/" : pwd;
            // no FEAT, or a FEAT silent on it: MDTM tried once (any answer but "unknown command" means it is known)
            mdtm = c.hasFeature("MDTM") || !unknownCommand(c.mdtm(home));
            return c;
        } catch (Failure e) {
            disconnect(c);
            throw e;
        } catch (SSLException e) {
            disconnect(c);
            if (pinning != null && pinning.failure() != null) { // the trust file not read
                throw new IOException(certificates.fileFailure(pinning.failure()), pinning.failure());
            }
            if (pinning != null && pinning.offered() != null) {
                throw new Untrusted(pinning.verdict(), pinning.offered(), e);
            }
            throw new IOException(message("ftp.error.tls", server, RemoteText.detail(e)), e);
        } catch (IOException | RuntimeException e) {
            disconnect(c);
            if (Thread.currentThread().isInterrupted()) {
                throw cancel(e);
            }
            throw new IOException(message("ftp.error.connect", server, RemoteText.detail(e)), e);
        }
    }

    /** The system's trusted certificates (cacerts), with their check of the server's name. */
    private static X509ExtendedTrustManager systemTrust() throws IOException {
        try {
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init((KeyStore) null);
            for (TrustManager manager : factory.getTrustManagers()) {
                if (manager instanceof X509ExtendedTrustManager x509) {
                    return x509;
                }
            }
            throw new IOException("no X.509 trust manager");
        } catch (GeneralSecurityException e) {
            throw new IOException(RemoteText.detail(e), e);
        }
    }

    /** A TLS context of its own (its session is resumed by the data connections of this connection only). */
    private static SSLContext tls(PinningTrustManager pinning) throws IOException {
        try {
            // not Commons Net's default, which only checks the dates of the certificate
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{pinning}, null);
            return context;
        } catch (GeneralSecurityException e) {
            throw new IOException(RemoteText.detail(e), e);
        }
    }

    // ---- RemoteFs

    /**
     * MLSD, else LIST, in the folder once entered (CWD): a missing folder fails there, where some servers would list
     * nothing, and its name is never read as options or a pattern by LIST.
     */
    @Override
    public List<RemoteEntry> list(String folder) throws IOException {
        String path = absolute(folder);
        if (mlst) {
            FTPFile[] files = call(c -> {
                enter(c, path);
                FTPFile[] listed = c.mlistDir();
                return FTPReply.isPositiveCompletion(c.getReplyCode()) ? listed : null;
            });
            if (files != null) {
                return entries(path, files, true);
            }
            if (!notImplemented(client.getReplyCode())) {
                throw new Failure(message("ftp.error.reply", reply(client)));
            }
            mlst = false; // MLST offered, MLSD not
        }
        return entries(path, listPlain(path), false);
    }

    /** LIST in the folder. */
    private FTPFile[] listPlain(String path) throws IOException {
        return call(c -> {
            enter(c, path);
            return listHere(c);
        });
    }

    /**
     * LIST of the current folder, as {@code LIST -a}: some servers (vsftpd by default) leave names starting with a
     * dot out otherwise, and the temporary files and dot-named targets would be taken for missing. A server refusing
     * the option is listed without it from then on.
     */
    private FTPFile[] listHere(FTPClient c) throws IOException {
        if (listFormatChosen != c) {
            chooseListFormat(c);
            c.setListHiddenFiles(true);
            listFormatChosen = c;
        }
        FTPFile[] listed = c.listFiles();
        if (c.getListHiddenFiles() && FTPReply.isNegativePermanent(c.getReplyCode())) {
            c.setListHiddenFiles(false); // "-a" refused (501), or taken for a path (550): plain LIST decides
            listed = c.listFiles();
        }
        if (!FTPReply.isPositiveCompletion(c.getReplyCode())) {
            throw new Failure(message("ftp.error.reply", reply(c)));
        }
        return listed;
    }

    /**
     * The LIST format of the system SYST names; Unix, the most common, when SYST fails or names a system Commons Net
     * has no parser for (every listing would fail; Commons Net would also look the name up as a class).
     */
    private static void chooseListFormat(FTPClient c) throws IOException {
        String system = null;
        if (FTPReply.isPositiveCompletion(c.syst()) && c.getReplyStrings().length > 0
                && c.getReplyStrings()[0].length() > 4) {
            String named = c.getReplyStrings()[0].substring(4).toUpperCase(Locale.ROOT);
            system = SYSTEMS.stream().filter(named::contains).findFirst().orElse(null);
        }
        if (system == null || system.equals(FTPClientConfig.SYST_L8)) {
            system = FTPClientConfig.SYST_UNIX;
        }
        c.configure(new FTPClientConfig(system));
    }

    /** CWD; a refusal (no such folder) as a {@link Failure}. */
    private void enter(FTPClient c, String path) throws IOException {
        if (!c.changeWorkingDirectory(path)) {
            throw new Failure(message("ftp.error.reply", reply(c)));
        }
    }

    /** The files and folders listed; "." and "..", links and other kinds left out. */
    private List<RemoteEntry> entries(String folder, FTPFile[] files, boolean mlsd) throws IOException {
        List<RemoteEntry> result = new ArrayList<>();
        for (FTPFile file : files) {
            String name = file == null || file.getName() == null ? "" : RemotePaths.name(file.getName());
            if (name.isEmpty() || name.equals(".") || name.equals("..")) {
                continue;
            }
            if (mlsd && file.getRawListing() != null) {
                String raw = file.getRawListing().toLowerCase(Locale.ROOT);
                if (raw.contains("type=cdir;") || raw.contains("type=pdir;")) {
                    continue; // the folder itself, its parent
                }
            }
            if (file.isDirectory()) {
                result.add(RemoteEntry.folder(name));
            } else if (file.isFile()) {
                String path = RemotePaths.join(folder, name);
                if (!mlsd && file.getSize() < 0) {
                    // a size LIST does not give (unreadable, out of range): 0 would be taken for an empty file
                    LOGGER.log(System.Logger.Level.WARNING, "ftp: " + display(path)
                            + " is left out: the server listed it without a readable size");
                    continue;
                }
                Instant date = mlsd ? timestamp(file) : date(path, file);
                result.add(RemoteEntry.file(name, Math.max(0, file.getSize()), date));
            } // a link, a device...: not a file to copy
        }
        return result;
    }

    @Override
    public InputStream open(String path) throws IOException {
        String absolute = absolute(path);
        InputStream data = call(c -> c.retrieveFileStream(absolute));
        if (data == null) {
            throw new Failure(message("ftp.error.reply", reply(client)));
        }
        return new DataStream(data);
    }

    @Override
    public Optional<RemoteEntry> stat(String path) throws IOException {
        String absolute = absolute(path);
        if (absolute.equals("/")) {
            return Optional.of(RemoteEntry.folder(""));
        }
        String name = RemotePaths.name(absolute);
        if (mlst) {
            FTPFile file = call(c -> c.mlistFile(absolute));
            if (file != null) {
                return Optional.of(file.isDirectory() ? RemoteEntry.folder(name)
                        : RemoteEntry.file(name, Math.max(0, file.getSize()), timestamp(file)));
            }
            int code = client.getReplyCode();
            if (code == FTPReply.FILE_UNAVAILABLE) {
                return Optional.empty(); // 550: nothing there
            }
            if (notImplemented(code)) {
                mlst = false;
            } else if (code != FTPReply.SYNTAX_ERROR_IN_ARGUMENTS) { // 450 busy, 530...: no answer about the path
                throw new Failure(message("ftp.error.reply", reply(client)));
            } // 501: what some servers (Apache FtpServer) answer for a missing path; the parent's LIST tells
        }
        String parent = RemotePaths.parent(absolute);
        FTPFile[] files = call(c -> {
            if (!c.changeWorkingDirectory(parent)) {
                if (c.getReplyCode() == FTPReply.FILE_UNAVAILABLE) {
                    return null; // 550: no parent folder
                }
                throw new Failure(message("ftp.error.reply", reply(c)));
            }
            return listHere(c);
        });
        if (files == null) {
            return Optional.empty();
        }
        for (FTPFile file : files) {
            if (file != null && name.equals(file.getName())) {
                return Optional.of(file.isDirectory() ? RemoteEntry.folder(name)
                        : RemoteEntry.file(name, Math.max(0, file.getSize()), date(absolute, file)));
            }
        }
        return Optional.empty();
    }

    @Override
    public void mkdirs(String folder) throws IOException {
        String absolute = absolute(folder);
        Optional<RemoteEntry> existing = stat(absolute);
        if (existing.isPresent()) {
            if (!existing.get().directory()) {
                throw new Failure(message("ftp.error.not-a-folder", display(absolute)));
            }
            return;
        }
        String parent = RemotePaths.parent(absolute);
        if (!parent.isEmpty() && !parent.equals("/")) {
            mkdirs(parent);
        }
        if (!call(c -> c.makeDirectory(absolute))) {
            String reply = reply(client);
            Optional<RemoteEntry> now;
            try {
                now = stat(absolute);
            } catch (Failure e) {
                now = Optional.empty(); // the refusal of MKD is the one to tell
            }
            if (now.map(RemoteEntry::directory).orElse(false)) {
                return; // created meanwhile by another connection
            }
            throw new Failure(message("ftp.error.mkdir", display(absolute), reply));
        }
    }

    /** MFMT, or SITE UTIME, offered by the server, and the dates read again exactly (MLST, or MDTM after LIST). */
    @Override
    public boolean keepsLastModified() {
        return setsDates() && (mlst || mdtm);
    }

    /** MFMT, or SITE UTIME, offered by the server. */
    private boolean setsDates() {
        return mfmt || siteUtime;
    }

    /**
     * Sends the content (STOR), then sets the date when the server can. The thread stays interrupted while the
     * content is sent: the caller's stream stops a cancelled upload at its next read. A server that takes nothing of
     * the content for 60 s has the data connection closed by a watchdog (the read timeout does not bound a write),
     * which fails the upload. After a failure the server's answer to the transfer is read, else the next call
     * connects again, so that the caller can delete what was sent.
     */
    @Override
    public void upload(String path, InputStream content, long size, Instant lastModified) throws IOException {
        String absolute = absolute(path);
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("interrupted");
        }
        FTPClient c = client();
        SourceStream source = new SourceStream(content);
        int stallMillis = uploadStallMillis;
        long period = Math.max(10, Math.min(1_000, stallMillis / 4));
        ScheduledFuture<?> watchdog = WATCHDOG.scheduleWithFixedDelay(() -> {
            if (source.stalledFor(stallMillis)) {
                source.stalled = true;
                ((DataSockets) c).closeData(); // the blocked write fails
            }
        }, period, period, TimeUnit.MILLISECONDS);
        boolean stored;
        try {
            stored = c.storeFile(absolute, source);
        } catch (IOException | RuntimeException e) {
            watchdog.cancel(false);
            if (e instanceof CopyStreamException) {
                settle(); // the content or the data connection failed; the control connection waits with its answer
            } else {
                stale = true;
            }
            if (source.failure != null) {
                if (source.failure instanceof InterruptedIOException) {
                    Thread.currentThread().interrupt();
                }
                throw source.failure; // the source failed (or the run was cancelled), not the server
            }
            if (source.runtime != null) {
                throw source.runtime;
            }
            if (source.stalled && e instanceof CopyStreamException) {
                IOException stalled = new IOException(message("ftp.error.stalled", display(absolute),
                        Math.max(1, stallMillis / 1000)));
                stalled.initCause(e);
                throw stalled;
            }
            throw failure(e);
        } finally {
            watchdog.cancel(false);
        }
        if (!stored) {
            throw new Failure(message("ftp.error.reply", reply(c)));
        }
        if (lastModified != null && setsDates()) {
            String time = FTP_TIME.format(lastModified);
            if (!call(cl -> mfmt ? cl.setModificationTime(absolute, time) : siteUtime(cl, absolute, time))) {
                throw new Failure(message("ftp.error.date", display(absolute), reply(client)));
            }
        }
    }

    /** "SITE UTIME time path" (ProFTPD), else "SITE UTIME path atime mtime ctime UTC". */
    private static boolean siteUtime(FTPClient c, String path, String time) throws IOException {
        return FTPReply.isPositiveCompletion(c.sendCommand("SITE", "UTIME " + time + " " + path))
                || FTPReply.isPositiveCompletion(c.sendCommand("SITE",
                "UTIME " + path + " " + time + " " + time + " " + time + " UTC"));
    }

    /**
     * RNFR / RNTO, which keeps the date. Servers differ on an existing target (replaced or refused): it is refused
     * first when not replacing; when replacing, RNTO is tried first (the server may replace in one step), and the
     * target deleted then renamed to only when RNTO refused the name (550, 553) while both files exist.
     */
    @Override
    public void rename(String from, String to, boolean replace) throws IOException {
        String source = absolute(from);
        String target = absolute(to);
        if (!replace && stat(target).isPresent()) {
            throw new Failure(message("ftp.error.exists", display(target)));
        }
        int refused = call(c -> renameTo(c, source, target));
        if (refused != 0 && replace && (refused == FTPReply.FILE_UNAVAILABLE
                || refused == FTPReply.FILE_NAME_NOT_ALLOWED) && stat(target).isPresent()
                && stat(source).isPresent()) { // some servers take RNFR of a missing file: the target is kept then
            delete(target);
            refused = call(c -> renameTo(c, source, target));
        }
        if (refused != 0) {
            throw new Failure(message("ftp.error.rename", display(source), display(target), reply(client)));
        }
    }

    /** RNFR then RNTO: 0 when renamed, -1 when RNFR was refused (the source), else the answer to RNTO. */
    private static int renameTo(FTPClient c, String source, String target) throws IOException {
        if (!FTPReply.isPositiveIntermediate(c.rnfr(source))) {
            return -1;
        }
        int code = c.rnto(target);
        return FTPReply.isPositiveCompletion(code) ? 0 : code;
    }

    @Override
    public void delete(String path) throws IOException {
        String absolute = absolute(path);
        if (!call(c -> c.deleteFile(absolute))) {
            String reply = reply(client);
            boolean left;
            try {
                left = stat(absolute).isPresent();
            } catch (Failure e) {
                left = true; // not known to be gone: the refusal of DELE is the one to tell
            }
            if (left) {
                throw new Failure(message("ftp.error.delete", display(absolute), reply));
            } // nothing to delete
        }
    }

    /** Local: the control connection is open and in step. */
    @Override
    public boolean isUsable() {
        return !stale && client.isConnected();
    }

    /** QUIT (5 s at most) when in step, then closed; also on the interrupted thread of a cancel, which stays so. */
    @Override
    public void close() {
        boolean interrupted = Thread.interrupted();
        try {
            if (!stale && client.isConnected()) {
                client.setSoTimeout(SETTLE_TIMEOUT_MILLIS);
                client.logout();
            }
        } catch (IOException | RuntimeException e) {
            // closed anyway
        } finally {
            disconnect(client);
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Visible for tests: whether the last data connection resumed the TLS session of the control connection. */
    boolean dataSessionResumed() {
        return client instanceof TlsClient tls && tls.resumed;
    }

    // ---- helpers

    /** A refusal of the server (a negative answer read in full): the connection stays in step. */
    private static final class Failure extends IOException {
        Failure(String message) {
            super(message);
        }
    }

    @FunctionalInterface
    private interface FtpCall<T> {
        T run(FTPClient c) throws IOException;
    }

    /**
     * Runs commands on a thread not interrupted, the interrupt of a cancel set again afterwards: an interrupted
     * virtual thread would have its socket closed by the first wait for the server.
     */
    private <T> T call(FtpCall<T> call) throws IOException {
        boolean interrupted = Thread.interrupted();
        try {
            FTPClient c = client();
            try {
                return call.run(c);
            } catch (Failure e) {
                throw e;
            } catch (IOException | RuntimeException e) {
                stale = true; // an answer may be left unread
                throw failure(e);
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** The control connection, connected again first when it may be out of step. */
    private FTPClient client() throws IOException {
        if (stale) {
            // connected again with the interrupt of a cancel set aside (an interrupted virtual thread would have the
            // new socket closed at once), so that the cleanup of a cancelled write still runs; the interrupt is set
            // again afterwards. A certificate question asked here would wait for its answer (the interrupt is set
            // aside); it cannot happen in practice, the server having been trusted when the connection first opened.
            boolean interrupted = Thread.interrupted();
            try {
                disconnect(client);
                client = open();
                stale = false;
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        return client;
    }

    /**
     * After a transfer that failed or was cut short: reads the server's answer to it, so that the connection is in
     * step again; when it does not come, the next call connects again.
     */
    private void settle() {
        boolean interrupted = Thread.interrupted();
        try {
            client.setSoTimeout(SETTLE_TIMEOUT_MILLIS);
            client.completePendingCommand(); // 226, 426, 451...: any answer
            client.setSoTimeout(READ_TIMEOUT_MILLIS);
        } catch (IOException | RuntimeException e) {
            stale = true;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** A failure of the connection; a cancel (the thread interrupted) as an {@link InterruptedIOException}. */
    private static IOException failure(Exception e) {
        if (Thread.currentThread().isInterrupted() || interrupted(e)) {
            return cancel(e);
        }
        return e instanceof IOException io ? io : new IOException(RemoteText.detail(e), e);
    }

    private static InterruptedIOException cancel(Exception e) {
        Thread.currentThread().interrupt();
        InterruptedIOException cancel = new InterruptedIOException(RemoteText.detail(e));
        cancel.initCause(e);
        return cancel;
    }

    /** Whether a failure comes from an interrupt of this thread (not a timeout of the socket). */
    private static boolean interrupted(Throwable failure) {
        for (Throwable e = failure; e != null; e = e.getCause()) {
            if (e instanceof InterruptedIOException && !(e instanceof SocketTimeoutException)) {
                return true;
            }
        }
        return false;
    }

    /** 500, 502, 504: the command is not known to the server. */
    private static boolean notImplemented(int code) {
        return code == 500 || code == 502 || code == 504;
    }

    /** 500, 502: the command is unknown (504 would be a parameter it does not take). */
    private static boolean unknownCommand(int code) {
        return code == 500 || code == 502;
    }

    /** The date of an MLSD or MLST entry (UTC); null when it has none. */
    private static Instant timestamp(FTPFile file) {
        return file.getTimestamp() == null ? null : file.getTimestamp().toInstant();
    }

    /** The date of a file listed by LIST: MDTM (UTC, to the second), else the listing's (often to the minute). */
    private Instant date(String path, FTPFile file) throws IOException {
        if (mdtm) {
            Instant exact = call(c -> c.mdtmInstant(path));
            if (exact != null) {
                return exact;
            }
        }
        return timestamp(file);
    }

    private static void disconnect(FTPClient c) {
        try {
            if (c.isConnected()) {
                c.disconnect();
            }
        } catch (IOException | RuntimeException e) {
            // closed anyway
        }
    }

    /** The last answer of the server, on one line. */
    private static String reply(FTPClient c) {
        String reply = c.getReplyString();
        return reply == null ? "" : reply.strip().replaceAll("\\s*[\\r\\n]+\\s*", " ");
    }

    /** "host", or "host:port" when the port is not the usual one. */
    private String server() {
        return port == (security == FtpConnections.Security.IMPLICIT ? 990 : 21) ? host : host + ":" + port;
    }

    /** "ftp://host/path", for the messages. */
    private String display(String absolute) {
        return RemoteText.display("ftp", host, absolute);
    }

    /**
     * The path from the server's root: a relative one under the folder the server started in.
     *
     * @throws IOException the path holds a line break (it would end the command)
     */
    private String absolute(String path) throws IOException {
        if (path.indexOf('\r') >= 0 || path.indexOf('\n') >= 0) {
            throw new Failure(message("ftp.error.path"));
        }
        if (path.startsWith("/")) {
            return path;
        }
        return path.isEmpty() ? home : RemotePaths.join(home, path);
    }

    private String message(String key, Object... args) {
        return RemoteText.message(plugin, key, args);
    }

    /**
     * The content of a download: its end completes the transfer (the server's answer read); closed before its end
     * or failed, the server's answer is read anyway, or the connection connected again at the next call.
     */
    private final class DataStream extends FilterInputStream {
        private boolean ended;
        private boolean failed;
        private boolean closed;

        DataStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            try {
                int b = in.read();
                ended |= b < 0;
                return b;
            } catch (IOException | RuntimeException e) {
                failed = true;
                throw e;
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            try {
                int n = in.read(b, off, len);
                ended |= n < 0;
                return n;
            } catch (IOException | RuntimeException e) {
                failed = true;
                throw e;
            }
        }

        @Override
        public long skip(long n) throws IOException {
            try {
                return in.skip(n);
            } catch (IOException | RuntimeException e) {
                failed = true;
                throw e;
            }
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                in.close();
            } catch (IOException | RuntimeException e) {
                failed = true;
            }
            if (!ended || failed) {
                settle();
                return;
            }
            boolean interrupted = Thread.interrupted();
            try {
                if (!client.completePendingCommand()) {
                    throw new Failure(message("ftp.error.reply", reply(client)));
                }
            } catch (Failure e) {
                throw e;
            } catch (IOException | RuntimeException e) {
                stale = true;
                throw e instanceof IOException io ? io : new IOException(RemoteText.detail(e), e);
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * The content of an upload: keeps its own failure, which Commons Net wraps in a {@link CopyStreamException}; a
     * {@link RuntimeException} of the source reaches Commons Net as an {@link IOException}, the only failure on which
     * it closes the data connection. The time since the last read returned is the time the server takes to take what
     * was read (the source's own waits are not counted).
     */
    private static final class SourceStream extends FilterInputStream {
        IOException failure;
        RuntimeException runtime;
        /** Set by the watchdog, which closed the data connection. */
        volatile boolean stalled;
        /** {@link System#nanoTime()} when the last read returned; 0 while reading or before the first read. */
        private volatile long sinceRead;

        SourceStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            sinceRead = 0;
            try {
                return in.read();
            } catch (IOException e) {
                failure = e;
                throw e;
            } catch (RuntimeException e) {
                runtime = e;
                throw new IOException(RemoteText.detail(e), e);
            } finally {
                sinceRead = System.nanoTime();
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            sinceRead = 0;
            try {
                return in.read(b, off, len);
            } catch (IOException e) {
                failure = e;
                throw e;
            } catch (RuntimeException e) {
                runtime = e;
                throw new IOException(RemoteText.detail(e), e);
            } finally {
                sinceRead = System.nanoTime();
            }
        }

        /** Whether what was read last has waited longer than this for the server to take it. */
        boolean stalledFor(int millis) {
            long since = sinceRead;
            return since != 0 && System.nanoTime() - since > TimeUnit.MILLISECONDS.toNanos(millis);
        }

        @Override
        public void close() {
            // closed by the caller, who opened it
        }
    }

    /** A control connection that can close its data connection from another thread (the upload watchdog). */
    private interface DataSockets {
        /** Closes the socket of the current or last data connection; nothing when there is none. */
        void closeData();
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                // closed anyway
            }
        }
    }

    /** Plain FTP, its data connection kept for the upload watchdog. */
    private static final class PlainClient extends FTPClient implements DataSockets {
        private volatile Socket data;

        @Override
        protected Socket _openDataConnection_(String command, String arg) throws IOException {
            Socket socket = super._openDataConnection_(command, arg);
            data = socket;
            return socket;
        }

        @Override
        public void closeData() {
            closeQuietly(data);
        }
    }

    /**
     * FTPS whose data connections are made TLS here, over plain sockets, under the name and port of the control
     * connection ({@link ControlPortSocket}): the JDK then offers to resume the control connection's TLS session
     * (vsftpd, FileZilla Server and others refuse a data connection that does not), and checks the certificate against
     * the server's name as for the control connection. The TLS sockets are always the client's side, also in active
     * mode (RFC 4217).
     */
    private static final class TlsClient extends FTPSClient implements DataSockets {
        private final SSLContext context;
        /** Whether the last data connection resumed the control connection's session (TLS 1.2: same id). */
        private volatile boolean resumed;
        /** The plain socket below the current or last data connection: closed, it fails a blocked TLS write. */
        private volatile Socket data;

        TlsClient(boolean implicit, SSLContext context) {
            super(implicit, context);
            this.context = context;
        }

        /** After PROT P (which installs TLS socket factories): the data sockets open plain, layered below. */
        void plainDataSockets() {
            setSocketFactory(null);
            setServerSocketFactory(null);
        }

        @Override
        protected Socket _openDataConnection_(String command, String arg) throws IOException {
            Socket plain = super._openDataConnection_(command, arg);
            if (plain == null || plain instanceof SSLSocket) {
                return plain;
            }
            data = plain;
            SSLSocket socket;
            try {
                socket = (SSLSocket) context.getSocketFactory().createSocket(
                        new ControlPortSocket(plain, getRemotePort()), _hostname_, getRemotePort(), true);
            } catch (IOException | RuntimeException e) {
                closeQuietly(plain); // not yet owned by a TLS socket
                throw e;
            }
            try {
                SSLParameters parameters = socket.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                socket.setSSLParameters(parameters);
                socket.setUseClientMode(true);
                socket.startHandshake();
                if (_socket_ instanceof SSLSocket control) {
                    resumed = Arrays.equals(socket.getSession().getId(), control.getSession().getId());
                }
                return socket;
            } catch (IOException | RuntimeException e) {
                try {
                    socket.close();
                } catch (IOException closing) {
                    e.addSuppressed(closing);
                }
                throw e;
            }
        }

        @Override
        public void closeData() {
            closeQuietly(data);
        }
    }
}

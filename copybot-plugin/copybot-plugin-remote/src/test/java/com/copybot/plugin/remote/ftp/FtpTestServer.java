package com.copybot.plugin.remote.ftp;

import org.apache.ftpserver.DataConnectionConfigurationFactory;
import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.command.Command;
import org.apache.ftpserver.command.CommandFactoryFactory;
import org.apache.ftpserver.ftplet.DefaultFtpReply;
import org.apache.ftpserver.ftplet.UserManager;
import org.apache.ftpserver.impl.DefaultFtpServer;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.ssl.SslConfiguration;
import org.apache.ftpserver.ssl.SslConfigurationFactory;
import org.apache.ftpserver.usermanager.ClearTextPasswordEncryptor;
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.WritePermission;

import org.apache.ftpserver.ftplet.DataConnection;
import org.apache.ftpserver.ftplet.FtpFile;

import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** An embedded FTP / FTPS server (Apache FtpServer) on 127.0.0.1, serving a folder to one user. */
final class FtpTestServer implements AutoCloseable {

    static final String USER = "alice";
    static final String PASSWORD = "ftp-Secret-42";
    static final String STORE_PASSWORD = "changeit";

    enum Tls { NONE, EXPLICIT, IMPLICIT }

    /** A command the server does not know (502). */
    static final Command NOT_IMPLEMENTED = (session, context, request) -> {
        session.resetState();
        session.write(new DefaultFtpReply(502, "Command not implemented."));
    };

    /** FEAT without MLST nor MFMT: a server listing by LIST, its dates read by MDTM and never written. */
    static final Command OLD_FEAT = (session, context, request) -> {
        session.resetState();
        session.write(new DefaultFtpReply(211, "Extensions supported\n SIZE\n MDTM\n UTF8\nEnd"));
    };

    /** A command answering this code (450: busy, 502: not implemented...). */
    static Command answering(int code, String text) {
        return (session, context, request) -> {
            session.resetState();
            session.write(new DefaultFtpReply(code, text));
        };
    }

    /** FEAT naming these features only. */
    static Command feat(String... features) {
        return answering(211, "Extensions supported\n " + String.join("\n ", features) + "\nEnd");
    }

    /** LIST giving this text, whatever the folder (CR LF line ends). */
    static Command listing(String text) {
        return (session, context, request) -> {
            session.resetState();
            session.write(new DefaultFtpReply(150, "Here comes the listing."));
            try {
                DataConnection data = session.getDataConnection().openConnection();
                data.transferToClient(session.getFtpletSession(), text);
            } catch (Exception e) {
                throw new IOException(e);
            } finally {
                session.getDataConnection().closeDataConnection();
            }
            session.write(new DefaultFtpReply(226, "Done."));
        };
    }

    /**
     * LIST of the current folder in the Unix format, as vsftpd gives it: names starting with a dot only with
     * {@code -a}.
     *
     * @param refusesOptions any option is refused (501), as by servers that take no option
     */
    static Command unixList(boolean refusesOptions) {
        return (session, context, request) -> {
            session.resetState();
            String argument = request.getArgument() == null ? "" : request.getArgument().strip();
            if (refusesOptions && argument.startsWith("-")) {
                session.getDataConnection().closeDataConnection();
                session.write(new DefaultFtpReply(501, "No options here."));
                return;
            }
            boolean all = refusesOptions || argument.contains("-a");
            DateTimeFormatter day = DateTimeFormatter.ofPattern("MMM dd  yyyy", Locale.ROOT)
                    .withZone(ZoneOffset.UTC);
            StringBuilder text = new StringBuilder();
            for (FtpFile file : session.getFileSystemView().getWorkingDirectory().listFiles()) {
                if (!all && file.getName().startsWith(".")) {
                    continue;
                }
                text.append(file.isDirectory() ? "drwxr-xr-x" : "-rw-r--r--").append("   1 alice users ")
                        .append(file.isDirectory() ? 0 : file.getSize()).append(' ')
                        .append(day.format(Instant.ofEpochMilli(file.getLastModified()))).append(' ')
                        .append(file.getName()).append("\r\n");
            }
            session.write(new DefaultFtpReply(150, "Here comes the listing."));
            try {
                DataConnection data = session.getDataConnection().openConnection();
                data.transferToClient(session.getFtpletSession(), text.toString());
            } catch (Exception e) {
                throw new IOException(e);
            } finally {
                session.getDataConnection().closeDataConnection();
            }
            session.write(new DefaultFtpReply(226, "Done."));
        };
    }

    /** STOR whose data connection is opened, then never read nor answered: a server that stalls. */
    static final Command STALLED_STOR = (session, context, request) -> {
        session.write(new DefaultFtpReply(150, "Send it."));
        try {
            session.getDataConnection().openConnection();
        } catch (Exception e) {
            throw new IOException(e);
        }
    };

    private final FtpServer server;
    private final int port;

    private FtpTestServer(FtpServer server, int port) {
        this.server = server;
        this.port = port;
    }

    static FtpTestServer start(Path root) throws Exception {
        return start(root, Tls.NONE, null, null, Map.of());
    }

    /**
     * @param keystore a PKCS12 key store ({@link #keystore}) for TLS
     * @param protocol the only TLS protocol the server accepts; null: its defaults
     * @param commands commands replaced (by name)
     */
    static FtpTestServer start(Path root, Tls tls, Path keystore, String protocol, Map<String, Command> commands)
            throws Exception {
        return start(root, tls, keystore, null, protocol, commands);
    }

    /**
     * @param dataKeystore the key store of the data connections, with a TLS context of their own (the control
     *                     connection's session cannot be resumed there); null: the control connection's
     */
    static FtpTestServer start(Path root, Tls tls, Path keystore, Path dataKeystore, String protocol,
                               Map<String, Command> commands) throws Exception {
        FtpServerFactory factory = new FtpServerFactory();
        ListenerFactory listener = new ListenerFactory();
        listener.setServerAddress("127.0.0.1");
        listener.setPort(0);
        DataConnectionConfigurationFactory data = new DataConnectionConfigurationFactory();
        data.setPassiveAddress("127.0.0.1");
        if (tls != Tls.NONE) {
            SslConfiguration configuration = ssl(keystore, protocol);
            listener.setSslConfiguration(configuration);
            listener.setImplicitSsl(tls == Tls.IMPLICIT);
            data.setSslConfiguration(dataKeystore == null ? configuration : ssl(dataKeystore, protocol));
        }
        listener.setDataConnectionConfiguration(data.createDataConnectionConfiguration());
        factory.addListener("default", listener.createListener());

        CommandFactoryFactory commandFactory = new CommandFactoryFactory();
        commands.forEach(commandFactory::addCommand);
        factory.setCommandFactory(commandFactory.createCommandFactory());

        PropertiesUserManagerFactory users = new PropertiesUserManagerFactory();
        users.setPasswordEncryptor(new ClearTextPasswordEncryptor());
        UserManager userManager = users.createUserManager();
        BaseUser user = new BaseUser();
        user.setName(USER);
        user.setPassword(PASSWORD);
        user.setHomeDirectory(root.toString());
        user.setAuthorities(List.of(new WritePermission()));
        userManager.save(user);
        factory.setUserManager(userManager);

        FtpServer server = factory.createServer();
        server.start();
        int port = ((DefaultFtpServer) server).getListener("default").getPort();
        return new FtpTestServer(server, port);
    }

    /** A TLS configuration of its own (its own TLS context, so its own sessions). */
    private static SslConfiguration ssl(Path keystore, String protocol) {
        SslConfigurationFactory ssl = new SslConfigurationFactory();
        ssl.setKeystoreFile(keystore.toFile());
        ssl.setKeystorePassword(STORE_PASSWORD);
        ssl.setKeystoreType("PKCS12");
        if (protocol != null) {
            ssl.setSslProtocol(protocol);
        }
        return ssl.createSslConfiguration();
    }

    int port() {
        return port;
    }

    @Override
    public void close() {
        server.stop();
    }

    /** A PKCS12 key store holding a new self-signed EC key, its certificate naming {@code san} (keytool). */
    static Path keystore(Path folder, String name, String san) throws Exception {
        return keystore(folder, name, san, null);
    }

    /** @param startDate keytool's -startdate (e.g. "2020/01/01" for a certificate long expired); null: now */
    static Path keystore(Path folder, String name, String san, String startDate) throws Exception {
        Path store = folder.resolve(name + ".p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        List<String> command = new java.util.ArrayList<>(List.of(keytool, "-genkeypair", "-alias", "ftp",
                "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=Copybot test", "-ext", "SAN=" + san,
                "-validity", "2", "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", STORE_PASSWORD,
                "-keypass", STORE_PASSWORD));
        if (startDate != null) {
            command.addAll(List.of("-startdate", startDate));
        }
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0 || !Files.exists(store)) {
            throw new IllegalStateException("keytool failed: " + output);
        }
        return store;
    }

    /** Trusts the certificates of these key stores, and nothing else. */
    static X509ExtendedTrustManager trusting(Path... keystores) throws Exception {
        KeyStore trusted = KeyStore.getInstance(KeyStore.getDefaultType());
        trusted.load(null, null);
        for (int i = 0; i < keystores.length; i++) {
            trusted.setCertificateEntry("ftp" + i, certificate(keystores[i]));
        }
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trusted);
        return (X509ExtendedTrustManager) factory.getTrustManagers()[0];
    }

    /** The certificate of a key store made by {@link #keystore}. */
    static X509Certificate certificate(Path keystore) throws Exception {
        KeyStore source = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            source.load(in, STORE_PASSWORD.toCharArray());
        }
        return (X509Certificate) source.getCertificate("ftp");
    }
}

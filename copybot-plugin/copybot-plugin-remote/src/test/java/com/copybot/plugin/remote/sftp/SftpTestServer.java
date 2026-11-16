package com.copybot.plugin.remote.sftp;

import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/** An SFTP server of Apache MINA SSHD on 127.0.0.1, serving a folder as its root ("/"). */
final class SftpTestServer implements AutoCloseable {

    static final String USER = "alice";
    static final String PASSWORD = "s3cr3t-Pa55";

    final SshServer sshd;
    final KeyPair hostKey;

    /** The public keys accepted for {@link #USER}, besides the password. */
    volatile List<PublicKey> authorizedKeys = List.of();

    private SftpTestServer(SshServer sshd, KeyPair hostKey) {
        this.sshd = sshd;
        this.hostKey = hostKey;
    }

    static SftpTestServer start(Path root, KeyPair hostKey) throws IOException {
        return start(root, hostKey, sshd -> { });
    }

    static SftpTestServer start(Path root, KeyPair hostKey, Consumer<SshServer> customize) throws IOException {
        SshServer sshd = SshServer.setUpDefaultServer();
        SftpTestServer server = new SftpTestServer(sshd, hostKey);
        sshd.setHost("127.0.0.1");
        sshd.setPort(0);
        sshd.setKeyPairProvider(KeyPairProvider.wrap(hostKey));
        sshd.setPasswordAuthenticator((user, password, session) -> USER.equals(user) && PASSWORD.equals(password));
        sshd.setPublickeyAuthenticator((user, key, session) -> USER.equals(user)
                && server.authorizedKeys.stream().anyMatch(allowed -> KeyUtils.compareKeys(allowed, key)));
        sshd.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        sshd.setFileSystemFactory(new VirtualFileSystemFactory(root));
        customize.accept(sshd);
        sshd.start();
        return server;
    }

    int port() {
        return sshd.getPort();
    }

    /** "[127.0.0.1]:port", as known_hosts names the server. */
    String knownHostsName() {
        return "[127.0.0.1]:" + port();
    }

    static KeyPair ecKey() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(256);
        return generator.generateKeyPair();
    }

    static KeyPair rsaKey() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    /** "SHA256:..." of a public key, as OpenSSH shows it. */
    static String fingerprint(PublicKey key) {
        return KeyUtils.getFingerPrint(BuiltinDigests.sha256, key);
    }

    /** The line of known_hosts of a key: "name type base64". */
    static String knownHostsLine(String name, PublicKey key) {
        return name + " " + PublicKeyEntry.toString(key);
    }

    /** A public key from its SSH blob (a key pair made by JSch). */
    static PublicKey publicKey(byte[] blob) throws GeneralSecurityException, IOException {
        return new ByteArrayBuffer(blob).getRawPublicKey();
    }

    @Override
    public void close() throws IOException {
        sshd.stop();
    }

    static {
        // On Windows, the accept a stopped server leaves pending ends later, on a thread of the JDK, by handing a task
        // to the server's threads, shut down by then: MINA refuses it, and the thread prints the refusal. Harmless,
        // and only that refusal is kept out of the output of the tests.
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
            if (isEndOfAcceptRefused(e)) {
                return;
            }
            if (previous != null) {
                previous.uncaughtException(thread, e);
            } else {
                System.err.print("Exception in thread \"" + thread.getName() + "\" ");
                e.printStackTrace();
            }
        });
    }

    private static boolean isEndOfAcceptRefused(Throwable e) {
        return e instanceof IllegalStateException && "Executor has been shut down".equals(e.getMessage())
                && Arrays.stream(e.getStackTrace()).anyMatch(frame -> frame.getClassName().contains("AcceptTask"));
    }
}

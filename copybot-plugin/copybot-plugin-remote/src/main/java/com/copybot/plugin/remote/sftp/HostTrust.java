package com.copybot.plugin.remote.sftp;

import com.copybot.plugin.api.PluginFolders;
import com.copybot.plugin.api.UserConfirmation;
import com.copybot.plugin.api.definition.IPlugin;
import com.copybot.plugin.remote.core.RemoteText;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Whether the key of an SFTP server is trusted (spec remote §6), for one step: a key recorded in the plugin's
 * {@code known_hosts} is trusted; a key marked revoked there, or another key of a recorded server, is refused without
 * asking (the message gives the fingerprints and the file to correct); the key of a server not recorded is put to the user
 * ({@link UserConfirmation#ask}), recorded on yes.
 * <p>
 * The connections of all the steps to one server check it one at a time, the file read again once their turn comes:
 * the threads waiting while the user answers find the key recorded (yes), or this step's no, remembered for the rest
 * of its run, so that N connections ask once.
 */
final class HostTrust {

    enum Verdict { TRUSTED, UNKNOWN, CHANGED, REVOKED }

    /** One check at a time per server and file, across the steps of the plugin. */
    private static final ConcurrentHashMap<String, ReentrantLock> SERVER_LOCKS = new ConcurrentHashMap<>();

    private final IPlugin plugin;

    /** The keys this step's user did not trust: "name fingerprint". */
    private final Set<String> declined = ConcurrentHashMap.newKeySet();

    private volatile KnownHosts knownHosts;

    HostTrust(IPlugin plugin) {
        this.plugin = plugin;
    }

    /** {@code <plugin data folder>/known_hosts}. */
    KnownHosts knownHosts() throws IOException {
        KnownHosts current = knownHosts;
        if (current == null) {
            current = new KnownHosts(PluginFolders.dataDirectory("remote").resolve("known_hosts"));
            knownHosts = current;
        }
        return current;
    }

    /** What the file says of this key of the server named {@code name} ({@link KnownHosts#name}). */
    Verdict verdict(String name, byte[] blob) throws IOException {
        if (knownHosts().revoked(blob)) {
            return Verdict.REVOKED; // even when recorded, as OpenSSH
        }
        List<KnownHosts.Key> keys = knownHosts().keys(name);
        if (keys.stream().anyMatch(key -> KnownHosts.same(key, blob))) {
            return Verdict.TRUSTED;
        }
        return keys.isEmpty() ? Verdict.UNKNOWN : Verdict.CHANGED;
    }

    /** The keys of the server recorded in the file, for {@link HostKeys#getHostKey}. */
    List<KnownHosts.Key> recorded(String name) throws IOException {
        return knownHosts().keys(name);
    }

    /**
     * Once a connection stopped on a key not recorded: when the server is still not recorded, asks the user, and
     * records the key on yes; the caller connects again.
     *
     * @param server the server as the user knows it: "host" or "host:port"
     * @throws IOException not trusted (now or before by this step), the key changed meanwhile, the file not written,
     *                     interrupted while waiting for the turn (an {@link InterruptedIOException})
     */
    void approve(String name, String server, byte[] blob) throws IOException {
        String type = KnownHosts.type(blob);
        String fingerprint = KnownHosts.fingerprint(blob);
        KnownHosts file = knownHosts();
        ReentrantLock lock = SERVER_LOCKS.computeIfAbsent(file.file() + " " + name, key -> new ReentrantLock());
        try {
            lock.lockInterruptibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException(message("sftp.error.not-trusted", server, type, fingerprint));
        }
        try {
            String answer = name + " " + fingerprint;
            if (declined.contains(answer)) {
                throw new IOException(message("sftp.error.not-trusted", server, type, fingerprint));
            }
            switch (verdict(name, blob)) {
                case TRUSTED -> {
                    return; // trusted meanwhile (another connection asked)
                }
                case CHANGED -> throw changed(name, server, blob);
                case REVOKED -> throw revoked(server, blob);
                case UNKNOWN -> { }
            }
            if (!UserConfirmation.ask(message("sftp.question", server, type, fingerprint))) {
                declined.add(answer);
                throw new IOException(message("sftp.error.not-trusted", server, type, fingerprint));
            }
            try {
                file.add(name, blob);
            } catch (IOException e) {
                throw new IOException(message("sftp.error.known-hosts", file.file(), RemoteText.detail(e)), e);
            }
        } finally {
            lock.unlock();
        }
    }

    /** The failure of a server whose key is not the recorded one: both fingerprints and the file to correct. */
    IOException changed(String name, String server, byte[] blob) throws IOException {
        String recorded = recorded(name).stream().map(key -> key.type() + " " + key.fingerprint())
                .collect(Collectors.joining(", "));
        return new IOException(message("sftp.error.changed", server, recorded, KnownHosts.type(blob),
                KnownHosts.fingerprint(blob), knownHosts().file()));
    }

    /** The failure of a server whose key is marked revoked in the file. */
    IOException revoked(String server, byte[] blob) throws IOException {
        return new IOException(message("sftp.error.revoked", server, KnownHosts.type(blob),
                KnownHosts.fingerprint(blob), knownHosts().file()));
    }

    private String message(String key, Object... args) {
        return RemoteText.message(plugin, key, args);
    }
}

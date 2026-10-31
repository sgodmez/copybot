package com.copybot.plugin.remote.ftp;

import com.copybot.plugin.api.PluginFolders;
import com.copybot.plugin.api.UserConfirmation;
import com.copybot.plugin.api.definition.IPlugin;
import com.copybot.plugin.remote.core.RemoteText;

import javax.security.auth.x500.X500Principal;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Whether an FTPS certificate this computer does not trust is trusted all the same (spec remote §6), for one step: a
 * certificate whose public key is recorded for the server in the plugin's {@code ftps_trust} ({@link FtpsTrustFile})
 * is trusted, even expired or naming another server; another key of a recorded server is refused without asking
 * (the message gives both fingerprints and the file to correct); the certificate of a server not recorded is put to
 * the user ({@link UserConfirmation#ask}), recorded on yes.
 * <p>
 * The connections of all the steps to one server check it one at a time, the file read again once their turn comes:
 * the threads waiting while the user answers find the key recorded (yes), or this step's no, remembered for the rest
 * of its run, so that N connections ask once.
 */
final class CertificateTrust {

    enum Verdict { TRUSTED, UNKNOWN, CHANGED }

    /** What a server's certificate shows, for the question and the messages. */
    record Offered(String pin, String subject, String issuer, Instant notBefore, Instant notAfter,
                   List<String> names, boolean nameMatches) {
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    /** One check at a time per server and file, across the steps of the plugin. */
    private static final ConcurrentHashMap<String, ReentrantLock> SERVER_LOCKS = new ConcurrentHashMap<>();

    private final IPlugin plugin;

    /** The certificates this step's user did not trust: "key fingerprint". */
    private final Set<String> declined = ConcurrentHashMap.newKeySet();

    private volatile FtpsTrustFile file;

    CertificateTrust(IPlugin plugin) {
        this.plugin = plugin;
    }

    /** {@code <plugin data folder>/ftps_trust}. */
    FtpsTrustFile file() throws IOException {
        FtpsTrustFile current = file;
        if (current == null) {
            current = new FtpsTrustFile(PluginFolders.dataDirectory("remote").resolve("ftps_trust"));
            file = current;
        }
        return current;
    }

    /** What the file says of this public key of the server {@code key} ({@link FtpsTrustFile#key}). */
    Verdict verdict(String key, String pin) throws IOException {
        List<String> pins = file().pins(key);
        if (pins.contains(pin)) {
            return Verdict.TRUSTED;
        }
        return pins.isEmpty() ? Verdict.UNKNOWN : Verdict.CHANGED;
    }

    /**
     * Once a connection stopped on a certificate not recorded: when the server is still not recorded, asks the user,
     * and records the key on yes; the caller connects again.
     *
     * @param server the server as the user knows it: "host" or "host:port"
     * @throws IOException not trusted (now or before by this step), the key changed meanwhile, the file not written,
     *                     interrupted while waiting for the turn (an {@link InterruptedIOException})
     */
    void approve(String key, String server, String host, Offered offered) throws IOException {
        FtpsTrustFile trustFile = file();
        ReentrantLock lock = SERVER_LOCKS.computeIfAbsent(trustFile.file() + " " + key, k -> new ReentrantLock());
        try {
            lock.lockInterruptibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException(notTrusted(server, offered).getMessage());
        }
        try {
            String answer = key + " " + offered.pin();
            if (declined.contains(answer)) {
                throw notTrusted(server, offered);
            }
            switch (verdict(key, offered.pin())) {
                case TRUSTED -> {
                    return; // trusted meanwhile (another connection asked)
                }
                case CHANGED -> throw changed(key, server, offered.pin());
                case UNKNOWN -> { }
            }
            String otherName = offered.nameMatches() ? ""
                    : " " + message("ftp.question.other-name", String.join(", ", offered.names()), host);
            if (!UserConfirmation.ask(message("ftp.question", server, offered.subject(), offered.issuer(),
                    DAY.format(offered.notBefore()), DAY.format(offered.notAfter()), offered.pin(), otherName))) {
                declined.add(answer);
                throw notTrusted(server, offered);
            }
            try {
                trustFile.add(key, offered.pin());
            } catch (IOException e) {
                throw new IOException(message("ftp.error.trust-file", trustFile.file(), RemoteText.detail(e)), e);
            }
        } finally {
            lock.unlock();
        }
    }

    /** The failure of a server whose certificate was not accepted. */
    IOException notTrusted(String server, Offered offered) {
        return new IOException(message("ftp.error.not-trusted", server, offered.pin()));
    }

    /** The failure of a server whose key is not the recorded one: both fingerprints and the file to correct. */
    IOException changed(String key, String server, String pin) throws IOException {
        return new IOException(message("ftp.error.changed", server, String.join(", ", file().pins(key)), pin,
                file().file()));
    }

    /** The message of a data connection's certificate refused (data connections never ask). */
    String dataRefused(String server, String pin) {
        return message("ftp.error.data-certificate", server, pin);
    }

    /** The message of a file that cannot be read. */
    String fileFailure(IOException e) {
        FtpsTrustFile current = file;
        return message("ftp.error.trust-file", current == null ? "ftps_trust" : current.file(), RemoteText.detail(e));
    }

    private String message(String key, Object... args) {
        return RemoteText.message(plugin, key, args);
    }

    // ---- certificates

    /** What a certificate shows, its names checked against the host the user configured. */
    static Offered offered(X509Certificate certificate, String host) {
        List<String> dns = new ArrayList<>();
        List<String> addresses = new ArrayList<>();
        try {
            Collection<List<?>> alternatives = certificate.getSubjectAlternativeNames();
            if (alternatives != null) {
                for (List<?> name : alternatives) {
                    if (name.size() >= 2 && name.get(1) instanceof String value) {
                        if (Integer.valueOf(2).equals(name.get(0))) {
                            dns.add(value);
                        } else if (Integer.valueOf(7).equals(name.get(0))) {
                            addresses.add(value);
                        }
                    }
                }
            }
        } catch (CertificateParsingException e) {
            // no names to match then
        }
        String commonName = commonName(certificate.getSubjectX500Principal());
        List<String> names = new ArrayList<>(dns);
        names.addAll(addresses);
        if (names.isEmpty() && commonName != null) {
            names.add(commonName);
        }
        return new Offered(pin(certificate), shown(certificate.getSubjectX500Principal().getName()),
                shown(certificate.getIssuerX500Principal().getName()), certificate.getNotBefore().toInstant(),
                certificate.getNotAfter().toInstant(), names.stream().map(CertificateTrust::shown).toList(),
                nameMatches(host, dns, addresses, commonName));
    }

    /** At most this many characters of a text of the certificate in the question. */
    static final int SHOWN_LENGTH = 200;

    /**
     * A text of the certificate (written by whoever made it) as the question may show it: control characters, line
     * breaks and invisible formatting (bidirectional overrides...) removed, at most {@link #SHOWN_LENGTH}
     * characters.
     */
    static String shown(String text) {
        StringBuilder shown = new StringBuilder();
        text.codePoints().filter(c -> !Character.isISOControl(c) && Character.getType(c) != Character.FORMAT
                        && Character.getType(c) != Character.LINE_SEPARATOR
                        && Character.getType(c) != Character.PARAGRAPH_SEPARATOR)
                .forEach(shown::appendCodePoint);
        if (shown.length() > SHOWN_LENGTH) {
            int end = SHOWN_LENGTH - 3;
            if (Character.isHighSurrogate(shown.charAt(end - 1))) {
                end--;
            }
            shown.setLength(end);
            shown.append("...");
        }
        return shown.toString();
    }

    /** "SHA256:<base64 without padding>" of the certificate's public key (its SubjectPublicKeyInfo). */
    static String pin(X509Certificate certificate) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getPublicKey().getEncoded());
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // every JDK has SHA-256
        }
    }

    /**
     * Whether the certificate names the host as HTTPS checks it (RFC 2818 / 6125): an address against the IP
     * addresses of the certificate; a name against its DNS names ("*" alone as the first label matching one label),
     * or its common name when it has none.
     */
    static boolean nameMatches(String host, List<String> dns, List<String> addresses, String commonName) {
        InetAddress literal = literal(host);
        if (literal != null) {
            return addresses.stream().map(CertificateTrust::literal).anyMatch(literal::equals);
        }
        String name = host.toLowerCase(Locale.ROOT);
        if (name.endsWith(".")) {
            name = name.substring(0, name.length() - 1);
        }
        List<String> candidates = dns.isEmpty() ? (commonName == null ? List.of() : List.of(commonName)) : dns;
        for (String candidate : candidates) {
            String pattern = candidate.toLowerCase(Locale.ROOT);
            if (pattern.equals(name)) {
                return true;
            }
            if (pattern.startsWith("*.") && pattern.indexOf('*', 1) < 0) {
                int dot = name.indexOf('.');
                if (dot > 0 && name.substring(dot).equals(pattern.substring(1))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The address written in the name, never looked up; null when it is not an address. */
    private static InetAddress literal(String name) {
        try {
            String bare = name.startsWith("[") && name.endsWith("]") ? name.substring(1, name.length() - 1) : name;
            return InetAddress.ofLiteral(bare);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The last CN of a name (RFC 2253, read here: java.naming is not in the image); null when there is none. */
    private static String commonName(X500Principal principal) {
        String found = null;
        for (String part : principal.getName(X500Principal.RFC2253).split("(?<!\\\\),")) {
            if (part.regionMatches(true, 0, "CN=", 0, 3)) {
                found = part.substring(3).replace("\\", "");
            }
        }
        return found;
    }
}

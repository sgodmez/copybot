package com.copybot.plugin.remote.sftp;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The plugin's {@code known_hosts} file (spec remote §6), in the OpenSSH format: one line per key,
 * {@code <names> <type> <base64 key>}, read again at each check (another run, or the user, may have changed it).
 * Names are plain ({@code host}, {@code [host]:port} when the port is not 22, comma separated) or hashed
 * ({@code |1|salt|hash}); wildcards and the lines {@code @cert-authority} are ignored. A key on a line {@code @revoked}
 * is refused whatever the names of that line.
 * <p>
 * The file is read as UTF-8, or as ISO-8859-1 when it is not UTF-8 (a comment written by an ANSI editor): names and
 * keys are ASCII either way. A line is added after the old bytes, kept as they are, by writing a new file and moving it
 * over the old one, so the file is never seen half written.
 */
final class KnownHosts {

    /** A key recorded for a server. */
    record Key(String type, byte[] blob) {

        String fingerprint() {
            return KnownHosts.fingerprint(blob);
        }
    }

    /** One writer per file in the plugin (the lines of two servers trusted at once are both kept). */
    private static final ConcurrentHashMap<Path, ReentrantLock> WRITE_LOCKS = new ConcurrentHashMap<>();

    private final Path file;

    KnownHosts(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    Path file() {
        return file;
    }

    /** The name of a server in the file: "host", or "[host]:port" when the port is not 22; lower case. */
    static String name(String host, int port) {
        String lower = host.toLowerCase(Locale.ROOT);
        return port == 22 ? lower : "[" + lower + "]:" + port;
    }

    /** The keys recorded for a server, in the order of the file; empty when the file does not exist. */
    List<Key> keys(String name) throws IOException {
        List<Key> keys = new ArrayList<>();
        for (String line : lines()) {
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("@")) {
                continue;
            }
            String[] fields = line.split("\\s+");
            if (fields.length >= 3 && matches(fields[0], name)) {
                Key key = key(fields[1], fields[2]);
                if (key != null) {
                    keys.add(key);
                }
            }
        }
        return keys;
    }

    /** Whether the key is on a line {@code @revoked}, whatever its names (mostly "*"). */
    boolean revoked(byte[] blob) throws IOException {
        for (String line : lines()) {
            String[] fields = line.split("\\s+");
            if (fields.length >= 4 && fields[0].equals("@revoked")) {
                Key key = key(fields[2], fields[3]);
                if (key != null && same(key, blob)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The lines of the file, stripped; none when the file does not exist. */
    private List<String> lines() throws IOException {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            return List.of();
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            text = new String(bytes, StandardCharsets.ISO_8859_1); // not UTF-8: only a comment can be other than ASCII
        }
        if (!text.isEmpty() && text.charAt(0) == (char) 0xFEFF) {
            text = text.substring(1); // a byte order mark (an editor's "UTF-8 with BOM"), not part of the first name
        }
        return text.lines().map(String::strip).toList();
    }

    /** A key from its fields; null when it is not one (a line written by hand, ignored as OpenSSH ignores it). */
    private static Key key(String type, String base64) {
        try {
            return new Key(type, Base64.getDecoder().decode(base64));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Adds a key of a server at the end of the file (created with its folder when missing). */
    void add(String name, byte[] blob) throws IOException {
        byte[] line = (name + " " + type(blob) + " " + Base64.getEncoder().encodeToString(blob) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        ReentrantLock lock = WRITE_LOCKS.computeIfAbsent(file, f -> new ReentrantLock());
        lock.lock();
        try {
            Files.createDirectories(file.getParent());
            byte[] old;
            try {
                old = Files.readAllBytes(file);
            } catch (NoSuchFileException e) {
                old = new byte[0];
            }
            Path temp = Files.createTempFile(file.getParent(), ".known_hosts", ".tmp");
            try {
                ByteArrayOutputStream text = new ByteArrayOutputStream(old.length + line.length + 1);
                text.writeBytes(old); // kept as they are, whatever their encoding
                if (old.length > 0 && old[old.length - 1] != '\n') {
                    text.write('\n');
                }
                text.writeBytes(line);
                Files.write(temp, text.toByteArray());
                try {
                    Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        } finally {
            lock.unlock();
        }
    }

    /** Whether a field of names holds this name, plain or hashed. */
    private static boolean matches(String names, String name) {
        for (String pattern : names.split(",")) {
            if (pattern.startsWith("|1|")) {
                if (hashedMatches(pattern, name)) {
                    return true;
                }
            } else if (pattern.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /** "|1|base64(salt)|base64(HMAC-SHA1(salt, name))", as OpenSSH's HashKnownHosts writes it. */
    private static boolean hashedMatches(String pattern, String name) {
        String[] parts = pattern.split("\\|");
        if (parts.length != 4) {
            return false;
        }
        try {
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] hash = Base64.getDecoder().decode(parts[3]);
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(salt, "HmacSHA1"));
            return MessageDigest.isEqual(hash, mac.doFinal(name.getBytes(StandardCharsets.UTF_8)));
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            return false;
        }
    }

    /** The type of a key, the first string of its SSH blob: "ssh-ed25519", "ecdsa-sha2-nistp256", "ssh-rsa". */
    static String type(byte[] blob) {
        if (blob.length >= 4) {
            int length = ByteBuffer.wrap(blob, 0, 4).getInt();
            if (length > 0 && length <= blob.length - 4) {
                return new String(blob, 4, length, StandardCharsets.US_ASCII);
            }
        }
        return "unknown";
    }

    /** "SHA256:<base64 without padding>", as OpenSSH shows a key. */
    static String fingerprint(byte[] blob) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(blob);
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // every JDK has SHA-256
        }
    }

    /** Whether a recorded key is this one. */
    static boolean same(Key key, byte[] blob) {
        return Arrays.equals(key.blob(), blob);
    }
}

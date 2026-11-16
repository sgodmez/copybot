package com.copybot.plugin.remote.ftp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The plugin's {@code ftps_trust} file (spec remote §6): the FTPS certificates the user trusted though this computer
 * does not, one line per server, {@code host:port SHA256:<base64>}, the SHA-256 of the certificate's public key
 * (SubjectPublicKeyInfo), which a renewed certificate with the same key keeps. Lines starting with {@code #} and
 * blank lines are ignored; the file is read again at each check (the user may have removed a line). A line is added by
 * writing a new file and moving it over the old one, so the file is never seen half written.
 */
final class FtpsTrustFile {

    private static final String HEADER = "# Copybot: FTPS certificates trusted by the user, one per line:"
            + " host:port SHA256:<base64 of the SHA-256 of the public key>\n";

    /** One writer per file in the plugin (the lines of two servers trusted at once are both kept). */
    private static final ConcurrentHashMap<Path, ReentrantLock> WRITE_LOCKS = new ConcurrentHashMap<>();

    private final Path file;

    FtpsTrustFile(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    Path file() {
        return file;
    }

    /** The name of a server in the file: "host:port", "[host]:port" for an IPv6 address; lower case. */
    static String key(String host, int port) {
        String lower = host.toLowerCase(Locale.ROOT);
        return (lower.indexOf(':') >= 0 ? "[" + lower + "]" : lower) + ":" + port;
    }

    /** The fingerprints recorded for a server, in the order of the file; none when the file does not exist. */
    List<String> pins(String key) throws IOException {
        List<String> pins = new ArrayList<>();
        for (String line : lines()) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] fields = line.split("\\s+");
            if (fields.length >= 2 && fields[0].equalsIgnoreCase(key)) {
                pins.add(fields[1]);
            }
        }
        return pins;
    }

    /** The lines of the file, stripped; none when the file does not exist. */
    private List<String> lines() throws IOException {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            return List.of();
        }
        int start = bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF
                ? 3 : 0; // a byte order mark (an editor's "UTF-8 with BOM"), not part of the first name
        // ASCII but for comments: decoded leniently, a stray byte never hides a line
        String text = new String(bytes, start, bytes.length - start, StandardCharsets.ISO_8859_1);
        return text.lines().map(String::strip).toList();
    }

    /** Adds a server's fingerprint at the end of the file (created with its folder and a header when missing). */
    void add(String key, String pin) throws IOException {
        byte[] line = (key + " " + pin + "\n").getBytes(StandardCharsets.US_ASCII);
        ReentrantLock lock = WRITE_LOCKS.computeIfAbsent(file, f -> new ReentrantLock());
        lock.lock();
        try {
            Files.createDirectories(file.getParent());
            byte[] old;
            try {
                old = Files.readAllBytes(file);
            } catch (NoSuchFileException e) {
                old = HEADER.getBytes(StandardCharsets.US_ASCII);
            }
            Path temp = Files.createTempFile(file.getParent(), ".ftps_trust", ".tmp");
            try {
                ByteArrayOutputStream text = new ByteArrayOutputStream(old.length + line.length + 1);
                text.writeBytes(old); // kept as they are
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
}

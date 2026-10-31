package com.copybot.plugin.remote.sftp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The plugin's known_hosts read and written as OpenSSH writes it. */
public class KnownHostsTest {

    @TempDir
    Path temp;

    /** An SSH blob: the type, then some key bytes. */
    private static byte[] blob(String type, int seed) {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        byte[] data = new byte[32];
        Arrays.fill(data, (byte) seed);
        return ByteBuffer.allocate(8 + name.length + data.length)
                .putInt(name.length).put(name).putInt(data.length).put(data).array();
    }

    private static String line(String names, byte[] blob) {
        return names + " " + KnownHosts.type(blob) + " " + Base64.getEncoder().encodeToString(blob);
    }

    /** "|1|salt|hash", as {@code ssh-keygen -H} writes a name. */
    private static String hashed(String name) throws Exception {
        byte[] salt = new byte[20];
        Arrays.fill(salt, (byte) 7);
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(salt, "HmacSHA1"));
        byte[] hash = mac.doFinal(name.getBytes(StandardCharsets.UTF_8));
        return "|1|" + Base64.getEncoder().encodeToString(salt) + "|" + Base64.getEncoder().encodeToString(hash);
    }

    private KnownHosts file(String... lines) throws IOException {
        Path file = temp.resolve("known_hosts");
        Files.writeString(file, String.join("\n", lines) + "\n");
        return new KnownHosts(file);
    }

    @Test
    public void aMissingFileHasNoKey() throws IOException {
        assertEquals(List.of(), new KnownHosts(temp.resolve("none")).keys("nas"));
    }

    @Test
    public void hashedNamesAreMatched() throws Exception {
        byte[] key = blob("ssh-ed25519", 1);
        KnownHosts file = file(line(hashed("nas.local"), key), line(hashed("[nas.local]:2222"), blob("ssh-ed25519", 2)));
        List<KnownHosts.Key> keys = file.keys("nas.local");
        assertEquals(1, keys.size());
        assertTrue(KnownHosts.same(keys.get(0), key));
        assertEquals(1, file.keys("[nas.local]:2222").size());
        assertEquals(List.of(), file.keys("other"));
    }

    @Test
    public void twoKeyTypesOfOneServerAreBothRecorded() throws IOException {
        byte[] ed = blob("ssh-ed25519", 1);
        byte[] ec = blob("ecdsa-sha2-nistp256", 2);
        KnownHosts file = file(line("nas,192.168.1.2", ed), "# a comment", line("nas", ec));
        List<KnownHosts.Key> keys = file.keys("nas");
        assertEquals(List.of("ssh-ed25519", "ecdsa-sha2-nistp256"), keys.stream().map(KnownHosts.Key::type).toList());
        assertEquals(1, file.keys("192.168.1.2").size());
    }

    @Test
    public void markedLinesAreNotRecordedKeysButRevokedOnesAreKnown() throws IOException {
        byte[] authority = blob("ssh-ed25519", 1);
        byte[] revoked = blob("ssh-ed25519", 2);
        byte[] plain = blob("ssh-ed25519", 3);
        KnownHosts file = file("@cert-authority nas " + line("", authority).strip(),
                "@revoked * " + line("", revoked).strip(), line("nas", plain));
        List<KnownHosts.Key> keys = file.keys("nas");
        assertEquals(1, keys.size());
        assertTrue(KnownHosts.same(keys.get(0), plain));
        assertTrue(file.revoked(revoked), "a revoked key, whatever the names of its line");
        assertFalse(file.revoked(plain));
        assertFalse(file.revoked(authority));
    }

    @Test
    public void anUnreadableFileFails() throws IOException {
        Path folder = Files.createDirectories(temp.resolve("known_hosts")); // a folder where the file should be
        KnownHosts file = new KnownHosts(folder);
        assertThrows(IOException.class, () -> file.keys("nas"));
        assertThrows(IOException.class, () -> file.add("nas", blob("ssh-ed25519", 1)));
    }

    @Test
    public void aCommentNotInUtf8IsReadAndKeptAsItIs() throws IOException {
        byte[] key = blob("ssh-ed25519", 1);
        ByteArrayOutputStream old = new ByteArrayOutputStream();
        old.writeBytes("# serveur d'\u00e9t\u00e9\n".getBytes(StandardCharsets.ISO_8859_1)); // an ANSI editor
        old.writeBytes((line("nas", key) + "\n").getBytes(StandardCharsets.US_ASCII));
        Path path = temp.resolve("known_hosts");
        Files.write(path, old.toByteArray());
        KnownHosts file = new KnownHosts(path);
        assertEquals(1, file.keys("nas").size());

        byte[] other = blob("ecdsa-sha2-nistp256", 2);
        file.add("[nas]:2222", other);
        byte[] written = Files.readAllBytes(path);
        assertArrayEquals(old.toByteArray(), Arrays.copyOf(written, old.size()), "the old bytes are kept");
        assertEquals(line("[nas]:2222", other) + "\n",
                new String(written, old.size(), written.length - old.size(), StandardCharsets.UTF_8));
        assertEquals(1, file.keys("[nas]:2222").size());
    }

    @Test
    public void aByteOrderMarkBeforeTheFirstLineIsIgnored() throws IOException {
        byte[] key = blob("ssh-ed25519", 1);
        Path path = temp.resolve("known_hosts");
        // Notepad's "UTF-8 with BOM": EF BB BF before the first line
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.writeBytes(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        bytes.writeBytes((line("nas", key) + "\n").getBytes(StandardCharsets.US_ASCII));
        Files.write(path, bytes.toByteArray());
        KnownHosts file = new KnownHosts(path);
        List<KnownHosts.Key> keys = file.keys("nas");
        assertEquals(1, keys.size());
        assertTrue(KnownHosts.same(keys.get(0), key));
    }
}

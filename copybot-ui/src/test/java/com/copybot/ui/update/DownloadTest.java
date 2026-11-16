package com.copybot.ui.update;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

public class DownloadTest {

    private static final byte[] CONTENT = "the new Copybot".repeat(10_000).getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path dir;

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private final HttpClient client = UpdateCheck.httpClient();

    @BeforeEach
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/file", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, CONTENT.length);
            exchange.getResponseBody().write(CONTENT);
            exchange.close();
        });
        server.createContext("/stall", exchange -> {
            exchange.sendResponseHeaders(200, CONTENT.length);
            exchange.getResponseBody().write(CONTENT, 0, 10);
            exchange.getResponseBody().flush();
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                // the test is over
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    public void stop() {
        server.stop(0);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private Release.Asset asset(String path, long size, String sha256) {
        return new Release.Asset("copybot.zip", URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path),
                size, sha256);
    }

    private static void assertNoFile(Path dir) throws IOException {
        try (var files = Files.list(dir)) {
            assertEquals(0, files.count(), "nothing is left in the folder");
        }
    }

    @Test
    public void aFileWithTheRightDigestIsKept() throws Exception {
        AtomicLong progress = new AtomicLong();

        Path file = Download.fetch(client, asset("/file", CONTENT.length, sha256(CONTENT)), dir,
                progress::set, () -> false);

        assertEquals(dir.resolve("copybot.zip"), file);
        assertArrayEquals(CONTENT, Files.readAllBytes(file));
        assertEquals(CONTENT.length, progress.get());
    }

    @Test
    public void aWrongDigestLeavesNothing() throws Exception {
        UpdateException e = assertThrows(UpdateException.class, () -> Download.fetch(client,
                asset("/file", CONTENT.length, sha256("other".getBytes(StandardCharsets.UTF_8))), dir,
                n -> { }, () -> false));

        assertEquals("update.error.digest", e.key());
        assertNoFile(dir);
    }

    @Test
    public void anAssetWithoutDigestIsRefused() throws Exception {
        UpdateException e = assertThrows(UpdateException.class, () -> Download.fetch(client,
                asset("/file", CONTENT.length, null), dir, n -> { }, () -> false));

        assertEquals("update.error.no-digest", e.key());
        assertEquals(0, requests.get(), "no request needed");
        assertNoFile(dir);
    }

    @Test
    public void aWrongAnnouncedSizeLeavesNothing() throws Exception {
        for (long size : new long[]{CONTENT.length - 1, CONTENT.length + 1}) {
            UpdateException e = assertThrows(UpdateException.class, () -> Download.fetch(client,
                    asset("/file", size, sha256(CONTENT)), dir, n -> { }, () -> false));

            assertEquals("update.error.size", e.key());
            assertNoFile(dir);
        }
    }

    @Test
    public void aCancelledDownloadLeavesNothing() throws Exception {
        AtomicLong progress = new AtomicLong();

        UpdateException e = assertThrows(UpdateException.class, () -> Download.fetch(client,
                asset("/file", CONTENT.length, sha256(CONTENT)), dir, progress::set, () -> progress.get() > 0));

        assertEquals("update.error.cancelled", e.key());
        assertNoFile(dir);
    }

    @Test
    public void aMissingFileIsAnHttpError() throws Exception {
        UpdateException e = assertThrows(UpdateException.class, () -> Download.fetch(client,
                asset("/nothing", 1, sha256(CONTENT)), dir, n -> { }, () -> false));

        assertEquals("update.error.http", e.key());
        assertEquals(404, e.args()[0]);
        assertNoFile(dir);
    }

    @Test
    public void aStalledDownloadIsAbandoned() throws Exception {
        UpdateException e = assertThrows(UpdateException.class, () -> Download.fetch(client,
                asset("/stall", CONTENT.length, sha256(CONTENT)), dir, n -> { }, () -> false,
                Duration.ofSeconds(5), Duration.ofMillis(300)));

        assertEquals("update.error.stalled", e.key());
        assertNoFile(dir);
    }

    @Test
    public void aNameLeavingTheFolderIsRefused() throws Exception {
        Release.Asset escaping = new Release.Asset("../evil.zip", URI.create("http://127.0.0.1/x"), 1, "00");

        UpdateException e = assertThrows(UpdateException.class,
                () -> Download.fetch(client, escaping, dir, n -> { }, () -> false));

        assertEquals("update.error.unreadable", e.key());
    }
}

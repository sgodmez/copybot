package com.copybot.plugin.ffmpeg.ffmpeg;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A fake GitHub release on a local HTTP server: the files it serves under /download/latest/, the number of requests
 * for each. No network.
 */
final class FakeRelease implements AutoCloseable {

    /** The archive of the tests: a stable branch, as in the real release. */
    static final String ARCHIVE = "ffmpeg-n8.1-latest-win64-gpl-8.1.zip";

    private final HttpServer server;
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    private final Map<String, String> redirects = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();
    /**
     * When set, the archive stops after its first bytes and the answer hangs until it is counted down, then sends
     * the rest.
     */
    volatile CountDownLatch hang;
    private boolean closed;

    /** The downloader logs each download at INFO: kept out of the output of the tests (held: a logger is weak). */
    private static final java.util.logging.Logger DOWNLOADER_LOG =
            java.util.logging.Logger.getLogger(FfmpegDownloader.class.getName());

    static {
        DOWNLOADER_LOG.setLevel(java.util.logging.Level.WARNING);
    }

    FakeRelease() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    /** The folder of the release: what the downloader is given in place of the GitHub one. */
    URI release() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/download/latest/");
    }

    FakeRelease file(String name, byte[] content) {
        return at("/download/latest/" + name, content);
    }

    /** Serves the content at this path of the server. */
    FakeRelease at(String path, byte[] content) {
        files.put(path, content);
        return this;
    }

    FakeRelease checksums(String content) {
        return file("checksums.sha256", content.getBytes(StandardCharsets.UTF_8));
    }

    /** The name answers a redirect to another path of this server (GitHub redirects to its file storage). */
    FakeRelease redirect(String name, String path) {
        redirects.put("/download/latest/" + name, path);
        return this;
    }

    /** Serves the archive and a checksums.sha256 listing it (with other builds around, as the real one). */
    FakeRelease release(byte[] archive) {
        file(ARCHIVE, archive);
        return checksums(sha256(new byte[]{1}) + "  ffmpeg-master-latest-win64-gpl.zip\n"
                + sha256(archive) + "  " + ARCHIVE + "\n"
                + sha256(new byte[]{2}) + "  ffmpeg-n8.1-latest-win64-gpl-shared-8.1.zip\n"
                + sha256(new byte[]{3}) + "  ffmpeg-n8.1-latest-linux64-gpl-8.1.tar.xz\n");
    }

    int requests(String name) {
        AtomicInteger count = requests.get("/download/latest/" + name);
        return count == null ? 0 : count.get();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            requests.computeIfAbsent(path, p -> new AtomicInteger()).incrementAndGet();
            String redirect = redirects.get(path);
            if (redirect != null) {
                exchange.getResponseHeaders().add("Location", redirect);
                exchange.sendResponseHeaders(302, -1);
                return;
            }
            byte[] content = files.get(path);
            if (content == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            CountDownLatch latch = hang;
            exchange.sendResponseHeaders(200, content.length);
            try (OutputStream out = exchange.getResponseBody()) {
                if (latch != null && path.endsWith(".zip")) {
                    int first = Math.min(100, content.length);
                    out.write(content, 0, first);
                    out.flush();
                    try {
                        latch.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    out.write(content, first, content.length - first); // the rest, once let go
                    return;
                }
                out.write(content);
            }
        } catch (IOException e) {
            // the client went away
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        CountDownLatch latch = hang;
        if (latch != null) {
            latch.countDown();
        }
        server.stop(0);
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A zip of these entries (name, content; a name ending with / is a folder). */
    static byte[] zip(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                if (!entry.getKey().endsWith("/")) {
                    zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                }
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    /** A zip laid out as a BtbN build: one top folder holding bin/ffmpeg.exe and the licence. */
    static byte[] build() throws IOException {
        java.util.LinkedHashMap<String, String> entries = new java.util.LinkedHashMap<>();
        entries.put("ffmpeg-n8.1-latest-win64-gpl-8.1/", "");
        entries.put("ffmpeg-n8.1-latest-win64-gpl-8.1/LICENSE.txt", "GPL v3");
        entries.put("ffmpeg-n8.1-latest-win64-gpl-8.1/bin/ffmpeg.exe", "not really ffmpeg");
        entries.put("ffmpeg-n8.1-latest-win64-gpl-8.1/bin/ffprobe.exe", "not really ffprobe");
        return zip(entries);
    }
}

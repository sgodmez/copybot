package com.copybot.ui.update;

import com.copybot.ui.update.Release.Asset;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/** Downloads a release file and accepts it only if its size and SHA-256 are those GitHub announced. */
public final class Download {

    private static final Duration HEADERS_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration STALL_TIMEOUT = Duration.ofSeconds(60);

    private Download() {
    }

    /**
     * Downloads the asset into {@code dir} (the caller's folder, which exists) and checks it.
     *
     * @param progress receives the bytes received so far
     * @return {@code dir/<asset name>}; on any failure the partial file is deleted
     */
    public static Path fetch(HttpClient client, Asset asset, Path dir, LongConsumer progress,
            BooleanSupplier cancelled) throws UpdateException, InterruptedException {
        return fetch(client, asset, dir, progress, cancelled, HEADERS_TIMEOUT, STALL_TIMEOUT);
    }

    static Path fetch(HttpClient client, Asset asset, Path dir, LongConsumer progress, BooleanSupplier cancelled,
            Duration headersTimeout, Duration stallTimeout) throws UpdateException, InterruptedException {
        if (asset.sha256() == null) {
            throw new UpdateException("update.error.no-digest");
        }
        Path target = dir.resolve(asset.name()).normalize();
        if (!dir.normalize().equals(target.getParent())) {
            throw new UpdateException("update.error.unreadable"); // a name that would leave the folder
        }
        boolean done = false;
        try {
            copy(client, asset, target, progress, cancelled, headersTimeout, stallTimeout);
            done = true;
            return target;
        } finally {
            if (!done) {
                try {
                    Files.deleteIfExists(target);
                } catch (IOException e) {
                    // nothing more to do, the caller deletes its folder
                }
            }
        }
    }

    private static void copy(HttpClient client, Asset asset, Path target, LongConsumer progress,
            BooleanSupplier cancelled, Duration headersTimeout, Duration stallTimeout)
            throws UpdateException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(asset.url()).timeout(headersTimeout)
                .header("User-Agent", "Copybot").GET().build();
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // every Java has SHA-256
        }
        AtomicBoolean stalled = new AtomicBoolean();
        AtomicLong lastByte = new AtomicLong(System.nanoTime());
        Timer watchdog = new Timer("download-watchdog", true);
        try {
            if (cancelled.getAsBoolean()) {
                throw new UpdateException("update.error.cancelled");
            }
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                if (response.statusCode() != 200) {
                    throw new UpdateException("update.error.http", response.statusCode());
                }
                lastByte.set(System.nanoTime());
                watchdog.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        if (System.nanoTime() - lastByte.get() > stallTimeout.toNanos()
                                && stalled.compareAndSet(false, true)) {
                            try {
                                in.close(); // ends the blocked read with an exception
                            } catch (IOException e) {
                                // the read fails anyway
                            }
                        }
                    }
                }, 250, 250);
                long received = 0;
                try (OutputStream out = Files.newOutputStream(target)) {
                    byte[] buffer = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buffer)) >= 0) {
                        lastByte.set(System.nanoTime());
                        if (cancelled.getAsBoolean()) {
                            throw new UpdateException("update.error.cancelled");
                        }
                        received += n;
                        if (received > asset.size()) {
                            throw new UpdateException("update.error.size");
                        }
                        sha.update(buffer, 0, n);
                        out.write(buffer, 0, n);
                        progress.accept(received);
                    }
                }
                if (cancelled.getAsBoolean()) {
                    throw new UpdateException("update.error.cancelled");
                }
                if (received != asset.size()) {
                    throw new UpdateException("update.error.size");
                }
            }
            if (!HexFormat.of().formatHex(sha.digest()).equals(asset.sha256())) {
                throw new UpdateException("update.error.digest");
            }
        } catch (IOException e) {
            throw stalled.get() ? new UpdateException("update.error.stalled")
                    : new UpdateException("update.error.network", UpdateCheck.message(e));
        } finally {
            watchdog.cancel();
        }
    }
}

package com.copybot.ui.update;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class UpdateCheckTest {

    private HttpServer server;
    private final AtomicReference<String> accept = new AtomicReference<>();
    private final AtomicReference<String> userAgent = new AtomicReference<>();
    private volatile int status = 200;

    @BeforeEach
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/latest", exchange -> {
            accept.set(exchange.getRequestHeaders().getFirst("Accept"));
            userAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            byte[] body = ("{\"tag_name\": \"v1.2.0\", \"html_url\": \"https://example.com/notes\", \"assets\": []}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, status == 200 ? body.length : -1);
            if (status == 200) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    public void stop() {
        server.stop(0);
    }

    private UpdateCheck check() {
        URI latest = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/latest");
        return new UpdateCheck(UpdateCheck.httpClient(), latest, "1.0.0");
    }

    @Test
    public void theLatestReleaseIsRead() throws Exception {
        Release release = check().fetchLatest();

        assertEquals("1.2.0", release.version());
    }

    @Test
    public void theRequestIdentifiesCopybotAndAsksForJson() throws Exception {
        check().fetchLatest();

        assertEquals("application/vnd.github+json", accept.get());
        assertEquals("Copybot/1.0.0", userAgent.get());
    }

    @Test
    public void anotherStatusIsAnHttpError() {
        status = 500;

        UpdateException e = assertThrows(UpdateException.class, () -> check().fetchLatest());

        assertEquals("update.error.http", e.key());
        assertEquals(500, e.args()[0]);
    }

    /** GitHub answers 404 to releases/latest while no release is published. */
    @Test
    public void notFoundMeansNoReleaseYet() {
        status = 404;

        UpdateException e = assertThrows(UpdateException.class, () -> check().fetchLatest());

        assertEquals("update.no-release", e.key());
    }

    @Test
    public void aServerThatIsGoneIsANetworkError() {
        server.stop(0);

        UpdateException e = assertThrows(UpdateException.class, () -> check().fetchLatest());

        assertEquals("update.error.network", e.key());
    }

    @Test
    public void theCheckIsDueOncePerDay() {
        Instant now = Instant.parse("2026-10-26T12:00:00Z");

        assertTrue(UpdateCheck.due(now, Optional.empty()));
        assertFalse(UpdateCheck.due(now, Optional.of(now.minus(Duration.ofHours(23)))));
        assertTrue(UpdateCheck.due(now, Optional.of(now.minus(Duration.ofHours(24)))));
        assertTrue(UpdateCheck.due(now, Optional.of(now.plus(Duration.ofHours(1)))), "a clock set back");
    }
}

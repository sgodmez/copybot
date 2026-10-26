package com.copybot.ui.update;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** Asks GitHub for the latest published release. */
public final class UpdateCheck {

    public static final URI LATEST = URI.create("https://api.github.com/repos/sgodmez/copybot/releases/latest");

    static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Duration INTERVAL = Duration.ofHours(24);

    private final HttpClient client;
    private final URI latest;
    private final String currentVersion;

    public UpdateCheck(HttpClient client, URI latest, String currentVersion) {
        this.client = client;
        this.latest = latest;
        this.currentVersion = currentVersion;
    }

    /** The system proxy, redirections followed, a connection abandoned after 10 s. */
    public static HttpClient httpClient() {
        return HttpClient.newBuilder().proxy(ProxySelector.getDefault())
                .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(TIMEOUT).build();
    }

    public Release fetchLatest() throws UpdateException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(latest).timeout(TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Copybot/" + currentVersion).GET().build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UpdateException("update.error.network", message(e));
        }
        if (response.statusCode() == 404) {
            throw new UpdateException("update.no-release"); // releases/latest of a repository without one yet
        }
        if (response.statusCode() != 200) {
            throw new UpdateException("update.error.http", response.statusCode());
        }
        return Release.parse(response.body());
    }

    /** True when there is no previous check, when it is in the future (a clock set back) or 24 h old or more. */
    public static boolean due(Instant now, Optional<Instant> lastCheck) {
        return lastCheck.isEmpty() || lastCheck.get().isAfter(now)
                || !lastCheck.get().plus(INTERVAL).isAfter(now);
    }

    public static String message(IOException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}

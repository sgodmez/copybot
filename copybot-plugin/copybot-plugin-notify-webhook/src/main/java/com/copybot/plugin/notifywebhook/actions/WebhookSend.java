package com.copybot.plugin.notifywebhook.actions;

import com.copybot.plugin.api.action.AbstractActionWithConfig;
import com.copybot.plugin.api.action.IRunEndAction;
import com.copybot.plugin.api.action.ItemOutcome;
import com.copybot.plugin.api.action.RunEndContext;
import com.copybot.plugin.api.action.RunOutcome;
import com.copybot.plugin.api.action.RunReport;
import com.copybot.plugin.api.definition.IPlugin;
import com.copybot.resources.CombinedResourceBundle;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * webhook.send: posts the result of the run to a web address at its end, shaped for ntfy, Discord, Slack, Telegram
 * or as JSON (spec run-end, notify-webhook). A status other than 2xx is a failure, which the engine turns into a
 * warning of the run.
 * <p>
 * The URL (a Telegram one embeds the bot token) and the headers (they may hold an API key) can come from secrets:
 * they appear in no message, warning or log; a failure names the host at most.
 */
public class WebhookSend extends AbstractActionWithConfig<WebhookConfig> implements IRunEndAction {

    public static final String CODE = "webhook.send";

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    private static final int BODY_START = 200;
    private static final int BODY_READ = 1024;
    private static final Pattern VARIABLE = Pattern.compile("\\{(pipeline|outcome|written|skipped|errors|duration|summary"
            + "|output:[^{}]+)}");

    /** The shape of the message of a service: its headers and body (a JSON template, or plain text for ntfy). */
    private enum Preset {
        JSON("application/json; charset=utf-8", true),
        NTFY("text/plain; charset=utf-8", false),
        DISCORD("application/json; charset=utf-8", true),
        SLACK("application/json; charset=utf-8", true),
        TELEGRAM("application/json; charset=utf-8", true);

        final String contentType;
        final boolean jsonBody;

        Preset(String contentType, boolean jsonBody) {
            this.contentType = contentType;
            this.jsonBody = jsonBody;
        }
    }

    private final HttpClient client = HttpClient.newBuilder()
            // a redirected POST would turn into a GET that "succeeds" without delivering: a 3xx is a failure
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    /** The limit of one request; null: none but the action's own time limit, which interrupts the thread. */
    private final Duration requestTimeout;

    public WebhookSend() {
        this(null);
    }

    WebhookSend(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    @Override
    protected Class<WebhookConfig> getConfigClass() {
        return WebhookConfig.class;
    }

    @Override
    public Duration defaultTimeout() {
        return DEFAULT_TIMEOUT;
    }

    @Override
    public void runEnded(RunReport report, RunEndContext context) {
        WebhookConfig config = getConfig();
        if (config == null || config.url() == null || config.url().isBlank()) {
            throw new IllegalArgumentException(message("webhook.error.url-missing"));
        }
        Preset preset = preset(config.preset());
        URI uri = uri(config.url());
        String host = uri.getHost() == null ? "?" : uri.getHost();

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", preset.contentType);
        if (preset == Preset.NTFY) {
            headers.put("Title", headerValue("Copybot - " + report.pipelineName()));
            if (report.outcome() == RunOutcome.ERROR) {
                headers.put("Priority", "high");
            }
        }
        addUserHeaders(headers, config.headers());

        String body = config.body() == null || config.body().isBlank()
                ? presetBody(preset, report)
                : fill(config.body(), report, preset.jsonBody);

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (requestTimeout != null) {
            builder.timeout(requestTimeout);
        }
        for (Map.Entry<String, String> header : headers.entrySet()) {
            try {
                builder.header(header.getKey(), header.getValue());
            } catch (IllegalArgumentException e) {
                // the message of the JDK quotes the value
                throw new IllegalArgumentException(message("webhook.error.header-invalid", header.getKey()));
            }
        }
        send(builder.build(), host);
    }

    private void send(HttpRequest request, String host) {
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                int code = response.statusCode();
                if (code < 200 || code >= 300) {
                    // at most a kilobyte of the answer is read, and the end of a long body is never wanted
                    String start = new String(in.readNBytes(BODY_READ), StandardCharsets.UTF_8).strip().replaceAll("\\s+", " ");
                    if (start.length() > BODY_START) {
                        start = start.substring(0, BODY_START) + "...";
                    }
                    throw new IllegalStateException(start.isEmpty()
                            ? message("webhook.error.status", host, code)
                            : message("webhook.error.status-body", host, code, start));
                }
                // a 2xx answer is not read: closing the stream drops the rest of it
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(message("webhook.error.interrupted", host));
        } catch (IOException e) {
            // no cause and no message of the JDK, which may quote the URL
            throw new IllegalStateException(message("webhook.error.io", host, e.getClass().getSimpleName()));
        }
    }

    /** A localised message of the plugin; its key and arguments when the action has no plugin (built by hand). */
    private String message(String key, Object... args) {
        IPlugin plugin = getPlugin();
        CombinedResourceBundle bundle = plugin == null ? null : plugin.getResourceBundle();
        if (bundle == null) {
            return key + java.util.Arrays.toString(args);
        }
        return bundle.getString("plugin.notify-webhook." + key, args);
    }

    private Preset preset(String name) {
        if (name == null || name.isBlank()) {
            return Preset.JSON;
        }
        try {
            return Preset.valueOf(name.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(message("webhook.error.preset-unknown", name));
        }
    }

    /** The URL is never in the message of the failure: URI's own message quotes it. */
    private URI uri(String text) {
        URI uri;
        try {
            uri = URI.create(text.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(message("webhook.error.url-invalid"));
        }
        String scheme = uri.getScheme();
        if (uri.getHost() == null || scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException(message("webhook.error.url-scheme"));
        }
        return uri;
    }

    private void addUserHeaders(Map<String, String> headers, java.util.List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                // the line may be a value on its own: only its position is told
                throw new IllegalArgumentException(message("webhook.error.header-line", i + 1));
            }
            String name = line.substring(0, colon).strip();
            // a header of the preset with the same name (any case) is replaced
            headers.keySet().removeIf(existing -> existing.equalsIgnoreCase(name));
            headers.put(name, line.substring(colon + 1).strip());
        }
    }

    // --- message ---

    private static String presetBody(Preset preset, RunReport report) {
        return switch (preset) {
            case JSON -> json(report);
            case NTFY -> summary(report);
            case DISCORD -> object("content", summary(report)).toString();
            case SLACK -> object("text", summary(report)).toString();
            case TELEGRAM -> {
                JsonObject object = new JsonObject();
                object.addProperty("chat_id", "CHAT_ID");
                object.addProperty("text", summary(report));
                yield object.toString();
            }
        };
    }

    private static JsonObject object(String name, String value) {
        JsonObject object = new JsonObject();
        object.addProperty(name, value);
        return object;
    }

    /**
     * The whole result as JSON, without the list of written files; the items in error, the outputs of the previous
     * actions and the directories created are listed.
     */
    private static String json(RunReport report) {
        JsonObject object = new JsonObject();
        object.addProperty("pipeline", report.pipelineName());
        object.addProperty("outcome", outcome(report));
        object.addProperty("runId", report.runId());
        object.addProperty("started", report.started().toString());
        object.addProperty("ended", report.ended().toString());
        object.addProperty("durationSeconds", report.duration().toSeconds());
        object.addProperty("written", report.counts().written());
        object.addProperty("skipped", report.counts().skipped());
        object.addProperty("errors", report.counts().errors());
        object.addProperty("filtered", report.counts().filtered());
        object.addProperty("failure", report.failure());
        JsonArray warnings = new JsonArray();
        report.warnings().forEach(warnings::add);
        object.add("warnings", warnings);
        JsonArray errorItems = new JsonArray();
        report.items().stream().filter(item -> item.outcome() == ItemOutcome.ERROR).forEach(item -> {
            JsonObject error = new JsonObject();
            error.addProperty("name", item.name());
            error.addProperty("source", item.source());
            error.addProperty("reason", item.reason());
            errorItems.add(error);
        });
        object.add("errorItems", errorItems);
        JsonObject outputs = new JsonObject();
        report.outputs().forEach(outputs::addProperty);
        object.add("outputs", outputs);
        JsonArray createdDirectories = new JsonArray();
        report.createdDirectories().forEach(directory -> createdDirectories.add(directory.toString()));
        object.add("createdDirectories", createdDirectories);
        return object.toString();
    }

    private static String outcome(RunReport report) {
        return report.outcome().name().toLowerCase(Locale.ROOT);
    }

    /** One readable line: "holidays: success, 12 written, 3 skipped, 0 errors, 1m 05s" (+ " - failure"). */
    private static String summary(RunReport report) {
        String line = report.pipelineName() + ": " + outcome(report) + ", " + report.counts().written() + " written, "
                + report.counts().skipped() + " skipped, " + report.counts().errors() + " errors, " + duration(report);
        return report.failure() == null || report.failure().isBlank() ? line : line + " - " + report.failure();
    }

    private static String duration(RunReport report) {
        long seconds = Math.max(0, report.duration().toSeconds());
        long hours = seconds / 3600;
        long minutes = seconds % 3600 / 60;
        long rest = seconds % 60;
        if (hours > 0) {
            return "%dh %02dm %02ds".formatted(hours, minutes, rest);
        }
        return minutes > 0 ? "%dm %02ds".formatted(minutes, rest) : rest + "s";
    }

    /** Replaces the known variables (an unknown {name} stays as written, an absent output gives ""). */
    private static String fill(String template, RunReport report, boolean json) {
        Matcher matcher = VARIABLE.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = switch (name) {
                case "pipeline" -> report.pipelineName();
                case "outcome" -> outcome(report);
                case "written" -> String.valueOf(report.counts().written());
                case "skipped" -> String.valueOf(report.counts().skipped());
                case "errors" -> String.valueOf(report.counts().errors());
                case "duration" -> duration(report);
                case "summary" -> summary(report);
                default -> report.outputs().getOrDefault(name.substring("output:".length()), "");
            };
            matcher.appendReplacement(out, Matcher.quoteReplacement(json ? escapeJson(value) : value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** The content of a JSON string literal for this text (without the surrounding quotes). */
    private static String escapeJson(String text) {
        String quoted = new JsonPrimitive(text == null ? "" : text).toString();
        return quoted.substring(1, quoted.length() - 1);
    }

    /** An HTTP header value is ISO-8859-1 at best: anything else goes in the RFC 2047 form (ntfy reads it). */
    private static String headerValue(String text) {
        String clean = text.replaceAll("[\\r\\n]+", " ");
        if (clean.chars().allMatch(c -> c >= 0x20 && c < 0x7F)) {
            return clean;
        }
        return "=?UTF-8?B?" + Base64.getEncoder().encodeToString(clean.getBytes(StandardCharsets.UTF_8)) + "?=";
    }
}

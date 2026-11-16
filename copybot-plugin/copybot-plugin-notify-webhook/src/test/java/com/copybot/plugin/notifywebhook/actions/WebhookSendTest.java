package com.copybot.plugin.notifywebhook.actions;

import com.copybot.plugin.api.action.ItemOutcome;
import com.copybot.plugin.api.action.RunCounts;
import com.copybot.plugin.api.action.RunEndContext;
import com.copybot.plugin.api.action.RunItem;
import com.copybot.plugin.api.action.RunOutcome;
import com.copybot.plugin.api.action.RunReport;
import com.copybot.plugin.notifywebhook.NotifyWebhookPlugin;
import com.copybot.resources.CombinedResourceBundle;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class WebhookSendTest {

    private HttpServer server;
    private volatile String body;
    private volatile String path;
    private volatile Map<String, List<String>> headers;
    private volatile int status = 200;
    private volatile String answer = "ok";
    private volatile long delayMillis;

    private final List<String> warnings = new ArrayList<>();
    private final Map<String, String> published = new java.util.HashMap<>();
    private final RunEndContext context = new RunEndContext() {
        @Override
        public void publish(String key, String value) {
            published.put(key, value);
        }

        @Override
        public void warn(String message) {
            warnings.add(message);
        }
    };

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            path = exchange.getRequestURI().toString();
            headers = Map.copyOf(exchange.getRequestHeaders());
            try {
                if (delayMillis > 0) {
                    Thread.sleep(delayMillis);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (IOException e) {
                // the client gave up
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook/SECRET-TOKEN";
    }

    /** The plugin with its English texts, as the engine gives it to the action. */
    private static NotifyWebhookPlugin plugin(Locale locale) {
        NotifyWebhookPlugin plugin = new NotifyWebhookPlugin();
        CombinedResourceBundle bundle = new CombinedResourceBundle(NotifyWebhookPlugin.class.getModule(), null);
        plugin.getI18nBundleNames().forEach(bundle::addBundle);
        bundle.load(locale);
        plugin.setResourceBundle(bundle);
        return plugin;
    }

    private static WebhookSend action(WebhookSend action, String json) {
        action.setPlugin(plugin(Locale.ENGLISH));
        action.loadConfig(JsonParser.parseString(json));
        return action;
    }

    private WebhookSend action(String preset) {
        return action(new WebhookSend(), "{\"url\":\"" + url() + "\",\"preset\":\"" + preset + "\"}");
    }

    private static RunReport report(RunOutcome outcome, String pipeline, Map<String, String> outputs) {
        Instant start = Instant.parse("2026-10-30T10:00:00Z");
        List<RunItem> items = List.of(
                new RunItem("a.jpg", "card", ItemOutcome.WRITTEN, Path.of("/out/a.jpg"), null),
                new RunItem("b \"x\".jpg", "card", ItemOutcome.ERROR, null, "disk\nfull"));
        return new RunReport(pipeline, Path.of("/p/pipeline.json"), outcome, start, start,
                start.plusSeconds(65), new RunCounts(12, 3, 1, 0), items, List.of(), List.of(Path.of("/out/2026")),
                List.of("w1"), outcome == RunOutcome.ERROR ? "boom" : null, "run-1", outputs);
    }

    private static RunReport report(RunOutcome outcome) {
        return report(outcome, "holidays", Map.of());
    }

    private String header(String name) {
        return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                .map(e -> e.getValue().get(0)).findFirst().orElse(null);
    }

    @Test
    void jsonPresetSendsTheWholeReportWithoutTheWrittenFiles() {
        action("json").runEnded(report(RunOutcome.ERROR, "holidays", Map.of("archive", "/out/run.zip")), context);

        assertEquals("/hook/SECRET-TOKEN", path);
        assertTrue(header("Content-Type").startsWith("application/json"));
        JsonObject json = JsonParser.parseString(body).getAsJsonObject();
        assertEquals("holidays", json.get("pipeline").getAsString());
        assertEquals("error", json.get("outcome").getAsString());
        assertEquals(12, json.get("written").getAsInt());
        assertEquals(3, json.get("skipped").getAsInt());
        assertEquals(1, json.get("errors").getAsInt());
        assertEquals(65, json.get("durationSeconds").getAsLong());
        assertEquals("boom", json.get("failure").getAsString());
        assertEquals("w1", json.getAsJsonArray("warnings").get(0).getAsString());
        JsonObject error = json.getAsJsonArray("errorItems").get(0).getAsJsonObject();
        assertEquals("b \"x\".jpg", error.get("name").getAsString());
        assertEquals("disk\nfull", error.get("reason").getAsString());
        assertFalse(json.has("writtenFiles"));
        assertEquals("/out/run.zip", json.getAsJsonObject("outputs").get("archive").getAsString());
        assertEquals(Path.of("/out/2026").toString(), json.getAsJsonArray("createdDirectories").get(0).getAsString());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void ntfySendsThePlainSummaryWithTitleAndPriority() {
        action("ntfy").runEnded(report(RunOutcome.ERROR), context);

        assertEquals("holidays: error, 12 written, 3 skipped, 1 errors, 1m 05s - boom", body);
        assertTrue(header("Content-Type").startsWith("text/plain"));
        assertEquals("Copybot - holidays", header("Title"));
        assertEquals("high", header("Priority"));
    }

    @Test
    void ntfyPriorityIsNotRaisedOnSuccessAndANonAsciiTitleIsEncoded() {
        action("ntfy").runEnded(report(RunOutcome.SUCCESS, "vacances \u00e9t\u00e9", Map.of()), context);

        assertNull(header("Priority"));
        assertTrue(header("Title").startsWith("=?UTF-8?B?"), header("Title"));
        assertTrue(body.startsWith("vacances \u00e9t\u00e9: success, 12 written"), body);
    }

    @Test
    void discordSendsContent() {
        action("discord").runEnded(report(RunOutcome.SUCCESS), context);

        assertTrue(header("Content-Type").startsWith("application/json"));
        assertEquals("holidays: success, 12 written, 3 skipped, 1 errors, 1m 05s",
                JsonParser.parseString(body).getAsJsonObject().get("content").getAsString());
    }

    @Test
    void slackSendsText() {
        action("slack").runEnded(report(RunOutcome.CANCELLED), context);

        assertTrue(JsonParser.parseString(body).getAsJsonObject().get("text").getAsString()
                .startsWith("holidays: cancelled, 12 written"));
    }

    @Test
    void telegramSendsAChatIdPlaceholderAndText() {
        action("telegram").runEnded(report(RunOutcome.SUCCESS), context);

        JsonObject json = JsonParser.parseString(body).getAsJsonObject();
        assertEquals("CHAT_ID", json.get("chat_id").getAsString());
        assertTrue(json.get("text").getAsString().startsWith("holidays: success"));
    }

    @Test
    void theDefaultPresetIsJson() {
        action(new WebhookSend(), "{\"url\":\"" + url() + "\"}").runEnded(report(RunOutcome.SUCCESS), context);

        assertEquals("holidays", JsonParser.parseString(body).getAsJsonObject().get("pipeline").getAsString());
    }

    @Test
    void aTemplateReplacesThePresetBodyWithItsVariables() {
        String template = "{\\\"chat_id\\\": \\\"42\\\", \\\"text\\\": \\\"{pipeline} {outcome} {written}/{skipped}/{errors} "
                + "{duration} archive={output:archive.path} none={output:nope} {unknown}\\\"}";
        action(new WebhookSend(), "{\"url\":\"" + url() + "\",\"preset\":\"telegram\",\"body\":\"" + template + "\"}")
                .runEnded(report(RunOutcome.SUCCESS, "holidays", Map.of("archive.path", "C:\\out\\a.zip")), context);

        JsonObject json = JsonParser.parseString(body).getAsJsonObject();
        assertEquals("42", json.get("chat_id").getAsString());
        assertEquals("holidays success 12/3/1 1m 05s archive=C:\\out\\a.zip none= {unknown}",
                json.get("text").getAsString());
    }

    @Test
    void variablesAreEscapedWhenTheBodyIsJson() {
        action(new WebhookSend(), "{\"url\":\"" + url() + "\",\"preset\":\"slack\",\"body\":\"{\\\"text\\\": \\\"{output:m}\\\"}\"}")
                .runEnded(report(RunOutcome.SUCCESS, "holidays", Map.of("m", "say \"hi\"\nline2\ttab\\")), context);

        assertTrue(body.contains("say \\\"hi\\\"\\nline2\\ttab\\\\"), body);
        assertEquals("say \"hi\"\nline2\ttab\\",
                JsonParser.parseString(body).getAsJsonObject().get("text").getAsString());
    }

    @Test
    void variablesAreNotEscapedWhenTheBodyIsPlainText() {
        action(new WebhookSend(), "{\"url\":\"" + url() + "\",\"preset\":\"ntfy\",\"body\":\"{output:m}\"}")
                .runEnded(report(RunOutcome.SUCCESS, "holidays", Map.of("m", "say \"hi\"")), context);

        assertEquals("say \"hi\"", body);
    }

    @Test
    void pipelineNamesWithQuotesAreEscapedInThePresets() {
        action("discord").runEnded(report(RunOutcome.SUCCESS, "my \"best\" run", Map.of()), context);

        assertTrue(JsonParser.parseString(body).getAsJsonObject().get("content").getAsString()
                .startsWith("my \"best\" run: success"));
    }

    @Test
    void headersAreSentAndReplaceThoseOfThePreset() {
        action(new WebhookSend(), "{\"url\":\"" + url() + "\",\"preset\":\"ntfy\",\"headers\":"
                + "[\"Authorization: Bearer abc\",\"X-Test:  spaced \",\"title: Mine\"]}")
                .runEnded(report(RunOutcome.ERROR), context);

        assertEquals("Bearer abc", header("Authorization"));
        assertEquals("spaced", header("X-Test"));
        assertEquals("Mine", header("Title"));
        assertEquals("high", header("Priority"));
    }

    @Test
    void aFailureStatusIsAnExceptionWithTheCodeAndTheStartOfTheBodyButNeitherUrlNorToken() {
        status = 500;
        answer = "Internal error " + "x".repeat(1000);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> action("json").runEnded(report(RunOutcome.SUCCESS), context));

        assertTrue(e.getMessage().contains("500"), e.getMessage());
        assertTrue(e.getMessage().contains("Internal error"), e.getMessage());
        assertTrue(e.getMessage().length() < 400, e.getMessage());
        assertTrue(e.getMessage().contains("127.0.0.1"), e.getMessage());
        assertFalse(e.getMessage().contains("SECRET-TOKEN"), e.getMessage());
        assertFalse(e.getMessage().contains("/hook"), e.getMessage());
    }

    @Test
    void aNotFoundStatusIsAFailure() {
        status = 404;
        answer = "not found";

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> action("json").runEnded(report(RunOutcome.SUCCESS), context));

        assertTrue(e.getMessage().contains("404"), e.getMessage());
    }

    @Test
    void aRedirectionIsNotFollowedAndIsAFailureWithItsCode() {
        server.createContext("/moved", exchange -> {
            exchange.getResponseHeaders().add("Location", "/hook/other");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        WebhookSend send = action(new WebhookSend(),
                "{\"url\":\"http://127.0.0.1:" + server.getAddress().getPort() + "/moved\"}");
        path = null;

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> send.runEnded(report(RunOutcome.SUCCESS), context));

        assertTrue(e.getMessage().contains("302"), e.getMessage());
        assertNull(path, "the Location was followed");
    }

    @Test
    void aLargeSuccessAnswerIsNotRead() {
        answer = "y".repeat(5_000_000);

        assertDoesNotThrow(() -> action("json").runEnded(report(RunOutcome.SUCCESS), context));
    }

    @Test
    void aLargeErrorAnswerGivesOnlyItsStart() {
        status = 502;
        answer = "start-of-body " + "z".repeat(5_000_000);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> action("json").runEnded(report(RunOutcome.SUCCESS), context));

        assertTrue(e.getMessage().contains("start-of-body"), e.getMessage());
        assertTrue(e.getMessage().length() < 400, e.getMessage());
    }

    @Test
    void failureMessagesAreLocalised() {
        status = 500;
        answer = "oups";
        WebhookSend send = new WebhookSend();
        send.setPlugin(plugin(Locale.FRENCH));
        send.loadConfig(JsonParser.parseString("{\"url\":\"" + url() + "\"}"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> send.runEnded(report(RunOutcome.SUCCESS), context));

        assertTrue(e.getMessage().contains("a r\u00e9pondu HTTP 500 : oups"), e.getMessage());
    }

    @Test
    void theConfigurationHidesTheUrlAndTheHeadersInItsText() {
        String text = new WebhookConfig("https://api.telegram.org/botSECRET/send", "telegram",
                List.of("Authorization: Bearer SECRET"), null).toString();

        assertFalse(text.contains("SECRET"), text);
        assertTrue(text.contains("telegram"), text);
    }

    @Test
    void anyTwoHundredStatusIsASuccess() {
        status = 204;
        answer = "";

        assertDoesNotThrow(() -> action("json").runEnded(report(RunOutcome.SUCCESS), context));
    }

    @Test
    void aSlowServerIsGivenUpOnAtTheTimeout() {
        delayMillis = 3000;
        WebhookSend slow = action(new WebhookSend(Duration.ofMillis(300)),
                "{\"url\":\"" + url() + "\",\"preset\":\"json\"}");

        long start = System.nanoTime();
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> slow.runEnded(report(RunOutcome.SUCCESS), context));

        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 2500);
        assertFalse(e.getMessage().contains("SECRET-TOKEN"), e.getMessage());
    }

    @Test
    void anInterruptionStopsTheRequestAndKeepsTheInterruptedFlag() throws Exception {
        delayMillis = 5000;
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Boolean> flag = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                action("json").runEnded(report(RunOutcome.SUCCESS), context);
            } catch (Throwable t) {
                failure.set(t);
                flag.set(Thread.currentThread().isInterrupted());
            }
        });
        thread.start();
        Thread.sleep(500);
        thread.interrupt();
        thread.join(3000);

        assertFalse(thread.isAlive());
        assertNotNull(failure.get());
        assertEquals(Boolean.TRUE, flag.get());
    }

    @Test
    void anUnreachableServerIsAnExceptionWithoutTheUrl() {
        int port = server.getAddress().getPort();
        server.stop(0);
        WebhookSend send = action(new WebhookSend(), "{\"url\":\"http://127.0.0.1:" + port + "/hook/SECRET-TOKEN\"}");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> send.runEnded(report(RunOutcome.SUCCESS), context));

        assertFalse(e.getMessage().contains("SECRET-TOKEN"), e.getMessage());
        assertNull(e.getCause());
    }

    @Test
    void anInvalidUrlDoesNotEchoIt() {
        WebhookSend send = action(new WebhookSend(), "{\"url\":\"not a url SECRET-TOKEN\"}");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> send.runEnded(report(RunOutcome.SUCCESS), context));

        assertFalse(e.getMessage().contains("SECRET-TOKEN"), e.getMessage());
    }

    @Test
    void aMissingUrlOrAUnknownPresetOrAMalformedHeaderIsAnError() {
        assertThrows(IllegalArgumentException.class,
                () -> action(new WebhookSend(), "{\"preset\":\"json\"}").runEnded(report(RunOutcome.SUCCESS), context));
        assertThrows(IllegalArgumentException.class,
                () -> action(new WebhookSend(), "{\"url\":\"" + url() + "\",\"preset\":\"fax\"}")
                        .runEnded(report(RunOutcome.SUCCESS), context));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> action(new WebhookSend(), "{\"url\":\"" + url() + "\",\"headers\":[\"Bearer SECRET-VALUE\"]}")
                        .runEnded(report(RunOutcome.SUCCESS), context));
        assertFalse(e.getMessage().contains("SECRET-VALUE"), e.getMessage());
    }

    @Test
    void theDefaultTimeoutIsSixtySeconds() {
        assertEquals(Duration.ofSeconds(60), new WebhookSend().defaultTimeout());
    }
}

package com.copybot.plugin.notifyemail.actions;

import com.copybot.plugin.api.action.ItemOutcome;
import com.copybot.plugin.api.action.RunCounts;
import com.copybot.plugin.api.action.RunEndContext;
import com.copybot.plugin.api.action.RunItem;
import com.copybot.plugin.api.action.RunOutcome;
import com.copybot.plugin.api.action.RunReport;
import com.copybot.plugin.notifyemail.NotifyEmailPlugin;
import com.copybot.plugin.notifyemail.TestSmtpServer;
import com.copybot.resources.CombinedResourceBundle;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

public class EmailSendTest {

    private static final String PASSWORD = "SECRET-PW-42";
    private static final String USER = "steven.login";

    private TestSmtpServer server;

    private final List<String> warnings = new ArrayList<>();
    private final RunEndContext context = new RunEndContext() {
        @Override
        public void publish(String key, String value) {
        }

        @Override
        public void warn(String message) {
            warnings.add(message);
        }
    };

    private Locale defaultLocale;

    @BeforeEach
    void startServer() {
        // a bundle missing in the asked language falls back on the default one, not on the English base
        defaultLocale = Locale.getDefault();
        Locale.setDefault(Locale.ENGLISH);
        server = new TestSmtpServer();
    }

    @AfterEach
    void stopServer() {
        server.close();
        Locale.setDefault(defaultLocale);
    }

    /** The plugin with its texts in this language, as the engine gives it to the action. */
    private static NotifyEmailPlugin plugin(Locale locale) {
        NotifyEmailPlugin plugin = new NotifyEmailPlugin();
        CombinedResourceBundle bundle = new CombinedResourceBundle(NotifyEmailPlugin.class.getModule(), null);
        plugin.getI18nBundleNames().forEach(bundle::addBundle);
        bundle.load(locale);
        plugin.setResourceBundle(bundle);
        return plugin;
    }

    private JsonObject config() {
        JsonObject json = new JsonObject();
        json.addProperty("host", "127.0.0.1");
        json.addProperty("port", server.port());
        json.addProperty("security", "none");
        json.addProperty("from", "copybot@example.org");
        JsonArray to = new JsonArray();
        to.add("me@example.org");
        to.add("You <you@example.org>");
        json.add("to", to);
        return json;
    }

    private static EmailSend action(JsonObject config) {
        return action(new EmailSend(), config, Locale.ENGLISH);
    }

    private static EmailSend action(EmailSend action, JsonObject config, Locale locale) {
        action.setPlugin(plugin(locale));
        action.loadConfig(config);
        return action;
    }

    private static RunReport report(RunOutcome outcome, List<RunItem> items, Map<String, String> outputs) {
        Instant start = Instant.parse("2026-10-30T10:00:00Z");
        return new RunReport("holidays", Path.of("/p/holidays.json"), outcome, start, start, start.plusSeconds(65),
                new RunCounts(12, 3, 1, 2), items, List.of(), List.of(Path.of("/out/2026"), Path.of("/out/2026/10")),
                List.of("disk almost full"), outcome == RunOutcome.ERROR ? "card removed" : null, "run-1", outputs);
    }

    private static List<RunItem> items() {
        return List.of(
                new RunItem("a.jpg", "card", ItemOutcome.WRITTEN, Path.of("/out/a.jpg"), null),
                new RunItem("b.jpg", "card", ItemOutcome.SKIPPED, Path.of("/out/b.jpg"), "already there"),
                new RunItem("c <x>.jpg", "card", ItemOutcome.ERROR, null, "disk\nfull"),
                new RunItem("d.jpg", "card", ItemOutcome.FILTERED, null, null));
    }

    private static RunReport report(RunOutcome outcome) {
        return report(outcome, items(), Map.of("archive.path", "C:\\out\\import.zip"));
    }

    /** The message the server received, parsed. */
    private MimeMessage received() throws Exception {
        assertEquals(1, server.messages.size(), "one message");
        return new MimeMessage(Session.getInstance(new Properties()),
                new ByteArrayInputStream(server.messages.get(0).data().getBytes(StandardCharsets.UTF_8)));
    }

    private record Parts(String text, String html) {
    }

    private Parts parts() throws Exception {
        MimeMessage message = received();
        assertTrue(message.isMimeType("multipart/alternative"), message.getContentType());
        Multipart multipart = (Multipart) message.getContent();
        assertEquals(2, multipart.getCount());
        BodyPart text = multipart.getBodyPart(0);
        BodyPart html = multipart.getBodyPart(1);
        assertTrue(text.isMimeType("text/plain"), text.getContentType());
        assertTrue(html.isMimeType("text/html"), html.getContentType());
        return new Parts((String) text.getContent(), (String) html.getContent());
    }

    @Test
    void theMessageIsSentWithTheSubjectAndTheEnvelope() throws Exception {
        action(config()).runEnded(report(RunOutcome.SUCCESS), context);

        TestSmtpServer.Received received = server.messages.get(0);
        assertEquals("<copybot@example.org>", received.from());
        assertEquals(List.of("<me@example.org>", "<you@example.org>"), received.to());
        MimeMessage message = received();
        assertEquals("Copybot \u2014 holidays : success", message.getSubject());
        assertEquals("copybot@example.org", message.getFrom()[0].toString());
        assertNotNull(message.getSentDate());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void theSubjectTemplateTakesTheVariables() throws Exception {
        JsonObject config = config();
        config.addProperty("subject", "[{pipeline}] {outcome} {written}/{skipped}/{errors} in {duration} "
                + "{output:archive.path} {output:none}{unknown}\nnext");
        action(config).runEnded(report(RunOutcome.ERROR), context);

        assertEquals("[holidays] error 12/3/1 in 1m 05s C:\\out\\import.zip {unknown} next", received().getSubject());
    }

    @Test
    void theOutcomeIsLocalised() throws Exception {
        action(new EmailSend(), config(), Locale.FRENCH).runEnded(report(RunOutcome.CANCELLED), context);

        assertEquals("Copybot \u2014 holidays : annul\u00e9", received().getSubject());
        assertTrue(parts().text().contains("Fichiers \u00e9crits : 12"), parts().text());
    }

    @Test
    void theTextAndHtmlPartsGiveTheOutcomeDurationCountsFoldersOutputsAndWarnings() throws Exception {
        action(config()).runEnded(report(RunOutcome.ERROR), context);

        Parts parts = parts();
        for (String body : List.of(parts.text(), parts.html())) {
            assertTrue(body.contains("holidays"), body);
            assertTrue(body.contains("error"), body);
            assertTrue(body.contains("card removed"), body);
            assertTrue(body.contains("1m 05s"), body);
            assertTrue(body.contains("12"), body);
            assertTrue(body.contains(Path.of("/out/2026/10").toString()), body);
            assertTrue(body.contains("archive.path"), body);
            assertTrue(body.contains("C:\\out\\import.zip"), body);
            assertTrue(body.contains("disk almost full"), body);
        }
        assertTrue(parts.text().contains("Files written: 12"), parts.text());
        assertTrue(parts.text().contains("Skipped: 3"), parts.text());
        assertTrue(parts.text().contains("Errors: 1"), parts.text());
        assertTrue(parts.text().contains("Filtered: 2"), parts.text());
        assertTrue(parts.html().contains("<html"), parts.html());
    }

    @Test
    void detailsErrorsByDefaultListsOnlyTheItemsInErrorWithTheirReason() throws Exception {
        action(config()).runEnded(report(RunOutcome.ERROR), context);

        Parts parts = parts();
        assertTrue(parts.text().contains("c <x>.jpg: disk full"), parts.text());
        assertFalse(parts.text().contains("a.jpg"), parts.text());
        assertFalse(parts.text().contains("b.jpg"), parts.text());
        // escaped in the HTML part
        assertTrue(parts.html().contains("c &lt;x&gt;.jpg"), parts.html());
        assertFalse(parts.html().contains("c <x>.jpg"), parts.html());
    }

    @Test
    void detailsAllListsEveryItem() throws Exception {
        JsonObject config = config();
        config.addProperty("details", "all");
        action(config).runEnded(report(RunOutcome.ERROR), context);

        String text = parts().text();
        assertTrue(text.contains("a.jpg: written to " +Path.of("/out/a.jpg")), text);
        assertTrue(text.contains("b.jpg: skipped (already there)"), text);
        assertTrue(text.contains("c <x>.jpg: disk full"), text);
        assertTrue(text.contains("d.jpg: filtered"), text);
    }

    @Test
    void aWrittenItemWithoutTargetTellsItsOutcomeOnly() throws Exception {
        JsonObject config = config();
        config.addProperty("details", "all");
        List<RunItem> items = List.of(new RunItem("a.jpg", "card", ItemOutcome.WRITTEN, null, null));
        action(config).runEnded(report(RunOutcome.SUCCESS, items, Map.of()), context);

        String text = parts().text();
        assertTrue(text.contains("a.jpg: written"), text);
        assertFalse(text.contains("written to"), text);
    }

    @Test
    void detailsNoneListsNoItem() throws Exception {
        JsonObject config = config();
        config.addProperty("details", "none");
        action(config).runEnded(report(RunOutcome.ERROR), context);

        Parts parts = parts();
        for (String name : List.of("a.jpg", "b.jpg", "c &lt;x&gt;.jpg", "c <x>.jpg", "d.jpg")) {
            assertFalse(parts.text().contains(name), parts.text());
            assertFalse(parts.html().contains(name), parts.html());
        }
        assertTrue(parts.text().contains("Errors: 1"), parts.text());
    }

    @Test
    void atMostTwoHundredItemLinesThenTheNumberOfOthers() throws Exception {
        List<RunItem> many = IntStream.rangeClosed(1, 250)
                .mapToObj(i -> new RunItem("err-%03d.jpg".formatted(i), "card", ItemOutcome.ERROR, null, "broken"))
                .toList();
        action(config()).runEnded(report(RunOutcome.ERROR, many, Map.of()), context);

        Parts parts = parts();
        for (String body : List.of(parts.text(), parts.html())) {
            assertTrue(body.contains("err-200.jpg"), "the 200th");
            assertFalse(body.contains("err-201.jpg"), "not the 201st");
            assertTrue(body.contains("\u2026 and 50 others"), body);
        }
    }

    @Test
    void credentialsAreSentToTheServerOnlyInTheAuthentication() throws Exception {
        JsonObject config = config();
        config.addProperty("user", USER);
        config.addProperty("password", PASSWORD);
        action(config).runEnded(report(RunOutcome.SUCCESS), context);

        assertTrue(String.join("|", server.auth).contains(PASSWORD), server.auth.toString());
        String data = server.messages.get(0).data();
        assertFalse(data.contains(PASSWORD));
        assertFalse(data.contains(USER));
    }

    @Test
    void aRefusedAuthenticationIsAnExceptionWithoutUserNorPassword() {
        server.authReply = "535 5.7.8 bad credentials for " + USER + " / " + PASSWORD;
        JsonObject config = config();
        config.addProperty("user", USER);
        config.addProperty("password", PASSWORD);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> action(config).runEnded(report(RunOutcome.SUCCESS), context));

        assertFalse(e.getMessage().contains(PASSWORD), e.getMessage());
        assertFalse(e.getMessage().contains(USER), e.getMessage());
        assertTrue(e.getMessage().contains("127.0.0.1"), e.getMessage());
        assertNull(e.getCause());
        assertTrue(server.messages.isEmpty());
    }

    @Test
    void anUnreachableServerIsAnExceptionWithoutCredentials() throws Exception {
        int port;
        try (ServerSocket closed = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = closed.getLocalPort();
        }
        JsonObject config = config();
        config.addProperty("port", port);
        config.addProperty("user", USER);
        config.addProperty("password", PASSWORD);

        long start = System.nanoTime();
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> action(config).runEnded(report(RunOutcome.SUCCESS), context));

        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 20);
        assertTrue(e.getMessage().contains("127.0.0.1"), e.getMessage());
        assertFalse(e.getMessage().contains(PASSWORD), e.getMessage());
        assertFalse(e.getMessage().contains(USER), e.getMessage());
        assertNull(e.getCause());
    }

    @Test
    void aDeadServerIsGivenUpOnAtTheSocketTimeout() {
        server.silent = true;
        EmailSend send = action(new EmailSend(Duration.ofMillis(500)), config(), Locale.ENGLISH);

        long start = System.nanoTime();
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> send.runEnded(report(RunOutcome.SUCCESS), context));

        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 3000);
        assertTrue(e.getMessage().contains("127.0.0.1"), e.getMessage());
    }

    @Test
    void theSocketTimeoutsAreAThirdOfTheDefaultTimeLimit() {
        Properties properties = new EmailSend().properties(new EmailConfig("smtp.example.org", null, null, null,
                null, "a@b.c", List.of("d@e.f"), null, null));

        assertEquals("20000", properties.getProperty("mail.smtp.connectiontimeout"));
        assertEquals("20000", properties.getProperty("mail.smtp.timeout"));
        assertEquals("20000", properties.getProperty("mail.smtp.writetimeout"));
        assertEquals(Duration.ofSeconds(60), new EmailSend().defaultTimeout());
    }

    @Test
    void anInterruptionStopsTheSendingAndKeepsTheInterruptedFlag() throws Exception {
        server.silent = true;
        EmailSend send = action(config());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Boolean> flag = new AtomicReference<>();
        // the engine runs the action on a virtual thread, whose socket I/O an interruption stops
        Thread thread = Thread.ofVirtual().start(() -> {
            try {
                send.runEnded(report(RunOutcome.SUCCESS), context);
            } catch (Throwable t) {
                failure.set(t);
                flag.set(Thread.currentThread().isInterrupted());
            }
        });
        Thread.sleep(500);
        thread.interrupt();
        thread.join(3000);

        assertFalse(thread.isAlive());
        assertInstanceOf(IllegalStateException.class, failure.get());
        assertTrue(failure.get().getMessage().contains("interrupted"), failure.get().getMessage());
        assertEquals(Boolean.TRUE, flag.get());
    }

    @Test
    void withoutPortSslUses465AndTheOthers587() {
        EmailSend send = new EmailSend();
        assertEquals("465", send.properties(new EmailConfig("smtp.example.org", null, "ssl", null, null,
                "a@b.c", List.of("d@e.f"), null, null)).getProperty("mail.smtp.port"));
        assertEquals("587", send.properties(new EmailConfig("smtp.example.org", null, "none", null, null,
                "a@b.c", List.of("d@e.f"), null, null)).getProperty("mail.smtp.port"));
        assertEquals("2465", send.properties(new EmailConfig("smtp.example.org", 2465, "ssl", null, null,
                "a@b.c", List.of("d@e.f"), null, null)).getProperty("mail.smtp.port"), "a port set wins");
    }

    @Test
    void theFormShowsNoFixedDefaultPort() {
        com.copybot.plugin.api.config.ConfigField port = com.copybot.plugin.api.config.ConfigSchema.of(EmailConfig.class)
                .field("port").orElseThrow();
        assertNull(port.defaultValue(), "465 or 587 by the security: no single default to show");
    }

    @Test
    void starttlsIsRequiredAndSslChecksTheServerIdentity() {
        EmailSend send = new EmailSend();
        Properties starttls = send.properties(new EmailConfig("smtp.example.org", null, null, USER, PASSWORD,
                "a@b.c", List.of("d@e.f"), null, null));
        assertEquals("587", starttls.getProperty("mail.smtp.port"));
        assertEquals("true", starttls.getProperty("mail.smtp.starttls.enable"));
        assertEquals("true", starttls.getProperty("mail.smtp.starttls.required"));
        assertEquals("true", starttls.getProperty("mail.smtp.ssl.checkserveridentity"));
        assertEquals("true", starttls.getProperty("mail.smtp.auth"));
        assertNull(starttls.getProperty("mail.smtp.ssl.enable"));
        assertFalse(starttls.toString().contains(PASSWORD), "the password is given to connect, not set as a property");

        Properties ssl = send.properties(new EmailConfig("smtp.example.org", 465, "ssl", null, null,
                "a@b.c", List.of("d@e.f"), null, null));
        assertEquals("true", ssl.getProperty("mail.smtp.ssl.enable"));
        assertEquals("true", ssl.getProperty("mail.smtp.ssl.checkserveridentity"));
        assertNull(ssl.getProperty("mail.smtp.starttls.enable"));
        assertEquals("false", ssl.getProperty("mail.smtp.auth"));

        Properties none = send.properties(new EmailConfig("smtp.example.org", 25, "none", null, null,
                "a@b.c", List.of("d@e.f"), null, null));
        assertNull(none.getProperty("mail.smtp.starttls.enable"));
        assertNull(none.getProperty("mail.smtp.ssl.enable"));
    }

    @Test
    void aConnectionLostAfterTheServerAcceptedTheMessageIsNotAFailure() {
        server.dropAfterData = true;

        assertDoesNotThrow(() -> action(config()).runEnded(report(RunOutcome.SUCCESS), context));

        assertEquals(1, server.messages.size());
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void aFailedTlsHandshakeHasItsOwnMessageWithoutCredentials() {
        // ssl against a server in clear: its greeting is not a TLS record
        JsonObject config = config();
        config.addProperty("security", "ssl");
        config.addProperty("user", USER);
        config.addProperty("password", PASSWORD);
        EmailSend send = action(new EmailSend(Duration.ofSeconds(5)), config, Locale.ENGLISH);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> send.runEnded(report(RunOutcome.SUCCESS), context));

        assertTrue(e.getMessage().contains("secure connection failed"), e.getMessage());
        assertTrue(e.getMessage().contains("SSL"), e.getMessage());
        assertTrue(e.getMessage().contains("127.0.0.1"), e.getMessage());
        assertFalse(e.getMessage().contains(PASSWORD), e.getMessage());
        assertFalse(e.getMessage().contains(USER), e.getMessage());
        assertNull(e.getCause());
    }

    @Test
    void aUserWithoutEncryptionIsWarnedThatThePasswordGoesInClear() {
        JsonObject config = config();
        config.addProperty("user", USER);
        config.addProperty("password", PASSWORD);
        action(config).runEnded(report(RunOutcome.SUCCESS), context);

        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("not encrypted"), warnings.get(0));
        assertTrue(warnings.get(0).contains("127.0.0.1"), warnings.get(0));
        assertFalse(warnings.get(0).contains(PASSWORD), warnings.get(0));
        assertFalse(warnings.get(0).contains(USER), warnings.get(0));
        assertEquals(1, server.messages.size(), "sent all the same");
    }

    @Test
    void noEncryptionWithoutUserIsNotWarned() {
        action(config()).runEnded(report(RunOutcome.SUCCESS), context);

        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void quitDoesNotWaitForTheAnswerOfTheServer() {
        Properties properties = new EmailSend().properties(new EmailConfig("smtp.example.org", null, null, null,
                null, "a@b.c", List.of("d@e.f"), null, null));

        assertEquals("false", properties.getProperty("mail.smtp.quitwait"));
    }

    @Test
    void theConfigurationHidesUserAndPasswordInItsText() {
        String text = new EmailConfig("smtp.example.org", 587, "starttls", USER, PASSWORD, "a@b.c",
                List.of("d@e.f"), null, null).toString();

        assertFalse(text.contains(PASSWORD), text);
        assertFalse(text.contains(USER), text);
        assertTrue(text.contains("smtp.example.org"), text);
    }

    @Test
    void anInvalidConfigurationIsALocalisedErrorWithoutTheValue() {
        JsonObject noHost = config();
        noHost.remove("host");
        assertThrows(IllegalArgumentException.class, () -> action(noHost).runEnded(report(RunOutcome.SUCCESS), context));
        JsonObject noFrom = config();
        noFrom.remove("from");
        assertThrows(IllegalArgumentException.class, () -> action(noFrom).runEnded(report(RunOutcome.SUCCESS), context));
        JsonObject noTo = config();
        noTo.add("to", new JsonArray());
        assertThrows(IllegalArgumentException.class, () -> action(noTo).runEnded(report(RunOutcome.SUCCESS), context));
        JsonObject security = config();
        security.addProperty("security", "tls13");
        assertThrows(IllegalArgumentException.class, () -> action(security).runEnded(report(RunOutcome.SUCCESS), context));
        JsonObject details = config();
        details.addProperty("details", "some");
        assertThrows(IllegalArgumentException.class, () -> action(details).runEnded(report(RunOutcome.SUCCESS), context));
        JsonObject port = config();
        port.addProperty("port", 70000);
        assertThrows(IllegalArgumentException.class, () -> action(port).runEnded(report(RunOutcome.SUCCESS), context));

        JsonObject badTo = config();
        JsonArray to = new JsonArray();
        to.add("me@example.org");
        to.add("not an address SECRET-PW-42");
        badTo.add("to", to);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> action(new EmailSend(), badTo, Locale.FRENCH).runEnded(report(RunOutcome.SUCCESS), context));
        assertFalse(e.getMessage().contains(PASSWORD), e.getMessage());
        assertTrue(e.getMessage().contains("destinataire 2"), e.getMessage());
        assertTrue(server.messages.isEmpty());
    }
}

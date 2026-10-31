package com.copybot.plugin.notifyemail.actions;

import com.copybot.plugin.api.action.AbstractActionWithConfig;
import com.copybot.plugin.api.action.IRunEndAction;
import com.copybot.plugin.api.action.RunEndContext;
import com.copybot.plugin.api.action.RunReport;
import com.copybot.plugin.api.definition.IPlugin;
import com.copybot.resources.CombinedResourceBundle;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

import javax.net.ssl.SSLException;

/**
 * email.send: sends the result of the run by e-mail (SMTP, Jakarta Mail with Angus Mail) at its end (spec run-end,
 * notify-email). Any failure is an exception, which the engine turns into a warning of the run.
 * <p>
 * The user and the password (a secret) appear in no message, warning or log: a failure names the server, the kind
 * of failure and at most the start of the server's reply, with the credentials blanked out. The socket timeouts are
 * a third of the default time limit, so a dead server ends the action within it; the engine runs the action on a
 * virtual thread, whose socket I/O its interruption (time limit, cancellation) stops at once.
 */
public class EmailSend extends AbstractActionWithConfig<EmailConfig> implements IRunEndAction {

    public static final String CODE = "email.send";

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    private static final Set<String> SECURITIES = Set.of("starttls", "ssl", "none");
    private static final Set<String> DETAILS = Set.of("none", "errors", "all");
    private static final int REPLY_START = 200;

    /** The connection, read and write timeouts of the SMTP socket. */
    private final Duration socketTimeout;

    public EmailSend() {
        this(DEFAULT_TIMEOUT.dividedBy(3));
    }

    EmailSend(Duration socketTimeout) {
        this.socketTimeout = socketTimeout;
    }

    @Override
    protected Class<EmailConfig> getConfigClass() {
        return EmailConfig.class;
    }

    @Override
    public Duration defaultTimeout() {
        return DEFAULT_TIMEOUT;
    }

    @Override
    public void runEnded(RunReport report, RunEndContext context) {
        EmailConfig config = getConfig();
        validate(config);
        String server = config.host().strip() + ":" + port(config);
        if (hasUser(config) && security(config).equals("none")) {
            context.warn(message("email.warning.clear", server));
        }

        // Jakarta Mail finds Angus Mail (and Activation its handlers) through the context class loader: the plugin's
        // own, whose layer holds them, not the application's the engine's thread carries
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(EmailSend.class.getClassLoader());
        try {
            Session session = Session.getInstance(properties(config));
            MimeMessage message = message(session, config, report);
            send(session, message, config, server);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /** The properties of the SMTP session: server, timeouts, security; never the credentials. */
    Properties properties(EmailConfig config) {
        String security = security(config);
        String timeout = String.valueOf(socketTimeout.toMillis());
        Properties properties = new Properties();
        properties.setProperty("mail.smtp.host", config.host().strip());
        properties.setProperty("mail.smtp.port", String.valueOf(port(config)));
        properties.setProperty("mail.smtp.connectiontimeout", timeout);
        properties.setProperty("mail.smtp.timeout", timeout);
        properties.setProperty("mail.smtp.writetimeout", timeout);
        properties.setProperty("mail.smtp.auth", String.valueOf(hasUser(config)));
        // QUIT does not wait for the server's answer: the message is already accepted then
        properties.setProperty("mail.smtp.quitwait", "false");
        switch (security) {
            case "starttls" -> {
                // required: a server that does not offer it is a failure, not a message sent in clear
                properties.setProperty("mail.smtp.starttls.enable", "true");
                properties.setProperty("mail.smtp.starttls.required", "true");
                properties.setProperty("mail.smtp.ssl.checkserveridentity", "true");
            }
            case "ssl" -> {
                properties.setProperty("mail.smtp.ssl.enable", "true");
                properties.setProperty("mail.smtp.ssl.checkserveridentity", "true");
            }
            default -> {
                // none: in clear, as asked
            }
        }
        return properties;
    }

    private void send(Session session, MimeMessage message, EmailConfig config, String server) {
        Transport transport;
        try {
            transport = session.getTransport("smtp");
        } catch (MessagingException e) {
            throw failure(e, config, server);
        }
        try {
            if (hasUser(config)) {
                transport.connect(config.host().strip(), port(config), config.user(),
                        config.password() == null ? "" : config.password());
            } else {
                transport.connect(config.host().strip(), port(config), null, null);
            }
            transport.sendMessage(message, message.getAllRecipients());
        } catch (MessagingException e) {
            close(transport);
            throw failure(e, config, server);
        }
        // the server has accepted the message: a failure of QUIT does not undo the delivery
        close(transport);
    }

    private static void close(Transport transport) {
        try {
            transport.close();
        } catch (MessagingException | RuntimeException e) {
            // the connection is dropped either way
        }
    }

    /** The localised failure, without cause: the exceptions of Jakarta Mail may quote the server's reply. */
    private IllegalStateException failure(MessagingException e, EmailConfig config, String server) {
        if (Thread.currentThread().isInterrupted()) {
            return new IllegalStateException(message("email.error.interrupted", server));
        }
        if (e instanceof AuthenticationFailedException) {
            return new IllegalStateException(message("email.error.auth", server));
        }
        if (e instanceof SendFailedException) {
            // a sender or a recipient refused: the reply of the server says why
            return new IllegalStateException(message("email.error.refused", server, reply(e, config)));
        }
        SSLException ssl = cause(e, SSLException.class);
        if (ssl != null) {
            // handshake refused, untrusted certificate, name of the server not in its certificate...
            return new IllegalStateException(message("email.error.tls", server, ssl.getClass().getSimpleName()));
        }
        IOException io = cause(e, IOException.class);
        if (io != null) {
            // connection refused or lost, unknown host, timeout, TLS handshake...: the kind only
            return new IllegalStateException(message("email.error.connect", server, io.getClass().getSimpleName()));
        }
        return new IllegalStateException(message("email.error.send", server, reply(e, config)));
    }

    /** The first cause of this type, in the chain of causes and of the next exceptions of Jakarta Mail. */
    private static <T extends Throwable> T cause(Throwable e, Class<T> type) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = e; current != null && seen.add(current); ) {
            if (current != e && type.isInstance(current)) {
                return type.cast(current);
            }
            Throwable next = current.getCause();
            if (next == null && current instanceof MessagingException messaging) {
                next = messaging.getNextException();
            }
            current = next;
        }
        return null;
    }

    /** The start of the server's reply (or of the message), on one line, with the credentials blanked out. */
    private static String reply(MessagingException e, EmailConfig config) {
        Exception next = e.getNextException();
        String text = e.getMessage() != null ? e.getMessage()
                : next != null && next.getMessage() != null ? next.getMessage() : e.getClass().getSimpleName();
        text = blank(blank(text, config.password()), config.user()).strip().replaceAll("\\s+", " ");
        return text.length() > REPLY_START ? text.substring(0, REPLY_START) + "..." : text;
    }

    private static String blank(String text, String secret) {
        return secret == null || secret.isBlank() ? text : text.replace(secret, "***");
    }

    // --- configuration ---

    private void validate(EmailConfig config) {
        if (config == null || config.host() == null || config.host().isBlank()) {
            throw new IllegalArgumentException(message("email.error.host-missing"));
        }
        int port = port(config);
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(message("email.error.port-invalid", String.valueOf(port)));
        }
        security(config);
        details(config);
        if (config.from() == null || config.from().isBlank()) {
            throw new IllegalArgumentException(message("email.error.from-missing"));
        }
        if (config.to().stream().allMatch(String::isBlank)) {
            throw new IllegalArgumentException(message("email.error.to-missing"));
        }
    }

    /** The port set, else the usual one of the security: 465 for ssl, 587 otherwise. */
    private int port(EmailConfig config) {
        if (config.port() != null) {
            return config.port();
        }
        return "ssl".equals(security(config)) ? 465 : 587;
    }

    private static boolean hasUser(EmailConfig config) {
        return config.user() != null && !config.user().isBlank();
    }

    private String security(EmailConfig config) {
        return oneOf(config.security(), "starttls", SECURITIES, "email.error.security-unknown");
    }

    private String details(EmailConfig config) {
        return oneOf(config.details(), "errors", DETAILS, "email.error.details-unknown");
    }

    private String oneOf(String value, String byDefault, Set<String> allowed, String error) {
        if (value == null || value.isBlank()) {
            return byDefault;
        }
        String name = value.strip().toLowerCase(Locale.ROOT);
        if (!allowed.contains(name)) {
            throw new IllegalArgumentException(message(error, value));
        }
        return name;
    }

    // --- message ---

    private MimeMessage message(Session session, EmailConfig config, RunReport report) {
        EmailContent content = new EmailContent(report, details(config), this::message);
        MimeMessage message = new MimeMessage(session);
        try {
            message.setFrom(address(config.from(), "email.error.from-invalid"));
            List<String> to = config.to().stream().filter(t -> !t.isBlank()).toList();
            InternetAddress[] recipients = new InternetAddress[to.size()];
            for (int i = 0; i < to.size(); i++) {
                recipients[i] = address(to.get(i), "email.error.to-invalid", i + 1);
            }
            message.setRecipients(Message.RecipientType.TO, recipients);
            String subject = config.subject() == null || config.subject().isBlank()
                    ? EmailConfig.DEFAULT_SUBJECT : config.subject();
            message.setSubject(content.fill(subject), "UTF-8");
            message.setSentDate(new Date());

            MimeBodyPart text = new MimeBodyPart();
            text.setText(content.text(), "UTF-8", "plain");
            MimeBodyPart html = new MimeBodyPart();
            html.setText(content.html(), "UTF-8", "html");
            // the reader shows the last part it can: the HTML one, the text one otherwise
            message.setContent(new MimeMultipart("alternative", text, html));
            message.saveChanges();
        } catch (MessagingException e) {
            throw new IllegalStateException(message("email.error.send", config.host().strip() + ":" + port(config),
                    reply(e, config)));
        }
        return message;
    }

    /** The address, strictly parsed; a failure names the field, not the value (it may be the user). */
    private InternetAddress address(String text, String error, Object... args) {
        try {
            return new InternetAddress(text.strip(), true);
        } catch (AddressException e) {
            throw new IllegalArgumentException(message(error, args));
        }
    }

    /** A localised message of the plugin; its key and arguments when the action has no plugin (built by hand). */
    private String message(String key, Object... args) {
        IPlugin plugin = getPlugin();
        CombinedResourceBundle bundle = plugin == null ? null : plugin.getResourceBundle();
        if (bundle == null) {
            return key + Arrays.toString(args);
        }
        return bundle.getString("plugin.notify-email." + key, args);
    }
}

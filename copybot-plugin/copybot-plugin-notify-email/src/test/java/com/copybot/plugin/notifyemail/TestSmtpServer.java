package com.copybot.plugin.notifyemail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A minimal SMTP server on the loopback for the tests: it answers EHLO, AUTH PLAIN/LOGIN, MAIL, RCPT, DATA, RSET,
 * NOOP and QUIT, and records what it receives. {@link #silent} accepts connections and never answers (a dead
 * server); {@link #authReply} refuses the authentication with that reply; {@link #dropAfterData} resets the\n * connection once a message is accepted.
 */
public final class TestSmtpServer implements AutoCloseable {

    /** One message received: the envelope and the DATA, dot-unstuffed, lines ending in CRLF. */
    public record Received(String from, List<String> to, String data) {
    }

    private final ServerSocket server;
    private final Thread acceptor;
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();

    public final List<Received> messages = new CopyOnWriteArrayList<>();
    /** The decoded AUTH exchanges ("user\0user\0password" for PLAIN, "user", "password" for LOGIN). */
    public final List<String> auth = new CopyOnWriteArrayList<>();
    public volatile boolean silent;
    public volatile String authReply;
    /** After accepting a message (250), the connection is reset: QUIT can neither be sent nor answered. */
    public volatile boolean dropAfterData;

    public TestSmtpServer() {
        try {
            server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        acceptor = Thread.ofPlatform().daemon().name("test-smtp").start(this::accept);
    }

    public int port() {
        return server.getLocalPort();
    }

    private void accept() {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                sockets.add(socket);
                Thread.ofPlatform().daemon().name("test-smtp-session").start(() -> session(socket));
            } catch (IOException e) {
                return; // closed
            }
        }
    }

    private void session(Socket socket) {
        try (socket) {
            if (silent) {
                // a dead server: the connection is open, nothing ever comes
                socket.getInputStream().read();
                return;
            }
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = socket.getOutputStream();
            reply(out, "220 test ESMTP");
            String from = null;
            List<String> to = new ArrayList<>();
            String line;
            while ((line = in.readLine()) != null) {
                String upper = line.toUpperCase();
                if (upper.startsWith("EHLO") || upper.startsWith("HELO")) {
                    reply(out, "250-test\r\n250-8BITMIME\r\n250-AUTH PLAIN LOGIN\r\n250 OK");
                } else if (upper.startsWith("AUTH PLAIN")) {
                    String rest = line.length() > 10 ? line.substring(10).strip() : "";
                    if (rest.isEmpty()) {
                        reply(out, "334 ");
                        rest = in.readLine();
                    }
                    auth.add(decode(rest));
                    authAnswer(out);
                } else if (upper.startsWith("AUTH LOGIN")) {
                    reply(out, "334 VXNlcm5hbWU6");
                    auth.add(decode(in.readLine()));
                    reply(out, "334 UGFzc3dvcmQ6");
                    auth.add(decode(in.readLine()));
                    authAnswer(out);
                } else if (upper.startsWith("MAIL FROM:")) {
                    from = line.substring(10).strip();
                    to = new ArrayList<>();
                    reply(out, "250 OK");
                } else if (upper.startsWith("RCPT TO:")) {
                    to.add(line.substring(8).strip());
                    reply(out, "250 OK");
                } else if (upper.equals("DATA")) {
                    reply(out, "354 go");
                    StringBuilder data = new StringBuilder();
                    String dataLine;
                    while ((dataLine = in.readLine()) != null && !dataLine.equals(".")) {
                        data.append(dataLine.startsWith(".") ? dataLine.substring(1) : dataLine).append("\r\n");
                    }
                    messages.add(new Received(from, List.copyOf(to), data.toString()));
                    reply(out, "250 queued");
                    if (dropAfterData) {
                        socket.setSoLinger(true, 0); // a reset, not an orderly close
                        return;
                    }
                } else if (upper.equals("QUIT")) {
                    reply(out, "221 bye");
                    return;
                } else {
                    reply(out, "250 OK"); // RSET, NOOP...
                }
            }
        } catch (IOException e) {
            // the client went away
        }
    }

    private void authAnswer(OutputStream out) throws IOException {
        String refusal = authReply;
        reply(out, refusal != null ? refusal : "235 authenticated");
    }

    private static String decode(String base64) {
        return base64 == null ? "" : new String(Base64.getDecoder().decode(base64.strip()), StandardCharsets.UTF_8);
    }

    private static void reply(OutputStream out, String text) throws IOException {
        out.write((text + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    @Override
    public void close() {
        try {
            server.close();
        } catch (IOException e) {
            // nothing to do
        }
        for (Socket socket : sockets) {
            try {
                socket.close();
            } catch (IOException e) {
                // nothing to do
            }
        }
        try {
            acceptor.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

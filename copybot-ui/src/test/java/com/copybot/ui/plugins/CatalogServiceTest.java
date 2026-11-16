package com.copybot.ui.plugins;

import com.copybot.ui.update.UpdateCheck;
import com.copybot.ui.update.UpdateException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

public class CatalogServiceTest {

    private static final byte[] ARCHIVE = "zip bytes".repeat(1_000).getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path dir;

    private HttpServer server;
    private String catalogue = PluginCatalogTest.FULL;
    private boolean catalogueListed = true;
    private boolean archiveListed = true;
    private boolean latestExists = true;

    @BeforeEach
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/latest", exchange -> {
            if (!latestExists) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                String assets = catalogueListed ? asset("catalogue.json", "/files/catalogue.json",
                        catalogue.getBytes(StandardCharsets.UTF_8)) : "";
                send(exchange, release("v1.2.0", assets));
            }
            exchange.close();
        });
        server.createContext("/tags/v1.0.0", exchange -> {
            send(exchange, release("v1.0.0", archiveListed
                    ? asset("copybot-plugin-metadata-extractor-1.0.0.zip", "/files/plugin.zip", ARCHIVE) : ""));
            exchange.close();
        });
        server.createContext("/files/catalogue.json", exchange -> {
            send(exchange, catalogue);
            exchange.close();
        });
        server.createContext("/files/plugin.zip", exchange -> {
            exchange.sendResponseHeaders(200, ARCHIVE.length);
            exchange.getResponseBody().write(ARCHIVE);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    public void stop() {
        server.stop(0);
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private String asset(String name, String path, byte[] content) {
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
            return "{\"name\": \"" + name + "\", \"browser_download_url\": \"" + base() + path + "\", \"size\": "
                    + content.length + ", \"digest\": \"sha256:" + digest + "\"}";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String release(String tag, String assets) {
        return "{\"tag_name\": \"" + tag + "\", \"html_url\": \"https://example.com/notes\", \"assets\": [" + assets + "]}";
    }

    private static void send(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private CatalogService service() {
        return new CatalogService(UpdateCheck.httpClient(), URI.create(base() + "/latest"), base() + "/tags/", "1.0.0");
    }

    @Test
    public void theCatalogueOfTheLatestReleaseIsRead() throws Exception {
        PluginCatalog catalog = service().fetch(dir);

        assertEquals(2, catalog.entries().size());
        assertEquals("metadata-extractor", catalog.entries().get(0).code());
    }

    @Test
    public void thePluginArchiveOfTheTagIsDownloadedAndChecked() throws Exception {
        PluginCatalog.Entry entry = PluginCatalog.parse(catalogue).entries().get(0);
        AtomicLong progress = new AtomicLong();

        Path zip = service().download(entry, dir, progress::set, () -> false);

        assertEquals(dir.resolve("copybot-plugin-metadata-extractor-1.0.0.zip"), zip);
        assertArrayEquals(ARCHIVE, Files.readAllBytes(zip));
        assertEquals(ARCHIVE.length, progress.get());
    }

    @Test
    public void noReleaseYetIsAReadableError() {
        latestExists = false;

        assertEquals("update.no-release", assertThrows(UpdateException.class, () -> service().fetch(dir)).key());
    }

    @Test
    public void aReleaseWithoutCatalogueIsAReadableError() {
        catalogueListed = false;

        assertEquals("plugins.error.no-catalogue",
                assertThrows(UpdateException.class, () -> service().fetch(dir)).key());
    }

    @Test
    public void aBadCatalogueIsAReadableError() {
        catalogue = "not json";

        assertEquals("update.error.unreadable", assertThrows(UpdateException.class, () -> service().fetch(dir)).key());
    }

    @Test
    public void anArchiveMissingFromItsReleaseIsAReadableError() throws Exception {
        archiveListed = false;
        PluginCatalog.Entry entry = PluginCatalog.parse(catalogue).entries().get(0);

        assertEquals("plugins.error.no-asset", assertThrows(UpdateException.class,
                () -> service().download(entry, dir, n -> { }, () -> false)).key());
    }

    /** A 404 on the release of the tag is not "no release of Copybot yet": the release of the plugin is named. */
    @Test
    public void aMissingReleaseOfTheTagNamesTheTag() throws Exception {
        PluginCatalog.Entry entry = PluginCatalog.parse(catalogue.replace("\"tag\": \"v1.0.0\"", "\"tag\": \"v9.9.9\""))
                .entries().get(0);
        assertEquals("v9.9.9", entry.tag());

        UpdateException e = assertThrows(UpdateException.class,
                () -> service().download(entry, dir, n -> { }, () -> false));

        assertEquals("plugins.error.no-release", e.key());
        assertArrayEquals(new Object[]{"v9.9.9", entry.asset()}, e.args());
    }
}

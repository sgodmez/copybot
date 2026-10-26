package com.copybot.ui.plugins;

import com.copybot.engine.plugin.report.PluginEntry;
import com.copybot.engine.plugin.report.PluginReport;
import com.copybot.engine.plugin.report.PluginSource;
import com.copybot.engine.plugin.report.PluginStatus;
import com.copybot.ui.update.UpdateCheck;
import com.copybot.ui.update.UpdateException;
import com.copybot.ui.update.UpdateService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** The plugin changes without JavaFX: the catalogue and the archives are faked, the launched process is recorded. */
public class PluginServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");
    private static final String A = "com.copybot.plugin.a";
    private static final String B = "com.copybot.plugin.b";
    private static final String C = "com.copybot.plugin.c";
    private static final String D = "com.copybot.plugin.d";

    /** A: 1.1.0 (1.0.0 installed: an update), B: installed at its version, C: not installed, D: another plugin API. */
    private static final String CATALOGUE = """
            { "format": 1, "plugins": [
              { "module": "com.copybot.plugin.a", "code": "a", "version": "1.1.0", "engine": "1.0", "tag": "v1",
                "asset": "copybot-plugin-a-1.1.0.zip", "description": { "en": "A" } },
              { "module": "com.copybot.plugin.b", "code": "b", "version": "1.0.0", "engine": "1.0", "tag": "v1",
                "asset": "copybot-plugin-b-1.0.0.zip", "description": { "en": "B" } },
              { "module": "com.copybot.plugin.c", "code": "c", "version": "1.0.0", "engine": "1.0", "tag": "v1",
                "asset": "copybot-plugin-c-1.0.0.zip", "description": { "en": "C" } },
              { "module": "com.copybot.plugin.d", "code": "d", "version": "1.0.0", "engine": "2.0", "tag": "v1",
                "asset": "copybot-plugin-d-1.0.0.zip", "description": { "en": "D" } }
            ] }""";

    @TempDir
    Path dir;

    private HttpServer server;
    private Path appDir;
    private Path pluginsDir;
    private boolean busy;
    private boolean writable = true;
    private final List<UpdateService.RunningProcess> processes = new ArrayList<>();
    private final List<List<String>> launched = new ArrayList<>();
    private final Settings settings = new Settings();
    private final FakeCatalogs catalogs = new FakeCatalogs();
    private PluginReport report;
    /** The portable archive of the release 1.2.0, null when the release has no asset. */
    private byte[] portableZip;

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/latest", exchange -> {
            String assets = portableZip == null ? "" : "{\"name\": \"copybot-1.2.0-windows-x64.zip\", "
                    + "\"browser_download_url\": \"http://127.0.0.1:" + server.getAddress().getPort()
                    + "/files/portable.zip\", \"size\": " + portableZip.length + ", \"digest\": \"sha256:"
                    + HexFormat.of().formatHex(sha256(portableZip)) + "\"}";
            byte[] body = ("{\"tag_name\": \"v1.2.0\", \"html_url\": \"https://example.com/notes\", \"assets\": ["
                    + assets + "]}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/files/", exchange -> {
            exchange.sendResponseHeaders(200, portableZip.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(portableZip);
            }
        });
        server.start();
        appDir = Files.createDirectories(dir.resolve("app"));
        pluginsDir = Files.createDirectories(appDir.resolve("plugins"));
        report = report(installed(A, "1.0.0", pluginsDir.resolve("old-a")), installed(B, "1.0.0", pluginsDir.resolve("b")));
    }

    @AfterEach
    public void stop() {
        server.stop(0);
    }

    private static PluginEntry installed(String module, String version, Path path) {
        return new PluginEntry(module, version, PluginSource.PLUGIN_PATH, path, PluginStatus.LOADED, null, List.of(),
                List.of(), List.of(), List.of(), null);
    }

    private PluginReport report(PluginEntry... entries) {
        return new PluginReport(dir.resolve("config.json"), pluginsDir, List.of(), List.of(), List.of(entries));
    }

    private Path tmp() {
        try {
            return Files.createDirectories(dir.resolve("tmp"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Path> tempContent() throws IOException {
        try (Stream<Path> content = Files.list(tmp())) {
            return content.toList();
        }
    }

    private PluginService service() {
        UpdateService.Host host = new UpdateService.Host(appDir.resolve("Copybot.exe").toString(), 4242L, 4241L, null,
                true, () -> List.copyOf(processes), folder -> writable);
        UpdateService updates = new UpdateService(UpdateCheck.httpClient(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/latest"), "1.0.0", host,
                dir.resolve("logs"), tmp(), Clock.fixed(NOW, ZoneOffset.UTC), settings, () -> busy, launched::add);
        return new PluginService(updates, catalogs, () -> report, "1.0");
    }

    private List<PluginRow> rows(PluginService service) throws Exception {
        return service.rows(service.catalog());
    }

    private static PluginRow row(List<PluginRow> rows, String module) {
        return rows.stream().filter(r -> r.entry().module().equals(module)).findFirst().orElseThrow();
    }

    private static final class Settings implements UpdateService.Settings {
        boolean auto = true;
        Optional<Instant> last = Optional.empty();

        @Override
        public boolean autoCheck() {
            return auto;
        }

        @Override
        public Optional<Instant> lastCheck() {
            return last;
        }

        @Override
        public void checked(Instant when) {
            last = Optional.of(when);
        }
    }

    /** Serves {@link #CATALOGUE} and an archive {@code <code>/<code>.jar} per entry. */
    private static final class FakeCatalogs implements PluginService.Catalogs {
        final AtomicInteger fetches = new AtomicInteger();
        final List<String> downloads = new ArrayList<>();
        UpdateException fetchFailure;
        String failing;

        @Override
        public PluginCatalog fetch(Path dir) throws UpdateException {
            fetches.incrementAndGet();
            if (fetchFailure != null) {
                throw fetchFailure;
            }
            return PluginCatalog.parse(CATALOGUE);
        }

        final List<String> releaseFetches = new ArrayList<>();

        @Override
        public PluginCatalog fetch(com.copybot.ui.update.Release release, Path dir) throws UpdateException {
            releaseFetches.add(release.version());
            return fetch(dir);
        }

        @Override
        public Path download(PluginCatalog.Entry entry, Path dir, LongConsumer progress, BooleanSupplier cancelled)
                throws UpdateException {
            downloads.add(entry.code());
            if (entry.code().equals(failing)) {
                throw new UpdateException("update.error.digest");
            }
            Path zip = dir.resolve(entry.asset());
            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
                out.putNextEntry(new ZipEntry(entry.code() + "/" + entry.code() + ".jar"));
                out.write(1);
                out.closeEntry();
                out.putNextEntry(new ZipEntry(entry.code() + "/lib/dep.jar"));
                out.write(2);
                out.closeEntry();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            progress.accept(3);
            return zip;
        }
    }

    @Test
    public void theChangesAreRefusedWhileBusy() throws Exception {
        PluginService service = service();
        List<PluginRow> rows = rows(service);
        busy = true;

        assertEquals(Optional.of("update.busy"), service.applyBlocked());
        UpdateException e = assertThrows(UpdateException.class, () -> service.prepare(List.of(row(rows, C)),
                List.of(), (i, n, code, bytes) -> { }, () -> false));
        assertEquals("update.busy", e.key());
        assertTrue(catalogs.downloads.isEmpty());
        assertTrue(tempContent().isEmpty());
    }

    @Test
    public void theChangesAreRefusedWhileAnotherCopybotRunsFromTheSameFolder() throws Exception {
        PluginService service = service();
        List<PluginRow> rows = rows(service);
        processes.add(new UpdateService.RunningProcess(77L, Optional.of(appDir.resolve("Copybot.exe").toString())));

        UpdateException e = assertThrows(UpdateException.class, () -> service.prepare(List.of(row(rows, C)),
                List.of(), (i, n, code, bytes) -> { }, () -> false));
        assertEquals("plugins.other-instance", e.key());
        assertTrue(catalogs.downloads.isEmpty());
    }

    @Test
    public void installsUpdatesAndRemovalsBecomeTheManifestOfTheScript() throws Exception {
        PluginService service = service();
        List<PluginRow> rows = rows(service);
        List<String> seen = new ArrayList<>();

        UpdateService.Prepared prepared = service.prepare(List.of(row(rows, A), row(rows, C)), List.of(row(rows, B)),
                (i, n, code, bytes) -> seen.add(i + "/" + n + " " + code), () -> false);

        Path temp = prepared.tempDir();
        assertEquals(tmp(), temp.getParent());
        assertTrue(temp.getFileName().toString().startsWith("copybot-update-"));
        assertTrue(Files.isRegularFile(temp.resolve("a/a.jar")));
        assertTrue(Files.isRegularFile(temp.resolve("c/lib/dep.jar")));
        assertFalse(Files.exists(temp.resolve("copybot-plugin-a-1.1.0.zip")), "the archive is deleted once unpacked");
        assertTrue(seen.contains("0/2 a") && seen.contains("1/2 c"), seen.toString());

        List<String> command = prepared.command();
        Path manifest = Path.of(command.get(command.indexOf("-PluginManifest") + 1));
        assertEquals(temp, manifest.getParent());
        assertTrue(Files.isRegularFile(Path.of(command.get(command.indexOf("-File") + 1))));
        assertEquals(appDir.toString(), command.get(command.indexOf("-AppDir") + 1));
        JsonObject json = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
        assertEquals(temp.resolve("a").toString(), json.getAsJsonArray("install").get(0).getAsJsonObject()
                .get("source").getAsString());
        assertEquals("c", json.getAsJsonArray("install").get(1).getAsJsonObject().get("target").getAsString());
        assertEquals(List.of(pluginsDir.resolve("old-a").toString(), pluginsDir.resolve("b").toString()),
                json.getAsJsonArray("remove").asList().stream().map(e -> e.getAsString()).toList());

        service.launch(prepared, () -> false);
        assertEquals(List.of(command), launched);
    }

    @Test
    public void aFailedDownloadLaunchesNothingAndDeletesTheTemporaryFolder() throws Exception {
        PluginService service = service();
        List<PluginRow> rows = rows(service);
        catalogs.failing = "c";

        UpdateException e = assertThrows(UpdateException.class, () -> service.prepare(
                List.of(row(rows, A), row(rows, C)), List.of(), (i, n, code, bytes) -> { }, () -> false));
        assertEquals("update.error.digest", e.key());
        assertTrue(tempContent().isEmpty());
        assertTrue(launched.isEmpty());
    }

    @Test
    public void aCancelBeforeTheNextArchiveStopsEverything() throws Exception {
        PluginService service = service();
        List<PluginRow> rows = rows(service);
        AtomicInteger asked = new AtomicInteger();

        UpdateException e = assertThrows(UpdateException.class, () -> service.prepare(
                List.of(row(rows, A), row(rows, C)), List.of(), (i, n, code, bytes) -> { },
                () -> !catalogs.downloads.isEmpty() && asked.incrementAndGet() > 0));
        assertEquals("update.error.cancelled", e.key());
        assertEquals(List.of("a"), catalogs.downloads);
        assertTrue(tempContent().isEmpty());
    }

    @Test
    public void aLateCancelLaunchesNothing() throws Exception {
        PluginService service = service();
        List<PluginRow> rows = rows(service);
        UpdateService.Prepared prepared = service.prepare(List.of(row(rows, C)), List.of(),
                (i, n, code, bytes) -> { }, () -> false);

        UpdateException e = assertThrows(UpdateException.class, () -> service.launch(prepared, () -> true));
        assertEquals("update.error.cancelled", e.key());
        assertTrue(launched.isEmpty());
        assertTrue(tempContent().isEmpty());
    }

    @Test
    public void aFolderOutsideThePluginsFolderIsNeverRemoved() throws Exception {
        report = report(installed(B, "1.0.0", dir.resolve("elsewhere/b")));
        PluginService service = service();
        List<PluginRow> rows = rows(service);

        UpdateException e = assertThrows(UpdateException.class, () -> service.prepare(List.of(), List.of(row(rows, B)),
                (i, n, code, bytes) -> { }, () -> false));
        assertEquals("plugins.error.outside", e.key());
        assertTrue(tempContent().isEmpty());
    }

    @Test
    public void theAdministratorIsNeededWhenThePluginsFolderOrTheAppFolderCannotBeWritten() throws Exception {
        List<Path> asked = new ArrayList<>();
        PluginService service = service();
        writable = false;
        assertTrue(service.adminApprovalNeeded());
        writable = true;
        assertFalse(service.adminApprovalNeeded());

        UpdateService.Host host = new UpdateService.Host(appDir.resolve("Copybot.exe").toString(), 1L, -1L, null,
                true, List::of, folder -> asked.add(folder));
        UpdateService updates = new UpdateService(UpdateCheck.httpClient(), URI.create("http://127.0.0.1:1/"),
                "1.0.0", host, dir, tmp(), Clock.systemUTC(), settings, () -> false, launched::add);
        new PluginService(updates, catalogs, () -> report, "1.0").adminApprovalNeeded();
        Files.delete(pluginsDir);
        new PluginService(updates, catalogs, () -> report, "1.0").adminApprovalNeeded();
        assertEquals(List.of(pluginsDir, appDir), asked);
    }

    @Test
    public void theDailyCheckAlsoCountsThePluginUpdates() throws Exception {
        PluginService.Check check = service().automaticCheck();

        assertEquals("1.2.0", check.release().orElseThrow().version());
        assertEquals(1, check.pluginUpdates()); // A only: D is for another plugin API, B is up to date
        assertEquals(Optional.of(NOW), settings.last);
    }

    /**
     * With a release, the plugins counted are those updated with it: every installed plugin with a newer version in
     * the catalogue of that release, even one for a plugin API this version does not have (spec update-with-plugins).
     */
    @Test
    public void withAReleaseTheCheckCountsThePluginsUpdatedWithIt() throws Exception {
        report = report(installed(A, "1.0.0", pluginsDir.resolve("old-a")), installed(B, "1.0.0", pluginsDir.resolve("b")),
                installed(D, "0.9.0", pluginsDir.resolve("d")));

        PluginService.Check check = service().automaticCheck();

        assertEquals("1.2.0", check.release().orElseThrow().version());
        assertEquals(2, check.pluginUpdates()); // A, and D for the plugin API of the release
    }

    /**
     * One temporary folder, one script: the application, then the plugins of the catalogue of the release installed
     * (not of whatever release is the latest by then) with a newer version (spec update-with-plugins).
     */
    @Test
    public void anApplicationUpdateTakesThePluginsWithIt() throws Exception {
        portableZip = zipOf("Copybot/Copybot.exe");
        PluginService service = service();
        var release = service.checkNow().release().orElseThrow();
        List<String> seen = new ArrayList<>();

        UpdateService.Prepared prepared = service.prepareUpdate(release, Set.of(A, B, D), bytes -> { },
                (i, n, code, bytes) -> seen.add(i + "/" + n + " " + code), () -> false);

        assertEquals(List.of("1.2.0"), catalogs.releaseFetches);
        Path temp = prepared.tempDir();
        List<String> command = prepared.command();
        assertEquals(temp.resolve("Copybot").toString(), command.get(command.indexOf("-NewDir") + 1));
        JsonObject json = manifestOf(command);
        assertEquals("a", json.getAsJsonArray("install").get(0).getAsJsonObject().get("target").getAsString());
        assertEquals(List.of(pluginsDir.resolve("old-a").toString()),
                json.getAsJsonArray("remove").asList().stream().map(e -> e.getAsString()).toList());
        assertTrue(Files.isRegularFile(temp.resolve("a/a.jar")));
        assertTrue(seen.contains("0/1 a"), seen.toString());
    }

    @Test
    public void onlyThePluginsOfThePluginsFolderGoWithTheApplication() throws Exception {
        portableZip = zipOf("Copybot/Copybot.exe");
        // B up to date, C not installed, D elsewhere, A without a folder of its own (never touched either)
        report = report(installed(A, "1.0.0", null), installed(B, "1.0.0", pluginsDir.resolve("b")),
                installed(D, "0.9.0", dir.resolve("elsewhere/d")));
        PluginService service = service();
        var release = service.checkNow().release().orElseThrow();

        UpdateService.Prepared prepared = service.prepareUpdate(release, Set.of(A, B, D), bytes -> { },
                (i, n, code, bytes) -> { }, () -> false);

        assertFalse(prepared.command().contains("-PluginManifest"));
        assertTrue(catalogs.downloads.isEmpty());
    }

    @Test
    public void onlyTheChosenPluginsGoWithTheApplication() throws Exception {
        portableZip = zipOf("Copybot/Copybot.exe");
        report = report(installed(A, "1.0.0", pluginsDir.resolve("old-a")), installed(D, "0.9.0", pluginsDir.resolve("d")));
        PluginService service = service();
        var release = service.checkNow().release().orElseThrow();

        UpdateService.Prepared prepared = service.prepareUpdate(release, Set.of(A), bytes -> { },
                (i, n, code, bytes) -> { }, () -> false);

        JsonObject json = manifestOf(prepared.command());
        assertEquals(1, json.getAsJsonArray("install").size());
        assertEquals("a", json.getAsJsonArray("install").get(0).getAsJsonObject().get("target").getAsString());
        assertEquals(List.of("a"), catalogs.downloads);
    }

    @Test
    public void noChosenPluginIsTheUsualUpdateWithoutPlugins() throws Exception {
        portableZip = zipOf("Copybot/Copybot.exe");
        PluginService service = service();
        var release = service.checkNow().release().orElseThrow();

        UpdateService.Prepared prepared = service.prepareUpdate(release, Set.of(), bytes -> { },
                (i, n, code, bytes) -> { }, () -> false);

        assertFalse(prepared.command().contains("-PluginManifest"));
        assertTrue(prepared.command().contains("-NewDir"));
        assertTrue(catalogs.downloads.isEmpty());
    }

    @Test
    public void theLinesOfTheUpdatesWindowAreTheInstalledPluginsOfThePluginsFolder() throws Exception {
        // A to update, B up to date (both kept), C not installed, D installed elsewhere
        report = report(installed(A, "1.0.0", pluginsDir.resolve("old-a")), installed(B, "1.0.0", pluginsDir.resolve("b")),
                installed(D, "0.9.0", dir.resolve("elsewhere/d")));
        PluginService service = service();

        List<PluginRow> lines = service.rowsForUpdates(service.catalog());

        assertEquals(List.of(A, B), lines.stream().map(r -> r.entry().module()).toList());
    }

    @Test
    public void aPluginWithoutAFolderOfItsOwnIsNotALineOfTheUpdatesWindow() throws Exception {
        report = report(installed(A, "1.0.0", null), installed(B, "1.0.0", pluginsDir.resolve("b")));
        PluginService service = service();

        assertEquals(List.of(B), service.rowsForUpdates(service.catalog()).stream()
                .map(r -> r.entry().module()).toList());
    }

    @Test
    public void anUnreadableCatalogueUpdatesTheApplicationAlone() throws Exception {
        portableZip = zipOf("Copybot/Copybot.exe");
        PluginService service = service();
        var release = service.checkNow().release().orElseThrow();
        catalogs.fetchFailure = new UpdateException("plugins.error.no-catalogue");

        UpdateService.Prepared prepared = service.prepareUpdate(release, Set.of(A), bytes -> { },
                (i, n, code, bytes) -> { }, () -> false);

        assertFalse(prepared.command().contains("-PluginManifest"));
        assertTrue(prepared.command().contains("-NewDir"));
    }

    private static JsonObject manifestOf(List<String> command) throws IOException {
        Path manifest = Path.of(command.get(command.indexOf("-PluginManifest") + 1));
        return JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
    }

    private static byte[] zipOf(String... names) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (String name : names) {
                out.putNextEntry(new ZipEntry(name));
                out.write(1);
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    @Test
    public void theDailyCheckReadsNoCatalogueWhenNotDue() throws Exception {
        settings.last = Optional.of(NOW.minus(Duration.ofHours(2)));

        PluginService.Check check = service().automaticCheck();

        assertTrue(check.release().isEmpty());
        assertEquals(0, check.pluginUpdates());
        assertEquals(0, catalogs.fetches.get());
    }

    @Test
    public void anUnreadableCatalogueLeavesTheAppUpdateAlone() throws Exception {
        catalogs.fetchFailure = new UpdateException("plugins.error.no-catalogue");

        PluginService.Check check = service().checkNow();

        assertEquals("1.2.0", check.release().orElseThrow().version());
        assertEquals(0, check.pluginUpdates());
    }

    @Test
    public void theCatalogueIsKeptInMemory() throws Exception {
        PluginService service = service();
        assertTrue(service.cached().isEmpty());

        PluginCatalog first = service.catalog();

        assertSame(first, service.catalog());
        assertEquals(Optional.of(first), service.cached());
        assertEquals(1, catalogs.fetches.get());
        assertTrue(tempContent().isEmpty(), "the folder of the download is deleted");
    }

    @Test
    public void theMissingPluginsOfAPipelineFoundInTheCatalogueAreInstallable() throws Exception {
        PluginService service = service();
        PluginCatalog catalog = service.catalog();

        List<PluginCatalog.Entry> installable = service.installable(Set.of(C, D, "com.other"), catalog);

        assertEquals(List.of("c"), installable.stream().map(PluginCatalog.Entry::code).toList());
    }

    /** Not loaded but installed: the Install of a pipeline leads somewhere only when an update fixes it. */
    @Test
    public void anInstalledPluginThatFailedToLoadIsInstallableOnlyWithACompatibleUpdate() throws Exception {
        report = report(failed(A, "1.0.0", pluginsDir.resolve("a"), "plugin.load.api-incompatible"),
                failed(B, "1.0.0", pluginsDir.resolve("b"), "plugin.load.api-incompatible"),
                failed(C, "1.0.0", pluginsDir.resolve("c"), "plugin.load.missing-dependency"));
        PluginService service = service();
        PluginCatalog catalog = service.catalog();

        List<PluginCatalog.Entry> installable = service.installable(Set.of(A, B, C, D), catalog);

        assertEquals(List.of("a"), installable.stream().map(PluginCatalog.Entry::code).toList()); // a 1.1.0 fixes it
        assertEquals(List.of(PluginState.UPDATE_AVAILABLE, PluginState.INSTALLED_INCOMPATIBLE, PluginState.UP_TO_DATE,
                        PluginState.INCOMPATIBLE),
                service.rowsOf(Set.of(A, B, C, D), catalog).stream().map(PluginRow::state).toList());
    }

    private static PluginEntry failed(String module, String version, Path path, String errorKey) {
        return new PluginEntry(module, version, PluginSource.PLUGIN_PATH, path, PluginStatus.ERROR, "failed",
                List.of(), List.of(), List.of(), List.of(), errorKey);
    }
}

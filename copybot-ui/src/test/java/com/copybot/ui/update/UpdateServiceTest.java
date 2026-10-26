package com.copybot.ui.update;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** The update flow without JavaFX: GitHub is a local server, the launched process is only recorded. */
public class UpdateServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");

    @TempDir
    Path dir;

    private HttpServer server;
    private final AtomicInteger checks = new AtomicInteger();
    private volatile int status = 200;
    private volatile String latestVersion = "1.2.0";
    /** The files of the release served, by name. */
    private final Map<String, byte[]> files = new LinkedHashMap<>();

    private Path appDir;
    private String appPath;
    private String version = "1.0.0";
    private boolean busy;
    private final TestSettings settings = new TestSettings();
    private final List<List<String>> launched = new ArrayList<>();
    /** The processes of the system, as the service sees them. */
    private final List<UpdateService.RunningProcess> processes = new ArrayList<>();
    private boolean writable = true;
    private Path configFile;

    @BeforeEach
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/latest", exchange -> {
            checks.incrementAndGet();
            byte[] body = latestJson().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, status == 200 ? body.length : -1);
            if (status == 200) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.createContext("/files/", exchange -> {
            byte[] body = files.get(exchange.getRequestURI().getPath().substring("/files/".length()));
            exchange.sendResponseHeaders(body == null ? 404 : 200, body == null ? -1 : body.length);
            if (body != null) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        appDir = Files.createDirectories(dir.resolve("app"));
        appPath = appDir.resolve("Copybot.exe").toString();
    }

    @AfterEach
    public void stop() {
        server.stop(0);
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private String latestJson() {
        String assets = files.entrySet().stream()
                .map(f -> "{\"name\": \"" + f.getKey() + "\", \"browser_download_url\": \"" + base() + "/files/"
                        + f.getKey() + "\", \"size\": " + f.getValue().length + ", \"digest\": \"sha256:"
                        + sha256(f.getValue()) + "\"}")
                .collect(Collectors.joining(", "));
        return "{\"tag_name\": \"v" + latestVersion + "\", \"html_url\": \"https://example.com/notes\", \"assets\": ["
                + assets + "]}";
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private UpdateService service() {
        return service(true, launched::add);
    }

    /** This process is 4242, started by the launcher 4241. */
    private UpdateService service(boolean windows, UpdateService.Launcher launcher) {
        UpdateService.Host host = new UpdateService.Host(appPath, 4242L, 4241L, configFile, windows,
                () -> List.copyOf(processes), folder -> writable);
        return new UpdateService(UpdateCheck.httpClient(), URI.create(base() + "/latest"), version, host,
                dir.resolve("logs"), tmp(), Clock.fixed(NOW, ZoneOffset.UTC), settings, () -> busy, launcher);
    }

    private Path tmp() {
        try {
            return Files.createDirectories(dir.resolve("tmp"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class TestSettings implements UpdateService.Settings {
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

    private static byte[] zip(String... names) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (String name : names) {
                out.putNextEntry(new ZipEntry(name));
                out.write(name.getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    /** The manifest a plugin step writes in the temporary folder. */
    private static Path manifestIn(Path temp) {
        try {
            return Files.writeString(temp.resolve("plugin-manifest.json"), "{}");
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private List<Path> tempContent() throws IOException {
        try (Stream<Path> content = Files.list(tmp())) {
            return content.toList();
        }
    }

    @Test
    public void aDueCheckFindsANewerRelease() throws Exception {
        Optional<Release> found = service().automaticCheck();

        assertEquals("1.2.0", found.orElseThrow().version());
        assertEquals(Optional.of(NOW), settings.last);
    }

    @Test
    public void aCheckDoneLessThanADayAgoIsNotRepeated() throws Exception {
        settings.last = Optional.of(NOW.minus(Duration.ofHours(2)));

        assertTrue(service().automaticCheck().isEmpty());
        assertEquals(0, checks.get());
    }

    @Test
    public void noAutomaticCheckWhenTurnedOff() throws Exception {
        settings.auto = false;

        assertTrue(service().automaticCheck().isEmpty());
        assertEquals(0, checks.get());
    }

    /** The header of the Updates window: never, then the date of the last check, recorded even when it fails. */
    @Test
    public void theLastCheckIsTheDateRecordedByTheChecks() throws Exception {
        UpdateService service = service();
        assertTrue(service.lastCheck().isEmpty());

        service.checkNow();

        assertEquals(settings.last, service.lastCheck());
        assertTrue(service.lastCheck().isPresent());
    }

    @Test
    public void anOlderOrEqualReleaseIsNotOffered() throws Exception {
        latestVersion = "1.0.0";
        assertTrue(service().checkNow().isEmpty());

        latestVersion = "0.9.0";
        assertTrue(service().checkNow().isEmpty());
        assertEquals(2, checks.get());
    }

    @Test
    public void noCheckOutsideThePackagedApp() throws Exception {
        appPath = null;
        assertEquals(Optional.of("update.unavailable-dev"), service().unavailableReason());
        assertTrue(service().automaticCheck().isEmpty());

        appPath = appDir.resolve("Copybot.exe").toString();
        version = "1.1.0-SNAPSHOT";
        assertEquals(Optional.of("update.unavailable-dev"), service().unavailableReason());
        assertTrue(service().automaticCheck().isEmpty());

        assertEquals(0, checks.get());
        assertTrue(settings.last.isEmpty());
    }

    @Test
    public void aFailedCheckIsStillRecordedAsDone() throws Exception {
        status = 500;

        assertTrue(service().automaticCheck().isEmpty()); // only logged
        assertEquals(Optional.of(NOW), settings.last);

        settings.last = Optional.empty();
        UpdateException e = assertThrows(UpdateException.class, () -> service().checkNow()); // the manual check tells
        assertEquals("update.error.http", e.key());
        assertEquals(Optional.of(NOW), settings.last);
    }

    @Test
    public void theUpdateIsRefusedWhileBusy() throws Exception {
        files.put("copybot-1.2.0-windows-x64.zip", zip("Copybot/Copybot.exe"));
        Release release = service().checkNow().orElseThrow();
        busy = true;

        assertEquals(Optional.of("update.busy"), service().installBlocked());
        UpdateException e = assertThrows(UpdateException.class,
                () -> service().prepare(release, bytes -> { }, () -> false));
        assertEquals("update.busy", e.key());
        assertTrue(tempContent().isEmpty());
    }

    @Test
    public void theUpdateIsOfferedOnlyOnWindows() {
        UpdateService other = service(false, launched::add);

        assertEquals(Optional.of("update.windows-only"), other.installBlocked());
        assertEquals(Optional.empty(), service().installBlocked());
    }

    @Test
    public void theAssetFollowsTheInstallMode() throws Exception {
        Release release = new Release("1.2.0", URI.create("https://example.com/notes"), List.of());
        assertEquals("copybot-1.2.0-windows-x64.zip", service().assetName(release));

        Files.writeString(appDir.resolve("unins000.exe"), "uninstaller");
        assertEquals("copybot-1.2.0-setup.exe", service().assetName(release));
    }

    @Test
    public void anInstalledCopyRunsTheSetup() throws Exception {
        Files.writeString(appDir.resolve("unins000.exe"), "uninstaller");
        files.put("copybot-1.2.0-setup.exe", "setup".getBytes(StandardCharsets.UTF_8));
        files.put("copybot-1.2.0-windows-x64.zip", zip("Copybot/Copybot.exe"));
        UpdateService service = service();
        Release release = service.checkNow().orElseThrow();

        UpdateService.Prepared prepared = service.prepare(release, bytes -> { }, () -> false);
        service.launch(prepared, () -> false);

        Path setup = prepared.tempDir().resolve("copybot-1.2.0-setup.exe");
        assertTrue(prepared.tempDir().getFileName().toString().startsWith("copybot-update-"));
        assertEquals(List.of(UpdateApply.setupCommand(setup)), launched);
        assertEquals("setup", Files.readString(setup));
    }

    /** With plugins to update, the script runs the installer then copies the plugins (spec update-with-plugins §1). */
    @Test
    public void anInstalledCopyWithPluginsRunsTheScriptWithTheInstaller() throws Exception {
        Files.writeString(appDir.resolve("unins000.exe"), "uninstaller");
        files.put("copybot-1.2.0-setup.exe", "setup".getBytes(StandardCharsets.UTF_8));
        UpdateService service = service();
        Release release = service.checkNow().orElseThrow();
        List<Path> asked = new ArrayList<>();

        UpdateService.Prepared prepared = service.prepare(release, bytes -> { }, () -> false, temp -> {
            asked.add(temp);
            assertTrue(Files.isRegularFile(temp.resolve("copybot-1.2.0-setup.exe")), "the application comes first");
            return manifestIn(temp);
        });

        Path temp = prepared.tempDir();
        assertEquals(List.of(temp), asked);
        assertEquals(UpdateApply.installerScriptCommand(temp.resolve("apply-update.ps1"), 4242L, appDir,
                temp.resolve("copybot-1.2.0-setup.exe"), temp.resolve("plugin-manifest.json"), Path.of(appPath),
                dir.resolve("logs"), null), prepared.command());
        assertTrue(Files.isRegularFile(temp.resolve("apply-update.ps1")));
    }

    @Test
    public void aPortableCopyWithPluginsGivesTheManifestToTheScript() throws Exception {
        files.put("copybot-1.2.0-windows-x64.zip", zip("Copybot/Copybot.exe"));
        UpdateService service = service();
        Release release = service.checkNow().orElseThrow();

        UpdateService.Prepared prepared = service.prepare(release, bytes -> { }, () -> false, temp -> manifestIn(temp));

        Path temp = prepared.tempDir();
        assertEquals(UpdateApply.scriptCommand(temp.resolve("apply-update.ps1"), 4242L, appDir,
                temp.resolve("Copybot"), temp.resolve("plugin-manifest.json"), Path.of(appPath), dir.resolve("logs"),
                null), prepared.command());
    }

    /** No plugin to update (the step returns no manifest): exactly the update without plugins. */
    @Test
    public void anUpdateWithoutPluginsKeepsItsUsualCommand() throws Exception {
        Files.writeString(appDir.resolve("unins000.exe"), "uninstaller");
        files.put("copybot-1.2.0-setup.exe", "setup".getBytes(StandardCharsets.UTF_8));
        UpdateService service = service();
        Release release = service.checkNow().orElseThrow();

        UpdateService.Prepared prepared = service.prepare(release, bytes -> { }, () -> false, temp -> null);

        assertEquals(UpdateApply.setupCommand(prepared.tempDir().resolve("copybot-1.2.0-setup.exe")),
                prepared.command());
    }

    @Test
    public void aFailedPluginStepLeavesNothing() throws Exception {
        files.put("copybot-1.2.0-windows-x64.zip", zip("Copybot/Copybot.exe"));
        UpdateService service = service();
        Release release = service.checkNow().orElseThrow();

        UpdateException e = assertThrows(UpdateException.class, () -> service.prepare(release, bytes -> { },
                () -> false, temp -> {
                    throw new UpdateException("update.error.digest");
                }));

        assertEquals("update.error.digest", e.key());
        assertTrue(tempContent().isEmpty());
        assertTrue(launched.isEmpty());
    }

    @Test
    public void aPortableCopyRunsTheScript() throws Exception {
        files.put("copybot-1.2.0-setup.exe", "setup".getBytes(StandardCharsets.UTF_8));
        files.put("copybot-1.2.0-windows-x64.zip", zip("Copybot/Copybot.exe", "Copybot/app/copybot.jar"));
        UpdateService service = service();
        Release release = service.checkNow().orElseThrow();

        UpdateService.Prepared prepared = service.prepare(release, bytes -> { }, () -> false);
        service.launch(prepared, () -> false);

        Path temp = prepared.tempDir();
        assertTrue(Files.isRegularFile(temp.resolve("Copybot").resolve("app").resolve("copybot.jar")));
        assertEquals(List.of(UpdateApply.scriptCommand(temp.resolve("apply-update.ps1"), 4242L, appDir,
                temp.resolve("Copybot"), Path.of(appPath), dir.resolve("logs"), null)), launched);
        assertTrue(Files.isRegularFile(temp.resolve("apply-update.ps1")));
    }

    /** Copybot started with --config-file is relaunched with it. */
    @Test
    public void theScriptRelaunchesWithTheSameConfigFile() throws Exception {
        configFile = dir.resolve("my config.json");
        files.put("copybot-1.2.0-windows-x64.zip", zip("Copybot/Copybot.exe"));
        UpdateService service = service();

        UpdateService.Prepared prepared = service.prepare(service.checkNow().orElseThrow(), bytes -> { },
                () -> false);

        Path temp = prepared.tempDir();
        assertEquals(UpdateApply.scriptCommand(temp.resolve("apply-update.ps1"), 4242L, appDir,
                temp.resolve("Copybot"), Path.of(appPath), dir.resolve("logs"), configFile), prepared.command());
    }

    /** Another Copybot of the same folder (its launcher or this process aside) would lock the files. */
    @Test
    public void anotherInstanceOfTheSameFolderRefusesTheUpdate() throws Exception {
        files.put("copybot-1.2.0-windows-x64.zip", zip("Copybot/Copybot.exe"));
        Release release = service().checkNow().orElseThrow();
        processes.add(new UpdateService.RunningProcess(4242L, Optional.of(appPath)));
        processes.add(new UpdateService.RunningProcess(4241L, Optional.of(appPath)));
        processes.add(new UpdateService.RunningProcess(10L, Optional.of(dir.resolve("other/Copybot.exe").toString())));
        processes.add(new UpdateService.RunningProcess(11L, Optional.empty()));
        processes.add(new UpdateService.RunningProcess(12L, Optional.of(dir.resolve("app-old/Copybot.exe").toString())));
        assertEquals(Optional.empty(), service().otherInstance());

        processes.add(new UpdateService.RunningProcess(13L,
                Optional.of(appDir.resolve("runtime/bin/java.exe").toString().toUpperCase(Locale.ROOT))));

        assertEquals(Optional.of("update.other-instance"), service().otherInstance());
        UpdateException e = assertThrows(UpdateException.class,
                () -> service().prepare(release, bytes -> { }, () -> false));
        assertEquals("update.other-instance", e.key());
        assertTrue(tempContent().isEmpty());
    }

    @Test
    public void anAppFolderThatIsNotWritableNeedsAnAdministrator() {
        assertFalse(service().adminApprovalNeeded());

        writable = false;

        assertTrue(service().adminApprovalNeeded());
    }

    /** The script deletes only its own folder, the parent of the unzipped copy. */
    @Test
    public void theUnzippedCopyMustBeDirectlyInTheTemporaryFolder() throws Exception {
        Path temp = dir.resolve("copybot-update-1");
        UpdateService.requireDirectlyIn(temp.resolve("Copybot"), temp);

        assertThrows(IOException.class, () -> UpdateService.requireDirectlyIn(temp.resolve("x/Copybot"), temp));
        assertThrows(IOException.class, () -> UpdateService.requireDirectlyIn(dir.resolve("Copybot"), temp));
    }

    /** What an update left behind (the installer, an interrupted copy) is deleted at the next start, after a day. */
    @Test
    public void theOldUpdateFoldersAreDeletedAtStartup() throws Exception {
        Path old = Files.createDirectories(tmp().resolve("copybot-update-old"));
        Files.writeString(old.resolve("copybot-1.1.0-setup.exe"), "setup");
        Files.setLastModifiedTime(old, FileTime.from(NOW.minus(Duration.ofHours(25))));
        Path recent = Files.createDirectories(tmp().resolve("copybot-update-recent"));
        Files.setLastModifiedTime(recent, FileTime.from(NOW.minus(Duration.ofHours(2))));
        Path otherApp = Files.createDirectories(tmp().resolve("other-update-old"));
        Files.setLastModifiedTime(otherApp, FileTime.from(NOW.minus(Duration.ofDays(3))));

        service().deleteOldUpdates();

        assertFalse(Files.exists(old));
        assertTrue(Files.exists(recent));
        assertTrue(Files.exists(otherApp));
    }

    /** A repository without a published release yet: said as such, not as an HTTP error. */
    @Test
    public void noPublishedReleaseIsNotAnError() throws Exception {
        status = 404;

        UpdateException e = assertThrows(UpdateException.class, () -> service().checkNow());
        assertEquals("update.no-release", e.key());

        settings.last = Optional.empty();
        assertTrue(service().automaticCheck().isEmpty()); // only logged
        assertEquals(Optional.of(NOW), settings.last);
    }

    @Test
    public void aMissingAssetIsReportedAndLeavesNothing() throws Exception {
        files.put("copybot-1.2.0-setup.exe", "setup".getBytes(StandardCharsets.UTF_8));
        UpdateService service = service();
        Release release = service.checkNow().orElseThrow();

        UpdateException e = assertThrows(UpdateException.class,
                () -> service.prepare(release, bytes -> { }, () -> false));

        assertEquals("update.error.no-asset", e.key());
        assertTrue(tempContent().isEmpty());
        assertTrue(launched.isEmpty());
    }

    @Test
    public void anInvalidArchiveIsRefusedAndLeavesNothing() throws Exception {
        files.put("copybot-1.2.0-windows-x64.zip", zip("Copybot/app/copybot.jar", "Other/Copybot.exe"));
        UpdateService service = service();
        Release release = service.checkNow().orElseThrow();

        UpdateException e = assertThrows(UpdateException.class,
                () -> service.prepare(release, bytes -> { }, () -> false));

        assertEquals("update.error.zip", e.key());
        assertTrue(tempContent().isEmpty());
        assertTrue(launched.isEmpty());
    }

    @Test
    public void aCancelledDownloadLeavesNothing() throws Exception {
        files.put("copybot-1.2.0-windows-x64.zip", zip("Copybot/Copybot.exe"));
        UpdateService service = service();
        Release release = service.checkNow().orElseThrow();

        UpdateException e = assertThrows(UpdateException.class,
                () -> service.prepare(release, bytes -> { }, () -> true));

        assertEquals("update.error.cancelled", e.key());
        assertTrue(tempContent().isEmpty());
    }

    @Test
    public void aFailedLaunchDeletesTheDownload() throws Exception {
        files.put("copybot-1.2.0-windows-x64.zip", zip("Copybot/Copybot.exe"));
        UpdateService service = service(true, command -> {
            throw new IOException("no powershell");
        });
        UpdateService.Prepared prepared = service.prepare(service.checkNow().orElseThrow(), bytes -> { },
                () -> false);

        UpdateException e = assertThrows(UpdateException.class, () -> service.launch(prepared, () -> false));

        assertEquals("update.error.launch", e.key());
        assertTrue(tempContent().isEmpty());
    }

    /** Cancel clicked during the extraction or as the download ends: nothing is launched, nothing is left. */
    @Test
    public void aLateCancelStopsTheLaunch() throws Exception {
        files.put("copybot-1.2.0-windows-x64.zip", zip("Copybot/Copybot.exe"));
        UpdateService service = service();
        UpdateService.Prepared prepared = service.prepare(service.checkNow().orElseThrow(), bytes -> { },
                () -> false);

        UpdateException e = assertThrows(UpdateException.class, () -> service.launch(prepared, () -> true));

        assertEquals("update.error.cancelled", e.key());
        assertTrue(launched.isEmpty());
        assertTrue(tempContent().isEmpty());
    }
}

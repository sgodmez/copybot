package com.copybot.ui.update;

import com.copybot.config.AppFolders;
import com.copybot.ui.update.Release.Asset;
import com.copybot.ui.util.UiPreferences;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * The update flow without JavaFX: when to check, whether the update can be installed now, and the preparation of the
 * download (installer, or unzipped copy and replacement script) up to the launch of what applies it. The UI calls it
 * off the JavaFX thread and quits the application after {@link #launch(Prepared, BooleanSupplier)}.
 */
public final class UpdateService {

    private static final System.Logger LOG = System.getLogger(UpdateService.class.getName());

    /** The preferences the checks read and write. */
    public interface Settings {
        boolean autoCheck();

        Optional<Instant> lastCheck();

        void checked(Instant when);
    }

    /** Starts the process applying the update, without waiting for it. */
    public interface Launcher {
        void start(List<String> command) throws IOException;
    }

    /** A downloaded update, ready to launch: its own temporary folder and the command applying it. */
    public record Prepared(Path tempDir, List<String> command) {
        public Prepared {
            command = List.copyOf(command);
        }
    }

    /** A process of the system: its executable, empty when it cannot be read (another user's, an elevated one). */
    public record RunningProcess(long pid, Optional<String> command) {
    }

    /**
     * This running Copybot and what it sees of the system.
     *
     * @param appPath    the executable given by jpackage, null outside the packaged application
     * @param pid        this process
     * @param parentPid  the process that started it (the launcher), -1 when unknown
     * @param configFile the {@code --config-file} it was started with, null for none
     * @param windows    true on Windows, the only system where the update is applied
     * @param processes  the processes of the system
     * @param writable   true when a file can be created in a folder
     */
    public record Host(String appPath, long pid, long parentPid, Path configFile, boolean windows,
            Supplier<List<RunningProcess>> processes, Predicate<Path> writable) {
    }

    /** Older than this, an update folder left in the temporary directory belongs to no update under way. */
    private static final Duration LEFTOVER_AGE = Duration.ofDays(1);
    private static final String TEMP_PREFIX = "copybot-update-";

    private final HttpClient client;
    private final URI latest;
    private final String currentVersion;
    private final Host host;
    /** The executable of the packaged application, null outside it (IDE, command line). */
    private final Path exe;
    private final Path logDir;
    /** Where the temporary folder of an update is created, null for the system one. */
    private final Path tempRoot;
    private final Clock clock;
    private final Settings settings;
    private final BooleanSupplier busy;
    private final Launcher launcher;

    public UpdateService(HttpClient client, URI latest, String currentVersion, Host host, Path logDir, Path tempRoot,
            Clock clock, Settings settings, BooleanSupplier busy, Launcher launcher) {
        this.client = client;
        this.latest = latest;
        this.currentVersion = currentVersion;
        this.host = host;
        String appPath = host.appPath();
        this.exe = appPath == null || appPath.isBlank() ? null : Path.of(appPath).toAbsolutePath().normalize();
        this.logDir = logDir;
        this.tempRoot = tempRoot;
        this.clock = clock;
        this.settings = settings;
        this.busy = busy;
        this.launcher = launcher;
    }

    /**
     * The service of this application: GitHub, the version of the build, the executable given by jpackage, the
     * preferences of {@link UiPreferences}, a detached process.
     *
     * @param logDir     where the portable replacement script writes its log
     * @param configFile the {@code --config-file} Copybot was started with, null for none: the relaunch gets it too
     * @param busy       true while a preparation or a copy runs
     */
    public static UpdateService forThisApp(String currentVersion, Path logDir, Path configFile, BooleanSupplier busy) {
        ProcessHandle self = ProcessHandle.current();
        Host host = new Host(System.getProperty("jpackage.app-path"), self.pid(),
                self.parent().map(ProcessHandle::pid).orElse(-1L),
                configFile == null ? null : configFile.toAbsolutePath().normalize(),
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"),
                UpdateService::systemProcesses, AppFolders::isWritable);
        return new UpdateService(UpdateCheck.httpClient(), UpdateCheck.LATEST, currentVersion, host, logDir, null,
                Clock.systemUTC(), new PreferencesSettings(), busy, UpdateService::startDetached);
    }

    public String currentVersion() {
        return currentVersion;
    }

    /** The message key telling why there is no update at all here: not a release, or not the packaged application. */
    public Optional<String> unavailableReason() {
        return AppVersion.isRelease(currentVersion) && exe != null && exe.getParent() != null
                ? Optional.empty() : Optional.of("update.unavailable-dev");
    }

    /**
     * The check at startup, when turned on, possible here and due (spec §2). Its date is recorded whatever the
     * outcome; a failure is only logged.
     *
     * @return the newer release, if any
     */
    public Optional<Release> automaticCheck() throws InterruptedException {
        if (!checkDue()) {
            return Optional.empty();
        }
        try {
            return checkNow();
        } catch (UpdateException e) {
            LOG.log(System.Logger.Level.INFO, "automatic update check failed: {0} {1}", e.key(),
                    List.of(e.args()));
            return Optional.empty();
        }
    }

    /**
     * True when the check at startup is to run: turned on, possible here and not done for a day. Asked before
     * {@link #automaticCheck()}, which records the date, by what is checked along (the plugins).
     */
    public boolean checkDue() {
        return settings.autoCheck() && unavailableReason().isEmpty()
                && UpdateCheck.due(clock.instant(), settings.lastCheck());
    }

    /**
     * Asks GitHub now (the button of the preferences) and records the date, even on a failure.
     *
     * @return the newer release, empty when this version is the latest one
     */
    public Optional<Release> checkNow() throws UpdateException, InterruptedException {
        Optional<String> unavailable = unavailableReason();
        if (unavailable.isPresent()) {
            throw new UpdateException(unavailable.get());
        }
        try {
            Release release = new UpdateCheck(client, latest, currentVersion).fetchLatest();
            return AppVersion.compare(release.version(), currentVersion) > 0 ? Optional.of(release)
                    : Optional.empty();
        } finally {
            settings.checked(clock.instant());
        }
    }

    /** The message key telling why the update cannot be installed now; empty when it can. */
    public Optional<String> installBlocked() {
        Optional<String> unavailable = unavailableReason();
        if (unavailable.isPresent()) {
            return unavailable;
        }
        if (!host.windows()) {
            return Optional.of("update.windows-only");
        }
        return busy.getAsBoolean() ? Optional.of("update.busy") : Optional.empty();
    }

    /**
     * The message key refusing the update while another Copybot runs from the same application folder (its files
     * could not be replaced); empty when there is none. This process and its launcher do not count; a process whose
     * executable cannot be read is left to the wait of the replacement script. Lists the processes of the system:
     * called when the update is asked for, not to refresh a view.
     */
    public Optional<String> otherInstance() {
        if (exe == null || exe.getParent() == null) {
            return Optional.empty();
        }
        String prefix = key(exe.getParent()) + File.separator;
        boolean found = host.processes().get().stream()
                .filter(process -> process.pid() != host.pid() && process.pid() != host.parentPid())
                .flatMap(process -> process.command().stream())
                .anyMatch(command -> command(command).map(path -> key(path).startsWith(prefix)).orElse(false));
        return found ? Optional.of("update.other-instance") : Optional.empty();
    }

    /**
     * True when Windows will ask for an administrator's approval: the application folder cannot be written (a
     * portable copy unzipped under Program Files, an installation for all users).
     */
    public boolean adminApprovalNeeded() {
        return exe != null && exe.getParent() != null && !host.writable().test(exe.getParent());
    }

    /** The folder of the packaged application, empty outside it. */
    public Optional<Path> appDir() {
        return exe == null ? Optional.empty() : Optional.ofNullable(exe.getParent());
    }

    /** True when a file can be created in this folder (else Windows will ask for an administrator). */
    public boolean writable(Path folder) {
        return host.writable().test(folder);
    }

    /**
     * The command running the replacement script of {@code script} in plugin mode for this Copybot (its process, its
     * folder, its executable, its log folder and its {@code --config-file}). Only in the packaged application.
     */
    public List<String> pluginCommand(Path script, Path manifest) {
        return UpdateApply.pluginScriptCommand(script, host.pid(), exe.getParent(), manifest, exe, logDir,
                host.configFile());
    }

    /** A new temporary folder of an update ({@code copybot-update-*}), deleted after a day if it is left there. */
    public Path createTempDir() throws UpdateException {
        try {
            return tempRoot == null ? Files.createTempDirectory(TEMP_PREFIX)
                    : Files.createTempDirectory(tempRoot, TEMP_PREFIX);
        } catch (IOException e) {
            throw new UpdateException("update.error.io", UpdateCheck.message(e));
        }
    }

    /**
     * Deletes the update folders older than a day left in the temporary directory: the installer of an installed
     * copy (it cannot delete itself), a copy whose script did not end. Failures are only logged.
     */
    public void deleteOldUpdates() {
        Path root = tempRoot != null ? tempRoot : Path.of(System.getProperty("java.io.tmpdir"));
        Instant limit = clock.instant().minus(LEFTOVER_AGE);
        try (Stream<Path> children = Files.list(root)) {
            children.filter(child -> child.getFileName().toString().startsWith(TEMP_PREFIX))
                    .filter(Files::isDirectory)
                    .filter(child -> modifiedBefore(child, limit))
                    .forEach(child -> {
                        LOG.log(System.Logger.Level.INFO, "deleting the old update folder {0}", child);
                        deleteQuietly(child);
                    });
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "could not list " + root, e);
        }
    }

    private static boolean modifiedBefore(Path path, Instant limit) {
        try {
            return Files.getLastModifiedTime(path).toInstant().isBefore(limit);
        } catch (IOException e) {
            return false;
        }
    }

    /** The executable of a process, absolute and normalized; empty when it is not a path. */
    private static Optional<Path> command(String command) {
        try {
            return Optional.of(Path.of(command).toAbsolutePath().normalize());
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }

    private static String key(Path path) {
        return path.toString().toLowerCase(Locale.ROOT);
    }

    private static List<RunningProcess> systemProcesses() {
        try (Stream<ProcessHandle> all = ProcessHandle.allProcesses()) {
            return all.map(process -> new RunningProcess(process.pid(), process.info().command())).toList();
        }
    }

    /** How this copy was installed; only meaningful in the packaged application. */
    public InstallMode installMode() {
        return InstallMode.of(exe.getParent());
    }

    /** The file of the release this copy needs: the installer, or the portable archive. */
    public String assetName(Release release) {
        return installMode() == InstallMode.INSTALLED ? "copybot-" + release.version() + "-setup.exe"
                : "copybot-" + release.version() + "-windows-x64.zip";
    }

    /**
     * Downloads and checks the file of the mode in a new temporary folder; for a portable copy, unzips it there and
     * writes the replacement script. On any failure the temporary folder is deleted.
     *
     * @param progress receives the bytes received so far
     */
    public Prepared prepare(Release release, LongConsumer progress, BooleanSupplier cancelled)
            throws UpdateException, InterruptedException {
        Optional<String> blocked = installBlocked().or(this::otherInstance);
        if (blocked.isPresent()) {
            throw new UpdateException(blocked.get());
        }
        String name = assetName(release);
        Asset asset = release.asset(name)
                .orElseThrow(() -> new UpdateException("update.error.no-asset", release.version(), name));
        Path temp = createTempDir();
        boolean done = false;
        try {
            Path file = Download.fetch(client, asset, temp, progress, cancelled);
            Prepared prepared;
            if (installMode() == InstallMode.INSTALLED) {
                prepared = new Prepared(temp, UpdateApply.setupCommand(file));
            } else {
                prepared = new Prepared(temp, portable(file, temp));
            }
            done = true;
            return prepared;
        } finally {
            if (!done) {
                deleteQuietly(temp);
            }
        }
    }

    private List<String> portable(Path zip, Path temp) throws UpdateException {
        Path unzipped;
        try {
            unzipped = UpdateApply.extractPortable(zip, temp);
        } catch (IOException e) {
            throw new UpdateException("update.error.zip", UpdateCheck.message(e));
        }
        try {
            requireDirectlyIn(unzipped, temp); // the script deletes the parent of the copy: its own folder only
            Path script = UpdateApply.writeScript(temp);
            return UpdateApply.scriptCommand(script, host.pid(), exe.getParent(), unzipped, exe, logDir,
                    host.configFile());
        } catch (IOException e) {
            throw new UpdateException("update.error.io", UpdateCheck.message(e));
        }
    }

    /** Refuses an unzipped copy that is not directly in the temporary folder of the update. */
    static void requireDirectlyIn(Path unzipped, Path tempDir) throws IOException {
        Path parent = unzipped.toAbsolutePath().normalize().getParent();
        if (parent == null || !parent.equals(tempDir.toAbsolutePath().normalize())) {
            throw new IOException(unzipped + " is not directly in " + tempDir);
        }
    }

    /**
     * Starts what applies the update, unless it was cancelled meanwhile (during the extraction, or as the download
     * ended); the caller then quits the application the usual way. On a cancel or a failure the download is deleted
     * and the application goes on.
     */
    public void launch(Prepared prepared, BooleanSupplier cancelled) throws UpdateException {
        if (cancelled.getAsBoolean()) {
            deleteQuietly(prepared.tempDir());
            throw new UpdateException("update.error.cancelled");
        }
        try {
            launcher.start(prepared.command());
        } catch (IOException | RuntimeException e) {
            deleteQuietly(prepared.tempDir());
            throw new UpdateException("update.error.launch",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /** Deletes the temporary folder of an update; what cannot be deleted is left to the system. */
    public static void deleteQuietly(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    LOG.log(System.Logger.Level.DEBUG, "could not delete " + path, e);
                }
            });
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "could not delete " + dir, e);
        }
    }

    /** Neither its output nor its end is waited for: it outlives the application. */
    private static void startDetached(List<String> command) throws IOException {
        new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    private static final class PreferencesSettings implements Settings {
        @Override
        public boolean autoCheck() {
            return UiPreferences.updateCheck();
        }

        @Override
        public Optional<Instant> lastCheck() {
            return UiPreferences.updateLastCheck();
        }

        @Override
        public void checked(Instant when) {
            UiPreferences.saveUpdateLastCheck(when);
        }
    }
}

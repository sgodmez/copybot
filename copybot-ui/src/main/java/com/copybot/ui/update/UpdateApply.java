package com.copybot.ui.update;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Builds and prepares what applies a downloaded update: the silent installer, or the portable replacement script. */
public final class UpdateApply {

    private static final String PORTABLE_ROOT = "Copybot";
    private static final String SCRIPT = "apply-update.ps1";

    private UpdateApply() {
    }

    /** The command running the installer silently, closing the running application and relaunching it. */
    public static List<String> setupCommand(Path setupExe) {
        return List.of(setupExe.toString(), "/SILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/CLOSEAPPLICATIONS",
                "/RELAUNCH");
    }

    /**
     * Unzips a portable archive into {@code into}.
     *
     * @return the unzipped application folder, {@code into/Copybot}
     * @throws IOException if an entry leaves {@code into} or the archive does not hold {@code Copybot/Copybot.exe}
     */
    public static Path extractPortable(Path zip, Path into) throws IOException {
        Path base = into.toAbsolutePath().normalize();
        Files.createDirectories(base);
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path target = base.resolve(entry.getName()).normalize();
                if (!target.startsWith(base) || target.equals(base)) {
                    throw new IOException("Unsafe entry in the update archive: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (InputStream in = zf.getInputStream(entry)) {
                        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
        Path root = base.resolve(PORTABLE_ROOT);
        if (!Files.isRegularFile(root.resolve("Copybot.exe"))) {
            throw new IOException("The update archive does not contain " + PORTABLE_ROOT + "/Copybot.exe");
        }
        return root;
    }

    /** Copies the replacement script into {@code dir} and returns it. */
    public static Path writeScript(Path dir) throws IOException {
        Files.createDirectories(dir);
        Path script = dir.resolve(SCRIPT);
        try (InputStream in = UpdateApply.class.getResourceAsStream(SCRIPT)) {
            if (in == null) {
                throw new IOException("Missing resource " + SCRIPT);
            }
            Files.copy(in, script, StandardCopyOption.REPLACE_EXISTING);
        }
        return script;
    }

    /**
     * The command running the replacement script, PowerShell given by its full path.
     *
     * @param configFile the {@code --config-file} Copybot was started with, passed on to its relaunch; null for none
     */
    public static List<String> scriptCommand(Path script, long pid, Path appDir, Path newDir, Path exe,
            Path logDir, Path configFile) {
        return command(script, pid, appDir, "-NewDir", newDir, exe, logDir, configFile);
    }

    /**
     * The command running the replacement script in plugin mode: it installs, replaces and removes plugin folders as
     * the JSON manifest says, with the same waiting, elevation and relaunch as an application update.
     *
     * @param manifest   the manifest, written in the folder of the script
     * @param configFile the {@code --config-file} Copybot was started with, passed on to its relaunch; null for none
     */
    public static List<String> pluginScriptCommand(Path script, long pid, Path appDir, Path manifest, Path exe,
            Path logDir, Path configFile) {
        return command(script, pid, appDir, "-PluginManifest", manifest, exe, logDir, configFile);
    }

    /** The command common to both modes: {@code modeFlag modePath} sits between the application folder and the exe. */
    private static List<String> command(Path script, long pid, Path appDir, String modeFlag, Path modePath, Path exe,
            Path logDir, Path configFile) {
        List<String> command = new ArrayList<>(List.of(powershell().toString(), "-NoProfile", "-ExecutionPolicy",
                "Bypass", "-WindowStyle", "Hidden", "-File", script.toString(),
                "-ProcessId", Long.toString(pid), "-AppDir", appDir.toString(), modeFlag, modePath.toString(),
                "-Exe", exe.toString(), "-LogDir", logDir.toString()));
        if (configFile != null) {
            command.addAll(List.of("-ConfigFile", configFile.toString()));
        }
        return List.copyOf(command);
    }

    /** The PowerShell of the system, by its full path: never a powershell.exe found first in the PATH. */
    static Path powershell() {
        String systemRoot = System.getenv("SystemRoot");
        return Path.of(systemRoot == null || systemRoot.isBlank() ? "C:\\Windows" : systemRoot,
                "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
    }
}

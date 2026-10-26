package com.copybot.ui.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class UpdateApplyTest {

    @TempDir
    Path dir;

    @Test
    void setupCommandIsSilentAndRelaunches() {
        Path setup = Path.of("setup.exe");
        assertEquals(
                List.of(setup.toString(), "/SILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/CLOSEAPPLICATIONS",
                        "/RELAUNCH"),
                UpdateApply.setupCommand(setup));
    }

    @Test
    void scriptCommandPassesEveryParameter() {
        String powershell = UpdateApply.powershell().toString();
        List<String> cmd = UpdateApply.scriptCommand(Path.of("s.ps1"), 42L, Path.of("app"), Path.of("new"),
                Path.of("Copybot.exe"), Path.of("logs"), null);
        assertEquals(List.of(powershell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", "s.ps1",
                "-ProcessId", "42", "-AppDir", "app", "-NewDir", "new", "-Exe", "Copybot.exe", "-LogDir", "logs"),
                cmd);

        assertEquals(List.of("-ConfigFile", Path.of("my config.json").toString()),
                UpdateApply.scriptCommand(Path.of("s.ps1"), 42L, Path.of("app"), Path.of("new"),
                        Path.of("Copybot.exe"), Path.of("logs"), Path.of("my config.json")).subList(18, 20));
    }

    /** The PowerShell of the system, never one found first in the PATH. */
    @Test
    void scriptCommandStartsWithTheFullPathOfPowerShell() {
        Path powershell = UpdateApply.powershell();
        assertTrue(powershell.isAbsolute(), powershell.toString());
        assertTrue(powershell.endsWith(Path.of("System32", "WindowsPowerShell", "v1.0", "powershell.exe")),
                powershell.toString());
        assertEquals(powershell.toString(), UpdateApply.scriptCommand(Path.of("s.ps1"), 42L, Path.of("app"),
                Path.of("new"), Path.of("Copybot.exe"), Path.of("logs"), null).get(0));
    }

    @Test
    void extractPortableUnpacksTheAppFolder() throws IOException {
        Path zip = zip("Copybot/Copybot.exe", "Copybot/app/x.jar");
        Path into = dir.resolve("out");
        Path root = UpdateApply.extractPortable(zip, into);
        assertEquals(into.resolve("Copybot"), root);
        assertTrue(Files.isRegularFile(root.resolve("Copybot.exe")));
        assertTrue(Files.isRegularFile(root.resolve("app").resolve("x.jar")));
    }

    @Test
    void aZipEntryEscapingTheFolderIsRefused() throws IOException {
        Path zip = zip("Copybot/Copybot.exe", "../evil.txt");
        Path into = dir.resolve("out");
        assertThrows(IOException.class, () -> UpdateApply.extractPortable(zip, into));
        assertFalse(Files.exists(dir.resolve("evil.txt")));
    }

    @Test
    void aZipWithoutTheAppIsRefused() throws IOException {
        Path zip = zip("Other/file.txt");
        assertThrows(IOException.class, () -> UpdateApply.extractPortable(zip, dir.resolve("out")));
    }

    @Test
    void writeScriptCopiesTheResource() throws IOException {
        Path script = UpdateApply.writeScript(dir);
        assertTrue(Files.size(script) > 0);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void theScriptReplacesOnlyTheAppImage() throws Exception {
        Path app = dir.resolve("inst");
        write(app.resolve("app/old.jar"), "old");
        write(app.resolve("app/keep.jar"), "v1");
        write(app.resolve("runtime/r.dll"), "r");
        write(app.resolve("Copybot.exe"), "exe1");
        write(app.resolve("plugins/p.jar"), "p");
        write(app.resolve("config.json"), "c");
        write(app.resolve("prefs.json"), "pr");
        write(app.resolve("logs/a.log"), "l");
        write(app.resolve("mine.txt"), "m");

        Path upd = dir.resolve("upd");
        Path nd = upd.resolve("Copybot");
        // other sizes: robocopy skips a file of the same size and time, as these files written at once may be
        write(nd.resolve("app/keep.jar"), "v2-new");
        write(nd.resolve("runtime/r.dll"), "r");
        write(nd.resolve("Copybot.exe"), "exe2-new");
        write(nd.resolve("Copybot.ico"), "ico");

        Path witness = dir.resolve("relaunched.txt");
        Path exe = fakeExe(witness);
        Path config = dir.resolve("my data/config.json");

        Path script = UpdateApply.writeScript(upd); // as Copybot does: the script in the temporary folder
        Path logDir = dir.resolve("log");

        runScript(UpdateApply.scriptCommand(script, endedPid(), app, nd, exe, logDir, config));

        assertFalse(Files.exists(app.resolve("app/old.jar")));
        assertEquals("v2-new", Files.readString(app.resolve("app/keep.jar")));
        assertEquals("exe2-new", Files.readString(app.resolve("Copybot.exe")), "root file replaced");
        assertTrue(Files.exists(app.resolve("Copybot.ico")));
        for (String kept : List.of("plugins/p.jar", "config.json", "prefs.json", "logs/a.log", "mine.txt")) {
            assertTrue(Files.exists(app.resolve(kept)), kept);
        }
        awaitFile(witness);
        assertTrue(Files.readString(witness).contains("--config-file=" + config), Files.readString(witness));
        assertTrue(Files.size(logDir.resolve("update.log")) > 0);
        for (int i = 0; i < 50 && Files.exists(upd); i++) {
            Thread.sleep(100); // the script may leave the rest of its own folder to a delayed removal
        }
        assertFalse(Files.exists(upd), "temporary update folder removed");
    }

    /** The script deletes only its own folder: an unzipped copy elsewhere is left where it is. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void theScriptDeletesOnlyItsOwnFolder() throws Exception {
        Path app = dir.resolve("inst");
        write(app.resolve("app/a.jar"), "v1");
        Path upd = dir.resolve("upd");
        Path nd = upd.resolve("Copybot");
        write(nd.resolve("app/a.jar"), "v2-new");
        write(nd.resolve("Copybot.exe"), "exe2-new");
        Path witness = dir.resolve("relaunched.txt");
        Path script = UpdateApply.writeScript(dir.resolve("scripts"));
        Path logDir = dir.resolve("log");

        runScript(UpdateApply.scriptCommand(script, endedPid(), app, nd, fakeExe(witness), logDir, null));

        assertEquals("v2-new", Files.readString(app.resolve("app/a.jar")));
        awaitFile(witness);
        assertTrue(Files.exists(nd.resolve("Copybot.exe")), "the folder of another script is kept");
        assertTrue(Files.readString(logDir.resolve("update.log")).contains("Refusing to delete"));
    }

    /** A batch file standing for Copybot.exe: its relaunch writes its arguments to the witness. */
    private Path fakeExe(Path witness) throws IOException {
        Path exe = dir.resolve("fake.cmd");
        Files.writeString(exe, "@echo off\r\necho ok %*> \"" + witness + "\"\r\n");
        return exe;
    }

    /** The PID of a process that already ended: the script does not wait for it. */
    private static long endedPid() throws Exception {
        Process done = new ProcessBuilder("cmd", "/c", "exit", "0").start();
        done.waitFor();
        return done.pid();
    }

    private static void runScript(List<String> command) throws Exception {
        Process p = new ProcessBuilder(command)
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assertTrue(p.waitFor(90, TimeUnit.SECONDS), "script timed out");
    }

    private static void awaitFile(Path file) throws InterruptedException {
        for (int i = 0; i < 50 && !Files.exists(file); i++) {
            Thread.sleep(100);
        }
        assertTrue(Files.exists(file), "relaunch witness");
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private Path zip(String... entries) throws IOException {
        Path zip = Files.createTempFile(dir, "u", ".zip");
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(out)) {
            for (String e : entries) {
                z.putNextEntry(new ZipEntry(e));
                z.write("x".getBytes());
                z.closeEntry();
            }
        }
        return zip;
    }
}

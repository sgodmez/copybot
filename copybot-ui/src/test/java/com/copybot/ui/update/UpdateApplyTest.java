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
import java.util.stream.Stream;
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

    /** The PowerShell of the system, never one found first in the PATH (a Windows path: the update is Windows only). */
    @Test
    @EnabledOnOs(OS.WINDOWS)
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

    @Test
    void pluginScriptCommandPassesTheManifestInsteadOfTheNewFolder() {
        String powershell = UpdateApply.powershell().toString();
        assertEquals(List.of(powershell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File",
                "s.ps1", "-ProcessId", "42", "-AppDir", "app", "-PluginManifest", "m.json", "-Exe", "Copybot.exe",
                "-LogDir", "logs"),
                UpdateApply.pluginScriptCommand(Path.of("s.ps1"), 42L, Path.of("app"), Path.of("m.json"),
                        Path.of("Copybot.exe"), Path.of("logs"), null));
        assertEquals(List.of("-ConfigFile", Path.of("my config.json").toString()),
                UpdateApply.pluginScriptCommand(Path.of("s.ps1"), 42L, Path.of("app"), Path.of("m.json"),
                        Path.of("Copybot.exe"), Path.of("logs"), Path.of("my config.json")).subList(18, 20));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void thePluginModeReplacesOnlyItsFolders() throws Exception {
        Path app = fakeApp();
        write(app.resolve("plugins/old-name/a.jar"), "old");
        write(app.resolve("plugins/other/b.jar"), "keep");
        Path upd = dir.resolve("upd");
        write(upd.resolve("metadata-extractor/m.jar"), "new-jar");
        Path manifest = manifest(upd, "{\"install\":[{\"source\":" + json(upd.resolve("metadata-extractor"))
                + ",\"target\":\"metadata-extractor\"}],\"remove\":[" + json(app.resolve("plugins/old-name")) + "]}");
        Path witness = dir.resolve("relaunched.txt");
        Path logDir = dir.resolve("log");
        Path script = UpdateApply.writeScript(upd);

        runScript(UpdateApply.pluginScriptCommand(script, endedPid(), app, manifest, fakeExe(witness), logDir, null));

        assertEquals("new-jar", Files.readString(app.resolve("plugins/metadata-extractor/m.jar")));
        assertFalse(Files.exists(app.resolve("plugins/old-name")));
        assertEquals("keep", Files.readString(app.resolve("plugins/other/b.jar")));
        assertAppIntact(app);
        awaitFile(witness);
        awaitRemoved(upd);
    }

    /** A manifest which only removes: an uninstall. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aManifestOnlyRemovingUninstalls() throws Exception {
        Path app = fakeApp();
        write(app.resolve("plugins/gone/a.jar"), "old");
        write(app.resolve("plugins/other/b.jar"), "keep");
        Path upd = dir.resolve("upd");
        Path manifest = manifest(upd, "{\"install\":[],\"remove\":[" + json(app.resolve("plugins/gone")) + "]}");
        Path witness = dir.resolve("relaunched.txt");
        Path script = UpdateApply.writeScript(upd);

        runScript(UpdateApply.pluginScriptCommand(script, endedPid(), app, manifest, fakeExe(witness),
                dir.resolve("log"), null));

        assertFalse(Files.exists(app.resolve("plugins/gone")));
        assertTrue(Files.exists(app.resolve("plugins/other/b.jar")));
        assertAppIntact(app);
        awaitFile(witness);
        awaitRemoved(upd);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aManifestOutsideThePluginsFolderIsRefused() throws Exception {
        for (String bad : List.of(
                "{\"install\":[],\"remove\":[APP]}",
                "{\"install\":[],\"remove\":[SIBLING]}",
                "{\"install\":[{\"source\":SRC,\"target\":\"..\\\\x\"}],\"remove\":[]}",
                "{\"install\":[{\"source\":SRC,\"target\":\"a\\\\b\"}],\"remove\":[]}")) {
            Path root = Files.createTempDirectory(dir, "case");
            Path app = root.resolve("inst");
            write(app.resolve("app/a.jar"), "v1");
            write(app.resolve("Copybot.exe"), "exe");
            write(app.resolve("plugins/p/p.jar"), "p");
            Path upd = root.resolve("upd");
            write(upd.resolve("src/s.jar"), "s");
            Path manifest = manifest(upd, bad.replace("SIBLING", json(app.resolve("plugins").resolve("..\\app")))
                    .replace("APP", json(app)).replace("SRC", json(upd.resolve("src"))));
            Path witness = root.resolve("relaunched.txt");
            Path logDir = root.resolve("log");

            runScript(UpdateApply.pluginScriptCommand(UpdateApply.writeScript(upd), endedPid(), app, manifest,
                    fakeExe(witness), logDir, null));

            assertEquals("v1", Files.readString(app.resolve("app/a.jar")), bad);
            assertEquals("p", Files.readString(app.resolve("plugins/p/p.jar")), bad);
            assertFalse(Files.exists(root.resolve("x")), bad);
            assertFalse(Files.exists(app.resolve("plugins/a")), bad);
            awaitFile(witness);
            assertTrue(Files.readString(logDir.resolve("update.log")).contains("Manifest refused"), bad);
            awaitRemoved(upd);
        }
    }

    /**
     * A target follows the rule of the catalogue codes (a letter or digit, then letters, digits, '.', '_', '-', never a
     * final '.'): "..." would otherwise mirror onto the plugins folder itself, Windows trims "x." to "x".
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aTargetThatIsNotASafeNameIsRefused() throws Exception {
        for (String target : List.of("...", "x.", "a b")) {
            Path root = Files.createTempDirectory(dir, "case");
            Path app = root.resolve("inst");
            write(app.resolve("app/a.jar"), "v1");
            write(app.resolve("Copybot.exe"), "exe");
            write(app.resolve("plugins/p/p.jar"), "p");
            Path upd = root.resolve("upd");
            write(upd.resolve("src/s.jar"), "s");
            Path manifest = manifest(upd, "{\"install\":[{\"source\":" + json(upd.resolve("src")) + ",\"target\":\""
                    + target + "\"}],\"remove\":[]}");
            Path witness = root.resolve("relaunched.txt");
            Path logDir = root.resolve("log");

            runScript(UpdateApply.pluginScriptCommand(UpdateApply.writeScript(upd), endedPid(), app, manifest,
                    fakeExe(witness), logDir, null));

            assertEquals("p", Files.readString(app.resolve("plugins/p/p.jar")), target);
            try (Stream<Path> content = Files.list(app.resolve("plugins"))) {
                assertEquals(List.of(app.resolve("plugins/p")), content.toList(), target);
            }
            awaitFile(witness);
            assertTrue(Files.readString(logDir.resolve("update.log")).contains("Manifest refused"), target);
            awaitRemoved(upd);
        }
    }

    /** The folder of the update is mirrored onto: never removed afterwards as the old folder of the plugin. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aRemovalOfTheInstalledFolderItselfKeepsTheNewVersion() throws Exception {
        Path app = fakeApp();
        write(app.resolve("plugins/metadata-extractor/m.jar"), "old");
        Path upd = dir.resolve("upd");
        write(upd.resolve("metadata-extractor/m.jar"), "new-jar"); // another size: robocopy copies it
        Path manifest = manifest(upd, "{\"install\":[{\"source\":" + json(upd.resolve("metadata-extractor"))
                + ",\"target\":\"metadata-extractor\"}],\"remove\":[" + json(app.resolve("plugins/metadata-extractor"))
                + "]}");
        Path witness = dir.resolve("relaunched.txt");

        runScript(UpdateApply.pluginScriptCommand(UpdateApply.writeScript(upd), endedPid(), app, manifest,
                fakeExe(witness), dir.resolve("log"), null));

        assertEquals("new-jar", Files.readString(app.resolve("plugins/metadata-extractor/m.jar")));
        awaitFile(witness);
    }

    /** The copies come first: a copy that fails leaves the old folder in place, an update never uninstalls. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aFailedCopyRemovesNothing() throws Exception {
        Path app = fakeApp();
        write(app.resolve("plugins/old-name/a.jar"), "old");
        write(app.resolve("plugins/blocked"), "a file where the folder should go: robocopy fails");
        Path upd = dir.resolve("upd");
        write(upd.resolve("blocked/m.jar"), "new-jar");
        Path manifest = manifest(upd, "{\"install\":[{\"source\":" + json(upd.resolve("blocked"))
                + ",\"target\":\"blocked\"}],\"remove\":[" + json(app.resolve("plugins/old-name")) + "]}");
        Path witness = dir.resolve("relaunched.txt");
        Path logDir = dir.resolve("log");

        runScript(UpdateApply.pluginScriptCommand(UpdateApply.writeScript(upd), endedPid(), app, manifest,
                fakeExe(witness), logDir, null));

        assertEquals("old", Files.readString(app.resolve("plugins/old-name/a.jar")));
        assertAppIntact(app);
        awaitFile(witness);
        assertTrue(Files.readString(logDir.resolve("update.log")).contains("failed with code"));
    }

    /** A source outside the folder of the script (even a readable folder) is never mirrored into plugins. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aSourceOutsideTheScriptFolderIsRefused() throws Exception {
        Path app = fakeApp();
        write(app.resolve("plugins/p/p.jar"), "p");
        Path elsewhere = dir.resolve("elsewhere");
        write(elsewhere.resolve("secret.txt"), "s");
        Path upd = dir.resolve("upd");
        write(upd.resolve("nested/deeper/x.jar"), "x");
        for (Path source : List.of(elsewhere, upd.resolve("nested/deeper"))) {
            Path manifest = manifest(upd, "{\"install\":[{\"source\":" + json(source) + ",\"target\":\"t\"}],\"remove\":[]}");
            Path witness = dir.resolve("relaunched-" + source.getFileName() + ".txt");
            Path logDir = dir.resolve("log");

            runScript(UpdateApply.pluginScriptCommand(UpdateApply.writeScript(upd), endedPid(), app, manifest,
                    fakeExe(witness), logDir, null));

            assertFalse(Files.exists(app.resolve("plugins/t")), source.toString());
            assertEquals("p", Files.readString(app.resolve("plugins/p/p.jar")));
            awaitFile(witness);
            assertTrue(Files.readString(logDir.resolve("update.log")).contains("Manifest refused"));
            awaitRemoved(upd);
            write(upd.resolve("nested/deeper/x.jar"), "x");
        }
    }

    /** Removing a plugin folder holding a junction deletes the link, never the files it points to. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void removingAFolderWithAJunctionKeepsTheLinkTarget() throws Exception {
        Path app = fakeApp();
        Path outside = dir.resolve("outside");
        write(outside.resolve("precious.txt"), "keep me");
        write(app.resolve("plugins/gone/a.jar"), "old");
        Process mk = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                app.resolve("plugins/gone/link").toString(), outside.toString()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        org.junit.jupiter.api.Assumptions.assumeTrue(mk.waitFor() == 0 && Files.exists(app.resolve("plugins/gone/link")),
                "mklink /J unavailable");
        Path upd = dir.resolve("upd");
        Path manifest = manifest(upd, "{\"install\":[],\"remove\":[" + json(app.resolve("plugins/gone")) + "]}");
        Path witness = dir.resolve("relaunched.txt");

        runScript(UpdateApply.pluginScriptCommand(UpdateApply.writeScript(upd), endedPid(), app, manifest,
                fakeExe(witness), dir.resolve("log"), null));

        assertFalse(Files.exists(app.resolve("plugins/gone")));
        assertEquals("keep me", Files.readString(outside.resolve("precious.txt")));
        awaitFile(witness);
    }

    /** Installing when plugins does not exist yet creates it. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void installingCreatesAMissingPluginsFolder() throws Exception {
        Path app = fakeApp();
        Path upd = dir.resolve("upd");
        write(upd.resolve("fresh/f.jar"), "f");
        Path manifest = manifest(upd, "{\"install\":[{\"source\":" + json(upd.resolve("fresh"))
                + ",\"target\":\"fresh\"}],\"remove\":[]}");
        Path witness = dir.resolve("relaunched.txt");

        runScript(UpdateApply.pluginScriptCommand(UpdateApply.writeScript(upd), endedPid(), app, manifest,
                fakeExe(witness), dir.resolve("log"), null));

        assertEquals("f", Files.readString(app.resolve("plugins/fresh/f.jar")));
        awaitFile(witness);
    }

    @Test
    void combinedCommandsAddTheManifestToTheApplicationMode() {
        String powershell = UpdateApply.powershell().toString();
        List<String> head = List.of(powershell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
                "-File", "s.ps1", "-ProcessId", "42", "-AppDir", "app");
        List<String> tail = List.of("-Exe", "Copybot.exe", "-LogDir", "logs");
        assertEquals(concat(head, List.of("-NewDir", "new", "-PluginManifest", "m.json"), tail),
                UpdateApply.scriptCommand(Path.of("s.ps1"), 42L, Path.of("app"), Path.of("new"), Path.of("m.json"),
                        Path.of("Copybot.exe"), Path.of("logs"), null));
        assertEquals(concat(head, List.of("-Installer", "setup.exe", "-PluginManifest", "m.json"), tail),
                UpdateApply.installerScriptCommand(Path.of("s.ps1"), 42L, Path.of("app"), Path.of("setup.exe"),
                        Path.of("m.json"), Path.of("Copybot.exe"), Path.of("logs"), null));
    }

    /** Portable copy and its plugins in one run: the application image, then the plugins, then one relaunch. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void thePortableUpdateAlsoUpdatesThePlugins() throws Exception {
        Path app = fakeApp();
        write(app.resolve("plugins/gps-zone/g.jar"), "old");
        Path upd = dir.resolve("upd");
        Path nd = upd.resolve("Copybot");
        write(nd.resolve("app/a.jar"), "v2-new");
        write(nd.resolve("Copybot.exe"), "exe2-new");
        write(upd.resolve("gps-zone/g.jar"), "new-jar");
        Path manifest = manifest(upd, "{\"install\":[{\"source\":" + json(upd.resolve("gps-zone"))
                + ",\"target\":\"gps-zone\"}],\"remove\":[]}");
        Path witness = dir.resolve("relaunched.txt");

        runScript(UpdateApply.scriptCommand(UpdateApply.writeScript(upd), endedPid(), app, nd, manifest,
                fakeExe(witness), dir.resolve("log"), null));

        assertEquals("v2-new", Files.readString(app.resolve("app/a.jar")));
        assertEquals("new-jar", Files.readString(app.resolve("plugins/gps-zone/g.jar")));
        assertEquals("c", Files.readString(app.resolve("config.json")));
        awaitFile(witness);
        awaitRemoved(upd);
    }

    /** A failed copy of the application leaves the plugins as they were: they may need the new application. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aFailedApplicationCopyLeavesThePlugins() throws Exception {
        Path app = dir.resolve("inst");
        write(app.resolve("app"), "a file where the folder should be: robocopy fails");
        write(app.resolve("Copybot.exe"), "exe1");
        write(app.resolve("plugins/gps-zone/g.jar"), "old");
        Path upd = dir.resolve("upd");
        Path nd = upd.resolve("Copybot");
        write(nd.resolve("app/a.jar"), "v2-new");
        write(nd.resolve("Copybot.exe"), "exe2-new");
        write(upd.resolve("gps-zone/g.jar"), "new-jar");
        Path manifest = manifest(upd, "{\"install\":[{\"source\":" + json(upd.resolve("gps-zone"))
                + ",\"target\":\"gps-zone\"}],\"remove\":[]}");
        Path witness = dir.resolve("relaunched.txt");
        Path logDir = dir.resolve("log");

        runScript(UpdateApply.scriptCommand(UpdateApply.writeScript(upd), endedPid(), app, nd, manifest,
                fakeExe(witness), logDir, null));

        assertEquals("old", Files.readString(app.resolve("plugins/gps-zone/g.jar")));
        awaitFile(witness);
        assertTrue(Files.readString(logDir.resolve("update.log")).contains("not applied"));
    }

    /** Installed copy and its plugins: the script runs the installer silently, then copies the plugins, then relaunches. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void theInstallerRunsThenThePluginsAreUpdated() throws Exception {
        Path app = fakeApp();
        write(app.resolve("plugins/gps-zone/g.jar"), "old");
        Path upd = dir.resolve("upd");
        Path ran = dir.resolve("installer-ran.txt");
        Path setup = fakeInstaller(upd, ran, 0);
        write(upd.resolve("gps-zone/g.jar"), "new-jar");
        Path manifest = manifest(upd, "{\"install\":[{\"source\":" + json(upd.resolve("gps-zone"))
                + ",\"target\":\"gps-zone\"}],\"remove\":[]}");
        Path witness = dir.resolve("relaunched.txt");
        Path config = dir.resolve("my data/config.json");

        runScript(UpdateApply.installerScriptCommand(UpdateApply.writeScript(upd), endedPid(), app, setup, manifest,
                fakeExe(witness), dir.resolve("log"), config));

        String args = Files.readString(ran);
        assertTrue(args.contains("/SILENT /SUPPRESSMSGBOXES /NORESTART"), args);
        assertFalse(args.contains("/RELAUNCH"), "the script relaunches, not the installer: " + args);
        assertEquals("new-jar", Files.readString(app.resolve("plugins/gps-zone/g.jar")));
        awaitFile(witness);
        assertTrue(Files.readString(witness).contains("--config-file=" + config), Files.readString(witness));
        awaitRemoved(upd);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aFailedInstallerLeavesThePlugins() throws Exception {
        Path app = fakeApp();
        write(app.resolve("plugins/gps-zone/g.jar"), "old");
        Path upd = dir.resolve("upd");
        Path setup = fakeInstaller(upd, dir.resolve("installer-ran.txt"), 5);
        write(upd.resolve("gps-zone/g.jar"), "new-jar");
        Path manifest = manifest(upd, "{\"install\":[{\"source\":" + json(upd.resolve("gps-zone"))
                + ",\"target\":\"gps-zone\"}],\"remove\":[]}");
        Path witness = dir.resolve("relaunched.txt");
        Path logDir = dir.resolve("log");

        runScript(UpdateApply.installerScriptCommand(UpdateApply.writeScript(upd), endedPid(), app, setup, manifest,
                fakeExe(witness), logDir, null));

        assertEquals("old", Files.readString(app.resolve("plugins/gps-zone/g.jar")));
        awaitFile(witness);
        assertTrue(Files.readString(logDir.resolve("update.log")).contains("Installer ended (code 5)"));
    }

    /** Only an installer of the folder of the script (the temporary folder of the update) is ever run. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void anInstallerOutsideTheScriptFolderIsRefused() throws Exception {
        Path app = fakeApp();
        write(app.resolve("plugins/gps-zone/g.jar"), "old");
        Path upd = dir.resolve("upd");
        Path ran = dir.resolve("installer-ran.txt");
        Path elsewhere = fakeInstaller(dir.resolve("elsewhere"), ran, 0);
        write(upd.resolve("gps-zone/g.jar"), "new-jar");
        Path manifest = manifest(upd, "{\"install\":[{\"source\":" + json(upd.resolve("gps-zone"))
                + ",\"target\":\"gps-zone\"}],\"remove\":[]}");
        Path witness = dir.resolve("relaunched.txt");
        Path logDir = dir.resolve("log");

        runScript(UpdateApply.installerScriptCommand(UpdateApply.writeScript(upd), endedPid(), app, elsewhere,
                manifest, fakeExe(witness), logDir, null));

        assertFalse(Files.exists(ran), "never run");
        assertEquals("old", Files.readString(app.resolve("plugins/gps-zone/g.jar")));
        awaitFile(witness);
        assertTrue(Files.readString(logDir.resolve("update.log")).contains("refused"));
    }

    /** A batch file standing for the installer: writes its arguments to {@code ran}, ends with {@code code}. */
    private static Path fakeInstaller(Path folder, Path ran, int code) throws IOException {
        Path setup = folder.resolve("copybot-9.9.9-setup.cmd");
        write(setup, "@echo off\r\necho %*> \"" + ran + "\"\r\nexit /b " + code + "\r\n");
        return setup;
    }

    @SafeVarargs
    private static List<String> concat(List<String>... parts) {
        return Stream.of(parts).flatMap(List::stream).toList();
    }

    private Path fakeApp() throws IOException {
        Path app = dir.resolve("inst");
        write(app.resolve("app/a.jar"), "v1");
        write(app.resolve("runtime/r.dll"), "r");
        write(app.resolve("Copybot.exe"), "exe1");
        write(app.resolve("config.json"), "c");
        return app;
    }

    private static void assertAppIntact(Path app) throws IOException {
        assertEquals("v1", Files.readString(app.resolve("app/a.jar")));
        assertEquals("r", Files.readString(app.resolve("runtime/r.dll")));
        assertEquals("exe1", Files.readString(app.resolve("Copybot.exe")));
        assertEquals("c", Files.readString(app.resolve("config.json")));
    }

    private static Path manifest(Path folder, String json) throws IOException {
        Path file = folder.resolve("plugin-manifest.json");
        write(file, json);
        return file;
    }

    /** A JSON string literal of the path. */
    private static String json(Path path) {
        return "\"" + path.toString().replace("\\", "\\\\") + "\"";
    }

    private static void awaitRemoved(Path folder) throws InterruptedException {
        for (int i = 0; i < 50 && Files.exists(folder); i++) {
            Thread.sleep(100); // the script may leave the rest of its own folder to a delayed removal
        }
        assertFalse(Files.exists(folder), "temporary update folder removed");
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

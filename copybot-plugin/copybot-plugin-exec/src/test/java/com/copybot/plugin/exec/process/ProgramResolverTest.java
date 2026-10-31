package com.copybot.plugin.exec.process;

import com.copybot.plugin.exec.process.ProgramResolver.Environment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class ProgramResolverTest {

    @TempDir
    Path tempDir;

    private Path folder(String name) throws IOException {
        return Files.createDirectories(tempDir.resolve(name));
    }

    private static Path file(Path folder, String name) throws IOException {
        return Files.writeString(folder.resolve(name), "x");
    }

    @Test
    public void onWindowsABareNameIsLookedUpInThePathWithTheExtensionsOfPathext() throws IOException {
        Path first = folder("first");
        Path second = folder("second");
        Path tool = file(second, "tool.exe");
        file(first, "tool.txt"); // not an extension of PATHEXT
        Environment windows = new Environment(first + ";;\"" + second + "\"", ".COM;.EXE", true);

        assertEquals(Optional.of(tool), ProgramResolver.resolve("tool", windows));
        assertEquals(Optional.of(tool), ProgramResolver.resolve("tool.exe", windows), "the extension written");
        assertEquals(Optional.empty(), ProgramResolver.resolve("other", windows));
    }

    @Test
    public void theFirstFolderOfThePathWins() throws IOException {
        Path first = folder("first");
        Path second = folder("second");
        Path inFirst = file(first, "tool.cmd");
        file(second, "tool.exe");

        assertEquals(Optional.of(inFirst),
                ProgramResolver.resolve("tool", new Environment(first + ";" + second, null, true)),
                "the default PATHEXT holds .CMD");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS) // a path of Windows holds the ':' that separates the folders of a Unix PATH
    public void elsewhereABareNameIsLookedUpInThePathAsWritten() throws IOException {
        Path bin = folder("bin");
        Path tool = file(bin, "tool");
        Environment unix = new Environment("relative:" + bin, null, false);

        assertEquals(Optional.of(tool), ProgramResolver.resolve("tool", unix));
    }

    @Test
    public void withoutPathABareNameIsNotFound() throws IOException {
        file(folder("bin"), "tool");

        assertEquals(Optional.empty(), ProgramResolver.resolve("tool", new Environment(null, null, false)));
        assertEquals(Optional.empty(), ProgramResolver.resolve("tool", new Environment(null, null, true)));
    }

    @Test
    public void aPathIsTakenAsWrittenAndOnWindowsCompletedWithAnExtension() throws IOException {
        Path bin = folder("bin");
        Path tool = file(bin, "tool.exe");

        assertEquals(Optional.of(tool), ProgramResolver.resolve(tool.toString(), new Environment(null, null, false)));
        assertEquals(Optional.of(tool), ProgramResolver.resolve(bin.resolve("tool").toString(),
                new Environment(null, null, true)));
        assertEquals(Optional.empty(), ProgramResolver.resolve(bin.resolve("absent.exe").toString(),
                new Environment(null, null, true)));
        assertEquals(Optional.empty(), ProgramResolver.resolve("  ", new Environment(null, null, true)));
    }

    @Test
    public void batchFilesAreToldApart() {
        assertTrue(ProgramResolver.isBatch(Path.of("C:/tools/run.BAT")));
        assertTrue(ProgramResolver.isBatch(Path.of("run.cmd")));
        assertFalse(ProgramResolver.isBatch(Path.of("ffmpeg.exe")));
    }
}

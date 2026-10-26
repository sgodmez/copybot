package com.copybot.ui.plugins;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

public class PluginArchiveTest {

    @TempDir
    Path dir;

    private int counter;

    private Path zip(String... names) throws IOException {
        Path zip = dir.resolve("plugin" + counter++ + ".zip");
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(out)) {
            for (String name : names) {
                z.putNextEntry(new ZipEntry(name));
                if (!name.endsWith("/")) {
                    z.write("x".getBytes());
                }
                z.closeEntry();
            }
        }
        return zip;
    }

    private Path into() throws IOException {
        return Files.createDirectory(dir.resolve("into" + counter++));
    }

    @Test
    public void aCorrectArchiveIsUnpackedUnderItsCode() throws Exception {
        Path into = into();

        Path folder = PluginArchive.extract(zip("my-plugin/", "my-plugin/my.jar", "my-plugin/lib/dep.jar",
                "my-plugin/readme.txt"), into, "my-plugin");

        assertEquals(into.resolve("my-plugin"), folder);
        assertTrue(Files.isRegularFile(folder.resolve("my.jar")));
        assertTrue(Files.isRegularFile(folder.resolve("lib/dep.jar")));
        assertTrue(Files.isRegularFile(folder.resolve("readme.txt")));
    }

    @Test
    public void aBadPluginZipIsRefused() throws Exception {
        String[][] bad = {
                {"../x", "my-plugin/my.jar"},
                {"my-plugin/../../x.jar", "my-plugin/my.jar"},
                {"/abs/my.jar"},
                {"my-plugin/my.jar", "other/other.jar"},
                {"other/other.jar"},
                {"my-plugin/readme.txt", "my-plugin/lib/"},
                {"my.jar"},
                {"my-plugin/my.jar", "stray.txt"},
                {"my-plugin\\..\\..\\x.jar"},
        };
        for (String[] names : bad) {
            Path into = into();
            Path archive = zip(names);
            assertThrows(IOException.class, () -> PluginArchive.extract(archive, into, "my-plugin"),
                    String.join(",", names));
            try (var files = Files.list(into)) {
                assertEquals(0, files.count(), "nothing is left after a refusal: " + String.join(",", names));
            }
        }
        assertFalse(Files.exists(dir.resolve("x")));
        assertFalse(Files.exists(dir.getParent().resolve("x")));
    }

    @Test
    public void anExistingTargetIsRefusedRatherThanMerged() throws Exception {
        Path into = into();
        Files.createDirectory(into.resolve("my-plugin"));
        Path archive = zip("my-plugin/my.jar");

        assertThrows(IOException.class, () -> PluginArchive.extract(archive, into, "my-plugin"));
    }
}

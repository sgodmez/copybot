package com.copybot.ui.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** A chooser opens in the folder of the current value: the folder itself, or the one of the file. */
public class ChoosersTest {

    @TempDir
    Path dir;

    @Test
    public void aFolderOpensItself() {
        assertEquals(Optional.of(dir.toFile()), Choosers.initialFolder(dir.toString()));
    }

    @Test
    public void aFileOrAMissingNameOpensItsFolder() throws IOException {
        Path file = Files.writeString(dir.resolve("photo.jpg"), "x");
        assertEquals(Optional.of(dir.toFile()), Choosers.initialFolder(file.toString()));
        assertEquals(Optional.of(dir.toFile()), Choosers.initialFolder(dir.resolve("not-yet").toString()));
    }

    @Test
    public void nothingThereOpensTheDefaultFolder() {
        assertEquals(Optional.empty(), Choosers.initialFolder(null));
        assertEquals(Optional.empty(), Choosers.initialFolder("  "));
        assertEquals(Optional.empty(), Choosers.initialFolder(dir.resolve("gone").resolve("deeper").toString()));
    }

    @Test
    public void theValueIsTrimmed() {
        Optional<File> folder = Choosers.initialFolder("  " + dir + "  ");
        assertEquals(Optional.of(dir.toFile()), folder);
    }
}

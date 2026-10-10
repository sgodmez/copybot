package com.copybot.engine.resources;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The volumes of this machine and the disk resource each one counts for, to show and set the disks. Each call
 * looks at the drives: a disconnected share may block it, so it is called off the JavaFX thread.
 */
public final class DiskInventory {

    /** A volume ("D:\", "/home", "\\nas\photos\") and the disk resource it counts for. */
    public record Volume(String path, String resource, DiskKind kind) {
    }

    private DiskInventory() {
    }

    /**
     * The volumes holding files of the user: the drives that are there on Windows (a card reader without card is
     * left out), the mounts of disks and shares on Linux, the roots elsewhere.
     */
    public static List<Volume> volumes() {
        List<String> paths = new ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.startsWith("linux")) {
            try {
                paths.addAll(LinuxDiskProbe.userMountPoints());
            } catch (IOException | RuntimeException e) {
                // unreadable: the roots below
            }
        }
        if (paths.isEmpty()) {
            for (Path root : FileSystems.getDefault().getRootDirectories()) {
                if (Files.exists(root)) {
                    paths.add(root.toString());
                }
            }
        }
        return paths.stream().map(p -> of(Path.of(p))).toList();
    }

    /** A folder chosen by the user (a share that is no drive): it stands for the volume holding it. */
    public static Volume of(Path path) {
        String resource = DiskResolver.diskResource(path);
        return new Volume(path.toString(), resource, DiskResolver.kindOf(resource));
    }
}

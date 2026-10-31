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

    /** A folder a pipeline reads or writes: the volume it lies on, named by its root, and whether it is there now. */
    public record Use(Volume volume, boolean present) {
    }

    /**
     * The volume of a folder of a pipeline, there or not (a share switched off, a card not inserted): named by its
     * root ("F:\", "\\nas\photos\"), or by the folder itself where every path has the same root ("/mnt/nas").
     */
    public static Use use(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path root = absolute.getRoot();
        boolean present = root != null && Files.exists(root);
        String resource = DiskResolver.diskResource(absolute);
        String name = root != null && root.toString().length() > 1 ? root.toString() : absolute.toString();
        return new Use(new Volume(name, resource, DiskResolver.kindOf(resource)), present);
    }

    /** A folder chosen by the user (a share that is no drive): it stands for the volume holding it. */
    public static Volume of(Path path) {
        String resource = DiskResolver.diskResource(path);
        return new Volume(path.toString(), resource, DiskResolver.kindOf(resource));
    }
}

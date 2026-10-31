package com.copybot.ui.plugins;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Unpacks a plugin archive: one root folder named after the plugin code, holding at least one jar. */
public final class PluginArchive {

    private PluginArchive() {
    }

    /**
     * Checks the whole archive, then unpacks it.
     *
     * @return {@code into/<code>}
     * @throws IOException for an archive that does not fit (an entry leaving the folder, several roots, a root other
     *                     than {@code code}, no jar) or an existing {@code into/<code>}; nothing is left then
     */
    public static Path extract(Path zip, Path into, String code) throws IOException {
        Path base = into.toAbsolutePath().normalize();
        Path target = base.resolve(code).normalize();
        if (!base.equals(target.getParent())) {
            throw new IOException("Bad plugin code: " + code);
        }
        if (Files.exists(target)) {
            throw new IOException("Already there: " + target);
        }
        try (ZipFile file = new ZipFile(zip.toFile())) {
            List<ZipEntry> entries = checked(file, target, code);
            try {
                for (ZipEntry entry : entries) {
                    Path path = path(entry.getName(), base);
                    if (entry.isDirectory()) {
                        Files.createDirectories(path);
                    } else {
                        Files.createDirectories(path.getParent());
                        try (InputStream in = file.getInputStream(entry)) {
                            Files.copy(in, path);
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                deleteQuietly(target);
                throw e;
            }
        }
        return target;
    }

    private static List<ZipEntry> checked(ZipFile file, Path target, String code) throws IOException {
        List<ZipEntry> entries = new ArrayList<>();
        boolean jar = false;
        for (var e = file.entries(); e.hasMoreElements();) {
            ZipEntry entry = e.nextElement();
            Path path = path(entry.getName(), target.getParent());
            if (!path.startsWith(target) || path.equals(target) && !entry.isDirectory()) {
                throw new IOException("Unexpected entry (expected a single folder " + code + "/): " + entry.getName());
            }
            jar |= !entry.isDirectory() && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar");
            entries.add(entry);
        }
        if (!jar) {
            throw new IOException("No jar in " + code + "/");
        }
        return entries;
    }

    /** The place of an entry under {@code base}; it may lie outside, the caller checks. */
    private static Path path(String name, Path base) throws IOException {
        String slashed = name.replace('\\', '/');
        if (slashed.startsWith("/") || slashed.matches("^[A-Za-z]:.*")) {
            throw new IOException("Absolute entry: " + name);
        }
        try {
            return base.resolve(slashed).normalize();
        } catch (RuntimeException e) { // an invalid path (InvalidPathException)
            throw new IOException("Bad entry: " + name, e);
        }
    }

    private static void deleteQuietly(Path folder) {
        try (var paths = Files.walk(folder)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException | RuntimeException e) {
            // nothing more to do, the caller deletes its folder
        }
    }
}

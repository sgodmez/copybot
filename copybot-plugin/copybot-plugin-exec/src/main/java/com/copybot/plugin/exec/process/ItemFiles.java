package com.copybot.plugin.exec.process;

import com.copybot.plugin.api.action.WorkItem;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

/** The files of an item handed to an external program, and the item made from what it wrote (spec §2). */
public final class ItemFiles {

    /** The device names of Windows: no file can bear them, whatever its extension. */
    private static final Pattern DEVICE = Pattern.compile("CON|PRN|AUX|NUL|CONIN\\$|CONOUT\\$"
            + "|(COM|LPT)[0-9\u00b9\u00b2\u00b3]");

    private ItemFiles() {
    }

    /** The name the plan shows: display.name, else the name of the source, else "item". */
    public static String name(WorkItem item) {
        String name = item.getMetadatas().display().get("name");
        if (name == null || name.isBlank()) {
            name = item.getNameDisplay();
        }
        return name == null || name.isBlank() ? "item" : name;
    }

    /** The name without its extension ("photo.heic" gives "photo", ".profile" stays). */
    public static String base(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    /** The extension without its dot, "" when none. */
    public static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? "" : name.substring(dot + 1);
    }

    /** The base with this extension; the base alone when the extension is empty. */
    public static String withExtension(String name, String extension) {
        return extension == null || extension.isEmpty() ? base(name) : base(name) + "." + extension;
    }

    /**
     * A file name any file system takes: the characters Windows refuses replaced with "_", without the dots and
     * spaces it drops at the end, and a device name of Windows (CON, NUL, COM1...) prefixed with "_".
     */
    public static String fileName(String name) {
        StringBuilder safe = new StringBuilder(name.length());
        for (char c : name.toCharArray()) {
            safe.append(c < 0x20 || "\\/:*?\"<>|".indexOf(c) >= 0 ? '_' : c);
        }
        String result = safe.toString().strip();
        int end = result.length();
        while (end > 0 && (result.charAt(end - 1) == '.' || result.charAt(end - 1) == ' ')) {
            end--;
        }
        result = result.substring(0, end);
        if (result.isEmpty()) {
            return "item";
        }
        int dot = result.indexOf('.');
        String stem = (dot < 0 ? result : result.substring(0, dot)).strip().toUpperCase(Locale.ROOT);
        return DEVICE.matcher(stem).matches() ? "_" + result : result;
    }

    /**
     * The file holding the item's content: its own local file when the content is that file as is, else a copy of
     * the content in {@code itemFolder}/in, under the shown name (same extension).
     */
    public static Path input(WorkItem item, Path itemFolder) throws IOException {
        if (item.getContentFile().isPresent()) {
            return item.getContentFile().get();
        }
        Path copy = Files.createDirectory(itemFolder.resolve("in")).resolve(fileName(name(item)));
        try (InputStream in = item.openInputStream()) {
            Files.copy(in, copy);
        }
        return copy;
    }

    /**
     * The item whose content is {@code file} (a working file of the step), of its real size, shown as
     * {@code name}; the source is kept.
     */
    public static WorkItem produced(WorkItem item, Path file, String name) throws IOException {
        WorkItem produced = item.withContentFile(file);
        produced.getMetadatas().setSize(Files.size(file));
        produced.getMetadatas().display().put("name", name);
        return produced;
    }
}

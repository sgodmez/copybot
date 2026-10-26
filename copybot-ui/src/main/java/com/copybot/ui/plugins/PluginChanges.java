package com.copybot.ui.plugins;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The folders to put in {@code plugins} and the folders to take out of it, as the apply script reads them. */
public final class PluginChanges {

    /** An unpacked plugin folder to copy to {@code plugins/<target>}. */
    public record Install(Path source, String target) {
    }

    private final List<Install> installs;
    private final List<Path> removals;

    public PluginChanges(List<Install> installs, List<Path> removals) {
        this.installs = List.copyOf(installs);
        this.removals = List.copyOf(removals);
    }

    public List<Install> installs() {
        return installs;
    }

    public List<Path> removals() {
        return removals;
    }

    /**
     * Plans the changes. Installing or updating a plugin removes the folders already holding its module (their name
     * is free), uninstalling removes them. Every row to install needs its unpacked folder in {@code extracted}.
     */
    public static PluginChanges plan(List<PluginRow> toInstallOrUpdate, Map<PluginRow, Path> extracted,
            List<PluginRow> toRemove) {
        List<Install> installs = new ArrayList<>();
        Set<Path> removals = new LinkedHashSet<>();
        for (PluginRow row : toInstallOrUpdate) {
            Path source = extracted.get(row);
            if (source == null) {
                throw new IllegalArgumentException("No unpacked folder for " + row.entry().code());
            }
            installs.add(new Install(source, row.entry().code()));
            removals.addAll(row.installedFolders());
        }
        for (PluginRow row : toRemove) {
            removals.addAll(row.installedFolders());
        }
        return new PluginChanges(installs, new ArrayList<>(removals));
    }

    /** Writes {@code {"install": [{"source", "target"}], "remove": [path]}} with absolute paths. */
    public void writeManifest(Path file) throws IOException {
        JsonObject root = new JsonObject();
        JsonArray install = new JsonArray();
        for (Install i : installs) {
            JsonObject o = new JsonObject();
            o.addProperty("source", i.source().toAbsolutePath().toString());
            o.addProperty("target", i.target());
            install.add(o);
        }
        JsonArray remove = new JsonArray();
        for (Path path : removals) {
            remove.add(path.toAbsolutePath().toString());
        }
        root.add("install", install);
        root.add("remove", remove);
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Files.writeString(file, gson.toJson(root) + System.lineSeparator(), StandardCharsets.UTF_8);
    }
}

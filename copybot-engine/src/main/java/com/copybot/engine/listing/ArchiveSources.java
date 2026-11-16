package com.copybot.engine.listing;

import com.copybot.plugin.api.action.SourceDeleter;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.resources.ResourcesEngine;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tracks the entries of the unfolded archives of a run (spec grouping/archives, "Entrées"): in move mode, an archive
 * is deleted only once each of its entries was released (written or found identical at the destination) in this
 * run. Any other entry keeps it (excluded, ignored, filtered, in error...): in doubt, the archive is kept. No state
 * between two runs.
 */
public final class ArchiveSources {

    private static final class Tracked {
        final int entries;
        final Closeable opened;
        int released;

        Tracked(int entries, Closeable opened) {
            this.entries = entries;
            this.opened = opened;
        }
    }

    private final Map<WorkItem, Tracked> archives = new LinkedHashMap<>(); // WorkItem: identity equality
    private final List<String> warnings = new ArrayList<>();

    /** Where an archive is: its local file, else its listed folder and name. */
    static String location(WorkItem archive) {
        return archive.getLocalLocation() != null
                ? archive.getLocalLocation().toString()
                : archive.getListedFolder() + "/" + archive.getNameDisplay();
    }

    /** @param entries every file entry of the archive, the ones that are not emitted included */
    public void register(WorkItem archive, int entries) {
        register(archive, entries, null);
    }

    /** @param opened the open archive, closed before the archive is deleted (Windows cannot delete an open file) */
    synchronized void register(WorkItem archive, int entries, Closeable opened) {
        archives.put(archive, new Tracked(entries, opened));
    }

    /**
     * The deleter of one entry: releases it once (a second call, e.g. from another product of the same entry, does
     * nothing).
     */
    SourceDeleter entryDeleter(WorkItem archive) {
        boolean[] done = {false};
        return () -> {
            synchronized (done) {
                if (done[0]) {
                    return;
                }
                done[0] = true;
            }
            release(archive);
        };
    }

    /**
     * Releases one entry of the archive; once all of them are released, deletes the archive: its local file, else
     * through its own source deleter (none: kept).
     *
     * @throws IOException the archive cannot be deleted
     */
    public void release(WorkItem archive) throws IOException {
        Tracked tracked;
        synchronized (this) {
            tracked = archives.get(archive);
            if (tracked == null || ++tracked.released != tracked.entries) {
                return;
            }
        }
        if (tracked.opened != null) {
            tracked.opened.close();
        }
        if (archive.getLocalLocation() != null) {
            Files.deleteIfExists(archive.getLocalLocation());
        } else {
            Optional<SourceDeleter> deleter = archive.getSourceDeleter();
            if (deleter.isPresent()) {
                deleter.get().delete();
            }
        }
    }

    /** Adds a user-facing warning of the unfolding (entry refused, archive too deep copied as it is). */
    synchronized void warn(String line) {
        warnings.add(line);
    }

    /** The warnings of the unfolding: entries refused, nested archives too deep copied as they are. */
    public synchronized List<String> warnings() {
        return List.copyOf(warnings);
    }

    /** One line per archive kept although some of its entries were released: "archive kept, N entries not copied". */
    public synchronized List<String> keptWarnings() {
        List<String> lines = new ArrayList<>();
        archives.forEach((archive, tracked) -> {
            if (tracked.released > 0 && tracked.released < tracked.entries) {
                lines.add(ResourcesEngine.getString("archive.kept", location(archive),
                        tracked.entries - tracked.released));
            }
        });
        return lines;
    }
}

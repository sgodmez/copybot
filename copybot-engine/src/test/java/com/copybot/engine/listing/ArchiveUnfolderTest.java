package com.copybot.engine.listing;

import com.copybot.engine.pipeline.ArchivesConfig;
import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.ListingSink;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import com.copybot.plugin.embedded.actions.FileReadAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchiveUnfolderTest {

    @TempDir
    Path dir;

    private ArchiveWorkspace ws;
    private final ArchiveSources sources = new ArchiveSources();
    private final List<String> events = new ArrayList<>();
    private final List<WorkItem> emitted = new ArrayList<>();
    private final List<String> failed = new ArrayList<>();
    private final List<CopybotException> causes = new ArrayList<>();
    private final AtomicInteger unfolded = new AtomicInteger();
    private final List<Long> downloads = new ArrayList<>();

    private final ListingSink out = new ListingSink() {
        @Override
        public void item(WorkItem item) {
            emitted.add(item);
            events.add("item " + item.getListedFolder() + " | " + item.getNameDisplay());
        }

        @Override
        public void folderDone(String folder) {
            events.add("done " + folder);
        }
    };

    private final ListingFailures failures = (item, cause) -> {
        failed.add(item.getNameDisplay());
        causes.add(cause);
    };

    private final ArchiveProgress progress = new ArchiveProgress() {
        @Override
        public void unfolded() {
            unfolded.incrementAndGet();
        }

        @Override
        public void toDownload(long bytes) {
            downloads.add(bytes);
        }
    };

    @BeforeEach
    void openWorkspace() throws IOException {
        ws = ArchiveWorkspace.open(dir.resolve("ws"));
    }

    @AfterEach
    void closeWorkspace() {
        ws.close();
    }

    // ---- helpers ----

    private static ArchivesConfig config(boolean nested, boolean merge) {
        return new ArchivesConfig(true, nested, merge, null, null);
    }

    private ArchiveUnfolder unfolder(ArchivesConfig config) {
        return new ArchiveUnfolder(config, ws, sources, out, failures, 1, progress);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** A ZIP of these entries, in order; a name ending with '/' is a directory entry. */
    private static byte[] zipBytes(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        return buffer.toByteArray();
    }

    private static Map<String, byte[]> entries(String... namesAndContents) {
        Map<String, byte[]> map = new LinkedHashMap<>();
        for (int i = 0; i < namesAndContents.length; i += 2) {
            map.put(namesAndContents[i], bytes(namesAndContents[i + 1]));
        }
        return map;
    }

    private Path zip(String name, Map<String, byte[]> entries) throws IOException {
        Path file = dir.resolve(name);
        Files.createDirectories(file.getParent());
        return Files.write(file, zipBytes(entries));
    }

    private static WorkItem local(Path file) throws IOException {
        return FileReadAction.workItemOf(file);
    }

    private static String read(WorkItem item) throws IOException {
        try (InputStream in = item.openInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private WorkItem emittedNamed(String name) {
        return emitted.stream().filter(i -> i.getNameDisplay().equals(name)).findFirst().orElseThrow();
    }

    private List<String> workspaceFiles() throws IOException {
        try (Stream<Path> files = Files.list(ws.folder())) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    // ---- entries ----

    @Test
    void localZipEntriesAreEmittedPerInnerFolder() throws IOException {
        Path zip = zip("d/a.zip", entries("x.jpg", "x", "s/y.jpg", "yy", "s/z.jpg", "zzz"));
        String root = zip + "!";
        ArchiveUnfolder unfolder = unfolder(config(false, false));
        unfolder.item(local(zip));
        unfolder.folderDone(zip.getParent().toString());
        unfolder.finish();

        assertEquals(List.of(
                "item " + root + " | x.jpg",
                "item " + root + "/s | y.jpg",
                "item " + root + "/s | z.jpg",
                "done " + root + "/s",
                "done " + root,
                "done " + zip.getParent()), events);
        WorkItem z = emittedNamed("z.jpg");
        assertEquals(root + "/s", z.getSourceLocationDisplay());
        assertEquals(3L, z.getMetadatas().getSize());
        assertEquals("zzz", read(z));
        assertEquals("zzz", read(z)); // reopened at each read
        assertFalse(z.isLocal());
        assertTrue(z.getSourceDeleter().isPresent());
        assertEquals(1, unfolded.get());
        assertEquals(List.of(), downloads);
        assertEquals(List.of(), failed);
    }

    @Test
    void notAZipOrUnfoldOffPassesThrough() throws IOException {
        Path zip = zip("a.zip", entries("x.jpg", "x"));
        Path jpg = Files.writeString(dir.resolve("b.jpg"), "b");
        ArchiveUnfolder off = new ArchiveUnfolder(new ArchivesConfig(false, false, false, null, null), ws, sources,
                out, failures, 1);
        off.item(local(zip));
        unfolder(config(false, false)).item(local(jpg));
        assertEquals(List.of("item " + dir + " | a.zip", "item " + dir + " | b.jpg"), events);
    }

    @Test
    void upperCaseExtensionIsUnfolded() throws IOException {
        Path zip = zip("A.ZIP", entries("x.jpg", "x"));
        unfolder(config(false, false)).item(local(zip));
        assertEquals(List.of("item " + zip + "! | x.jpg", "done " + zip + "!"), events);
    }

    @Test
    void extendedAndDosDates() throws IOException {
        Instant extended = Instant.parse("2024-05-01T10:00:01Z");
        LocalDateTime dos = LocalDateTime.of(2023, 3, 4, 5, 6, 8);
        Path zip = dir.resolve("dates.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            ZipEntry e1 = new ZipEntry("ext.jpg");
            e1.setLastModifiedTime(FileTime.from(extended));
            out.putNextEntry(e1);
            out.closeEntry();
            ZipEntry e2 = new ZipEntry("dos.jpg");
            e2.setTime(dos.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
            out.putNextEntry(e2);
            out.closeEntry();
        }
        unfolder(config(false, false)).item(local(zip));
        assertEquals(extended,
                emittedNamed("ext.jpg").getMetadatas().getTime(WorkItemMetadata.LAST_MODIFIED).orElseThrow());
        assertEquals(dos.atZone(ZoneId.systemDefault()).toInstant(),
                emittedNamed("dos.jpg").getMetadatas().getTime(WorkItemMetadata.LAST_MODIFIED).orElseThrow());
    }

    @Test
    void trappedNamesKeepTheirLastSegmentInTheArchive() throws IOException {
        Path zip = zip("slip.zip", entries("../../x.jpg", "x", "/abs/y.jpg", "y", "s\\..\\..\\w.jpg", "w",
                "c:v.jpg", "v", "s/..", "refused", "ok/u.jpg", "u"));
        unfolder(config(false, false)).item(local(zip));
        String root = zip + "!";
        assertEquals(List.of(
                "item " + root + " | x.jpg",
                "item " + root + " | y.jpg",
                "item " + root + " | w.jpg",
                "item " + root + " | v.jpg",
                "item " + root + "/ok | u.jpg",
                "done " + root + "/ok",
                "done " + root), events);
        List<String> warnings = sources.warnings();
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("s/..") && warnings.get(0).contains(zip.toString()), warnings.get(0));
    }

    @Test
    void namesThatAreNotUtf8AreReadAsCp437() throws IOException {
        String name = "caf" + (char) 0xE9 + ".jpg";
        Path zip = dir.resolve("cp437.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip), Charset.forName("IBM437"))) {
            out.putNextEntry(new ZipEntry(name)); // no UTF-8 flag, 0x82 is not UTF-8
            out.write(bytes("c"));
            out.closeEntry();
        }
        unfolder(config(false, false)).item(local(zip));
        assertEquals(List.of(), failed);
        assertEquals("c", read(emittedNamed(name)));
    }

    @Test
    void directoryEntriesAreNotEmitted() throws IOException {
        Path zip = zip("dirs.zip", entries("a/", "", "a/b/", "", "a/x.jpg", "x"));
        unfolder(config(false, false)).item(local(zip));
        String root = zip + "!";
        assertEquals(List.of("item " + root + "/a | x.jpg", "done " + root + "/a", "done " + root), events);
    }

    @Test
    void includeAndExcludeFilterTheInnerPaths() throws IOException {
        Path zip = zip("f.zip", entries("a/x.JPG", "x", "a/x.json", "j", "b/y.jpg", "y"));
        new ArchiveUnfolder(new ArchivesConfig(true, false, false, List.of("**/*.jpg"), List.of("b/**")), ws,
                sources, out, failures, 1).item(local(zip));
        assertEquals(List.of("x.JPG"), emitted.stream().map(WorkItem::getNameDisplay).toList());
    }

    // ---- failures: nothing emitted ----

    private void assertFailedWithNothingEmitted(String name) throws IOException {
        assertEquals(List.of(name), failed);
        assertEquals(List.of(), events);
        assertEquals(List.of("run.lock"), workspaceFiles());
        assertEquals(0, unfolded.get());
    }

    @Test
    void corruptArchiveFails() throws IOException {
        Path zip = Files.writeString(dir.resolve("bad.zip"), "this is not a zip at all, not even close to one");
        unfolder(config(false, false)).item(local(zip));
        assertFailedWithNothingEmitted("bad.zip");
    }

    @Test
    void truncatedArchiveFails() throws IOException {
        byte[] full = zipBytes(entries("x.jpg", "some content", "y.jpg", "more content"));
        Path zip = Files.write(dir.resolve("cut.zip"), Arrays.copyOf(full, full.length / 2));
        unfolder(config(false, false)).item(local(zip));
        assertFailedWithNothingEmitted("cut.zip");
    }

    @Test
    void encryptedArchiveFails() throws IOException {
        byte[] data = zipBytes(entries("x.jpg", "x", "y.jpg", "y"));
        // sets bit 0 (encrypted) of the general purpose flag of the second entry, local and central headers
        int seen = 0;
        for (int i = 0; i + 4 <= data.length; i++) {
            if (data[i] == 'P' && data[i + 1] == 'K' && data[i + 2] == 3 && data[i + 3] == 4 && seen++ == 1) {
                data[i + 6] |= 1;
            }
        }
        int central = 0;
        for (int i = 0; i + 4 <= data.length; i++) {
            if (data[i] == 'P' && data[i + 1] == 'K' && data[i + 2] == 1 && data[i + 3] == 2 && central++ == 1) {
                data[i + 8] |= 1;
            }
        }
        Path zip = Files.write(dir.resolve("enc.zip"), data);
        unfolder(config(false, false)).item(local(zip));
        assertFailedWithNothingEmitted("enc.zip");
        assertTrue(causes.get(0).getMessage().contains("encrypted"), causes.get(0).getMessage());
    }

    @Test
    void failedRemoteArchiveLeavesNoCopy() throws IOException {
        byte[] data = bytes("not a zip, but long enough to be looked at by the unfolder");
        WorkItem remote = WorkItem.remote("r.zip", "ftp://h/d", "ftp://h/d", data.length, Instant.EPOCH,
                () -> new ByteArrayInputStream(data), null);
        unfolder(config(false, false)).item(remote);
        assertFailedWithNothingEmitted("r.zip");
    }

    @Test
    void insufficientSpaceFails() throws IOException {
        ws.close();
        ws = ArchiveWorkspace.open(dir.resolve("ws"), folder -> 10);
        byte[] data = zipBytes(entries("x.jpg", "x"));
        WorkItem remote = WorkItem.remote("r.zip", "ftp://h/d", "ftp://h/d", data.length, Instant.EPOCH,
                () -> new ByteArrayInputStream(data), null);
        unfolder(config(false, false)).item(remote);
        assertFailedWithNothingEmitted("r.zip");
        assertEquals(List.of((long) data.length), downloads); // reported before the copy, with the item's size
    }

    @Test
    void overlappingArchiveFailsAsAZipBomb() throws IOException {
        // two central entries pointing at the same local header
        byte[] one = zipBytes(entries("a.jpg", "a"));
        int cen = -1;
        int eocd = -1;
        for (int i = 0; i + 4 <= one.length; i++) {
            if (one[i] == 'P' && one[i + 1] == 'K' && one[i + 2] == 1 && one[i + 3] == 2) {
                cen = i;
            }
            if (one[i] == 'P' && one[i + 1] == 'K' && one[i + 2] == 5 && one[i + 3] == 6) {
                eocd = i;
            }
        }
        byte[] cenEntry = Arrays.copyOfRange(one, cen, eocd);
        cenEntry[46] = 'b'; // a second name, same data
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(one, 0, eocd);
        out.write(cenEntry);
        byte[] end = Arrays.copyOfRange(one, eocd, one.length);
        end[8] = 2;
        end[10] = 2;
        int cdSize = 2 * cenEntry.length;
        end[12] = (byte) cdSize;
        end[13] = (byte) (cdSize >> 8);
        out.write(end);
        Path zip = Files.write(dir.resolve("bomb.zip"), out.toByteArray());
        unfolder(config(false, false)).item(local(zip));
        assertFailedWithNothingEmitted("bomb.zip");
    }

    @Test
    void entryContentIsRatioGuarded() throws IOException {
        Path zip = zip("ratio.zip", Map.of("zeros.bin", new byte[4 * 1024 * 1024]));
        unfolder(config(false, false)).item(local(zip));
        WorkItem entry = emittedNamed("zeros.bin");
        assertThrows(ZipBombException.class, () -> {
            try (InputStream in = entry.openInputStream()) {
                in.readAllBytes();
            }
        });
    }

    // ---- remote archives ----

    @Test
    void remoteArchiveIsCopiedOnceIntoTheWorkspace() throws IOException {
        byte[] data = zipBytes(entries("p/x.jpg", "x"));
        AtomicInteger opened = new AtomicInteger();
        WorkItem remote = WorkItem.remote("r.zip", "ftp://h/d", "ftp://h/d", data.length, Instant.EPOCH,
                () -> {
                    opened.incrementAndGet();
                    return new ByteArrayInputStream(data);
                }, null);
        unfolder(config(false, false)).item(remote);
        assertEquals(List.of(
                "item ftp://h/d/r.zip!/p | x.jpg",
                "done ftp://h/d/r.zip!/p",
                "done ftp://h/d/r.zip!"), events);
        assertEquals("x", read(emitted.get(0)));
        assertEquals("x", read(emitted.get(0)));
        assertEquals(1, opened.get());
        assertEquals(2, workspaceFiles().size()); // the copy and run.lock
        assertEquals(List.of((long) data.length), downloads);
        assertEquals(1, unfolded.get());
    }

    // ---- nested archives ----

    @Test
    void nestedOffEmitsTheInnerZipAsItIs() throws IOException {
        byte[] inner = zipBytes(entries("y/c.txt", "c"));
        Map<String, byte[]> outer = new LinkedHashMap<>();
        outer.put("x/b.zip", inner);
        outer.put("x/d.jpg", bytes("d"));
        Path zip = zip("a.zip", outer);
        unfolder(config(false, false)).item(local(zip));
        assertEquals(List.of("item " + zip + "!/x | b.zip", "item " + zip + "!/x | d.jpg", "done " + zip + "!/x",
                "done " + zip + "!"), events);
        // the inner zip copied as it is keeps the archive
        assertTrue(emittedNamed("b.zip").getSourceDeleter().isEmpty());
        emittedNamed("d.jpg").getSourceDeleter().orElseThrow().delete();
        assertTrue(Files.exists(zip));
        assertEquals(1, sources.keptWarnings().size());
        assertEquals(List.of(), sources.warnings()); // nested off: no depth warning
    }

    @Test
    void nestedOnUnfoldsTheInnerZip() throws IOException {
        byte[] inner = zipBytes(entries("y/c.txt", "c"));
        Map<String, byte[]> outer = new LinkedHashMap<>();
        outer.put("x/b.zip", inner);
        Path zip = zip("a.zip", outer);
        unfolder(config(true, false)).item(local(zip));
        String nested = zip + "!/x/b.zip!";
        assertEquals(List.of(
                "item " + nested + "/y | c.txt",
                "done " + nested + "/y",
                "done " + nested,
                "done " + zip + "!/x",
                "done " + zip + "!"), events);
        assertEquals("c", read(emitted.get(0)));
        assertEquals(2, unfolded.get());
        assertEquals(List.of(), downloads); // the inner zip is copied out of a local zip: not a download
    }

    @Test
    void beyondDepthFiveTheZipIsEmittedAsItIs() throws IOException {
        byte[] current = zipBytes(entries("f.txt", "f"));
        for (int level = 6; level >= 2; level--) {
            Map<String, byte[]> wrapper = new LinkedHashMap<>();
            wrapper.put("a" + level + ".zip", current);
            if (level == 6) {
                wrapper.put("g.txt", bytes("g")); // a5.zip holds a6.zip and g.txt
            }
            current = zipBytes(wrapper);
        }
        Path zip = Files.write(dir.resolve("a1.zip"), current);
        unfolder(config(true, false)).item(local(zip));
        assertEquals(List.of("a6.zip", "g.txt"), emitted.stream().map(WorkItem::getNameDisplay).toList());
        String a5 = zip + "!/a2.zip!/a3.zip!/a4.zip!/a5.zip!";
        assertEquals(a5, emitted.get(0).getListedFolder());
        assertEquals(5, unfolded.get());
        assertEquals(List.of(), downloads); // nested copies out of a local zip are not downloads
        List<String> warnings = sources.warnings();
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains(a5 + "/a6.zip"), warnings.get(0));
        // the deepest archive, a6.zip copied as it is, keeps a5.zip and so every archive above it
        assertTrue(emittedNamed("a6.zip").getSourceDeleter().isEmpty());
        emittedNamed("g.txt").getSourceDeleter().orElseThrow().delete();
        assertTrue(Files.exists(zip));
        List<String> kept = sources.keptWarnings();
        assertEquals(1, kept.size(), kept.toString());
        assertTrue(kept.get(0).contains("a5.zip"), kept.get(0));
    }

    @Test
    void anArchiveToUnfoldIsNotFilteredByInclude() throws IOException {
        Map<String, byte[]> outer = new LinkedHashMap<>();
        outer.put("x/b.zip", zipBytes(entries("c.jpg", "c", "d.txt", "d")));
        outer.put("e.txt", bytes("e"));
        Path zip = zip("a.zip", outer);
        new ArchiveUnfolder(new ArchivesConfig(true, true, false, List.of("**/*.jpg"), null), ws, sources, out,
                failures, 1, progress).item(local(zip));
        assertEquals(List.of("c.jpg"), emitted.stream().map(WorkItem::getNameDisplay).toList());
    }

    // ---- merge folder ----

    @Test
    void mergeFolderJoinsThePartsOfAFolder() throws IOException {
        Path p1 = zip("d/part1.zip", entries("T/Album/IMG_1.jpg", "1", "T/x.txt", "x"));
        Path p2 = zip("d/part2.zip", entries("T/Album/IMG_1.jpg.json", "j"));
        String folder = p1.getParent().toString();
        ArchiveUnfolder unfolder = unfolder(config(false, true));
        unfolder.item(local(p1));
        unfolder.item(local(p2));
        assertTrue(events.stream().noneMatch(e -> e.startsWith("done")));
        unfolder.folderDone(folder);
        unfolder.finish();
        String root = folder + "!";
        assertEquals(List.of(
                "item " + root + "/T | x.txt",
                "item " + root + "/T/Album | IMG_1.jpg",
                "item " + root + "/T/Album | IMG_1.jpg.json",
                "done " + folder,
                "done " + root + "/T/Album",
                "done " + root + "/T",
                "done " + root), events);
        assertEquals(p2 + "!/T/Album", emittedNamed("IMG_1.jpg.json").getSourceLocationDisplay());
    }

    @Test
    void mergeFolderWithoutSignalIsDoneAtFinish() throws IOException {
        Path p1 = zip("d/part1.zip", entries("x.jpg", "x"));
        ArchiveUnfolder unfolder = unfolder(config(false, true));
        unfolder.item(local(p1));
        unfolder.finish();
        String root = p1.getParent() + "!";
        assertEquals(List.of("item " + root + " | x.jpg", "done " + root), events);
    }

    // ---- source tracking ----

    @Test
    void archiveIsDeletedOnceEveryEntryIsReleased() throws IOException {
        Path zip = zip("m.zip", entries("x.jpg", "x", "s/y.jpg", "y"));
        unfolder(config(false, false)).item(local(zip));
        emitted.get(0).getSourceDeleter().orElseThrow().delete();
        emitted.get(0).getSourceDeleter().orElseThrow().delete(); // a second release of the same entry: ignored
        assertTrue(Files.exists(zip));
        assertEquals(1, sources.keptWarnings().size());
        emitted.get(1).getSourceDeleter().orElseThrow().delete();
        assertFalse(Files.exists(zip));
        assertEquals(List.of(), sources.keptWarnings());
    }

    @Test
    void archiveWithAnEntryNotReleasedIsKept() throws IOException {
        Path zip = zip("k.zip", entries("x.jpg", "x", "y.jpg", "y", "z.json", "z"));
        new ArchiveUnfolder(new ArchivesConfig(true, false, false, null, List.of("*.json")), ws, sources, out,
                failures, 1).item(local(zip));
        for (WorkItem item : emitted) {
            item.getSourceDeleter().orElseThrow().delete();
        }
        assertTrue(Files.exists(zip));
        List<String> warnings = sources.keptWarnings();
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains(zip.toString()), warnings.get(0));
        assertTrue(warnings.get(0).contains("1"), warnings.get(0));
    }

    @Test
    void untouchedArchiveGivesNoWarning() throws IOException {
        Path zip = zip("u.zip", entries("x.jpg", "x"));
        unfolder(config(false, false)).item(local(zip));
        assertEquals(List.of(), sources.keptWarnings());
    }

    @Test
    void remoteArchiveIsDeletedByItsOwnDeleter() throws IOException {
        byte[] data = zipBytes(entries("x.jpg", "x"));
        AtomicInteger deleted = new AtomicInteger();
        WorkItem remote = WorkItem.remote("r.zip", "ftp://h/d", "ftp://h/d", data.length, Instant.EPOCH,
                () -> new ByteArrayInputStream(data), deleted::incrementAndGet);
        unfolder(config(false, false)).item(remote);
        emitted.get(0).getSourceDeleter().orElseThrow().delete();
        assertEquals(1, deleted.get());
    }

    @Test
    void nestedArchiveReleasesItsParent() throws IOException {
        Map<String, byte[]> outer = new LinkedHashMap<>();
        outer.put("b.zip", zipBytes(entries("c.txt", "c")));
        Path zip = zip("n.zip", outer);
        unfolder(config(true, false)).item(local(zip));
        emittedNamed("c.txt").getSourceDeleter().orElseThrow().delete();
        assertFalse(Files.exists(zip));
    }

    @Test
    void failureCauseIsACopybotException() throws IOException {
        Path zip = Files.writeString(dir.resolve("bad.zip"), "garbage garbage garbage garbage garbage");
        unfolder(config(false, false)).item(local(zip));
        assertInstanceOf(CopybotException.class, causes.get(0));
        assertTrue(causes.get(0).getMessage().contains(zip.toString()), causes.get(0).getMessage());
    }
}

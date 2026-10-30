package com.copybot.plugin.archive.actions;

import com.copybot.plugin.api.action.ItemOutcome;
import com.copybot.plugin.api.action.RunCounts;
import com.copybot.plugin.api.action.RunEndContext;
import com.copybot.plugin.api.action.RunItem;
import com.copybot.plugin.api.action.RunOutcome;
import com.copybot.plugin.api.action.RunReport;
import com.copybot.plugin.api.action.WrittenFile;
import com.copybot.plugin.archive.ArchivePlugin;
import com.copybot.resources.CombinedResourceBundle;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

public class ArchiveZipTest {

    private static Locale defaultLocale;

    @BeforeAll
    static void english() {
        // the bundle starts from the default locale: the messages checked below are the English ones
        defaultLocale = Locale.getDefault();
        Locale.setDefault(Locale.ENGLISH);
    }

    @AfterAll
    static void restoreLocale() {
        Locale.setDefault(defaultLocale);
    }

    @TempDir
    Path dir;

    private final List<String> warnings = new ArrayList<>();
    private final Map<String, String> published = new HashMap<>();
    private final RunEndContext context = new RunEndContext() {
        @Override
        public void publish(String key, String value) {
            published.put(key, value);
        }

        @Override
        public void warn(String message) {
            warnings.add(message);
        }
    };

    private static ArchiveZip action(JsonObject config) {
        return action(new ArchiveZip(), config);
    }

    private static ArchiveZip action(ArchiveZip action, JsonObject config) {
        ArchivePlugin plugin = new ArchivePlugin();
        CombinedResourceBundle bundle = new CombinedResourceBundle(ArchivePlugin.class.getModule(), null);
        plugin.getI18nBundleNames().forEach(bundle::addBundle);
        bundle.load(Locale.ENGLISH);
        plugin.setResourceBundle(bundle);
        action.setPlugin(plugin);
        action.loadConfig(config);
        return action;
    }

    private static JsonObject config(Path target, String content) {
        JsonObject json = new JsonObject();
        json.addProperty("target", target.toString());
        if (content != null) {
            json.addProperty("content", content);
        }
        return json;
    }

    private Path write(String relative, byte[] bytes) throws IOException {
        Path file = dir.resolve("src").resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
        return file;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private WrittenFile written(Path file) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        return new WrittenFile(file, sha256(bytes), bytes.length);
    }

    private static RunReport report(String pipeline, List<WrittenFile> files, List<RunItem> items) {
        Instant start = Instant.parse("2026-10-30T10:05:09Z");
        return new RunReport(pipeline, Path.of("/p/pipeline.json"), RunOutcome.SUCCESS, start, start,
                start.plusSeconds(5), new RunCounts(files.size(), 0, 0, 0), items, files, List.of(), List.of(),
                null, "run-1", Map.of());
    }

    private static RunReport report(List<WrittenFile> files) {
        return report("holidays", files, List.of());
    }

    private static String text(ZipFile zip, String name) throws IOException {
        try (InputStream in = zip.getInputStream(zip.getEntry(name))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String> parseSums(String sums) {
        Map<String, String> map = new TreeMap<>();
        for (String line : sums.split("\n")) {
            int sep = line.indexOf("  ");
            map.put(line.substring(sep + 2), line.substring(0, sep));
        }
        return map;
    }

    @Test
    void zipHoldsTheFilesAndASumsManifestThatMatchesARecomputation() throws Exception {
        byte[] a = "photo bytes".repeat(100).getBytes(StandardCharsets.UTF_8);
        byte[] b = "notes".repeat(50).getBytes(StandardCharsets.UTF_8);
        Path fa = write("10/a.jpg", a);
        Path fb = write("11/b.txt", b);
        Path target = dir.resolve("out").resolve("archive.zip");

        action(config(target, null)).runEnded(report(List.of(written(fa), written(fb))), context);

        assertTrue(warnings.isEmpty(), warnings.toString());
        assertEquals(target.toString(), published.get("archive.path"));
        try (ZipFile zip = new ZipFile(target.toFile())) {
            assertArrayEquals(a, zip.getInputStream(zip.getEntry("10/a.jpg")).readAllBytes());
            assertArrayEquals(b, zip.getInputStream(zip.getEntry("11/b.txt")).readAllBytes());
            Map<String, String> sums = parseSums(text(zip, "SHA256SUMS"));
            assertEquals(Map.of("10/a.jpg", sha256(a), "11/b.txt", sha256(b)), sums);
            assertEquals(3, zip.size());
        }
        // parents created, no temporary file left
        try (var list = Files.list(target.getParent())) {
            assertEquals(List.of(target), list.toList());
        }
    }

    @Test
    void manifestModeWritesTheSumsFileAlone() throws Exception {
        byte[] a = "x".repeat(10).getBytes(StandardCharsets.UTF_8);
        Path fa = write("a.bin", a);
        Path target = dir.resolve("SHA256SUMS");

        action(config(target, "manifest")).runEnded(report(List.of(written(fa))), context);

        assertEquals(sha256(a) + "  a.bin\n", Files.readString(target));
        assertEquals(target.toString(), published.get("archive.path"));
    }

    @Test
    void entriesAreRelativeToTheBaseFolderOrTheCommonDirectory() throws Exception {
        Path f1 = write("p/q/1.txt", new byte[]{1});
        Path f2 = write("p/r/2.txt", new byte[]{2});
        Path target = dir.resolve("m1");
        action(config(target, "manifest")).runEnded(report(List.of(written(f1), written(f2))), context);
        // common directory: .../src/p
        assertEquals(List.of("q/1.txt", "r/2.txt"), new ArrayList<>(parseSums(Files.readString(target)).keySet()));

        Path alone = dir.resolve("m2");
        action(config(alone, "manifest")).runEnded(report(List.of(written(f1))), context);
        assertEquals(List.of("1.txt"), new ArrayList<>(parseSums(Files.readString(alone)).keySet()));

        JsonObject withBase = config(dir.resolve("m3"), "manifest");
        withBase.addProperty("baseFolder", dir.resolve("src").toString());
        action(withBase).runEnded(report(List.of(written(f1), written(f2))), context);
        assertEquals(List.of("p/q/1.txt", "p/r/2.txt"),
                new ArrayList<>(parseSums(Files.readString(dir.resolve("m3"))).keySet()));
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void aFileOutsideTheBaseFolderIsLeftOutWithAWarningAndNoEntryHoldsDotDot() throws Exception {
        Path inside = write("in/a.txt", new byte[]{1});
        Path outside = write("out/b.txt", new byte[]{2});
        Path target = dir.resolve("m");
        JsonObject json = config(target, "manifest");
        json.addProperty("baseFolder", dir.resolve("src/in").toString());

        action(json).runEnded(report(List.of(written(inside), written(outside))), context);

        assertEquals(List.of("a.txt"), new ArrayList<>(parseSums(Files.readString(target)).keySet()));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("outside the base folder"), warnings.get(0));
        assertFalse(Files.readString(target).contains(".."));
    }

    @Test
    void aTargetWrittenTwiceIsListedOnce() throws Exception {
        Path f = write("a.txt", new byte[]{1, 2});
        Path target = dir.resolve("m");

        action(config(target, "manifest")).runEnded(report(List.of(written(f), written(f))), context);

        assertEquals(1, Files.readAllLines(target).size());
    }

    @Test
    void mediaAreWrittenWithoutCompressionAndTheOthersCompressed() throws Exception {
        List<WrittenFile> files = new ArrayList<>();
        for (String name : new String[]{"a.JPG", "b.heic", "c.cr3", "d.mp4", "e.flac", "f.txt", "g.xmp", "h"}) {
            files.add(written(write(name, "abcabcabcabc".repeat(200).getBytes(StandardCharsets.UTF_8))));
        }
        Path target = dir.resolve("a.zip");

        action(config(target, null)).runEnded(report(files), context);

        try (ZipFile zip = new ZipFile(target.toFile())) {
            for (String name : new String[]{"a.JPG", "b.heic", "c.cr3", "d.mp4", "e.flac"}) {
                ZipEntry entry = zip.getEntry(name);
                assertEquals(ZipEntry.DEFLATED, entry.getMethod(), name);
                // level 0: stored blocks, a few bytes of framing over the size
                assertTrue(entry.getCompressedSize() >= 2400 && entry.getCompressedSize() < 2400 + 32,
                        name + " " + entry.getCompressedSize());
                assertEquals(2400, zip.getInputStream(entry).readAllBytes().length, name);
            }
            for (String name : new String[]{"f.txt", "g.xmp", "h", "SHA256SUMS"}) {
                ZipEntry entry = zip.getEntry(name);
                assertEquals(ZipEntry.DEFLATED, entry.getMethod(), name);
                assertTrue(name.equals("SHA256SUMS") || entry.getCompressedSize() < 200, name);
            }
        }
    }

    @Test
    void noFileMeansNothingProducedAndAnInformationWarning() {
        Path target = dir.resolve("a.zip");

        action(config(target, null)).runEnded(report(List.of()), context);

        assertFalse(Files.exists(target));
        assertFalse(published.containsKey("archive.path"));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("no file was written"), warnings.get(0));
    }

    @Test
    void skippedTargetsAreIncludedOnDemandWithARecomputedHash() throws Exception {
        byte[] a = "new".getBytes(StandardCharsets.UTF_8);
        byte[] s = "old".getBytes(StandardCharsets.UTF_8);
        Path fa = write("a.txt", a);
        Path fs = write("s.txt", s);
        List<RunItem> items = List.of(new RunItem("s.txt", "card", ItemOutcome.SKIPPED, fs, "exists"));

        Path without = dir.resolve("m1");
        action(config(without, "manifest")).runEnded(report("p", List.of(written(fa)), items), context);
        assertEquals(List.of("a.txt"), new ArrayList<>(parseSums(Files.readString(without)).keySet()));

        JsonObject json = config(dir.resolve("m2"), "manifest");
        json.addProperty("includeSkipped", true);
        action(json).runEnded(report("p", List.of(written(fa)), items), context);
        assertEquals(Map.of("a.txt", sha256(a), "s.txt", sha256(s)),
                parseSums(Files.readString(dir.resolve("m2"))));
    }

    @Test
    void aMissingFileIsLeftOutWithAWarning() throws Exception {
        Path f = write("a.txt", new byte[]{1});
        WrittenFile gone = new WrittenFile(dir.resolve("src/gone.txt"), "00", 1);
        Path target = dir.resolve("m");

        action(config(target, "manifest")).runEnded(report(List.of(written(f), gone)), context);

        assertEquals(List.of("a.txt"), new ArrayList<>(parseSums(Files.readString(target)).keySet()));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("gone.txt"), warnings.get(0));
    }

    @Test
    void targetVariablesAreExpanded() throws Exception {
        Path f = write("a.txt", new byte[]{1});
        RunReport report = report("my/trip:1", List.of(written(f)), List.of());
        ZonedDateTime start = report.started().atZone(ZoneId.systemDefault());
        String date = start.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String time = start.format(DateTimeFormatter.ofPattern("HHmmss"));

        action(config(dir.resolve("{date}-{time}-{pipeline}.zip"), null)).runEnded(report, context);

        assertTrue(Files.exists(dir.resolve(date + "-" + time + "-my_trip_1.zip")));
        assertEquals(dir.resolve(date + "-" + time + "-my_trip_1.zip").toString(), published.get("archive.path"));
    }

    /** An action whose reads interrupt the thread: during the read of the file given (ClosedByInterruptException). */
    private static final class Interrupting extends ArchiveZip {
        private final Path trigger;
        private final boolean closedByInterrupt;

        Interrupting(Path trigger, boolean closedByInterrupt) {
            this.trigger = trigger;
            this.closedByInterrupt = closedByInterrupt;
        }

        @Override
        InputStream open(Path file) throws IOException {
            if (!file.equals(trigger)) {
                return super.open(file);
            }
            Thread.currentThread().interrupt();
            if (closedByInterrupt) {
                throw new java.nio.channels.ClosedByInterruptException();
            }
            // the read goes on with the flag set: the action must notice it
            return new java.io.ByteArrayInputStream(new byte[]{1, 2, 3});
        }
    }

    private void assertInterruptedCleanly(IllegalStateException failure, Path outDir, Path target) {
        assertTrue(Thread.interrupted(), "the interruption stays visible to the engine");
        assertTrue(failure.getMessage().contains("interrupted"), failure.getMessage());
        assertFalse(Files.exists(target));
        assertFalse(published.containsKey("archive.path"));
        assertTrue(Files.notExists(outDir) || outDir.toFile().list().length == 0, "no temporary file left");
    }

    @Test
    void anInterruptionWhileWritingTheZipPublishesNothingAndRemovesTheTemporaryFile() throws Exception {
        Path f = write("a.txt", new byte[]{1});
        Path g = write("b.txt", new byte[]{2});
        Path out = dir.resolve("out");
        Path target = out.resolve("a.zip");
        for (boolean closed : new boolean[]{false, true}) {
            published.clear();
            ArchiveZip action = action(new Interrupting(g, closed), config(target, null));
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> action.runEnded(report(List.of(written(f), written(g))), context));
            assertInterruptedCleanly(failure, out, target);
        }
    }

    @Test
    void anInterruptionWhileHashingASkippedFileIsAnInterruptionNotAnUnreadableFile() throws Exception {
        Path f = write("a.txt", new byte[]{1});
        Path skipped = write("s.txt", new byte[]{2});
        Path out = dir.resolve("out");
        Path target = out.resolve("m");
        List<RunItem> items = List.of(new RunItem("s", "card", ItemOutcome.SKIPPED, skipped, "exists"));
        JsonObject json = config(target, "manifest");
        json.addProperty("includeSkipped", true);
        ArchiveZip action = action(new Interrupting(skipped, true), json);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> action.runEnded(report("p", List.of(written(f)), items), context));

        assertInterruptedCleanly(failure, out, target);
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void aFlagSetBeforeTheCallStopsTheActionAtOnce() throws Exception {
        Path f = write("a.txt", new byte[]{1});
        Path out = dir.resolve("out");
        Path target = out.resolve("a.zip");
        ArchiveZip action = action(config(target, null));
        Thread.currentThread().interrupt();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> action.runEnded(report(List.of(written(f))), context));

        assertInterruptedCleanly(failure, out, target);
    }

    @Test
    void anExistingTargetIsNeverOverwrittenTheArchiveTakesTheNextFreeName() throws Exception {
        Path f = write("a.txt", new byte[]{1});
        Path target = dir.resolve("out").resolve("a.zip");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "precious");
        Files.writeString(target.resolveSibling("a (1).zip"), "precious too");

        action(config(target, null)).runEnded(report(List.of(written(f))), context);

        assertEquals("precious", Files.readString(target));
        assertEquals("precious too", Files.readString(target.resolveSibling("a (1).zip")));
        Path expected = target.resolveSibling("a (2).zip");
        assertEquals(expected.toString(), published.get("archive.path"));
        try (ZipFile zip = new ZipFile(expected.toFile())) {
            assertNotNull(zip.getEntry("SHA256SUMS"));
        }
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("already exists"), warnings.get(0));
        try (var list = Files.list(target.getParent())) {
            assertEquals(3, list.count(), "no temporary file left");
        }
    }

    @Test
    void pipelineNameIsMadeSafeForAFileName() {
        assertEquals("a_b_c__", ArchiveZip.safeName("a/b:c*?"));
        assertEquals("_", ArchiveZip.safeName(".."));
        assertEquals("trip_", ArchiveZip.safeName("trip. "));
        assertEquals("_CON", ArchiveZip.safeName("CON"));
        assertEquals("_lpt1.txt", ArchiveZip.safeName("lpt1.txt"));
        assertEquals("holidays", ArchiveZip.safeName("holidays"));
    }

    @Test
    void aDotDotPipelineCannotMoveTheTarget() throws Exception {
        Path f = write("a.txt", new byte[]{1});
        Path sub = dir.resolve("out");
        RunReport report = report("..", List.of(written(f)), List.of());

        action(config(sub.resolve("{pipeline}"), "manifest")).runEnded(report, context);

        assertEquals(sub.resolve("_").toString(), published.get("archive.path"));
    }

    @Test
    void aRelativeTargetAndBaseFolderResolveAgainstThePipelineFolder() throws Exception {
        Path f = write("p/q/1.txt", new byte[]{1});
        Path pipelineFolder = dir.resolve("pipes");
        Instant start = Instant.parse("2026-10-30T10:05:09Z");
        RunReport report = new RunReport("x", pipelineFolder.resolve("x.json"), RunOutcome.SUCCESS, start, start,
                start.plusSeconds(5), new RunCounts(1, 0, 0, 0), List.of(), List.of(written(f)), List.of(),
                List.of(), null, "run-1", Map.of());
        JsonObject json = new JsonObject();
        json.addProperty("target", "out/m");
        json.addProperty("content", "manifest");
        json.addProperty("baseFolder", "../src");

        action(json).runEnded(report, context);

        Path expected = pipelineFolder.resolve("out/m");
        assertEquals(expected.toString(), published.get("archive.path"));
        assertEquals(List.of("p/q/1.txt"), new ArrayList<>(parseSums(Files.readString(expected)).keySet()));
    }

    @Test
    void aRelativeTargetWithoutAPipelineFileResolvesAgainstTheWorkingDirectory() throws Exception {
        Path f = write("a.txt", new byte[]{1});
        Instant start = Instant.parse("2026-10-30T10:05:09Z");
        RunReport report = new RunReport("x", null, RunOutcome.SUCCESS, start, start,
                start.plusSeconds(5), new RunCounts(1, 0, 0, 0), List.of(), List.of(written(f)), List.of(),
                List.of(), null, "run-1", Map.of());
        Path expected = Path.of("").toAbsolutePath().resolve("archive-test-" + System.nanoTime() + ".sums");
        JsonObject json = new JsonObject();
        json.addProperty("target", expected.getFileName().toString());
        json.addProperty("content", "manifest");

        try {
            action(json).runEnded(report, context);
            assertEquals(expected.toString(), published.get("archive.path"));
        } finally {
            Files.deleteIfExists(expected);
        }
    }

    @Test
    void aRealFileNamedLikeTheManifestIsLeftOutWithAWarning() throws Exception {
        Path real = write("SHA256SUMS", "mine".getBytes(StandardCharsets.UTF_8));
        Path other = write("a.txt", new byte[]{1});
        Path target = dir.resolve("a.zip");

        action(config(target, null)).runEnded(report(List.of(written(real), written(other))), context);

        try (ZipFile zip = new ZipFile(target.toFile())) {
            assertEquals(2, zip.size());
            assertEquals(Map.of("a.txt", sha256(new byte[]{1})), parseSums(text(zip, "SHA256SUMS")));
        }
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("like the manifest"), warnings.get(0));
    }

    @Test
    void defaultTimeoutIsNoLimitAndTheConfigToStringIsHarmless() {
        ArchiveZip action = action(config(dir.resolve("a.zip"), null));

        assertEquals(Duration.ZERO, action.defaultTimeout());
        assertTrue(new ArchiveConfig("t", "zip", false, null).toString().contains("target=t"));
    }

    @Test
    void aMissingTargetOrAnUnknownContentIsRejected() {
        JsonObject noTarget = new JsonObject();
        noTarget.addProperty("target", " ");
        assertThrows(IllegalArgumentException.class, () -> action(noTarget).runEnded(report(List.of()), context));
        assertThrows(IllegalArgumentException.class,
                () -> action(config(dir.resolve("a"), "tar")).runEnded(report(List.of()), context));
    }
}

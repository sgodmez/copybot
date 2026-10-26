package com.copybot.plugin.metadataextractor.actions;

import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import com.copybot.plugin.api.action.WriteContext;
import com.copybot.plugin.embedded.actions.FileWriteAction;
import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;
import com.drew.metadata.exif.ExifDirectoryBase;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.ExifThumbnailDirectory;
import com.drew.metadata.exif.GpsDirectory;
import com.drew.metadata.exif.makernotes.SonyType1MakernoteDirectory;
import com.google.gson.JsonParser;
import org.apache.commons.imaging.bytesource.ByteSource;
import org.apache.commons.imaging.formats.jpeg.JpegImageParser;
import org.apache.commons.imaging.formats.tiff.TiffDirectory;
import org.apache.commons.imaging.formats.tiff.TiffImageMetadata;
import org.apache.commons.imaging.formats.tiff.constants.TiffDirectoryConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.copybot.plugin.metadataextractor.actions.TestJpegs.*;
import static org.junit.jupiter.api.Assertions.*;

public class WriteDateTest {

    private static final ZoneId MEXICO = ZoneId.of("America/Mexico_City");
    /** 01:30 on the 25th at +02:00 (the camera), shot in Mexico: 17:30 on the 24th at -06:00. */
    private static final Instant SHOT = Instant.parse("2026-10-24T23:30:00Z");
    private static final ZonedDateTime LOCAL = SHOT.atZone(MEXICO);

    private static final String REWRITTEN = "EXIF r\u00e9\u00e9crit : 2026-10-24 17:30 -06:00";
    private static final String UNSUPPORTED = "EXIF non r\u00e9\u00e9crit : format non pris en charge";
    private static final String NO_DATE = "EXIF non r\u00e9\u00e9crit : pas de date EXIF";
    private static final String UNCHANGED = "inchang\u00e9 : fuseau horaire non corrig\u00e9";

    /**
     * A camera JPEG: dates at +02:00 (DateTime a minute later), sub-seconds, make, model, a Sony maker note, an
     * empty user comment, a GPS IFD (whose version, tag 0, Commons Imaging does not read) and a thumbnail in IFD1.
     */
    private static byte[] cameraJpeg() {
        return cameraJpeg(ByteOrder.LITTLE_ENDIAN);
    }

    /** As cameraJpeg(), its TIFF in this byte order ("MM" for big-endian, as some cameras write it). */
    private static byte[] cameraJpeg(ByteOrder order) {
        return jpeg(tiff(order,
                List.of(ascii(TAG_MAKE, "SONY"), ascii(TAG_MODEL, "ILCE-7M3"), ascii(TAG_DATETIME, "2026:10:25 01:31:00")),
                List.of(ascii(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL, "2026:10:25 01:30:00"),
                        ascii(ExifSubIFDDirectory.TAG_DATETIME_DIGITIZED, "2026:10:25 01:30:00"),
                        ascii(ExifSubIFDDirectory.TAG_TIME_ZONE, "+02:00"),
                        ascii(ExifSubIFDDirectory.TAG_TIME_ZONE_ORIGINAL, "+02:00"),
                        ascii(ExifSubIFDDirectory.TAG_TIME_ZONE_DIGITIZED, "+02:00"),
                        sonyMakerNote("Standard"),
                        undefined(TAG_USER_COMMENT, new byte[64]), // as a Sony camera writes it: no charset, zeros
                        ascii(ExifSubIFDDirectory.TAG_SUBSECOND_TIME_ORIGINAL, "123")),
                List.of(bytes(TAG_GPS_VERSION, (byte) 2, (byte) 3, (byte) 0, (byte) 0), ascii(TAG_GPS_MAP_DATUM, "WGS-84")),
                THUMBNAIL));
    }

    private static WorkItem item(byte[] content) throws IOException {
        return new WorkItem(URI.create("file:///card/DSC00001.JPG").toURL(), () -> new ByteArrayInputStream(content));
    }

    /** An item corrected to Mexico in the plan, its capture date set by the analysis. */
    private static WorkItem corrected(byte[] content) throws IOException {
        WorkItem item = item(content);
        item.getMetadatas().raw().put(WorkItemMetadata.CAPTURE_ZONE, MEXICO);
        item.getMetadatas().setTime(WorkItemMetadata.CAPTURE_DATE, LOCAL);
        return item;
    }

    private static byte[] content(WorkItem item) throws IOException {
        try (InputStream in = item.openInputStream()) {
            return in.readAllBytes();
        }
    }

    private static Metadata read(byte[] jpeg) throws IOException, ImageProcessingException {
        return ImageMetadataReader.readMetadata(new ByteArrayInputStream(jpeg));
    }

    private static Object note(WorkItem item) {
        return item.getMetadatas().raw().get(WorkItemMetadata.NOTE);
    }

    private static final Set<Integer> DATE_TAGS = Set.of(ExifDirectoryBase.TAG_DATETIME,
            ExifDirectoryBase.TAG_DATETIME_ORIGINAL, ExifDirectoryBase.TAG_DATETIME_DIGITIZED, ExifDirectoryBase.TAG_TIME_ZONE,
            ExifDirectoryBase.TAG_TIME_ZONE_ORIGINAL, ExifDirectoryBase.TAG_TIME_ZONE_DIGITIZED);

    @Test
    public void rewritesTheDateOfACorrectedJpeg() throws Exception {
        rewritesTheDate(ByteOrder.LITTLE_ENDIAN);
    }

    @Test
    public void rewritesTheDateOfABigEndianJpeg() throws Exception {
        byte[] motorola = cameraJpeg(ByteOrder.BIG_ENDIAN);
        assertEquals("MM", new String(motorola, 12, 2, StandardCharsets.US_ASCII), "the fixture is big-endian");

        rewritesTheDate(ByteOrder.BIG_ENDIAN);
        keepsTheImageAndTheOtherTags(motorola);
    }

    private static void rewritesTheDate(ByteOrder order) throws Exception {
        WorkItem photo = corrected(cameraJpeg(order));

        List<WorkItem> produced = new WriteDate().doProcess(photo);

        assertEquals(1, produced.size());
        WorkItem copy = produced.getFirst();
        byte[] rewritten = content(copy);
        assertEquals(Long.valueOf(rewritten.length), copy.getMetadatas().getSize());
        assertNull(note(copy));
        ExifSubIFDDirectory exif = read(rewritten).getFirstDirectoryOfType(ExifSubIFDDirectory.class);
        assertEquals("2026:10:24 17:30:00", exif.getString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL));
        assertEquals("-06:00", exif.getString(ExifSubIFDDirectory.TAG_TIME_ZONE_ORIGINAL));
        assertEquals("2026:10:24 17:30:00", exif.getString(ExifSubIFDDirectory.TAG_DATETIME_DIGITIZED));
        assertEquals("-06:00", exif.getString(ExifSubIFDDirectory.TAG_TIME_ZONE_DIGITIZED));
        assertEquals("-06:00", exif.getString(ExifSubIFDDirectory.TAG_TIME_ZONE));
        assertEquals("123", exif.getString(ExifSubIFDDirectory.TAG_SUBSECOND_TIME_ORIGINAL));
        // the modification date, a minute after the shot, is moved by the same amount
        assertEquals("2026:10:24 17:31:00", read(rewritten).getFirstDirectoryOfType(ExifIFD0Directory.class)
                .getString(ExifIFD0Directory.TAG_DATETIME));
        // the original content is unchanged
        assertArrayEquals(cameraJpeg(order), content(photo));
    }

    @Test
    public void rewritesOnlyTheDatesPresent() throws Exception {
        byte[] rewritten = WriteDate.rewrite(jpegWithDateOriginal("2026:10:25 01:30:00"), LOCAL).orElseThrow();

        Metadata metadata = read(rewritten);
        ExifSubIFDDirectory exif = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
        assertEquals("2026:10:24 17:30:00", exif.getString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL));
        assertEquals("-06:00", exif.getString(ExifSubIFDDirectory.TAG_TIME_ZONE_ORIGINAL));
        assertFalse(exif.containsTag(ExifSubIFDDirectory.TAG_DATETIME_DIGITIZED));
        assertFalse(exif.containsTag(ExifSubIFDDirectory.TAG_TIME_ZONE));
        ExifIFD0Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
        assertTrue(ifd0 == null || !ifd0.containsTag(ExifIFD0Directory.TAG_DATETIME));
    }

    @Test
    public void keepsTheImageAndTheOtherTags() throws Exception {
        keepsTheImageAndTheOtherTags(cameraJpeg());
    }

    private static void keepsTheImageAndTheOtherTags(byte[] original) throws Exception {
        byte[] rewritten = WriteDate.rewrite(original, LOCAL).orElseThrow();

        assertTrue(WriteDate.sameOutsideExif(original, rewritten));
        Metadata before = read(original);
        Metadata after = read(rewritten);
        // the fixture holds what it should, so that comparing proves something
        assertEquals("ILCE-7M3", before.getFirstDirectoryOfType(ExifIFD0Directory.class).getString(ExifIFD0Directory.TAG_MODEL));
        assertEquals("Standard", before.getFirstDirectoryOfType(SonyType1MakernoteDirectory.class)
                .getString(SonyType1MakernoteDirectory.TAG_COLOR_MODE_SETTING));
        assertEquals("2.300", before.getFirstDirectoryOfType(GpsDirectory.class).getDescription(GpsDirectory.TAG_VERSION_ID));
        assertTrue(before.getFirstDirectoryOfType(ExifSubIFDDirectory.class).containsTag(TAG_USER_COMMENT));
        // the thumbnail is checked apart (keepsTheThumbnailWhereMetadataExtractorNoLongerSeesIt)
        List<Directory> kept = butThumbnail(before);
        assertEquals(kept.size(), butThumbnail(after).size(), () -> after.getDirectories().toString());
        for (Directory directory : kept) {
            assertEquals(tagsButDates(directory), tagsButDates(after.getFirstDirectoryOfType(directory.getClass())),
                    directory.getName());
        }
        assertFalse(after.hasErrors(), () -> after.getDirectories().toString());
    }

    /**
     * Accepted limit (spec 6): the lossless writer of Commons Imaging may place IFD1 before IFD0, a backward link
     * that metadata-extractor does not follow, so it may no longer list the thumbnail. The thumbnail is still in
     * the copy, whole, where its offset points: a reader following the TIFF links (Commons Imaging) finds it.
     */
    @Test
    public void keepsTheThumbnailWhereMetadataExtractorNoLongerSeesIt() throws Exception {
        byte[] original = cameraJpeg();
        assertNotNull(read(original).getFirstDirectoryOfType(ExifThumbnailDirectory.class), "the fixture has a thumbnail");

        byte[] rewritten = WriteDate.rewrite(original, LOCAL).orElseThrow();

        TiffImageMetadata exif = new JpegImageParser().getExifMetadata(ByteSource.array(rewritten), null);
        TiffDirectory ifd1 = exif.findDirectory(TiffDirectoryConstants.DIRECTORY_TYPE_DIR_1);
        assertNotNull(ifd1, "IFD1 is still linked from IFD0");
        assertArrayEquals(THUMBNAIL, ifd1.getJpegImageData().getData());
    }

    private static List<Directory> butThumbnail(Metadata metadata) {
        List<Directory> directories = new ArrayList<>();
        metadata.getDirectories().forEach(directories::add);
        directories.removeIf(directory -> directory instanceof ExifThumbnailDirectory);
        return directories;
    }

    /** The tags of {@code directory}, by name, but the dates and offsets the rewrite sets. */
    private static Map<String, String> tagsButDates(Directory directory) {
        Map<String, String> tags = new LinkedHashMap<>();
        for (Tag tag : directory.getTags()) {
            if (!(directory instanceof ExifDirectoryBase) || !DATE_TAGS.contains(tag.getTagType())) {
                tags.put(tag.getTagName(), directory.getString(tag.getTagType()));
            }
        }
        return tags;
    }

    @Test
    public void leavesAnUncorrectedJpegUntouched() throws IOException {
        WorkItem photo = item(cameraJpeg());
        photo.getMetadatas().setTime(WorkItemMetadata.CAPTURE_DATE, LOCAL);

        List<WorkItem> produced = new WriteDate().doProcess(photo);

        assertEquals(1, produced.size());
        assertSame(photo, produced.getFirst());
        assertNull(note(photo));
    }

    @Test
    public void leavesANonJpegUntouched() throws IOException {
        byte[] raw = "II*\0 a raw file named .JPG".getBytes(StandardCharsets.US_ASCII);
        WorkItem file = corrected(raw);

        List<WorkItem> produced = new WriteDate().doProcess(file);

        assertSame(file, produced.getFirst());
        assertArrayEquals(raw, content(produced.getFirst()));
        assertEquals(UNSUPPORTED, note(file));
    }

    @Test
    public void leavesAJpegWithoutDateTimeOriginalUntouched() throws Exception {
        byte[] noDate = jpeg(tiff(List.of(ascii(TAG_MAKE, "SONY")), List.of()));
        WorkItem photo = corrected(noDate);

        List<WorkItem> produced = new WriteDate().doProcess(photo);

        assertSame(photo, produced.getFirst());
        assertEquals(NO_DATE, note(photo));
        assertEquals(Optional.empty(), WriteDate.rewrite(noDate, LOCAL));
        assertEquals(Optional.empty(), WriteDate.rewrite(jpegWithoutExif(), LOCAL));
    }

    @Test
    public void copiesAsIsWhenTheExifIsBroken() throws IOException {
        // the EXIF segment ends in the middle of IFD0
        byte[] whole = tiff(List.of(ascii(TAG_MAKE, "SONY")),
                List.of(ascii(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL, "2026:10:25 01:30:00")));
        byte[] broken = jpeg(Arrays.copyOf(whole, 16));
        WorkItem photo = corrected(broken);

        List<WorkItem> produced = new WriteDate().doProcess(photo);

        assertSame(photo, produced.getFirst());
        assertArrayEquals(broken, content(produced.getFirst()));
        String note = (String) note(photo);
        assertTrue(note.startsWith("EXIF non r\u00e9\u00e9crit : "), note);
        assertNotEquals(NO_DATE, note);
    }

    @Test
    public void rewritingTwiceGivesTheSameBytes() throws Exception {
        // a second copy of the same corrected original: no false conflict with the first one (spec, interaction with the copy)
        WorkItem first = new WriteDate().doProcess(corrected(cameraJpeg())).getFirst();
        WorkItem second = new WriteDate().doProcess(corrected(cameraJpeg())).getFirst();

        assertArrayEquals(content(first), content(second));
    }

    /**
     * The rewritten copy is not kept in memory (a plan keeps every item until its end): each open of the copy reads
     * the original again and rewrites it, to the same bytes.
     */
    @Test
    public void theCopyIsRewrittenFromTheOriginalAtEachOpen() throws Exception {
        byte[] original = cameraJpeg();
        AtomicInteger opens = new AtomicInteger();
        WorkItem photo = new WorkItem(URI.create("file:///card/DSC00001.JPG").toURL(), () -> {
            opens.incrementAndGet();
            return new ByteArrayInputStream(original);
        });
        photo.getMetadatas().raw().put(WorkItemMetadata.CAPTURE_ZONE, MEXICO);
        photo.getMetadatas().setTime(WorkItemMetadata.CAPTURE_DATE, LOCAL);

        WorkItem copy = new WriteDate().doProcess(photo).getFirst();
        assertEquals(1, opens.get(), "read once to know the rewritten size");

        byte[] first = content(copy);
        byte[] second = content(copy);

        assertEquals(3, opens.get(), "each open of the copy reads the original again");
        assertArrayEquals(first, second);
        assertArrayEquals(WriteDate.rewrite(original, LOCAL).orElseThrow(), first);
        assertEquals(Long.valueOf(first.length), copy.getMetadatas().getSize());
    }

    /** The original changed after the step (its EXIF now broken): opening the copy fails, the write fails the item. */
    @Test
    public void aRewriteThatFailsAtReopenIsAnIOExceptionOfTheStream() throws Exception {
        byte[] whole = tiff(List.of(ascii(TAG_MAKE, "SONY")),
                List.of(ascii(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL, "2026:10:25 01:30:00")));
        byte[][] current = {cameraJpeg()};
        WorkItem photo = new WorkItem(URI.create("file:///card/DSC00001.JPG").toURL(),
                () -> new ByteArrayInputStream(current[0]));
        photo.getMetadatas().raw().put(WorkItemMetadata.CAPTURE_ZONE, MEXICO);
        photo.getMetadatas().setTime(WorkItemMetadata.CAPTURE_DATE, LOCAL);
        WorkItem copy = new WriteDate().doProcess(photo).getFirst();
        assertNotSame(photo, copy);

        current[0] = jpeg(Arrays.copyOf(whole, 16));

        assertThrows(IOException.class, () -> content(copy));
    }

    @Test
    public void copiesAsIsWhenTheCheckAfterTheRewriteFails() throws Exception {
        byte[] original = cameraJpeg();
        WorkItem photo = corrected(original);
        WriteDate failing = new WriteDate((before, after, local) -> {
            throw new IOException("forced");
        });

        List<WorkItem> produced = failing.doProcess(photo);

        assertSame(photo, produced.getFirst());
        assertArrayEquals(original, content(produced.getFirst()));
        assertEquals("EXIF non réécrit : forced", note(photo));
    }

    @Test
    public void theCheckAcceptsTheRewriteAndRefusesAChangedImageOrDate() throws Exception {
        byte[] original = cameraJpeg();
        byte[] rewritten = WriteDate.rewrite(original, LOCAL).orElseThrow();

        WriteDate.check(original, rewritten, LOCAL); // the normal path passes it

        byte[] changedImage = rewritten.clone();
        changedImage[changedImage.length - 3] ^= 1; // in the scan data
        IOException image = assertThrows(IOException.class, () -> WriteDate.check(original, changedImage, LOCAL));
        assertTrue(image.getMessage().contains("hors EXIF"), image.getMessage());
        IOException date = assertThrows(IOException.class, () -> WriteDate.check(original, rewritten, LOCAL.plusHours(1)));
        assertTrue(date.getMessage().contains("2026:10:24 17:30:00"), date.getMessage());
        IOException unreadable = assertThrows(IOException.class,
                () -> WriteDate.check(original, jpeg(Arrays.copyOf(rewritten, 40)), LOCAL));
        assertTrue(unreadable.getMessage().startsWith("vérification"), unreadable.getMessage());
    }

    @Test
    public void sameOutsideExifIgnoresOnlyTheExifSegment() {
        byte[] original = cameraJpeg();
        byte[] otherExif = jpeg(tiff(List.of(ascii(TAG_MAKE, "OTHER")), List.of()));

        assertTrue(WriteDate.sameOutsideExif(original, otherExif));
        assertTrue(WriteDate.sameOutsideExif(original, jpegWithoutExif()));
        byte[] changed = original.clone();
        changed[changed.length - 3] ^= 1;
        assertFalse(WriteDate.sameOutsideExif(original, changed));
        assertFalse(WriteDate.sameOutsideExif(original, Arrays.copyOf(original, original.length - 1)));
        assertFalse(WriteDate.sameOutsideExif(original, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}));
    }

    @Test
    public void dryRunNotesTheVerdictWithoutReadingTheExif() throws IOException {
        // only the signature can be read: the EXIF is never parsed in a dry run
        WorkItem photo = new WorkItem(URI.create("file:///card/DSC00001.JPG").toURL(), () -> new InputStream() {
            private int read;

            @Override
            public int read() throws IOException {
                if (read == 2) {
                    throw new UncheckedIOException(new IOException("the dry run reads past the signature"));
                }
                return read++ == 0 ? 0xFF : 0xD8;
            }
        });
        photo.getMetadatas().raw().put(WorkItemMetadata.CAPTURE_ZONE, MEXICO);
        photo.getMetadatas().setTime(WorkItemMetadata.CAPTURE_DATE, LOCAL);

        Optional<List<WorkItem>> projected = new WriteDate().dryRun(photo);

        assertEquals(Optional.of(List.of(photo)), projected);
        assertEquals(REWRITTEN, note(photo));

        WorkItem raw = corrected("II*\0".getBytes(StandardCharsets.US_ASCII));
        assertEquals(Optional.of(List.of(raw)), new WriteDate().dryRun(raw));
        assertEquals(UNSUPPORTED, note(raw));

        WorkItem undated = item(cameraJpeg());
        undated.getMetadatas().raw().put(WorkItemMetadata.CAPTURE_ZONE, MEXICO);
        new WriteDate().dryRun(undated);
        assertEquals(NO_DATE, note(undated));

        // the plan says so: a step listed for a file it leaves alone must not look like a processing
        WorkItem uncorrected = item(cameraJpeg());
        assertEquals(Optional.of(List.of(uncorrected)), new WriteDate().dryRun(uncorrected));
        assertEquals(UNCHANGED, note(uncorrected));
    }

    @Test
    public void endToEndTheCopyCarriesTheDate(@TempDir Path dir) throws Exception {
        Path source = Files.write(dir.resolve("DSC00001.JPG"), jpegWithDateOriginal("2026:10:25 01:30:00", "+02:00"));
        Path target = dir.resolve("out").resolve("DSC00001.JPG");
        WorkItem photo = new WorkItem(source);
        photo.getMetadatas().setSize(Files.size(source));
        photo.getMetadatas().raw().put(WorkItemMetadata.CAPTURE_ZONE, MEXICO);
        new ExtractMetadata().doAnalyze(photo);
        FileWriteAction write = new FileWriteAction();
        write.loadConfig(JsonParser.parseString("{\"outPattern\":\"" + target.toString().replace("\\", "\\\\")
                + "\",\"verify\":\"size\",\"deleteSource\":true}"));

        List<WorkItem> produced = new WriteDate().doProcess(photo);
        write.write(produced.getFirst(), WriteContext.newRun());

        ExifSubIFDDirectory exif = read(Files.readAllBytes(target)).getFirstDirectoryOfType(ExifSubIFDDirectory.class);
        assertEquals("2026:10:24 17:30:00", exif.getString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL));
        assertEquals("-06:00", exif.getString(ExifSubIFDDirectory.TAG_TIME_ZONE_ORIGINAL));
        assertFalse(Files.exists(source), "the source is deleted once its rewritten copy is verified");
    }
}

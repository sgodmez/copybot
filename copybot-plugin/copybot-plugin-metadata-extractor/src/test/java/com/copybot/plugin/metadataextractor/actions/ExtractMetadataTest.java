package com.copybot.plugin.metadataextractor.actions;

import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.mov.QuickTimeDirectory;
import com.drew.metadata.mov.metadata.QuickTimeMetadataDirectory;
import com.drew.metadata.mp4.Mp4Directory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

public class ExtractMetadataTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final Supplier<Optional<ZoneOffset>> NO_SIDECAR = Optional::empty;

    private static final ZoneId MEXICO = ZoneId.of("America/Mexico_City");

    private static Optional<ZonedDateTime> date(Metadata metadata, ZoneId localZone, Supplier<Optional<ZoneOffset>> sidecarOffset) {
        return ExtractMetadata.captureDate(metadata, localZone, sidecarOffset).map(ExtractMetadata.Capture::date);
    }

    // ---- correction: the capture zone and the camera clock ----

    @Test
    public void correctedShowsARecordedOffsetInThePlaceZone() {
        // Sony without daylight saving time, offset noted +01:00, July, place Paris -> 14:00 in Paris
        assertEquals(ZonedDateTime.parse("2026-07-10T14:00:00+02:00[Europe/Paris]"),
                ExtractMetadata.corrected(new ExtractMetadata.Capture(ZonedDateTime.parse("2026-07-10T13:00:00+01:00"), false), PARIS, null));
    }

    @Test
    public void correctedReadsAnUnknownClockInTheCameraClock() {
        assertEquals(ZonedDateTime.parse("2026-07-10T14:00:00+02:00[Europe/Paris]"),
                ExtractMetadata.corrected(new ExtractMetadata.Capture(ZonedDateTime.parse("2026-07-10T13:00:00+02:00[Europe/Paris]"), true),
                        PARIS, ZoneOffset.ofHours(1)));
    }

    @Test
    public void correctedMovesAParisClockToMexico() {
        assertEquals(ZonedDateTime.parse("2026-10-25T13:00:00-06:00[America/Mexico_City]"),
                ExtractMetadata.corrected(new ExtractMetadata.Capture(ZonedDateTime.parse("2026-10-25T20:00:00+01:00[Europe/Paris]"), true),
                        MEXICO, PARIS));
    }

    @Test
    public void correctedReadsAnUnknownClockInThePlaceByDefault() {
        assertEquals(ZonedDateTime.parse("2026-10-25T20:00:00-06:00[America/Mexico_City]"),
                ExtractMetadata.corrected(new ExtractMetadata.Capture(ZonedDateTime.parse("2026-10-25T20:00:00+01:00[Europe/Paris]"), true),
                        MEXICO, null));
    }

    @Test
    public void correctedSurvivesADstGap() {
        // 02:30 does not exist in Paris on 2026-03-29: moved forward by the gap
        assertEquals(ZonedDateTime.parse("2026-03-29T03:30:00+02:00[Europe/Paris]"), ExtractMetadata.corrected(
                new ExtractMetadata.Capture(ZonedDateTime.parse("2026-03-29T02:30:00+01:00"), true), PARIS, PARIS));
    }

    @Test
    public void captureTellsWhetherTheClockIsUnknown() {
        Metadata photo = new Metadata();
        ExifSubIFDDirectory exif = new ExifSubIFDDirectory();
        exif.setString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL, "2026:09:28 17:42:10");
        photo.addDirectory(exif);
        Metadata withOffset = new Metadata();
        ExifSubIFDDirectory exifWithOffset = new ExifSubIFDDirectory();
        exifWithOffset.setString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL, "2026:09:28 17:42:10");
        exifWithOffset.setString(ExifSubIFDDirectory.TAG_TIME_ZONE_ORIGINAL, "+01:00");
        withOffset.addDirectory(exifWithOffset);
        Metadata video = new Metadata();
        QuickTimeDirectory qt = new QuickTimeDirectory();
        qt.setDate(QuickTimeDirectory.TAG_CREATION_TIME, Date.from(Instant.parse("2026-09-28T15:00:00Z")));
        video.addDirectory(qt);

        assertTrue(ExtractMetadata.captureDate(photo, PARIS, NO_SIDECAR).orElseThrow().clockUnknown());
        assertFalse(ExtractMetadata.captureDate(withOffset, PARIS, NO_SIDECAR).orElseThrow().clockUnknown());
        assertFalse(ExtractMetadata.captureDate(video, PARIS, NO_SIDECAR).orElseThrow().clockUnknown());
    }

    @Test
    public void analyzeTellsWhetherTheClockIsUnknown() throws IOException {
        byte[] without = jpegWithDateOriginal("2026:10:25 23:30:00");
        byte[] with = jpegWithDateOriginal("2026:10:25 23:30:00", "+01:00");
        WorkItem unknown = item(() -> new ByteArrayInputStream(without));
        WorkItem known = item(() -> new ByteArrayInputStream(with));

        new ExtractMetadata().doAnalyze(unknown);
        new ExtractMetadata().doAnalyze(known);

        assertEquals(Boolean.TRUE, unknown.getMetadatas().raw().get(WorkItemMetadata.CAPTURE_CLOCK_UNKNOWN));
        assertEquals(Boolean.FALSE, known.getMetadatas().raw().get(WorkItemMetadata.CAPTURE_CLOCK_UNKNOWN));
    }

    @Test
    public void analyzeAppliesTheCaptureZone() throws IOException {
        // recorded +02:00: 01:30 on the 25th is 23:30Z on the 24th, 17:30 on the 24th in Mexico
        byte[] jpeg = jpegWithDateOriginal("2026:10:25 01:30:00", "+02:00");
        WorkItem photo = item(() -> new ByteArrayInputStream(jpeg));
        photo.getMetadatas().raw().put(WorkItemMetadata.CAPTURE_ZONE, MEXICO);

        new ExtractMetadata().doAnalyze(photo);

        assertEquals("24", photo.getMetadatas().display().get("captureDate.D"));
        assertEquals("10", photo.getMetadatas().display().get("captureDate.m"));
        assertEquals(Optional.of(Instant.parse("2026-10-24T23:30:00Z")), photo.getMetadatas().getTime(WorkItemMetadata.CAPTURE_DATE));
    }

    @Test
    public void analyzeReadsAnUnknownClockInTheCameraClock() throws IOException {
        // no offset, camera on Paris time (still +02:00 at 01:30): 23:30Z on the 24th, 17:30 on the 24th in Mexico
        byte[] jpeg = jpegWithDateOriginal("2026:10:25 01:30:00");
        WorkItem photo = item(() -> new ByteArrayInputStream(jpeg));
        photo.getMetadatas().raw().put(WorkItemMetadata.CAPTURE_ZONE, MEXICO);
        photo.getMetadatas().raw().put(WorkItemMetadata.CAMERA_CLOCK, PARIS);

        new ExtractMetadata().doAnalyze(photo);

        assertEquals("24", photo.getMetadatas().display().get("captureDate.D"));
        assertEquals(Optional.of(Instant.parse("2026-10-24T23:30:00Z")), photo.getMetadatas().getTime(WorkItemMetadata.CAPTURE_DATE));
    }

    @Test
    public void exifOriginalDateIsReadAsLocalTime() {
        Metadata metadata = new Metadata();
        ExifSubIFDDirectory exif = new ExifSubIFDDirectory();
        exif.setString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL, "2026:09:28 17:42:10");
        metadata.addDirectory(exif);

        assertEquals(Optional.of(ZonedDateTime.parse("2026-09-28T17:42:10+02:00[Europe/Paris]")), date(metadata, PARIS, NO_SIDECAR));
    }

    @Test
    public void exifOffsetKeepsTheCameraWallTimeWhateverTheLocalZone() {
        // shot abroad with the camera set to -06:00: filed on the day the camera showed, not in Paris time
        Metadata metadata = new Metadata();
        ExifSubIFDDirectory exif = new ExifSubIFDDirectory();
        exif.setString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL, "2026:10:25 23:30:00");
        exif.setString(ExifSubIFDDirectory.TAG_TIME_ZONE_ORIGINAL, "-06:00");
        metadata.addDirectory(exif);

        assertEquals(Optional.of(ZonedDateTime.parse("2026-10-25T23:30:00-06:00")), date(metadata, PARIS, NO_SIDECAR));
    }

    @Test
    public void unreadableExifOffsetFallsBackToTheLocalZone() {
        Metadata metadata = new Metadata();
        ExifSubIFDDirectory exif = new ExifSubIFDDirectory();
        exif.setString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL, "2026:09:28 17:42:10");
        exif.setString(ExifSubIFDDirectory.TAG_TIME_ZONE_ORIGINAL, "   ");
        metadata.addDirectory(exif);

        assertEquals(Optional.of(ZonedDateTime.parse("2026-09-28T17:42:10+02:00[Europe/Paris]")), date(metadata, PARIS, NO_SIDECAR));
    }

    @Test
    public void quickTimeCreationDateIsUsedForVideos() {
        Metadata metadata = new Metadata();
        QuickTimeDirectory qt = new QuickTimeDirectory();
        qt.setDate(QuickTimeDirectory.TAG_CREATION_TIME, Date.from(Instant.parse("2026-09-28T15:00:00Z")));
        metadata.addDirectory(qt);

        assertEquals(Optional.of(ZonedDateTime.parse("2026-09-28T17:00:00+02:00[Europe/Paris]")), date(metadata, PARIS, NO_SIDECAR));
    }

    @Test
    public void videoDateWithAnOffsetInTheFileIsShownInThatOffset() {
        // the UTC creation time stays the reference, the camera offset comes from the creationdate key
        Metadata metadata = new Metadata();
        QuickTimeDirectory qt = new QuickTimeDirectory();
        qt.setDate(QuickTimeDirectory.TAG_CREATION_TIME, Date.from(Instant.parse("2026-10-26T05:30:00Z")));
        metadata.addDirectory(qt);
        QuickTimeMetadataDirectory keys = new QuickTimeMetadataDirectory();
        keys.setString(QuickTimeMetadataDirectory.TAG_CREATION_DATE, "2026-10-25T23:30:00-0600");
        metadata.addDirectory(keys);

        assertEquals(Optional.of(ZonedDateTime.parse("2026-10-25T23:30:00-06:00")),
                date(metadata, PARIS, () -> Optional.of(ZoneOffset.ofHours(9))));
    }

    @Test
    public void videoDateWithoutOffsetTakesTheSidecarOne() {
        Metadata metadata = new Metadata();
        Mp4Directory mp4 = new Mp4Directory();
        mp4.setDate(Mp4Directory.TAG_CREATION_TIME, Date.from(Instant.parse("2026-10-26T05:30:00Z")));
        metadata.addDirectory(mp4);

        assertEquals(Optional.of(ZonedDateTime.parse("2026-10-25T23:30:00-06:00")),
                date(metadata, PARIS, () -> Optional.of(ZoneOffset.ofHours(-6))));
    }

    @Test
    public void unreadableVideoOffsetFallsBackToTheSidecarOne() {
        Metadata metadata = new Metadata();
        QuickTimeDirectory qt = new QuickTimeDirectory();
        qt.setDate(QuickTimeDirectory.TAG_CREATION_TIME, Date.from(Instant.parse("2026-10-26T05:30:00Z")));
        metadata.addDirectory(qt);
        QuickTimeMetadataDirectory keys = new QuickTimeMetadataDirectory();
        keys.setString(QuickTimeMetadataDirectory.TAG_CREATION_DATE, "yesterday");
        metadata.addDirectory(keys);

        assertEquals(Optional.of(ZonedDateTime.parse("2026-10-25T23:30:00-06:00")),
                date(metadata, PARIS, () -> Optional.of(ZoneOffset.ofHours(-6))));
    }

    @Test
    public void photoDateDoesNotReadTheSidecar() {
        Metadata metadata = new Metadata();
        ExifSubIFDDirectory exif = new ExifSubIFDDirectory();
        exif.setString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL, "2026:09:28 17:42:10");
        metadata.addDirectory(exif);

        assertEquals(Optional.of(ZonedDateTime.parse("2026-09-28T17:42:10+02:00[Europe/Paris]")),
                date(metadata, PARIS, () -> fail("the sidecar of a photo must not be read")));
    }

    // ---- Sony sidecar: C0099.MP4 -> C0099M01.XML ----

    private static final String SONY_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <NonRealTimeMeta xmlns="urn:schemas-professionalDisc:nonRealTimeMeta:ver.2.00" lastUpdate="2026-10-18T10:32:15+02:00">
            \t<Duration value="504"/>
            \t<CreationDate value="2026-10-18T10:32:15+02:00"/>
            \t<Device manufacturer="Sony" modelName="DSC-RX10M4" serialNo="4294967295"/>
            </NonRealTimeMeta>
            """;

    @Test
    public void sonySidecarGivesTheCameraOffset(@TempDir Path dir) throws IOException {
        Path video = Files.createFile(dir.resolve("C0099.MP4"));
        Files.writeString(dir.resolve("C0099M01.XML"), SONY_XML);

        assertEquals(Optional.of(ZoneOffset.ofHours(2)), ExtractMetadata.sonySidecarOffset(video));
    }

    @Test
    public void missingOrUnreadableSonySidecarGivesNothing(@TempDir Path dir) throws IOException {
        Path alone = Files.createFile(dir.resolve("C0001.MP4"));
        Path broken = Files.createFile(dir.resolve("C0002.MP4"));
        Files.writeString(dir.resolve("C0002M01.XML"), "<NonRealTimeMeta><CreationDate value=\"soon\"/></NonRealTimeMeta>");

        assertTrue(ExtractMetadata.sonySidecarOffset(alone).isEmpty());
        assertTrue(ExtractMetadata.sonySidecarOffset(broken).isEmpty());
    }

    @Test
    public void unsetQuickTimeCreationDateIsIgnored() {
        // a creation time of 0 decodes to the QuickTime epoch: such a file would sort before any cursor
        Metadata metadata = new Metadata();
        QuickTimeDirectory qt = new QuickTimeDirectory();
        qt.setDate(QuickTimeDirectory.TAG_CREATION_TIME, Date.from(Instant.parse("1904-01-01T00:00:00Z")));
        metadata.addDirectory(qt);

        assertTrue(date(metadata, PARIS, NO_SIDECAR).isEmpty());
    }

    @Test
    public void videoDateAtTheUnixEpochIsIgnoredAndMp4IsTriedNext() {
        Metadata metadata = new Metadata();
        QuickTimeDirectory qt = new QuickTimeDirectory();
        qt.setDate(QuickTimeDirectory.TAG_CREATION_TIME, Date.from(Instant.EPOCH));
        metadata.addDirectory(qt);
        Mp4Directory mp4 = new Mp4Directory();
        mp4.setDate(Mp4Directory.TAG_CREATION_TIME, Date.from(Instant.parse("2026-09-28T15:00:00Z")));
        metadata.addDirectory(mp4);

        assertEquals(Optional.of(ZonedDateTime.parse("2026-09-28T17:00:00+02:00[Europe/Paris]")), date(metadata, PARIS, NO_SIDECAR));
    }

    @Test
    public void noUsableDateGivesNothing() {
        assertTrue(date(new Metadata(), PARIS, NO_SIDECAR).isEmpty());
    }

    // ---- doAnalyze: the item content is read ----

    /** A JPEG holding only an EXIF segment whose sub-IFD has DateTimeOriginal (little-endian TIFF). */
    private static byte[] jpegWithDateOriginal(String exifDate) {
        ByteBuffer tiff = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        tiff.put(new byte[]{'I', 'I'}).putShort((short) 42).putInt(8);
        // IFD0 at 8: one entry, the pointer to the EXIF sub-IFD at 26
        tiff.putShort((short) 1).putShort((short) 0x8769).putShort((short) 4).putInt(1).putInt(26).putInt(0);
        // sub-IFD at 26: one entry, DateTimeOriginal (ASCII, 20 bytes at 44)
        tiff.putShort((short) 1).putShort((short) ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL).putShort((short) 2)
                .putInt(20).putInt(44).putInt(0);
        tiff.put((exifDate + "\0").getBytes(StandardCharsets.US_ASCII));
        return jpeg(tiff.array());
    }

    /** As jpegWithDateOriginal, the sub-IFD also holding OffsetTimeOriginal. */
    private static byte[] jpegWithDateOriginal(String exifDate, String offset) {
        ByteBuffer tiff = ByteBuffer.allocate(96).order(ByteOrder.LITTLE_ENDIAN);
        tiff.put(new byte[]{'I', 'I'}).putShort((short) 42).putInt(8);
        // IFD0 at 8: one entry, the pointer to the EXIF sub-IFD at 26
        tiff.putShort((short) 1).putShort((short) 0x8769).putShort((short) 4).putInt(1).putInt(26).putInt(0);
        // sub-IFD at 26: two entries, DateTimeOriginal (ASCII, 20 bytes at 56), OffsetTimeOriginal (7 bytes at 76)
        tiff.putShort((short) 2).putShort((short) ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL).putShort((short) 2)
                .putInt(20).putInt(56)
                .putShort((short) ExifSubIFDDirectory.TAG_TIME_ZONE_ORIGINAL).putShort((short) 2)
                .putInt(7).putInt(76).putInt(0);
        tiff.put((exifDate + "\0").getBytes(StandardCharsets.US_ASCII));
        tiff.put((offset + "\0").getBytes(StandardCharsets.US_ASCII));
        return jpeg(tiff.array());
    }

    private static byte[] jpeg(byte[] exif) {

        ByteBuffer jpeg = ByteBuffer.allocate(2 + 4 + 6 + exif.length + 2).order(ByteOrder.BIG_ENDIAN);
        jpeg.putShort((short) 0xFFD8).putShort((short) 0xFFE1).putShort((short) (2 + 6 + exif.length));
        jpeg.put("Exif\0\0".getBytes(StandardCharsets.US_ASCII)).put(exif).putShort((short) 0xFFD9);
        return jpeg.array();
    }

    private static WorkItem item(Supplier<InputStream> content) throws MalformedURLException {
        return new WorkItem(URI.create("file:///card/item").toURL(), content);
    }

    @Test
    public void analyzeSetsTheCaptureDateOfAPhoto() throws IOException {
        byte[] jpeg = jpegWithDateOriginal("2026:09:28 17:42:10");
        WorkItem photo = item(() -> new ByteArrayInputStream(jpeg));

        new ExtractMetadata().doAnalyze(photo);

        Instant expected = LocalDateTime.parse("2026-09-28T17:42:10").atZone(ZoneId.systemDefault()).toInstant();
        assertEquals(Optional.of(expected), photo.getMetadatas().getTime(WorkItemMetadata.CAPTURE_DATE));
    }

    @Test
    public void analyzeFilesAPhotoTakenAbroadOnTheDayItsCameraShowed() throws IOException {
        byte[] jpeg = jpegWithDateOriginal("2026:10:25 23:30:00", "-06:00");
        WorkItem photo = item(() -> new ByteArrayInputStream(jpeg));

        new ExtractMetadata().doAnalyze(photo);

        assertEquals(Optional.of(Instant.parse("2026-10-26T05:30:00Z")), photo.getMetadatas().getTime(WorkItemMetadata.CAPTURE_DATE));
        assertEquals("25", photo.getMetadatas().display().get("captureDate.D"));
    }

    /** An MP4 holding only ftyp and a moov with its mvhd, created at {@code created} (seconds since 1904). */
    private static byte[] mp4CreatedAt(Instant created) {
        long since1904 = created.getEpochSecond() + 2_082_844_800L;
        ByteBuffer mp4 = ByteBuffer.allocate(20 + 8 + 108).order(ByteOrder.BIG_ENDIAN);
        mp4.putInt(20).put("ftypmp42".getBytes(StandardCharsets.US_ASCII)).putInt(0).put("mp42".getBytes(StandardCharsets.US_ASCII));
        mp4.putInt(8 + 108).put("moov".getBytes(StandardCharsets.US_ASCII));
        mp4.putInt(108).put("mvhd".getBytes(StandardCharsets.US_ASCII)).putInt(0) // version 0, no flags
                .putInt((int) since1904).putInt((int) since1904).putInt(1000).putInt(0) // created, modified, timescale, duration
                .putInt(0x00010000).putShort((short) 0x0100).put(new byte[10]) // rate, volume, reserved
                .putInt(0x00010000).putInt(0).putInt(0).putInt(0).putInt(0x00010000).putInt(0).putInt(0).putInt(0)
                .putInt(0x40000000) // identity matrix
                .put(new byte[24]).putInt(2); // pre-defined, next track id
        return mp4.array();
    }

    @Test
    public void analyzeFilesASonyVideoShotAbroadOnTheDayItsCameraShowed(@TempDir Path dir) throws IOException {
        Path video = Files.write(dir.resolve("C0099.MP4"), mp4CreatedAt(Instant.parse("2026-10-26T05:30:00Z")));
        Files.writeString(dir.resolve("C0099M01.XML"), SONY_XML.replace("+02:00", "-06:00"));
        WorkItem clip = new WorkItem(video);

        new ExtractMetadata().doAnalyze(clip);

        assertEquals(Optional.of(Instant.parse("2026-10-26T05:30:00Z")), clip.getMetadatas().getTime(WorkItemMetadata.CAPTURE_DATE));
        assertEquals("25", clip.getMetadatas().display().get("captureDate.D"));
    }

    @Test
    public void analyzeLeavesAnUnsupportedFormatWithoutCaptureDate() throws IOException {
        WorkItem text = item(() -> new ByteArrayInputStream("not an image".getBytes(StandardCharsets.US_ASCII)));

        assertDoesNotThrow(() -> new ExtractMetadata().doAnalyze(text));
        assertTrue(text.getMetadatas().getTime(WorkItemMetadata.CAPTURE_DATE).isEmpty());
    }

    @Test
    public void analyzeFailsTheItemWhenItCannotBeRead() throws IOException {
        IOException removed = new IOException("card removed");
        WorkItem unreadable = item(() -> new InputStream() {
            @Override
            public int read() throws IOException {
                throw removed;
            }
        });

        CopybotException e = assertThrows(CopybotException.class, () -> new ExtractMetadata().doAnalyze(unreadable));
        assertSame(removed, e.getCause());
    }
}

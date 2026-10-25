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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static com.copybot.plugin.metadataextractor.actions.TestJpegs.*;
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

    // ---- doAnalyze: the raw GPS values and the recorded offset ----

    private static byte[] jpegWithGps(String status, String date, int[] time, int[] lat, int[] lon, String exifDate, String offset) {
        List<TestJpegs.Entry> exif = new ArrayList<>(List.of(ascii(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL, exifDate)));
        if (offset != null) {
            exif.add(ascii(ExifSubIFDDirectory.TAG_TIME_ZONE_ORIGINAL, offset));
        }
        return jpeg(tiff(List.of(), exif, List.of(
                ascii(TAG_GPS_LATITUDE_REF, "N"), rationals(TAG_GPS_LATITUDE, lat),
                ascii(TAG_GPS_LONGITUDE_REF, "E"), rationals(TAG_GPS_LONGITUDE, lon),
                rationals(TAG_GPS_TIME_STAMP, time), ascii(TAG_GPS_STATUS, status),
                ascii(TAG_GPS_DATE_STAMP, date))));
    }

    private static final int[] PARIS_LAT = {48, 51, 24};
    private static final int[] PARIS_LON = {2, 21, 8};

    @Test
    public void exposesTheGpsPositionTimeAndValidity() throws IOException {
        byte[] jpeg = jpegWithGps("A", "2026:10:25", new int[]{18, 30, 5}, PARIS_LAT, PARIS_LON, "2026:10:25 19:30:00", null);
        WorkItem photo = item(() -> new ByteArrayInputStream(jpeg));

        new ExtractMetadata().doAnalyze(photo);

        var raw = photo.getMetadatas().raw();
        assertEquals(48 + 51 / 60.0 + 24 / 3600.0, (Double) raw.get(WorkItemMetadata.GPS_LATITUDE), 1e-6);
        assertEquals(2 + 21 / 60.0 + 8 / 3600.0, (Double) raw.get(WorkItemMetadata.GPS_LONGITUDE), 1e-6);
        assertEquals(Instant.parse("2026-10-25T18:30:05Z"), raw.get(WorkItemMetadata.GPS_TIME));
        assertEquals(Boolean.TRUE, raw.get(WorkItemMetadata.GPS_VALID));
    }

    @Test
    public void aVoidOrZeroGpsIsNotValid() throws IOException {
        byte[] voidFix = jpegWithGps("V", "2026:10:25", new int[]{18, 30, 5}, PARIS_LAT, PARIS_LON, "2026:10:25 19:30:00", null);
        byte[] zero = jpegWithGps("A", "2026:10:25", new int[]{18, 30, 5}, new int[]{0, 0, 0}, new int[]{0, 0, 0}, "2026:10:25 19:30:00", null);
        WorkItem voided = item(() -> new ByteArrayInputStream(voidFix));
        WorkItem zeroed = item(() -> new ByteArrayInputStream(zero));

        new ExtractMetadata().doAnalyze(voided);
        new ExtractMetadata().doAnalyze(zeroed);

        assertEquals(Boolean.FALSE, voided.getMetadatas().raw().get(WorkItemMetadata.GPS_VALID));
        assertEquals(Boolean.FALSE, zeroed.getMetadatas().raw().get(WorkItemMetadata.GPS_VALID));
    }

    @Test
    public void exposesTheRecordedOffsetOfAPhotoOnly(@TempDir Path dir) throws IOException {
        byte[] jpeg = jpegWithDateOriginal("2026:10:25 23:30:00", "-06:00");
        WorkItem photo = item(() -> new ByteArrayInputStream(jpeg));
        byte[] noOffset = jpegWithDateOriginal("2026:10:25 23:30:00");
        WorkItem unrecorded = item(() -> new ByteArrayInputStream(noOffset));
        WorkItem clip = new WorkItem(Files.write(dir.resolve("clip.mp4"), mp4CreatedAt(Instant.parse("2026-10-26T05:30:00Z"))));

        new ExtractMetadata().doAnalyze(photo);
        new ExtractMetadata().doAnalyze(unrecorded);
        new ExtractMetadata().doAnalyze(clip);

        assertEquals(ZoneOffset.of("-06:00"), photo.getMetadatas().raw().get(WorkItemMetadata.RECORDED_OFFSET));
        assertFalse(unrecorded.getMetadatas().raw().containsKey(WorkItemMetadata.RECORDED_OFFSET));
        assertTrue(clip.getMetadatas().getTime(WorkItemMetadata.CAPTURE_DATE).isPresent());
        assertFalse(clip.getMetadatas().raw().containsKey(WorkItemMetadata.RECORDED_OFFSET));
    }

    @Test
    public void aReanalysisWithoutGpsRemovesTheKeys() throws IOException {
        byte[] withGps = jpegWithGps("A", "2026:10:25", new int[]{18, 30, 5}, PARIS_LAT, PARIS_LON, "2026:10:25 19:30:00", "+01:00");
        byte[] without = jpegWithDateOriginal("2026:10:25 19:30:00");
        boolean[] first = {true};
        WorkItem photo = item(() -> new ByteArrayInputStream(first[0] ? withGps : without));
        ZoneId suggested = ZoneId.of("Europe/Paris");
        new ExtractMetadata().doAnalyze(photo);
        photo.getMetadatas().raw().put(WorkItemMetadata.SUGGESTED_ZONE, suggested);
        first[0] = false;

        new ExtractMetadata().doAnalyze(photo);

        var raw = photo.getMetadatas().raw();
        for (String key : List.of(WorkItemMetadata.GPS_LATITUDE, WorkItemMetadata.GPS_LONGITUDE, WorkItemMetadata.GPS_TIME,
                WorkItemMetadata.GPS_VALID, WorkItemMetadata.RECORDED_OFFSET)) {
            assertFalse(raw.containsKey(key), key);
        }
        assertEquals(suggested, raw.get(WorkItemMetadata.SUGGESTED_ZONE));
    }
}

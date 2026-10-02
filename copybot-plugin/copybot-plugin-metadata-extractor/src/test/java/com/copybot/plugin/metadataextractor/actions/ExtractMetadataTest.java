package com.copybot.plugin.metadataextractor.actions;

import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.mov.QuickTimeDirectory;
import com.drew.metadata.mp4.Mp4Directory;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.Optional;
import java.util.TimeZone;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

public class ExtractMetadataTest {

    private static final TimeZone PARIS = TimeZone.getTimeZone("Europe/Paris");

    @Test
    public void exifOriginalDateIsReadAsLocalTime() {
        Metadata metadata = new Metadata();
        ExifSubIFDDirectory exif = new ExifSubIFDDirectory();
        exif.setString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL, "2026:09:28 17:42:10");
        metadata.addDirectory(exif);

        assertEquals(Optional.of(Instant.parse("2026-09-28T15:42:10Z")), ExtractMetadata.captureDate(metadata, PARIS));
    }

    @Test
    public void quickTimeCreationDateIsUsedForVideos() {
        Metadata metadata = new Metadata();
        QuickTimeDirectory qt = new QuickTimeDirectory();
        qt.setDate(QuickTimeDirectory.TAG_CREATION_TIME, Date.from(Instant.parse("2026-09-28T15:00:00Z")));
        metadata.addDirectory(qt);

        assertEquals(Optional.of(Instant.parse("2026-09-28T15:00:00Z")), ExtractMetadata.captureDate(metadata, PARIS));
    }

    @Test
    public void unsetQuickTimeCreationDateIsIgnored() {
        // a creation time of 0 decodes to the QuickTime epoch: such a file would sort before any cursor
        Metadata metadata = new Metadata();
        QuickTimeDirectory qt = new QuickTimeDirectory();
        qt.setDate(QuickTimeDirectory.TAG_CREATION_TIME, Date.from(Instant.parse("1904-01-01T00:00:00Z")));
        metadata.addDirectory(qt);

        assertTrue(ExtractMetadata.captureDate(metadata, PARIS).isEmpty());
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

        assertEquals(Optional.of(Instant.parse("2026-09-28T15:00:00Z")), ExtractMetadata.captureDate(metadata, PARIS));
    }

    @Test
    public void noUsableDateGivesNothing() {
        assertTrue(ExtractMetadata.captureDate(new Metadata(), PARIS).isEmpty());
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
        byte[] exif = tiff.array();

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

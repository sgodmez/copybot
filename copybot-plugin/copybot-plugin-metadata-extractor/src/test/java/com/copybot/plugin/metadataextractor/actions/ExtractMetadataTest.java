package com.copybot.plugin.metadataextractor.actions;

import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.mov.QuickTimeDirectory;
import com.drew.metadata.mp4.Mp4Directory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.TimeZone;

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
}

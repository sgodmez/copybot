package com.copybot.plugin.metadataextractor.actions;

import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.AbstractAction;
import com.copybot.plugin.api.action.IAnalyzeAction;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.mov.QuickTimeDirectory;
import com.drew.metadata.mp4.Mp4Directory;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.TimeZone;

/** Sets the item capture date (metadata "captureDate") from the EXIF or video creation date. */
public class ExtractMetadata extends AbstractAction implements IAnalyzeAction {

    @Override
    public void doAnalyze(WorkItem item) {
        Metadata metadata;
        try (InputStream is = item.openInputStream()) {
            metadata = ImageMetadataReader.readMetadata(is);
        } catch (ImageProcessingException e) {
            return; // unsupported format: no capture date
        } catch (IOException e) {
            throw CopybotException.ofResource(e, "plugin.metadata-extractor.extract.error.io", item.getSourceLocationDisplay());
        }
        captureDate(metadata, TimeZone.getDefault())
                .ifPresent(date -> item.getMetadatas().setTime(WorkItemMetadata.CAPTURE_DATE, date));
    }

    /**
     * EXIF DateTimeOriginal is a local time without zone: it is read in {@code localZone}, as the OS
     * does for FAT file dates. QuickTime / MP4 creation dates are already UTC.
     */
    static Optional<Instant> captureDate(Metadata metadata, TimeZone localZone) {
        ExifSubIFDDirectory exif = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
        if (exif != null) {
            Date original = exif.getDateOriginal(localZone);
            if (original != null) {
                return Optional.of(original.toInstant());
            }
        }
        return videoDate(metadata.getFirstDirectoryOfType(QuickTimeDirectory.class), QuickTimeDirectory.TAG_CREATION_TIME)
                .or(() -> videoDate(metadata.getFirstDirectoryOfType(Mp4Directory.class), Mp4Directory.TAG_CREATION_TIME));
    }

    private static Optional<Instant> videoDate(Directory directory, int tag) {
        if (directory == null) {
            return Optional.empty();
        }
        Date date = directory.getDate(tag);
        // an unset creation time (0) decodes to the QuickTime epoch 1904-01-01: not a capture date
        return Optional.ofNullable(date).map(Date::toInstant).filter(instant -> instant.isAfter(Instant.EPOCH));
    }
}

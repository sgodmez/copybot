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
import com.drew.metadata.mov.metadata.QuickTimeMetadataDirectory;
import com.drew.metadata.mp4.Mp4Directory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Optional;
import java.util.TimeZone;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        Path local = item.getLocalLocation();
        Optional<Capture> capture = captureDate(metadata, ZoneId.systemDefault(),
                () -> local == null ? Optional.empty() : sonySidecarOffset(local));
        if (capture.isEmpty()) {
            return;
        }
        WorkItemMetadata metadatas = item.getMetadatas();
        metadatas.raw().put(WorkItemMetadata.CAPTURE_CLOCK_UNKNOWN, capture.get().clockUnknown());
        Optional<ZoneId> place = metadatas.getZone(WorkItemMetadata.CAPTURE_ZONE);
        metadatas.setTime(WorkItemMetadata.CAPTURE_DATE, place.isPresent()
                ? corrected(capture.get(), place.get(), metadatas.getZone(WorkItemMetadata.CAMERA_CLOCK).orElse(null))
                : capture.get().date());
    }

    /**
     * A capture date, and whether its clock is unknown: an EXIF photo without recorded offset, whose wall time
     * is only known to be that of some clock. A video is never unknown: its UTC creation date is reliable.
     */
    record Capture(ZonedDateTime date, boolean clockUnknown) {
    }

    /**
     * The capture date shown in the zone of the {@code place} where it was shot. A recorded offset is exact.
     * An unknown clock is read in the camera {@code clock} (nullable), else in the place itself.
     */
    static ZonedDateTime corrected(Capture capture, ZoneId place, ZoneId clock) {
        if (!capture.clockUnknown()) {
            return capture.date().withZoneSameInstant(place);
        }
        return capture.date().toLocalDateTime().atZone(clock != null ? clock : place).withZoneSameInstant(place);
    }

    /**
     * The capture date in the zone of the camera clock, so that a photo is filed on the day its camera showed.
     * EXIF DateTimeOriginal is a local time: in the zone of its OffsetTimeOriginal when the camera wrote one,
     * else in {@code localZone}, as the OS does for FAT file dates. QuickTime / MP4 creation dates are UTC:
     * shown in the offset of the creationdate key when the camera wrote one, else in {@code sidecarOffset}
     * (read only for a video), else in {@code localZone}.
     */
    static Optional<Capture> captureDate(Metadata metadata, ZoneId localZone,
                                         Supplier<Optional<ZoneOffset>> sidecarOffset) {
        ExifSubIFDDirectory exif = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
        if (exif != null) {
            Optional<ZoneId> recorded = offset(exif.getString(ExifSubIFDDirectory.TAG_TIME_ZONE_ORIGINAL));
            ZoneId cameraZone = recorded.orElse(localZone);
            Date original = exif.getDateOriginal(TimeZone.getTimeZone(cameraZone));
            if (original != null) {
                return Optional.of(new Capture(original.toInstant().atZone(cameraZone), recorded.isEmpty()));
            }
        }
        return videoDate(metadata, QuickTimeDirectory.class, QuickTimeDirectory.TAG_CREATION_TIME)
                .or(() -> videoDate(metadata, Mp4Directory.class, Mp4Directory.TAG_CREATION_TIME))
                .map(instant -> new Capture(instant.atZone(videoOffset(metadata).or(sidecarOffset)
                        .<ZoneId>map(offset -> offset).orElse(localZone)), false));
    }

    /** The offset of the QuickTime creationdate key (local time with offset, e.g. 2026-10-25T23:30:00-0600). */
    private static Optional<ZoneOffset> videoOffset(Metadata metadata) {
        QuickTimeMetadataDirectory keys = metadata.getFirstDirectoryOfType(QuickTimeMetadataDirectory.class);
        String text = keys == null ? null : keys.getString(QuickTimeMetadataDirectory.TAG_CREATION_DATE);
        return dateOffset(text);
    }

    private static final DateTimeFormatter DATE_WITH_OFFSET = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss[.SSS][XXX][XX]");

    private static Optional<ZoneOffset> dateOffset(String text) {
        if (text == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(OffsetDateTime.parse(text.strip(), DATE_WITH_OFFSET).getOffset());
        } catch (DateTimeException e) {
            return Optional.empty(); // malformed or without offset: as if the camera wrote none
        }
    }

    private static final Pattern SONY_CREATION_DATE = Pattern.compile("<CreationDate\\s+value=\"([^\"]*)\"");
    private static final long SIDECAR_MAX_SIZE = 1 << 20;

    /**
     * The offset of the Sony XAVC sidecar next to a video (C0099.MP4 -> C0099M01.XML), whose CreationDate is
     * the local time of the camera with its offset. Nothing when there is none or it cannot be read.
     */
    static Optional<ZoneOffset> sonySidecarOffset(Path video) {
        String name = video.getFileName().toString();
        int dot = name.lastIndexOf('.');
        Path sidecar = video.resolveSibling((dot < 0 ? name : name.substring(0, dot)) + "M01.XML");
        try {
            if (!Files.isRegularFile(sidecar) || Files.size(sidecar) > SIDECAR_MAX_SIZE) {
                return Optional.empty();
            }
            Matcher matcher = SONY_CREATION_DATE.matcher(Files.readString(sidecar));
            return matcher.find() ? dateOffset(matcher.group(1)) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty(); // the sidecar only refines the date: the video is still analyzed
        }
    }

    private static Optional<ZoneId> offset(String text) {
        if (text == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(ZoneOffset.of(text.strip()));
        } catch (DateTimeException e) {
            return Optional.empty(); // blank or malformed: as if the camera wrote none
        }
    }

    private static Optional<Instant> videoDate(Metadata metadata, Class<? extends Directory> type, int tag) {
        // exact type: QuickTimeMetadataDirectory is a QuickTimeDirectory without creation time
        Optional<? extends Directory> directory = metadata.getDirectoriesOfType(type).stream()
                .filter(candidate -> candidate.getClass() == type).findFirst();
        if (directory.isEmpty()) {
            return Optional.empty();
        }
        Date date = directory.get().getDate(tag);
        // an unset creation time (0) decodes to the QuickTime epoch 1904-01-01: not a capture date
        return Optional.ofNullable(date).map(Date::toInstant).filter(instant -> instant.isAfter(Instant.EPOCH));
    }
}

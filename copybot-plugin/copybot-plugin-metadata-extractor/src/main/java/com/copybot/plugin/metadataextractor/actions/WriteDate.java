package com.copybot.plugin.metadataextractor.actions;

import com.copybot.plugin.api.action.AbstractAction;
import com.copybot.plugin.api.action.IProcessAction;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import com.drew.imaging.jpeg.JpegMetadataReader;
import com.drew.imaging.jpeg.JpegProcessingException;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifReader;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;
import org.apache.commons.imaging.bytesource.ByteSource;
import org.apache.commons.imaging.formats.jpeg.JpegImageParser;
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.tiff.TiffDirectory;
import org.apache.commons.imaging.formats.tiff.TiffField;
import org.apache.commons.imaging.formats.tiff.TiffImageMetadata;
import org.apache.commons.imaging.formats.tiff.constants.ExifTagConstants;
import org.apache.commons.imaging.formats.tiff.constants.GpsTagConstants;
import org.apache.commons.imaging.formats.tiff.constants.TiffDirectoryConstants;
import org.apache.commons.imaging.formats.tiff.constants.TiffDirectoryType;
import org.apache.commons.imaging.formats.tiff.constants.TiffTagConstants;
import org.apache.commons.imaging.formats.tiff.taginfos.TagInfoAscii;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputDirectory;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputField;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Gives the copy of a JPEG whose time zone was corrected in the plan (metadata "captureZone") its local date and
 * offset in EXIF, without recompressing the image; to place just before the write. Any other item passes as is,
 * byte for byte. A rewrite that fails passes the item as is too, with a note: a wrong date is better than a
 * missing file.
 */
public class WriteDate extends AbstractAction implements IProcessAction {

    private static final System.Logger LOGGER = System.getLogger(WriteDate.class.getName());

    private static final String REWRITTEN = "EXIF r\u00e9\u00e9crit : ";
    private static final String NOT_REWRITTEN = "EXIF non r\u00e9\u00e9crit : ";
    private static final String UNSUPPORTED = NOT_REWRITTEN + "format non pris en charge";
    private static final String NO_DATE = NOT_REWRITTEN + "pas de date EXIF";
    /** In the plan, for an item passed as is: the step is listed for every file, it must not look like a processing. */
    private static final String UNCHANGED = "inchang\u00e9 : fuseau horaire non corrig\u00e9";

    private static final byte[] JPEG_SIGNATURE = {(byte) 0xFF, (byte) 0xD8};
    private static final DateTimeFormatter EXIF_DATE = DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss");
    private static final DateTimeFormatter OFFSET = DateTimeFormatter.ofPattern("xxx");
    private static final DateTimeFormatter NOTE_DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm xxx");

    // the offsets of EXIF 2.31, unknown to Commons Imaging: "+02:00" and its terminating NUL
    private static final TagInfoAscii OFFSET_TIME =
            new TagInfoAscii("OffsetTime", 0x9010, 7, TiffDirectoryType.EXIF_DIRECTORY_EXIF_IFD);
    private static final TagInfoAscii OFFSET_TIME_ORIGINAL =
            new TagInfoAscii("OffsetTimeOriginal", 0x9011, 7, TiffDirectoryType.EXIF_DIRECTORY_EXIF_IFD);
    private static final TagInfoAscii OFFSET_TIME_DIGITIZED =
            new TagInfoAscii("OffsetTimeDigitized", 0x9012, 7, TiffDirectoryType.EXIF_DIRECTORY_EXIF_IFD);

    /** The check of a rewritten JPEG before it is used; throws, with the reason, when it must not be. */
    @FunctionalInterface
    interface SelfCheck {
        void verify(byte[] original, byte[] rewritten, ZonedDateTime local) throws IOException;
    }

    private final SelfCheck selfCheck;

    public WriteDate() {
        this(WriteDate::check);
    }

    /** For the tests: another check of the rewritten JPEG than {@link #check}. */
    WriteDate(SelfCheck selfCheck) {
        this.selfCheck = selfCheck;
    }

    /**
     * Gives a corrected JPEG a copy whose content is its rewritten version. The rewritten bytes are not kept: a plan
     * keeps every item until its end, so the copy rewrites the original again at each open (deterministic: the same
     * bytes, of the size computed here). A rewrite that fails then (the original changed in between) is an
     * {@link IOException} of the stream: the write fails the item and the source is kept.
     */
    @Override
    public List<WorkItem> doProcess(WorkItem item) {
        Optional<ZoneId> place = item.getMetadatas().getZone(WorkItemMetadata.CAPTURE_ZONE);
        if (place.isEmpty()) {
            return List.of(item);
        }
        try (PushbackInputStream content = new PushbackInputStream(item.openInputStream(), JPEG_SIGNATURE.length)) {
            Optional<String> refusal = refusal(item, content);
            if (refusal.isPresent()) {
                return noted(item, refusal.get());
            }
            content.unread(JPEG_SIGNATURE);
            ZonedDateTime local = local(item, place.get());
            Optional<byte[]> rewritten = checkedRewrite(content.readAllBytes(), local);
            if (rewritten.isEmpty()) {
                return noted(item, NO_DATE);
            }
            long size = rewritten.get().length;
            return List.of(item.withContent(() -> reopen(item, local, size), size));
        } catch (IOException | RuntimeException e) {
            LOGGER.log(System.Logger.Level.WARNING, "EXIF date of {0} not rewritten, copied as is: {1}",
                    Objects.requireNonNullElse(item.getNameDisplay(), item.getSourceLocationDisplay()), message(e));
            return noted(item, NOT_REWRITTEN + message(e));
        }
    }

    /** The verdict of {@link #doProcess} for the plan, from the metadata and the JPEG signature only. */
    @Override
    public Optional<List<WorkItem>> dryRun(WorkItem item) {
        Optional<ZoneId> place = item.getMetadatas().getZone(WorkItemMetadata.CAPTURE_ZONE);
        if (place.isEmpty()) {
            item.getMetadatas().raw().put(WorkItemMetadata.NOTE, UNCHANGED);
        } else {
            String note;
            try (InputStream content = item.openInputStream()) {
                note = refusal(item, content).orElseGet(() -> REWRITTEN + NOTE_DATE.format(local(item, place.get())));
            } catch (IOException | RuntimeException e) {
                note = NOT_REWRITTEN + message(e);
            }
            item.getMetadatas().raw().put(WorkItemMetadata.NOTE, note);
        }
        return Optional.of(List.of(item));
    }

    /**
     * Why a corrected item cannot be rewritten, read on the first two bytes of its {@code content}: not a JPEG,
     * or no capture date. Empty when it can.
     */
    private static Optional<String> refusal(WorkItem item, InputStream content) throws IOException {
        if (!Arrays.equals(JPEG_SIGNATURE, content.readNBytes(JPEG_SIGNATURE.length))) {
            return Optional.of(UNSUPPORTED);
        }
        if (item.getMetadatas().getTime(WorkItemMetadata.CAPTURE_DATE).isEmpty()) {
            return Optional.of(NO_DATE);
        }
        return Optional.empty();
    }

    /** The corrected capture date, shown in the place where it was shot. */
    private static ZonedDateTime local(WorkItem item, ZoneId place) {
        Instant shot = item.getMetadatas().getTime(WorkItemMetadata.CAPTURE_DATE).orElseThrow();
        return shot.atZone(place);
    }

    private static List<WorkItem> noted(WorkItem item, String note) {
        item.getMetadatas().raw().put(WorkItemMetadata.NOTE, note);
        return List.of(item);
    }

    private static String message(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    /** {@link #rewrite}, then the {@link SelfCheck} of the result. */
    private Optional<byte[]> checkedRewrite(byte[] jpeg, ZonedDateTime local) throws IOException {
        Optional<byte[]> rewritten = rewrite(jpeg, local);
        if (rewritten.isPresent()) {
            selfCheck.verify(jpeg, rewritten.get(), local);
        }
        return rewritten;
    }

    /**
     * The content of the rewritten copy of {@code original}, rewritten again from it; a stream whose reads throw
     * the reason when it can no longer be (the original changed: other EXIF, other size).
     */
    private InputStream reopen(WorkItem original, ZonedDateTime local, long size) {
        try {
            byte[] jpeg;
            try (InputStream in = original.openInputStream()) {
                jpeg = in.readAllBytes();
            }
            byte[] rewritten = checkedRewrite(jpeg, local).orElseThrow(() -> new IOException(NO_DATE));
            if (rewritten.length != size) {
                throw new IOException(NOT_REWRITTEN + "l'original a changé (" + rewritten.length + " octets au lieu de "
                        + size + ")");
            }
            return new ByteArrayInputStream(rewritten);
        } catch (IOException | RuntimeException e) {
            IOException failure = e instanceof IOException io ? io : new IOException(message(e), e);
            return new InputStream() {
                @Override
                public int read() throws IOException {
                    throw failure;
                }
            };
        }
    }

    private static final String CHECK = "vérification : ";

    /**
     * The check of a rewritten JPEG: every byte outside the EXIF segment is the {@code original}'s, and
     * metadata-extractor reads it, without other errors than in the original, with DateTimeOriginal and
     * OffsetTimeOriginal at {@code local}.
     *
     * @throws IOException with the reason, in French (it ends in the note of the item), when it fails
     */
    static void check(byte[] original, byte[] rewritten, ZonedDateTime local) throws IOException {
        if (!sameOutsideExif(original, rewritten)) {
            throw new IOException(CHECK + "image modifiée hors EXIF");
        }
        Metadata after = readExif(rewritten);
        if (after.hasErrors()) {
            List<String> errors = errors(after);
            errors.removeAll(errors(readExif(original)));
            if (!errors.isEmpty()) {
                throw new IOException(CHECK + "EXIF illisible (" + String.join(", ", errors) + ")");
            }
        }
        ExifSubIFDDirectory exif = after.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
        String date = exif == null ? null : exif.getString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL);
        String offset = exif == null ? null : exif.getString(ExifSubIFDDirectory.TAG_TIME_ZONE_ORIGINAL);
        String expected = EXIF_DATE.format(local) + " " + OFFSET.format(local);
        if (!expected.equals(date + " " + offset)) {
            throw new IOException(CHECK + "date relue " + date + " " + offset + " au lieu de " + expected);
        }
    }

    private static Metadata readExif(byte[] jpeg) throws IOException {
        try {
            return JpegMetadataReader.readMetadata(new ByteArrayInputStream(jpeg), List.of(new ExifReader()));
        } catch (JpegProcessingException | RuntimeException e) {
            throw new IOException(CHECK + "EXIF illisible (" + message(e) + ")", e);
        }
    }

    private static List<String> errors(Metadata metadata) {
        List<String> errors = new ArrayList<>();
        for (Directory directory : metadata.getDirectories()) {
            directory.getErrors().forEach(errors::add);
        }
        return errors;
    }

    /**
     * Whether {@code a} and {@code b} are the same JPEG but their EXIF segment (APP1 "Exif"): the same other
     * segments, in the same order, and the same image, byte for byte. Compared in place, without copies.
     */
    static boolean sameOutsideExif(byte[] a, byte[] b) {
        List<int[]> inA = outsideExif(a);
        List<int[]> inB = outsideExif(b);
        if (inA == null || inB == null) {
            return false;
        }
        int ia = 0;
        int ib = 0;
        int atA = inA.isEmpty() ? 0 : inA.getFirst()[0];
        int atB = inB.isEmpty() ? 0 : inB.getFirst()[0];
        while (ia < inA.size() && ib < inB.size()) {
            int[] rangeA = inA.get(ia);
            int[] rangeB = inB.get(ib);
            int length = Math.min(rangeA[1] - atA, rangeB[1] - atB);
            if (!Arrays.equals(a, atA, atA + length, b, atB, atB + length)) {
                return false;
            }
            atA += length;
            atB += length;
            if (atA == rangeA[1] && ++ia < inA.size()) {
                atA = inA.get(ia)[0];
            }
            if (atB == rangeB[1] && ++ib < inB.size()) {
                atB = inB.get(ib)[0];
            }
        }
        return ia == inA.size() && ib == inB.size();
    }

    /**
     * The ranges [start, end) of {@code jpeg} outside its EXIF segments: the start of image, every other segment
     * before the start of scan, then everything from it (the image). Null when the segments cannot be walked.
     */
    private static List<int[]> outsideExif(byte[] jpeg) {
        if (jpeg.length < 4 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) {
            return null;
        }
        List<int[]> ranges = new ArrayList<>();
        ranges.add(new int[]{0, 2});
        int at = 2;
        while (true) {
            if (at + 4 > jpeg.length || (jpeg[at] & 0xFF) != 0xFF) {
                return null; // no start of scan: not a whole JPEG
            }
            int marker = jpeg[at + 1] & 0xFF;
            if (marker == 0xDA) { // start of scan: the rest is the image
                ranges.add(new int[]{at, jpeg.length});
                return ranges;
            }
            int end = at + 2 + (((jpeg[at + 2] & 0xFF) << 8) | (jpeg[at + 3] & 0xFF));
            if (end > jpeg.length) {
                return null;
            }
            boolean exif = marker == 0xE1 && end - at >= 10
                    && Arrays.equals(jpeg, at + 4, at + 10, EXIF_HEADER, 0, EXIF_HEADER.length);
            if (!exif) {
                ranges.add(new int[]{at, end});
            }
            at = end;
        }
    }

    private static final byte[] EXIF_HEADER = {'E', 'x', 'i', 'f', 0, 0};

    /**
     * The {@code jpeg} whose EXIF says it was shot at {@code local}: DateTimeOriginal and OffsetTimeOriginal set,
     * DateTimeDigitized and DateTime (with their offsets), when present, moved by the same amount; every other
     * tag (with its bytes as read), the maker notes and the other segments kept. The IFDs may move: the lossless
     * writer of Commons Imaging places them where they fit. Empty when there is no DateTimeOriginal.
     *
     * @throws IOException the EXIF cannot be read or written
     */
    static Optional<byte[]> rewrite(byte[] jpeg, ZonedDateTime local) throws IOException {
        TiffImageMetadata exif = new JpegImageParser().getExifMetadata(ByteSource.array(jpeg), null);
        TiffDirectory exifIfd = exif == null ? null : exif.findDirectory(TiffDirectoryConstants.DIRECTORY_TYPE_EXIF);
        String original = text(exifIfd, ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL);
        if (original == null) {
            return Optional.empty();
        }
        TiffOutputSet output = exif.getOutputSet();
        keepRawValues(exif, output);
        keepGpsVersion(jpeg, output);
        TiffOutputDirectory exifOutput = output.getOrCreateExifDirectory();
        set(exifOutput, ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL, EXIF_DATE.format(local));
        set(exifOutput, OFFSET_TIME_ORIGINAL, OFFSET.format(local));
        Optional<LocalDateTime> was = parse(original);
        if (was.isPresent()) {
            move(exifIfd, exifOutput, ExifTagConstants.EXIF_TAG_DATE_TIME_DIGITIZED, exifOutput, OFFSET_TIME_DIGITIZED,
                    was.get(), local);
            move(exif.findDirectory(TiffDirectoryConstants.DIRECTORY_TYPE_ROOT), output.getOrCreateRootDirectory(),
                    TiffTagConstants.TIFF_TAG_DATE_TIME, exifOutput, OFFSET_TIME, was.get(), local);
        }
        ByteArrayOutputStream rewritten = new ByteArrayOutputStream(jpeg.length + 64);
        new ExifRewriter().updateExifMetadataLossless(jpeg, rewritten, output);
        return Optional.of(rewritten.toByteArray());
    }

    /**
     * Moves the {@code date} tag of {@code directory}, when present and readable, as DateTimeOriginal moved from
     * {@code was} to {@code local}; its {@code offset} tag, when present, becomes the offset of the moved date.
     */
    private static void move(TiffDirectory directory, TiffOutputDirectory output, TagInfoAscii date,
                             TiffOutputDirectory offsetOutput, TagInfoAscii offset,
                             LocalDateTime was, ZonedDateTime local) throws IOException {
        Optional<LocalDateTime> current = parse(text(directory, date));
        if (current.isEmpty()) {
            return;
        }
        ZonedDateTime moved = local.plus(Duration.between(was, current.get()));
        set(output, date, EXIF_DATE.format(moved));
        if (offsetOutput.findField(offset.tag) != null) {
            set(offsetOutput, offset, OFFSET.format(moved));
        }
    }

    /**
     * Gives the fields of {@code output} back the bytes they have in {@code exif}: Commons Imaging decodes then
     * encodes every value, which changes some (an empty user comment gets a charset).
     */
    private static void keepRawValues(TiffImageMetadata exif, TiffOutputSet output) {
        Set<Integer> seen = new HashSet<>();
        for (TiffDirectory directory : exif.contents.directories) {
            TiffOutputDirectory outputDirectory = output.findDirectory(directory.type);
            if (outputDirectory == null || !seen.add(directory.type)) {
                continue; // a repeated directory is not in the output either
            }
            for (TiffField field : directory.getDirectoryEntries()) {
                TiffOutputField encoded = outputDirectory.findField(field.getTag());
                byte[] raw = field.getByteArrayValue();
                if (encoded != null && !Arrays.equals(encoded.getData(), raw)) {
                    outputDirectory.removeField(field.getTag());
                    outputDirectory.add(new TiffOutputField(field.getTag(), field.getTagInfo(), field.getFieldType(),
                            (int) field.getCount(), raw));
                }
            }
        }
    }

    /**
     * Puts the GPS version of {@code jpeg} back in the {@code output}: Commons Imaging skips every entry of tag 0
     * when it reads, and GPSVersionID is the tag 0 of the GPS IFD. metadata-extractor reads it.
     */
    private static void keepGpsVersion(byte[] jpeg, TiffOutputSet output) throws IOException {
        TiffOutputDirectory gpsOutput = output.getGpsDirectory();
        if (gpsOutput == null) {
            return;
        }
        Metadata metadata;
        try {
            metadata = JpegMetadataReader.readMetadata(new ByteArrayInputStream(jpeg), List.of(new ExifReader()));
        } catch (JpegProcessingException e) {
            throw new IOException(e.getMessage(), e);
        }
        GpsDirectory gps = metadata.getFirstDirectoryOfType(GpsDirectory.class);
        byte[] version = gps == null ? null : gps.getByteArray(GpsDirectory.TAG_VERSION_ID);
        if (version != null) {
            gpsOutput.removeField(GpsTagConstants.GPS_TAG_GPS_VERSION_ID);
            gpsOutput.add(GpsTagConstants.GPS_TAG_GPS_VERSION_ID, version);
        }
    }

    private static String text(TiffDirectory directory, TagInfoAscii tag) throws IOException {
        TiffField field = directory == null ? null : directory.findField(tag);
        return field == null ? null : field.getStringValue();
    }

    private static Optional<LocalDateTime> parse(String exifDate) {
        if (exifDate == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDateTime.parse(exifDate.strip(), EXIF_DATE));
        } catch (DateTimeParseException e) {
            return Optional.empty(); // blank or malformed: not moved
        }
    }

    private static void set(TiffOutputDirectory directory, TagInfoAscii tag, String value) throws IOException {
        directory.removeField(tag);
        directory.add(tag, value);
    }
}

package com.copybot.plugin.metadataextractor.actions;

import com.drew.metadata.exif.ExifSubIFDDirectory;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** JPEG fabricators for the tests: an EXIF segment built from IFD entries, an XMP segment, then a tiny image. */
final class TestJpegs {

    static final int TAG_MAKE = 0x010F;
    static final int TAG_MODEL = 0x0110;
    static final int TAG_DATETIME = 0x0132;
    static final int TAG_MAKERNOTE = 0x927C;
    static final int TAG_USER_COMMENT = 0x9286;
    static final int TAG_GPS_VERSION = 0x0000;
    static final int TAG_GPS_MAP_DATUM = 0x0012;
    static final int TAG_GPS_LATITUDE_REF = 0x0001;
    static final int TAG_GPS_LATITUDE = 0x0002;
    static final int TAG_GPS_LONGITUDE_REF = 0x0003;
    static final int TAG_GPS_LONGITUDE = 0x0004;
    static final int TAG_GPS_TIME_STAMP = 0x0007;
    static final int TAG_GPS_STATUS = 0x0009;
    static final int TAG_GPS_DATE_STAMP = 0x001D;
    private static final int TAG_EXIF_POINTER = 0x8769;
    private static final int TAG_GPS_POINTER = 0x8825;
    private static final int TAG_COMPRESSION = 0x0103;
    private static final int TAG_THUMBNAIL_OFFSET = 0x0201;
    private static final int TAG_THUMBNAIL_LENGTH = 0x0202;
    private static final short BYTE = 1;
    private static final short ASCII = 2;
    private static final short SHORT = 3;
    private static final short LONG = 4;
    private static final short RATIONAL = 5;
    private static final short UNDEFINED = 7;

    private TestJpegs() {
    }

    /** The value of an IFD entry, built from its own offset in the TIFF (a maker note points inside itself). */
    interface Value {
        byte[] at(int offset, ByteOrder order);
    }

    /** An IFD entry. */
    record Entry(int tag, short type, Value value) {
    }

    static Entry ascii(int tag, String text) {
        byte[] value = (text + "\0").getBytes(StandardCharsets.US_ASCII);
        return new Entry(tag, ASCII, (offset, order) -> value);
    }

    /**
     * A Sony maker note ("SONY DSC" header, then an IFD in the byte order of the TIFF) holding ColorModeSetting
     * (0xB020) = {@code style}, whose value is stored by absolute offset in the TIFF: moving the maker note without
     * fixing it breaks the tag.
     */
    static Entry sonyMakerNote(String style) {
        return new Entry(TAG_MAKERNOTE, UNDEFINED, (offset, order) -> {
            byte[] text = (style + "\0").getBytes(StandardCharsets.US_ASCII);
            ByteBuffer note = ByteBuffer.allocate(12 + 2 + 12 + 4 + text.length).order(order);
            note.put("SONY DSC \0\0\0".getBytes(StandardCharsets.US_ASCII));
            note.putShort((short) 1).putShort((short) 0xB020).putShort(ASCII).putInt(text.length).putInt(offset + 30).putInt(0);
            note.put(text);
            return note.array();
        });
    }

    /** RATIONAL entry of whole numbers (denominator 1): degrees, minutes, seconds, or hours, minutes, seconds. */
    static Entry rationals(int tag, int... numbers) {
        return new Entry(tag, RATIONAL, (offset, order) -> {
            ByteBuffer value = ByteBuffer.allocate(8 * numbers.length).order(order);
            for (int number : numbers) {
                value.putInt(number).putInt(1);
            }
            return value.array();
        });
    }

    static Entry bytes(int tag, byte... value) {
        return new Entry(tag, BYTE, (offset, order) -> value);
    }

    static Entry undefined(int tag, byte... value) {
        return new Entry(tag, UNDEFINED, (offset, order) -> value);
    }

    /** A little-endian TIFF: IFD0 with {@code ifd0} (sorted by tag), then the EXIF sub-IFD with {@code exif}. */
    static byte[] tiff(List<Entry> ifd0, List<Entry> exif) {
        return tiff(ifd0, exif, List.of());
    }

    /** As tiff(ifd0, exif), with a GPS IFD holding {@code gps} when it is not empty. */
    static byte[] tiff(List<Entry> ifd0, List<Entry> exif, List<Entry> gps) {
        return tiff(ifd0, exif, gps, null);
    }

    /**
     * As tiff(ifd0, exif, gps), with an IFD1 after IFD0 when {@code thumbnail} is not null: a JPEG thumbnail
     * (Compression 6, JPEGInterchangeFormat and its length) whose bytes follow IFD1.
     */
    static byte[] tiff(List<Entry> ifd0, List<Entry> exif, List<Entry> gps, byte[] thumbnail) {
        return tiff(ByteOrder.LITTLE_ENDIAN, ifd0, exif, gps, thumbnail);
    }

    /** As tiff(ifd0, exif, gps, thumbnail), in this byte order: "II" (Intel, little-endian) or "MM" (Motorola). */
    static byte[] tiff(ByteOrder order, List<Entry> ifd0, List<Entry> exif, List<Entry> gps, byte[] thumbnail) {
        List<Entry> root = new ArrayList<>(ifd0);
        int ifd0Size = 2 + 12 * (root.size() + (gps.isEmpty() ? 1 : 2)) + 4;
        int exifStart = 8 + ifd0Size;
        int gpsStart = exifStart + 2 + 12 * exif.size() + 4;
        int ifd1Start = gpsStart + (gps.isEmpty() ? 0 : 2 + 12 * gps.size() + 4);
        int thumbnailStart = ifd1Start + (thumbnail == null ? 0 : 2 + 12 * 3 + 4);
        int dataStart = thumbnailStart + (thumbnail == null ? 0 : thumbnail.length + thumbnail.length % 2);
        root.add(new Entry(TAG_EXIF_POINTER, LONG, (offset, o) -> intBytes(exifStart, o)));
        if (!gps.isEmpty()) {
            root.add(new Entry(TAG_GPS_POINTER, LONG, (offset, o) -> intBytes(gpsStart, o)));
        }
        ByteBuffer tiff = ByteBuffer.allocate(dataStart + 4096).order(order);
        byte mark = order == ByteOrder.LITTLE_ENDIAN ? (byte) 'I' : (byte) 'M';
        tiff.put(new byte[]{mark, mark}).putShort((short) 42).putInt(8);
        int[] data = {dataStart};
        writeIfd(tiff, root, data, thumbnail == null ? 0 : ifd1Start);
        writeIfd(tiff, exif, data, 0);
        if (!gps.isEmpty()) {
            writeIfd(tiff, gps, data, 0);
        }
        if (thumbnail != null) {
            writeIfd(tiff, List.of(
                    new Entry(TAG_COMPRESSION, SHORT, (offset, o) -> ByteBuffer.allocate(2).order(o).putShort((short) 6).array()),
                    new Entry(TAG_THUMBNAIL_OFFSET, LONG, (offset, o) -> intBytes(thumbnailStart, o)),
                    new Entry(TAG_THUMBNAIL_LENGTH, LONG, (offset, o) -> intBytes(thumbnail.length, o))), data, 0);
            tiff.put(thumbnailStart, thumbnail);
        }
        byte[] result = new byte[data[0]];
        tiff.position(0).get(result);
        return result;
    }

    private static void writeIfd(ByteBuffer tiff, List<Entry> all, int[] data, int next) {
        tiff.putShort((short) all.size());
        for (Entry entry : all) {
            byte[] value = entry.value().at(data[0], tiff.order());
            int count = value.length / (entry.type() == LONG ? 4 : entry.type() == SHORT ? 2 : entry.type() == RATIONAL ? 8 : 1);
            tiff.putShort((short) entry.tag()).putShort(entry.type()).putInt(count);
            if (value.length <= 4) {
                tiff.put(value).put(new byte[4 - value.length]);
            } else {
                tiff.putInt(data[0]);
                tiff.put(data[0], value);
                data[0] += value.length + (value.length % 2); // word aligned
            }
        }
        tiff.putInt(next);
    }

    private static byte[] intBytes(int value, ByteOrder order) {
        return ByteBuffer.allocate(4).order(order).putInt(value).array();
    }

    /** A JPEG holding only an EXIF segment whose sub-IFD has DateTimeOriginal. */
    static byte[] jpegWithDateOriginal(String exifDate) {
        return jpeg(tiff(List.of(), List.of(ascii(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL, exifDate))));
    }

    /** As jpegWithDateOriginal, the sub-IFD also holding OffsetTimeOriginal. */
    static byte[] jpegWithDateOriginal(String exifDate, String offset) {
        return jpeg(tiff(List.of(), List.of(ascii(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL, exifDate),
                ascii(ExifSubIFDDirectory.TAG_TIME_ZONE_ORIGINAL, offset))));
    }

    static final byte[] XMP_SEGMENT = segment(0xFFE1, ("http://ns.adobe.com/xap/1.0/\0"
            + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"/></x:xmpmeta>")
            .getBytes(StandardCharsets.US_ASCII));

    /** The image after the metadata: a quantization table, a start of scan, scan data and the end of image. */
    static final byte[] IMAGE = concat(segment(0xFFDB, filled(65, 7)), segment(0xFFDA, filled(6, 1)),
            filled(32, 0x5A), new byte[]{(byte) 0xFF, (byte) 0xD9});

    /** A tiny JPEG to embed as the EXIF thumbnail. */
    static final byte[] THUMBNAIL = concat(new byte[]{(byte) 0xFF, (byte) 0xD8}, segment(0xFFDB, filled(65, 3)),
            segment(0xFFDA, filled(6, 2)), filled(10, 0x33), new byte[]{(byte) 0xFF, (byte) 0xD9});

    /** A JPEG: start of image, the EXIF segment of {@code tiff}, an XMP segment and {@link #IMAGE}. */
    static byte[] jpeg(byte[] tiff) {
        return concat(new byte[]{(byte) 0xFF, (byte) 0xD8},
                segment(0xFFE1, concat("Exif\0\0".getBytes(StandardCharsets.US_ASCII), tiff)), XMP_SEGMENT, IMAGE);
    }

    /** A JPEG without EXIF segment. */
    static byte[] jpegWithoutExif() {
        return concat(new byte[]{(byte) 0xFF, (byte) 0xD8}, XMP_SEGMENT, IMAGE);
    }

    private static byte[] segment(int marker, byte[] payload) {
        return concat(new byte[]{(byte) (marker >> 8), (byte) marker,
                (byte) ((payload.length + 2) >> 8), (byte) (payload.length + 2)}, payload);
    }

    private static byte[] filled(int length, int value) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}

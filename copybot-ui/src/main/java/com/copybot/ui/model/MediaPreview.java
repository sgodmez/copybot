package com.copybot.ui.model;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The preview of a file of the plan (double click on a row), best effort: what JavaFX shows by itself (JPEG, PNG,
 * GIF, BMP; MP4, MP3, WAV, AIFF), the other files are opened with the application of the system.
 */
public final class MediaPreview {

    /** What the preview shows of a file, from its extension. */
    public enum Kind {
        IMAGE, VIDEO, AUDIO,
        /** Not shown here (RAW, HEIC, documents...): the application of the system opens it. */
        OTHER
    }

    private static final Set<String> IMAGES = Set.of("jpg", "jpeg", "jpe", "jfif", "png", "gif", "bmp");
    // mov: H.264 in a QuickTime container often plays, else the preview offers the application of the system
    private static final Set<String> VIDEOS = Set.of("mp4", "m4v", "mov");
    private static final Set<String> AUDIOS = Set.of("mp3", "m4a", "wav", "aif", "aiff");

    /** The EXIF of a JPEG comes first, a few KB: no need to read the picture to find it. */
    private static final int MAX_EXIF_SEARCH = 256 * 1024;
    private static final int ORIENTATION_TAG = 0x0112;

    private MediaPreview() {
    }

    public static Kind kindOf(Path file) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (IMAGES.contains(extension)) {
            return Kind.IMAGE;
        }
        if (VIDEOS.contains(extension)) {
            return Kind.VIDEO;
        }
        return AUDIOS.contains(extension) ? Kind.AUDIO : Kind.OTHER;
    }

    /**
     * The rows the previous / next buttons go through, in the order of the table: the ones with a local file
     * shown here (from a photo, its sidecar files are skipped), and the clicked one in any case.
     */
    public static <T> List<T> navigable(List<T> rows, T clicked, Function<T, Optional<Path>> file) {
        return rows.stream()
                .filter(row -> row == clicked || file.apply(row).map(path -> kindOf(path) != Kind.OTHER).orElse(false))
                .toList();
    }

    /** A position or a duration of a video or a sound: 1:05, 1:00:00 (0:00 while unknown). */
    public static String clock(double seconds) {
        long whole = Double.isFinite(seconds) ? (long) Math.max(0, seconds) : 0;
        long hours = whole / 3600;
        long minutes = whole / 60 % 60;
        return hours > 0
                ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, whole % 60)
                : String.format(Locale.ROOT, "%d:%02d", minutes, whole % 60);
    }

    /**
     * How to turn a picture to show it upright, from its EXIF orientation (1 to 8): mirrored horizontally first,
     * then turned clockwise by rotate degrees, like the scale and rotate of a JavaFX node.
     */
    public record Orientation(int rotate, boolean mirror) {
        public static final Orientation UPRIGHT = new Orientation(0, false);

        public static Orientation ofExif(int value) {
            return switch (value) {
                case 2 -> new Orientation(0, true);
                case 3 -> new Orientation(180, false);
                case 4 -> new Orientation(180, true);
                case 5 -> new Orientation(270, true);
                case 6 -> new Orientation(90, false);
                case 7 -> new Orientation(90, true);
                case 8 -> new Orientation(270, false);
                default -> UPRIGHT;
            };
        }

        /** Once turned, the width and height of the picture are exchanged. */
        public boolean quarterTurn() {
            return rotate % 180 != 0;
        }
    }

    /**
     * The orientation of a JPEG, from the orientation tag of the first directory of its EXIF: upright when it has
     * none, or when it is not a JPEG or cannot be read (JavaFX ignores the tag: a phone photo is shown lying).
     */
    public static Orientation orientationOf(InputStream jpeg) {
        try {
            return Orientation.ofExif(exifOrientation(new DataInputStream(jpeg)));
        } catch (IOException | RuntimeException e) {
            return Orientation.UPRIGHT; // best effort: shown as stored
        }
    }

    private static int exifOrientation(DataInputStream in) throws IOException {
        if (in.readUnsignedShort() != 0xFFD8) {
            return 1;
        }
        for (int read = 2; read < MAX_EXIF_SEARCH; ) {
            int marker = in.readUnsignedShort();
            if ((marker & 0xFF00) != 0xFF00 || marker == 0xFFDA) {
                return 1; // not a segment, or the picture data starts: no EXIF
            }
            byte[] segment = new byte[in.readUnsignedShort() - 2];
            in.readFully(segment);
            read += 4 + segment.length;
            if (marker == 0xFFE1 && segment.length > 6
                    && new String(segment, 0, 6, StandardCharsets.ISO_8859_1).equals("Exif\0\0")) {
                return tiffOrientation(ByteBuffer.wrap(segment, 6, segment.length - 6).slice());
            }
        }
        return 1;
    }

    /** The orientation tag of the first directory of the TIFF structure of the EXIF. */
    private static int tiffOrientation(ByteBuffer tiff) {
        ByteOrder order = switch (tiff.getShort(0)) {
            case 0x4949 -> ByteOrder.LITTLE_ENDIAN; // "II"
            case 0x4D4D -> ByteOrder.BIG_ENDIAN; // "MM"
            default -> null;
        };
        if (order == null) {
            return 1;
        }
        tiff.order(order);
        int directory = tiff.getInt(4);
        int entries = Short.toUnsignedInt(tiff.getShort(directory));
        for (int i = 0; i < entries; i++) {
            int entry = directory + 2 + i * 12;
            if (Short.toUnsignedInt(tiff.getShort(entry)) == ORIENTATION_TAG) {
                return Short.toUnsignedInt(tiff.getShort(entry + 8)); // a SHORT, first in the value field
            }
        }
        return 1;
    }
}

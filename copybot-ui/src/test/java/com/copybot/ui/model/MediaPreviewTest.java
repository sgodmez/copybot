package com.copybot.ui.model;

import com.copybot.ui.model.MediaPreview.Kind;
import com.copybot.ui.model.MediaPreview.Orientation;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class MediaPreviewTest {

    @Test
    public void kindOfComesFromTheExtensionWhateverItsCase() {
        assertEquals(Kind.IMAGE, MediaPreview.kindOf(Path.of("dir", "IMG_0001.JPG")));
        assertEquals(Kind.IMAGE, MediaPreview.kindOf(Path.of("a.png")));
        assertEquals(Kind.IMAGE, MediaPreview.kindOf(Path.of("a.gif")));
        assertEquals(Kind.VIDEO, MediaPreview.kindOf(Path.of("clip.Mp4")));
        assertEquals(Kind.VIDEO, MediaPreview.kindOf(Path.of("clip.mov")));
        assertEquals(Kind.AUDIO, MediaPreview.kindOf(Path.of("song.mp3")));
        assertEquals(Kind.AUDIO, MediaPreview.kindOf(Path.of("memo.m4a")));
    }

    @Test
    public void rawHeicDocumentsAndNamesWithoutExtensionAreOther() {
        assertEquals(Kind.OTHER, MediaPreview.kindOf(Path.of("a.CR2")));
        assertEquals(Kind.OTHER, MediaPreview.kindOf(Path.of("a.heic")));
        assertEquals(Kind.OTHER, MediaPreview.kindOf(Path.of("a.jpg.xmp")));
        assertEquals(Kind.OTHER, MediaPreview.kindOf(Path.of("README")));
        assertEquals(Kind.OTHER, MediaPreview.kindOf(Path.of("/")));
    }

    @Test
    public void navigableKeepsTheTableOrderAndSkipsWhatIsNotShownExceptTheClickedRow() {
        Map<String, Optional<Path>> files = Map.of(
                "photo", Optional.of(Path.of("a.jpg")),
                "sidecar", Optional.of(Path.of("a.xmp")),
                "archive entry", Optional.empty(),
                "clip", Optional.of(Path.of("b.mp4")),
                "raw", Optional.of(Path.of("c.nef")));
        List<String> rows = List.of("photo", "sidecar", "archive entry", "clip", "raw");

        assertEquals(List.of("photo", "clip"), MediaPreview.navigable(rows, "photo", files::get));
        assertEquals(List.of("photo", "clip", "raw"), MediaPreview.navigable(rows, "raw", files::get));
    }

    @Test
    public void exifOrientationsTurnThenMirrorLikeTheSpecification() {
        assertEquals(new Orientation(0, false), Orientation.ofExif(1));
        assertEquals(new Orientation(0, true), Orientation.ofExif(2));
        assertEquals(new Orientation(180, false), Orientation.ofExif(3));
        assertEquals(new Orientation(180, true), Orientation.ofExif(4));
        assertEquals(new Orientation(270, true), Orientation.ofExif(5));
        assertEquals(new Orientation(90, false), Orientation.ofExif(6));
        assertEquals(new Orientation(90, true), Orientation.ofExif(7));
        assertEquals(new Orientation(270, false), Orientation.ofExif(8));
        assertEquals(Orientation.UPRIGHT, Orientation.ofExif(0));
        assertEquals(Orientation.UPRIGHT, Orientation.ofExif(42));
    }

    @Test
    public void onlyTheQuarterTurnsExchangeWidthAndHeight() {
        assertTrue(Orientation.ofExif(6).quarterTurn());
        assertTrue(Orientation.ofExif(5).quarterTurn());
        assertFalse(Orientation.ofExif(3).quarterTurn());
        assertFalse(Orientation.UPRIGHT.quarterTurn());
    }

    @Test
    public void orientationOfReadsTheTagOfALittleEndianExifAfterAJfifSegment() {
        assertEquals(new Orientation(90, false), MediaPreview.orientationOf(jpeg(ByteOrder.LITTLE_ENDIAN, 6, true)));
    }

    @Test
    public void orientationOfReadsTheTagOfABigEndianExif() {
        assertEquals(new Orientation(270, false), MediaPreview.orientationOf(jpeg(ByteOrder.BIG_ENDIAN, 8, false)));
    }

    @Test
    public void orientationOfIsUprightWithoutExifOrForAnotherFormat() {
        byte[] noExif = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xDA, 0, 2, 1, 2, 3};
        assertEquals(Orientation.UPRIGHT, MediaPreview.orientationOf(new ByteArrayInputStream(noExif)));
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        assertEquals(Orientation.UPRIGHT, MediaPreview.orientationOf(new ByteArrayInputStream(png)));
    }

    @Test
    public void orientationOfIsUprightForATruncatedFile() {
        byte[] whole = jpeg(ByteOrder.BIG_ENDIAN, 6, false).readAllBytes();
        for (int length : new int[]{0, 1, 5, 20, whole.length - 10}) {
            byte[] truncated = Arrays.copyOf(whole, length);
            assertEquals(Orientation.UPRIGHT, MediaPreview.orientationOf(new ByteArrayInputStream(truncated)),
                    "truncated at " + length);
        }
    }

    @Test
    public void clockShowsMinutesAndSecondsThenHoursWhenNeeded() {
        assertEquals("0:00", MediaPreview.clock(0));
        assertEquals("0:09", MediaPreview.clock(9.99));
        assertEquals("1:05", MediaPreview.clock(65));
        assertEquals("59:59", MediaPreview.clock(3599));
        assertEquals("1:00:00", MediaPreview.clock(3600));
        assertEquals("0:00", MediaPreview.clock(Double.NaN)); // a duration not known yet
        assertEquals("0:00", MediaPreview.clock(Double.POSITIVE_INFINITY)); // a stream
    }

    /** A JPEG start: SOI, an optional JFIF APP0, an EXIF APP1 whose first directory has 2 tags, then SOS. */
    private static ByteArrayInputStream jpeg(ByteOrder order, int orientation, boolean jfif) {
        ByteBuffer tiff = ByteBuffer.allocate(8 + 2 + 2 * 12 + 4).order(order);
        tiff.put(order == ByteOrder.LITTLE_ENDIAN ? new byte[]{'I', 'I'} : new byte[]{'M', 'M'});
        tiff.putShort((short) 42).putInt(8);
        tiff.putShort((short) 2);
        tiff.putShort((short) 0x010F).putShort((short) 2).putInt(4).putInt(0); // Make: an ASCII before
        tiff.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0);
        tiff.putInt(0); // no next directory
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xD8});
        if (jfif) {
            out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xE0, 0, 7, 'J', 'F', 'I', 'F', 0});
        }
        int length = 2 + 6 + tiff.capacity();
        out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xE1, (byte) (length >> 8), (byte) length});
        out.writeBytes(new byte[]{'E', 'x', 'i', 'f', 0, 0});
        out.writeBytes(tiff.array());
        out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xDA, 0, 2});
        return new ByteArrayInputStream(out.toByteArray());
    }
}

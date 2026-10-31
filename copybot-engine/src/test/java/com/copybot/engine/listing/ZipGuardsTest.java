package com.copybot.engine.listing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ZipGuardsTest {

    private static final byte[] DATA = "data".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path dir;

    // ---- overlap ----

    @Test
    void ordinaryZipIsAccepted() throws IOException {
        Path zip = dir.resolve("ok.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (int i = 0; i < 5; i++) {
                out.putNextEntry(new ZipEntry("f" + i + ".txt"));
                out.write(("content " + i).getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        assertDoesNotThrow(() -> ZipLayout.checkNoOverlap(zip));
    }

    @Test
    void forcedZip64FieldsAreAccepted() throws IOException {
        Path zip = dir.resolve("zip64.zip");
        Files.write(zip, handMade(List.of("a.txt", "b.txt"), false, true));
        assertDoesNotThrow(() -> ZipLayout.checkNoOverlap(zip));
    }

    @Test
    void forcedZip64OverlapIsRefused() throws IOException {
        Path zip = dir.resolve("zip64-overlap.zip");
        Files.write(zip, handMade(List.of("a.txt", "b.txt"), true, true));
        assertThrows(ZipBombException.class, () -> ZipLayout.checkNoOverlap(zip));
    }

    @Test
    void handMadeZipWithoutOverlapIsAccepted() throws IOException {
        Path zip = dir.resolve("hand.zip");
        Files.write(zip, handMade(List.of("a.txt", "b.txt"), false, false));
        assertDoesNotThrow(() -> ZipLayout.checkNoOverlap(zip));
    }

    @Test
    void twoCentralEntriesOnTheSameLocalHeaderAreRefused() throws IOException {
        Path zip = dir.resolve("overlap.zip");
        Files.write(zip, handMade(List.of("a.txt", "b.txt"), true, false));
        assertThrows(ZipBombException.class, () -> ZipLayout.checkNoOverlap(zip));
    }

    // ---- malformed input: always an IOException ----

    // layout of handMade(["a.txt", "b.txt"], false, zip64): local headers 2 x 39 bytes, central directory at 78
    private static final int CD_START = 78;
    private static final int ZIP64_COMPRESSED = CD_START + 46 + 5 + 4 + 8;
    private static final int ZIP64_LOCAL_OFFSET = ZIP64_COMPRESSED + 8;
    private static final int ZIP64_EOCD = CD_START + 2 * (46 + 5 + 28);

    private void assertCorrupt(byte[] bytes) throws IOException {
        Path zip = dir.resolve("corrupt.zip");
        Files.write(zip, bytes);
        assertThrows(IOException.class, () -> ZipLayout.checkNoOverlap(zip));
    }

    private static byte[] zip64() throws IOException {
        return handMade(List.of("a.txt", "b.txt"), false, true);
    }

    private static byte[] plain() throws IOException {
        return handMade(List.of("a.txt", "b.txt"), false, false);
    }

    private static byte[] withLong(byte[] bytes, int pos, long value) {
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putLong(pos, value);
        return bytes;
    }

    @Test
    void truncatedFileIsRejected() throws IOException {
        assertCorrupt(java.util.Arrays.copyOf(plain(), 10));
    }

    @Test
    void missingEndRecordIsRejected() throws IOException {
        assertCorrupt(new byte[64]);
    }

    @Test
    void garbageCentralSignatureIsRejected() throws IOException {
        byte[] bytes = plain();
        bytes[CD_START] = 0;
        assertCorrupt(bytes);
    }

    @Test
    void centralDirectoryBeyondTheFileIsRejected() throws IOException {
        byte[] bytes = plain();
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.length - 22 + 16, 1_000_000);
        assertCorrupt(bytes);
    }

    @Test
    void overflowingZip64CentralDirectorySizesAreRejected() throws IOException {
        byte[] bytes = zip64();
        withLong(bytes, ZIP64_EOCD + 32, 0x7FFF_FFFFL);
        withLong(bytes, ZIP64_EOCD + 40, 0x7FFF_FFFF_FFFF_FFF0L);
        withLong(bytes, ZIP64_EOCD + 48, 0x7FFF_FFFF_FFFF_FFF0L);
        assertCorrupt(bytes);
    }

    @Test
    void negativeZip64LocalOffsetIsRejected() throws IOException {
        assertCorrupt(withLong(zip64(), ZIP64_LOCAL_OFFSET, -5L));
    }

    @Test
    void negativeZip64CompressedSizeIsRejected() throws IOException {
        assertCorrupt(withLong(zip64(), ZIP64_COMPRESSED, -5L));
    }

    // ---- ratio ----

    @Test
    void unknownCompressedSizeIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new RatioGuardInputStream(new ByteArrayInputStream(new byte[0]), -1, "x"));
    }

    @Test
    void entryProducingMoreThanItsSmallDeclaredSizeIsRefused() throws IOException {
        byte[] zeros = new byte[2 * 1024 * 1024];
        try (InputStream in = new RatioGuardInputStream(new ByteArrayInputStream(zeros), 100, "x")) {
            assertThrows(ZipBombException.class, in::readAllBytes);
        }
    }

    @Test
    void twoMegabytesOfZerosAreRefused() throws IOException {
        Path zip = zipOfZeros(2 * 1024 * 1024);
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            ZipEntry e = zf.getEntry("z.bin");
            try (InputStream in = new RatioGuardInputStream(zf.getInputStream(e), e.getCompressedSize(), "z.bin")) {
                assertThrows(ZipBombException.class, in::readAllBytes);
            }
        }
    }

    @Test
    void nineHundredKilobytesOfZerosAreAcceptedBelowTheFloor() throws IOException {
        Path zip = zipOfZeros(900 * 1024);
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            ZipEntry e = zf.getEntry("z.bin");
            try (InputStream in = new RatioGuardInputStream(zf.getInputStream(e), e.getCompressedSize(), "z.bin")) {
                assertDoesNotThrow(in::readAllBytes);
            }
        }
    }

    // ---- helpers ----

    private Path zipOfZeros(int size) throws IOException {
        Path zip = dir.resolve("zeros-" + size + ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("z.bin"));
            out.write(new byte[size]);
            out.closeEntry();
        }
        return zip;
    }

    /**
     * Writes a ZIP by hand with stored entries. With {@code overlap}, every central entry points to the first local
     * header. With {@code zip64}, sizes and offsets live in ZIP64 extra fields and the end record is ZIP64.
     */
    private static byte[] handMade(List<String> names, boolean overlap, boolean zip64) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        for (String name : names) {
            offsets.add(out.size());
            byte[] n = name.getBytes(StandardCharsets.UTF_8);
            ByteBuffer h = le(30);
            h.putInt(0x04034b50).putShort((short) 20).putShort((short) 0).putShort((short) 0).putInt(0).putInt(0)
                    .putInt(DATA.length).putInt(DATA.length).putShort((short) n.length).putShort((short) 0);
            out.write(h.array());
            out.write(n);
            out.write(DATA);
        }
        int cdStart = out.size();
        for (int i = 0; i < names.size(); i++) {
            byte[] n = names.get(i).getBytes(StandardCharsets.UTF_8);
            int offset = overlap ? offsets.get(0) : offsets.get(i);
            ByteBuffer c = le(46);
            c.putInt(0x02014b50).putShort((short) 45).putShort((short) 20).putShort((short) 0).putShort((short) 0)
                    .putInt(0).putInt(0).putInt(zip64 ? -1 : DATA.length).putInt(zip64 ? -1 : DATA.length)
                    .putShort((short) n.length).putShort((short) (zip64 ? 28 : 0)).putShort((short) 0)
                    .putShort((short) 0).putShort((short) 0).putInt(0).putInt(zip64 ? -1 : offset);
            out.write(c.array());
            out.write(n);
            if (zip64) {
                ByteBuffer x = le(28);
                x.putShort((short) 1).putShort((short) 24).putLong(DATA.length).putLong(DATA.length).putLong(offset);
                out.write(x.array());
            }
        }
        int cdSize = out.size() - cdStart;
        if (zip64) {
            int eocd64Pos = out.size();
            ByteBuffer r = le(56);
            r.putInt(0x06064b50).putLong(44).putShort((short) 45).putShort((short) 45).putInt(0).putInt(0)
                    .putLong(names.size()).putLong(names.size()).putLong(cdSize).putLong(cdStart);
            out.write(r.array());
            ByteBuffer loc = le(20);
            loc.putInt(0x07064b50).putInt(0).putLong(eocd64Pos).putInt(1);
            out.write(loc.array());
            ByteBuffer e = le(22);
            e.putInt(0x06054b50).putShort((short) 0).putShort((short) 0).putShort((short) -1).putShort((short) -1)
                    .putInt(-1).putInt(-1).putShort((short) 0);
            out.write(e.array());
        } else {
            ByteBuffer e = le(22);
            e.putInt(0x06054b50).putShort((short) 0).putShort((short) 0).putShort((short) names.size())
                    .putShort((short) names.size()).putInt(cdSize).putInt(cdStart).putShort((short) 0);
            out.write(e.array());
        }
        return out.toByteArray();
    }

    private static ByteBuffer le(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }
}

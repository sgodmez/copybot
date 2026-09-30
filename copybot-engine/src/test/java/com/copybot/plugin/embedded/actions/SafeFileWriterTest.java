package com.copybot.plugin.embedded.actions;

import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Temporary file, direct write, orphans and verification (spec safe-write §3, §4). */
public class SafeFileWriterTest {

    private static final String RUN = "run1";

    @TempDir
    Path tempDir;

    private Path nas() throws IOException {
        return Files.createDirectories(tempDir.resolve("nas"));
    }

    private static SafeFileWriter writer(String actionConfig) {
        return new SafeFileWriter(FileWriteSettings.of(
                new Gson().fromJson("{\"outPattern\":\"x\"" + actionConfig + "}", FileWriteConfig.class)));
    }

    private WorkItem local(String content) throws IOException {
        Path file = Files.writeString(Files.createDirectories(tempDir.resolve("card")).resolve("photo.jpg"), content);
        WorkItem item = new WorkItem(file);
        item.getMetadatas().setSize(content.length());
        return item;
    }

    /** A card pulled out mid-copy: failAfter zero bytes, then an IOException. */
    private static InputStream failingAfter(int failAfter) {
        return new InputStream() {
            private int served;

            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (served >= failAfter) {
                    throw new IOException("card removed");
                }
                int n = Math.min(len, failAfter - served);
                Arrays.fill(b, off, off + n, (byte) 0);
                served += n;
                return n;
            }
        };
    }

    private WorkItem failing(int size, int failAfter) throws IOException {
        WorkItem item = new WorkItem(tempDir.toUri().toURL(), () -> failingAfter(failAfter));
        item.getMetadatas().setSize(size);
        return item;
    }

    private static List<String> names(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    public void tempAndRenameLeavesOnlyTheTargetAndReturnsTheSha256() throws IOException {
        Path target = nas().resolve("photo.jpg");

        SafeFileWriter.Written written = writer("").write(local("abc"), target, false, RUN, p -> {
        });

        assertEquals("abc", Files.readString(target));
        assertEquals(List.of("photo.jpg"), names(nas()), "no temporary file left");
        assertEquals(3, written.size());
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", written.sha256());
    }

    @Test
    public void theWriteCreatesTheTargetDirectoryAndKeepsTheSourceDate() throws IOException {
        Path target = tempDir.resolve("nas").resolve("2026").resolve("photo.jpg");
        WorkItem item = local("abc");
        Instant shot = Instant.parse("2026-09-01T10:00:00Z");
        item.getMetadatas().setTime(WorkItemMetadata.LAST_MODIFIED, shot);

        writer("").write(item, target, false, RUN, p -> {
        });

        assertEquals(shot, Files.getLastModifiedTime(target).toInstant());
    }

    @Test
    public void aFailingStreamLeavesNeitherTemporaryNorTarget() throws IOException {
        Path target = nas().resolve("photo.jpg");

        assertThrows(IOException.class, () -> writer("").write(failing(40000, 20000), target, false, RUN, p -> {
        }));

        assertEquals(List.of(), names(nas()));
    }

    @Test
    public void aFailedOverwriteThroughATemporaryKeepsTheOriginal() throws IOException {
        Path target = Files.writeString(nas().resolve("photo.jpg"), "original");

        assertThrows(IOException.class, () -> writer("").write(failing(40000, 20000), target, true, RUN, p -> {
        }));

        assertEquals("original", Files.readString(target));
        assertEquals(List.of("photo.jpg"), names(nas()));
    }

    @Test
    public void aTemporaryNameCollisionFailsThisItemAndLeavesTheOtherTemporaryUntouched() throws IOException {
        Path target = nas().resolve("photo.jpg");
        Path otherTemp = Files.writeString(nas().resolve(".photo.jpg." + RUN + SafeFileWriter.TEMP_SUFFIX),
                "other item in progress");

        for (boolean replaceExisting : List.of(false, true)) {
            assertThrows(FileAlreadyExistsException.class, () -> writer("").write(local("abc"), target, replaceExisting, RUN, p -> {
            }), "replaceExisting=" + replaceExisting);

            assertEquals("other item in progress", Files.readString(otherTemp), "replaceExisting=" + replaceExisting);
            assertFalse(Files.exists(target), "replaceExisting=" + replaceExisting);
        }
    }

    @Test
    public void directDeletesThePartialFileItCreated() throws IOException {
        Path target = nas().resolve("photo.jpg");

        assertThrows(IOException.class, () -> writer(",\"writeMode\":\"direct\"").write(failing(40000, 20000), target, false, RUN, p -> {
        }));

        assertEquals(List.of(), names(nas()));
    }

    @Test
    public void directOverwriteLeavesTheFileItDidNotCreate() throws IOException {
        Path target = Files.writeString(nas().resolve("photo.jpg"), "original");

        assertThrows(IOException.class, () -> writer(",\"writeMode\":\"direct\"").write(failing(40000, 20000), target, true, RUN, p -> {
        }));

        assertTrue(Files.exists(target), "the original was lost when the write started, the partial file stays");
    }

    @Test
    public void directWritesUnderTheFinalName() throws IOException {
        Path target = nas().resolve("photo.jpg");

        writer(",\"writeMode\":\"direct\"").write(local("abc"), target, false, RUN, p -> {
        });

        assertEquals("abc", Files.readString(target));
        assertEquals(List.of("photo.jpg"), names(nas()));
    }

    @Test
    public void anExistingTargetIsNeverReplacedWithoutReplaceExisting() throws IOException {
        Path target = Files.writeString(nas().resolve("photo.jpg"), "original");

        for (String mode : List.of("", ",\"writeMode\":\"direct\"")) {
            assertThrows(FileAlreadyExistsException.class, () -> writer(mode).write(local("abc"), target, false, RUN, p -> {
            }), mode);
            assertEquals("original", Files.readString(target), mode);
            assertEquals(List.of("photo.jpg"), names(nas()), mode);
        }
    }

    @Test
    public void orphansOfAnotherRunAreDeletedThoseOfTheCurrentRunNever() throws IOException {
        Path orphan = Files.writeString(nas().resolve(".old.jpg.crashed" + SafeFileWriter.TEMP_SUFFIX), "partial");
        Path ours = Files.writeString(nas().resolve(".other.jpg." + RUN + SafeFileWriter.TEMP_SUFFIX), "in progress");
        Path unrelated = Files.writeString(nas().resolve(".keep.tmp"), "not ours");

        writer("").write(local("abc"), nas().resolve("photo.jpg"), false, RUN, p -> {
        });

        assertFalse(Files.exists(orphan));
        assertTrue(Files.exists(ours), "a temporary file of the current run is never deleted");
        assertTrue(Files.exists(unrelated));
    }

    @Test
    public void onlyTheFirstWriteOfTheRunInADirectoryCleansIt() throws IOException {
        SafeFileWriter writer = writer("");
        writer.write(local("abc"), nas().resolve("a.jpg"), false, RUN, p -> {
        });
        Path lateOrphan = Files.writeString(nas().resolve(".old.jpg.crashed" + SafeFileWriter.TEMP_SUFFIX), "partial");

        writer.write(local("abc"), nas().resolve("b.jpg"), false, RUN, p -> {
        });
        assertTrue(Files.exists(lateOrphan), "already cleaned for this run");

        writer.write(local("abc"), nas().resolve("c.jpg"), false, "run2", p -> {
        });
        assertFalse(Files.exists(lateOrphan), "the next run cleans again");
    }

    @Test
    public void theRunIdIsTheLastSegmentOfATemporaryName() {
        assertEquals("run1", SafeFileWriter.runIdOf(".IMG.0001.JPG.run1" + SafeFileWriter.TEMP_SUFFIX));
    }

    /** Test hook: rewrites the written file with other bytes of the same length before the verification. */
    private static void alter(Path file) {
        try {
            byte[] content = Files.readAllBytes(file);
            content[0] ^= 0x55;
            Files.write(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void append(Path file) {
        try {
            Files.writeString(file, "!", StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    public void readBackDetectsAnAlteredTargetAndDeletesIt() throws IOException {
        for (String mode : List.of("", ",\"writeMode\":\"direct\"")) {
            SafeFileWriter writer = writer(",\"verify\":\"readBack\"" + mode);
            writer.setBeforeVerify(SafeFileWriterTest::alter);

            assertThrows(CopybotException.class, () -> writer.write(local("abc"), nas().resolve("photo.jpg"), false, RUN, p -> {
            }), mode);

            assertEquals(List.of(), names(nas()), mode);
        }
    }

    @Test
    public void sizeDetectsAWrongSizeButNotAnAlteredContent() throws IOException {
        SafeFileWriter wrongSize = writer("");
        wrongSize.setBeforeVerify(SafeFileWriterTest::append);
        assertThrows(CopybotException.class, () -> wrongSize.write(local("abc"), nas().resolve("a.jpg"), false, RUN, p -> {
        }));
        assertFalse(Files.exists(nas().resolve("a.jpg")));

        SafeFileWriter altered = writer("");
        altered.setBeforeVerify(SafeFileWriterTest::alter);
        altered.write(local("abc"), nas().resolve("b.jpg"), false, RUN, p -> {
        });
        assertTrue(Files.exists(nas().resolve("b.jpg")), "only readBack reads the content again");
    }

    @Test
    public void aSecondWriteToATargetBeingWrittenFailsBeforeOpeningAnything() throws IOException {
        for (String mode : List.of("", ",\"writeMode\":\"direct\"")) {
            Path target = nas().resolve("photo" + mode.length() + ".jpg");
            Files.writeString(target, "original");
            SafeFileWriter writer = writer(mode);
            AtomicReference<Throwable> second = new AtomicReference<>();
            AtomicBoolean fired = new AtomicBoolean();
            writer.setBeforeVerify(file -> {
                if (fired.getAndSet(true)) {
                    return; // once: the second write must not recurse through this hook
                }
                try {
                    writer.write(local("xyz"), target, true, RUN, p -> {
                    });
                } catch (Throwable e) {
                    second.set(e);
                }
            });

            writer.write(local("abc"), target, true, RUN, p -> {
            });

            assertInstanceOf(FileAlreadyExistsException.class, second.get(), mode);
            assertEquals("abc", Files.readString(target), mode);
            assertEquals(List.of(target.getFileName().toString()), names(nas()).stream()
                    .filter(n -> n.contains(target.getFileName().toString())).toList(), mode);

            writer.setBeforeVerify(file -> {
            });
            writer.write(local("def"), target, true, RUN, p -> {
            });
            assertEquals("def", Files.readString(target), "released after the write: " + mode);
            Files.delete(target);
        }
    }

    @Test
    public void aDirectoryNamedLikeATemporaryOfAnotherRunIsNotDeleted() throws IOException {
        Path dir = Files.createDirectories(nas().resolve(".x.crashed" + SafeFileWriter.TEMP_SUFFIX));

        writer("").write(local("abc"), nas().resolve("photo.jpg"), false, RUN, p -> {
        });

        assertTrue(Files.isDirectory(dir));
    }

    @Test
    public void aFailedVerificationOfAnOverwriteThroughATemporaryKeepsTheOriginal() throws IOException {
        Path target = Files.writeString(nas().resolve("photo.jpg"), "original");
        SafeFileWriter writer = writer("");
        writer.setBeforeVerify(SafeFileWriterTest::append);

        assertThrows(CopybotException.class, () -> writer.write(local("abc"), target, true, RUN, p -> {
        }));

        assertEquals("original", Files.readString(target));
        assertEquals(List.of("photo.jpg"), names(nas()));
    }

    @Test
    public void deleteSourceWritesAndForcesTheCopyInBothModes() throws IOException {
        for (String mode : List.of("", ",\"writeMode\":\"direct\"")) {
            Path target = nas().resolve("photo" + mode.length() + ".jpg");

            SafeFileWriter.Written written = writer(",\"deleteSource\":true" + mode).write(local("abc"), target, false, RUN, p -> {
            });

            assertEquals("abc", Files.readString(target), mode);
            assertEquals(3, written.size(), mode);
        }
    }

    @Test
    public void noneOnlyChecksTheBytesWritten() throws IOException {
        SafeFileWriter none = writer(",\"verify\":\"none\"");
        none.setBeforeVerify(SafeFileWriterTest::append);
        none.write(local("abc"), nas().resolve("a.jpg"), false, RUN, p -> {
        });
        assertTrue(Files.exists(nas().resolve("a.jpg")), "the destination is not read again");

        WorkItem announcedLonger = local("abc");
        announcedLonger.getMetadatas().setSize(999); // the listing announced 999 bytes, the copy got 3
        assertThrows(CopybotException.class, () -> none.write(announcedLonger, nas().resolve("b.jpg"), false, RUN, p -> {
        }));
        assertFalse(Files.exists(nas().resolve("b.jpg")));
    }

    @Test
    public void aDanglingLinkAtTheTargetCountsAsExistingAndIsNeverCopiedOver() throws IOException {
        Path link = nas().resolve("photo.jpg");
        try {
            Files.createSymbolicLink(link, tempDir.resolve("nowhere.jpg"));
        } catch (IOException | UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.abort("symbolic links cannot be created here: " + e);
        }
        AtomicBoolean opened = new AtomicBoolean();
        WorkItem item = new WorkItem(tempDir.toUri().toURL(), () -> {
            opened.set(true);
            return InputStream.nullInputStream();
        });

        assertThrows(FileAlreadyExistsException.class, () -> writer("").write(item, link, false, RUN, p -> {
        }));

        assertFalse(opened.get(), "refused before any copy, like any existing target");
        assertTrue(Files.isSymbolicLink(link));
        assertFalse(Files.exists(tempDir.resolve("nowhere.jpg")));
        assertEquals(List.of("photo.jpg"), names(nas()));
    }
}

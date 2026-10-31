package com.copybot.plugin.remote.ftp;

import com.copybot.engine.plugin.ConfirmationHandler;
import com.copybot.engine.plugin.Confirmations;
import com.copybot.engine.plugin.PluginDataRoot;
import com.copybot.plugin.api.action.ListingSink;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import com.copybot.plugin.api.action.WriteContext;
import com.copybot.plugin.api.action.WriteResult;
import com.copybot.plugin.api.config.ConfigField;
import com.copybot.plugin.api.config.ConfigSchema;
import com.copybot.plugin.api.config.FieldHint;
import com.copybot.plugin.remote.RemotePlugin;
import com.copybot.plugin.remote.core.RemoteEntry;
import com.copybot.resources.CombinedResourceBundle;
import com.google.gson.JsonParser;
import org.apache.ftpserver.command.Command;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.X509ExtendedTrustManager;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** ftp.read and ftp.write against an embedded FTP / FTPS server (Apache FtpServer): no external network. */
public class FtpTest {

    private static final Instant DATE = Instant.parse("2024-05-01T10:15:30Z");
    private static final WriteContext CONTEXT = new WriteContext("run1");

    @TempDir
    static Path keys;
    /** A certificate naming 127.0.0.1, one naming another server, one naming 127.0.0.1 long expired. */
    private static Path serverStore;
    private static Path otherStore;
    private static Path expiredStore;

    @TempDir
    Path temp;

    private Path root;
    private Path data;
    private FtpTestServer server;
    /** The answers about the certificates this computer does not trust, for {@link #connect}. */
    private CertificateTrust certificates;

    private final List<String> questions = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean answer;
    private volatile long answerDelayMillis;
    private ConfirmationHandler previousHandler;

    @BeforeAll
    public static void certificates() throws Exception {
        serverStore = FtpTestServer.keystore(keys, "server", "ip:127.0.0.1");
        otherStore = FtpTestServer.keystore(keys, "other", "dns:other.example");
        expiredStore = FtpTestServer.keystore(keys, "expired", "ip:127.0.0.1", "2020/01/01");
    }

    @BeforeEach
    public void setUp() throws Exception {
        root = Files.createDirectories(temp.resolve("server"));
        data = temp.resolve("data");
        PluginDataRoot.set(data);
        previousHandler = Confirmations.setHandler(question -> {
            questions.add(question);
            return CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(answerDelayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return answer;
            });
        });
        certificates = new CertificateTrust(plugin());
        server = FtpTestServer.start(root);
    }

    @AfterEach
    public void tearDown() {
        server.close();
        Confirmations.setHandler(previousHandler);
        PluginDataRoot.set(null);
    }

    // ---- helpers

    private static RemotePlugin plugin() {
        RemotePlugin plugin = new RemotePlugin();
        CombinedResourceBundle bundle = new CombinedResourceBundle(RemotePlugin.class.getModule(), null);
        plugin.getI18nBundleNames().forEach(bundle::addBundle);
        bundle.load(Locale.ROOT); // the English texts, whatever the language of this computer
        plugin.setResourceBundle(bundle);
        return plugin;
    }

    private String connection(String security) {
        return "host: '127.0.0.1', port: " + server.port() + ", user: '" + FtpTestServer.USER + "', password: '"
                + FtpTestServer.PASSWORD + "', security: '" + security + "'";
    }

    private FtpRead readJson(String json) {
        FtpRead action = new FtpRead();
        action.setPlugin(plugin());
        action.loadConfig(JsonParser.parseString(json));
        return action;
    }

    private FtpWrite writeJson(String json) {
        FtpWrite action = new FtpWrite();
        action.setPlugin(plugin());
        action.loadConfig(JsonParser.parseString(json));
        return action;
    }

    private FtpRead read(String folder, String extra) {
        return readJson("{" + connection("none") + ", folder: '" + folder + "'" + extra + "}");
    }

    private FtpWrite write(String extra) {
        return writeJson("{" + connection("none") + ", outPattern: '/out/{name}'" + extra + "}");
    }

    /** A connection of its own, outside an action. */
    private FtpFs connect(String security, X509ExtendedTrustManager trust) throws IOException {
        FtpReadConfig config = new FtpReadConfig("127.0.0.1", server.port(), FtpTestServer.USER,
                FtpTestServer.PASSWORD, security, true, "/", true, null, null, null, 1);
        return FtpFs.connect(config, FtpConnections.security(null, "ftp.read", config), trust, certificates,
                plugin());
    }

    /** The plugin's trusted certificates file. */
    private Path trustFile() {
        return data.resolve("remote").resolve("ftps_trust");
    }

    /** The key fingerprint of the certificate of a key store. */
    private static String pin(Path keystore) throws Exception {
        return CertificateTrust.pin(FtpTestServer.certificate(keystore));
    }

    /** Records the certificate of a key store as trusted by the user for the test server. */
    private void trustAsUser(Path keystore) throws Exception {
        Files.createDirectories(trustFile().getParent());
        Files.writeString(trustFile(), "# trusted\r\n127.0.0.1:" + server.port() + " " + pin(keystore) + "\r\n");
    }

    private static List<WorkItem> list(FtpRead read) {
        List<WorkItem> items = new ArrayList<>();
        read.listFiles(items::add);
        return items;
    }

    private static WorkItem item(String name, String content, Instant date) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return WorkItem.remote(name, "/src", "test://src/" + name, bytes.length, date,
                () -> new ByteArrayInputStream(bytes), () -> { });
    }

    private static String content(WorkItem item) throws IOException {
        try (InputStream in = item.openInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Instant date(WorkItem item) {
        return item.getMetadatas().getTime(WorkItemMetadata.LAST_MODIFIED).orElseThrow();
    }

    private void putFile(String path, String content, Instant date) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        Files.setLastModifiedTime(file, FileTime.from(date));
    }

    private List<String> filesUnder(String folder) throws IOException {
        if (!Files.exists(root.resolve(folder))) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(root.resolve(folder))) {
            return files.filter(Files::isRegularFile).map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .sorted().toList();
        }
    }

    private void restart(FtpTestServer.Tls tls, Path keystore, String protocol, Map<String, Command> commands)
            throws Exception {
        restart(tls, keystore, null, protocol, commands);
    }

    private void restart(FtpTestServer.Tls tls, Path keystore, Path dataKeystore, String protocol,
                         Map<String, Command> commands) throws Exception {
        server.close();
        server = FtpTestServer.start(root, tls, keystore, dataKeystore, protocol, commands);
    }

    /** A content failing after 50,000 bytes. */
    private static InputStream failingSource() {
        return new InputStream() {
            private int count;

            @Override
            public int read() throws IOException {
                if (++count > 50_000) {
                    throw new IOException("disk gone");
                }
                return 1;
            }
        };
    }

    // ---- listing and reading

    @Test
    public void readsATreeWithSizesDatesAndContents() throws Exception {
        putFile("in/a.jpg", "hello", DATE);
        putFile("in/sub/b.png", "world!", DATE.plusSeconds(60));
        putFile("in/.hidden", "x", DATE);
        FtpRead read = read("/in", "");
        List<WorkItem> items = new ArrayList<>();
        List<String> done = new ArrayList<>();
        read.listFiles(new ListingSink() {
            @Override
            public void item(WorkItem item) {
                items.add(item);
            }

            @Override
            public void folderDone(String folder) {
                done.add(folder);
            }
        });
        assertEquals(List.of("a.jpg", "b.png"), items.stream().map(WorkItem::getNameDisplay).toList());
        assertEquals(List.of("/in/sub", "/in"), done);
        WorkItem a = items.get(0);
        assertEquals("ftp://127.0.0.1/in/a.jpg", a.getSourceLocationDisplay());
        assertEquals(5L, a.getMetadatas().getSize());
        assertEquals(DATE, date(a));
        assertEquals(DATE.plusSeconds(60), date(items.get(1)));
        assertEquals("hello", content(a));
        assertEquals("world!", content(items.get(1)));
        assertEquals("hello", content(a), "each open reads again");
        assertTrue(read.signalsFolders());
        read.afterAll();
    }

    @Test
    public void aRelativeFolderIsUnderTheStartingFolder() throws Exception {
        putFile("in/a.jpg", "hello", DATE);
        List<WorkItem> items = list(read("in", ""));
        assertEquals(1, items.size());
        assertEquals("hello", content(items.getFirst()));
    }

    @Test
    public void aMissingFolderFailsTheListing() {
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> list(read("/nothing", "")));
        assertTrue(failure.getMessage().contains("ftp://127.0.0.1/nothing"), failure.getMessage());
        assertTrue(failure.getMessage().contains("the server answered 550"), failure.getMessage());
    }

    @Test
    public void theSourceDeleterDeletesTheRemoteFile() throws Exception {
        putFile("in/a.jpg", "hello", DATE);
        WorkItem a = list(read("/in", "")).getFirst();
        a.getSourceDeleter().orElseThrow().delete();
        assertFalse(Files.exists(root.resolve("in/a.jpg")));
    }

    @Test
    public void aServerWithoutMlsdIsListedByListWithTheDatesOfMdtm() throws Exception {
        restart(FtpTestServer.Tls.NONE, null, null, Map.of("MLSD", FtpTestServer.NOT_IMPLEMENTED));
        putFile("in/a.jpg", "hello", DATE);
        putFile("in/sub/b.png", "world!", DATE.plusSeconds(61));
        List<WorkItem> items = list(read("/in", ""));
        assertEquals(List.of("a.jpg", "b.png"), items.stream().map(WorkItem::getNameDisplay).toList());
        assertEquals(DATE, date(items.get(0)), "to the second, not the minute of LIST");
        assertEquals(DATE.plusSeconds(61), date(items.get(1)));
        assertEquals("world!", content(items.get(1)));
    }

    @Test
    public void anOldServerIsListedByListAndKeepsItsOwnDates() throws Exception {
        restart(FtpTestServer.Tls.NONE, null, null, Map.of("FEAT", FtpTestServer.OLD_FEAT,
                "MLSD", FtpTestServer.NOT_IMPLEMENTED, "MLST", FtpTestServer.NOT_IMPLEMENTED,
                "MFMT", FtpTestServer.NOT_IMPLEMENTED));
        putFile("in/a.jpg", "hello", DATE);
        try (FtpFs fs = connect("none", null)) {
            assertFalse(fs.keepsLastModified(), "neither MFMT nor SITE UTIME");
            assertEquals(List.of(RemoteEntry.file("a.jpg", 5, DATE)), fs.list("/in"));
            assertEquals(Optional.of(RemoteEntry.file("a.jpg", 5, DATE)), fs.stat("/in/a.jpg"));
            assertEquals(Optional.of(RemoteEntry.folder("in")), fs.stat("/in"));
            assertEquals(Optional.empty(), fs.stat("/in/none.jpg"));
            assertEquals(Optional.empty(), fs.stat("/none/a.jpg"));
        }
        FtpWrite write = write("");
        assertFalse(write.write(item("b.jpg", "new", DATE), CONTEXT).isSkipped());
        assertTrue(write.write(item("b.jpg", "new", DATE), CONTEXT).isSkipped(), "found by LIST");
        assertEquals(List.of("out/b.jpg"), filesUnder("out"));
    }

    @Test
    public void aBusyAnswerIsNotTakenForAMissingFileAndNothingIsOverwritten() throws Exception {
        restart(FtpTestServer.Tls.NONE, null, null, Map.of("MLST", FtpTestServer.answering(450, "Busy, later.")));
        putFile("out/a.jpg", "old content", DATE);
        try (FtpFs fs = connect("none", null)) {
            IOException failure = assertThrows(IOException.class, () -> fs.stat("/out/a.jpg"));
            assertTrue(failure.getMessage().contains("the server answered 450"), failure.getMessage());
            assertTrue(fs.isUsable());
        }
        FtpWrite write = write(", onConflict: {ifDifferent: 'overwrite'}");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> write.write(item("a.jpg", "new", DATE), CONTEXT));
        assertTrue(failure.getMessage().contains("the server answered 450"), failure.getMessage());
        assertEquals("old content", Files.readString(root.resolve("out/a.jpg")));
        assertEquals(List.of("out/a.jpg"), filesUnder("out"), "nothing sent");
    }

    @Test
    public void onlyA550OnTheParentMeansMissingWhenListing() throws Exception {
        restart(FtpTestServer.Tls.NONE, null, null, Map.of("FEAT", FtpTestServer.OLD_FEAT,
                "MLSD", FtpTestServer.NOT_IMPLEMENTED, "MLST", FtpTestServer.NOT_IMPLEMENTED,
                "CWD", FtpTestServer.answering(450, "Busy, later.")));
        try (FtpFs fs = connect("none", null)) {
            IOException failure = assertThrows(IOException.class, () -> fs.stat("/out/a.jpg"));
            assertTrue(failure.getMessage().contains("the server answered 450"), failure.getMessage());
        }
    }

    @Test
    public void datesAreKeptOnlyWhenTheyCanBeReadAgain() throws Exception {
        // MFMT offered, but neither MLST nor MDTM: a date written could never be compared
        restart(FtpTestServer.Tls.NONE, null, null, Map.of("FEAT", FtpTestServer.feat("MFMT", "UTF8"),
                "MLSD", FtpTestServer.NOT_IMPLEMENTED, "MLST", FtpTestServer.NOT_IMPLEMENTED,
                "MDTM", FtpTestServer.NOT_IMPLEMENTED));
        try (FtpFs fs = connect("none", null)) {
            assertFalse(fs.keepsLastModified());
            fs.mkdirs("/out");
            byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
            fs.upload("/out/a.jpg", new ByteArrayInputStream(content), content.length, DATE);
            assertEquals(DATE, Files.getLastModifiedTime(root.resolve("out/a.jpg")).toInstant(), "set all the same");
        }
    }

    @Test
    public void withoutFeatMdtmIsTriedAndGivesTheDates() throws Exception {
        restart(FtpTestServer.Tls.NONE, null, null, Map.of("FEAT", FtpTestServer.NOT_IMPLEMENTED,
                "MLSD", FtpTestServer.NOT_IMPLEMENTED, "MLST", FtpTestServer.NOT_IMPLEMENTED));
        putFile("in/a.jpg", "hello", DATE);
        try (FtpFs fs = connect("none", null)) {
            assertEquals(List.of(RemoteEntry.file("a.jpg", 5, DATE)), fs.list("/in"), "to the second: MDTM");
        }
    }

    @Test
    public void aFailingOrUnknownSystIsListedAsUnix() throws Exception {
        putFile("in/a.jpg", "hello", DATE);
        for (Command syst : List.of(FtpTestServer.NOT_IMPLEMENTED, FtpTestServer.answering(215, "PLAN9 of course"))) {
            restart(FtpTestServer.Tls.NONE, null, null, Map.of("SYST", syst,
                    "MLSD", FtpTestServer.NOT_IMPLEMENTED, "MLST", FtpTestServer.NOT_IMPLEMENTED));
            try (FtpFs fs = connect("none", null)) {
                assertEquals(List.of(RemoteEntry.file("a.jpg", 5, DATE)), fs.list("/in"));
                assertEquals(Optional.of(RemoteEntry.file("a.jpg", 5, DATE)), fs.stat("/in/a.jpg"));
            }
        }
    }

    @Test
    public void aFileListedWithoutAReadableSizeIsLeftOut() throws Exception {
        putFile("in/a.jpg", "hello", DATE);
        restart(FtpTestServer.Tls.NONE, null, null, Map.of("MLSD", FtpTestServer.NOT_IMPLEMENTED,
                "MLST", FtpTestServer.NOT_IMPLEMENTED, "LIST", FtpTestServer.listing(
                        "-rw-r--r--   1 alice users                    5 May 01  2024 a.jpg\r\n"
                                + "-rw-r--r--   1 alice users 99999999999999999999 May 01  2024 huge.bin\r\n")));
        try (FtpFs fs = connect("none", null)) {
            assertEquals(List.of(RemoteEntry.file("a.jpg", 5, DATE)), fs.list("/in"), "not huge.bin, size 0");
        }
    }

    @Test
    public void aServerHidingDotNamesFromListIsAskedForThem() throws Exception {
        // vsftpd: no MLST, and LIST shows names starting with a dot only with -a (force_dot_files=NO)
        restart(FtpTestServer.Tls.NONE, null, null, Map.of("FEAT", FtpTestServer.OLD_FEAT,
                "MLSD", FtpTestServer.NOT_IMPLEMENTED, "MLST", FtpTestServer.NOT_IMPLEMENTED,
                "LIST", FtpTestServer.unixList(false)));
        putFile("out/.hidden", "same", DATE);
        FtpWrite write = write("");
        assertFalse(write.write(item("a.jpg", "hello", DATE), CONTEXT).isSkipped(), "its temporary file is found");
        assertTrue(write.write(item("a.jpg", "hello", DATE), CONTEXT).isSkipped());
        assertTrue(write.write(item(".hidden", "same", DATE), CONTEXT).isSkipped(), "an existing dot name is seen");
        write.afterAll();
        assertEquals(List.of("out/.hidden", "out/a.jpg"), filesUnder("out"));
        try (FtpFs fs = connect("none", null)) {
            assertEquals(Optional.of(RemoteEntry.file(".hidden", 4, DATE)), fs.stat("/out/.hidden"));
        }
    }

    @Test
    public void aServerRefusingListOptionsIsListedWithout() throws Exception {
        restart(FtpTestServer.Tls.NONE, null, null, Map.of("FEAT", FtpTestServer.OLD_FEAT,
                "MLSD", FtpTestServer.NOT_IMPLEMENTED, "MLST", FtpTestServer.NOT_IMPLEMENTED,
                "LIST", FtpTestServer.unixList(true)));
        putFile("in/a.jpg", "hello", DATE);
        try (FtpFs fs = connect("none", null)) {
            assertEquals(List.of(RemoteEntry.file("a.jpg", 5, DATE)), fs.list("/in"));
            assertEquals(Optional.of(RemoteEntry.file("a.jpg", 5, DATE)), fs.stat("/in/a.jpg"));
        }
    }

    @Test
    public void anUploadTheServerTakesNothingOfIsAbandoned() throws Exception {
        restart(FtpTestServer.Tls.NONE, null, null, Map.of("STOR", FtpTestServer.STALLED_STOR));
        Files.createDirectories(root.resolve("out"));
        int before = FtpFs.uploadStallMillis;
        FtpFs.uploadStallMillis = 500;
        try (FtpFs fs = connect("none", null)) {
            InputStream endless = new InputStream() { // far more than the socket buffers hold
                private long left = 1L << 31;

                @Override
                public int read() {
                    return left-- > 0 ? 0 : -1;
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    if (left <= 0) {
                        return -1;
                    }
                    int n = (int) Math.min(len, left);
                    Arrays.fill(b, off, off + n, (byte) 0);
                    left -= n;
                    return n;
                }
            };
            IOException failure = assertTimeoutPreemptively(java.time.Duration.ofSeconds(30),
                    () -> assertThrows(IOException.class, () -> fs.upload("/out/x.bin", endless, 1L << 31, DATE)));
            assertTrue(failure.getMessage().contains("the server took nothing of ftp://127.0.0.1/out/x.bin"),
                    failure.getMessage());
        } finally {
            FtpFs.uploadStallMillis = before;
        }
    }

    @Test
    public void aSourceFailingWithARuntimeExceptionKeepsItAndTheConnection() throws Exception {
        try (FtpFs fs = connect("none", null)) {
            fs.mkdirs("/out");
            InputStream failing = new InputStream() {
                private int count;

                @Override
                public int read() {
                    if (++count > 50_000) {
                        throw new IllegalStateException("decoder broke");
                    }
                    return 1;
                }
            };
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> fs.upload("/out/.x.tmp", failing, 100_000, DATE));
            assertEquals("decoder broke", failure.getMessage(), "the source's own failure");
            assertTrue(fs.stat("/out/.x.tmp").isPresent(), "the data connection was closed: the transfer ended");
            fs.delete("/out/.x.tmp");
            assertEquals(List.of(), filesUnder("out"));
        }
    }

    @Test
    public void aRenameFromAMissingSourceNeverDeletesTheTarget() throws Exception {
        putFile("out/a.jpg", "kept", DATE);
        try (FtpFs fs = connect("none", null)) {
            assertThrows(IOException.class, () -> fs.rename("/out/.gone.tmp", "/out/a.jpg", true));
        }
        assertEquals("kept", Files.readString(root.resolve("out/a.jpg")));
    }

    @Test
    public void aContentClosedBeforeItsEndLeavesTheConnectionInStep() throws Exception {
        byte[] big = new byte[8 << 20];
        Arrays.fill(big, (byte) 7);
        Files.createDirectories(root.resolve("in"));
        Files.write(root.resolve("in/big.bin"), big);
        putFile("in/b.txt", "world", DATE);
        try (FtpFs fs = connect("none", null)) {
            try (InputStream in = fs.open("/in/big.bin")) {
                assertEquals(7, in.read()); // then closed: the server's answer to the transfer is still to come
            }
            assertEquals(Optional.of(RemoteEntry.file("b.txt", 5, DATE)), fs.stat("/in/b.txt"));
            try (InputStream in = fs.open("/in/b.txt")) {
                assertEquals("world", new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            assertTrue(fs.isUsable());
        }
    }

    @Test
    public void contentsAreReadOnPooledConnections() throws Exception {
        for (int i = 0; i < 10; i++) {
            putFile("in/f" + i + ".txt", "content " + i, DATE);
        }
        FtpRead read = read("/in", ", maxConnections: 2");
        List<WorkItem> items = list(read);
        assertEquals(10, items.size());
        ExecutorService threads = Executors.newFixedThreadPool(5);
        try {
            List<Future<String>> contents = new ArrayList<>();
            for (WorkItem item : items) {
                contents.add(threads.submit(() -> {
                    try {
                        return content(item);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }));
            }
            for (int i = 0; i < 10; i++) {
                assertEquals("content " + i, contents.get(i).get(30, TimeUnit.SECONDS));
            }
        } finally {
            threads.shutdownNow();
        }
        read.afterAll();
    }

    // ---- writing

    @Test
    public void writesWithTheDateThenReadsBack() throws Exception {
        FtpWrite write = writeJson("{" + connection("none") + ", outPattern: '/out/{lastModified.Y}/{name}'}");
        assertFalse(write.write(item("a.jpg", "hello", DATE), CONTEXT).isSkipped());
        assertFalse(write.write(item("b é.txt", "world!", DATE.plusSeconds(3600)), CONTEXT).isSkipped());
        write.afterAll();
        assertEquals(List.of("out/2024/a.jpg", "out/2024/b é.txt"), filesUnder("out"));
        assertEquals(DATE, Files.getLastModifiedTime(root.resolve("out/2024/a.jpg")).toInstant());

        List<WorkItem> items = list(read("/out", ""));
        assertEquals(List.of("a.jpg", "b é.txt"), items.stream().map(WorkItem::getNameDisplay).toList());
        assertEquals("hello", content(items.get(0)));
        assertEquals("world!", content(items.get(1)));
        assertEquals(DATE, date(items.get(0)));
        assertEquals(DATE.plusSeconds(3600), date(items.get(1)));
    }

    @Test
    public void conflictsRenameSkipAndOverwrite() throws Exception {
        putFile("out/a.jpg", "old content", DATE);
        FtpWrite rename = write("");
        assertFalse(rename.write(item("a.jpg", "new", DATE), CONTEXT).isSkipped());
        assertEquals("new", Files.readString(root.resolve("out/a (1).jpg")));
        assertTrue(rename.write(item("a.jpg", "new", DATE), CONTEXT).isSkipped(), "the renamed copy is found again");

        FtpWrite overwrite = write(", onConflict: {ifDifferent: 'overwrite'}");
        assertFalse(overwrite.write(item("a.jpg", "replaced", DATE), CONTEXT).isSkipped());
        assertEquals("replaced", Files.readString(root.resolve("out/a.jpg")));
        assertEquals(List.of("out/a (1).jpg", "out/a.jpg"), filesUnder("out"));
    }

    @Test
    public void aReRunWithSizeAndDateSkipsWhatIsWritten() throws Exception {
        String extra = ", onConflict: {compare: 'sizeAndDate'}";
        assertFalse(write(extra).write(item("a.jpg", "hello", DATE), CONTEXT).isSkipped());
        WriteResult again = write(extra).write(item("a.jpg", "hello", DATE), new WriteContext("run2"));
        assertTrue(again.isSkipped(), "the date written is the item's");
        assertEquals(List.of("out/a.jpg"), filesUnder("out"));
        assertEquals(DATE, Files.getLastModifiedTime(root.resolve("out/a.jpg")).toInstant());
    }

    @Test
    public void aFileInTheWayOfAFolderIsNamed() throws Exception {
        putFile("out/x", "a file", DATE);
        FtpWrite write = writeJson("{" + connection("none") + ", outPattern: '/out/x/{name}'}");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> write.write(item("a.jpg", "hello", DATE), CONTEXT));
        assertTrue(failure.getMessage().contains("ftp://127.0.0.1/out/x is not a folder"), failure.getMessage());
    }

    @Test
    public void aFailingSourceDeletesTheTemporaryFileAndTheStepGoesOn() throws Exception {
        FtpWrite write = write(", maxConnections: 1");
        WorkItem failing = WorkItem.remote("bad.bin", "/src", "test://src/bad.bin", 100_000, DATE,
                FtpTest::failingSource, () -> { });
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> write.write(failing, CONTEXT));
        assertTrue(failure.getMessage().contains("disk gone"), failure.getMessage());
        assertEquals(List.of(), filesUnder("out"));

        assertFalse(write.write(item("a.jpg", "hello", DATE), CONTEXT).isSkipped());
        assertEquals(List.of("out/a.jpg"), filesUnder("out"));
    }

    @Test
    public void aFailedUploadLeavesTheConnectionUsableToDeleteWhatWasSent() throws Exception {
        try (FtpFs fs = connect("none", null)) {
            fs.mkdirs("/out");
            IOException failure = assertThrows(IOException.class,
                    () -> fs.upload("/out/.x.tmp", failingSource(), 100_000, DATE));
            assertEquals("disk gone", failure.getMessage(), "the source's own failure");
            assertTrue(fs.stat("/out/.x.tmp").isPresent(), "what was sent is on the server");
            fs.delete("/out/.x.tmp");
            assertEquals(Optional.empty(), fs.stat("/out/.x.tmp"));
            assertEquals(List.of(), filesUnder("out"));
        }
    }

    @Test
    public void aCancelledUploadLeavesNothingAndTheStepGoesOn() throws Exception {
        for (Thread.Builder threads : List.of(Thread.ofPlatform(), Thread.ofVirtual())) {
            FtpWrite write = write(", maxConnections: 1");
            byte[] big = new byte[1 << 20];
            WorkItem cancelled = WorkItem.remote("big.bin", "/src", "test://src/big.bin", big.length, DATE,
                    () -> new InputStream() {
                        private final ByteArrayInputStream in = new ByteArrayInputStream(big);

                        @Override
                        public int read() {
                            return in.read();
                        }

                        @Override
                        public int read(byte[] b, int off, int len) {
                            Thread.currentThread().interrupt(); // a cancel of the run while the content is sent
                            return in.read(b, off, len);
                        }
                    }, () -> { });
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicBoolean interrupted = new AtomicBoolean();
            Thread thread = threads.start(() -> {
                try {
                    write.write(cancelled, CONTEXT);
                } catch (Throwable e) {
                    failure.set(e);
                }
                interrupted.set(Thread.interrupted());
            });
            assertTrue(thread.join(java.time.Duration.ofSeconds(30)));
            assertInstanceOf(IllegalStateException.class, failure.get());
            assertTrue(interrupted.get(), "the interrupt is kept: " + failure.get());
            assertEquals(List.of(), filesUnder("out"), "the temporary file is deleted");

            assertFalse(write.write(item("a.jpg", "hello", DATE), CONTEXT).isSkipped());
            assertEquals(List.of("out/a.jpg"), filesUnder("out"));
            Files.delete(root.resolve("out/a.jpg"));
            write.afterAll();
        }
    }

    @Test
    public void theActiveModeWritesAndReads() throws Exception {
        FtpWrite write = write(", passive: false");
        assertFalse(write.write(item("a.jpg", "hello", DATE), CONTEXT).isSkipped());
        List<WorkItem> items = list(read("/out", ", passive: false"));
        assertEquals("hello", content(items.getFirst()));
        assertEquals(DATE, date(items.getFirst()));
    }

    @Test
    public void aLineBreakInAPathIsRefused() throws Exception {
        try (FtpFs fs = connect("none", null)) {
            IOException failure = assertThrows(IOException.class, () -> fs.stat("/in/a\r\nDELE b"));
            assertTrue(failure.getMessage().contains("line break"), failure.getMessage());
            assertTrue(fs.isUsable());
        }
    }

    // ---- authentication and TLS

    @Test
    public void aWrongPasswordFailsWithoutShowingIt() {
        FtpRead read = readJson("{host: '127.0.0.1', port: " + server.port()
                + ", user: 'alice', password: 'wrong-Password-1', security: 'none', folder: '/'}");
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> list(read));
        assertTrue(failure.getMessage().contains("refused the login"), failure.getMessage());
        assertFalse(failure.getMessage().contains("wrong-Password-1"), failure.getMessage());
        assertFalse(failure.getMessage().contains("alice"), failure.getMessage());
    }

    @Test
    public void explicitFtpsWritesAndReadsWithTheDataConnectionsProtected() throws Exception {
        // the data connections are protected: after PROT P, Apache FtpServer takes a data connection only in TLS (it
        // starts a handshake on it), so transfers that work are protected ones; the client checks their certificate
        // too (aDataConnectionCertificateOfAnotherNameIsRefused)
        restart(FtpTestServer.Tls.EXPLICIT, serverStore, null, Map.of());
        X509ExtendedTrustManager trust = FtpTestServer.trusting(serverStore);
        FtpWrite write = writeJson("{" + connection("explicit") + ", outPattern: '/out/{name}'}");
        write.trust(trust);
        assertFalse(write.write(item("a.jpg", "hello", DATE), CONTEXT).isSkipped());
        write.afterAll();
        assertEquals(DATE, Files.getLastModifiedTime(root.resolve("out/a.jpg")).toInstant());

        FtpRead read = readJson("{" + connection("explicit") + ", folder: '/out'}");
        read.trust(trust);
        List<WorkItem> items = list(read);
        assertEquals(List.of("a.jpg"), items.stream().map(WorkItem::getNameDisplay).toList());
        assertEquals("hello", content(items.getFirst()));
        assertEquals(DATE, date(items.getFirst()));
        read.afterAll();
    }

    @Test
    public void aDataConnectionCertificateOfAnotherNameIsRefused() throws Exception {
        // the control connection names 127.0.0.1; the data connections, a TLS context of their own (no session to
        // resume), show a certificate trusted but for other.example: only the data connection's check can refuse
        restart(FtpTestServer.Tls.EXPLICIT, serverStore, otherStore, "TLSv1.2", Map.of());
        putFile("in/a.jpg", "hello", DATE);
        try (FtpFs fs = connect("explicit", FtpTestServer.trusting(serverStore, otherStore))) {
            assertThrows(IOException.class, () -> fs.list("/in"));
            assertFalse(fs.dataSessionResumed());
        }
    }

    @Test
    public void aFtpsContentClosedBeforeItsEndLeavesTheConnectionInStep() throws Exception {
        restart(FtpTestServer.Tls.EXPLICIT, serverStore, null, Map.of());
        byte[] big = new byte[8 << 20];
        Arrays.fill(big, (byte) 7);
        Files.createDirectories(root.resolve("in"));
        Files.write(root.resolve("in/big.bin"), big);
        putFile("in/b.txt", "world", DATE);
        try (FtpFs fs = connect("explicit", FtpTestServer.trusting(serverStore))) {
            try (InputStream in = fs.open("/in/big.bin")) {
                assertEquals(7, in.read());
            }
            assertEquals(Optional.of(RemoteEntry.file("b.txt", 5, DATE)), fs.stat("/in/b.txt"));
            try (InputStream in = fs.open("/in/b.txt")) {
                assertEquals("world", new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            assertTrue(fs.isUsable());
        }
    }

    @Test
    public void implicitFtpsWritesAndReads() throws Exception {
        restart(FtpTestServer.Tls.IMPLICIT, serverStore, null, Map.of());
        X509ExtendedTrustManager trust = FtpTestServer.trusting(serverStore);
        FtpWrite write = writeJson("{" + connection("implicit") + ", outPattern: '/out/{name}'}");
        write.trust(trust);
        assertFalse(write.write(item("a.jpg", "hello", DATE), CONTEXT).isSkipped());
        FtpRead read = readJson("{" + connection("implicit") + ", folder: '/out'}");
        read.trust(trust);
        assertEquals("hello", content(list(read).getFirst()));
    }

    @Test
    public void theDataConnectionsResumeTheTlsSessionOfTheControlConnection() throws Exception {
        restart(FtpTestServer.Tls.EXPLICIT, serverStore, "TLSv1.2", Map.of()); // TLS 1.2: a resumed session keeps its id
        putFile("in/a.jpg", "hello", DATE);
        try (FtpFs fs = connect("explicit", FtpTestServer.trusting(serverStore))) {
            assertEquals(List.of(RemoteEntry.file("a.jpg", 5, DATE)), fs.list("/in"));
            assertTrue(fs.dataSessionResumed());
        }
    }

    @Test
    public void explicitFtpsWorksInActiveMode() throws Exception {
        restart(FtpTestServer.Tls.EXPLICIT, serverStore, null, Map.of());
        X509ExtendedTrustManager trust = FtpTestServer.trusting(serverStore);
        FtpWrite write = writeJson("{" + connection("explicit") + ", passive: false, outPattern: '/out/{name}'}");
        write.trust(trust);
        assertFalse(write.write(item("a.jpg", "hello", DATE), CONTEXT).isSkipped());
        FtpRead read = readJson("{" + connection("explicit") + ", passive: false, folder: '/out'}");
        read.trust(trust);
        assertEquals("hello", content(list(read).getFirst()));
    }

    @Test
    public void ftpsWorksOverTls13() throws Exception {
        restart(FtpTestServer.Tls.EXPLICIT, serverStore, "TLSv1.3", Map.of());
        try (FtpFs fs = connect("explicit", FtpTestServer.trusting(serverStore))) {
            fs.mkdirs("/out");
            byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
            fs.upload("/out/a.jpg", new ByteArrayInputStream(content), content.length, DATE);
            assertEquals(List.of(RemoteEntry.file("a.jpg", 5, DATE)), fs.list("/out"));
            try (InputStream in = fs.open("/out/a.jpg")) {
                assertEquals("hello", new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    // ---- certificates this computer does not trust (trust on first use)

    @Test
    public void aSelfSignedCertificateIsAskedOnceThenTrustedSilently() throws Exception {
        restart(FtpTestServer.Tls.EXPLICIT, serverStore, null, Map.of()); // the system's certificates: untrusted
        putFile("in/a.jpg", "hello", DATE);
        answer = true;
        FtpRead read = readJson("{" + connection("explicit") + ", folder: '/in'}");
        List<WorkItem> items = list(read);
        assertEquals("hello", content(items.getFirst()));
        read.afterAll();

        assertEquals(1, questions.size(), questions.toString());
        String question = questions.getFirst();
        X509Certificate certificate = FtpTestServer.certificate(serverStore);
        String day = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(java.time.ZoneOffset.UTC)
                .format(certificate.getNotAfter().toInstant());
        assertTrue(question.startsWith("The FTPS server 127.0.0.1:" + server.port() + " shows a certificate"
                + " this computer does not trust: CN=Copybot test, issued by CN=Copybot test, valid from "), question);
        assertTrue(question.contains(" to " + day + ", key fingerprint " + pin(serverStore) + ". Trust"), question);
        assertFalse(question.contains("names"), "the certificate names 127.0.0.1: " + question);
        assertFalse(question.contains(FtpTestServer.PASSWORD), question);
        assertTrue(Files.readString(trustFile()).contains("127.0.0.1:" + server.port() + " " + pin(serverStore)
                + "\n"));

        FtpRead again = readJson("{" + connection("explicit") + ", folder: '/in'}"); // another step, another run
        assertEquals("hello", content(list(again).getFirst()));
        assertEquals(1, questions.size(), "not asked again");
    }

    @Test
    public void aCertificateRefusedByTheUserFailsAndIsNotAskedAgainByTheStep() throws Exception {
        restart(FtpTestServer.Tls.EXPLICIT, serverStore, null, Map.of());
        answer = false;
        FtpRead read = readJson("{" + connection("explicit") + ", folder: '/'}");
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> list(read));
        assertTrue(failure.getMessage().contains("the certificate of the server 127.0.0.1:" + server.port()
                + " is not trusted (key fingerprint " + pin(serverStore) + " was not accepted)"), failure.getMessage());
        assertFalse(failure.getMessage().contains(FtpTestServer.PASSWORD), failure.getMessage());
        assertThrows(IllegalStateException.class, () -> list(read));
        assertEquals(1, questions.size(), "the step's no is remembered");
        assertFalse(Files.exists(trustFile()));
    }

    @Test
    public void aChangedCertificateIsRefusedWithoutAsking() throws Exception {
        restart(FtpTestServer.Tls.EXPLICIT, serverStore, null, Map.of());
        trustAsUser(otherStore); // the key trusted before
        String before = Files.readString(trustFile());
        answer = true;
        FtpRead read = readJson("{" + connection("explicit") + ", folder: '/'}");
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> list(read));
        assertTrue(failure.getMessage().contains("the certificate of the server 127.0.0.1:" + server.port()
                + " has changed: key fingerprint " + pin(otherStore) + " recorded, " + pin(serverStore)
                + " shown now"), failure.getMessage());
        assertTrue(failure.getMessage().contains(trustFile().toAbsolutePath().normalize().toString()),
                failure.getMessage());
        assertEquals(List.of(), questions);
        assertEquals(before, Files.readString(trustFile()));
    }

    @Test
    public void aCertificateTrustedByThisComputerIsNeverAsked() throws Exception {
        restart(FtpTestServer.Tls.EXPLICIT, serverStore, null, Map.of());
        trustAsUser(otherStore); // even another key recorded: the system's trust comes first
        FtpRead read = readJson("{" + connection("explicit") + ", folder: '/'}");
        read.trust(FtpTestServer.trusting(serverStore));
        assertEquals(List.of(), list(read));
        assertEquals(List.of(), questions);
    }

    @Test
    public void aCertificateOfAnotherNameIsAskedAboutWithItsNames() throws Exception {
        restart(FtpTestServer.Tls.EXPLICIT, otherStore, null, Map.of());
        FtpRead read = readJson("{" + connection("explicit") + ", folder: '/'}");
        read.trust(FtpTestServer.trusting(otherStore)); // trusted, but for other.example
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> list(read));
        assertTrue(failure.getMessage().contains("is not trusted"), failure.getMessage());
        assertEquals(1, questions.size());
        assertTrue(questions.getFirst().contains(". The certificate names other.example, not 127.0.0.1. Trust"),
                questions.getFirst());
    }

    @Test
    public void anExpiredCertificateTrustedByTheUserIsAccepted() throws Exception {
        restart(FtpTestServer.Tls.EXPLICIT, expiredStore, null, Map.of());
        trustAsUser(expiredStore);
        putFile("in/a.jpg", "hello", DATE);
        try (FtpFs fs = connect("explicit", null)) {
            assertEquals(List.of(RemoteEntry.file("a.jpg", 5, DATE)), fs.list("/in"));
        }
        assertEquals(List.of(), questions);
    }

    @Test
    public void aDataConnectionNotResumingIsCheckedAgainstTheTrustedKey() throws Exception {
        // the data connections have a TLS context of their own: no session to resume, a full handshake each
        restart(FtpTestServer.Tls.EXPLICIT, serverStore, serverStore, "TLSv1.2", Map.of());
        trustAsUser(serverStore);
        putFile("in/a.jpg", "hello", DATE);
        try (FtpFs fs = connect("explicit", null)) {
            assertEquals(List.of(RemoteEntry.file("a.jpg", 5, DATE)), fs.list("/in"));
            assertFalse(fs.dataSessionResumed());
        }
        // another key on the data connections only: refused, never asked
        restart(FtpTestServer.Tls.EXPLICIT, serverStore, otherStore, "TLSv1.2", Map.of());
        trustAsUser(serverStore);
        answer = true;
        try (FtpFs fs = connect("explicit", null)) {
            IOException failure = assertThrows(IOException.class, () -> fs.list("/in"));
            assertTrue(failure.getMessage().contains("a data connection of 127.0.0.1:" + server.port()
                    + " shows a certificate that is not trusted (key fingerprint " + pin(otherStore) + ")"),
                    failure.getMessage());
        }
        assertEquals(List.of(), questions);
    }

    @Test
    public void concurrentConnectionsAskOnce() throws Exception {
        restart(FtpTestServer.Tls.EXPLICIT, serverStore, null, Map.of());
        answer = true;
        answerDelayMillis = 300;
        FtpWrite write = writeJson("{" + connection("explicit") + ", outPattern: '/out/{name}', maxConnections: 4}");
        ExecutorService threads = Executors.newFixedThreadPool(6);
        try {
            List<Future<WriteResult>> results = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                WorkItem item = item("f" + i + ".txt", "content " + i, DATE);
                results.add(threads.submit(() -> write.write(item, CONTEXT)));
            }
            for (Future<WriteResult> result : results) {
                assertFalse(result.get(30, TimeUnit.SECONDS).isSkipped());
            }
        } finally {
            threads.shutdownNow();
        }
        write.afterAll();
        assertEquals(1, questions.size(), questions.toString());
        assertEquals(1, Files.readAllLines(trustFile()).stream().filter(l -> !l.startsWith("#")).count());
        assertEquals(6, filesUnder("out").size());
    }

    @Test
    public void namesAreMatchedAsHttpsDoes() {
        assertTrue(CertificateTrust.nameMatches("NAS.example.com", List.of("nas.example.com"), List.of(), null));
        assertTrue(CertificateTrust.nameMatches("nas.example.com", List.of("*.example.com"), List.of(), null));
        assertFalse(CertificateTrust.nameMatches("a.nas.example.com", List.of("*.example.com"), List.of(), null));
        assertFalse(CertificateTrust.nameMatches("example.com", List.of("*.example.com"), List.of(), null));
        assertTrue(CertificateTrust.nameMatches("nas", List.of(), List.of(), "nas"), "the CN without DNS names");
        assertFalse(CertificateTrust.nameMatches("nas", List.of("other"), List.of(), "nas"), "DNS names first");
        assertTrue(CertificateTrust.nameMatches("192.168.1.2", List.of(), List.of("192.168.1.2"), null));
        assertFalse(CertificateTrust.nameMatches("192.168.1.2", List.of("192.168.1.2"), List.of(), null),
                "an address only against the addresses");
        assertTrue(CertificateTrust.nameMatches("::1", List.of(), List.of("0:0:0:0:0:0:0:1"), null));
    }

    @Test
    public void theTextsOfACertificateAreCleanedForTheQuestion() {
        String crafted = "CN=nas" + (char) 13 + (char) 10 + "Trust it? yes" + (char) 0x202E + (char) 0x2028 + "!"
                + (char) 0x1B + "[2J";
        assertEquals("CN=nasTrust it? yes![2J", CertificateTrust.shown(crafted));
        String shown = CertificateTrust.shown("O=" + "x".repeat(500));
        assertEquals(CertificateTrust.SHOWN_LENGTH, shown.length());
        assertTrue(shown.endsWith("xx..."), shown);
    }

    @Test
    public void theTrustFileIsReadLeniently() throws Exception {
        Path file = temp.resolve("ftps_trust");
        Files.write(file, ((char) 0xFEFF + "nas:21 SHA256:aaa\r\n# comment\r\n\r\n  NAS:990   SHA256:bbb  \r\nother:21 SHA256:c")
                .getBytes(StandardCharsets.UTF_8));
        FtpsTrustFile trust = new FtpsTrustFile(file);
        assertEquals(List.of("SHA256:aaa"), trust.pins("nas:21"));
        assertEquals(List.of("SHA256:bbb"), trust.pins("nas:990"));
        trust.add("nas:21", "SHA256:ddd");
        assertEquals(List.of("SHA256:aaa", "SHA256:ddd"), trust.pins("nas:21"));
        assertEquals(List.of("SHA256:c"), trust.pins("other:21"), "the last line, ended before adding");
        assertEquals("[::1]:21", FtpsTrustFile.key("::1", 21));
    }

    @Test
    public void aServerWithoutTlsIsRefusedWhenExplicitIsAsked() {
        FtpRead read = readJson("{" + connection("explicit") + ", folder: '/'}");
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> list(read));
        assertTrue(failure.getMessage().contains("the secure connection (TLS)"), failure.getMessage());
    }

    // ---- configuration

    @Test
    public void theConfigurationNeverShowsItsSecrets() {
        for (Object config : List.of(read("/in", "").config(), write("").config())) {
            String text = config.toString();
            assertFalse(text.contains(FtpTestServer.PASSWORD), text);
            assertFalse(text.contains(FtpTestServer.USER), text);
            assertTrue(text.contains("127.0.0.1"), text);
            assertTrue(text.contains("security=none"), text);
        }
    }

    @Test
    public void theConfigurationIsChecked() {
        assertThrows(IllegalArgumentException.class, () -> readJson("{user: 'alice', folder: '/in'}"));
        assertThrows(IllegalArgumentException.class, () -> readJson("{host: 'nas', folder: '/in'}"));
        assertThrows(IllegalArgumentException.class, () -> readJson("{host: 'nas', user: 'a', port: 0, folder: '/'}"));
        IllegalArgumentException port = assertThrows(IllegalArgumentException.class,
                () -> writeJson("{host: 'nas', user: 'a', port: 70000, outPattern: 'x'}"));
        assertTrue(port.getMessage().contains("the port must be between 1 and 65535"), port.getMessage());
        IllegalArgumentException security = assertThrows(IllegalArgumentException.class,
                () -> writeJson("{host: 'nas', user: 'a', security: 'ssl', outPattern: 'x'}"));
        assertTrue(security.getMessage().contains("the security cannot be \"ssl\""), security.getMessage());
        assertThrows(IllegalArgumentException.class, () -> readJson("{host: 'nas', user: 'a'}"));
    }

    @Test
    public void aPasswordSentInClearIsWarned() {
        List<String> warnings = read("/in", "").configWarnings();
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().startsWith("ftp.read: with the security none, the password"), warnings.getFirst());
        assertEquals(1, write("").configWarnings().size());
        assertEquals(List.of(), readJson("{" + connection("explicit") + ", folder: '/'}").configWarnings());
        assertEquals(List.of(), readJson("{host: 'nas', user: 'anonymous', security: 'none', folder: '/'}")
                .configWarnings());
    }

    @Test
    public void thePortDependsOnTheSecurity() {
        FtpReadConfig config = new FtpReadConfig("nas", null, "a", null, null, null, "/", null, null, null, null, null);
        assertEquals(21, FtpConnections.port(config, FtpConnections.Security.EXPLICIT));
        assertEquals(21, FtpConnections.port(config, FtpConnections.Security.NONE));
        assertEquals(990, FtpConnections.port(config, FtpConnections.Security.IMPLICIT));
        assertEquals(FtpConnections.Security.EXPLICIT, FtpConnections.security(null, "ftp.read", config));
    }

    @Test
    public void theServerIsTheResource() {
        assertEquals(Set.of("net:ftp:nas.local"), readJson("{host: 'NAS.local', user: 'a', folder: '/'}")
                .requiredResources(null));
        assertEquals(Set.of("net:ftp:nas.local"), writeJson("{host: 'NAS.local', user: 'a', outPattern: '/x'}")
                .requiredResources(null));
    }

    @Test
    public void theEditorOffersASecretAndTheSecuritiesWithTheirTexts() {
        CombinedResourceBundle bundle = plugin().getResourceBundle();
        for (var action : List.of(new FtpRead(), new FtpWrite())) {
            String code = action instanceof FtpRead ? "ftp.read" : "ftp.write";
            ConfigSchema schema = action.configSchema().orElseThrow().withKeyPrefix("plugin.remote." + code);
            assertTrue(schema.field("password").orElseThrow().hints().contains(FieldHint.SECRET));
            assertEquals(List.of("none", "explicit", "implicit"), schema.field("security").orElseThrow().enumValues());
            assertTrue(bundle.containsKey("plugin.remote." + code + ".name"), code);
            assertTrue(bundle.containsKey("plugin.remote." + code + ".description"), code);
            for (ConfigField field : schema.allFields()) {
                assertTrue(bundle.containsKey(field.labelKey()), field.labelKey());
                assertTrue(bundle.containsKey(field.descriptionKey()), field.descriptionKey());
                for (String value : field.enumValues()) {
                    assertTrue(bundle.containsKey(field.valueKey(value)), field.valueKey(value));
                }
            }
        }
    }
}

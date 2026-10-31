package com.copybot.plugin.remote.s3;

import com.copybot.plugin.api.action.ListingSink;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import com.copybot.plugin.api.action.WriteContext;
import com.copybot.plugin.api.config.ConfigField;
import com.copybot.plugin.api.config.ConfigSchema;
import com.copybot.plugin.api.config.FieldHint;
import com.copybot.plugin.remote.RemotePlugin;
import com.copybot.plugin.remote.core.RemoteEntry;
import com.copybot.resources.CombinedResourceBundle;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** s3.read and s3.write against a fake S3 server checking every signature: no external network. */
public class S3Test {

    private static final Instant DATE = Instant.parse("2024-05-01T10:15:30Z");
    private static final Instant UPLOADED = Instant.parse("2024-06-02T08:00:00Z");
    private static final WriteContext CONTEXT = new WriteContext("run1");
    /** Small sizes: multipart above 1000 bytes, parts of 400 bytes (the first one of 1000). */
    private static final S3Fs.Tuning SMALL = new S3Fs.Tuning(1000, 400, Duration.ofSeconds(60), null,
            Clock.systemUTC());

    private S3TestServer server;

    @BeforeEach
    public void setUp() throws IOException {
        server = S3TestServer.pathStyle();
    }

    @AfterEach
    public void tearDown() {
        server.close();
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

    private String connection() {
        return "endpoint: 'http://127.0.0.1:" + server.port() + "', region: '" + S3TestServer.REGION + "', bucket: '"
                + S3TestServer.BUCKET + "', accessKey: '" + S3TestServer.ACCESS_KEY + "', secretKey: '"
                + S3TestServer.SECRET_KEY + "', pathStyle: true";
    }

    private static S3Read readJson(String json, S3Fs.Tuning tuning) {
        S3Read action = new S3Read();
        action.setPlugin(plugin());
        action.loadConfig(JsonParser.parseString(json));
        action.tuning(tuning);
        return action;
    }

    private static S3Write writeJson(String json, S3Fs.Tuning tuning) {
        S3Write action = new S3Write();
        action.setPlugin(plugin());
        action.loadConfig(JsonParser.parseString(json));
        action.tuning(tuning);
        return action;
    }

    private S3Read read(String folder, String extra) {
        return readJson("{" + connection() + ", folder: '" + folder + "'" + extra + "}", SMALL);
    }

    private S3Write write(String extra) {
        return writeJson("{" + connection() + ", outPattern: 'out/{name}'" + extra + "}", SMALL);
    }

    private S3Fs connect(S3Fs.Tuning tuning) {
        return S3Fs.connect(new S3ReadConfig("http://127.0.0.1:" + server.port(), S3TestServer.REGION,
                S3TestServer.BUCKET, S3TestServer.ACCESS_KEY, S3TestServer.SECRET_KEY, true, "/", null, null, null,
                null, null), plugin(), tuning);
    }

    private record Listing(List<WorkItem> items, List<String> done) {
    }

    private static Listing list(S3Read read) {
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
        return new Listing(items, done);
    }

    private static List<String> sources(Listing listing) {
        return listing.items().stream().map(WorkItem::getSourceLocationDisplay).toList();
    }

    private static WorkItem item(String name, byte[] bytes, Instant date) {
        return WorkItem.remote(name, "/src", "test://src/" + name, bytes.length, date,
                () -> new ByteArrayInputStream(bytes), () -> { });
    }

    private static WorkItem item(String name, String content, Instant date) {
        return item(name, content.getBytes(StandardCharsets.UTF_8), date);
    }

    private static String content(WorkItem item) throws IOException {
        try (InputStream in = item.openInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static byte[] bytes(int size) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) (i * 31 + 7);
        }
        return bytes;
    }

    private List<String> requests(String start) {
        return server.requests.stream().filter(r -> r.startsWith(start)).toList();
    }

    private void sampleBucket() {
        server.put("in/a.jpg", "aaa", UPLOADED);
        server.put("in/b.txt", "bb", UPLOADED);
        server.put("in/sub/c.jpg", "cccc", UPLOADED);
        server.put("in/sub/deep/d.jpg", "ddddd", UPLOADED);
        server.put("in/.hidden/x.jpg", "x", UPLOADED);
        server.put("in/sub/.e.jpg", "e", UPLOADED);
        server.put("in/sub/", "", UPLOADED); // a "folder" made by a console
        server.put("in2/w.jpg", "w", UPLOADED);
        server.put("other/z.jpg", "z", UPLOADED);
    }

    // ---- reading

    @Test
    public void aRecursiveListingIsOneListingOfThePrefixPageByPage() throws Exception {
        sampleBucket();
        server.pageSize = 2;
        Listing listing = list(read("in", ""));
        assertEquals(List.of("s3://photos/in/a.jpg", "s3://photos/in/b.txt", "s3://photos/in/sub/c.jpg",
                "s3://photos/in/sub/deep/d.jpg"), sources(listing), "hidden names, the folder key and in2/ left out");
        assertEquals(List.of(), listing.done(), "S3 signals no folder");
        WorkItem deep = listing.items().get(3);
        assertEquals("d.jpg", deep.getNameDisplay());
        assertEquals(5L, deep.getMetadatas().getSize());
        assertEquals(UPLOADED, deep.getMetadatas().getTime(WorkItemMetadata.LAST_MODIFIED).orElseThrow());
        assertEquals("ddddd", content(deep));
        assertEquals("aaa", content(listing.items().getFirst()));
        List<String> lists = requests("GET /photos?");
        assertEquals(4, lists.size(), "7 keys under in/ in pages of 2: " + lists);
        assertTrue(lists.stream().noneMatch(r -> r.contains("delimiter")), lists.toString());
        assertTrue(lists.getFirst().contains("prefix=in%2F"), lists.getFirst());
        assertTrue(lists.get(1).contains("continuation-token="), lists.get(1));
    }

    @Test
    public void aNonRecursiveListingUsesTheDelimiter() {
        sampleBucket();
        server.pageSize = 1;
        Listing listing = list(read("/in/", ", recursive: false"));
        assertEquals(List.of("s3://photos/in/a.jpg", "s3://photos/in/b.txt"), sources(listing));
        assertTrue(requests("GET /photos?").stream().allMatch(r -> r.contains("delimiter=%2F")));
        assertEquals(4, requests("GET /photos?").size(), "a.jpg, b.txt, the common prefixes .hidden/ and sub/");
    }

    @Test
    public void globsExcludedFoldersAndMinimumSizeApplyToTheTree() {
        sampleBucket();
        assertEquals(List.of("s3://photos/in/sub/c.jpg", "s3://photos/in/sub/deep/d.jpg"),
                sources(list(read("in", ", include: ['**/*.JPG'], minSize: 4"))));
        assertEquals(List.of("s3://photos/in/sub/c.jpg"), sources(list(read("in", ", exclude: ['a.jpg', '*.txt',"
                + " 'sub/deep/**']"))));
        assertEquals(List.of("s3://photos/in/a.jpg", "s3://photos/in/b.txt"),
                sources(list(read("in", ", exclude: ['sub/**']"))));
    }

    @Test
    public void theWholeBucketIsListedFromItsRoot() {
        sampleBucket();
        assertEquals(6, list(read("/", "")).items().size());
        assertTrue(requests("GET /photos?").getFirst().endsWith("&prefix="), requests("GET /photos?").toString());
    }

    @Test
    public void keysWithSpacesAccentsAndSignsGoBothWays() throws Exception {
        String key = "in/été 2024/a+b c&d=e%f~.jpg";
        server.put(key, "content", UPLOADED);
        Listing listing = list(read("in", ""));
        assertEquals(List.of("s3://photos/" + key), sources(listing));
        assertEquals("content", content(listing.items().getFirst()));
        listing.items().getFirst().getSourceDeleter().orElseThrow().delete();
        assertEquals(Optional.empty(), server.object(key));

        assertFalse(write("").write(item("x y+é.jpg", "hello", DATE), CONTEXT).isSkipped());
        assertEquals("hello", server.content("out/x y+é.jpg"));
    }

    @Test
    public void aMissingObjectFailsItsReadAndAMissingKeyIsNoState() throws Exception {
        try (S3Fs fs = connect(SMALL)) {
            IOException failure = assertThrows(IOException.class, () -> fs.open("/in/none.jpg"));
            assertEquals("s3://photos/in/none.jpg does not exist", failure.getMessage());
            assertEquals(Optional.empty(), fs.stat("in/none.jpg"));
            fs.mkdirs("in/new/folder");
            fs.delete("in/none.jpg");
            assertEquals(List.of(), fs.list("in"));
            assertTrue(fs.isUsable());
        }
    }

    // ---- writing

    @Test
    public void writesUnderTheFinalKeyWithTheDateThenFindsItAgain() throws Exception {
        S3Write write = writeJson("{" + connection() + ", outPattern: 'out/{lastModified.Y}/{name}',"
                + " onConflict: {compare: 'sizeAndDate'}}", SMALL);
        assertFalse(write.write(item("a.jpg", "hello", DATE.plusMillis(250)), CONTEXT).isSkipped());
        assertEquals("hello", server.content("out/2024/a.jpg"));
        assertEquals(Map.of("x-amz-meta-mtime", "1714558530.25"), server.objects.get("out/2024/a.jpg").metadata());
        assertEquals(List.of("HEAD /photos/out/2024/a.jpg", "PUT /photos/out/2024/a.jpg",
                "HEAD /photos/out/2024/a.jpg"), server.requests, "no temporary object, no rename");
        try (S3Fs fs = connect(SMALL)) {
            RemoteEntry entry = fs.stat("/out/2024/a.jpg").orElseThrow();
            assertEquals(RemoteEntry.file("a.jpg", 5, DATE.plusMillis(250)), entry);
        }

        S3Write again = writeJson("{" + connection() + ", outPattern: 'out/{lastModified.Y}/{name}',"
                + " onConflict: {compare: 'sizeAndDate'}}", SMALL);
        assertTrue(again.write(item("a.jpg", "hello", DATE), new WriteContext("run2")).isSkipped(),
                "the date written is the item's, given back by HEAD");
        assertEquals(1, server.objects.size());
    }

    @Test
    public void aConflictIsRenamedAndAnOverwriteReplaces() {
        server.put("out/a.jpg", "old content", UPLOADED);
        assertFalse(write("").write(item("a.jpg", "new", DATE), CONTEXT).isSkipped());
        assertEquals("new", server.content("out/a (1).jpg"));
        assertFalse(write(", onConflict: {ifDifferent: 'overwrite'}").write(item("a.jpg", "replaced", DATE), CONTEXT)
                .isSkipped());
        assertEquals("replaced", server.content("out/a.jpg"));
        assertEquals(Set.of("out/a.jpg", "out/a (1).jpg"), server.objects.keySet());
    }

    @Test
    public void aFileAboveTheThresholdIsSentInParts() {
        byte[] big = bytes(2500);
        assertFalse(write("").write(item("big.bin", big, DATE), CONTEXT).isSkipped());
        assertArrayEquals(big, server.objects.get("out/big.bin").data());
        assertEquals(Map.of("x-amz-meta-mtime", "1714558530"), server.objects.get("out/big.bin").metadata());
        assertEquals(1, requests("POST /photos/out/big.bin?uploads=").size());
        List<String> parts = requests("PUT /photos/out/big.bin?partNumber=");
        assertEquals(5, parts.size(), "1000 + 400 + 400 + 400 + 300: " + parts);
        assertEquals(1, requests("POST /photos/out/big.bin?uploadId=").size());
        assertEquals(0, server.openUploads());
        assertEquals(List.of(), server.aborted);
    }

    @Test
    public void theThresholdItselfIsOnePutAndAnUnknownSizeIsMeasured() throws Exception {
        try (S3Fs fs = connect(SMALL)) {
            fs.upload("/k/exact.bin", new ByteArrayInputStream(bytes(1000)), 1000, null);
            fs.upload("/k/unknown.bin", new ByteArrayInputStream(bytes(1200)), -1, DATE);
            fs.upload("/k/empty.bin", new ByteArrayInputStream(new byte[0]), 0, null);
            fs.upload("/k/parts.bin", new ByteArrayInputStream(bytes(1800)), -1, null);
        }
        assertArrayEquals(bytes(1000), server.objects.get("k/exact.bin").data());
        assertArrayEquals(bytes(1200), server.objects.get("k/unknown.bin").data());
        assertArrayEquals(new byte[0], server.objects.get("k/empty.bin").data());
        assertArrayEquals(bytes(1800), server.objects.get("k/parts.bin").data());
        assertEquals(Map.of(), server.objects.get("k/exact.bin").metadata(), "no date given: none written");
        assertEquals(List.of("POST /photos/k/unknown.bin?uploads=", "POST /photos/k/parts.bin?uploads="),
                requests("POST /photos/k/").stream().filter(r -> r.endsWith("uploads=")).toList());
        assertEquals(2, requests("PUT /photos/k/unknown.bin?partNumber=").size());
        assertEquals(3, requests("PUT /photos/k/parts.bin?partNumber=").size(), "1000 + 400 + 400");
    }

    @Test
    public void aFailingPartAbortsTheUploadAndTheConnectionGoesOn() throws Exception {
        server.fail(r -> r.startsWith("PUT /photos/out/big.bin?partNumber=2&"), 500, "InternalError",
                "We encountered an internal error. Please try again.", Map.of());
        S3Write write = write(", maxConnections: 1");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> write.write(item("big.bin", bytes(2500), DATE), CONTEXT));
        assertTrue(failure.getMessage().contains("the server answered 500 InternalError: We encountered an internal"
                + " error"), failure.getMessage());
        assertEquals(1, server.aborted.size());
        assertEquals(0, server.openUploads());
        assertEquals(Optional.empty(), server.object("out/big.bin"));

        assertFalse(write.write(item("a.jpg", "hello", DATE), CONTEXT).isSkipped());
        assertEquals("hello", server.content("out/a.jpg"));

        try (S3Fs fs = connect(SMALL)) {
            server.fail(r -> r.startsWith("PUT /photos/out/single.bin"), 503, "SlowDown", "Please reduce your rate",
                    Map.of());
            IOException put = assertThrows(IOException.class,
                    () -> fs.upload("out/single.bin", new ByteArrayInputStream(bytes(10)), 10, null));
            assertEquals("the server answered 503 SlowDown: Please reduce your rate", put.getMessage());
            fs.upload("out/single.bin", new ByteArrayInputStream(bytes(10)), 10, null);
            assertArrayEquals(bytes(10), server.objects.get("out/single.bin").data(), "the same client goes on");
        }
    }

    @Test
    public void bigFilesGoInPartsOf16MiBMoreWhen10000WouldNotHoldThem() {
        int mib = S3Fs.MIB;
        assertEquals(16 * mib, S3Fs.Tuning.DEFAULT.multipartThreshold());
        assertEquals(16 * mib, S3Fs.Tuning.DEFAULT.partSize());
        assertEquals(16L * mib, S3Fs.partSize(-1, 16 * mib), "an unknown size");
        assertEquals(16L * mib, S3Fs.partSize(100L << 30, 16 * mib));
        assertEquals(16L * mib, S3Fs.partSize(16L * mib * 9999, 16 * mib), "the first part may be smaller");
        assertEquals(21L * mib, S3Fs.partSize(200L << 30, 16 * mib), "200 GiB / 9999, in whole MiB");
    }

    @Test
    public void oneBufferIsReusedByTheUploadsOfAConnectionAndFreedWithIt() throws Exception {
        S3Fs fs = connect(SMALL);
        assertNull(fs.buffer(), "none before the first upload");
        fs.upload("k/big.bin", new ByteArrayInputStream(bytes(2500)), 2500, null);
        byte[] buffer = fs.buffer();
        assertEquals(1000, buffer.length, "the first part's size, the largest");
        fs.upload("k/small.bin", new ByteArrayInputStream(bytes(10)), 10, null);
        fs.upload("k/other.bin", new ByteArrayInputStream(bytes(1800)), -1, null);
        assertSame(buffer, fs.buffer());
        assertArrayEquals(bytes(10), server.objects.get("k/small.bin").data());
        assertArrayEquals(bytes(1800), server.objects.get("k/other.bin").data());
        fs.close();
        assertNull(fs.buffer(), "freed with the connection");
    }

    @Test
    public void aCompleteAnsweringAnErrorAfterItsStatusAborts() {
        server.fail(r -> r.startsWith("POST /photos/out/big.bin?uploadId="), 200, "InternalError",
                "We encountered an internal error. Please try again.", Map.of());
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> write("").write(item("big.bin", bytes(2500), DATE), CONTEXT));
        assertTrue(failure.getMessage().contains("InternalError"), failure.getMessage());
        assertEquals(1, server.aborted.size());
        assertEquals(Optional.empty(), server.object("out/big.bin"));
    }

    @Test
    public void aCancelledMultipartUploadIsAbortedAndKeepsTheInterrupt() throws Exception {
        for (Thread.Builder threads : List.of(Thread.ofPlatform(), Thread.ofVirtual())) {
            server.aborted.clear();
            S3Write write = write(", maxConnections: 1");
            byte[] big = bytes(2500);
            WorkItem cancelled = WorkItem.remote("big.bin", "/src", "test://src/big.bin", big.length, DATE,
                    () -> new InputStream() {
                        private final ByteArrayInputStream in = new ByteArrayInputStream(big);

                        @Override
                        public int read() {
                            return in.read();
                        }

                        @Override
                        public int read(byte[] b, int off, int len) {
                            if (big.length - in.available() >= 1200) {
                                Thread.currentThread().interrupt(); // a cancel of the run during the second part
                            }
                            return in.read(b, off, Math.min(len, 100));
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
            assertTrue(thread.join(Duration.ofSeconds(30)));
            assertInstanceOf(IllegalStateException.class, failure.get());
            assertTrue(interrupted.get(), "the interrupt is kept: " + failure.get());
            assertEquals(1, server.aborted.size(), "the upload is aborted on the interrupted thread");
            assertEquals(0, server.openUploads());
            assertEquals(Optional.empty(), server.object("out/big.bin"));

            assertFalse(write.write(item("a.jpg", "hello", DATE), CONTEXT).isSkipped());
            server.objects.clear();
            write.afterAll();
        }
    }

    @Test
    public void theSourceDeleterDeletesTheObject() {
        server.put("in/a.jpg", "aaa", UPLOADED);
        S3Write write = write(", deleteSource: true");
        WorkItem source = list(read("in", "")).items().getFirst();
        assertFalse(write.write(source, CONTEXT).isSkipped());
        assertEquals("aaa", server.content("out/a.jpg"));
        assertEquals(Optional.empty(), server.object("in/a.jpg"));
    }

    // ---- addresses

    @Test
    public void theVirtualHostedStyleNamesTheBucketInTheHost() throws Exception {
        server.close();
        server = S3TestServer.virtualHosted();
        S3Fs.Tuning proxied = new S3Fs.Tuning(1000, 400, Duration.ofSeconds(60),
                ProxySelector.of(new InetSocketAddress("127.0.0.1", server.port())), Clock.systemUTC());
        String connection = "endpoint: 'http://s3.test', region: '" + S3TestServer.REGION + "', bucket: '"
                + S3TestServer.BUCKET + "', accessKey: '" + S3TestServer.ACCESS_KEY + "', secretKey: '"
                + S3TestServer.SECRET_KEY + "'";
        S3Write write = writeJson("{" + connection + ", outPattern: 'out/{name}'}", proxied);
        assertFalse(write.write(item("a b.jpg", "hello", DATE), CONTEXT).isSkipped());
        assertFalse(write.write(item("big.bin", bytes(2500), DATE), CONTEXT).isSkipped());
        S3Read read = readJson("{" + connection + ", folder: 'out'}", proxied);
        Listing listing = list(read);
        assertEquals(List.of("s3://photos/out/a b.jpg", "s3://photos/out/big.bin"), sources(listing));
        assertEquals("hello", content(listing.items().getFirst()));
        assertTrue(server.hosts.stream().allMatch("photos.s3.test"::equals), server.hosts.toString());
        assertTrue(server.requests.contains("PUT /out/a%20b.jpg"), server.requests.toString());
        assertTrue(server.requests.stream().anyMatch(r -> r.startsWith("GET /?") && r.contains("list-type=2")),
                server.requests.toString());
        assertEquals(Set.of("net:s3:s3.test"), read.requiredResources(null));
    }

    @Test
    public void anonymousRequestsAreNotSigned() throws Exception {
        server.anonymous = true;
        server.put("in/a.jpg", "aaa", UPLOADED);
        S3Read read = readJson("{endpoint: 'http://127.0.0.1:" + server.port() + "', region: 'eu-west-3',"
                + " bucket: 'photos', pathStyle: true, folder: 'in'}", SMALL);
        assertEquals("aaa", content(list(read).items().getFirst()));
        assertEquals(server.requests.size(), server.unsigned.size());
    }

    // ---- failures

    @Test
    public void aWrongSecretKeyIsNamedWithoutShowingTheSignature() {
        S3Read read = readJson("{" + connection().replace(S3TestServer.SECRET_KEY, "wrong-secret") + ", folder: 'in'}",
                SMALL);
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> list(read));
        assertEquals("s3.read: cannot list s3://photos/in (the server refused the signature (SignatureDoesNotMatch):"
                + " check the secret key, the region and the clock of this computer)", failure.getMessage());
        for (Throwable e = failure; e != null; e = e.getCause()) {
            assertFalse(e.getMessage().contains("wrong-secret"), e.getMessage());
            assertFalse(e.getMessage().contains("Signature="), e.getMessage());
            assertFalse(e.getMessage().contains(S3TestServer.ACCESS_KEY), e.getMessage());
        }
    }

    @Test
    public void theErrorsOfS3AreTranslated() throws Exception {
        S3Read missing = readJson("{" + connection().replace("bucket: 'photos'", "bucket: 'albums'")
                + ", folder: 'in'}", SMALL);
        assertEquals("s3.read: cannot list s3://albums/in (the bucket albums does not exist)",
                assertThrows(IllegalStateException.class, () -> list(missing)).getMessage());

        try (S3Fs fs = connect(SMALL)) {
            server.fail(r -> r.startsWith("GET /photos?"), 301, "PermanentRedirect", "The bucket you are attempting"
                    + " to access must be addressed using the specified endpoint.", Map.of("x-amz-bucket-region",
                    "us-east-2"));
            assertEquals("the bucket photos is in the region us-east-2, not eu-west-3: set the region to us-east-2",
                    assertThrows(IOException.class, () -> fs.list("in")).getMessage());

            server.fail(r -> r.startsWith("HEAD "), 400, null, null, Map.of("x-amz-bucket-region", "us-east-2"));
            assertEquals("the bucket photos is in the region us-east-2, not eu-west-3: set the region to us-east-2",
                    assertThrows(IOException.class, () -> fs.stat("a.jpg")).getMessage());

            server.fail(r -> r.startsWith("GET /photos?"), 307, null, null, Map.of("Location", "http://elsewhere"));
            assertEquals("the server redirected the request (HTTP 307): check the endpoint and the region",
                    assertThrows(IOException.class, () -> fs.list("in")).getMessage());

            server.fail(r -> r.startsWith("HEAD "), 403, null, null, Map.of());
            assertEquals("access to s3://photos/a.jpg is denied: check the permissions of the keys on the bucket photos",
                    assertThrows(IOException.class, () -> fs.stat("a.jpg")).getMessage());

            server.fail(r -> r.startsWith("GET /photos/a.jpg"), 403, "RequestTimeTooSkewed",
                    "The difference between the request time and the current time is too large.", Map.of());
            assertEquals("the clock of this computer is too far from the server’s (RequestTimeTooSkewed): set it"
                    + " right", assertThrows(IOException.class, () -> fs.open("a.jpg")).getMessage());

            server.fail(r -> r.startsWith("GET /photos?"), 403, "InvalidAccessKeyId", "The AWS Access Key Id you"
                    + " provided does not exist in our records.", Map.of());
            assertEquals("the server does not know the access key (InvalidAccessKeyId)",
                    assertThrows(IOException.class, () -> fs.list("in")).getMessage());

            server.fail(r -> r.startsWith("DELETE "), 500, "Internal\nError", "x".repeat(500), Map.of());
            String text = assertThrows(IOException.class, () -> fs.delete("a.jpg")).getMessage();
            assertTrue(text.startsWith("the server answered 500 Internal Error: xxx"), text);
            assertTrue(text.length() < 260, "capped: " + text.length());

            server.fail(r -> r.startsWith("GET /photos?"), 200, null, null, Map.of());
            assertTrue(assertThrows(IOException.class, () -> fs.list("in")).getMessage()
                    .startsWith("the answer of the server cannot be read"));
        }
    }

    @Test
    public void aServerThatCannotBeReachedFailsTheListing() throws Exception {
        int port = server.port();
        server.close();
        S3Read read = readJson("{" + connection() + ", folder: 'in'}", SMALL);
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> list(read));
        assertTrue(failure.getMessage().startsWith("s3.read: cannot list s3://photos/in (the request to 127.0.0.1:"
                + port + " failed ("), failure.getMessage());
        server = S3TestServer.pathStyle();
    }

    @Test
    public void aStalledAnswerOrDownloadIsAbandoned() throws Exception {
        S3Fs.Tuning quick = new S3Fs.Tuning(1000, 400, Duration.ofSeconds(1), null, Clock.systemUTC());
        server.put("in/slow.jpg", "0123456789 and the rest", UPLOADED);
        server.stalledDownloads.add("in/slow.jpg");
        server.stalledAnswers.add("HEAD in/stuck.jpg");
        try (S3Fs fs = connect(quick)) {
            long start = System.nanoTime();
            IOException stat = assertThrows(IOException.class, () -> fs.stat("in/stuck.jpg"));
            assertTrue(stat.getMessage().startsWith("nothing moved with 127.0.0.1:"), stat.getMessage());
            assertTrue(stat.getMessage().endsWith(" for 1 s: the request is abandoned"), stat.getMessage());
            try (InputStream in = fs.open("in/slow.jpg")) {
                assertEquals("0123456789", new String(in.readNBytes(10), StandardCharsets.US_ASCII));
                Thread.sleep(1500); // the reader's own pace is not a stall
                IOException read = assertThrows(IOException.class, in::read);
                assertEquals("nothing moved with s3://photos/in/slow.jpg for 1 s: the request is abandoned",
                        read.getMessage());
            }
            assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(15)) < 0);
        }
    }

    @Test
    public void aStalledErrorAnswerIsAbandonedToo() throws Exception {
        server.stalledErrors.add("in/broken.jpg");
        S3Fs.Tuning quick = new S3Fs.Tuning(1000, 400, Duration.ofSeconds(1), null, Clock.systemUTC());
        try (S3Fs fs = connect(quick)) {
            long start = System.nanoTime();
            IOException failure = assertThrows(IOException.class, () -> fs.open("in/broken.jpg"));
            assertEquals("nothing moved with s3://photos/in/broken.jpg for 1 s: the request is abandoned",
                    failure.getMessage());
            assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(15)) < 0);
        }
    }

    @Test
    public void keysAreDecodedOnlyWhenTheAnswerSaysTheyAreEncoded() {
        String key = "in/a+b%41 c.jpg";
        server.put(key, "x", UPLOADED);
        assertEquals(List.of("s3://photos/" + key), sources(list(read("in", ""))), "encoded, then decoded");
        server.ignoresEncodingType = true;
        assertEquals(List.of("s3://photos/" + key), sources(list(read("in", ""))), "given as they are");

        server.ignoresEncodingType = false;
        server.rawKeysSaidEncoded = true;
        server.put("in/100%.jpg", "y", UPLOADED);
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> list(read("in", "")));
        assertTrue(failure.getMessage().startsWith("s3.read: cannot list s3://photos/in (the answer of the server"
                + " cannot be read ("), failure.getMessage());
    }

    @Test
    public void anInterruptedRequestStopsAndKeepsTheInterrupt() throws Exception {
        server.stalledAnswers.add("GET in/stuck.jpg");
        try (S3Fs fs = connect(SMALL)) {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicBoolean interrupted = new AtomicBoolean();
            Thread thread = Thread.ofVirtual().start(() -> {
                try {
                    fs.open("in/stuck.jpg");
                } catch (Throwable e) {
                    failure.set(e);
                }
                interrupted.set(Thread.currentThread().isInterrupted());
            });
            Thread.sleep(300);
            thread.interrupt();
            assertTrue(thread.join(Duration.ofSeconds(10)));
            assertInstanceOf(java.io.InterruptedIOException.class, failure.get());
            assertTrue(interrupted.get());
        }
    }

    // ---- dates

    @Test
    public void theDateIsWrittenInSecondsAndReadWithAnyFraction() {
        assertEquals("1714558530", S3Fs.mtime(DATE));
        assertEquals("1714558530.25", S3Fs.mtime(DATE.plusMillis(250)));
        assertEquals(DATE.plusNanos(123_456_789), S3Fs.parseMtime("1714558530.123456789"));
        assertEquals(DATE, S3Fs.parseMtime(" 1714558530 "));
        assertEquals(Instant.parse("1969-12-31T23:59:59.500Z"), S3Fs.parseMtime("-0.5"));
        assertNull(S3Fs.parseMtime("yesterday"));
    }

    // ---- configuration

    @Test
    public void theConfigurationNeverShowsItsKeys() {
        for (Object config : List.of(read("in", "").config(), write("").config())) {
            String text = config.toString();
            assertFalse(text.contains(S3TestServer.SECRET_KEY), text);
            assertFalse(text.contains(S3TestServer.ACCESS_KEY), text);
            assertTrue(text.contains("bucket=photos"), text);
        }
    }

    @Test
    public void theConfigurationIsChecked() {
        String keys = ", accessKey: 'a', secretKey: 's'";
        assertEquals("s3.read: the region is missing", assertThrows(IllegalArgumentException.class,
                () -> readJson("{bucket: 'b', folder: 'in'}", SMALL)).getMessage());
        assertEquals("s3.read: the bucket is missing", assertThrows(IllegalArgumentException.class,
                () -> readJson("{region: 'eu-west-3', folder: 'in'}", SMALL)).getMessage());
        assertEquals("s3.write: the bucket name \"a/b\" is not valid", assertThrows(IllegalArgumentException.class,
                () -> writeJson("{region: 'eu-west-3', bucket: 'a/b', outPattern: 'x'}", SMALL)).getMessage());
        assertEquals("s3.write: the region \"eu west\" is not valid", assertThrows(IllegalArgumentException.class,
                () -> writeJson("{region: 'eu west', bucket: 'b', outPattern: 'x'}", SMALL)).getMessage());
        assertEquals("s3.read: give both the access key and the secret key, or neither for a public bucket",
                assertThrows(IllegalArgumentException.class, () -> readJson("{region: 'r', bucket: 'bb',"
                        + " accessKey: 'a', folder: 'in'}", SMALL)).getMessage());
        for (String endpoint : List.of("ftp://host", "https://host/path", "https://user@host", "minio.local:9000",
                "https://host?x=1")) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> readJson("{endpoint: '" + endpoint + "', region: 'r', bucket: 'bb', pathStyle: true,"
                            + " folder: 'in'" + keys + "}", SMALL), endpoint);
            assertTrue(failure.getMessage().startsWith("s3.read: the endpoint \"" + endpoint + "\" is not valid"),
                    failure.getMessage());
        }
        assertEquals("s3.read: the endpoint 192.168.1.5 is an address, the bucket cannot be put in it: turn on"
                + " \"Bucket in the path\"", assertThrows(IllegalArgumentException.class,
                () -> readJson("{endpoint: 'http://192.168.1.5:9000', region: 'r', bucket: 'bb', folder: 'in'}",
                        SMALL)).getMessage());
        readJson("{endpoint: 'https://MinIO.local:9000/', region: 'us-east-1', bucket: 'bbb', folder: 'in'}", SMALL);
    }

    @Test
    public void aBucketNameThatCannotBeAServerNameNeedsTheBucketInThePath() throws Exception {
        for (String bucket : List.of("My_Photos", "ab", "a..b", "photos-", "photos.", "x".repeat(64))) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> readJson("{region: 'eu-west-3', bucket: '" + bucket + "', folder: 'in'}", SMALL), bucket);
            assertEquals("s3.read: the bucket name \"" + bucket + "\" cannot be put in a server name (lower case"
                    + " letters, digits, dots and hyphens, 3 to 63 characters): turn on \"Bucket in the path\"",
                    failure.getMessage());
            readJson("{endpoint: 'https://minio.local', region: 'us-east-1', bucket: '" + bucket + "',"
                    + " pathStyle: true, folder: 'in'}", SMALL);
        }
        readJson("{region: 'eu-west-3', bucket: 'my.photos-2024', folder: 'in'}", SMALL);

        // an unchecked configuration: a failure of the request, never an exception of the URI
        S3ReadConfig unchecked = new S3ReadConfig("http://s3.test", S3TestServer.REGION, "My_Photos",
                S3TestServer.ACCESS_KEY, S3TestServer.SECRET_KEY, false, "/", null, null, null, null, null);
        try (S3Fs fs = S3Fs.connect(unchecked, plugin(), SMALL)) {
            String expected = "the bucket name \"My_Photos\" cannot be put in a server name: turn on \"Bucket in the"
                    + " path\"";
            assertEquals(expected, assertThrows(IOException.class, () -> fs.list("in")).getMessage());
            assertEquals(expected, assertThrows(IOException.class, () -> fs.stat("a.jpg")).getMessage());
            assertEquals(expected, assertThrows(IOException.class, () -> fs.open("a.jpg")).getMessage());
            assertEquals(expected, assertThrows(IOException.class, () -> fs.delete("a.jpg")).getMessage());
            assertEquals(expected, assertThrows(IOException.class,
                    () -> fs.upload("a.jpg", new ByteArrayInputStream(bytes(10)), 10, null)).getMessage());
        }
    }

    @Test
    public void theEndpointGivesTheResourceAndAnHttpEndpointIsWarned() {
        S3Read aws = readJson("{region: 'eu-west-3', bucket: 'bk1', folder: 'in'}", SMALL);
        assertEquals(Set.of("net:s3:s3.eu-west-3.amazonaws.com"), aws.requiredResources(null));
        assertEquals(List.of(), aws.configWarnings());
        assertEquals(URI("https://s3.eu-west-3.amazonaws.com"), S3Connections.endpoint(aws.config()));
        S3Write minio = writeJson("{endpoint: 'http://MinIO.local:9000', region: 'us-east-1', bucket: 'b2',"
                + " pathStyle: true, outPattern: 'x'}", SMALL);
        assertEquals(Set.of("net:s3:minio.local"), minio.requiredResources(null));
        assertEquals(List.of("s3.write: with an http:// endpoint, the files cross the network in clear (the secret"
                + " key is never sent); prefer https://"), minio.configWarnings());
        assertFalse(aws.signalsFolders());
    }

    private static java.net.URI URI(String text) {
        return java.net.URI.create(text);
    }

    @Test
    public void theEditorOffersASecretAndTheFieldsWithTheirTexts() {
        CombinedResourceBundle bundle = plugin().getResourceBundle();
        for (var action : List.of(new S3Read(), new S3Write())) {
            String code = action instanceof S3Read ? "s3.read" : "s3.write";
            ConfigSchema schema = action.configSchema().orElseThrow().withKeyPrefix("plugin.remote." + code);
            assertTrue(schema.field("secretKey").orElseThrow().hints().contains(FieldHint.SECRET));
            assertFalse(schema.field("accessKey").orElseThrow().hints().contains(FieldHint.SECRET));
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

    @Test
    public void aContentThatCannotBeReadNamesTheObject() {
        S3Read read = read("in", "");
        server.put("in/a.jpg", "aaa", UPLOADED);
        WorkItem item = list(read).items().getFirst();
        server.objects.clear();
        UncheckedIOException failure = assertThrows(UncheckedIOException.class, item::openInputStream);
        assertEquals("Cannot read s3://photos/in/a.jpg (s3://photos/in/a.jpg does not exist)", failure.getMessage());
    }
}

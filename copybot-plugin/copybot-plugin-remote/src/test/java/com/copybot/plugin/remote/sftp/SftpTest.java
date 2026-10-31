package com.copybot.plugin.remote.sftp;

import com.copybot.engine.plugin.ConfirmationHandler;
import com.copybot.engine.plugin.Confirmations;
import com.copybot.engine.plugin.PluginDataRoot;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import com.copybot.plugin.api.action.WriteContext;
import com.copybot.plugin.api.action.WriteResult;
import com.copybot.plugin.api.config.ConfigField;
import com.copybot.plugin.api.config.ConfigSchema;
import com.copybot.plugin.api.config.FieldHint;
import com.copybot.plugin.remote.RemotePlugin;
import com.copybot.resources.CombinedResourceBundle;
import com.google.gson.JsonParser;
import com.jcraft.jsch.JSch;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.mac.BuiltinMacs;
import org.apache.sshd.common.signature.BuiltinSignatures;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.server.SftpEventListener;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.KeyPair;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** sftp.read and sftp.write against an embedded SFTP server (Apache MINA SSHD): no external network. */
public class SftpTest {

    private static final Instant DATE = Instant.parse("2024-05-01T10:15:30Z");
    private static final WriteContext CONTEXT = new WriteContext("run1");

    @TempDir
    Path temp;

    private Path root;
    private Path data;
    private KeyPair hostKey;
    private SftpTestServer server;

    private final List<String> questions = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean answer = true;
    private volatile long answerDelayMillis;
    private ConfirmationHandler previousHandler;

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
        hostKey = SftpTestServer.ecKey();
        server = SftpTestServer.start(root, hostKey);
    }

    @AfterEach
    public void tearDown() throws IOException {
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

    private String connection() {
        return "host: '127.0.0.1', port: " + server.port() + ", user: 'alice', password: '"
                + SftpTestServer.PASSWORD + "'";
    }

    private SftpRead read(String json) {
        SftpRead action = new SftpRead();
        action.setPlugin(plugin());
        action.loadConfig(JsonParser.parseString(json));
        return action;
    }

    private SftpWrite writeJson(String json) {
        SftpWrite action = new SftpWrite();
        action.setPlugin(plugin());
        action.loadConfig(JsonParser.parseString(json));
        return action;
    }

    private SftpRead read(String folder, String extra) {
        return read("{" + connection() + ", folder: '" + folder + "'" + extra + "}");
    }

    private SftpWrite write(String extra) {
        return writeJson("{" + connection() + ", outPattern: '/out/{name}'" + extra + "}");
    }

    private static List<WorkItem> list(SftpRead read) {
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

    private Path knownHosts() {
        return data.resolve("remote").resolve("known_hosts");
    }

    private void putFile(String path, String content, Instant date) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        Files.setLastModifiedTime(file, FileTime.from(date));
    }

    private void trustServer() throws IOException {
        Files.createDirectories(knownHosts().getParent());
        Files.writeString(knownHosts(), SftpTestServer.knownHostsLine(server.knownHostsName(), hostKey.getPublic())
                + "\n");
    }

    private List<String> filesUnder(String folder) throws IOException {
        try (Stream<Path> files = Files.walk(root.resolve(folder))) {
            return files.filter(Files::isRegularFile).map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .sorted().toList();
        }
    }

    // ---- host keys

    @Test
    public void anUnknownServerIsAskedOnceThenRecorded() throws Exception {
        putFile("in/a.jpg", "hello", DATE);
        List<WorkItem> items = list(read("/in", ""));
        assertEquals(1, items.size());
        assertEquals(1, questions.size());
        String question = questions.getFirst();
        assertTrue(question.contains("127.0.0.1:" + server.port()), question);
        assertTrue(question.contains(SftpTestServer.fingerprint(hostKey.getPublic())), question);
        assertTrue(question.contains("ecdsa-sha2-nistp256"), question);
        assertFalse(question.contains(SftpTestServer.PASSWORD), question);

        List<String> lines = Files.readAllLines(knownHosts());
        assertEquals(List.of(SftpTestServer.knownHostsLine(server.knownHostsName(), hostKey.getPublic())), lines);

        list(read("/in", "")); // another step, another run: the server is known
        assertEquals(1, questions.size());
        assertEquals("hello", content(items.getFirst()));
        assertEquals(1, questions.size());
    }

    @Test
    public void anUnknownServerRefusedFailsAndIsNotAskedAgainByTheStep() throws Exception {
        answer = false;
        SftpRead read = read("/in", "");
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> list(read));
        assertTrue(failure.getMessage().contains("not trusted"), failure.getMessage());
        assertTrue(failure.getMessage().contains(SftpTestServer.fingerprint(hostKey.getPublic())), failure.getMessage());
        assertFalse(Files.exists(knownHosts()) && !Files.readString(knownHosts()).isBlank());

        assertThrows(IllegalStateException.class, () -> list(read));
        assertEquals(1, questions.size(), "the answer no is remembered by the step");
    }

    @Test
    public void manyConnectionsAtOnceAskOnce() throws Exception {
        answerDelayMillis = 300;
        SftpWrite write = write(", maxConnections: 4");
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
        assertEquals(1, questions.size());
        assertEquals(1, Files.readAllLines(knownHosts()).size());
        assertEquals(6, filesUnder("out").size());
        write.afterAll();
    }

    @Test
    public void manyConnectionsAtOnceRefusedAskOnce() throws Exception {
        answer = false;
        answerDelayMillis = 300;
        SftpWrite write = write(", maxConnections: 4");
        ExecutorService threads = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                WorkItem item = item("f" + i + ".txt", "content " + i, DATE);
                results.add(threads.submit(() -> assertThrows(IllegalStateException.class,
                        () -> write.write(item, CONTEXT))));
            }
            for (Future<?> result : results) {
                result.get(30, TimeUnit.SECONDS);
            }
        } finally {
            threads.shutdownNow();
        }
        assertEquals(1, questions.size());
    }

    @Test
    public void aChangedKeyIsRefusedWithoutAsking() throws Exception {
        KeyPair recorded = SftpTestServer.ecKey();
        Files.createDirectories(knownHosts().getParent());
        Files.writeString(knownHosts(), "# my servers\n"
                + SftpTestServer.knownHostsLine(server.knownHostsName(), recorded.getPublic()) + "\n");
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> list(read("/in", "")));
        String message = failure.getMessage();
        assertTrue(message.contains("changed"), message);
        assertTrue(message.contains(SftpTestServer.fingerprint(recorded.getPublic())), message);
        assertTrue(message.contains(SftpTestServer.fingerprint(hostKey.getPublic())), message);
        assertTrue(message.contains(knownHosts().toString()), message);
        assertEquals(List.of(), questions);
        assertEquals(2, Files.readAllLines(knownHosts()).size(), "known_hosts is left as it is");
    }

    @Test
    public void aRecordedKeyOfAnotherServerDoesNotCount() throws Exception {
        Files.createDirectories(knownHosts().getParent());
        Files.writeString(knownHosts(), SftpTestServer.knownHostsLine("other.example.org", hostKey.getPublic()) + "\n");
        putFile("in/a.jpg", "hello", DATE);
        list(read("/in", ""));
        assertEquals(1, questions.size());
        assertEquals(2, Files.readAllLines(knownHosts()).size(), "the line is added, the others kept");
    }

    @Test
    public void aServerSigningWithSha1RsaOnlyIsRefused() throws Exception {
        server.close();
        KeyPair rsa = SftpTestServer.rsaKey();
        server = SftpTestServer.start(root, rsa, sshd -> sshd.setSignatureFactories(List.of(BuiltinSignatures.rsa)));
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> list(read("/in", "")));
        assertEquals(List.of(), questions, failure.getMessage());
    }

    @Test
    public void anRsaServerKeyIsUsedWithSha2() throws Exception {
        server.close();
        KeyPair rsa = SftpTestServer.rsaKey();
        server = SftpTestServer.start(root, rsa);
        putFile("in/a.jpg", "hello", DATE);
        assertEquals(1, list(read("/in", "")).size());
        assertEquals(1, questions.size());
        assertTrue(questions.getFirst().contains("ssh-rsa"), questions.getFirst());
    }

    @Test
    public void aRevokedKeyIsRefusedWithoutAsking() throws Exception {
        Files.createDirectories(knownHosts().getParent());
        Files.writeString(knownHosts(), "@revoked * " + PublicKeyEntry.toString(hostKey.getPublic()) + "\n");
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> list(read("/in", "")));
        String message = failure.getMessage();
        assertTrue(message.contains("revoked"), message);
        assertTrue(message.contains(SftpTestServer.fingerprint(hostKey.getPublic())), message);
        assertEquals(List.of(), questions);

        // recorded too: revoked all the same
        Files.writeString(knownHosts(), SftpTestServer.knownHostsLine(server.knownHostsName(), hostKey.getPublic())
                + "\n@revoked * " + PublicKeyEntry.toString(hostKey.getPublic()) + "\n", StandardOpenOption.APPEND);
        failure = assertThrows(IllegalStateException.class, () -> list(read("/in", "")));
        assertTrue(failure.getMessage().contains("revoked"), failure.getMessage());
        assertEquals(List.of(), questions);
    }

    @Test
    public void anUnreadableKnownHostsFileFailsTheConnection() throws Exception {
        Files.createDirectories(knownHosts()); // a folder where the file should be
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> list(read("/in", "")));
        assertTrue(failure.getMessage().contains(knownHosts().toString()), failure.getMessage());
        assertEquals(List.of(), questions);
    }

    @Test
    public void aServerWithSha1MacsOnlyIsRefused() throws Exception {
        server.close();
        server = SftpTestServer.start(root, hostKey, sshd -> sshd.setMacFactories(List.of(BuiltinMacs.hmacsha1)));
        trustServer();
        putFile("in/a.jpg", "hello", DATE);
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> list(read("/in", "")));
        assertTrue(failure.getMessage().contains("cannot connect"), failure.getMessage());
    }

    // ---- authentication

    @Test
    public void aWrongPasswordFailsWithoutShowingIt() throws Exception {
        trustServer();
        SftpRead read = read("{host: '127.0.0.1', port: " + server.port()
                + ", user: 'alice', password: 'wrong-Secret-42', folder: '/in'}");
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> list(read));
        assertTrue(failure.getMessage().contains("authentication"), failure.getMessage());
        assertFalse(failure.getMessage().contains("wrong-Secret-42"), failure.getMessage());
    }

    @Test
    public void aPrivateKeyAuthenticates() throws Exception {
        trustServer();
        Path key = temp.resolve("id_ecdsa");
        com.jcraft.jsch.KeyPair pair = com.jcraft.jsch.KeyPair.genKeyPair(new JSch(), com.jcraft.jsch.KeyPair.ECDSA, 256);
        pair.writePrivateKey(key.toString());
        server.authorizedKeys = List.of(SftpTestServer.publicKey(pair.getPublicKeyBlob()));
        putFile("in/a.jpg", "hello", DATE);
        SftpRead read = read("{host: '127.0.0.1', port: " + server.port() + ", user: 'alice', privateKey: '"
                + key.toString().replace("\\", "\\\\") + "', folder: '/in'}");
        assertEquals(1, list(read).size());
    }

    @Test
    public void aPrivateKeyWithAPassphraseAuthenticates() throws Exception {
        trustServer();
        Path key = temp.resolve("id_ecdsa");
        com.jcraft.jsch.KeyPair pair = com.jcraft.jsch.KeyPair.genKeyPair(new JSch(), com.jcraft.jsch.KeyPair.ECDSA, 256);
        pair.writePrivateKey(key.toString(), "my-Passphrase-7".getBytes(StandardCharsets.UTF_8));
        server.authorizedKeys = List.of(SftpTestServer.publicKey(pair.getPublicKeyBlob()));
        putFile("in/a.jpg", "hello", DATE);
        String json = "{host: '127.0.0.1', port: " + server.port() + ", user: 'alice', privateKey: '"
                + key.toString().replace("\\", "\\\\") + "', folder: '/in', passphrase: ";
        assertEquals(1, list(read(json + "'my-Passphrase-7'}")).size());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> list(read(json + "'bad-Passphrase-9'}")));
        assertFalse(failure.getMessage().contains("bad-Passphrase-9"), failure.getMessage());
        assertTrue(failure.getMessage().contains("id_ecdsa"), failure.getMessage());
    }

    @Test
    public void theConfigurationNeverShowsItsSecrets() {
        SftpRead read = read("/in", ", passphrase: 'pass-Phrase-1', privateKey: 'k'");
        SftpWrite write = write(", passphrase: 'pass-Phrase-1'");
        for (Object config : List.of(read.config(), write.config())) {
            String text = config.toString();
            assertFalse(text.contains(SftpTestServer.PASSWORD), text);
            assertFalse(text.contains("pass-Phrase-1"), text);
            assertFalse(text.contains("alice"), text);
            assertTrue(text.contains("127.0.0.1"), text);
        }
    }

    @Test
    public void theConfigurationIsChecked() {
        assertThrows(IllegalArgumentException.class, () -> read("{user: 'alice', folder: '/in'}"));
        assertThrows(IllegalArgumentException.class, () -> read("{host: 'nas', folder: '/in'}"));
        assertThrows(IllegalArgumentException.class, () -> read("{host: 'nas', user: 'a', port: 0, folder: '/in'}"));
        IllegalArgumentException port = assertThrows(IllegalArgumentException.class,
                () -> writeJson("{host: 'nas', user: 'a', port: 70000, outPattern: 'x'}"));
        assertTrue(port.getMessage().contains("the port must be between 1 and 65535"), port.getMessage());
        assertThrows(IllegalArgumentException.class, () -> read("{host: 'nas', user: 'a'}"));
    }

    @Test
    public void theEditorOffersSecretsAndAKeyFileWithTheirTexts() {
        CombinedResourceBundle bundle = plugin().getResourceBundle();
        for (var action : List.of(new SftpRead(), new SftpWrite())) {
            String code = action instanceof SftpRead ? "sftp.read" : "sftp.write";
            ConfigSchema schema = action.configSchema().orElseThrow().withKeyPrefix("plugin.remote." + code);
            assertTrue(schema.field("password").orElseThrow().hints().contains(FieldHint.SECRET));
            assertTrue(schema.field("passphrase").orElseThrow().hints().contains(FieldHint.SECRET));
            assertTrue(schema.field("privateKey").orElseThrow().hints().contains(FieldHint.FILE));
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
    public void theServerIsTheResource() {
        assertEquals(Set.of("net:sftp:nas.local"), read("{host: 'NAS.local', user: 'a', folder: '/'}")
                .requiredResources(null));
        assertEquals(Set.of("net:sftp:nas.local"), writeJson("{host: 'NAS.local', user: 'a', outPattern: '/x'}")
                .requiredResources(null));
    }

    // ---- files

    @Test
    public void readsATreeWithSizesDatesAndContents() throws Exception {
        trustServer();
        putFile("in/a.jpg", "hello", DATE);
        putFile("in/sub/b.png", "world!", DATE.plusSeconds(60));
        putFile("in/.hidden", "x", DATE);
        SftpRead read = read("/in", "");
        List<WorkItem> items = list(read);
        assertEquals(List.of("a.jpg", "b.png"), items.stream().map(WorkItem::getNameDisplay).toList());
        WorkItem a = items.get(0);
        assertEquals("sftp://127.0.0.1/in/a.jpg", a.getSourceLocationDisplay());
        assertEquals(5L, a.getMetadatas().getSize());
        assertEquals(DATE, a.getMetadatas().getTime(WorkItemMetadata.LAST_MODIFIED).orElseThrow());
        assertEquals(DATE.plusSeconds(60),
                items.get(1).getMetadatas().getTime(WorkItemMetadata.LAST_MODIFIED).orElseThrow());
        assertEquals("hello", content(a));
        assertEquals("world!", content(items.get(1)));
        assertEquals("hello", content(a), "each open reads again");
        read.afterAll();
    }

    @Test
    public void aRelativeFolderIsUnderTheStartingFolder() throws Exception {
        trustServer();
        putFile("in/a.jpg", "hello", DATE);
        List<WorkItem> items = list(read("in", ""));
        assertEquals(1, items.size());
        assertEquals("hello", content(items.getFirst()));
    }

    @Test
    public void aMissingFolderFailsTheListing() throws Exception {
        trustServer();
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> list(read("/nothing", "")));
        assertTrue(failure.getMessage().contains("sftp://127.0.0.1/nothing"), failure.getMessage());
    }

    @Test
    public void theSourceDeleterDeletesTheRemoteFile() throws Exception {
        trustServer();
        putFile("in/a.jpg", "hello", DATE);
        WorkItem a = list(read("/in", "")).getFirst();
        a.getSourceDeleter().orElseThrow().delete();
        assertFalse(Files.exists(root.resolve("in/a.jpg")));
    }

    @Test
    public void writesWithTheDateThenReadsBack() throws Exception {
        trustServer();
        SftpWrite write = writeJson("{" + connection() + ", outPattern: '/out/{lastModified.Y}/{name}'}");
        assertFalse(write.write(item("a.jpg", "hello", DATE), CONTEXT).isSkipped());
        assertFalse(write.write(item("b é.txt", "world!", DATE.plusSeconds(3600)), CONTEXT).isSkipped());
        write.afterAll();
        assertEquals(List.of("out/2024/a.jpg", "out/2024/b é.txt"), filesUnder("out"));

        List<WorkItem> items = list(read("/out", ""));
        assertEquals(List.of("a.jpg", "b é.txt"), items.stream().map(WorkItem::getNameDisplay).toList());
        assertEquals("hello", content(items.get(0)));
        assertEquals("world!", content(items.get(1)));
        assertEquals(DATE, items.get(0).getMetadatas().getTime(WorkItemMetadata.LAST_MODIFIED).orElseThrow());
        assertEquals(DATE.plusSeconds(3600),
                items.get(1).getMetadatas().getTime(WorkItemMetadata.LAST_MODIFIED).orElseThrow());
    }

    @Test
    public void conflictsRenameSkipAndOverwrite() throws Exception {
        trustServer();
        putFile("out/a.jpg", "old content", DATE);
        SftpWrite rename = write("");
        assertFalse(rename.write(item("a.jpg", "new", DATE), CONTEXT).isSkipped());
        assertEquals("new", Files.readString(root.resolve("out/a (1).jpg")));
        assertTrue(rename.write(item("a.jpg", "new", DATE), CONTEXT).isSkipped(), "the renamed copy is found again");

        SftpWrite overwrite = write(", onConflict: {ifDifferent: 'overwrite'}");
        assertFalse(overwrite.write(item("a.jpg", "replaced", DATE), CONTEXT).isSkipped());
        assertEquals("replaced", Files.readString(root.resolve("out/a.jpg")));
        assertEquals(List.of("out/a (1).jpg", "out/a.jpg"), filesUnder("out"));
    }

    @Test
    public void aReRunWithSizeAndDateSkipsWhatIsWritten() throws Exception {
        trustServer();
        String extra = ", onConflict: {compare: 'sizeAndDate'}";
        assertFalse(write(extra).write(item("a.jpg", "hello", DATE), CONTEXT).isSkipped());
        WriteResult again = write(extra).write(item("a.jpg", "hello", DATE), new WriteContext("run2"));
        assertTrue(again.isSkipped(), "the date written is the item's");
        assertEquals(List.of("out/a.jpg"), filesUnder("out"));
        assertEquals(DATE, Files.getLastModifiedTime(root.resolve("out/a.jpg")).toInstant());
    }

    @Test
    public void aCancelledUploadLeavesNothingAndTheStepGoesOn() throws Exception {
        trustServer();
        SftpWrite write = write(", maxConnections: 1");
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
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> write.write(cancelled, CONTEXT));
        assertTrue(Thread.interrupted(), "the interrupt is kept: " + failure);
        assertEquals(List.of(), filesUnder("out"), "the temporary file is deleted");

        assertFalse(write.write(item("a.jpg", "hello", DATE), CONTEXT).isSkipped());
        assertEquals(List.of("out/a.jpg"), filesUnder("out"));
    }

    @Test
    public void aFailingSourceDeletesTheTemporaryFile() throws Exception {
        trustServer();
        SftpWrite write = write("");
        WorkItem failing = WorkItem.remote("bad.bin", "/src", "test://src/bad.bin", 100_000, DATE,
                () -> new InputStream() {
                    private int count;

                    @Override
                    public int read() throws IOException {
                        if (++count > 50_000) {
                            throw new IOException("disk gone");
                        }
                        return 1;
                    }
                }, () -> { });
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> write.write(failing, CONTEXT));
        assertTrue(failure.getMessage().contains("disk gone"), failure.getMessage());
        assertEquals(List.of(), filesUnder("out"));
    }

    @Test
    public void anInterruptWhileTheDateIsSetIsKeptAndLeavesNoTemporaryFile() throws Exception {
        server.close();
        CountDownLatch dating = new CountDownLatch(1);
        AtomicBoolean slow = new AtomicBoolean(true);
        server = SftpTestServer.start(root, hostKey, sshd -> {
            SftpSubsystemFactory factory = new SftpSubsystemFactory();
            factory.addSftpEventListener(new SftpEventListener() {
                @Override
                public void modifyingAttributes(ServerSession session, Path path, Map<String, ?> attrs) {
                    if (path.getFileName().toString().endsWith(".copybot-tmp") && slow.compareAndSet(true, false)) {
                        dating.countDown();
                        try {
                            Thread.sleep(1500); // the answer comes after the cancel
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            });
            sshd.setSubsystemFactories(List.of(factory));
        });
        trustServer();
        SftpWrite write = write(", maxConnections: 1");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interruptKept = new AtomicBoolean();
        Thread writer = new Thread(() -> {
            try {
                write.write(item("a.jpg", "hello", DATE), CONTEXT);
            } catch (Throwable e) {
                failure.set(e);
            }
            interruptKept.set(Thread.interrupted());
        });
        writer.start();
        assertTrue(dating.await(20, TimeUnit.SECONDS), "the date is set");
        writer.interrupt(); // a cancel of the run while JSch waits for the server
        writer.join(30_000);
        assertFalse(writer.isAlive());
        assertNotNull(failure.get(), "the write fails");
        assertTrue(interruptKept.get(), "the interrupt is kept: " + failure.get());
        Thread.sleep(2000); // the late answer of the server
        assertEquals(List.of(), filesUnder("out"), "the temporary file is deleted");

        assertFalse(write.write(item("b.jpg", "hello", DATE), CONTEXT).isSkipped(), "the connection is usable");
        assertEquals(List.of("out/b.jpg"), filesUnder("out"));
    }

    @Test
    public void anAnswerLeftUnreadByAnInterruptIsNotTakenForTheNextOne() throws Exception {
        server.close();
        CountDownLatch asked = new CountDownLatch(1);
        AtomicBoolean slow = new AtomicBoolean(true);
        server = SftpTestServer.start(root, hostKey, sshd -> {
            SftpSubsystemFactory factory = new SftpSubsystemFactory();
            factory.addSftpEventListener(new SftpEventListener() {
                @Override
                public void received(ServerSession session, int type, int id) {
                    if (type == SftpConstants.SSH_FXP_STAT && slow.compareAndSet(true, false)) {
                        asked.countDown();
                        try {
                            Thread.sleep(1500); // the answer comes after the cancel
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            });
            sshd.setSubsystemFactories(List.of(factory));
        });
        trustServer();
        putFile("a.txt", "12345", DATE);
        putFile("b.txt", "12345678901", DATE);
        RemotePlugin plugin = plugin();
        SftpFs fs = SftpFs.connect(write("").config(), new HostTrust(plugin), plugin);
        try {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicBoolean interruptKept = new AtomicBoolean();
            AtomicReference<Long> nextSize = new AtomicReference<>();
            Thread caller = new Thread(() -> {
                try {
                    fs.stat("/a.txt");
                } catch (Throwable e) {
                    failure.set(e);
                }
                interruptKept.set(Thread.interrupted());
                try {
                    nextSize.set(fs.stat("/b.txt").orElseThrow().size());
                } catch (Throwable e) {
                    failure.set(e);
                }
            });
            caller.start();
            assertTrue(asked.await(20, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(30_000);
            assertFalse(caller.isAlive());
            assertTrue(interruptKept.get(), "the interrupt is kept: " + failure.get());
            assertInstanceOf(InterruptedIOException.class, failure.get());
            assertEquals(11L, nextSize.get(), "the answer about b.txt, not the late one about a.txt");
        } finally {
            fs.close();
        }
    }

    @Test
    public void aFileInTheWayOfAFolderIsNamed() throws Exception {
        trustServer();
        putFile("out/x", "a file", DATE);
        SftpWrite write = writeJson("{" + connection() + ", outPattern: '/out/x/{name}'}");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> write.write(item("a.jpg", "hello", DATE), CONTEXT));
        assertTrue(failure.getMessage().contains("sftp://127.0.0.1/out/x is not a folder"), failure.getMessage());
    }

    @Test
    public void aLinkToAFolderIsNotFollowed() throws Exception {
        trustServer();
        putFile("in/a.jpg", "hello", DATE);
        putFile("in/sub/b.jpg", "world", DATE);
        try {
            Files.createSymbolicLink(root.resolve("in/loop"), root.resolve("in"));
            Files.createSymbolicLink(root.resolve("in/sub/linked.jpg"), root.resolve("in/a.jpg"));
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.abort("no symbolic links here: " + e);
        }
        List<WorkItem> items = list(read("/in", ""));
        assertEquals(List.of("a.jpg", "b.jpg", "linked.jpg"),
                items.stream().map(WorkItem::getNameDisplay).sorted().toList());
    }

    @Test
    public void contentsAreReadOnPooledConnections() throws Exception {
        trustServer();
        for (int i = 0; i < 10; i++) {
            putFile("in/f" + i + ".txt", "content " + i, DATE);
        }
        SftpRead read = read("/in", ", maxConnections: 2");
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
}

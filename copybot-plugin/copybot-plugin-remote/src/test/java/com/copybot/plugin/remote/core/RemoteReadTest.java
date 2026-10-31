package com.copybot.plugin.remote.core;

import com.copybot.plugin.api.action.ListingSink;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class RemoteReadTest {

    private static final Instant DATE = Instant.parse("2024-05-01T10:15:30Z");

    private final InMemoryServer server = new InMemoryServer();

    /** What a listing sends, in order: "root /in", "item /in/a.jpg", "done /in". */
    private static final class Recorder implements ListingSink {
        final List<String> events = new ArrayList<>();
        final List<WorkItem> items = new ArrayList<>();

        @Override
        public void root(String folder) {
            events.add("root " + folder);
        }

        @Override
        public void item(WorkItem item) {
            items.add(item);
            events.add("item " + item.getListedFolder() + "/" + item.getNameDisplay());
        }

        @Override
        public void folderDone(String folder) {
            events.add("done " + folder);
        }
    }

    private Recorder list(String config) {
        Recorder recorder = new Recorder();
        MemoryActions.read(server, config).listFiles(recorder);
        return recorder;
    }

    private void sampleTree() {
        server.put("/in/b.jpg", "bb", DATE);
        server.put("/in/a.JPG", "a", DATE);
        server.put("/in/sub/c.jpg", "ccc", DATE);
        server.put("/in/sub/deeper/d.png", "dddd", DATE);
        server.put("/in/other/e.txt", "e", DATE);
        server.put("/elsewhere/f.jpg", "f", DATE);
    }

    @Test
    public void listsTheTreeEachFolderDoneAfterItsContent() {
        sampleTree();
        Recorder recorder = list("{host: 'nas', folder: '\\\\in//./'}"); // normalized as a write's target
        assertEquals(List.of(
                "root /in",
                "item /in/a.JPG", "item /in/b.jpg",
                "item /in/other/e.txt", "done /in/other",
                "item /in/sub/c.jpg",
                "item /in/sub/deeper/d.png", "done /in/sub/deeper",
                "done /in/sub",
                "done /in"), recorder.events);
        assertEquals(1, server.open(), "one connection, kept idle for the contents");
    }

    @Test
    public void anItemTellsItsSourceSizeAndDate() throws IOException {
        sampleTree();
        WorkItem c = list("{host: 'nas', folder: '/in'}").items.stream()
                .filter(i -> i.getNameDisplay().equals("c.jpg")).findFirst().orElseThrow();
        assertEquals("mem://nas/in/sub/c.jpg", c.getSourceLocationDisplay());
        assertEquals("/in/sub", c.getListedFolder());
        assertEquals(3L, c.getMetadatas().getSize());
        assertEquals(DATE, c.getMetadatas().getTime(WorkItemMetadata.LAST_MODIFIED).orElseThrow());
        assertFalse(c.isLocal());
        try (InputStream in = c.openInputStream()) {
            assertEquals("ccc", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    public void aRelativeFolderGivesRelativePaths() {
        server.put("photos/a.jpg", "a", DATE);
        server.folder("photos");
        WorkItem a = list("{host: 'nas', folder: 'photos'}").items.getFirst();
        assertEquals("photos", a.getListedFolder());
        assertEquals("mem://nas/photos/a.jpg", a.getSourceLocationDisplay());
    }

    @Test
    public void notRecursiveListsTheFirstLevelOnly() {
        sampleTree();
        Recorder recorder = list("{host: 'nas', folder: '/in', recursive: false}");
        assertEquals(List.of("root /in", "item /in/a.JPG", "item /in/b.jpg", "done /in"), recorder.events);
        assertEquals(List.of("list /in"), server.operations);
    }

    @Test
    public void globsAreCaseInsensitiveRelativeToTheFolder() {
        sampleTree();
        assertEquals(List.of("a.JPG", "b.jpg", "c.jpg"),
                names(list("{host: 'nas', folder: '/in', include: ['**/*.jpg'], exclude: ['other/**']}")));
        assertEquals(List.of("c.jpg"), names(list("{host: 'nas', folder: '/in', include: ['SUB/*.JPG']}")));
        assertEquals(List.of("a.JPG", "b.jpg", "e.txt"),
                names(list("{host: 'nas', folder: '/in', exclude: ['sub/**']}")));
        assertEquals(List.of("a.JPG", "d.png"),
                names(list("{host: 'nas', folder: '/in', include: ['{a,e,d}.*', '**/d.png']}")));
    }

    @Test
    public void anExcludedFolderIsNotListed() {
        sampleTree();
        list("{host: 'nas', folder: '/in', exclude: ['sub/**']}");
        assertFalse(server.operations.contains("list /in/sub"), server.operations.toString());
        assertTrue(server.operations.contains("list /in/other"));
    }

    @Test
    public void hiddenEntriesAndTemporaryFilesAreSkipped() {
        server.put("/in/a.jpg", "a", DATE);
        server.put("/in/.b.jpg.run1.copybot-tmp", "b", DATE);
        server.put("/in/.ssh/id_ed25519", "key", DATE);
        Recorder recorder = list("{host: 'nas', folder: '/in'}");
        assertEquals(List.of("a.jpg"), names(recorder));
        assertFalse(server.operations.contains("list /in/.ssh"));
    }

    @Test
    public void minSizeLeavesSmallerFilesOut() {
        sampleTree();
        assertEquals(List.of("c.jpg", "d.png"), names(list("{host: 'nas', folder: '/in', minSize: 3}")));
    }

    @Test
    public void eachOpenReadsAgainOnAPooledConnection() throws IOException {
        sampleTree();
        WorkItem a = list("{host: 'nas', folder: '/in', maxConnections: 2}").items.getFirst();
        for (int i = 0; i < 3; i++) {
            try (InputStream in = a.openInputStream()) {
                assertEquals("a", new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertEquals(1, server.connects.get(), "the listing's connection, reused");
        InputStream first = a.openInputStream();
        InputStream second = a.openInputStream();
        assertEquals(2, server.open(), "two streams open at once: two connections");
        first.close();
        second.close();
    }

    @Test
    public void aFailedOpenIsAnUncheckedIOExceptionAndClosesTheConnection() {
        server.put("/in/a.jpg", "a", DATE);
        WorkItem a = list("{host: 'nas', folder: '/in'}").items.getFirst();
        removed(a); // behind the listing's back
        UncheckedIOException e = assertThrows(UncheckedIOException.class, a::openInputStream);
        assertTrue(e.getMessage().contains("mem://nas/in/a.jpg"), e.getMessage());
        assertEquals(0, server.open(), "the failed connection closed");
    }

    private void removed(WorkItem item) {
        try (RemoteFs fs = server.connect()) {
            fs.delete(item.getListedFolder() + "/" + item.getNameDisplay());
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void theDeleterDeletesTheRemoteFile() throws IOException {
        sampleTree();
        WorkItem a = list("{host: 'nas', folder: '/in'}").items.getFirst();
        a.getSourceDeleter().orElseThrow().delete();
        assertTrue(server.content("/in/a.JPG").isEmpty());
    }

    @Test
    public void aFailedListingNamesTheFolderAndClosesTheConnection() {
        sampleTree();
        server.failList = path -> path.equals("/in/sub");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> list("{host: 'nas', folder: '/in'}"));
        assertTrue(e.getMessage().contains("mem://nas/in/sub"), e.getMessage());
        assertTrue(e.getMessage().startsWith("mem.read"), e.getMessage());
        assertEquals(0, server.open());
    }

    @Test
    public void aFailedConnectionFailsTheListing() {
        server.failConnect = true;
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> list("{host: 'nas', folder: '/in'}"));
        assertTrue(e.getMessage().contains("connection refused"), e.getMessage());
    }

    @Test
    public void afterAllClosesTheConnections() throws IOException {
        sampleTree();
        MemoryActions.Read read = MemoryActions.read(server, "{host: 'nas', folder: '/in'}");
        read.beforeAll();
        Recorder recorder = new Recorder();
        read.listFiles(recorder);
        read.afterAll();
        assertEquals(0, server.open());
        // the content is read later (the execution phase): it connects again
        try (InputStream in = recorder.items.getFirst().openInputStream()) {
            assertEquals("a", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    public void theResourceIsTheServerAlsoForTheListing() {
        MemoryActions.Read read = MemoryActions.read(server, "{host: 'NAS.local', folder: '/in'}");
        assertEquals(Set.of("net:mem:nas.local"), read.requiredResources(null));
        assertTrue(read.signalsFolders());
    }

    @Test
    public void aWrongConfigurationIsRefused() {
        IllegalArgumentException noFolder = assertThrows(IllegalArgumentException.class,
                () -> MemoryActions.read(server, "{host: 'nas', folder: ' '}"));
        assertEquals("mem.read: the folder is missing", noFolder.getMessage());
        IllegalArgumentException glob = assertThrows(IllegalArgumentException.class,
                () -> MemoryActions.read(server, "{host: 'nas', folder: '/in', include: ['[a']}"));
        assertEquals("mem.read: the glob \"[a\" is not valid", glob.getMessage());
        IllegalArgumentException parent = assertThrows(IllegalArgumentException.class,
                () -> MemoryActions.read(server, "{host: 'nas', folder: '/in/../etc'}"));
        assertEquals("mem.read: the folder \"/in/../etc\" is not valid", parent.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> MemoryActions.read(server, "{host: 'nas', folder: '/in', maxConnections: 0}"));
        assertThrows(IllegalArgumentException.class,
                () -> MemoryActions.read(server, "{host: 'nas', folder: '/in', minSize: -1}"));
    }

    @Test
    public void withoutFolderSignalsTheListingSettlesGroupsAtItsEnd() {
        sampleTree();
        MemoryActions.Read read = new MemoryActions.Read(server) {
            @Override
            public boolean signalsFolders() {
                return false;
            }
        };
        read.setPlugin(MemoryActions.plugin());
        read.loadConfig(com.google.gson.JsonParser.parseString("{host: 'nas', folder: '/in'}"));
        Recorder recorder = new Recorder();
        read.listFiles(recorder);
        assertTrue(recorder.events.stream().noneMatch(e -> e.startsWith("done")), recorder.events.toString());
        assertEquals(5, recorder.items.size());
    }

    private static List<String> names(Recorder recorder) {
        return recorder.items.stream().map(WorkItem::getNameDisplay).toList();
    }
}

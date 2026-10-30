package com.copybot.engine.listing;

import com.copybot.engine.pipeline.GroupingRuleConfig;
import com.copybot.plugin.api.action.Companion;
import com.copybot.plugin.api.action.ListingSink;
import com.copybot.plugin.api.action.WorkItem;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class GrouperTest {

    private final List<String> events = new ArrayList<>();
    private final List<WorkItem> emitted = new ArrayList<>();

    private final ListingSink out = new ListingSink() {
        @Override
        public void item(WorkItem item) {
            emitted.add(item);
            events.add("item " + item.getNameDisplay());
        }

        @Override
        public void folderDone(String folder) {
            events.add("done " + folder);
        }
    };

    private static GroupingRuleConfig preset(String name) {
        return new GroupingRuleConfig(name, null, null, null, null, null, null, null);
    }

    /** A regex rule reading the JSON of a Google Takeout photo (no longer a preset of the core). */
    private static GroupingRuleConfig takeoutRule() {
        return new GroupingRuleConfig(null, "regex", List.of("(.+\\.(?:jpe?g|heic|png|gif|mp4|mov))"), null, null,
                "read", "\\1(?:\\.supplemental-metadata)?\\.json", "name");
    }

    private static GroupingRuleConfig sameBase(List<String> main, Map<String, String> companions) {
        return new GroupingRuleConfig(null, "sameBase", main, companions, null, null, null, null);
    }

    private static WorkItem file(String folder, String name) {
        return WorkItem.remote(name, folder, folder, 1, Instant.EPOCH, null, null);
    }

    /** The emitted item, its companions as {@code name:ROLE:LINK:suffix}. */
    private static String describe(WorkItem item) {
        StringBuilder sb = new StringBuilder(item.getNameDisplay());
        for (Companion c : item.getCompanions()) {
            sb.append(" [").append(c.item().getNameDisplay()).append(':').append(c.role()).append(':')
                    .append(c.link()).append(':').append(c.suffix()).append(']');
        }
        return sb.toString();
    }

    private List<String> described() {
        return emitted.stream().map(GrouperTest::describe).toList();
    }

    @Test
    void sameBasePrefersMainByPriority() {
        Grouper grouper = new Grouper(List.of(preset("raw-jpeg-xmp")), out);
        grouper.item(file("/a", "IMG_1.xmp"));
        grouper.item(file("/a", "IMG_1.JPG"));
        grouper.item(file("/a", "IMG_1.CR3"));
        grouper.folderDone("/a");
        assertEquals(List.of("IMG_1.CR3 [IMG_1.xmp:COPY:BASE:.xmp] [IMG_1.JPG:COPY:BASE:.JPG]"), described());
        assertEquals(List.of("item IMG_1.CR3", "done /a"), events);
    }

    @Test
    void companionBeforeMainGivesSameGroup() {
        Grouper grouper = new Grouper(List.of(preset("raw-jpeg-xmp")), out);
        grouper.item(file("/a", "IMG_1.CR3"));
        grouper.item(file("/a", "IMG_1.JPG"));
        grouper.item(file("/a", "IMG_1.xmp"));
        grouper.folderDone("/a");
        assertEquals(List.of("IMG_1.CR3 [IMG_1.JPG:COPY:BASE:.JPG] [IMG_1.xmp:COPY:BASE:.xmp]"), described());
    }

    @Test
    void interleavedSubfolderDoesNotSplitGroup() {
        Grouper grouper = new Grouper(List.of(preset("raw-jpeg-xmp")), out);
        grouper.item(file("/a", "IMG_1.CR3"));
        grouper.item(file("/a/sub", "z.jpg"));
        grouper.folderDone("/a/sub");
        grouper.item(file("/a", "IMG_1.xmp"));
        grouper.folderDone("/a");
        assertEquals(List.of("z.jpg", "IMG_1.CR3 [IMG_1.xmp:COPY:BASE:.xmp]"), described());
        assertEquals(List.of("item z.jpg", "done /a/sub", "item IMG_1.CR3", "done /a"), events);
    }

    @Test
    void doubleExtension() {
        Grouper grouper = new Grouper(List.of(preset("raw-jpeg-xmp")), out);
        grouper.item(file("/a", "IMG_1.CR3.xmp"));
        grouper.item(file("/a", "IMG_1.CR3"));
        grouper.folderDone("/a");
        assertEquals(List.of("IMG_1.CR3 [IMG_1.CR3.xmp:COPY:NAME:.xmp]"), described());
    }

    @Test
    void folderRuleTakesSubtree() {
        Grouper grouper = new Grouper(List.of(preset("web-page")), out);
        grouper.item(file("/d", "page.html"));
        grouper.item(file("/d/page_files", "a.png"));
        grouper.item(file("/d/page_files/img", "b.png"));
        grouper.folderDone("/d/page_files/img");
        grouper.folderDone("/d/page_files");
        assertEquals(List.of(), events);
        grouper.folderDone("/d");
        assertEquals(List.of("page.html [a.png:COPY:BASE:_files/a.png] [b.png:COPY:BASE:_files/img/b.png]"),
                described());
        assertEquals(List.of("item page.html", "done /d/page_files/img", "done /d/page_files", "done /d"), events);
    }

    @Test
    void folderRuleLeftoverWithoutMainIsOrdinary() {
        Grouper grouper = new Grouper(List.of(preset("web-page")), out);
        grouper.item(file("C:\\d\\page_files", "a.png"));
        grouper.folderDone("C:\\d\\page_files");
        grouper.item(file("C:\\d", "other.txt"));
        grouper.folderDone("C:\\d");
        assertEquals(List.of("other.txt", "a.png"), described());
        assertEquals(List.of("item other.txt", "item a.png", "done C:\\d\\page_files", "done C:\\d"), events);
    }

    @Test
    void regexTakeout() {
        Grouper grouper = new Grouper(List.of(takeoutRule()), out);
        grouper.item(file("/t", "IMG_1.jpg.supplemental-metadata.json"));
        grouper.item(file("/t", "IMG_1.jpg"));
        grouper.item(file("/t", "IMG_2.JPG"));
        grouper.item(file("/t", "IMG_2.json"));
        grouper.folderDone("/t");
        assertEquals(List.of("IMG_1.jpg [IMG_1.jpg.supplemental-metadata.json:READ:NAME:.supplemental-metadata.json]",
                "IMG_2.JPG", "IMG_2.json"), described());
    }

    @Test
    void regexTakeoutWithPlainJson() {
        Grouper grouper = new Grouper(List.of(takeoutRule()), out);
        grouper.item(file("/t", "IMG_2.JPG.json"));
        grouper.item(file("/t", "IMG_2.JPG"));
        grouper.folderDone("/t");
        assertEquals(List.of("IMG_2.JPG [IMG_2.JPG.json:READ:NAME:.json]"), described());
    }

    @Test
    void regexSony() {
        Grouper grouper = new Grouper(List.of(preset("sony-xml")), out);
        grouper.item(file("/s", "C0099.MP4"));
        grouper.item(file("/s", "C0099M01.XML"));
        grouper.folderDone("/s");
        assertEquals(List.of("C0099.MP4 [C0099M01.XML:READ:BASE:M01.XML]"), described());
    }

    @Test
    void orphanCompanionIsOrdinary() {
        Grouper grouper = new Grouper(List.of(preset("raw-jpeg-xmp")), out);
        grouper.item(file("/a", "x.xmp"));
        grouper.folderDone("/a");
        assertEquals(List.of("x.xmp"), described());
    }

    @Test
    void orphanReadCompanionIsOrdinary() {
        Grouper grouper = new Grouper(List.of(preset("sony-xml")), out);
        grouper.item(file("/a", "C1M01.XML"));
        grouper.folderDone("/a");
        assertEquals(List.of("C1M01.XML"), described());
    }

    @Test
    void ignoredCompanionIsDropped() {
        Grouper grouper = new Grouper(List.of(sameBase(List.of("cr3"), Map.of("xmp", "ignore", "jpg", "copy"))), out);
        grouper.item(file("/a", "IMG_1.CR3"));
        grouper.item(file("/a", "IMG_1.xmp"));
        grouper.item(file("/a", "IMG_1.jpg"));
        grouper.folderDone("/a");
        assertEquals(List.of("IMG_1.CR3 [IMG_1.jpg:COPY:BASE:.jpg]"), described());
    }

    @Test
    void oneLevelOnly() {
        Grouper grouper = new Grouper(List.of(
                sameBase(List.of("cr3"), Map.of("jpg", "copy")),
                new GroupingRuleConfig(null, "doubleExtension", null, Map.of("cr3", "copy", "xmp", "copy"), null,
                        null, null, null)), out);
        grouper.item(file("/a", "IMG_1.CR3"));
        grouper.item(file("/a", "IMG_1.JPG"));
        grouper.item(file("/a", "IMG_1.JPG.CR3"));
        grouper.item(file("/a", "IMG_1.JPG.xmp"));
        grouper.folderDone("/a");
        // IMG_1.JPG is attached to the CR3, so it is not a candidate main for the double extension rule any more
        assertEquals(List.of("IMG_1.CR3 [IMG_1.JPG:COPY:BASE:.JPG]", "IMG_1.JPG.CR3", "IMG_1.JPG.xmp"), described());
    }

    @Test
    void finishFlushesWithoutSignal() {
        Grouper grouper = new Grouper(List.of(preset("raw-jpeg-xmp")), out);
        grouper.item(file("/a", "IMG_1.CR3"));
        grouper.item(file("/a", "IMG_1.xmp"));
        grouper.item(file("/a/b", "z.txt"));
        grouper.finish();
        assertEquals(List.of("z.txt", "IMG_1.CR3 [IMG_1.xmp:COPY:BASE:.xmp]"), described());
        assertEquals(List.of("item z.txt", "done /a/b", "item IMG_1.CR3", "done /a"), events);
    }

    @Test
    void caseInsensitive() {
        Grouper grouper = new Grouper(List.of(preset("raw-jpeg-xmp")), out);
        grouper.item(file("/a", "img_1.cr3"));
        grouper.item(file("/a", "IMG_1.XMP"));
        grouper.folderDone("/a");
        assertEquals(List.of("img_1.cr3 [IMG_1.XMP:COPY:BASE:.XMP]"), described());
    }

    @Test
    void archiveFolders() {
        Grouper grouper = new Grouper(List.of(preset("web-page")), out);
        grouper.item(file("C:\\d\\x.zip!/DCIM", "p.htm"));
        grouper.item(file("C:\\d\\x.zip!/DCIM/p_files", "a.css"));
        grouper.folderDone("C:\\d\\x.zip!/DCIM/p_files");
        grouper.folderDone("C:\\d\\x.zip!/DCIM");
        assertEquals(List.of("p.htm [a.css:COPY:BASE:_files/a.css]"), described());
    }

    @Test
    void sourceBelowAFilesFolderWithKnownRootIsDecidedAtEachFolderDone() {
        Grouper grouper = new Grouper(List.of(preset("raw-jpeg-xmp"), preset("web-page")), out);
        grouper.root("/x/Old_files/Photos");
        grouper.item(file("/x/Old_files/Photos", "IMG_1.CR3"));
        grouper.item(file("/x/Old_files/Photos", "IMG_1.xmp"));
        grouper.item(file("/x/Old_files/Photos/sub", "IMG_2.CR3"));
        grouper.item(file("/x/Old_files/Photos/sub", "IMG_2.xmp"));
        grouper.folderDone("/x/Old_files/Photos/sub");
        assertEquals(List.of("item IMG_2.CR3", "done /x/Old_files/Photos/sub"), events);
        grouper.folderDone("/x/Old_files/Photos");
        assertEquals(List.of("item IMG_2.CR3", "done /x/Old_files/Photos/sub", "item IMG_1.CR3",
                "done /x/Old_files/Photos"), events);
    }

    @Test
    void rootThatIsAFilesFolderItselfIsNeverHeld() {
        Grouper grouper = new Grouper(List.of(preset("web-page")), out);
        grouper.root("/d/page_files");
        grouper.item(file("/d/page_files", "a.png"));
        grouper.folderDone("/d/page_files");
        assertEquals(List.of("item a.png", "done /d/page_files"), events);
    }

    @Test
    void folderRuleStillWorksBelowTheRoot() {
        Grouper grouper = new Grouper(List.of(preset("web-page")), out);
        grouper.root("/d");
        grouper.item(file("/d", "page.html"));
        grouper.item(file("/d/page_files", "a.png"));
        grouper.folderDone("/d/page_files");
        assertEquals(List.of(), events);
        grouper.folderDone("/d");
        assertEquals(List.of("page.html [a.png:COPY:BASE:_files/a.png]"), described());
    }

    @Test
    void folderRuleBelowTheRootWorksInsideAnArchiveOfAMergedFolder() {
        Grouper grouper = new Grouper(List.of(preset("web-page")), out);
        grouper.root("C:\\d");
        grouper.item(file("C:\\d!/DCIM", "p.htm"));
        grouper.item(file("C:\\d!/DCIM/p_files", "a.css"));
        grouper.folderDone("C:\\d!/DCIM/p_files");
        assertEquals(List.of(), events);
        grouper.folderDone("C:\\d!/DCIM");
        assertEquals(List.of("p.htm [a.css:COPY:BASE:_files/a.css]"), described());
    }

    @Test
    void sourceBelowAFilesFolderIsGroupedNormally() {
        Grouper grouper = new Grouper(List.of(preset("raw-jpeg-xmp"), preset("web-page")), out);
        grouper.item(file("/x/Old_files/Photos", "IMG_1.CR3"));
        grouper.item(file("/x/Old_files/Photos/sub", "IMG_2.CR3"));
        grouper.item(file("/x/Old_files/Photos/sub", "IMG_2.xmp"));
        grouper.folderDone("/x/Old_files/Photos/sub");
        grouper.item(file("/x/Old_files/Photos", "IMG_1.xmp"));
        grouper.folderDone("/x/Old_files/Photos");
        grouper.finish();
        assertEquals(List.of("IMG_2.CR3 [IMG_2.xmp:COPY:BASE:.xmp]", "IMG_1.CR3 [IMG_1.xmp:COPY:BASE:.xmp]"),
                described());
        assertEquals(List.of("item IMG_2.CR3", "done /x/Old_files/Photos/sub", "item IMG_1.CR3",
                "done /x/Old_files/Photos"), events);
    }

    @Test
    void unclaimedFilesFolderIsGroupedAsAnOrdinaryFolder() {
        Grouper grouper = new Grouper(List.of(preset("raw-jpeg-xmp"), preset("web-page")), out);
        grouper.item(file("/d/Camera_files", "IMG_1.CR3"));
        grouper.item(file("/d/Camera_files", "IMG_1.xmp"));
        grouper.folderDone("/d/Camera_files");
        grouper.folderDone("/d");
        assertEquals(List.of("IMG_1.CR3 [IMG_1.xmp:COPY:BASE:.xmp]"), described());
        assertEquals(List.of("item IMG_1.CR3", "done /d/Camera_files", "done /d"), events);
    }

    @Test
    void bigFolderIsNotQuadratic() {
        Grouper grouper = new Grouper(List.of(preset("raw-jpeg-xmp"), takeoutRule()), out);
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
            for (int i = 0; i < 7000; i++) {
                grouper.item(file("/big", "IMG_" + i + ".CR3"));
                grouper.item(file("/big", "IMG_" + i + ".xmp"));
                grouper.item(file("/big", "P_" + i + ".jpg"));
                grouper.item(file("/big", "P_" + i + ".jpg.json"));
            }
            grouper.folderDone("/big");
        });
        assertEquals(14000, emitted.size());
        assertEquals(1, emitted.get(0).getCompanions().size());
    }

    @Test
    void parentOfAFolder() {
        assertEquals("C:\\d", Grouper.parent("C:\\d\\x.zip!"));
        assertEquals("C:\\d\\x.zip!", Grouper.parent("C:\\d\\x.zip!/DCIM"));
        assertEquals("C:\\d\\x.zip!/DCIM", Grouper.parent("C:\\d\\x.zip!/DCIM/sub"));
        assertEquals("/a", Grouper.parent("/a/b"));
        assertEquals("/", Grouper.parent("/a"));
        assertEquals(null, Grouper.parent("/"));
    }

    @Test
    void archiveRootParentIsTheFolderOfTheArchive() {
        Grouper grouper = new Grouper(List.of(preset("web-page")), out);
        grouper.item(file("C:\\d", "p.html"));
        grouper.item(file("C:\\d\\p_files.zip!", "a.css"));
        grouper.folderDone("C:\\d\\p_files.zip!");
        grouper.folderDone("C:\\d");
        // "p_files.zip!" does not match "{base}_files": nothing is held
        assertEquals(List.of("a.css", "p.html"), described());
    }

    private static GroupingRuleConfig regex(String main, String companion, String link) {
        return new GroupingRuleConfig(null, "regex", List.of(main), null, null, "copy", companion, link);
    }

    @Test
    void aSameBaseCandidateWithTheMainExtensionStaysOrdinary() {
        Grouper grouper = new Grouper(List.of(preset("raw-jpeg-xmp")), out);
        grouper.item(file("/a", "IMG_1.jpg"));
        grouper.item(file("/a", "IMG_1.JPG"));
        grouper.item(file("/a", "IMG_1.xmp"));
        grouper.folderDone("/a");
        // IMG_1.JPG would be written over IMG_1.jpg on a destination that ignores case
        assertEquals(List.of("IMG_1.jpg [IMG_1.xmp:COPY:BASE:.xmp]", "IMG_1.JPG"), described());
    }

    @Test
    void aRegexCandidateWithTheMainTargetStaysOrdinary() {
        Grouper byName = new Grouper(List.of(regex("(.+)", "\\1", null)), out);
        byName.item(file("/r", "a.mp4"));
        byName.item(file("/r", "A.MP4"));
        byName.folderDone("/r");
        assertEquals(List.of("a.mp4", "A.MP4"), described());

        emitted.clear();
        Grouper byBase = new Grouper(List.of(regex("(.+)\\.mp4", "\\1\\.(?:mp4|lrv)", "base")), out);
        byBase.item(file("/r", "b.mp4"));
        byBase.item(file("/r", "b.MP4"));
        byBase.item(file("/r", "b.lrv"));
        byBase.folderDone("/r");
        assertEquals(List.of("b.mp4 [b.lrv:COPY:BASE:.lrv]", "b.MP4"), described());
    }

    @Test
    void anItemWithoutNameNorFolderGoesDownstreamUngrouped() throws Exception {
        Grouper grouper = new Grouper(List.of(preset("raw-jpeg-xmp")), out);
        WorkItem url = new WorkItem(URI.create("http://host/x.jpg").toURL(), null);
        grouper.item(url);
        grouper.item(file("/a", "IMG_1.CR3"));
        grouper.finish();
        assertEquals(2, emitted.size());
        assertSame(url, emitted.get(0));
    }
}

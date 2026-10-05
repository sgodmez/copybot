package com.copybot.engine.resume;

import com.copybot.engine.pipeline.ItemStatus;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.IOutAction;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import com.copybot.plugin.api.action.WorkStatus;
import com.copybot.plugin.api.definition.IPlugin;
import com.copybot.resources.ResourcesEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** The destination options of the resume block (spec execution-mode §2) and the streaming proposal (§3). */
public class ResumeResolverDestinationTest {

    @TempDir
    Path tempDir;

    /** An out action whose targets come from a function of the key, telling (or not) whether its directory varies. */
    static final class Out implements IOutAction {
        final Function<ItemKey, Path> target;
        final Optional<Boolean> varies;

        Out(Function<ItemKey, Path> target, Optional<Boolean> varies) {
            this.target = target;
            this.varies = varies;
        }

        @Override
        public void writeItem(WorkItem workItem) {
        }

        @Override
        public Optional<Path> resolveTarget(WorkItem workItem) {
            return Optional.of(target.apply(ItemKey.of(workItem).orElseThrow()));
        }

        @Override
        public Optional<Boolean> targetDirectoryVaries() {
            return varies;
        }

        @Override
        public void setStatusWatcher(Consumer<WorkStatus> watcher) {
        }

        @Override
        public void setPlugin(IPlugin plugin) {
        }
    }

    private Path nas() {
        return tempDir.resolve("nas");
    }

    /** nas/&lt;day&gt;/&lt;name&gt;. */
    private Out dayOut(Optional<Boolean> varies) {
        return new Out(key -> nas().resolve(key.date().toString().substring(0, 10)).resolve(key.name()), varies);
    }

    /** nas/all/&lt;name&gt;: a fixed directory. */
    private Out flatOut(Optional<Boolean> varies) {
        return new Out(key -> nas().resolve("all").resolve(key.name()), varies);
    }

    private WorkItemExecution item(String name, String instant) throws IOException {
        WorkItem wi = new WorkItem(Files.createFile(tempDir.resolve(name)));
        wi.getMetadatas().setTime(WorkItemMetadata.LAST_MODIFIED, Instant.parse(instant));
        return new WorkItemExecution(wi, List.of());
    }

    /** A (09-01), B (09-02), C (09-03), ordered. */
    private List<WorkItemExecution> card() throws IOException {
        List<WorkItemExecution> items = new ArrayList<>();
        items.add(item("C.JPG", "2026-09-03T10:00:00Z"));
        items.add(item("A.JPG", "2026-09-01T10:00:00Z"));
        items.add(item("B.JPG", "2026-09-02T10:00:00Z"));
        return ResumeResolver.order(items);
    }

    private ResumeStateStore store() {
        return new ResumeStateStore(tempDir.resolve("p.state.json"));
    }

    private ResumeResolver resolver(ResumeMode mode, DestinationCheck check, DestinationMatch match, IOutAction out) {
        return new ResumeResolver(new ResumeContext(mode, store(), check, match), out);
    }

    private void day(String day) throws IOException {
        Files.createDirectories(nas().resolve(day));
    }

    private void file(String dir, String name) throws IOException {
        Files.createDirectories(nas().resolve(dir));
        Files.writeString(nas().resolve(dir).resolve(name), name);
    }

    private static List<ItemStatus> statuses(List<WorkItemExecution> items) {
        return items.stream().map(WorkItemExecution::getStatus).toList();
    }

    private static ItemKey key(WorkItemExecution w) {
        return w.getResumeKey().orElseThrow();
    }

    // ---- everyFile, directory ----

    @Test
    public void everyFileSkipsEachFileWhoseTargetDirectoryExistsAndImportsAMissingDayInTheMiddle() throws Exception {
        List<WorkItemExecution> ordered = card();
        day("2026-09-01");
        day("2026-09-03");
        ResumeResolver resolver = resolver(ResumeMode.DESTINATION, DestinationCheck.EVERY_FILE,
                DestinationMatch.DIRECTORY, dayOut(Optional.of(true)));

        ResumeProposal p = resolver.propose(ordered);
        resolver.apply(p.point(), p.source(), ordered);

        assertEquals(ResumePoint.notAtDestination(), p.point());
        assertEquals(ResumeSource.DESTINATION, p.source());
        assertTrue(p.warnings().isEmpty());
        assertEquals(List.of(ItemStatus.SKIPPED, ItemStatus.PENDING, ItemStatus.SKIPPED), statuses(ordered));
        assertTrue(ordered.get(0).isSkippedByResumePoint());
        assertEquals(ResourcesEngine.getString("resume.skip.at-destination", nas().resolve("2026-09-01").toAbsolutePath()),
                ordered.get(0).getSkipReason());

        ordered.get(1).setDone();
        assertEquals(key(ordered.get(2)), resolver.nextCursor(ordered, p.point(), p.source()).orElseThrow(),
                "the files found at the destination count as imported");
    }

    @Test
    public void everyFileWithNothingAtTheDestinationSelectsEverything() throws Exception {
        List<WorkItemExecution> ordered = card();
        ResumeProposal p = resolver(ResumeMode.DESTINATION, DestinationCheck.EVERY_FILE, DestinationMatch.DIRECTORY,
                dayOut(Optional.of(true))).propose(ordered);

        assertEquals(ResumePoint.all(), p.point());
        assertEquals(ResumeSource.NONE, p.source());
    }

    @Test
    public void everyFileWithADeclaredFixedDirectorySelectsEverythingWithAWarning() throws Exception {
        List<WorkItemExecution> ordered = card();
        day("all");
        ResumeResolver resolver = resolver(ResumeMode.DESTINATION, DestinationCheck.EVERY_FILE,
                DestinationMatch.DIRECTORY, flatOut(Optional.of(false)));

        ResumeProposal p = resolver.propose(ordered);
        resolver.apply(p.point(), p.source(), ordered);

        assertEquals(ResumePoint.all(), p.point());
        assertEquals(List.of(ResourcesEngine.getString("resume.warn.single-directory", nas().resolve("all").toAbsolutePath())),
                p.warnings());
        assertEquals(List.of(ItemStatus.PENDING, ItemStatus.PENDING, ItemStatus.PENDING), statuses(ordered));
    }

    @Test
    public void everyFileGuessesAFixedDirectoryWhenTheOutStepCannotTell() throws Exception {
        List<WorkItemExecution> ordered = card();
        day("all");

        ResumeProposal p = resolver(ResumeMode.DESTINATION, DestinationCheck.EVERY_FILE, DestinationMatch.DIRECTORY,
                flatOut(Optional.empty())).propose(ordered);

        assertEquals(ResumePoint.all(), p.point());
        assertEquals(1, p.warnings().size());
    }

    @Test
    public void everyFileChecksEachFileWhenTheUnknownDirectoriesDiffer() throws Exception {
        List<WorkItemExecution> ordered = card();
        day("2026-09-02");
        ResumeResolver resolver = resolver(ResumeMode.DESTINATION, DestinationCheck.EVERY_FILE,
                DestinationMatch.DIRECTORY, dayOut(Optional.empty()));

        ResumeProposal p = resolver.propose(ordered);
        resolver.apply(p.point(), p.source(), ordered);

        assertEquals(List.of(ItemStatus.PENDING, ItemStatus.SKIPPED, ItemStatus.PENDING), statuses(ordered));
    }

    @Test
    public void aManualPointReplacesTheDestinationOneAndTheProposalComesBack() throws Exception {
        List<WorkItemExecution> ordered = card();
        day("2026-09-01");
        ResumeResolver resolver = resolver(ResumeMode.DESTINATION, DestinationCheck.EVERY_FILE,
                DestinationMatch.DIRECTORY, dayOut(Optional.of(true)));
        ResumeProposal p = resolver.propose(ordered);
        resolver.apply(p.point(), p.source(), ordered);

        resolver.apply(ResumePoint.all(), ResumeSource.MANUAL, ordered);
        assertEquals(List.of(ItemStatus.PENDING, ItemStatus.PENDING, ItemStatus.PENDING), statuses(ordered));

        resolver.apply(p.point(), p.source(), ordered);
        assertEquals(List.of(ItemStatus.SKIPPED, ItemStatus.PENDING, ItemStatus.PENDING), statuses(ordered));
    }

    @Test
    public void aStateThenDestinationResolverWithoutCursorChecksEveryFile() throws Exception {
        List<WorkItemExecution> ordered = card();
        day("2026-09-02");
        ResumeResolver resolver = resolver(ResumeMode.STATE_THEN_DESTINATION, DestinationCheck.EVERY_FILE,
                DestinationMatch.DIRECTORY, dayOut(Optional.of(true)));

        assertFalse(resolver.probesTheDestination(), "every file is analysed at the listing");
        ResumeProposal p = resolver.propose(ordered);
        resolver.apply(p.point(), p.source(), ordered);

        assertEquals(List.of(ItemStatus.PENDING, ItemStatus.SKIPPED, ItemStatus.PENDING), statuses(ordered));
    }

    // ---- file match ----

    @Test
    public void theDichotomyProbesTheFilesInFileMode() throws Exception {
        List<WorkItemExecution> ordered = card();
        file("2026-09-01", "A.JPG");
        day("2026-09-02"); // the directory of B exists, B itself was deleted

        ResumeProposal p = resolver(ResumeMode.DESTINATION, DestinationCheck.DICHOTOMY, DestinationMatch.FILE,
                dayOut(Optional.of(true))).propose(ordered);

        assertEquals(ResumePoint.after(key(ordered.get(0))), p.point());
        assertEquals(ResumeSource.DESTINATION, p.source());
    }

    @Test
    public void theReasonOfTheDichotomyNamesWhatItChecked() throws Exception {
        file("2026-09-01", "A.JPG");
        List<WorkItemExecution> ordered = card();
        for (DestinationMatch match : DestinationMatch.values()) {
            ResumeResolver resolver = resolver(ResumeMode.DESTINATION, DestinationCheck.DICHOTOMY, match,
                    dayOut(Optional.of(true)));
            ResumeProposal p = resolver.propose(ordered);

            resolver.apply(p.point(), p.source(), ordered);

            String key = match == DestinationMatch.FILE ? "resume.skip.destination-file" : "resume.skip.destination";
            String date = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
                    .withZone(java.time.ZoneId.systemDefault()).format(p.point().key().date());
            assertEquals(ResourcesEngine.getString(key, p.point().key().name(), date), ordered.get(0).getSkipReason(),
                    match.name());
        }
    }

    @Test
    public void aFixedDirectoryIsFineInFileMode() throws Exception {
        List<WorkItemExecution> ordered = card();
        file("all", "A.JPG");
        file("all", "B.JPG");

        ResumeProposal dichotomy = resolver(ResumeMode.DESTINATION, DestinationCheck.DICHOTOMY, DestinationMatch.FILE,
                flatOut(Optional.of(false))).propose(ordered);
        assertEquals(ResumePoint.after(key(ordered.get(1))), dichotomy.point());
        assertTrue(dichotomy.warnings().isEmpty(), "no single-directory guard in file mode");

        ResumeResolver everyFile = resolver(ResumeMode.DESTINATION, DestinationCheck.EVERY_FILE, DestinationMatch.FILE,
                flatOut(Optional.of(false)));
        ResumeProposal p = everyFile.propose(ordered);
        everyFile.apply(p.point(), p.source(), ordered);
        assertTrue(p.warnings().isEmpty());
        assertEquals(List.of(ItemStatus.SKIPPED, ItemStatus.SKIPPED, ItemStatus.PENDING), statuses(ordered));
        assertEquals(ResourcesEngine.getString("resume.skip.at-destination",
                nas().resolve("all").resolve("A.JPG").toAbsolutePath()), ordered.get(0).getSkipReason());
    }

    @Test
    public void everyFileInFileModeReimportsAFileDeletedFromAnExistingDirectory() throws Exception {
        List<WorkItemExecution> ordered = card();
        file("2026-09-01", "A.JPG");
        day("2026-09-02");
        ResumeResolver resolver = resolver(ResumeMode.DESTINATION, DestinationCheck.EVERY_FILE, DestinationMatch.FILE,
                dayOut(Optional.of(true)));

        ResumeProposal p = resolver.propose(ordered);
        resolver.apply(p.point(), p.source(), ordered);

        assertEquals(List.of(ItemStatus.SKIPPED, ItemStatus.PENDING, ItemStatus.PENDING), statuses(ordered));
    }

    // ---- streaming (spec execution-mode §3) ----

    @Test
    public void theStreamingProposalIsKnownBeforeTheListing() throws Exception {
        assertEquals(ResumePoint.all(), resolver(ResumeMode.NONE, null, null, null).streamingProposal().point());
        assertEquals(ResumePoint.all(), resolver(ResumeMode.STATE, null, null, null).streamingProposal().point());
        ItemKey cursor = new ItemKey(Instant.parse("2026-09-02T10:00:00Z"), "B.JPG");
        store().writeCursor(cursor);
        ResumeProposal state = resolver(ResumeMode.STATE, null, null, null).streamingProposal();
        assertEquals(ResumePoint.after(cursor), state.point());
        assertEquals(ResumeSource.STATE, state.source());
        ResumeProposal withCursor = resolver(ResumeMode.STATE_THEN_DESTINATION, DestinationCheck.DICHOTOMY,
                DestinationMatch.DIRECTORY, dayOut(Optional.of(true))).streamingProposal();
        assertEquals(ResumePoint.after(cursor), withCursor.point());
        assertTrue(withCursor.warnings().isEmpty());
    }

    @Test
    public void theDestinationIsCheckedFileByFileWhileStreamingTheDichotomyWithAWarning() {
        ResumeProposal dichotomy = resolver(ResumeMode.DESTINATION, DestinationCheck.DICHOTOMY,
                DestinationMatch.DIRECTORY, dayOut(Optional.of(true))).streamingProposal();
        assertEquals(ResumePoint.notAtDestination(), dichotomy.point());
        assertEquals(ResumeSource.DESTINATION, dichotomy.source());
        assertEquals(List.of(ResourcesEngine.getString("execution.streaming.dichotomy")), dichotomy.warnings());

        ResumeProposal everyFile = resolver(ResumeMode.STATE_THEN_DESTINATION, DestinationCheck.EVERY_FILE,
                DestinationMatch.FILE, dayOut(Optional.of(true))).streamingProposal();
        assertEquals(ResumePoint.notAtDestination(), everyFile.point());
        assertTrue(everyFile.warnings().isEmpty());
    }

    @Test
    public void withoutOutStepTheStreamingProposalIsTheOneOfThePreparation() {
        assertThrows(CopybotException.class,
                () -> resolver(ResumeMode.DESTINATION, DestinationCheck.EVERY_FILE, DestinationMatch.DIRECTORY, null)
                        .streamingProposal());
        ResumeProposal fallback = resolver(ResumeMode.STATE_THEN_DESTINATION, DestinationCheck.EVERY_FILE,
                DestinationMatch.DIRECTORY, null).streamingProposal();
        assertEquals(ResumePoint.all(), fallback.point());
        assertEquals(List.of(ResourcesEngine.getString("resume.warn.no-target")), fallback.warnings());
    }

    @Test
    public void whileStreamingAFileIsCheckedOnItsOwnTarget() throws Exception {
        List<WorkItemExecution> ordered = card();
        day("2026-09-02");
        ResumeResolver resolver = resolver(ResumeMode.DESTINATION, DestinationCheck.EVERY_FILE,
                DestinationMatch.DIRECTORY, dayOut(Optional.of(true)));
        resolver.streamingProposal();

        assertTrue(resolver.checkDestination(ordered.get(0)).isEmpty());
        assertEquals(Optional.of(ResourcesEngine.getString("resume.skip.at-destination",
                nas().resolve("2026-09-02").toAbsolutePath())), resolver.checkDestination(ordered.get(1)));
    }

    @Test
    public void whileStreamingAnUnknownDirectorySkipsOnlyOnceTwoDirectoriesWereSeen() throws Exception {
        List<WorkItemExecution> ordered = card();
        day("2026-09-01");
        day("2026-09-02");
        ResumeResolver resolver = resolver(ResumeMode.DESTINATION, DestinationCheck.EVERY_FILE,
                DestinationMatch.DIRECTORY, dayOut(Optional.empty()));
        resolver.streamingProposal();

        assertTrue(resolver.checkDestination(ordered.get(0)).isEmpty(), "a single directory seen: it may be fixed");
        assertTrue(resolver.checkDestination(ordered.get(1)).isPresent(), "two directories seen: they vary");
    }

    @Test
    public void whileStreamingADeclaredFixedDirectorySkipsNothingAndWarnsOnce() throws Exception {
        List<WorkItemExecution> ordered = card();
        day("all");
        ResumeResolver resolver = resolver(ResumeMode.DESTINATION, DestinationCheck.EVERY_FILE,
                DestinationMatch.DIRECTORY, flatOut(Optional.of(false)));
        resolver.streamingProposal();

        for (WorkItemExecution item : ordered) {
            assertTrue(resolver.checkDestination(item).isEmpty());
        }
        assertEquals(List.of(ResourcesEngine.getString("resume.warn.single-directory", nas().resolve("all").toAbsolutePath())),
                resolver.drainWarnings());
        assertTrue(resolver.drainWarnings().isEmpty(), "once");
    }
}

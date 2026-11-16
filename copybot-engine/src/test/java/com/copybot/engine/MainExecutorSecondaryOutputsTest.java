package com.copybot.engine;

import com.copybot.config.SecretReferences;
import com.copybot.engine.ControlFakes.FakeAction;
import com.copybot.engine.ControlFakes.NoopAnalyze;
import com.copybot.engine.pipeline.ItemStatus;
import com.copybot.engine.pipeline.PipelineStatus;
import com.copybot.engine.pipeline.PipelineStep;
import com.copybot.engine.pipeline.SecondaryOutputConfig.OnError;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.engine.pipeline.WorkItemExecution.BranchOutcome;
import com.copybot.engine.pipeline.WorkItemExecution.BranchResult;
import com.copybot.engine.resume.ItemKey;
import com.copybot.engine.resume.ResumeContext;
import com.copybot.engine.resume.ResumeMode;
import com.copybot.engine.resume.ResumeStateStore;
import com.copybot.plugin.api.action.Companion;
import com.copybot.plugin.api.action.CompanionLink;
import com.copybot.plugin.api.action.CompanionRole;
import com.copybot.plugin.api.action.IAction;
import com.copybot.plugin.api.action.IInAction;
import com.copybot.plugin.api.action.IOutAction;
import com.copybot.plugin.api.action.IProcessAction;
import com.copybot.plugin.api.action.RunOutcome;
import com.copybot.plugin.api.action.RunReport;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import com.copybot.plugin.api.action.WriteContext;
import com.copybot.plugin.api.action.WriteResult;
import com.copybot.plugin.api.action.WrittenFile;
import com.copybot.plugin.embedded.actions.FileWriteAction;
import com.copybot.resources.ResourcesEngine;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.copybot.engine.ControlFakes.awaitStatus;
import static com.copybot.engine.ControlFakes.emptyConfig;
import static com.copybot.engine.ControlFakes.registry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The secondary outputs run by the engine (spec secondary-outputs §3, §4, §6). */
class MainExecutorSecondaryOutputsTest {

    private static final String TAG = "tag";

    @TempDir
    Path dir;

    private final List<String> log = new CopyOnWriteArrayList<>();

    /** Lists files IMG_01.JPG... dated 2026-09-01... ; each listed item may be changed first (e.g. companions). */
    final class ListIn extends FakeAction implements IInAction {
        final int count;
        final Function<WorkItem, WorkItem> change;

        ListIn(int count, Function<WorkItem, WorkItem> change) {
            this.count = count;
            this.change = change;
        }

        @Override
        public void listFiles(Consumer<WorkItem> consumer) {
            for (int day = 1; day <= count; day++) {
                consumer.accept(change.apply(file(String.format("IMG_%02d.JPG", day), day)));
            }
        }
    }

    private WorkItem file(String name, int day) {
        try {
            WorkItem item = new WorkItem(Files.writeString(dir.resolve(name), name));
            item.getMetadatas().setTime(WorkItemMetadata.LAST_MODIFIED,
                    Instant.parse(String.format("2026-09-%02dT10:00:00Z", day)));
            return item;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String label(WorkItem item) {
        Object tag = item.getMetadatas().raw().get(TAG);
        return item.getNameDisplay() + (tag == null ? "" : "#" + tag);
    }

    /** Logs its lifecycle calls as "name:call:item". */
    abstract class Logged extends FakeAction {
        final String name;

        Logged(String name) {
            this.name = name;
        }

        @Override
        public void beforeAll() {
            log.add(name + ":beforeAll");
        }

        @Override
        public void afterAll() {
            log.add(name + ":afterAll");
        }

        @Override
        public void afterItem(WorkItem item) {
            log.add(name + ":afterItem:" + label(item));
        }
    }

    final class Proc extends Logged implements IProcessAction {
        final Function<WorkItem, List<WorkItem>> body;
        Optional<List<WorkItem>> dryRun = Optional.empty();

        Proc(String name, Function<WorkItem, List<WorkItem>> body) {
            super(name);
            this.body = body;
        }

        @Override
        public List<WorkItem> doProcess(WorkItem item) {
            log.add(name + ":process:" + label(item));
            return body.apply(item);
        }

        @Override
        public Optional<List<WorkItem>> dryRun(WorkItem item) {
            return dryRun;
        }
    }

    class Out extends Logged implements IOutAction {
        final List<WorkItem> got = new CopyOnWriteArrayList<>();
        volatile BiFunction<WorkItem, WriteContext, WriteResult> body = (item, context) -> WriteResult.written(null);

        Out(String name) {
            super(name);
        }

        @Override
        public WriteResult write(WorkItem item, WriteContext context) {
            log.add(name + ":write:" + label(item));
            got.add(item);
            return body.apply(item, context);
        }
    }

    private static <A extends IAction> PipelineStep<A> step(A action) {
        return new PipelineStep<>(null, action, emptyConfig());
    }

    private static StepResolver.Branch branch(String name, OnError onError, IOutAction out, Proc... steps) {
        return branch(name, onError, SecretReferences.Redactor.NONE, out, steps);
    }

    private static StepResolver.Branch branch(String name, OnError onError, SecretReferences.Redactor redactor,
                                              IOutAction out, Proc... steps) {
        List<PipelineStep<IProcessAction>> resolved = new ArrayList<>();
        for (Proc proc : steps) {
            resolved.add(new PipelineStep<>(null, proc, emptyConfig(), redactor));
        }
        return new StepResolver.Branch(name, onError, resolved, new PipelineStep<>(null, out, emptyConfig(), redactor));
    }

    private ResumeStateStore store() {
        return new ResumeStateStore(dir.resolve("p.state.json"));
    }

    /** listing -> analyse | barrier | branches, main process steps, main out; resume STATE unless mode is null. */
    private MainExecutor executor(IInAction in, ResumeMode mode, List<StepResolver.Branch> branches, IOutAction mainOut,
                                  Proc... mainSteps) {
        List<PipelineStep<?>> steps = new ArrayList<>();
        steps.add(step(new NoopAnalyze()));
        for (Proc proc : mainSteps) {
            steps.add(step(proc));
        }
        steps.add(step(mainOut));
        MainExecutor exec = new MainExecutor(List.of(step(in)), steps, 1, false, null,
                registry(Map.of("disk:*", 1000)), mode == null ? null : new ResumeContext(mode, store()));
        exec.setSecondaryBranches(branches);
        return exec;
    }

    private static WorkItemExecution item(MainExecutor exec, String name) {
        return exec.getState().getWorkItems().stream()
                .filter(e -> e.getWorkItem().getNameDisplay().equals(name)).findFirst().orElseThrow();
    }

    private List<String> logOf(String item) {
        return log.stream().filter(line -> line.endsWith(":" + item) || line.contains(":" + item + "#")).toList();
    }

    private static ItemKey day(int d) {
        return new ItemKey(Instant.parse(String.format("2026-09-%02dT10:00:00Z", d)), String.format("IMG_%02d.JPG", d));
    }

    @Test
    void theBranchesRunInOrderThenTheMainBranchLast() {
        Out b1 = new Out("b1");
        Out b2 = new Out("b2");
        Out main = new Out("main");
        MainExecutor exec = executor(new ListIn(2, item -> item), ResumeMode.STATE,
                List.of(branch("B1", OnError.FAIL, b1), branch("B2", OnError.FAIL, b2)), main);

        exec.run();

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
        for (String name : List.of("IMG_01.JPG", "IMG_02.JPG")) {
            assertEquals(List.of("b1:write:" + name, "b2:write:" + name, "main:write:" + name),
                    logOf(name).stream().filter(line -> line.contains(":write:")).toList());
            WorkItemExecution exec1 = item(exec, name);
            assertEquals(WorkItemExecution.Ending.WRITTEN, exec1.getEnding());
            assertEquals(List.of("B1", "B2"), exec1.getBranchResults().stream().map(BranchResult::name).toList());
            assertTrue(exec1.getBranchResults().stream().allMatch(r -> r.outcome() == BranchOutcome.WRITTEN));
        }
        assertTrue(log.containsAll(List.of("b1:beforeAll", "b2:beforeAll", "b1:afterAll", "b2:afterAll")),
                "beforeAll and afterAll cover the steps of the branches: " + log);
    }

    @Test
    void eachBranchGetsItsOwnCopyWithoutCompanionsMarkedSourceKeptWithoutTheItemsOwnPattern() {
        Out branchOut = new Out("b");
        branchOut.body = (item, context) -> {
            item.getMetadatas().raw().put(WorkItemMetadata.SHA256, "branch");
            return WriteResult.written(null);
        };
        Proc branchProc = new Proc("bp", item -> {
            item.getMetadatas().raw().put("seen", "branch");
            return List.of(item);
        });
        Out main = new Out("main");
        MainExecutor exec = executor(new ListIn(1, item -> {
            item.setOutPattern("/own/{name}");
            return item.withCompanions(List.of(new Companion(file("IMG_01.xmp", 1), CompanionRole.COPY,
                    CompanionLink.BASE, ".xmp")));
        }), null, List.of(branch("B", OnError.FAIL, branchOut, branchProc)), main);

        exec.run();

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
        WorkItem inBranch = branchOut.got.getFirst();
        WorkItem inMain = main.got.getFirst();
        assertNotSame(inMain, inBranch);
        assertEquals("branch", inBranch.getMetadatas().raw().get(WorkItemMetadata.SHA256));
        assertNull(inMain.getMetadatas().raw().get(WorkItemMetadata.SHA256), "the branch never writes into the main item");
        assertNull(inMain.getMetadatas().raw().get("seen"));
        assertEquals(inMain.getMetadatas().getTime(WorkItemMetadata.LAST_MODIFIED),
                inBranch.getMetadatas().getTime(WorkItemMetadata.LAST_MODIFIED), "the metadata are copied");
        assertTrue(inBranch.getCompanions().isEmpty(), "no companion in a branch");
        assertEquals(1, inMain.getCompanions().size(), "the main branch writes the group");
        assertTrue(inBranch.isSourceKept());
        assertFalse(inMain.isSourceKept());
        assertEquals(Optional.empty(), inBranch.getOutPattern(), "the item's own pattern is the main output's");
        assertEquals(Optional.of("/own/{name}"), inMain.getOutPattern());
        assertEquals(inMain.getLocalLocation(), inBranch.getLocalLocation(), "same source");
    }

    @Test
    void eachProductOfABranchProcessGoesOnAloneToTheOutputInSequence() {
        Out branchOut = new Out("b");
        Proc split = new Proc("split", item -> Stream.of("a", "b").map(tag -> {
            WorkItem product = item.copyForDryRun();
            product.getMetadatas().raw().put(TAG, tag);
            return product;
        }).toList());
        Proc mark = new Proc("mark", item -> List.of(item));
        Out main = new Out("main");
        MainExecutor exec = executor(new ListIn(1, item -> item), null,
                List.of(branch("B", OnError.FAIL, branchOut, split, mark)), main);

        exec.run();

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
        assertEquals(1, exec.getState().getWorkItems().size(), "no new item in the queue");
        assertEquals(List.of(
                "split:process:IMG_01.JPG",
                "mark:process:IMG_01.JPG#a", "b:write:IMG_01.JPG#a",
                "mark:process:IMG_01.JPG#b", "b:write:IMG_01.JPG#b",
                "b:afterItem:IMG_01.JPG#b", "mark:afterItem:IMG_01.JPG#b",
                "b:afterItem:IMG_01.JPG#a", "mark:afterItem:IMG_01.JPG#a",
                "split:afterItem:IMG_01.JPG",
                "main:write:IMG_01.JPG"),
                log.stream().filter(line -> !line.endsWith("All") && !line.startsWith("main:afterItem")).toList(),
                "each product to the output, then afterItem of each step for each item it got, the last first");
    }

    @Test
    void aBranchThatFiltersWritesNothingAndIsNoError() {
        Out branchOut = new Out("b");
        Out main = new Out("main");
        MainExecutor exec = executor(new ListIn(1, item -> item), null,
                List.of(branch("B", OnError.FAIL, branchOut, new Proc("drop", item -> List.of()))), main);

        exec.run();

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
        assertTrue(branchOut.got.isEmpty());
        assertEquals(1, main.got.size());
        WorkItemExecution only = item(exec, "IMG_01.JPG");
        assertEquals(WorkItemExecution.Ending.WRITTEN, only.getEnding());
        assertEquals(BranchOutcome.FILTERED, only.getBranchResults().getFirst().outcome());
        assertTrue(log.contains("drop:afterItem:IMG_01.JPG"));
    }

    @Test
    void aFailingBranchFailsTheItemStopsItThereAndHoldsTheCursorBack() {
        Out b1 = new Out("b1");
        Proc b1Proc = new Proc("b1p", item -> List.of(item));
        b1.body = (item, context) -> {
            if (item.getNameDisplay().equals("IMG_02.JPG")) {
                throw new IllegalStateException("disk full");
            }
            return WriteResult.written(null);
        };
        Out b2 = new Out("b2");
        Out main = new Out("main");
        MainExecutor exec = executor(new ListIn(3, item -> item), ResumeMode.STATE,
                List.of(branch("Thumbs", OnError.FAIL, b1, b1Proc), branch("B2", OnError.FAIL, b2)), main);

        exec.run();

        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus());
        WorkItemExecution failed = item(exec, "IMG_02.JPG");
        assertEquals(ItemStatus.ERROR, failed.getStatus());
        assertEquals(ResourcesEngine.getString("secondary.item.failed", "Thumbs", "disk full"),
                failed.getError().getMessage());
        assertEquals(List.of("b1p:process:IMG_02.JPG", "b1:write:IMG_02.JPG",
                "b1:afterItem:IMG_02.JPG", "b1p:afterItem:IMG_02.JPG"), logOf("IMG_02.JPG"),
                "neither the next branch nor the main one; afterItem of the branch steps all the same");
        assertEquals(BranchOutcome.ERROR, failed.getBranchResults().getFirst().outcome());
        assertEquals("disk full", failed.getBranchResults().getFirst().message());
        assertEquals(2, main.got.size());
        assertEquals(day(1), store().readCursor().orElseThrow(), "the failed item is retried next time");
    }

    @Test
    void aBranchThatWarnsGivesARunWarningAndTheItemGoesOn() {
        Out b = new Out("b");
        b.body = (item, context) -> {
            throw new IllegalStateException("no space");
        };
        Out main = new Out("main");
        MainExecutor exec = executor(new ListIn(2, item -> item), ResumeMode.STATE,
                List.of(branch("Thumbs", OnError.WARN, b)), main);

        exec.run();

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
        assertEquals(2, main.got.size());
        List<String> warnings = exec.getState().getWarnings();
        assertTrue(warnings.contains(ResourcesEngine.getString("secondary.item.warning", "Thumbs", "no space",
                "IMG_01.JPG")), String.valueOf(warnings));
        assertTrue(warnings.contains(ResourcesEngine.getString("secondary.item.warning", "Thumbs", "no space",
                "IMG_02.JPG")), String.valueOf(warnings));
        assertEquals(BranchOutcome.WARNING, item(exec, "IMG_01.JPG").getBranchResults().getFirst().outcome());
        assertEquals(day(2), store().readCursor().orElseThrow(), "the item succeeded");
    }

    @Test
    void theErrorsAndWarningsOfABranchHideItsSecrets() {
        SecretReferences.Redactor redactor = new SecretReferences.Redactor() {
            @Override
            public String text(String text) {
                return text == null ? null : text.replace("s3cret", "${secret:pwd}");
            }

            @Override
            public Throwable redact(Throwable error) {
                return error.getMessage() != null && error.getMessage().contains("s3cret")
                        ? new IllegalArgumentException(text(error.getMessage())) : error;
            }
        };
        Out failing = new Out("f");
        failing.body = (item, context) -> {
            throw new IllegalStateException("login s3cret refused");
        };
        Out warning = new Out("w");
        warning.body = failing.body;
        MainExecutor exec = executor(new ListIn(1, item -> item), null,
                List.of(branch("W", OnError.WARN, redactor, warning), branch("F", OnError.FAIL, redactor, failing)),
                new Out("main"));

        exec.run();

        WorkItemExecution only = item(exec, "IMG_01.JPG");
        assertEquals(ResourcesEngine.getString("secondary.item.failed", "F", "login ${secret:pwd} refused"),
                only.getError().getMessage());
        assertEquals(List.of(ResourcesEngine.getString("secondary.item.warning", "W", "login ${secret:pwd} refused",
                "IMG_01.JPG")), exec.getState().getWarnings());
    }

    @Test
    void aFailureWhileTellingTheResourcesOfABranchStepHidesItsSecrets() {
        SecretReferences.Redactor redactor = new SecretReferences.Redactor() {
            @Override
            public String text(String text) {
                return text == null ? null : text.replace("s3cret", "${secret:pwd}");
            }

            @Override
            public Throwable redact(Throwable error) {
                return error.getMessage() != null && error.getMessage().contains("s3cret")
                        ? new IllegalArgumentException(text(error.getMessage())) : error;
            }
        };
        Out failing = new Out("f") {
            @Override
            public java.util.Set<String> requiredResources(WorkItem item) {
                throw new IllegalStateException("resources s3cret refused");
            }
        };
        MainExecutor exec = executor(new ListIn(1, item -> item), null,
                List.of(branch("W", OnError.WARN, redactor, failing)), new Out("main"));

        exec.run();

        assertEquals(List.of(ResourcesEngine.getString("secondary.item.warning", "W",
                "resources ${secret:pwd} refused", "IMG_01.JPG")), exec.getState().getWarnings());
    }

    @Test
    void theFilesOfTheBranchesAreInTheRunReport() {
        Out b = new Out("b");
        b.body = (item, context) -> {
            Path target = dir.resolve("thumbs").resolve(item.getNameDisplay());
            context.fileWritten(target, "abc", 3);
            return WriteResult.written(target);
        };
        Out main = new Out("main");
        main.body = (item, context) -> {
            Path target = dir.resolve("photos").resolve(item.getNameDisplay());
            context.fileWritten(target, "def", 3);
            return WriteResult.written(target);
        };
        MainExecutor exec = executor(new ListIn(1, item -> item), null, List.of(branch("B", OnError.FAIL, b)), main);
        AtomicReference<RunReport> report = new AtomicReference<>();
        exec.setRunEnd(List.of(new RunEnd.Entry("end", new MainExecutorRunEndTest.Act("end", new ArrayList<>(),
                (r, context) -> report.set(r)), EnumSet.allOf(RunOutcome.class), Duration.ofSeconds(30))));

        exec.run();

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
        assertEquals(List.of(dir.resolve("thumbs").resolve("IMG_01.JPG"), dir.resolve("photos").resolve("IMG_01.JPG")),
                report.get().writtenFiles().stream().map(WrittenFile::target).toList());
        assertEquals(1, report.get().items().size(), "the item of the main branch only");
        assertEquals(List.of(dir.resolve("thumbs").resolve("IMG_01.JPG")),
                item(exec, "IMG_01.JPG").getBranchResults().getFirst().targets());
    }

    @Test
    void thePlanProjectsTheMainBranchOnly() {
        Proc dropsInDryRun = new Proc("bp", item -> List.of(item));
        dropsInDryRun.dryRun = Optional.of(List.of());
        MainExecutor exec = executor(new ListIn(1, item -> item), ResumeMode.STATE,
                List.of(branch("B", OnError.FAIL, new Out("b"), dropsInDryRun)), new Out("main"));

        exec.prepare();

        assertEquals(PipelineStatus.PREPARED, exec.getState().getStatus());
        assertInstanceOf(Projection.Projected.class, item(exec, "IMG_01.JPG").getProjection());
        assertTrue(log.stream().noneMatch(line -> line.contains(":process:") || line.contains(":write:")),
                "nothing after the barrier while preparing: " + log);
    }

    @Test
    void aCancelInABranchIsNotAnErrorOfTheItem() {
        AtomicReference<MainExecutor> holder = new AtomicReference<>();
        Out b = new Out("b");
        b.body = (item, context) -> {
            holder.get().cancel();
            throw new IllegalStateException("interrupted by the cancel");
        };
        Out main = new Out("main");
        MainExecutor exec = executor(new ListIn(1, item -> item), null, List.of(branch("B", OnError.FAIL, b)), main);
        holder.set(exec);

        exec.run();

        assertEquals(PipelineStatus.CANCELLED, exec.getState().getStatus());
        assertEquals(ItemStatus.PENDING, item(exec, "IMG_01.JPG").getStatus());
        assertTrue(main.got.isEmpty());
        assertTrue(log.contains("b:afterItem:IMG_01.JPG"));
    }

    @Test
    void streamingRunsTheBranchesTheSameWay() {
        Out b = new Out("b");
        Out main = new Out("main");
        MainExecutor exec = executor(new ListIn(2, item -> item), ResumeMode.STATE,
                List.of(branch("B", OnError.FAIL, b)), main);

        exec.stream();

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
        for (String name : List.of("IMG_01.JPG", "IMG_02.JPG")) {
            assertEquals(List.of("b:write:" + name, "b:afterItem:" + name, "main:write:" + name),
                    logOf(name).stream().filter(line -> !line.startsWith("main:afterItem")).toList());
        }
        assertEquals(day(2), store().readCursor().orElseThrow());
    }

    @Test
    void withoutBranchesTheStepsAreTheMainOnes() {
        Proc proc = new Proc("p", item -> List.of(item));
        Out main = new Out("main");
        MainExecutor exec = executor(new ListIn(1, item -> item), null, List.of(), main, proc);

        exec.run();

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
        assertEquals(List.of(), item(exec, "IMG_01.JPG").getBranchResults());
        assertEquals(main, exec.findOutAction());
        assertEquals(3, item(exec, "IMG_01.JPG").getPipelineSteps().size());
    }

    @Test
    void theMainOutputIsTheLastStepWithBranchesToo() {
        Out main = new Out("main");
        MainExecutor exec = executor(new ListIn(1, item -> item), null,
                List.of(branch("B", OnError.FAIL, new Out("b"))), main);

        assertEquals(main, exec.findOutAction());
        exec.run();
        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
    }

    private static FileWriteAction fileWrite(Path folder) {
        FileWriteAction action = new FileWriteAction();
        action.loadConfig(JsonParser.parseString("{\"outPattern\":\"" + json(folder) + "/{name}\","
                + "\"deleteSource\":true,\"verify\":\"readBack\"}"));
        return action;
    }

    @Test
    void aNewItemOfTheSameSourceHandedOnInABranchIsKeptAsWell() throws IOException {
        Path copies = dir.resolve("copies");
        Path photos = dir.resolve("photos");
        Proc fresh = new Proc("fresh", item -> {
            try {
                WorkItem same = new WorkItem(item.getLocalLocation()); // not marked by the plugin
                same.getMetadatas().display().putAll(item.getMetadatas().display());
                return List.of(same);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        MainExecutor exec = executor(new ListIn(1, item -> {
            item.getMetadatas().display().put("name", item.getNameDisplay()); // what file.read gives {name}
            return item;
        }), null,
                List.of(branch("Copies", OnError.FAIL, fileWrite(copies), fresh)), fileWrite(photos));

        exec.run();

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus(),
                String.valueOf(item(exec, "IMG_01.JPG").getError()));
        assertEquals("IMG_01.JPG", Files.readString(copies.resolve("IMG_01.JPG")));
        assertEquals("IMG_01.JPG", Files.readString(photos.resolve("IMG_01.JPG")));
        assertFalse(Files.exists(dir.resolve("IMG_01.JPG")), "the main output moved it, once the branch had copied it");
    }

    /** Logs its beforeEach / afterEach calls too. */
    final class EachOut extends Logged implements IOutAction {
        EachOut(String name) {
            super(name);
        }

        @Override
        public void beforeEach(WorkItem item) {
            log.add(name + ":beforeEach:" + label(item));
        }

        @Override
        public void afterEach(WorkItem item) {
            log.add(name + ":afterEach:" + label(item));
        }

        @Override
        public WriteResult write(WorkItem item, WriteContext context) {
            log.add(name + ":write:" + label(item));
            context.directoryCreated(dir.resolve("thumbs"));
            return WriteResult.written(null);
        }
    }

    @Test
    void aBranchStepGetsBeforeEachAndAfterEachAndItsFoldersReachTheReport() {
        AtomicReference<RunReport> report = new AtomicReference<>();
        MainExecutor exec = executor(new ListIn(1, item -> item), null,
                List.of(branch("B", OnError.FAIL, new EachOut("b"))), new Out("main"));
        exec.setRunEnd(List.of(new RunEnd.Entry("end", new MainExecutorRunEndTest.Act("end", new ArrayList<>(),
                (r, context) -> report.set(r)), EnumSet.allOf(RunOutcome.class), Duration.ofSeconds(30))));

        exec.run();

        assertEquals(List.of("b:beforeEach:IMG_01.JPG", "b:write:IMG_01.JPG", "b:afterEach:IMG_01.JPG",
                "b:afterItem:IMG_01.JPG"), logOf("IMG_01.JPG").stream().filter(line -> line.startsWith("b:")).toList());
        assertEquals(List.of(dir.resolve("thumbs")), report.get().createdDirectories());
    }

    @Test
    void anErrorOfABranchHidesItsSecretsAndTheJvmFailingIsNeverAWarning() {
        SecretReferences.Redactor redactor = new SecretReferences.Redactor() {
            @Override
            public String text(String text) {
                return text == null ? null : text.replace("s3cret", "${secret:pwd}");
            }

            @Override
            public Throwable redact(Throwable error) {
                return error.getMessage() != null && error.getMessage().contains("s3cret")
                        ? new IllegalStateException(text(error.getMessage())) : error;
            }
        };
        Out asserting = new Out("a");
        asserting.body = (item, context) -> {
            throw new AssertionError("login s3cret refused");
        };
        Out overflowing = new Out("o");
        overflowing.body = (item, context) -> {
            throw new StackOverflowError("deep");
        };
        MainExecutor exec = executor(new ListIn(1, item -> item), null,
                List.of(branch("A", OnError.WARN, redactor, asserting), branch("O", OnError.WARN, overflowing)),
                new Out("main"));

        exec.run();

        assertEquals(List.of(ResourcesEngine.getString("secondary.item.warning", "A", "login ${secret:pwd} refused",
                "IMG_01.JPG")), exec.getState().getWarnings());
        WorkItemExecution only = item(exec, "IMG_01.JPG");
        assertEquals(ItemStatus.ERROR, only.getStatus());
        assertInstanceOf(StackOverflowError.class, only.getError(), "never converted by the branch");
    }

    /** A real pipeline: the branch copies first, then the main output moves the file. */
    @Test
    void endToEndTheBranchWritesThenTheMainOutputDeletesTheSource() throws Exception {
        Path card = Files.createDirectories(dir.resolve("card"));
        Path photos = dir.resolve("nas").resolve("photos");
        Path copies = dir.resolve("nas").resolve("copies");
        for (int i = 1; i <= 3; i++) {
            Files.writeString(card.resolve("IMG_000" + i + ".JPG"), "photo " + i);
        }
        Path pipeline = Files.writeString(dir.resolve("p.json"), """
                {
                  "inSteps": [ { "action": "file.read", "actionConfig": { "path": "%s" } } ],
                  "outStep": { "action": "file.write", "actionConfig": { "outPattern": "%s/{name}",
                      "deleteSource": true } },
                  "secondaryOutputs": [ { "name": "Copies", "outStep": { "action": "file.write",
                      "actionConfig": { "outPattern": "%s/{name}" } } } ]
                }
                """.formatted(json(card), json(photos), json(copies)));

        try (CopybotEngine engine = CopybotEngine.create(Optional.of(CONFIG))) {
            Execution run = engine.run(pipeline, null);

            assertEquals(PipelineStatus.SUCCESS, awaitStatus(run), String.valueOf(run.getState().getFailure()));
        }
        for (int i = 1; i <= 3; i++) {
            assertEquals("photo " + i, Files.readString(copies.resolve("IMG_000" + i + ".JPG")));
            assertEquals("photo " + i, Files.readString(photos.resolve("IMG_000" + i + ".JPG")));
        }
        try (Stream<Path> left = Files.list(card)) {
            assertEquals(0, left.count(), "the main output moved the files once the branch had copied them");
        }
    }

    private static final Path CONFIG = Path.of("src", "test", "resources", "com", "copybot", "engine", "config.json");

    private static String json(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }
}

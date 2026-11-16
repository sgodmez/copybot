# Own Out Pattern Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** In the Plan view, after analysis, the user selects files and gives them their own output pattern (prefilled with the pipeline's), persisted in the pipeline state file and honoured by projection, conflict check, write and resume.

**Architecture:** The own pattern is a field of `WorkItem` (plugin API), read by `FileWriteAction.resolution`, the single point every target goes through. The engine propagates it to items produced by process steps, restores it at the listing from the state file (`ResumeStateStore`, keyed by `ItemKey`), and exposes `Plan.setOutPattern`. The UI adds two menu entries and a dialog whose logic lives in a JavaFX-free model.

**Tech Stack:** Java 25 (JPMS), Maven 3.8.8, JUnit 5, JavaFX 27, Gson.

**Spec:** `docs/superpowers/specs/2026-10-25-own-out-pattern-design.md`

## Global Constraints

- Build with JDK 25: `export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-25.0.3.9-hotspot"`; Maven: `mvn -q -pl <module> -am test -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false` (always `-am`: alone, `-pl copybot-ui` resolves a stale engine from ~/.m2). Run git/mvn as plain single commands.
- Java files are LF; some engine files and bundles are CRLF: keep each file's line endings (never GNU `sed -i` on them; check `git diff --stat`).
- `.properties` bundles hold non-ASCII as `\uXXXX` escapes (engine bundles: ASCII enforced by a test). Typing a backslash-u sequence in a tool input writes the decoded char: write raw text, then convert with a Perl one-liner using `sprintf("\\u%04x", ord)` on chars > 127.
- Every new UI key goes into `uiBundle.properties`, `uiBundle_fr.properties`, `uiBundle_it.properties` (`UiBundleTest` checks parity).
- Without any own pattern, preparation, execution, streaming, resume and CLI behave exactly as today (existing tests stay green unchanged).
- Comments and Javadoc in English, in the terse style of the surrounding code; spec references as `(spec own-out-pattern §N)`.
- Commit after each task, message in the repo's style, ending with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.

## Review Focus

1. A state file written by an older version (only `cursor`) or a cursor written after patterns: neither loses the other. → Task 2 tests.
2. An own pattern pointing to another disk (`E:/Albums/{name}`): footprint/locks must cover that disk. → Task 1 `touchedPathsUseTheOwnPattern`.
3. Re-preparing the plan (or a new UI session, or the CLI) finds the same targets for the overridden files. → Task 3 `aNewPreparationRestoresOwnPatterns`.
4. Destination resume with a fixed-directory pipeline pattern and some overridden files must not "select everything". → Task 3 resolver test.
5. A pattern equal to the pipeline's, or blank, removes the entry instead of storing a duplicate. → Task 4 `samePatternAsPipelineRemoves`.

---

### Task 1: Plugin API and file.write honour an own pattern

**Files:**
- Modify: `copybot-engine/src/main/java/com/copybot/plugin/api/action/WorkItem.java`
- Modify: `copybot-engine/src/main/java/com/copybot/plugin/api/action/IOutAction.java`
- Modify: `copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileWriteAction.java` (`touchedPaths` :75, `resolution` :270)
- Test: `copybot-engine/src/test/java/com/copybot/plugin/api/action/WorkItemCopyTest.java`, `copybot-engine/src/test/java/com/copybot/plugin/embedded/actions/FileWriteActionTest.java` (and `FileWriteCheckTest` for checkTarget)

**Interfaces:**
- Produces: `WorkItem.getOutPattern(): Optional<String>`, `WorkItem.setOutPattern(String pattern)` (null or blank = none); `IOutAction.defaultOutPattern(): Optional<String>` (default `Optional.empty()`); `FileWriteAction.defaultOutPattern()` returns the configured `outPattern` text.

- [ ] **Step 1: Failing tests**
  - `WorkItemCopyTest.copyKeepsTheOwnPattern`: `item.setOutPattern("x/{name}")` → `item.copyForDryRun().getOutPattern()` is `Optional.of("x/{name}")`; `setOutPattern(" ")` → empty.
  - `FileWriteActionTest.ownPatternDecidesTheTarget`: pipeline `outPattern` `<tmp>/out/{name}`, item `a.jpg` with own pattern `<tmp>/sud/{name}` → `resolveTarget` = `<tmp>/sud/a.jpg`; `write` creates `<tmp>/sud/a.jpg` and nothing in `out`; another item without own pattern → `<tmp>/out/b.jpg`.
  - `FileWriteActionTest.touchedPathsUseTheOwnPattern`: own pattern `<tmp2>/albums/{name}` → `touchedPaths(item)` = `{<tmp2>/albums}`; `touchedPaths(null)` = `{<tmp>/out}`.
  - `FileWriteActionTest.defaultOutPatternIsTheConfiguredOne`.
  - `FileWriteCheckTest.checkTargetUsesTheOwnPattern`: existing file at the own target → not `free()`.
- [ ] **Step 2: Run** `mvn -q -pl copybot-engine -am test -Dtest='WorkItemCopyTest,FileWriteActionTest,FileWriteCheckTest' -Dsurefire.failIfNoSpecifiedTests=false` → compile failure / FAIL.
- [ ] **Step 3: Implement.** `WorkItem`: private `String outPattern`, copied by `copyForDryRun`. `IOutAction.defaultOutPattern()` with Javadoc "the pattern applied to an item without its own; empty: this out step does not take an own pattern (spec own-out-pattern §1)". `FileWriteAction`: a private `OutPattern pattern(WorkItem item)` returning the item's own pattern parsed through a `ConcurrentHashMap<String, OutPattern>` cache (use the same factory `FileWriteSettings` uses to build `outPattern`), else `settings.outPattern()`; `resolution` and `touchedPaths(item)` (item non null) use it.
- [ ] **Step 4: Run** same command → PASS; then `mvn -q -pl copybot-engine -am test` → all green.
- [ ] **Step 5: Commit** "file.write: an item's own out pattern decides its target".

### Task 2: The state file keeps own patterns beside the cursor

**Files:**
- Modify: `copybot-engine/src/main/java/com/copybot/engine/resume/ResumeStateStore.java`
- Test: `copybot-engine/src/test/java/com/copybot/engine/resume/ResumeStateStoreTest.java`

**Interfaces:**
- Produces: `Map<ItemKey, String> readOutPatterns()` (empty map when no file / no member; `CopybotException resume.state.invalid` when unreadable, like `readCursor`); `void writeOutPatterns(Map<ItemKey, String> patterns)` (replaces the whole member; empty map removes it; `resume.state.write-error` on failure). JSON member `"outPatterns": [ {"date": ISO instant, "name": ..., "pattern": ...} ]`, ordered by `ItemKey`.

- [ ] **Step 1: Failing tests**
  - `outPatternsRoundTrip`: write 2 entries, read them back equal.
  - `writeCursorKeepsOutPatterns`: write patterns, then `writeCursor(k)` → `readOutPatterns()` unchanged, `readCursor()` = k.
  - `writeOutPatternsKeepsTheCursor`: reverse order.
  - `oldStateFileHasNoOutPatterns`: file `{"cursor":{...}}` → empty map, cursor read.
  - `invalidOutPatternsFails`: `"outPatterns": 3` → `CopybotException`.
- [ ] **Step 2: Run** `-Dtest=ResumeStateStoreTest` → FAIL.
- [ ] **Step 3: Implement**: both writes read the existing root object (if the file exists and parses) and replace only their member, then `AtomicFiles.write` as today.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** "Resume state: own out patterns kept beside the cursor".

### Task 3: The engine restores and propagates own patterns

**Files:**
- Modify: `copybot-engine/src/main/java/com/copybot/engine/MainExecutor.java` (`emitItem` :1163, preparation start ~:516 and `resumeAtTheListing` ~:453)
- Modify: `copybot-engine/src/main/java/com/copybot/engine/CopybotEngine.java` (where `MainExecutor` gets its `ResumeContext`, ~:313 / `resumeContext` :520)
- Modify: `copybot-engine/src/main/java/com/copybot/engine/ItemRunner.java` (`runStep`, :290)
- Modify: `copybot-engine/src/main/java/com/copybot/engine/DryRunner.java` (`project`)
- Modify: `copybot-engine/src/main/java/com/copybot/engine/resume/ResumeResolver.java` (`everyFile` :301, `directoryVaries` :431)
- Test: `DryRunnerTest`, `MainExecutorTest` (or `PlanTest`), `ResumeResolverDestinationTest`

**Interfaces:**
- Consumes: Task 1 `WorkItem.get/setOutPattern`; Task 2 `ResumeStateStore.readOutPatterns`.
- Produces: `MainExecutor.setOutPatternStore(ResumeStateStore store)` (null = own patterns in memory only), `ResumeStateStore MainExecutor.outPatternStore()`; `CopybotEngine` sets it to `ResumeStateStore.forPipeline(pipelinePath)` for every pipeline read from a path, whatever the resume mode.

- [ ] **Step 1: Failing tests**
  - `DryRunnerTest.producedItemsInheritTheOwnPattern`: a process step returning a new `WorkItem` from `dryRun` → projected item has the parent's own pattern; a produced item that already has one keeps it.
  - `MainExecutorTest` (or the closest existing executor test with a fake process step): `producedItemsInheritTheOwnPatternAtExecution`.
  - `aNewPreparationRestoresOwnPatterns` (in `PlanTest` or `CopybotEngineTest`, with a real pipeline file + `file.write` to a temp dir): state file holding an entry for file `a.jpg` (its mtime to the second) → after `prepare`, `projectionOf(a)` targets the own pattern; other files the pipeline's; the same in streaming (`run`) writes `a.jpg` at its own target.
  - `ResumeResolverDestinationTest.ownPatternMakesTheDirectoryVary`: out step with `targetDirectoryVaries()` = `Optional.of(false)`, one candidate with an own pattern → no `resume.warn.single-directory`, no "everything".
- [ ] **Step 2: Run** those tests → FAIL.
- [ ] **Step 3: Implement.**
  - `DryRunner.project` and `ItemRunner.runStep`: for each produced item without own pattern, `setOutPattern(parent's)`.
  - `MainExecutor`: at the start of `prepare()` and `stream()`, `ownPatterns = store == null ? Map.of() : store.readOutPatterns()` (an unreadable file = preparation failure, the same path as an unreadable cursor); in `emitItem`, before `deferAtTheListing`, if `ItemKey.of(workItem)` is in `ownPatterns`, set it on the work item.
  - `ResumeResolver`: the "fixed directory" shortcut applies only when no candidate's work item has an own pattern; `directoryVaries(Path)` likewise returns true when one has.
- [ ] **Step 4: Run** → PASS; `mvn -q -pl copybot-engine -am test` all green.
- [ ] **Step 5: Commit** "Engine: own out patterns restored at the listing, inherited by produced items, varying the target directory for the resume".

### Task 4: Plan.setOutPattern

**Files:**
- Modify: `copybot-engine/src/main/java/com/copybot/engine/Plan.java`
- Modify: `copybot-engine/src/main/java/com/copybot/engine/MainExecutor.java` (the work, next to `setIgnored`)
- Modify: engine messages bundles (the ones holding `resume.state.invalid`): new keys `plan.out-pattern.unsupported`, `plan.out-pattern.not-prepared` if a message is needed
- Test: `copybot-engine/src/test/java/com/copybot/engine/PlanTest.java` (new `PlanOutPatternTest` if PlanTest is crowded)

**Interfaces:**
- Consumes: Tasks 1-3.
- Produces:
  - `Optional<String> Plan.defaultOutPattern()` — the out step's `defaultOutPattern()`, empty without out step.
  - `void Plan.setOutPattern(Collection<WorkItemExecution> items, String pattern)` — blocking, any thread but JavaFX; `IllegalStateException` when the plan is neither PREPARED nor stopped after the listing, or `defaultOutPattern()` is empty; `CopybotException` with `OutPattern`'s syntax message when invalid.
  - `static boolean Plan.hasOwnPattern(WorkItemExecution item)` — `item.getWorkItem().getOutPattern().isPresent()`.

- [ ] **Step 1: Failing tests**
  - `setOutPatternChangesTheProjection`: prepared plan, `setOutPattern([a], "<tmp>/sud/{name}")` → `projectionOf(a)` targets `<tmp>/sud`, `detailOf(a)` lists `<tmp>/sud/a.jpg`; b unchanged.
  - `setOutPatternIsPersisted`: state file now holds a's key → a new `prepare` restores it (reuse Task 3 helper).
  - `samePatternAsPipelineRemoves` and `blankRemoves`: entry and own pattern gone.
  - `unanalysedItemsAreLeftAlone`: an item with `isAnalysisDeferred()` keeps no pattern.
  - `invalidSyntaxIsRefused` (e.g. `"x/{name"`), `refusedWhileNotPrepared`, `refusedWithoutOutPatternSupport` (out step whose `defaultOutPattern()` is empty).
  - `conflictCheckIsRedone`: conflictCheck mode not `none`, a file already present at the own target → `getTargetCheck()` of a reflects it after the call.
  - `executionWritesAtTheOwnTarget`: `execute(plan, null)` → file at the own target.
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement** in `MainExecutor.setOutPattern(items, pattern)`: validate (state, support, syntax with the same `OutPattern` factory as Task 1); normalise (blank or equal to default → null); for each item with a projection: set on its work item and on every item of `Projection.Projected.items()`; update the in-memory map keyed by `getResumeKey()` (items without key: memory only) and `store.writeOutPatterns(map)` when a store is set; then, if the conflict-check mode is not `none`, `itemRunner.checkTarget(exec, registry.ticket())` for those items (as `projectItems` does) and `notifier.changed()`.
- [ ] **Step 4: Run** → PASS; full engine suite green.
- [ ] **Step 5: Commit** "Plan: setOutPattern gives chosen files their own out pattern, persisted and checked".

### Task 5: UI models: menu counts, dialog model, background operation

**Files:**
- Create: `copybot-ui/src/main/java/com/copybot/ui/model/OutPatternModel.java`
- Modify: `copybot-ui/src/main/java/com/copybot/ui/model/PlanViewModel.java` (next to `ignorable` / `unignorable`)
- Modify: `copybot-ui/src/main/java/com/copybot/ui/model/PlanOperations.java`, `copybot-ui/src/main/java/com/copybot/ui/PlanEngine.java`
- Test: `copybot-ui/src/test/java/com/copybot/ui/model/OutPatternModelTest.java`, `PlanViewModelTest`, `PlanOperationsTest`

**Interfaces:**
- Consumes: Task 4 `Plan.defaultOutPattern`, `Plan.setOutPattern`, `Plan.hasOwnPattern`.
- Produces:
  - `List<WorkItemExecution> PlanViewModel.patternable(List<WorkItemExecution> selected)` — analysed (has a projection) items, empty when the plan has no `defaultOutPattern()`; `List<WorkItemExecution> PlanViewModel.withOwnPattern(List<WorkItemExecution> selected)`.
  - `OutPatternModel`: `static String initialPattern(List<WorkItem> items, String defaultPattern)` (the shared own pattern if all have the same one, else the default); `record Preview(Optional<String> syntaxError, List<PatternHelperModel.Row> rows)` and `static Preview preview(String pattern, List<WorkItem> items, String onMissingKey)` (at most 3 rows, target text resolved from `item.getMetadatas().display()`, missing keys listed); `static boolean canApply(Preview p)` = no syntax error; `static List<String> keys(List<WorkItem> items)` sorted union of display keys.
  - `PlanOperations.setOutPattern(List<WorkItemExecution> items, String pattern)` — runs `PlanEngine.setOutPattern` in the background like `analyse`/ignore, busy meanwhile, refreshes the rows at the end, errors shown like the other operations.
- [ ] **Step 1: Failing tests** for each function above (prefill shared/mixed/none; preview with a missing key and with a syntax error; at most 3 rows; `patternable` excludes deferred items and returns empty without support; operation busy then refreshed, error surfaced) using the fakes already in `PlanOperationsTest` / `PlanViewModelTest`.
- [ ] **Step 2: Run** `mvn -q -pl copybot-ui -am test -Dtest='OutPatternModelTest,PlanViewModelTest,PlanOperationsTest' -Dsurefire.failIfNoSpecifiedTests=false` → FAIL.
- [ ] **Step 3: Implement**, reusing `PatternHelperModel.syntaxError` / `Row` and the existing `OutPattern` resolution.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** "Plan view models: own out pattern, its menu counts and its background operation".

### Task 6: Plan view: menu entries, dialog, Target mark

**Files:**
- Create: `copybot-ui/src/main/java/com/copybot/ui/OutPatternDialog.java`
- Modify: `copybot-ui/src/main/java/com/copybot/ui/PlanController.java` (`rowWithMenu` :370, Target cell factory)
- Modify: `copybot-ui/src/main/resources/com/copybot/ui/i18n/uiBundle{,_fr,_it}.properties`
- Test: `copybot-ui/src/test/java/com/copybot/ui/OutPatternDialogFxTest.java` (pattern of `PatternHelperFxTest`), `UiBundleTest` (parity, existing)

**Interfaces:**
- Consumes: Task 5.
- Keys (en / fr; it translated): `plan.menu.out-pattern` "Output pattern… ({0})" / "Pattern de sortie… ({0})"; `plan.menu.pipeline-pattern` "Back to the pipeline pattern ({0})" / "Revenir au pattern du pipeline ({0})"; `out-pattern.title` "Output pattern" / "Pattern de sortie"; `out-pattern.pipeline` "Pipeline pattern: {0}" / "Pattern du pipeline : {0}"; `out-pattern.files` "{0} file(s)" / "{0} fichier(s)"; `plan.target.own-pattern` "Pattern of this file: {0}" / "Pattern propre à ce fichier : {0}".

- [ ] **Step 1: Failing FX test** `OutPatternDialogFxTest`: dialog opened on 2 items with a default pattern → field prefilled; typing `x/{name` disables OK and shows the syntax error; typing a valid pattern shows the resolved target of each item; OK returns the pattern, Cancel returns empty.
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement**
  - `OutPatternDialog.show(Window owner, List<WorkItem> items, String defaultPattern, String onMissingKey): Optional<String>` — a `Dialog` with the field, the {+} key picker (reuse `PatternHelper`'s picker if it can be extracted without changing its behaviour, otherwise a `MenuButton` of `OutPatternModel.keys` inserting with `PatternHelperModel.insert`), the pipeline pattern reminder, the live preview rows, OK bound to `canApply`.
  - `PlanController.rowWithMenu`: after `exclude`, a separator then `outPattern` and `pipelinePattern` entries, counted with `setCountedItem` from `patternable` / `withOwnPattern` (0 when busy); actions call the dialog then `ops.setOutPattern(items, pattern)`, or `ops.setOutPattern(items, null)`.
  - Target cell: when `Plan.hasOwnPattern(item)`, a small drawn mark (an `SVGPath`, like `MAGNIFIER`) as graphic and the `plan.target.own-pattern` tooltip.
  - Bundles: the keys above in the three files, `\u` escapes.
- [ ] **Step 4: Run** `mvn -q -pl copybot-ui -am test` → all green (FX tests included).
- [ ] **Step 5: Commit** "Plan view: a chosen output pattern for the selected files (prefilled with the pipeline's), marked in the Target column".

### Final check

- [ ] `mvn -q verify` at the root with JDK 25 → green.

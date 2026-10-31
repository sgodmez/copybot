# Deferred Analysis Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When a manual resume point selects files whose analysis was deferred at the listing, analyse them (analyse steps + dry run) before the execution, so the plan shows their targets and details.

**Architecture:** `MainExecutor.analyseDeferred()` is a phase like `prepare()` limited to the selected deferred items, stopping at the barrier, ending PREPARED; `CopybotEngine.analyse(Plan)` runs it as the active operation; `Plan.toAnalyse()` / `Plan.cancelAnalysis()`. The UI launches it after `preview` when `toAnalyse()` is not empty (phase `ANALYSING`); the CLI runs it in dry run after the override.

**Spec:** `docs/superpowers/specs/2026-10-05-deferred-analysis-design.md`

## Global Constraints

- JDK 25: `export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-25.0.3.9-hotspot"`, `mvn -o install` from the root.
- Java files LF; bundles with `\u` escapes; en/fr key parity.
- When the analysis does not run, behaviour strictly identical to before (non-regression tests below).

## Tasks

### Task 1: engine phase

- [ ] Failing tests in `MainExecutorResumeTest`: `aManualPointSelectingDeferredItemsAnalysesThemBeforeTheExecution` (analysed, PENDING, projected, no longer deferred, PREPARED, watcher terminal notification PREPARED), `anItemFailingItsDeferredAnalysisEndsInError`, `aCancelledAnalysisLeavesThePlanPreparedAndTheRestDeferred` (blocking analyse step, then `execute` analyses the rest from step 0), `cancelAnalysisOutsideAnAnalysisDoesNothing`, non-regression `withoutDeferredItemTheAnalysisIsNoPhase` and `aPointSelectingNoDeferredItemAnalysesNothing` (status and watcher untouched), existing `aManualPointSelectingItemsSkippedAtTheListingAnalysesThemAtTheExecution` kept green.
- [ ] Implement `analyseDeferred`, `cancelAnalysis`, `markPrepared` clearing the deferred flag, `isPreparing` during the analysis.

### Task 2: engine API

- [ ] Failing tests: `PlanTest.aDeferredItemSelectedAgainGetsItsTargetOnceAnalysed`, `CopybotEngineTest` (analyse holds the engine, refused while busy).
- [ ] `Plan.toAnalyse()`, `Plan.cancelAnalysis()`, `CopybotEngine.analyse(Plan)`.

### Task 3: CLI

- [ ] `ResumeEndToEndTest.dryRunWithAllAfterACursorPrintsTheReselectedFiles`; `Copybot.doRun` calls `engine.analyse(plan)` after `preview` in dry run.

### Task 4: UI

- [ ] Failing tests in `PlanViewModelTest`: phase `ANALYSING` (buttons, progress over the analysed files, resume line kept, stop allowed, change of point refused), end on PREPARED.
- [ ] `PlanViewModel` phase and texts (`plan.analysing-again` en/fr), `PlanController.applyResumePoint` → `startAnalysis`, Stop → `plan.cancelAnalysis()`.

### Task 5: full build `mvn -o install`, one squashed commit.

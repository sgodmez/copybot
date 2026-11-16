# Updates window Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One "Updates" window for the application and its official plugins (choose what to update, see what's new, one restart), reached from the banner, the Edit menu, the preferences and the Plugins window; plus *File › Open a pipeline…*.

**Architecture:** Data first (release notes, plugin `changes`, preparation of exactly the chosen plugins), then a JavaFX-free model of the window (rows, linked selection, summary, what to apply) unit-tested, then the window (FXML + controller) on that model, then the wiring of the banner, menu, preferences and Plugins window, then the File menu item.

**Tech Stack:** Java 25 (JPMS), JavaFX, Maven (JDK `C:\Program Files\Eclipse Adoptium\jdk-25*`), JUnit 5, PowerShell for `.github/scripts/plugin-catalogue.ps1`.

**Spec:** `docs/superpowers/specs/2026-10-26-updates-window-design.md`

## Global Constraints

- Build/test: `mvn -o test -pl copybot-ui -am -Dtest=<Classes> -Dsurefire.failIfNoSpecifiedTests=false` (always `-am`); full check `mvn -o verify -pl copybot-ui,copybot-plugin/copybot-plugin-gps-zone -am`.
- Java files are LF; match the surrounding style (Javadoc on every non-trivial method, comments say why).
- UI texts: every new key in `copybot-ui/src/main/resources/com/copybot/ui/i18n/uiBundle.properties`, `_fr`, `_it` (parity checked by `UiBundleTest`); non-ASCII written as `\uXXXX` escapes (a tool input decodes them: write the raw text then convert with a small Perl script, `sprintf("\\u%04x", ord)`).
- Everything on the JavaFX thread in controllers; network and disk off it via `com.copybot.ui.util.Background.run`.
- Never weaken the update script's guards (`apply-update.ps1` is not to change in this plan).
- Commit after each task with a message describing the behaviour; end messages with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.

## Review Focus

1. A plugin built for a newer plugin API must never be applied without the application update (checked-state link and the apply path).
2. Unchecking Copybot while a linked plugin is checked: the plugin is unchecked and disabled; checking it again checks Copybot.
3. Plugins installed outside the app's `plugins` folder or absent from the catalogue never appear as updatable.
4. Plugin-only updates still work when no release is offered (plugin mode of the script).
5. The window refreshes after *Check*; a failed check shows its message and keeps the rows of the last good check.

---

### Task 1: Data — release notes, plugin "changes", exactly the chosen plugins

**Files:**
- Modify: `copybot-ui/src/main/java/com/copybot/ui/update/Release.java` (+ `ReleaseTest`)
- Modify: `copybot-ui/src/main/java/com/copybot/ui/plugins/PluginCatalog.java` (+ `PluginCatalogTest`)
- Modify: `.github/scripts/plugin-catalogue.ps1` (copy `changes` like `description`)
- Modify: `copybot-plugin/copybot-plugin-gps-zone/catalog.json`, `copybot-plugin/copybot-plugin-metadata-extractor/catalog.json` (add a `changes` object en/fr/it describing their current version)
- Modify: `copybot-ui/src/main/java/com/copybot/ui/plugins/PluginService.java` (+ `PluginServiceTest`), `copybot-ui/src/main/java/com/copybot/ui/UpdateUi.java` (adapt the one caller)

**Interfaces — Produces:**
- `record Release(String version, URI notesUrl, String notes, List<Asset> assets)`; `notes` = `body` of the GitHub JSON, `""` when absent/null. Keep a constructor `Release(String version, URI notesUrl, List<Asset> assets)` delegating with `""` if it eases existing tests.
- `PluginCatalog.Entry` gains `Map<String, String> changes` (after `description`; empty map when absent), copied defensively like `description`.
- `PluginService.prepareUpdate(Release release, Set<String> modules, LongConsumer appProgress, Progress pluginProgress, BooleanSupplier cancelled)`: like today but only plugins whose module is in `modules` (still filtered by `updatedWithTheApplication(rows)`); empty set → the usual update without plugins.
- `public List<PluginRow> rowsForUpdates(PluginCatalog catalog)` on `PluginService`: rows of `rows(catalog)` that are installed with all their folders in the app's `plugins` folder (the same folder rule as `updatedWithTheApplication`, but up-to-date ones included) — the plugin lines of the window.

- [ ] Step 1: failing tests — `ReleaseTest`: body read, missing/null body → `""`. `PluginCatalogTest`: `changes` read per language, absent → empty map. `PluginServiceTest`: `prepareUpdate` with `Set.of(A)` when A and D both have updates → manifest installs only `a`; with `Set.of()` → no `-PluginManifest`; `rowsForUpdates` returns installed rows of the plugins folder (up to date included), not elsewhere, not without folder, not uninstalled.
- [ ] Step 2: run, expect FAIL (compile).
- [ ] Step 3: implement; in `plugin-catalogue.ps1` add `changes = $source.changes` when present (same place as `description`); add `changes` to both `catalog.json` (UTF-8 JSON, real accents allowed there).
- [ ] Step 4: run the three test classes + `UpdateServiceTest`, expect PASS.
- [ ] Step 5: commit.

### Task 2: Model of the window (no JavaFX)

**Files:**
- Create: `copybot-ui/src/main/java/com/copybot/ui/update/UpdatesModel.java` — package `com.copybot.ui.update` would create a cycle with `plugins`; put it in **`com.copybot.ui.plugins`** as `UpdatesModel.java`.
- Test: `copybot-ui/src/test/java/com/copybot/ui/plugins/UpdatesModelTest.java`

**Interfaces — Consumes:** `Release` (Task 1), `PluginRow`, `PluginCatalog.Entry` (with `changes`), `VersionUtil.isCompatible(String engine, String builtFor, boolean)`.

**Interfaces — Produces:**
```java
public final class UpdatesModel {
    public enum PluginLine { UP_TO_DATE, UPDATABLE, NEEDS_APP, NEEDS_NEWER_APP }
    public record Plugin(PluginRow row, PluginLine line) { }
    /** release: the newer release offered, empty when none; apiVersion: this Copybot's plugin API (null = unknown, accepts all). */
    public UpdatesModel(Optional<Release> release, List<PluginRow> installedRows, String apiVersion);
    public Optional<Release> release();
    public List<Plugin> plugins();          // catalogue order
    public boolean appChecked();
    public boolean checked(PluginRow row);
    public boolean enabled(PluginRow row);  // false for UP_TO_DATE, NEEDS_NEWER_APP, and NEEDS_APP while the app is unchecked
    public void setAppChecked(boolean checked);   // unchecking unchecks every NEEDS_APP plugin
    public void setChecked(PluginRow row, boolean checked); // checking a NEEDS_APP plugin checks the app
    public Set<String> checkedModules();
    public boolean nothingToApply();
    public long knownSize();                // app asset size (given via setAppSize) + archive sizes known; 0 when none
    public void setAppSize(long bytes);
}
```
Rules (from the spec): UPDATABLE = newer version compatible with `apiVersion`; NEEDS_APP = newer but not compatible and a release is offered; NEEDS_NEWER_APP = newer, not compatible, no release. Everything updatable is checked at construction. Plugin archive sizes are not known from the catalogue: `knownSize()` counts the app only (document it).

- [ ] Step 1: failing tests for each rule above (construction states, initial checks, link both ways, disabled states, checkedModules, nothingToApply, knownSize).
- [ ] Step 2: run, expect FAIL.
- [ ] Step 3: implement.
- [ ] Step 4: run, expect PASS.
- [ ] Step 5: commit.

### Task 3: The Updates window

**Files:**
- Create: `copybot-ui/src/main/resources/com/copybot/ui/views/updates-view.fxml`, `copybot-ui/src/main/java/com/copybot/ui/UpdatesController.java`
- Modify: `copybot-ui/src/main/java/com/copybot/ui/UpdateUi.java` (apply entry point), `MainController.java` (`void openUpdates()` single instance, like `openPlugins`), i18n bundles, `copybot-ui/src/test/java/com/copybot/ui/ViewsLoadFxTest.java` (the view loads)

**Interfaces — Consumes:** `UpdatesModel` (Task 2), `PluginService.checkNow()`, `catalog()`/`fetchCatalog()`, `rowsForUpdates`, `prepareUpdate(release, modules, …)` (Task 1), `UpdateUi.offer/offered/lastResult`, `UpdateService.installBlocked/unavailableReason/assetName`, `PluginService.applyBlocked`, `UpdateUi.applyPlugins(List<PluginRow>, List<PluginRow>, Window)`.

**Interfaces — Produces:**
- `UpdateUi.install(Release release, Set<String> modules, Window owner)` (the old `install(Release)` removed or delegating with all updatable modules — the banner no longer calls it after Task 4).
- `MainController.openUpdates()`; `UpdateUi.lastCheck()` → `Optional<Instant>` of the last check (from `UiPreferences.updateLastCheck(version)` through the service) for the header.

Behaviour: the spec's "La fenêtre" section. On open: show the last known state (offered release + cached catalogue when present) then read the catalogue in the background if not cached; *Check* = `checkNow` then `UpdateUi.offer(check)`, then catalogue refetched, rows rebuilt, all checked again. "▸ Quoi de neuf": a toggle showing a wrapped `Label`/`TextArea` (read-only) with the notes or `changes` in the UI language (fallback en); app notes add a hyperlink to `notesUrl`. Apply: app checked → `UpdateUi.install(release, model.checkedModules(), window)`; plugins only → `UpdateUi.applyPlugins(checked rows, List.of(), window)`. Button disabled with the reason as in the banner/Plugins window.

- [ ] Step 1: add `thePluginsUpdatesViewLoads` to `ViewsLoadFxTest` (expect FAIL: missing view).
- [ ] Step 2: implement view, controller, `openUpdates`, `UpdateUi.install(release, modules, owner)`, texts (en/fr/it).
- [ ] Step 3: run `ViewsLoadFxTest,UiBundleTest,UpdatesModelTest,PluginServiceTest`, expect PASS.
- [ ] Step 4: commit.

### Task 4: Wiring — banner, menu, preferences, Plugins window

**Files:** `MainController.java` + `main-view.fxml` (banner: text "Mises à jour disponibles : …", [Voir] → `openUpdates`, × kept; notes link and install button removed; Edit menu item *Mises à jour…*), `PreferencesController.java` + `preferences-view.fxml` (section reduced to the auto-check box + *Mises à jour…* button: save as OK, close, `openUpdates`), `UpdateUi.java` (`checkText` → new banner text keys; delete the old `install(Release)` and `MainController.installOffered`; remove dead code), i18n (remove keys that become unused only if no code uses them; `UiBundleTest` parity). The Plugins window is NOT touched here (Task 6 redoes it).

- [ ] Step 1: adapt/extend tests touching these texts (`UiBundleTest` plural forms if a new count text is added).
- [ ] Step 2: implement.
- [ ] Step 3: full UI tests `mvn -o test -pl copybot-ui -am`, expect PASS.
- [ ] Step 4: commit.

### Task 5: File › Open a pipeline…

**Files:** `main-view.fxml` (File menu, before Exit), `MainController.java`, `HomeController.java` (move the open logic — chooser, `.state.json` refusal, opening the plan — to one shared method, e.g. `static void MainController.openPipelineChooser()` or a small helper, used by both), i18n (`menu.file.open`, reuse `home.open` text if identical).

- [ ] Step 1: implement (pure UI wiring; keep the home screen behaviour identical).
- [ ] Step 2: run `ViewsLoadFxTest,UiBundleTest`, expect PASS.
- [ ] Step 3: commit.

### Task 6: The Plugins window redone as cards

**Spec:** section "Refonte de l'écran Plugins".

**Files:** `copybot-ui/src/main/resources/com/copybot/ui/views/plugins-view.fxml` (rewritten), `PluginsController.java` (rewritten around cards; keep its public/package API used by `MainController`: `choices()`, `useChoices(...)`, `showOfficial`/init signatures as called by `MainController.showPlugins`), a JavaFX-free helper for what a card shows (e.g. `com.copybot.ui.plugins.PluginCard` built from a `PluginRow` and/or a `PluginEntry` of the report: title, state, main action, description, action lines, depends-on, missing, technical details; plus the filter rule) with a unit test, i18n en/fr/it.

**Consumes:** `PluginReport`/`PluginEntry` (engine: `actions` = `ActionEntry(StepType type, String code, String name)`, `pluginDependencies`, `missingRequires`, `modules`, `status`, `message`, `source` EMBEDDED/PLUGIN_PATH/DEV), `PluginRow`/`PluginState`/`PluginChoices` (official rows and chosen actions), `UpdateUi.applyPlugins`, `MainController.openUpdates()` (Task 3/4) for the "Mises à jour…" action of an installed plugin with an update.

Rules: an installed plugin with an update gets "Mises à jour…" (opens the Updates window) instead of an Update action, except the pipeline repair flow (`offersInstall` preselected modules) which keeps installing it here; "Tout mettre à jour" removed; everything else the current window does is kept (warnings, unreadable catalogue message, preselection, choices kept across refreshes, apply bar and its blocked reason, open folder).

- [ ] Step 1: failing unit tests for the card helper (action lines with type and pipeline code, depends-on/missing only when non-empty, filter Installed/Available, unofficial plugin has no install action, update → "Mises à jour…" except preselected repair).
- [ ] Step 2: implement helper, then view + controller; keep `ViewsLoadFxTest` loading `plugins-view.fxml` (add the case).
- [ ] Step 3: full UI tests, expect PASS.
- [ ] Step 4: commit.

# Reprise d'import (curseur) — Plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ne copier que ce qui n'a pas encore été importé, grâce à un curseur (date de prise de vue + nom) persistant par pipeline, un mode de détection « depuis la destination » et une reprise manuelle, avec une séparation préparation / exécution du pipeline.

**Architecture:** Nouveau package `com.copybot.engine.resume` (clé d'ordre, point de reprise, fichier d'état, sonde de destination, résolveur). `MainExecutor` gagne une barrière entre `analyseSteps` et le reste : `prepare()` (listing + analyses + résolution de la reprise) puis `execute(override)` (étapes restantes pour les items sélectionnés, puis avancement du curseur). Sans reprise configurée, `run()` garde exactement le comportement actuel.

**Tech Stack:** Java 25 (threads virtuels, JPMS), Maven multi-module, JUnit Jupiter `${junit.version}` (6.1.2), Gson, picocli 4.7.7, metadata-extractor 2.20.0. Aucune nouvelle dépendance de production.

**Spec:** `docs/superpowers/specs/2026-09-30-resume-cursor-design.md` (à lire avant chaque tâche).

## Global Constraints

- Java 25 ; code, noms et javadoc en **anglais** (convention du code existant) ; ce plan et la spec en français.
- API plugin rétro-compatible : n'ajouter aux interfaces `IAction`/`IOutAction` que des méthodes `default` ; ne pas modifier `copybot-plugin-demo*`.
- Bloc `resume` absent du pipeline ⇒ mode `none` ⇒ comportement et sorties **identiques** à aujourd'hui (tous les tests existants restent verts sans modification de leurs assertions).
- Bloc `resume` présent sans `mode` ⇒ `stateThenDestination`.
- Fichier d'état : `<nom du pipeline sans extension>.state.json` dans le répertoire du pipeline ; format `{ "cursor": { "date": "<Instant ISO-8601 UTC>", "name": "<nom>" } }`.
- Clé d'ordre : date tronquée à la seconde, puis nom. Date = `raw["captureDate"]`, sinon `raw["lastModified"]` (des `Instant`).
- Messages utilisateur via `ResourcesEngine.getString` / `CopybotException.ofResource`, clés ajoutées à `engineBundle.properties` **et** `engineBundle_fr.properties`. Format `MessageFormat` : apostrophe doublée (`n''existe`). `engineBundle_fr.properties` est encodé ISO-8859-1 : écrire les caractères non ASCII en échappement `\uXXXX` (ex. `déjà`).
- Les tests ne comparent jamais le texte exact d'un message traduit (la locale de la JVM de test peut être FR ou EN) : ils vérifient sa présence ou qu'il contient un nom de fichier.
- Tests : `mvn -o -q -pl copybot-engine test` depuis la racine du repo (plugin EXIF : `mvn -o -q -pl copybot-engine,copybot-plugin/copybot-plugin-metadata-extractor install`). Vérification finale : `mvn -o clean install` à la racine.
- Branche `feature/resume-cursor`. Un commit par tâche, `git add` ciblé sur les fichiers de la tâche uniquement (des fichiers non suivis `copybot-ui/*.ico`/`*.png` existent : ne jamais les ajouter). Messages de commit terminés par la ligne `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.

## Carte des fichiers

| Fichier (sous `copybot-engine/src/main/java/com/copybot/`) | Rôle |
|---|---|
| `plugin/api/action/WorkItemMetadata.java` | fix `setTime`, + `getTime`, constantes `CAPTURE_DATE`/`LAST_MODIFIED` |
| `plugin/embedded/actions/FileReadAction.java` | suppression du `sleep` de debug, utilise `LAST_MODIFIED` |
| `engine/resume/ItemKey.java` | **Créer** — clé d'ordre |
| `engine/resume/ResumePoint.java` | **Créer** — `all` / `after` / `from` |
| `engine/resume/ResumeSource.java` | **Créer** — provenance |
| `engine/resume/ResumeProposal.java` | **Créer** — point + source + avertissements |
| `engine/resume/ResumeMode.java`, `ResumeConfig.java` | **Créer** — config pipeline |
| `engine/resume/ResumeStateStore.java` | **Créer** — fichier d'état |
| `engine/resume/DestinationProbe.java` | **Créer** — dichotomie répertoire cible |
| `engine/resume/ResumeContext.java` | **Créer** — mode + store passés à l'exécuteur |
| `engine/resume/ResumeResolver.java` | **Créer** — proposition, application, curseur suivant |
| `engine/pipeline/PipelineConfig.java` | + composant `resume` |
| `engine/pipeline/ItemStatus.java`, `PipelineStatus.java` | + `SKIPPED`, + `PREPARED` |
| `engine/pipeline/WorkItemExecution.java` | + `setSkipped`, `getSkipReason`, `setReady` |
| `engine/pipeline/PipelineState.java` | + proposition de reprise, + `failure` |
| `plugin/api/action/IOutAction.java` | + `resolveTarget` par défaut |
| `plugin/embedded/actions/FileWriteAction.java` | implémente `resolveTarget` |
| `engine/MainExecutor.java` | barrière, `prepare`/`execute` |
| `engine/Plan.java` | **Créer** — résultat de préparation |
| `engine/CopybotEngine.java` | `prepare`/`execute`, init idempotente |
| `Copybot.java` | options CLI, dry-run |
| `module-info.java` | export `com.copybot.engine.resume` |
| `copybot-plugin/copybot-plugin-metadata-extractor/.../ExtractMetadata.java` | pose `captureDate` |

---

### Task 1: Métadonnées de date fiables

**Files:**
- Modify: `copybot-engine/src/main/java/com/copybot/plugin/api/action/WorkItemMetadata.java`
- Modify: `copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileReadAction.java`
- Test: `copybot-engine/src/test/java/com/copybot/plugin/api/action/WorkItemMetadataTest.java` (créer)

**Interfaces:**
- Produces: `WorkItemMetadata.CAPTURE_DATE = "captureDate"`, `WorkItemMetadata.LAST_MODIFIED = "lastModified"`, `void setTime(String key, Instant time)` (stocke `raw.put(key, time)`), `Optional<Instant> getTime(String key)`.

- [ ] **Step 1: Write the failing test**

```java
package com.copybot.plugin.api.action;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class WorkItemMetadataTest {

    @Test
    public void setTimeStoresTheRawInstantUnderItsOwnKey() {
        WorkItemMetadata m = new WorkItemMetadata();
        Instant capture = Instant.parse("2026-09-28T15:42:10Z");
        Instant modified = Instant.parse("2026-09-29T08:00:00Z");

        m.setTime(WorkItemMetadata.CAPTURE_DATE, capture);
        m.setTime(WorkItemMetadata.LAST_MODIFIED, modified);

        assertEquals(Optional.of(capture), m.getTime("captureDate"));
        assertEquals(Optional.of(modified), m.getTime("lastModified"));
        assertFalse(m.raw().containsKey("key"), "the literal key \"key\" must not be used anymore");
        assertEquals("2026", m.display().get("captureDate.Y"));
    }

    @Test
    public void getTimeIsEmptyForUnknownOrNonInstantValues() {
        WorkItemMetadata m = new WorkItemMetadata();
        m.raw().put("weird", "not an instant");

        assertTrue(m.getTime("missing").isEmpty());
        assertTrue(m.getTime("weird").isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -o -q -pl copybot-engine test -Dtest=WorkItemMetadataTest`
Expected: FAIL (compilation : `CAPTURE_DATE`, `getTime` inexistants).

- [ ] **Step 3: Implement**

Dans `WorkItemMetadata` : ajouter en tête du record

```java
    public static final String CAPTURE_DATE = "captureDate";
    public static final String LAST_MODIFIED = "lastModified";
```

remplacer dans `setTime` la ligne `raw.put("key", time);` par `raw.put(key, time);`, et ajouter :

```java
    public Optional<Instant> getTime(String key) {
        return raw.get(key) instanceof Instant instant ? Optional.of(instant) : Optional.empty();
    }
```

(import `java.util.Optional`).

Dans `FileReadAction.listFiles` : supprimer le bloc `try { Thread.currentThread().sleep(5); } catch ...` ; dans `extractMetadata`, remplacer `"lastModified"` par `WorkItemMetadata.LAST_MODIFIED`.

- [ ] **Step 4: Run tests**

Run: `mvn -o -q -pl copybot-engine test`
Expected: PASS (tous les tests du module).

- [ ] **Step 5: Commit**

```bash
git add copybot-engine/src/main/java/com/copybot/plugin/api/action/WorkItemMetadata.java copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileReadAction.java copybot-engine/src/test/java/com/copybot/plugin/api/action/WorkItemMetadataTest.java
git commit -m "Store metadata times under their own key and drop the listing debug sleep"
```

---

### Task 2: Types de base de la reprise et configuration pipeline

**Files:**
- Create: `copybot-engine/src/main/java/com/copybot/engine/resume/ItemKey.java`, `ResumePoint.java`, `ResumeSource.java`, `ResumeProposal.java`, `ResumeMode.java`, `ResumeConfig.java`
- Modify: `copybot-engine/src/main/java/com/copybot/engine/pipeline/PipelineConfig.java`
- Modify: `copybot-engine/src/main/java/module-info.java`
- Test: `copybot-engine/src/test/java/com/copybot/engine/resume/ItemKeyTest.java`, `ResumePointTest.java`, `ResumeConfigTest.java` (créer)

**Interfaces:**
- Consumes: `WorkItemMetadata.getTime`, `CAPTURE_DATE`, `LAST_MODIFIED` (Task 1).
- Produces:
  - `record ItemKey(Instant date, String name) implements Comparable<ItemKey>` ; `static Optional<ItemKey> of(WorkItem item)`.
  - `record ResumePoint(Kind kind, ItemKey key)` ; `enum Kind { ALL, AFTER, FROM }` ; `static all()`, `static after(ItemKey)`, `static from(ItemKey)` ; `boolean selects(ItemKey)`.
  - `enum ResumeSource { NONE, STATE, DESTINATION, MANUAL }`.
  - `record ResumeProposal(ResumePoint point, ResumeSource source, List<String> warnings)`.
  - `enum ResumeMode { NONE, STATE, DESTINATION, STATE_THEN_DESTINATION }` (JSON : `none`, `state`, `destination`, `stateThenDestination`).
  - `record ResumeConfig(ResumeMode mode)` ; `ResumeMode effectiveMode()`.
  - `PipelineConfig` : nouveau dernier composant `ResumeConfig resume` ; méthode `ResumeMode resumeMode()` (absent ⇒ `NONE`).

- [ ] **Step 1: Write the failing tests**

`ItemKeyTest.java` :

```java
package com.copybot.engine.resume;

import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

public class ItemKeyTest {

    @TempDir
    Path tempDir;

    @Test
    public void ordersByDateThenName() {
        ItemKey a = new ItemKey(Instant.parse("2026-09-28T10:00:00Z"), "DSC_9999.NEF");
        ItemKey b = new ItemKey(Instant.parse("2026-09-28T10:00:01Z"), "DSC_0001.NEF");
        ItemKey c = new ItemKey(Instant.parse("2026-09-28T10:00:01Z"), "DSC_0002.NEF");

        assertTrue(a.compareTo(b) < 0, "date first: the counter wrap must not matter");
        assertTrue(b.compareTo(c) < 0, "same second: name breaks the tie");
    }

    @Test
    public void truncatesToTheSecond() {
        ItemKey precise = new ItemKey(Instant.parse("2026-09-28T10:00:00.750Z"), "X");
        ItemKey rounded = new ItemKey(Instant.parse("2026-09-28T10:00:00Z"), "X");

        assertEquals(rounded, precise);
        assertEquals(0, precise.compareTo(rounded));
    }

    @Test
    public void ofPrefersCaptureDateAndFallsBackOnLastModified() throws Exception {
        WorkItem item = new WorkItem(Files.createFile(tempDir.resolve("IMG_1.JPG")));
        item.getMetadatas().setTime(WorkItemMetadata.LAST_MODIFIED, Instant.parse("2026-09-29T08:00:00Z"));
        assertEquals(new ItemKey(Instant.parse("2026-09-29T08:00:00Z"), "IMG_1.JPG"), ItemKey.of(item).orElseThrow());

        item.getMetadatas().setTime(WorkItemMetadata.CAPTURE_DATE, Instant.parse("2026-09-28T15:42:10Z"));
        assertEquals(new ItemKey(Instant.parse("2026-09-28T15:42:10Z"), "IMG_1.JPG"), ItemKey.of(item).orElseThrow());
    }

    @Test
    public void ofIsEmptyWithoutAnyDate() throws Exception {
        WorkItem item = new WorkItem(Files.createFile(tempDir.resolve("nodate.bin")));
        assertTrue(ItemKey.of(item).isEmpty());
    }
}
```

`ResumePointTest.java` :

```java
package com.copybot.engine.resume;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

public class ResumePointTest {

    private static final ItemKey K1 = new ItemKey(Instant.parse("2026-09-28T10:00:00Z"), "A");
    private static final ItemKey K2 = new ItemKey(Instant.parse("2026-09-28T10:00:00Z"), "B");
    private static final ItemKey K3 = new ItemKey(Instant.parse("2026-09-28T11:00:00Z"), "A");

    @Test
    public void allSelectsEverything() {
        assertTrue(ResumePoint.all().selects(K1));
        assertTrue(ResumePoint.all().selects(K3));
    }

    @Test
    public void afterIsExclusive() {
        ResumePoint p = ResumePoint.after(K2);
        assertFalse(p.selects(K1));
        assertFalse(p.selects(K2));
        assertTrue(p.selects(K3));
    }

    @Test
    public void fromIsInclusive() {
        ResumePoint p = ResumePoint.from(K2);
        assertFalse(p.selects(K1));
        assertTrue(p.selects(K2));
        assertTrue(p.selects(K3));
    }
}
```

`ResumeConfigTest.java` :

```java
package com.copybot.engine.resume;

import com.copybot.engine.pipeline.PipelineConfig;
import com.copybot.utils.GsonUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ResumeConfigTest {

    private static PipelineConfig parse(String json) {
        return GsonUtil.getGson().fromJson(json, PipelineConfig.class);
    }

    @Test
    public void absentResumeBlockMeansNone() {
        assertEquals(ResumeMode.NONE, parse("{\"inSteps\":[]}").resumeMode());
    }

    @Test
    public void resumeBlockWithoutModeMeansStateThenDestination() {
        assertEquals(ResumeMode.STATE_THEN_DESTINATION, parse("{\"resume\":{}}").resumeMode());
    }

    @Test
    public void modesAreReadFromTheirJsonNames() {
        assertEquals(ResumeMode.NONE, parse("{\"resume\":{\"mode\":\"none\"}}").resumeMode());
        assertEquals(ResumeMode.STATE, parse("{\"resume\":{\"mode\":\"state\"}}").resumeMode());
        assertEquals(ResumeMode.DESTINATION, parse("{\"resume\":{\"mode\":\"destination\"}}").resumeMode());
        assertEquals(ResumeMode.STATE_THEN_DESTINATION, parse("{\"resume\":{\"mode\":\"stateThenDestination\"}}").resumeMode());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -o -q -pl copybot-engine test -Dtest='ItemKeyTest,ResumePointTest,ResumeConfigTest'`
Expected: FAIL (compilation).

- [ ] **Step 3: Implement**

`ItemKey.java` :

```java
package com.copybot.engine.resume;

import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Optional;

/**
 * Resume ordering key of an item: capture date (to the second) then file name. The name only breaks
 * ties between shots of the same second, so a wrapping file counter (DSC_9999 -> DSC_0001) is harmless.
 */
public record ItemKey(Instant date, String name) implements Comparable<ItemKey> {

    private static final Comparator<ItemKey> ORDER = Comparator.comparing(ItemKey::date).thenComparing(ItemKey::name);

    public ItemKey {
        date = date.truncatedTo(ChronoUnit.SECONDS);
        name = name == null ? "" : name;
    }

    /** Capture date when an analysis provided it, file modification date otherwise; empty without any date. */
    public static Optional<ItemKey> of(WorkItem item) {
        WorkItemMetadata metadata = item.getMetadatas();
        return metadata.getTime(WorkItemMetadata.CAPTURE_DATE)
                .or(() -> metadata.getTime(WorkItemMetadata.LAST_MODIFIED))
                .map(date -> new ItemKey(date, item.getNameDisplay()));
    }

    @Override
    public int compareTo(ItemKey other) {
        return ORDER.compare(this, other);
    }
}
```

`ResumePoint.java` :

```java
package com.copybot.engine.resume;

/**
 * Where an import resumes: everything, strictly after a key (automatic detection),
 * or from a key included (manual choice of a file or a date).
 */
public record ResumePoint(Kind kind, ItemKey key) {

    public enum Kind { ALL, AFTER, FROM }

    public static ResumePoint all() {
        return new ResumePoint(Kind.ALL, null);
    }

    public static ResumePoint after(ItemKey key) {
        return new ResumePoint(Kind.AFTER, key);
    }

    public static ResumePoint from(ItemKey key) {
        return new ResumePoint(Kind.FROM, key);
    }

    public boolean selects(ItemKey candidate) {
        return switch (kind) {
            case ALL -> true;
            case AFTER -> candidate.compareTo(key) > 0;
            case FROM -> candidate.compareTo(key) >= 0;
        };
    }
}
```

`ResumeSource.java` :

```java
package com.copybot.engine.resume;

/** Where a resume point comes from. */
public enum ResumeSource {
    /** nothing detected: everything is selected */
    NONE,
    /** the cursor of the state file */
    STATE,
    /** the last item whose target directory exists */
    DESTINATION,
    /** chosen by the user */
    MANUAL
}
```

`ResumeProposal.java` :

```java
package com.copybot.engine.resume;

import java.util.List;

/** The resume point the engine proposes after preparation, with its origin and the warnings to show. */
public record ResumeProposal(ResumePoint point, ResumeSource source, List<String> warnings) {
    public ResumeProposal {
        warnings = List.copyOf(warnings);
    }
}
```

`ResumeMode.java` :

```java
package com.copybot.engine.resume;

import com.google.gson.annotations.SerializedName;

public enum ResumeMode {
    @SerializedName("none") NONE,
    @SerializedName("state") STATE,
    @SerializedName("destination") DESTINATION,
    @SerializedName("stateThenDestination") STATE_THEN_DESTINATION
}
```

`ResumeConfig.java` :

```java
package com.copybot.engine.resume;

/** The "resume" block of a pipeline. */
public record ResumeConfig(ResumeMode mode) {

    /** A resume block without mode opts in the recommended default. */
    public ResumeMode effectiveMode() {
        return mode == null ? ResumeMode.STATE_THEN_DESTINATION : mode;
    }
}
```

`PipelineConfig.java` : ajouter après `startProcessingWhileListing` le composant

```java
        /**
         * Resume detection. Absent: no resume (every listed file is processed, no state file).
         */
        ResumeConfig resume
```

et dans le corps du record :

```java
    public ResumeMode resumeMode() {
        return resume == null ? ResumeMode.NONE : resume.effectiveMode();
    }
```

(imports `com.copybot.engine.resume.ResumeConfig`, `com.copybot.engine.resume.ResumeMode`).

`module-info.java` : ajouter `exports com.copybot.engine.resume to com.google.gson, com.copybot.ui;` à côté de l'export de `com.copybot.engine.pipeline`.

Si un appel `new PipelineConfig(...)` existe dans le code ou les tests (vérifier avec `grep -rn "new PipelineConfig" --include=*.java .`), ajouter l'argument `null` en dernière position.

- [ ] **Step 4: Run tests**

Run: `mvn -o -q -pl copybot-engine test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add copybot-engine/src/main/java/com/copybot/engine/resume copybot-engine/src/main/java/com/copybot/engine/pipeline/PipelineConfig.java copybot-engine/src/main/java/module-info.java copybot-engine/src/test/java/com/copybot/engine/resume
git commit -m "Add resume key, point and pipeline resume configuration"
```

---

### Task 3: Fichier d'état du curseur

**Files:**
- Create: `copybot-engine/src/main/java/com/copybot/engine/resume/ResumeStateStore.java`
- Modify: `copybot-engine/src/main/resources/com/copybot/engine/i18n/engineBundle.properties`, `engineBundle_fr.properties`
- Test: `copybot-engine/src/test/java/com/copybot/engine/resume/ResumeStateStoreTest.java` (créer)

**Interfaces:**
- Consumes: `ItemKey` (Task 2).
- Produces: `final class ResumeStateStore` : `ResumeStateStore(Path stateFile)`, `static ResumeStateStore forPipeline(Path pipelinePath)`, `Path getPath()`, `Optional<ItemKey> readCursor()` (absent ⇒ vide ; invalide ⇒ `CopybotException` clé `resume.state.invalid`), `void writeCursor(ItemKey cursor)` (atomique ; échec ⇒ `CopybotException` clé `resume.state.write-error`).

- [ ] **Step 1: Write the failing test**

```java
package com.copybot.engine.resume;

import com.copybot.exception.CopybotException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public class ResumeStateStoreTest {

    @TempDir
    Path tempDir;

    @Test
    public void stateFileLivesNextToThePipelineWithoutItsExtension() {
        Path pipeline = tempDir.resolve("sd-to-nas.json");
        assertEquals(tempDir.resolve("sd-to-nas.state.json"), ResumeStateStore.forPipeline(pipeline).getPath());
    }

    @Test
    public void missingFileMeansNoCursor() {
        assertTrue(new ResumeStateStore(tempDir.resolve("none.state.json")).readCursor().isEmpty());
    }

    @Test
    public void writeThenReadRoundTrips() throws Exception {
        ResumeStateStore store = new ResumeStateStore(tempDir.resolve("p.state.json"));
        ItemKey cursor = new ItemKey(Instant.parse("2026-09-28T15:42:10Z"), "DSC_4821.NEF");

        store.writeCursor(cursor);

        assertEquals(cursor, store.readCursor().orElseThrow());
        String json = Files.readString(store.getPath());
        assertTrue(json.contains("\"2026-09-28T15:42:10Z\""), "the date must be human readable: " + json);
        assertTrue(json.contains("DSC_4821.NEF"));
        try (Stream<Path> files = Files.list(tempDir)) {
            assertEquals(1, files.count(), "no temporary file may be left behind");
        }
    }

    @Test
    public void writeReplacesAnExistingCursor() {
        ResumeStateStore store = new ResumeStateStore(tempDir.resolve("p.state.json"));
        store.writeCursor(new ItemKey(Instant.parse("2026-09-01T00:00:00Z"), "A"));
        ItemKey second = new ItemKey(Instant.parse("2026-09-02T00:00:00Z"), "B");

        store.writeCursor(second);

        assertEquals(second, store.readCursor().orElseThrow());
    }

    @Test
    public void fileWithoutCursorMeansNoCursor() throws Exception {
        Path file = tempDir.resolve("p.state.json");
        Files.writeString(file, "{}");
        assertTrue(new ResumeStateStore(file).readCursor().isEmpty());
    }

    @Test
    public void invalidFileIsAnExplicitErrorNotAFreshStart() throws Exception {
        Path file = tempDir.resolve("p.state.json");
        Files.writeString(file, "{ this is not json");
        assertThrows(CopybotException.class, () -> new ResumeStateStore(file).readCursor());

        Files.writeString(file, "{\"cursor\":{\"date\":\"yesterday\",\"name\":\"A\"}}");
        assertThrows(CopybotException.class, () -> new ResumeStateStore(file).readCursor());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -o -q -pl copybot-engine test -Dtest=ResumeStateStoreTest`
Expected: FAIL (compilation).

- [ ] **Step 3: Implement**

```java
package com.copybot.engine.resume;

import com.copybot.exception.CopybotException;
import com.copybot.utils.GsonUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * The resume cursor of a pipeline, kept in a small human-editable JSON file next to the pipeline:
 * {@code { "cursor": { "date": "2026-09-28T15:42:10Z", "name": "DSC_4821.NEF" } }}.
 */
public final class ResumeStateStore {

    private static final String STATE_SUFFIX = ".state.json";

    private final Path stateFile;

    public ResumeStateStore(Path stateFile) {
        this.stateFile = stateFile;
    }

    /** {@code dir/sd-to-nas.json} -> {@code dir/sd-to-nas.state.json}. */
    public static ResumeStateStore forPipeline(Path pipelinePath) {
        String fileName = pipelinePath.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String baseName = dot > 0 ? fileName.substring(0, dot) : fileName;
        return new ResumeStateStore(pipelinePath.resolveSibling(baseName + STATE_SUFFIX));
    }

    public Path getPath() {
        return stateFile;
    }

    /**
     * @return the cursor, empty when the file does not exist or holds no cursor
     * @throws CopybotException when the file exists but cannot be understood: starting over silently
     *                          would re-import the whole card
     */
    public Optional<ItemKey> readCursor() {
        if (!Files.exists(stateFile)) {
            return Optional.empty();
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(stateFile)).getAsJsonObject();
            JsonElement cursor = root.get("cursor");
            if (cursor == null || cursor.isJsonNull()) {
                return Optional.empty();
            }
            JsonObject c = cursor.getAsJsonObject();
            return Optional.of(new ItemKey(Instant.parse(c.get("date").getAsString()), c.get("name").getAsString()));
        } catch (IOException | JsonParseException | IllegalStateException | NullPointerException
                 | UnsupportedOperationException | DateTimeParseException e) {
            throw CopybotException.ofResource(e, "resume.state.invalid", stateFile.toAbsolutePath());
        }
    }

    /** Writes a temporary file in the same directory then moves it over the state file. */
    public void writeCursor(ItemKey cursor) {
        JsonObject c = new JsonObject();
        c.addProperty("date", cursor.date().toString());
        c.addProperty("name", cursor.name());
        JsonObject root = new JsonObject();
        root.add("cursor", c);

        Path temp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
        try {
            Files.writeString(temp, GsonUtil.getGson().toJson(root));
            try {
                Files.move(temp, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // best effort: the original error is the one worth reporting
            }
            throw CopybotException.ofResource(e, "resume.state.write-error", stateFile.toAbsolutePath());
        }
    }
}
```

Ajouter à `engineBundle.properties` :

```properties

resume.state.invalid=Resume state file "{0}" is not valid (fix or delete it)
resume.state.write-error=Could not write resume state file "{0}"
```

et à `engineBundle_fr.properties` :

```properties

resume.state.invalid=Le fichier d''état de reprise "{0}" est invalide (le corriger ou le supprimer)
resume.state.write-error=Impossible d''écrire le fichier d''état de reprise "{0}"
```

- [ ] **Step 4: Run tests**

Run: `mvn -o -q -pl copybot-engine test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add copybot-engine/src/main/java/com/copybot/engine/resume/ResumeStateStore.java copybot-engine/src/main/resources/com/copybot/engine/i18n copybot-engine/src/test/java/com/copybot/engine/resume/ResumeStateStoreTest.java
git commit -m "Persist the resume cursor in a state file next to the pipeline"
```

---

### Task 4: Chemin cible et sonde de destination

**Files:**
- Modify: `copybot-engine/src/main/java/com/copybot/plugin/api/action/IOutAction.java`
- Modify: `copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileWriteAction.java`
- Create: `copybot-engine/src/main/java/com/copybot/engine/resume/DestinationProbe.java`
- Modify: les deux `engineBundle*.properties`
- Test: `copybot-engine/src/test/java/com/copybot/engine/resume/DestinationProbeTest.java` (créer) ; ajouter un test dans `copybot-engine/src/test/java/com/copybot/plugin/embedded/actions/FileWriteActionTest.java`

**Interfaces:**
- Consumes: `ItemKey`, `ResumePoint` (Task 2).
- Produces:
  - `IOutAction` : `default Optional<Path> resolveTarget(WorkItem workItem) { return Optional.empty(); }`.
  - `FileWriteAction.resolveTarget` : `Optional.of(Path.of(<pattern résolu>))`.
  - `DestinationProbe` : `record Candidate(ItemKey key, Path targetDir)`, `record Result(ResumePoint point, String warning)` (`warning` nullable), `static Result probe(List<Candidate> ordered, Predicate<Path> dirExists)`.

- [ ] **Step 1: Write the failing tests**

`DestinationProbeTest.java` :

```java
package com.copybot.engine.resume;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class DestinationProbeTest {

    private static final Path D1 = Path.of("nas", "2026-09-01");
    private static final Path D2 = Path.of("nas", "2026-09-02");
    private static final Path D3 = Path.of("nas", "2026-09-03");

    private static DestinationProbe.Candidate c(int day, int n, Path dir) {
        return new DestinationProbe.Candidate(
                new ItemKey(Instant.parse(String.format("2026-09-%02dT10:00:%02dZ", day, n)), "IMG_" + day + n), dir);
    }

    private static final List<DestinationProbe.Candidate> CARD = List.of(
            c(1, 1, D1), c(1, 2, D1), c(2, 1, D2), c(2, 2, D2), c(3, 1, D3));

    @Test
    public void nothingImportedYetSelectsEverything() {
        DestinationProbe.Result r = DestinationProbe.probe(CARD, dir -> false);
        assertEquals(ResumePoint.all(), r.point());
        assertNull(r.warning());
    }

    @Test
    public void resumesAfterTheLastItemWhoseDirectoryExists() {
        DestinationProbe.Result r = DestinationProbe.probe(CARD, Set.of(D1, D2)::contains);
        assertEquals(ResumePoint.after(CARD.get(3).key()), r.point(),
                "files of an existing day directory count as imported, even if some were deleted from it");
    }

    @Test
    public void everythingImported() {
        DestinationProbe.Result r = DestinationProbe.probe(CARD, dir -> true);
        assertEquals(ResumePoint.after(CARD.get(4).key()), r.point());
    }

    @Test
    public void usesADichotomyNotOneCheckPerFile() {
        List<DestinationProbe.Candidate> big = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            big.add(new DestinationProbe.Candidate(
                    new ItemKey(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i * 3600L), "F" + i),
                    Path.of("nas", "d" + (i / 10))));
        }
        AtomicInteger checks = new AtomicInteger();
        DestinationProbe.probe(big, dir -> {
            checks.incrementAndGet();
            return Integer.parseInt(dir.getFileName().toString().substring(1)) < 42;
        });
        assertTrue(checks.get() <= 12, "expected ~log2(1000) checks, got " + checks.get());
    }

    @Test
    public void singleTargetDirectoryIsIgnoredWithAWarning() {
        List<DestinationProbe.Candidate> flat = List.of(c(1, 1, D1), c(2, 1, D1), c(3, 1, D1));
        DestinationProbe.Result r = DestinationProbe.probe(flat, dir -> true);
        assertEquals(ResumePoint.all(), r.point());
        assertNotNull(r.warning());
    }

    @Test
    public void emptyListSelectsEverything() {
        assertEquals(ResumePoint.all(), DestinationProbe.probe(List.of(), dir -> true).point());
    }
}
```

Dans `FileWriteActionTest.java`, ajouter (en réutilisant la façon dont les tests existants du fichier instancient `FileWriteAction` et chargent sa config ; lire le fichier d'abord) un test :

```java
    @Test
    public void resolveTargetAppliesTheOutPatternWithoutWriting(@TempDir Path dir) throws Exception {
        Path source = Files.writeString(dir.resolve("DSC_1.NEF"), "x");
        WorkItem item = new WorkItem(source);
        item.getMetadatas().display().put("name", "DSC_1.NEF");
        item.getMetadatas().setTime(WorkItemMetadata.CAPTURE_DATE, Instant.parse("2026-09-28T10:00:00Z"));
        FileWriteAction action = new FileWriteAction();
        String outPattern = dir.resolve("out").toString().replace('\\', '/') + "/{captureDate.Y}/{name}";
        action.loadConfig(JsonParser.parseString("{\"outPattern\":\"" + outPattern + "\",\"overwrite\":false}"));

        Path target = action.resolveTarget(item).orElseThrow();

        assertEquals(dir.resolve("out").resolve("2026").resolve("DSC_1.NEF"), target.toAbsolutePath().normalize());
        assertFalse(Files.exists(dir.resolve("out")), "resolving a target must not create anything");
    }
```

(imports nécessaires : `com.copybot.plugin.api.action.WorkItem`, `WorkItemMetadata`, `com.google.gson.JsonParser`, `java.time.Instant`, `org.junit.jupiter.api.io.TempDir`, `java.nio.file.*` s'ils manquent.)

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -o -q -pl copybot-engine test -Dtest='DestinationProbeTest,FileWriteActionTest'`
Expected: FAIL (compilation).

- [ ] **Step 3: Implement**

`IOutAction.java` :

```java
package com.copybot.plugin.api.action;

import java.nio.file.Path;
import java.util.Optional;

public interface IOutAction extends IAction {

    void writeItem(WorkItem workItem);

    /**
     * Where {@link #writeItem} would write this item, without writing anything. Used to detect what
     * was already imported. Empty when the action cannot tell (the default).
     */
    default Optional<Path> resolveTarget(WorkItem workItem) {
        return Optional.empty();
    }
}
```

`FileWriteAction.java` : ajouter

```java
    @Override
    public Optional<Path> resolveTarget(WorkItem workItem) {
        return Optional.of(Path.of(resolveFileName(workItem)));
    }
```

(import `java.util.Optional`).

`DestinationProbe.java` :

```java
package com.copybot.engine.resume;

import com.copybot.resources.ResourcesEngine;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * "Resume from destination": an item counts as imported when its target directory exists (one
 * directory per day: deleting photos inside it afterwards does not move the resume point back).
 * The destination is assumed filled up to a point, so a dichotomy finds it in ~log2(n) checks.
 */
public final class DestinationProbe {

    public record Candidate(ItemKey key, Path targetDir) {
    }

    /** @param warning message to show the user, null when none */
    public record Result(ResumePoint point, String warning) {
    }

    private DestinationProbe() {
    }

    /** @param ordered candidates sorted by key */
    public static Result probe(List<Candidate> ordered, Predicate<Path> dirExists) {
        if (ordered.isEmpty()) {
            return new Result(ResumePoint.all(), null);
        }
        Path first = ordered.getFirst().targetDir();
        if (ordered.size() >= 2 && ordered.stream().allMatch(c -> Objects.equals(c.targetDir(), first))) {
            // the out pattern has no variable directory: existence of the only directory tells nothing
            return new Result(ResumePoint.all(), ResourcesEngine.getString("resume.warn.single-directory", first));
        }
        int lastExisting = -1;
        int firstMissing = ordered.size();
        while (firstMissing - lastExisting > 1) {
            int mid = (lastExisting + firstMissing) >>> 1;
            if (dirExists.test(ordered.get(mid).targetDir())) {
                lastExisting = mid;
            } else {
                firstMissing = mid;
            }
        }
        return new Result(lastExisting < 0 ? ResumePoint.all() : ResumePoint.after(ordered.get(lastExisting).key()), null);
    }
}
```

Ajouter à `engineBundle.properties` :

```properties
resume.warn.single-directory=Resume from destination ignored: every file goes to the same directory "{0}" (the out pattern has no variable directory)
```

et à `engineBundle_fr.properties` :

```properties
resume.warn.single-directory=Reprise depuis la destination ignorée : tous les fichiers vont dans le même répertoire "{0}" (le pattern de sortie n''a pas de répertoire variable)
```

- [ ] **Step 4: Run tests**

Run: `mvn -o -q -pl copybot-engine test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add copybot-engine/src/main/java/com/copybot/plugin/api/action/IOutAction.java copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileWriteAction.java copybot-engine/src/main/java/com/copybot/engine/resume/DestinationProbe.java copybot-engine/src/main/resources/com/copybot/engine/i18n copybot-engine/src/test/java/com/copybot/engine/resume/DestinationProbeTest.java copybot-engine/src/test/java/com/copybot/plugin/embedded/actions/FileWriteActionTest.java
git commit -m "Let out actions resolve their target and probe the destination by dichotomy"
```

---

### Task 5: Résolveur de reprise et statut SKIPPED

**Files:**
- Modify: `copybot-engine/src/main/java/com/copybot/engine/pipeline/ItemStatus.java` (+`SKIPPED`)
- Modify: `copybot-engine/src/main/java/com/copybot/engine/pipeline/WorkItemExecution.java`
- Create: `copybot-engine/src/main/java/com/copybot/engine/resume/ResumeResolver.java`, `ResumeContext.java`
- Modify: les deux `engineBundle*.properties`
- Test: `copybot-engine/src/test/java/com/copybot/engine/resume/ResumeResolverTest.java` (créer)

**Interfaces:**
- Consumes: Tasks 2–4 (`ItemKey`, `ResumePoint`, `ResumeSource`, `ResumeProposal`, `ResumeMode`, `ResumeStateStore`, `DestinationProbe`, `IOutAction.resolveTarget`).
- Produces:
  - `ItemStatus.SKIPPED` ; `WorkItemExecution.setSkipped(String reason)`, `String getSkipReason()`, `setReady()` (retour à `PENDING`).
  - `record ResumeContext(ResumeMode mode, ResumeStateStore store)`.
  - `final class ResumeResolver` :
    - `ResumeResolver(ResumeMode mode, ResumeStateStore store, IOutAction out)` (`store` ignoré si `NONE`, `out` nullable) ;
    - `static List<WorkItemExecution> order(Collection<WorkItemExecution> items)` (items avec clé triés, sans clé à la fin) ;
    - `ResumeProposal propose(List<WorkItemExecution> ordered)` (lit l'ancien curseur) ;
    - `void apply(ResumePoint point, ResumeSource source, List<WorkItemExecution> ordered)` ;
    - `Optional<ItemKey> nextCursor(List<WorkItemExecution> ordered, ResumePoint point, ResumeSource source)` (vide si rien à écrire).

- [ ] **Step 1: Write the failing test**

```java
package com.copybot.engine.resume;

import com.copybot.engine.pipeline.ItemStatus;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.IOutAction;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import com.copybot.plugin.api.action.WorkStatus;
import com.copybot.plugin.api.definition.IPlugin;
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

import static org.junit.jupiter.api.Assertions.*;

public class ResumeResolverTest {

    @TempDir
    Path tempDir;

    /** Out action writing to nas/<day>/<name>, day taken from the key date. */
    final class DayOut implements IOutAction {
        @Override
        public void writeItem(WorkItem workItem) {
        }

        @Override
        public Optional<Path> resolveTarget(WorkItem workItem) {
            ItemKey key = ItemKey.of(workItem).orElseThrow();
            return Optional.of(tempDir.resolve("nas").resolve(key.date().toString().substring(0, 10)).resolve(key.name()));
        }

        @Override
        public void setStatusWatcher(Consumer<WorkStatus> watcher) {
        }

        @Override
        public void setPlugin(IPlugin plugin) {
        }
    }

    private WorkItemExecution item(String name, String instant) throws IOException {
        WorkItem wi = new WorkItem(Files.createFile(tempDir.resolve(name)));
        if (instant != null) {
            wi.getMetadatas().setTime(WorkItemMetadata.LAST_MODIFIED, Instant.parse(instant));
        }
        return new WorkItemExecution(wi, List.of());
    }

    private List<WorkItemExecution> card() throws IOException {
        List<WorkItemExecution> items = new ArrayList<>();
        items.add(item("C.JPG", "2026-09-03T10:00:00Z"));
        items.add(item("A.JPG", "2026-09-01T10:00:00Z"));
        items.add(item("B.JPG", "2026-09-02T10:00:00Z"));
        return ResumeResolver.order(items);
    }

    private static ItemKey key(WorkItemExecution w) {
        return ItemKey.of(w.getWorkItem()).orElseThrow();
    }

    private ResumeStateStore store() {
        return new ResumeStateStore(tempDir.resolve("p.state.json"));
    }

    @Test
    public void orderSortsByKeyAndPutsKeylessItemsLast() throws IOException {
        List<WorkItemExecution> items = List.of(item("nodate.bin", null), item("B.JPG", "2026-09-02T10:00:00Z"),
                item("A.JPG", "2026-09-01T10:00:00Z"));
        List<String> names = ResumeResolver.order(items).stream().map(w -> w.getWorkItem().getNameDisplay()).toList();
        assertEquals(List.of("A.JPG", "B.JPG", "nodate.bin"), names);
    }

    @Test
    public void noneSelectsEverything() throws IOException {
        List<WorkItemExecution> ordered = card();
        ResumeProposal p = new ResumeResolver(ResumeMode.NONE, null, null).propose(ordered);
        assertEquals(ResumePoint.all(), p.point());
        assertEquals(ResumeSource.NONE, p.source());
    }

    @Test
    public void stateResumesAfterTheCursorAndSkipsWithAReason() throws IOException {
        List<WorkItemExecution> ordered = card();
        store().writeCursor(key(ordered.get(0)));
        ResumeResolver resolver = new ResumeResolver(ResumeMode.STATE, store(), null);

        ResumeProposal p = resolver.propose(ordered);
        resolver.apply(p.point(), p.source(), ordered);

        assertEquals(ResumePoint.after(key(ordered.get(0))), p.point());
        assertEquals(ResumeSource.STATE, p.source());
        assertEquals(ItemStatus.SKIPPED, ordered.get(0).getStatus());
        assertTrue(ordered.get(0).getSkipReason().contains("A.JPG"), ordered.get(0).getSkipReason());
        assertEquals(ItemStatus.PENDING, ordered.get(1).getStatus());
        assertEquals(ItemStatus.PENDING, ordered.get(2).getStatus());
    }

    @Test
    public void stateWithoutFileSelectsEverything() throws IOException {
        ResumeProposal p = new ResumeResolver(ResumeMode.STATE, store(), null).propose(card());
        assertEquals(ResumePoint.all(), p.point());
        assertEquals(ResumeSource.NONE, p.source());
    }

    @Test
    public void destinationResumesAfterTheLastExistingDay() throws IOException {
        List<WorkItemExecution> ordered = card();
        Files.createDirectories(tempDir.resolve("nas").resolve("2026-09-01"));
        Files.createDirectories(tempDir.resolve("nas").resolve("2026-09-02"));

        ResumeProposal p = new ResumeResolver(ResumeMode.DESTINATION, store(), new DayOut()).propose(ordered);

        assertEquals(ResumePoint.after(key(ordered.get(1))), p.point());
        assertEquals(ResumeSource.DESTINATION, p.source());
    }

    @Test
    public void explicitDestinationWithoutTargetResolutionFails() throws IOException {
        List<WorkItemExecution> ordered = card();
        assertThrows(CopybotException.class,
                () -> new ResumeResolver(ResumeMode.DESTINATION, store(), null).propose(ordered));
    }

    @Test
    public void stateThenDestinationFallsBackOnDestinationThenOnEverythingWithAWarning() throws IOException {
        List<WorkItemExecution> ordered = card();
        Files.createDirectories(tempDir.resolve("nas").resolve("2026-09-01"));

        ResumeProposal viaDestination = new ResumeResolver(ResumeMode.STATE_THEN_DESTINATION, store(), new DayOut()).propose(ordered);
        assertEquals(ResumeSource.DESTINATION, viaDestination.source());

        ResumeProposal noTarget = new ResumeResolver(ResumeMode.STATE_THEN_DESTINATION, store(), null).propose(ordered);
        assertEquals(ResumePoint.all(), noTarget.point());
        assertEquals(1, noTarget.warnings().size());

        store().writeCursor(key(ordered.get(1)));
        ResumeProposal viaState = new ResumeResolver(ResumeMode.STATE_THEN_DESTINATION, store(), new DayOut()).propose(ordered);
        assertEquals(ResumeSource.STATE, viaState.source());
        assertEquals(ResumePoint.after(key(ordered.get(1))), viaState.point());
    }

    @Test
    public void itemWithoutDateBecomesAnError() throws IOException {
        List<WorkItemExecution> ordered = ResumeResolver.order(List.of(item("nodate.bin", null)));
        new ResumeResolver(ResumeMode.NONE, null, null).apply(ResumePoint.all(), ResumeSource.NONE, ordered);
        assertEquals(ItemStatus.ERROR, ordered.get(0).getStatus());
        assertNotNull(ordered.get(0).getError());
    }

    @Test
    public void manualOverrideRecomputesSkippedItems() throws IOException {
        List<WorkItemExecution> ordered = card();
        store().writeCursor(key(ordered.get(2)));
        ResumeResolver resolver = new ResumeResolver(ResumeMode.STATE, store(), null);
        ResumeProposal p = resolver.propose(ordered);
        resolver.apply(p.point(), p.source(), ordered);
        assertTrue(ordered.stream().allMatch(w -> w.getStatus() == ItemStatus.SKIPPED));

        resolver.apply(ResumePoint.from(key(ordered.get(1))), ResumeSource.MANUAL, ordered);

        assertEquals(ItemStatus.SKIPPED, ordered.get(0).getStatus());
        assertEquals(ItemStatus.PENDING, ordered.get(1).getStatus());
        assertEquals(ItemStatus.PENDING, ordered.get(2).getStatus());
    }

    @Test
    public void cursorAdvancesToTheLastItemWhenEverythingSucceeded() throws IOException {
        List<WorkItemExecution> ordered = card();
        ResumeResolver resolver = new ResumeResolver(ResumeMode.STATE, store(), null);
        ResumeProposal p = resolver.propose(ordered);
        ordered.forEach(WorkItemExecution::setDone);

        assertEquals(Optional.of(key(ordered.get(2))), resolver.nextCursor(ordered, p.point(), p.source()));
    }

    @Test
    public void cursorStopsBeforeTheFirstFailure() throws IOException {
        List<WorkItemExecution> ordered = card();
        ResumeResolver resolver = new ResumeResolver(ResumeMode.STATE, store(), null);
        ResumeProposal p = resolver.propose(ordered);
        ordered.get(0).setDone();
        ordered.get(1).setError(new IllegalStateException("NAS gone"));
        ordered.get(2).setDone();

        assertEquals(Optional.of(key(ordered.get(0))), resolver.nextCursor(ordered, p.point(), p.source()));
    }

    @Test
    public void noSuccessKeepsTheCursorUnchanged() throws IOException {
        List<WorkItemExecution> ordered = card();
        store().writeCursor(key(ordered.get(0)));
        ResumeResolver resolver = new ResumeResolver(ResumeMode.STATE, store(), null);
        ResumeProposal p = resolver.propose(ordered);
        ordered.get(1).setError(new IllegalStateException("boom"));

        assertTrue(resolver.nextCursor(ordered, p.point(), p.source()).isEmpty(), "nothing new to write");
    }

    @Test
    public void manualReimportOfOldFilesDoesNotMoveTheCursorBack() throws IOException {
        List<WorkItemExecution> ordered = card();
        store().writeCursor(key(ordered.get(2)));
        ResumeResolver resolver = new ResumeResolver(ResumeMode.STATE, store(), null);
        resolver.propose(ordered);
        ResumePoint manual = ResumePoint.from(key(ordered.get(0)));
        resolver.apply(manual, ResumeSource.MANUAL, ordered);
        ordered.get(0).setDone();
        ordered.get(1).setDone();
        ordered.get(2).setDone();

        assertTrue(resolver.nextCursor(ordered, manual, ResumeSource.MANUAL).isEmpty());
    }

    @Test
    public void destinationPointIsPersistedEvenWhenNothingNewWasCopied() throws IOException {
        List<WorkItemExecution> ordered = card();
        Files.createDirectories(tempDir.resolve("nas").resolve("2026-09-01"));
        Files.createDirectories(tempDir.resolve("nas").resolve("2026-09-02"));
        Files.createDirectories(tempDir.resolve("nas").resolve("2026-09-03"));
        ResumeResolver resolver = new ResumeResolver(ResumeMode.DESTINATION, store(), new DayOut());
        ResumeProposal p = resolver.propose(ordered);

        assertEquals(Optional.of(key(ordered.get(2))), resolver.nextCursor(ordered, p.point(), p.source()));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -o -q -pl copybot-engine test -Dtest=ResumeResolverTest`
Expected: FAIL (compilation).

- [ ] **Step 3: Implement**

`ItemStatus.java` : ajouter `SKIPPED` après `ERROR` avec la javadoc `/** not selected by the resume point: see WorkItemExecution#getSkipReason() */`.

`WorkItemExecution.java` : ajouter le champ `private volatile String skipReason;` et :

```java
    /** Not selected by the resume point; the reason is shown to the user. */
    public void setSkipped(String reason) {
        this.skipReason = reason;
        this.waitingFor = Set.of();
        this.status = ItemStatus.SKIPPED;
    }

    public String getSkipReason() {
        return skipReason;
    }

    /** Back to PENDING: stopped at the preparation barrier, or selected again by a manual resume point. */
    public void setReady() {
        this.skipReason = null;
        this.waitingFor = Set.of();
        this.status = ItemStatus.PENDING;
    }
```

`ResumeContext.java` :

```java
package com.copybot.engine.resume;

/** What the executor needs to resume a pipeline. */
public record ResumeContext(ResumeMode mode, ResumeStateStore store) {
}
```

`ResumeResolver.java` :

```java
package com.copybot.engine.resume;

import com.copybot.engine.pipeline.ItemStatus;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.IOutAction;
import com.copybot.resources.ResourcesEngine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Decides which prepared items are imported: proposes a resume point according to the mode,
 * applies a point (SKIPPED with a reason / PENDING), and computes the cursor to persist after the run.
 */
public final class ResumeResolver {

    private static final DateTimeFormatter DISPLAY_DATE =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(ZoneId.systemDefault());

    private final ResumeMode mode;
    private final ResumeStateStore store;
    private final IOutAction out;

    private Optional<ItemKey> previousCursor = Optional.empty();

    /**
     * @param store state file, unused when mode is NONE
     * @param out   the pipeline out action, null when there is none
     */
    public ResumeResolver(ResumeMode mode, ResumeStateStore store, IOutAction out) {
        this.mode = mode;
        this.store = store;
        this.out = out;
    }

    /** Items with a key sorted by key, then the items without key in their original order. */
    public static List<WorkItemExecution> order(Collection<WorkItemExecution> items) {
        List<WorkItemExecution> keyed = new ArrayList<>();
        List<WorkItemExecution> keyless = new ArrayList<>();
        for (WorkItemExecution item : items) {
            (ItemKey.of(item.getWorkItem()).isPresent() ? keyed : keyless).add(item);
        }
        keyed.sort(Comparator.comparing(item -> ItemKey.of(item.getWorkItem()).orElseThrow()));
        keyed.addAll(keyless);
        return keyed;
    }

    public ResumeProposal propose(List<WorkItemExecution> ordered) {
        if (mode == ResumeMode.NONE) {
            return everything(List.of());
        }
        previousCursor = store.readCursor();
        return switch (mode) {
            case STATE -> fromState().orElseGet(() -> everything(List.of()));
            case DESTINATION -> fromDestination(ordered, true);
            case STATE_THEN_DESTINATION -> fromState().orElseGet(() -> fromDestination(ordered, false));
            case NONE -> throw new IllegalStateException("unreachable");
        };
    }

    /**
     * Applies a resume point to the items still undecided (PENDING or SKIPPED); failed items are left
     * alone, items without any date become errors (never silently skipped).
     */
    public void apply(ResumePoint point, ResumeSource source, List<WorkItemExecution> ordered) {
        for (WorkItemExecution item : ordered) {
            if (item.getStatus() != ItemStatus.PENDING && item.getStatus() != ItemStatus.SKIPPED) {
                continue;
            }
            Optional<ItemKey> key = ItemKey.of(item.getWorkItem());
            if (key.isEmpty()) {
                item.setError(CopybotException.ofResource("resume.item.no-date", item.getWorkItem().getNameDisplay()));
            } else if (point.selects(key.get())) {
                item.setReady();
            } else {
                item.setSkipped(skipReason(point, source));
            }
        }
    }

    /**
     * The cursor to persist once the run is over: the last item of the longest run of successes among
     * the selected items (in key order), never before the automatic resume point nor the previous cursor.
     *
     * @return empty when there is nothing (new) to write
     */
    public Optional<ItemKey> nextCursor(List<WorkItemExecution> ordered, ResumePoint point, ResumeSource source) {
        ItemKey lastSuccess = null;
        for (WorkItemExecution item : ordered) {
            Optional<ItemKey> key = ItemKey.of(item.getWorkItem());
            if (key.isEmpty() || !point.selects(key.get())) {
                continue;
            }
            if (item.getStatus() != ItemStatus.DONE) {
                break;
            }
            lastSuccess = key.get();
        }
        ItemKey automaticPoint = (source == ResumeSource.STATE || source == ResumeSource.DESTINATION)
                && point.kind() == ResumePoint.Kind.AFTER ? point.key() : null;
        Optional<ItemKey> next = Stream.of(previousCursor.orElse(null), automaticPoint, lastSuccess)
                .filter(k -> k != null)
                .max(Comparator.naturalOrder());
        if (next.isEmpty() || next.equals(previousCursor)) {
            return Optional.empty();
        }
        return next;
    }

    private Optional<ResumeProposal> fromState() {
        return previousCursor.map(cursor -> new ResumeProposal(ResumePoint.after(cursor), ResumeSource.STATE, List.of()));
    }

    private ResumeProposal fromDestination(List<WorkItemExecution> ordered, boolean explicit) {
        if (out == null) {
            return noTarget(explicit);
        }
        List<DestinationProbe.Candidate> candidates = new ArrayList<>();
        for (WorkItemExecution item : ordered) {
            Optional<ItemKey> key = ItemKey.of(item.getWorkItem());
            if (item.getStatus() == ItemStatus.ERROR || key.isEmpty()) {
                continue;
            }
            Optional<Path> target = out.resolveTarget(item.getWorkItem());
            if (target.isEmpty()) {
                return noTarget(explicit);
            }
            candidates.add(new DestinationProbe.Candidate(key.get(), target.get().toAbsolutePath().normalize().getParent()));
        }
        DestinationProbe.Result result = DestinationProbe.probe(candidates, Files::isDirectory);
        ResumeSource source = result.point().kind() == ResumePoint.Kind.AFTER ? ResumeSource.DESTINATION : ResumeSource.NONE;
        return new ResumeProposal(result.point(), source, result.warning() == null ? List.of() : List.of(result.warning()));
    }

    private ResumeProposal noTarget(boolean explicit) {
        if (explicit) {
            throw CopybotException.ofResource("resume.error.no-target");
        }
        return everything(List.of(ResourcesEngine.getString("resume.warn.no-target")));
    }

    private static ResumeProposal everything(List<String> warnings) {
        return new ResumeProposal(ResumePoint.all(), ResumeSource.NONE, warnings);
    }

    private static String skipReason(ResumePoint point, ResumeSource source) {
        String resourceKey = switch (source) {
            case STATE -> "resume.skip.state";
            case DESTINATION -> "resume.skip.destination";
            case MANUAL, NONE -> "resume.skip.manual";
        };
        return ResourcesEngine.getString(resourceKey, point.key().name(), DISPLAY_DATE.format(point.key().date()));
    }
}
```

Ajouter à `engineBundle.properties` :

```properties
resume.skip.state=Already imported (cursor: {0}, {1})
resume.skip.destination=Already imported (target directory exists up to {0}, {1})
resume.skip.manual=Before the chosen resume point ({0}, {1})
resume.item.no-date=No date available to order "{0}" for resume
resume.warn.no-target=Resume from destination ignored: the out step cannot tell target paths
resume.error.no-target=Resume mode "destination" requires an out step able to resolve target paths
```

et à `engineBundle_fr.properties` :

```properties
resume.skip.state=Déjà importé (curseur : {0}, {1})
resume.skip.destination=Déjà importé (répertoire cible existant jusqu''à {0}, {1})
resume.skip.manual=Avant le point de reprise choisi ({0}, {1})
resume.item.no-date=Aucune date pour ordonner "{0}" en vue de la reprise
resume.warn.no-target=Reprise depuis la destination ignorée : l''étape de sortie ne sait pas donner les chemins cibles
resume.error.no-target=Le mode de reprise "destination" nécessite une étape de sortie capable de donner les chemins cibles
```

- [ ] **Step 4: Run tests**

Run: `mvn -o -q -pl copybot-engine test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add copybot-engine/src/main/java/com/copybot/engine/pipeline/ItemStatus.java copybot-engine/src/main/java/com/copybot/engine/pipeline/WorkItemExecution.java copybot-engine/src/main/java/com/copybot/engine/resume copybot-engine/src/main/resources/com/copybot/engine/i18n copybot-engine/src/test/java/com/copybot/engine/resume/ResumeResolverTest.java
git commit -m "Resolve the resume point, skip already imported items and compute the next cursor"
```

---

### Task 6: Barrière préparation / exécution dans MainExecutor

**Files:**
- Modify: `copybot-engine/src/main/java/com/copybot/engine/MainExecutor.java`
- Modify: `copybot-engine/src/main/java/com/copybot/engine/pipeline/PipelineStatus.java` (+`PREPARED`)
- Modify: `copybot-engine/src/main/java/com/copybot/engine/pipeline/PipelineState.java`
- Modify: `copybot-engine/src/main/java/com/copybot/engine/CopybotEngine.java` (seulement l'appel du constructeur, voir Step 3)
- Test: `copybot-engine/src/test/java/com/copybot/engine/MainExecutorResumeTest.java` (créer) ; `MainExecutorTest` doit rester vert **sans modification**.

**Interfaces:**
- Consumes: Task 5 (`ResumeResolver`, `ResumeContext`, `ItemStatus.SKIPPED`, `WorkItemExecution.setReady/setSkipped`), Task 4 (`IOutAction.resolveTarget`).
- Produces:
  - `PipelineStatus.PREPARED`.
  - `PipelineState` : `ResumeProposal getResumeProposal()` / `setResumeProposal(ResumeProposal)`, `Throwable getFailure()` / `setFailure(Throwable)`.
  - `MainExecutor` :
    - `public MainExecutor(PipelineConfig, Consumer<PipelineState> watcher, ResourceRegistry, ResumeContext resume)` — `resume` null ⇒ `run()` exécute en une phase comme aujourd'hui ;
    - constructeur de test existant inchangé (sans barrière) ;
    - nouveau constructeur de test `MainExecutor(List<PipelineStep<IInAction>> inSteps, List<PipelineStep<?>> itemSteps, int barrierIndex, boolean startProcessingWhileListing, Consumer<PipelineState> watcher, ResourceRegistry registry, ResumeContext resume)` ;
    - `public void prepare()` — bloquant ; statut final `PREPARED` ou `ERROR` ; relance les `RuntimeException` (ex. fichier d'état invalide) après avoir mis `ERROR` ;
    - `public void execute(ResumePoint override)` — bloquant ; `override` null ⇒ point proposé ; `IllegalStateException` si le statut n'est pas `PREPARED` ;
    - `ResumeProposal getProposal()`, `List<WorkItemExecution> getOrderedItems()` (package-private) ;
    - `run()` : `resume == null` ⇒ une phase ; sinon `prepare()` puis, si `PREPARED`, `execute(null)`.

- [ ] **Step 1: Write the failing test**

```java
package com.copybot.engine;

import com.copybot.config.CopybotConfig;
import com.copybot.engine.pipeline.*;
import com.copybot.engine.resources.ResourceRegistry;
import com.copybot.engine.resources.ResourceSettings;
import com.copybot.engine.resume.*;
import com.copybot.plugin.api.action.*;
import com.copybot.plugin.api.definition.IPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

public class MainExecutorResumeTest {

    @TempDir
    Path tempDir;

    abstract static class FakeAction implements IAction {
        @Override
        public void setStatusWatcher(Consumer<WorkStatus> watcher) {
        }

        @Override
        public void setPlugin(IPlugin plugin) {
        }
    }

    /** Emits one item per day of September, dated through lastModified. */
    final class DatedIn extends FakeAction implements IInAction {
        final int days;

        DatedIn(int days) {
            this.days = days;
        }

        @Override
        public void listFiles(Consumer<WorkItem> consumer) {
            for (int day = 1; day <= days; day++) {
                try {
                    WorkItem wi = new WorkItem(Files.createFile(tempDir.resolve(String.format("IMG_%02d.JPG", day))));
                    wi.getMetadatas().setTime(WorkItemMetadata.LAST_MODIFIED,
                            Instant.parse(String.format("2026-09-%02dT10:00:00Z", day)));
                    consumer.accept(wi);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
    }

    static final class RecordingAnalyze extends FakeAction implements IAnalyzeAction {
        final Set<String> seen = ConcurrentHashMap.newKeySet();

        @Override
        public void doAnalyze(WorkItem item) {
            seen.add(item.getNameDisplay());
        }
    }

    static final class RecordingOut extends FakeAction implements IOutAction {
        final Set<String> written = ConcurrentHashMap.newKeySet();
        final String failOn;

        RecordingOut(String failOn) {
            this.failOn = failOn;
        }

        @Override
        public void writeItem(WorkItem item) {
            if (item.getNameDisplay().equals(failOn)) {
                throw new IllegalStateException("write failed");
            }
            written.add(item.getNameDisplay());
        }
    }

    static PipelineStepConfig emptyConfig() {
        return new PipelineStepConfig(null, null, null, null, null, null, null, null);
    }

    static ResourceRegistry registry() {
        return new ResourceRegistry(ResourceSettings.from(new CopybotConfig(null, null, Map.of("disk:*", 1000), null)));
    }

    private ResumeStateStore store() {
        return new ResumeStateStore(tempDir.resolve("p.state.json"));
    }

    private MainExecutor executor(int days, RecordingAnalyze analyze, RecordingOut out, ResumeMode mode) {
        return new MainExecutor(
                List.of(new PipelineStep<>(null, new DatedIn(days), emptyConfig())),
                List.of(new PipelineStep<>(null, analyze, emptyConfig()), new PipelineStep<>(null, out, emptyConfig())),
                1, false, null, registry(), new ResumeContext(mode, store()));
    }

    private static ItemKey day(int d) {
        return new ItemKey(Instant.parse(String.format("2026-09-%02dT10:00:00Z", d)), String.format("IMG_%02d.JPG", d));
    }

    @Test
    public void prepareRunsAnalysesButNothingAfterTheBarrier() {
        RecordingAnalyze analyze = new RecordingAnalyze();
        RecordingOut out = new RecordingOut(null);
        MainExecutor exec = executor(3, analyze, out, ResumeMode.STATE);

        exec.prepare();

        assertEquals(PipelineStatus.PREPARED, exec.getState().getStatus());
        assertEquals(3, analyze.seen.size(), "analyses run for every listed item");
        assertTrue(out.written.isEmpty(), "nothing after the barrier runs while preparing");
        assertTrue(exec.getState().getWorkItems().stream().allMatch(w -> w.getStatus() == ItemStatus.PENDING));
        assertEquals(ResumeSource.NONE, exec.getState().getResumeProposal().source());
        assertFalse(Files.exists(store().getPath()), "preparing never writes the state file");
    }

    @Test
    public void executeCopiesOnlyAfterTheCursorThenAdvancesIt() {
        store().writeCursor(day(2));
        RecordingAnalyze analyze = new RecordingAnalyze();
        RecordingOut out = new RecordingOut(null);
        MainExecutor exec = executor(4, analyze, out, ResumeMode.STATE);

        exec.run();

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus(), "skipped items are not failures");
        assertEquals(Set.of("IMG_03.JPG", "IMG_04.JPG"), out.written);
        assertEquals(2, exec.getState().getWorkItems().stream().filter(w -> w.getStatus() == ItemStatus.SKIPPED).count());
        assertEquals(day(4), store().readCursor().orElseThrow());
    }

    @Test
    public void failureInTheMiddleStopsTheCursorBeforeIt() {
        RecordingOut out = new RecordingOut("IMG_02.JPG");
        MainExecutor exec = executor(3, new RecordingAnalyze(), out, ResumeMode.STATE);

        exec.run();

        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus());
        assertEquals(day(1), store().readCursor().orElseThrow());
    }

    @Test
    public void manualOverrideSelectsFromTheChosenItem() {
        store().writeCursor(day(3));
        RecordingOut out = new RecordingOut(null);
        MainExecutor exec = executor(3, new RecordingAnalyze(), out, ResumeMode.STATE);

        exec.prepare();
        assertTrue(exec.getOrderedItems().stream().allMatch(w -> w.getStatus() == ItemStatus.SKIPPED));
        exec.execute(ResumePoint.from(day(2)));

        assertEquals(Set.of("IMG_02.JPG", "IMG_03.JPG"), out.written);
        assertEquals(day(3), store().readCursor().orElseThrow(), "the cursor never moves back");
    }

    @Test
    public void modeNoneNeverWritesAStateFile() {
        RecordingOut out = new RecordingOut(null);
        MainExecutor exec = executor(2, new RecordingAnalyze(), out, ResumeMode.NONE);

        exec.prepare();
        exec.execute(null);

        assertEquals(2, out.written.size());
        assertFalse(Files.exists(store().getPath()));
    }

    @Test
    public void invalidStateFileFailsThePreparation() throws IOException {
        Files.writeString(store().getPath(), "not json");
        MainExecutor exec = executor(2, new RecordingAnalyze(), new RecordingOut(null), ResumeMode.STATE);

        assertThrows(RuntimeException.class, exec::prepare);
        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus());
        assertThrows(IllegalStateException.class, () -> exec.execute(null));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -o -q -pl copybot-engine test -Dtest=MainExecutorResumeTest`
Expected: FAIL (compilation).

- [ ] **Step 3: Implement**

`PipelineStatus.java` : ajouter `PREPARED` après `LISTED` (javadoc : `/** listed and analysed, resume resolved: waiting for execute() */`).

`PipelineState.java` : ajouter

```java
    // written by the pipeline thread, read by watchers/UI on other threads
    private volatile ResumeProposal resumeProposal;
    private volatile Throwable failure;

    public ResumeProposal getResumeProposal() {
        return resumeProposal;
    }

    public void setResumeProposal(ResumeProposal resumeProposal) {
        this.resumeProposal = resumeProposal;
    }

    /** A pipeline-level failure that is not tied to an item (e.g. the state file could not be written). */
    public Throwable getFailure() {
        return failure;
    }

    public void setFailure(Throwable failure) {
        this.failure = failure;
    }
```

(import `com.copybot.engine.resume.ResumeProposal`).

`MainExecutor.java` — modifications, en conservant tout le reste à l'identique (javadoc de classe, `submitTask`, `completeTask`, `awaitCompletion`, `runListing`, `shutdownTasks`, `registerStepCapacities`, `resolveFinalStatus`, notifier, `resolveOtherSteps`, `doResolveStep`) :

1. Nouveaux imports : `com.copybot.engine.resume.ResumeContext`, `ResumeMode`, `ResumePoint`, `ResumeProposal`, `ResumeResolver`, `ResumeSource`.

2. Nouveaux champs :

```java
    /** null: single-phase run without barrier nor resume (historical behaviour of run()). */
    private final ResumeContext resume;

    /** Number of item steps before the preparation barrier (the analyse steps). */
    private int barrierIndex;

    /** Exclusive end of the steps run by the items the listings emit: the barrier while preparing, all steps otherwise. */
    private volatile int phaseEnd;

    private ResumeResolver resolver;
    private ResumeProposal proposal;
    private List<WorkItemExecution> orderedItems = List.of();
```

3. Constructeurs :

```java
    public MainExecutor(PipelineConfig pipelineConfig, Consumer<PipelineState> watcher, ResourceRegistry registry,
                        ResumeContext resume) {
        this.pipelineConfig = pipelineConfig;
        this.watcher = watcher;
        this.registry = registry;
        this.resume = resume;
        this.state = new PipelineState(List.of());
        this.state.setRegistry(registry);
    }

    // visible for tests: runs with pre-resolved steps, bypassing PluginEngine, without barrier
    MainExecutor(List<PipelineStep<IInAction>> inSteps, List<PipelineStep<?>> itemSteps,
                 boolean startProcessingWhileListing, Consumer<PipelineState> watcher, ResourceRegistry registry) {
        this(inSteps, itemSteps, itemSteps.size(), startProcessingWhileListing, watcher, registry, null);
    }

    // visible for tests: pre-resolved steps, the first barrierIndex item steps run before the barrier
    MainExecutor(List<PipelineStep<IInAction>> inSteps, List<PipelineStep<?>> itemSteps, int barrierIndex,
                 boolean startProcessingWhileListing, Consumer<PipelineState> watcher, ResourceRegistry registry,
                 ResumeContext resume) {
        this.pipelineConfig = null;
        this.watcher = watcher;
        this.registry = registry;
        this.resume = resume;
        this.inSteps = inSteps;
        this.itemSteps = itemSteps;
        this.barrierIndex = barrierIndex;
        this.startProcessingWhileListing = startProcessingWhileListing;
        this.state = new PipelineState(List.of());
        this.state.setRegistry(registry);
    }
```

(supprimer l'ancien constructeur public à 3 arguments et l'ancien constructeur de test à 5 arguments, remplacés par ceux-ci.)

4. Remplacer `run()` par :

```java
    /**
     * Without resume: one phase, every step, as before. With resume: {@link #prepare()} then, when the
     * preparation succeeded, {@link #execute(ResumePoint)} with the proposed resume point.
     */
    @Override
    public void run() {
        if (resume == null) {
            runSinglePhase();
            return;
        }
        prepare();
        if (state.getStatus() == PipelineStatus.PREPARED) {
            execute(null);
        }
    }

    private void runSinglePhase() {
        state.setStatus(PipelineStatus.RUNNING);
        state.setListingInProgress(true);
        startPhase();
        try {
            // Step resolution is INSIDE the try: a missing plugin/action must be reported as a
            // failed run (status ERROR + watcher notified), not as an exception out of a state-less run.
            resolveStepsIfNeeded();
            phaseEnd = itemSteps.size();
            runListings();
            state.setStatus(resolveFinalStatus());
        } catch (InterruptedException e) {
            // Cancellation: unblock every item still parked in acquireAll/IO and let it release its
            // permits, THEN restore the interrupt flag (awaitTermination would return immediately
            // with the flag set, leaving writers to be killed mid-stream by JVM exit).
            state.setStatus(PipelineStatus.ERROR);
            shutdownTasks();
            Thread.currentThread().interrupt();
        } catch (RuntimeException | Error e) {
            state.setStatus(PipelineStatus.ERROR);
            throw e;
        } finally {
            state.setListingInProgress(false);
            endPhase();
        }
    }

    /**
     * Lists and analyses every item, then resolves the resume point: items end PENDING (selected),
     * SKIPPED (with a reason) or ERROR, and the pipeline PREPARED. Nothing after the analyses runs.
     * A listing failure leaves the pipeline in ERROR: a partial listing would give a wrong resume point.
     *
     * @throws RuntimeException when the resume point cannot be resolved (e.g. invalid state file),
     *                          after the status has been set to ERROR
     */
    public void prepare() {
        if (resume == null) {
            throw new IllegalStateException("prepare() requires a resume context");
        }
        state.setStatus(PipelineStatus.RUNNING);
        state.setListingInProgress(true);
        startPhase();
        try {
            resolveStepsIfNeeded();
            phaseEnd = barrierIndex;
            runListings();
            if (listingFailed.get()) {
                state.setStatus(PipelineStatus.ERROR);
                return;
            }
            orderedItems = ResumeResolver.order(state.getWorkItems());
            resolver = new ResumeResolver(resume.mode(), resume.store(), findOutAction());
            proposal = resolver.propose(orderedItems);
            state.setResumeProposal(proposal);
            resolver.apply(proposal.point(), proposal.source(), orderedItems);
            state.setStatus(PipelineStatus.PREPARED);
        } catch (InterruptedException e) {
            state.setStatus(PipelineStatus.ERROR);
            shutdownTasks();
            Thread.currentThread().interrupt();
        } catch (RuntimeException | Error e) {
            state.setStatus(PipelineStatus.ERROR);
            state.setFailure(e);
            throw e;
        } finally {
            state.setListingInProgress(false);
            endPhase();
        }
    }

    /**
     * Runs the steps after the barrier for the selected items, then advances the resume cursor
     * (only when the run completed normally and the mode is not NONE).
     *
     * @param override resume point chosen by the user, null for the proposed one
     */
    public void execute(ResumePoint override) {
        if (state.getStatus() != PipelineStatus.PREPARED) {
            throw new IllegalStateException("Pipeline is not prepared: " + state.getStatus());
        }
        ResumePoint point = override != null ? override : proposal.point();
        ResumeSource source = override != null ? ResumeSource.MANUAL : proposal.source();
        if (override != null) {
            resolver.apply(point, source, orderedItems);
        }
        state.setStatus(PipelineStatus.RUNNING);
        startPhase();
        boolean completed = false;
        try {
            for (WorkItemExecution exec : orderedItems) {
                if (exec.getStatus() == ItemStatus.PENDING) {
                    submitItem(exec, barrierIndex, itemSteps.size());
                }
            }
            awaitCompletion();
            state.setStatus(resolveFinalStatus());
            completed = true;
        } catch (InterruptedException e) {
            state.setStatus(PipelineStatus.ERROR);
            shutdownTasks();
            Thread.currentThread().interrupt();
        } catch (RuntimeException | Error e) {
            state.setStatus(PipelineStatus.ERROR);
            throw e;
        } finally {
            if (completed) {
                saveCursor(point, source);
            }
            endPhase();
        }
    }

    private void saveCursor(ResumePoint point, ResumeSource source) {
        if (resume.mode() == ResumeMode.NONE) {
            return;
        }
        try {
            resolver.nextCursor(orderedItems, point, source).ifPresent(resume.store()::writeCursor);
        } catch (RuntimeException e) {
            // the files are copied, but the next resume would not know it: this run is not a success
            state.setFailure(e);
            state.setStatus(PipelineStatus.ERROR);
        }
    }

    private void resolveStepsIfNeeded() {
        if (pipelineConfig != null && inSteps == null) {
            inSteps = doResolveStep(pipelineConfig.inSteps(), IInAction.class);
            itemSteps = resolveOtherSteps(pipelineConfig);
            barrierIndex = pipelineConfig.analyseSteps() == null ? 0 : pipelineConfig.analyseSteps().size();
            startProcessingWhileListing = Boolean.TRUE.equals(pipelineConfig.startProcessingWhileListing());
        }
        registerStepCapacities();
    }

    /** Submits every listing and waits for them AND every item they emitted (including forked ones). */
    private void runListings() throws InterruptedException {
        listingGate = new CountDownLatch(inSteps.size());
        for (PipelineStep<IInAction> inStep : inSteps) {
            submitTask(() -> runListing(inStep));
        }
        awaitCompletion();
    }

    private void startPhase() {
        taskExecutor = Executors.newVirtualThreadPerTaskExecutor();
        startNotifier();
        notifyWatcher();
    }

    private void endPhase() {
        shutdownTasks();
        stopNotifier(); // includes the final, guaranteed notification
    }

    private IOutAction findOutAction() {
        if (!itemSteps.isEmpty() && itemSteps.getLast().getAction() instanceof IOutAction out) {
            return out;
        }
        return null;
    }

    ResumeProposal getProposal() {
        return proposal;
    }

    List<WorkItemExecution> getOrderedItems() {
        return orderedItems;
    }
```

5. `emitItem` : remplacer `submitItem(exec, 0);` par `submitItem(exec, 0, phaseEnd);`.

6. `submitItem` / `runItem` deviennent :

```java
    private void submitItem(WorkItemExecution exec, int fromStep, int toStep) {
        submitTask(() -> runItem(exec, fromStep, toStep));
    }

    /** Runs the steps [fromStep, toStep); an item stopped at the barrier goes back to PENDING. */
    private void runItem(WorkItemExecution exec, int fromStep, int toStep) {
        try {
            if (!startProcessingWhileListing) {
                listingGate.await();
            }
            boolean filtered = false;
            for (int i = fromStep; i < toStep; i++) {
                PipelineStep<?> step = itemSteps.get(i);
                Set<String> footprint = FootprintResolver.resolve(step.getAction(), exec.getWorkItem(), step.getConfig(), i);
                exec.setWaitingResources(i, footprint);
                notifyWatcher();
                registry.acquireAll(footprint);
                exec.setRunning(i);
                notifyWatcher();
                boolean continueItem;
                try {
                    continueItem = runStep(exec, step, i);
                } finally {
                    registry.releaseAll(footprint);
                }
                if (!continueItem) {
                    filtered = true;
                    break;
                }
            }
            if (filtered || toStep == itemSteps.size()) {
                exec.setDone();
            } else {
                exec.setReady();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            exec.setError(t);
        } finally {
            notifyWatcher();
        }
    }
```

7. Dans `runStep`, remplacer `submitItem(forked, stepIndex + 1);` par `submitItem(forked, stepIndex + 1, itemSteps.size());`.

8. Supprimer l'ancien corps de `run()` (remplacé par `runSinglePhase`) ; la ligne `listingGate = new CountDownLatch(...)` et la boucle de soumission des listings vivent désormais dans `runListings()`.

`CopybotEngine.java` : dans `run(...)`, remplacer `new MainExecutor(pipelineConfig, watcher, registry)` par `new MainExecutor(pipelineConfig, watcher, registry, null)` (le branchement réel de la reprise est fait en Task 7).

- [ ] **Step 4: Run tests**

Run: `mvn -o -q -pl copybot-engine test`
Expected: PASS — `MainExecutorResumeTest` et tous les tests existants (`MainExecutorTest`, `MainTest`…) inchangés.

- [ ] **Step 5: Commit**

```bash
git add copybot-engine/src/main/java/com/copybot/engine/MainExecutor.java copybot-engine/src/main/java/com/copybot/engine/pipeline/PipelineStatus.java copybot-engine/src/main/java/com/copybot/engine/pipeline/PipelineState.java copybot-engine/src/main/java/com/copybot/engine/CopybotEngine.java copybot-engine/src/test/java/com/copybot/engine/MainExecutorResumeTest.java
git commit -m "Split pipeline runs into prepare and execute around a resume barrier"
```

---

### Task 7: API moteur, CLI et bout en bout

**Files:**
- Create: `copybot-engine/src/main/java/com/copybot/engine/Plan.java`
- Modify: `copybot-engine/src/main/java/com/copybot/engine/CopybotEngine.java`
- Modify: `copybot-engine/src/main/java/com/copybot/Copybot.java`
- Modify: les deux `engineBundle*.properties`
- Test: `copybot-engine/src/test/java/com/copybot/engine/ResumeEndToEndTest.java` (créer)

**Interfaces:**
- Consumes: Task 6 (`MainExecutor(PipelineConfig, watcher, registry, ResumeContext)`, `prepare`, `execute`, `getProposal`, `getOrderedItems`), Task 2/3 (`PipelineConfig.resumeMode()`, `ResumeStateStore.forPipeline`, `ResumePoint`, `ItemKey`).
- Produces:
  - `public final class Plan` : `void preview(ResumePoint override)`, `PipelineState getState()`, `ResumeProposal getProposal()`, `List<WorkItemExecution> getOrderedItems()`, `ResumePoint fromFile(String fileName)` (`CopybotException` clé `resume.from-file.not-found` si absent), `static ResumePoint fromDate(LocalDate date)`.
  - `CopybotEngine` : `static Plan prepare(Path pipelinePath, Consumer<PipelineState> watcher)` (bloquant, dans le thread appelant), `static void execute(Plan plan, ResumePoint override)` (asynchrone, joint par `waitForCompletion()`), `run(Path, Consumer)` inchangé en signature (reprise branchée si le pipeline a un bloc `resume`), `init` idempotent pour le chargement des plugins.
  - CLI : `--from-file <nom>`, `--from-date <AAAA-MM-JJ>`, `--all` (exclusives), `--dry-run` effectif.

- [ ] **Step 1: Write the failing test**

```java
package com.copybot.engine;

import com.copybot.Copybot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CLI runs of a real file.read -> file.write pipeline with resume. Dates come from the files'
 * modification time, set explicitly so that the ordering is deterministic.
 */
public class ResumeEndToEndTest {

    private static final String CONFIG = "-c=./src/test/resources/com/copybot/engine/config.json";

    @TempDir
    Path tempDir;

    Path card;
    Path nas;

    @BeforeEach
    public void createCard() throws IOException {
        card = Files.createDirectories(tempDir.resolve("card"));
        nas = tempDir.resolve("nas");
        photo("IMG_01.JPG", "2026-09-01T10:00:00Z");
        photo("IMG_02.JPG", "2026-09-02T10:00:00Z");
    }

    private void photo(String name, String date) throws IOException {
        Path file = Files.writeString(card.resolve(name), name);
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse(date)));
    }

    private static String json(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }

    private Path pipeline(String mode, String outPattern) throws IOException {
        String resume = mode == null ? "" : ",\"resume\":{\"mode\":\"" + mode + "\"}";
        return Files.writeString(tempDir.resolve("sd.json"), """
                {
                  "inSteps": [ { "action": "file.read", "actionConfig": { "path": "%s" } } ],
                  "outStep": { "action": "file.write", "actionConfig": { "outPattern": "%s", "overwrite": false } }%s
                }
                """.formatted(json(card), json(nas) + "/" + outPattern, resume));
    }

    private static int cli(Path pipeline, String... extra) {
        String[] args = new String[extra.length + 3];
        args[0] = "-p=" + pipeline.toAbsolutePath();
        args[1] = CONFIG;
        args[2] = "--debug";
        System.arraycopy(extra, 0, args, 3, extra.length);
        return Copybot.doMain(args);
    }

    @Test
    public void stateModeImportsOnlyTheDeltaAndNeverReimportsDeletedFiles() throws IOException {
        Path pipeline = pipeline("state", "{name}");

        assertEquals(0, cli(pipeline));
        assertTrue(Files.exists(nas.resolve("IMG_01.JPG")));
        assertTrue(Files.exists(nas.resolve("IMG_02.JPG")));
        assertTrue(Files.exists(tempDir.resolve("sd.state.json")), "the cursor is persisted next to the pipeline");

        Files.delete(nas.resolve("IMG_01.JPG")); // sorted out on the NAS afterwards
        photo("IMG_03.JPG", "2026-09-03T10:00:00Z");
        assertEquals(0, cli(pipeline));

        assertFalse(Files.exists(nas.resolve("IMG_01.JPG")), "a file deleted from the NAS must not come back");
        assertTrue(Files.exists(nas.resolve("IMG_03.JPG")), "the new photo is imported");
    }

    @Test
    public void dryRunCopiesAndWritesNothing() throws IOException {
        Path pipeline = pipeline("state", "{name}");

        assertEquals(0, cli(pipeline, "--dry-run"));

        assertFalse(Files.exists(nas));
        assertFalse(Files.exists(tempDir.resolve("sd.state.json")));
    }

    @Test
    public void fromFileForcesAResumePoint() throws IOException {
        Path pipeline = pipeline("state", "{name}");
        assertEquals(0, cli(pipeline));
        Files.delete(nas.resolve("IMG_02.JPG"));

        assertEquals(0, cli(pipeline, "--from-file=IMG_02.JPG"));

        assertTrue(Files.exists(nas.resolve("IMG_02.JPG")), "the manual resume point re-imports from that file");
        assertTrue(Files.exists(nas.resolve("IMG_01.JPG")), "IMG_01 was never deleted and stays");
    }

    @Test
    public void allReimportsEverything() throws IOException {
        Path pipeline = pipeline("state", "{name}");
        assertEquals(0, cli(pipeline));
        Files.delete(nas.resolve("IMG_01.JPG"));

        // IMG_02.JPG still exists and overwrite=false: that item fails, which does not stop IMG_01
        assertEquals(0, cli(pipeline, "--all"));

        assertTrue(Files.exists(nas.resolve("IMG_01.JPG")));
    }

    @Test
    public void destinationModeResumesAfterTheLastExistingDayDirectory() throws IOException {
        Path pipeline = pipeline("destination", "{lastModified.Y}-{lastModified.m}-{lastModified.D}/{name}");
        Files.createDirectories(nas.resolve("2026-09-01")); // day 1 already imported, then emptied

        assertEquals(0, cli(pipeline));

        assertFalse(Files.exists(nas.resolve("2026-09-01").resolve("IMG_01.JPG")), "day 1 counts as imported");
        assertTrue(Files.exists(nas.resolve("2026-09-02").resolve("IMG_02.JPG")));
    }

    @Test
    public void pipelineWithoutResumeBlockKeepsCopyingEverythingWithoutStateFile() throws IOException {
        Path pipeline = pipeline(null, "{name}");

        assertEquals(0, cli(pipeline));

        assertTrue(Files.exists(nas.resolve("IMG_01.JPG")));
        assertFalse(Files.exists(tempDir.resolve("sd.state.json")));
    }
}
```

Les dates des répertoires (`{lastModified.D}`…) sont calculées dans le fuseau système par `WorkItemMetadata.setTime` : 10:00 UTC reste le même jour sur tous les fuseaux de UTC-9 à UTC+13, ce qui rend le test stable.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -o -q -pl copybot-engine test -Dtest=ResumeEndToEndTest`
Expected: FAIL (ex. `--from-file` inconnue ; le 2e run en mode `state` recopie `IMG_01.JPG`, ou échec de `PluginEngine.load` au 2e `init`).

- [ ] **Step 3: Implement**

`Plan.java` :

```java
package com.copybot.engine;

import com.copybot.engine.pipeline.PipelineState;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.engine.resume.ItemKey;
import com.copybot.engine.resume.ResumePoint;
import com.copybot.engine.resume.ResumeProposal;
import com.copybot.exception.CopybotException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/** A prepared pipeline: what would be imported, and from where, before anything is written. */
public final class Plan {

    private final MainExecutor executor;

    Plan(MainExecutor executor) {
        this.executor = executor;
    }

    MainExecutor getExecutor() {
        return executor;
    }

    public PipelineState getState() {
        return executor.getState();
    }

    public ResumeProposal getProposal() {
        return executor.getProposal();
    }

    /** Listed items in resume order (items without date last). */
    public List<WorkItemExecution> getOrderedItems() {
        return executor.getOrderedItems();
    }

    /** Resume from this listed file, included. */
    public ResumePoint fromFile(String fileName) {
        return getOrderedItems().stream()
                .filter(item -> fileName.equals(item.getWorkItem().getNameDisplay()))
                .map(item -> ItemKey.of(item.getWorkItem()))
                .flatMap(java.util.Optional::stream)
                .findFirst()
                .map(ResumePoint::from)
                .orElseThrow(() -> CopybotException.ofResource("resume.from-file.not-found", fileName));
    }

    /** Resume from the start of this day (system time zone), included. */
    public static ResumePoint fromDate(LocalDate date) {
        return ResumePoint.from(new ItemKey(date.atStartOfDay(ZoneId.systemDefault()).toInstant(), ""));
    }
}
```

`CopybotEngine.java` :

- `init` : charger les plugins **une seule fois** par JVM (`PluginEngine.load` n'est pas ré-entrant : il transforme ses listes en listes non modifiables). Ajouter un champ `private static boolean pluginsLoaded;` et entourer l'appel : `if (!pluginsLoaded) { PluginEngine.load(pluginPath, devPluginPaths); pluginsLoaded = true; }`. Ne créer l'`executor` que s'il est `null` ou `isShutdown()`.
- Extraire la lecture du pipeline :

```java
    private static PipelineConfig readPipeline(Path pipelinePath) {
        try (var reader = Files.newBufferedReader(pipelinePath)) {
            return GsonUtil.getGson().fromJson(reader, PipelineConfig.class);
        } catch (IOException | JsonSyntaxException e) {
            throw CopybotException.ofResource(e, "pipeline.not-json", pipelinePath);
        }
    }

    private static ResumeContext resumeContext(Path pipelinePath, PipelineConfig pipelineConfig) {
        return new ResumeContext(pipelineConfig.resumeMode(), ResumeStateStore.forPipeline(pipelinePath));
    }
```

- `run(pipelinePath, watcher)` : `PipelineConfig pipelineConfig = readPipeline(pipelinePath);` ; `ResumeContext resume = pipelineConfig.resumeMode() == ResumeMode.NONE ? null : resumeContext(pipelinePath, pipelineConfig);` ; `new MainExecutor(pipelineConfig, watcher, registry, resume)` ; soumission inchangée via une méthode commune `submit(Runnable)` (le bloc `synchronized` existant, « Engine already running » compris).
- Nouvelles méthodes :

```java
    /**
     * Lists and analyses the pipeline input and resolves the resume point, without writing anything.
     * Blocking, runs in the calling thread.
     */
    public static Plan prepare(Path pipelinePath, Consumer<PipelineState> watcher) {
        PipelineConfig pipelineConfig = readPipeline(pipelinePath);
        ResourceRegistry registry = new ResourceRegistry(ResourceSettings.from(config));
        MainExecutor mainExecutor = new MainExecutor(pipelineConfig, watcher, registry, resumeContext(pipelinePath, pipelineConfig));
        mainExecutor.prepare();
        return new Plan(mainExecutor);
    }

    /**
     * Executes a prepared plan on a background thread; join it with {@link #waitForCompletion()}.
     *
     * @param override resume point chosen by the user, null for the proposed one
     */
    public static void execute(Plan plan, ResumePoint override) {
        submit(() -> plan.getExecutor().execute(override));
    }
```

`submit(Runnable)` reprend exactement le bloc `synchronized (CopybotEngine.class) { if (mainTask != null) throw ...; mainTask = executor.submit(() -> { try { task.run(); } finally { synchronized ... mainTask = null; } }); }` existant.

`Copybot.java` :

```java
    @CommandLine.ArgGroup(exclusive = true)
    private ResumeOverride resumeOverride;

    static class ResumeOverride {
        @CommandLine.Option(names = "--from-file", description = "Resume from this file (included)")
        String fromFile;

        @CommandLine.Option(names = "--from-date", description = "Resume from this day (included), yyyy-MM-dd")
        LocalDate fromDate;

        @CommandLine.Option(names = "--all", description = "Ignore the resume point: process every file")
        boolean all;
    }
```

(Si picocli ne convertit pas `LocalDate` nativement dans ce contexte JPMS, déclarer `String fromDate` et parser avec `LocalDate.parse`.) `doRun()` devient :

```java
    public void doRun() {
        CopybotEngine.init(Optional.ofNullable(configPath));

        if (!pipelinePath.toFile().canRead()) {
            throw CopybotException.ofResource("pipeline.not-found", pipelinePath);
        }

        if (isDryRun || resumeOverride != null) {
            runWithPlan();
        } else {
            CopybotEngine.run(pipelinePath, pipelineState -> System.out.println(pipelineState));
            awaitEngine();
        }
    }

    private void runWithPlan() {
        Plan plan = CopybotEngine.prepare(pipelinePath, null);
        if (plan.getState().getStatus() != PipelineStatus.PREPARED) {
            throw CopybotException.ofResource("pipeline.prepare-failed");
        }
        ResumePoint override = resolveOverride(plan);
        if (isDryRun) {
            plan.preview(override);
            PlanPrinter.print(plan, override, System.out);
            return;
        }
        CopybotEngine.execute(plan, override);
        awaitEngine();
        if (plan.getState().getFailure() != null) {
            System.err.println(plan.getState().getFailure().getMessage());
        }
    }

    private ResumePoint resolveOverride(Plan plan) {
        if (resumeOverride == null) {
            return null;
        }
        if (resumeOverride.all) {
            return ResumePoint.all();
        }
        if (resumeOverride.fromFile != null) {
            return plan.fromFile(resumeOverride.fromFile);
        }
        return Plan.fromDate(resumeOverride.fromDate);
    }
```

`awaitEngine()` = le bloc actuel `try { CopybotEngine.waitForCompletion(); } catch (InterruptedException ...) {...} catch (ExecutionException ...) {...} System.out.println("Done !");` déplacé tel quel.

Créer `copybot-engine/src/main/java/com/copybot/PlanPrinter.java` :

```java
package com.copybot;

import com.copybot.engine.Plan;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.engine.resume.ResumePoint;
import com.copybot.engine.resume.ResumeProposal;

import java.io.PrintStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Dry-run output: the resume point, its origin, the warnings, then one line per item. */
final class PlanPrinter {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(ZoneId.systemDefault());

    private PlanPrinter() {
    }

    static void print(Plan plan, ResumePoint override, PrintStream out) {
        ResumeProposal proposal = plan.getProposal();
        ResumePoint point = override != null ? override : proposal.point();
        out.println("Resume point: " + describe(point) + " [" + (override != null ? "MANUAL" : proposal.source()) + "]");
        proposal.warnings().forEach(w -> out.println("Warning: " + w));
        for (WorkItemExecution item : plan.getOrderedItems()) {
            String name = item.getWorkItem().getNameDisplay();
            String line = switch (item.getStatus()) {
                case ERROR -> "ERROR " + name + "  " + item.getError().getMessage();
                case SKIPPED -> "SKIP  " + name + "  " + item.getSkipReason();
                default -> "COPY  " + name;
            };
            out.println(line);
        }
    }

    private static String describe(ResumePoint point) {
        return switch (point.kind()) {
            case ALL -> "everything";
            case AFTER -> "after " + point.key().name() + " (" + DATE.format(point.key().date()) + ")";
            case FROM -> "from " + point.key().name() + " (" + DATE.format(point.key().date()) + ")";
        };
    }
}
```

Pour que le dry-run avec surcharge manuelle affiche des statuts cohérents, ajouter à `Plan` :

```java
    /** Re-applies a manual resume point to the item statuses, without executing anything (dry-run display). */
    public void preview(ResumePoint override) {
        executor.applyOverride(override);
    }
```

et à `MainExecutor` (package-private), en l'appelant dans `execute` à la place du bloc `if (override != null) { resolver.apply(point, source, orderedItems); }` :

```java
    void applyOverride(ResumePoint override) {
        if (override != null) {
            resolver.apply(override, ResumeSource.MANUAL, orderedItems);
        }
    }
```

Ajouter à `engineBundle.properties` :

```properties
resume.from-file.not-found=No listed file named "{0}"
pipeline.prepare-failed=The pipeline preparation failed (see the errors above)
```

et à `engineBundle_fr.properties` :

```properties
resume.from-file.not-found=Aucun fichier listé ne s''appelle "{0}"
pipeline.prepare-failed=La préparation du pipeline a échoué (voir les erreurs ci-dessus)
```

- [ ] **Step 4: Run tests**

Run: `mvn -o -q -pl copybot-engine test`
Expected: PASS (dont `MainTest` inchangé).

Vérifier aussi que l'UI compile toujours : `mvn -o -q -pl copybot-engine,copybot-ui compile`.

- [ ] **Step 5: Commit**

```bash
git add copybot-engine/src/main/java/com/copybot/engine/Plan.java copybot-engine/src/main/java/com/copybot/engine/CopybotEngine.java copybot-engine/src/main/java/com/copybot/engine/MainExecutor.java copybot-engine/src/main/java/com/copybot/Copybot.java copybot-engine/src/main/java/com/copybot/PlanPrinter.java copybot-engine/src/main/resources/com/copybot/engine/i18n copybot-engine/src/test/java/com/copybot/engine/ResumeEndToEndTest.java
git commit -m "Expose prepare/execute in the engine and resume options in the CLI"
```

---

### Task 8: Date de prise de vue depuis l'EXIF

**Files:**
- Modify: `copybot-plugin/copybot-plugin-metadata-extractor/src/main/java/com/copybot/plugin/metadataextractor/actions/ExtractMetadata.java`
- Modify: `copybot-plugin/copybot-plugin-metadata-extractor/pom.xml` (dépendances de test JUnit)
- Test: `copybot-plugin/copybot-plugin-metadata-extractor/src/test/java/com/copybot/plugin/metadataextractor/actions/ExtractMetadataTest.java` (créer)

**Interfaces:**
- Consumes: `WorkItemMetadata.CAPTURE_DATE`, `setTime` (Task 1).
- Produces: `static Optional<Instant> ExtractMetadata.captureDate(Metadata metadata, TimeZone localZone)` (package-private) ; `doAnalyze` pose `captureDate` quand il existe.

- [ ] **Step 1: Write the failing test**

Ajouter au `pom.xml` du plugin :

```xml
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter-api</artifactId>
            <version>${junit.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter-engine</artifactId>
            <version>${junit.version}</version>
            <scope>test</scope>
        </dependency>
```

```java
package com.copybot.plugin.metadataextractor.actions;

import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.mov.QuickTimeDirectory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.*;

public class ExtractMetadataTest {

    private static final TimeZone PARIS = TimeZone.getTimeZone("Europe/Paris");

    @Test
    public void exifOriginalDateIsReadAsLocalTime() {
        Metadata metadata = new Metadata();
        ExifSubIFDDirectory exif = new ExifSubIFDDirectory();
        exif.setString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL, "2026:09:28 17:42:10");
        metadata.addDirectory(exif);

        assertEquals(Optional.of(Instant.parse("2026-09-28T15:42:10Z")), ExtractMetadata.captureDate(metadata, PARIS));
    }

    @Test
    public void quickTimeCreationDateIsUsedForVideos() {
        Metadata metadata = new Metadata();
        QuickTimeDirectory qt = new QuickTimeDirectory();
        qt.setDate(QuickTimeDirectory.TAG_CREATION_TIME, Date.from(Instant.parse("2026-09-28T15:00:00Z")));
        metadata.addDirectory(qt);

        assertEquals(Optional.of(Instant.parse("2026-09-28T15:00:00Z")), ExtractMetadata.captureDate(metadata, PARIS));
    }

    @Test
    public void noUsableDateGivesNothing() {
        assertTrue(ExtractMetadata.captureDate(new Metadata(), PARIS).isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -o -q -pl copybot-engine,copybot-plugin/copybot-plugin-metadata-extractor install`
Expected: FAIL (compilation : `captureDate` inexistant).

- [ ] **Step 3: Implement**

Remplacer le contenu de `ExtractMetadata.java` :

```java
package com.copybot.plugin.metadataextractor.actions;

import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.AbstractAction;
import com.copybot.plugin.api.action.IAnalyzeAction;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.mov.QuickTimeDirectory;
import com.drew.metadata.mp4.Mp4Directory;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.TimeZone;

/** Sets the item capture date (metadata "captureDate") from the EXIF or video creation date. */
public class ExtractMetadata extends AbstractAction implements IAnalyzeAction {

    @Override
    public void doAnalyze(WorkItem item) {
        Metadata metadata;
        try (InputStream is = item.openInputStream()) {
            metadata = ImageMetadataReader.readMetadata(is);
        } catch (ImageProcessingException e) {
            return; // unsupported format: no capture date, resume falls back on the file date
        } catch (IOException e) {
            throw CopybotException.ofResource(e, "plugin.metadata-extractor.extract.error.io", item.getSourceLocationDisplay());
        }
        captureDate(metadata, TimeZone.getDefault())
                .ifPresent(date -> item.getMetadatas().setTime(WorkItemMetadata.CAPTURE_DATE, date));
    }

    /**
     * EXIF DateTimeOriginal is a local time without zone: it is read in {@code localZone}, as the OS
     * does for FAT file dates. QuickTime / MP4 creation dates are already UTC.
     */
    static Optional<Instant> captureDate(Metadata metadata, TimeZone localZone) {
        ExifSubIFDDirectory exif = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
        if (exif != null) {
            Date original = exif.getDateOriginal(localZone);
            if (original != null) {
                return Optional.of(original.toInstant());
            }
        }
        return videoDate(metadata.getFirstDirectoryOfType(QuickTimeDirectory.class), QuickTimeDirectory.TAG_CREATION_TIME)
                .or(() -> videoDate(metadata.getFirstDirectoryOfType(Mp4Directory.class), Mp4Directory.TAG_CREATION_TIME));
    }

    private static Optional<Instant> videoDate(Directory directory, int tag) {
        if (directory == null) {
            return Optional.empty();
        }
        Date date = directory.getDate(tag);
        return Optional.ofNullable(date).map(Date::toInstant);
    }
}
```

Si `QuickTimeDirectory`/`Mp4Directory` ou leurs constantes `TAG_CREATION_TIME` diffèrent dans metadata-extractor 2.20.0, vérifier dans le jar (`copybot-plugin/copybot-plugin-metadata-extractor/target/lib/metadata-extractor-2.20.0.jar`, `javap -cp <jar> com.drew.metadata.mov.QuickTimeDirectory`) et adapter les noms, sans changer le comportement.

- [ ] **Step 4: Run tests**

Run: `mvn -o -q -pl copybot-engine,copybot-plugin/copybot-plugin-metadata-extractor install`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add copybot-plugin/copybot-plugin-metadata-extractor/pom.xml copybot-plugin/copybot-plugin-metadata-extractor/src
git commit -m "Set the capture date from EXIF or video metadata"
```

---

## Vérification finale

- [ ] `mvn -o clean install` à la racine : BUILD SUCCESS (le `clean` évite l'échec connu de jpackage « Application destination directory … already exists »).
- [ ] `git status` : seuls les fichiers non suivis préexistants (`copybot-ui/*.ico`, `*.png`) restent.

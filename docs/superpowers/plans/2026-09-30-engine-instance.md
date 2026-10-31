# Moteur en instance, annulation / pause, codes de sortie — Plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remplacer l'API statique de `CopybotEngine` par une instance `AutoCloseable` (une opération à la fois), exposer l'annulation propre et la pause / reprise d'une exécution via un objet `Execution`, unifier le style des échecs de préparation, rendre le chargement des plugins idempotent et donner à la CLI des codes de sortie exploitables (0 / 1 / 2 / 130) avec un hook Ctrl+C.

**Architecture:** La pause est portée par `ResourceRegistry` (plus aucun permis accordé, listings gelés avant l'émission d'un item) ; l'annulation par `MainExecutor` (interruption du thread de la phase en cours, statut `CANCELLED`, curseur inchangé). `CopybotEngine` devient une instance qui possède sa config, son exécuteur et un verrou « une opération à la fois » ; `execute` / `run` rendent une `Execution` (await / cancel / pause / resume). `MainExecutor` ne relance plus jamais une exception de pipeline : l'échec est dans `PipelineState` (`ERROR` + `failure`). La CLI passe en `Callable<Integer>` et mappe le statut final sur le code de sortie.

**Tech Stack:** Java 25 (threads virtuels, JPMS), Maven multi-module, JUnit Jupiter `${junit.version}` (6.1.2), Gson, picocli 4.7.7, JavaFX (copybot-ui). Aucune nouvelle dépendance.

**Spec:** `docs/superpowers/specs/2026-09-30-engine-instance-design.md` (à lire avant chaque tâche).

## Global Constraints

- Java 25 ; code, noms et javadoc en **anglais** (convention du code existant) ; ce plan et la spec en français.
- **Ne pas modifier** les interfaces de plugin (`IAction`, `IInAction`, `IOutAction`, …) ni `copybot-plugin/*` / `copybot-plugin-demo/*`.
- **Une instance = un pipeline à la fois** : `prepare`, `execute` ou `run` pendant une opération active de la même instance ⇒ `IllegalStateException` ; `prepare` s'exécute dans le thread appelant et compte comme opération active pendant sa durée.
- L'API statique disparaît entièrement (`init`, `run`, `prepare`, `execute`, `waitForCompletion`, `destroy`) ; CLI, UI et tests passent à l'instance dans la **même** tâche (tâche 6).
- Erreurs **avant** qu'un plan existe (pipeline introuvable / JSON invalide / mode de reprise inconnu, y compris non chaîne comme `"mode": 1`) ⇒ `CopybotException`. Erreurs **pendant** la préparation ou l'exécution ⇒ jamais d'exception : statut `ERROR` + `PipelineState.getFailure()`.
- Nouveaux statuts `PipelineStatus.PAUSED` (pendant une pause, retour à `RUNNING` à la reprise) et `PipelineStatus.CANCELLED` (annulation **et** interruption du thread pipeline, qui donnait `ERROR`). Curseur de reprise jamais écrit par une exécution annulée. Délai de grâce : 5 s.
- Codes de sortie CLI : `0` SUCCESS ou `--dry-run` préparé sans erreur ; `1` exécution terminée en `ERROR` ; `2` erreur fatale (config / pipeline introuvable ou invalide, préparation en `ERROR`, `--from-file` inconnu, options invalides = code picocli) ; `130` `CANCELLED`. `--debug` imprime la stacktrace mais ne change pas le code.
- Modifications de tests existants **autorisées par la spec §9, et seulement celles-ci** : assertions `ERROR` après interruption ⇒ `CANCELLED` (tâche 2) ; `--all` avec un item en échec attend le code `1` (tâche 7) ; « chaque cause d'échec de préparation ⇒ `Plan` en `ERROR`, sans exception » (tâche 4, deux `assertThrows(RuntimeException.class, exec::prepare)`) ; passage à l'API d'instance (tâche 6).
- Messages utilisateur via `ResourcesEngine.getString` / `CopybotException.ofResource` / `CopybotLogger`, clés ajoutées à `engineBundle.properties` **et** `engineBundle_fr.properties`. Format `MessageFormat` : apostrophe doublée.
- **Règle byte-safe des bundles** : `engineBundle_fr.properties` est en **ISO-8859-1** avec fins de ligne **CRLF** (et `engineBundle.properties` en ASCII CRLF). Ne jamais les ouvrir avec Edit/Write (réencodage UTF-8 des `é` existants) : ajouter une ligne **uniquement** avec `printf '%s\r\n' '<ligne>' >> <fichier>` depuis le shell Bash (le `%s` empêche `printf` d'interpréter `\u`), caractères non ASCII écrits en échappement `\uXXXX` (ex. `déjà`). Contrôle : `file <fichier>` ⇒ `ISO-8859 text, with CRLF line terminators` (resp. `ASCII text, with CRLF line terminators`) et `git diff` ne montre que la ligne ajoutée.
- **Préserver les fins de ligne de chaque fichier (plusieurs fichiers sont CRLF).** CRLF : `Copybot.java`, `engine/CopybotEngine.java`, `engine/plugin/PluginEngine.java`, `engine/pipeline/PipelineState.java`, `engine/pipeline/PipelineStatus.java`, les deux `engineBundle*.properties`. LF : `MainExecutor.java`, `Plan.java`, `ResourceRegistry.java`, `ResourceSnapshot.java`, `WorkItemExecution.java`, tous les tests, les fichiers UI. Nouveaux fichiers : LF. Après toute réécriture complète d'un fichier CRLF : `unix2dos -q <fichier>`. Contrôle après chaque tâche : `git ls-files --eol <fichiers modifiés>` ⇒ `w/crlf` ou `w/lf` comme avant, jamais `w/mixed` (si `w/mixed` sur un fichier CRLF : `unix2dos -q <fichier>`).
- Les tests ne comparent jamais le texte exact d'un message traduit (locale FR ou EN) : présence, nom de fichier ou valeur citée.
- Les tests qui bloquent un thread le libèrent toujours (latch, `cancel`) et joignent avec un délai (`join(...)`), jamais d'attente infinie.
- Maven **hors ligne** depuis la racine du repo : tests du module `mvn -o -q -pl copybot-engine test` ; un test `mvn -o -q -pl copybot-engine test -Dtest=<Classe>` ; compilation UI `mvn -o -q -pl copybot-engine,copybot-ui compile` ; vérification finale `mvn -o clean install`. En `-q`, un succès n'affiche que les `WARNING` `sun.misc.Unsafe` de Maven et la sortie standard des tests CLI : « PASS » = aucune ligne `[ERROR]` et code retour 0.
- Branche `feature/wave2` (déjà active). Un commit par tâche, `git add` ciblé sur les fichiers de la tâche uniquement (les non suivis `.superpowers/` et `copybot-ui/Copybot.ico` ne sont **jamais** ajoutés). Commit : `git commit -m "<sujet>" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"`.

## Carte des fichiers

Chemins de production sous `copybot-engine/src/main/java/com/copybot/`, de test sous `copybot-engine/src/test/java/com/copybot/`.

| Fichier | Rôle | Tâches |
|---|---|---|
| `engine/resources/ResourceRegistry.java` | `pause` / `resume` / `isPaused` / `awaitNotPaused` | 1 |
| `engine/resources/ResourceSnapshot.java` | + composant `paused` | 1 |
| `engine/pipeline/PipelineStatus.java` | + `PAUSED`, `CANCELLED` | 2 |
| `engine/pipeline/PipelineState.java` | `compareAndSetStatus` ; `recordFailureIfAbsent` (échec atomique) | 3, 8 |
| `engine/MainExecutor.java` | interruption ⇒ `CANCELLED` ; `cancel` / `pause` / `resume` ; échecs capturés ; échec de listing atomique ; javadoc | 2, 3, 4, 8 |
| `engine/plugin/PluginEngine.java` | `load` thread-safe et idempotent | 5 |
| `engine/Execution.java` | **Créer** — exécution asynchrone (await / cancel / pause / resume) | 6 |
| `engine/CopybotEngine.java` | instance `AutoCloseable`, une opération à la fois, plus d'API statique | 5, 6 |
| `Copybot.java` | instance d'engine (6), `Callable<Integer>`, codes de sortie, hook Ctrl+C (7) | 6, 7 |
| `engine/pipeline/WorkItemExecution.java` | `setResumeKey` figée au premier appel | 8 |
| `resources/com/copybot/engine/i18n/engineBundle*.properties` | + `plugin.load.ignored` | 5 |
| `copybot-ui/src/main/java/com/copybot/ui/CopybotMainUi.java` | crée / ferme l'instance | 6 |
| `copybot-ui/src/main/java/com/copybot/ui/HelloController.java` | `ENGINE.run` | 6 |
| Tests : `engine/resources/ResourceRegistryTest.java` | pause / reprise / interruption | 1 |
| Tests : `engine/MainExecutorTest.java`, `engine/MainExecutorResumeTest.java` | `CANCELLED` après interruption (2), échecs sans exception (4), instance (6) | 2, 4, 6 |
| Tests : `engine/ControlFakes.java` | **Créer** — fakes partagés (listings datés / bloquants, sortie à porte) | 3 |
| Tests : `engine/MainExecutorControlTest.java` | **Créer** — cancel / pause au niveau exécuteur | 3 |
| Tests : `engine/pipeline/PipelineStateTest.java` | **Créer** — CAS de statut (3), échec atomique (8) | 3, 8 |
| Tests : `engine/plugin/PluginEngineTest.java` | **Créer** — idempotence, concurrence, avertissement | 5 |
| Tests : `engine/ExecutionTest.java`, `engine/CopybotEngineTest.java` | **Créer** — `Execution`, instance | 6 |
| Tests : `CopybotExitCodeTest.java` | **Créer** — un test par code de sortie, hook | 7 |
| Tests : `engine/ResumeEndToEndTest.java` | `--all` avec échec ⇒ 1 | 7 |
| Tests : `engine/pipeline/WorkItemExecutionTest.java` | clé figée | 8 |
| Tests : `engine/MainTest.java` | javadoc périmée (plus de `CopybotEngine.init`) | 6 |

Écart assumé à l'ordre suggéré (foundations → instance → CLI → UI) : l'adaptation minimale de l'UI et de la CLI est faite **dans la tâche 6**, parce qu'une méthode statique et une méthode d'instance de même signature (`run(Path, Consumer)`, `prepare(Path, Consumer)`) ne peuvent pas coexister en Java : l'API statique doit disparaître dans la tâche qui introduit l'instance, et chaque tâche doit laisser le build vert (UI comprise). La tâche 7 ne traite plus que les codes de sortie et le hook.

---

### Task 1: Pause dans le registre de ressources

**Files:**
- Modify: `copybot-engine/src/main/java/com/copybot/engine/resources/ResourceRegistry.java`
- Modify: `copybot-engine/src/main/java/com/copybot/engine/resources/ResourceSnapshot.java`
- Test: `copybot-engine/src/test/java/com/copybot/engine/resources/ResourceRegistryTest.java`

**Interfaces:**
- Consumes: —
- Produces: `public void ResourceRegistry.pause()`, `public void ResourceRegistry.resume()`, `public boolean ResourceRegistry.isPaused()`, `public void ResourceRegistry.awaitNotPaused() throws InterruptedException` (lève immédiatement si le thread est déjà interrompu) ; `acquireAll` n'accorde rien pendant la pause, y compris pour une empreinte vide ; `record ResourceSnapshot(String name, int capacity, int used, int waiting, boolean paused)`.

- [ ] **Step 1: Write the failing tests**

Dans `ResourceRegistryTest`, ajouter l'import `import java.util.concurrent.atomic.AtomicBoolean;` puis, à la fin de la classe (avant la dernière `}`), les tests :

```java
    // ---- pause ----

    @Test
    public void pauseHoldsEveryNewGrantUntilResume() throws Exception {
        ResourceRegistry reg = registry(Map.of("r", 2));
        reg.pause();
        assertTrue(reg.isPaused());

        Acquirer waiter = new Acquirer(reg, Set.of("r"));
        awaitTrue(() -> waiting(reg, "r") == 1);
        assertFalse(waiter.acquired.await(200, TimeUnit.MILLISECONDS),
                "nothing is granted while paused, even with free capacity");

        reg.resume();
        assertFalse(reg.isPaused());
        assertTrue(waiter.acquired.await(5, TimeUnit.SECONDS), "resume grants what became grantable");
        waiter.release();
    }

    @Test
    public void holdersKeepTheirPermitsAndReleaseThemDuringThePause() throws Exception {
        ResourceRegistry reg = registry(Map.of("r", 1));
        Acquirer holder = new Acquirer(reg, Set.of("r"));
        assertTrue(holder.acquired.await(5, TimeUnit.SECONDS));
        reg.pause();
        Acquirer next = new Acquirer(reg, Set.of("r"));
        awaitTrue(() -> waiting(reg, "r") == 1);

        holder.release(); // a step already running ends and releases normally
        assertEquals(0, used(reg, "r"));
        assertFalse(next.acquired.await(200, TimeUnit.MILLISECONDS),
                "the released permit is not handed over while paused");

        reg.resume();
        assertTrue(next.acquired.await(5, TimeUnit.SECONDS));
        next.release();
    }

    @Test
    public void interruptingAWaiterDuringThePauseLeavesNothingBehind() throws Exception {
        ResourceRegistry reg = registry(Map.of("r", 1));
        reg.pause();
        Acquirer waiter = new Acquirer(reg, Set.of("r"));
        awaitTrue(() -> waiting(reg, "r") == 1);

        waiter.thread.interrupt();
        waiter.thread.join(5000);

        assertFalse(waiter.thread.isAlive());
        assertEquals(1, waiter.acquired.getCount(), "an interrupted waiter is never granted");
        assertEquals(0, waiting(reg, "r"));
        reg.resume();
        assertEquals(0, used(reg, "r"), "resume must not grant a permit to the interrupted waiter");
    }

    @Test
    public void anEmptyFootprintAlsoWaitsForTheResume() throws Exception {
        ResourceRegistry reg = registry(Map.of());
        reg.pause();
        Acquirer nothing = new Acquirer(reg, Set.of());
        assertFalse(nothing.acquired.await(200, TimeUnit.MILLISECONDS), "no step starts while paused");

        reg.resume();
        assertTrue(nothing.acquired.await(5, TimeUnit.SECONDS));
        nothing.release();
    }

    @Test
    public void awaitNotPausedBlocksUntilResumeAndIsInterruptible() throws Exception {
        ResourceRegistry reg = registry(Map.of());
        reg.pause();
        CountDownLatch passed = new CountDownLatch(1);
        Thread blocked = Thread.ofVirtual().start(() -> {
            try {
                reg.awaitNotPaused();
                passed.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread cancelled = Thread.ofVirtual().start(() -> {
            try {
                reg.awaitNotPaused();
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        });
        assertFalse(passed.await(200, TimeUnit.MILLISECONDS), "awaitNotPaused must block while paused");

        cancelled.interrupt();
        cancelled.join(5000);
        assertTrue(interrupted.get(), "awaitNotPaused must be interruptible");

        reg.resume();
        assertTrue(passed.await(5, TimeUnit.SECONDS), "resume must wake awaitNotPaused");
        blocked.join(5000);
    }

    @Test
    public void snapshotExposesThePause() throws Exception {
        ResourceRegistry reg = registry(Map.of("r", 1));
        reg.acquireAll(Set.of("r"));
        reg.releaseAll(Set.of("r"));
        assertTrue(reg.snapshot().stream().noneMatch(ResourceSnapshot::paused));

        reg.pause();
        assertTrue(reg.snapshot().stream().allMatch(ResourceSnapshot::paused));

        reg.resume();
        assertTrue(reg.snapshot().stream().noneMatch(ResourceSnapshot::paused));
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -o -q -pl copybot-engine test -Dtest=ResourceRegistryTest`
Expected: FAIL — erreur de compilation `cannot find symbol` sur `pause()` / `isPaused()` / `awaitNotPaused()` / `paused()`.

- [ ] **Step 3: Implement**

`ResourceSnapshot.java` (LF) devient :

```java
package com.copybot.engine.resources;

/** Point-in-time view of one resource, for UI display; paused is the registry-wide pause flag. */
public record ResourceSnapshot(String name, int capacity, int used, int waiting, boolean paused) {
}
```

Dans `ResourceRegistry.java` (LF) :

1. Après `private final Object lock = new Object();`, ajouter :

```java

    /** While true nothing is granted (see {@link #pause()}). Guarded by lock. */
    private boolean paused;
```

2. Juste avant la méthode `acquireAll`, ajouter :

```java
    /**
     * Stops granting permits: every acquirer, new or already waiting, keeps waiting (interruptibly)
     * until {@link #resume()}. Permits already held are unaffected and are released normally.
     */
    public void pause() {
        synchronized (lock) {
            paused = true;
        }
    }

    /** Lifts a {@link #pause()}: grants whatever became grantable and wakes {@link #awaitNotPaused()} callers. */
    public void resume() {
        synchronized (lock) {
            if (!paused) {
                return;
            }
            paused = false;
            grantEligibleWaiters();
            lock.notifyAll();
        }
    }

    public boolean isPaused() {
        synchronized (lock) {
            return paused;
        }
    }

    /**
     * Blocks while the registry is paused. Interruptible, and throws at once when the calling thread is
     * already interrupted (a cancelled listing must stop emitting even when nothing is paused).
     */
    public void awaitNotPaused() throws InterruptedException {
        synchronized (lock) {
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            while (paused) {
                lock.wait();
            }
        }
    }
```

3. Dans `acquireAll`, remplacer

```java
        if (canonical.isEmpty()) {
            return; // nothing to arbitrate; an empty waiter would never be indexed, hence never scanned
        }
```

par

```java
        if (canonical.isEmpty()) {
            // nothing to arbitrate (an empty waiter would never be indexed, hence never scanned),
            // but a pause must still hold the step
            awaitNotPaused();
            return;
        }
```

4. Dans `snapshot()`, remplacer la ligne

```java
                            waitersByResource.getOrDefault(e.getKey(), Set.of()).size()))
```

par les deux lignes

```java
                            waitersByResource.getOrDefault(e.getKey(), Set.of()).size(),
                            paused))
```

5. En tête de `grantEligibleWaiters()` (première instruction du corps, avant `Set<String> grantable = new HashSet<>();`), ajouter :

```java
        if (paused) {
            return; // resume() rescans
        }
```

- [ ] **Step 4: Run the tests**

Run: `mvn -o -q -pl copybot-engine test`
Expected: PASS (tous les tests du module, dont les 6 nouveaux).

- [ ] **Step 5: Commit**

```bash
git ls-files --eol copybot-engine/src/main/java/com/copybot/engine/resources/ResourceRegistry.java copybot-engine/src/main/java/com/copybot/engine/resources/ResourceSnapshot.java
git add copybot-engine/src/main/java/com/copybot/engine/resources/ResourceRegistry.java copybot-engine/src/main/java/com/copybot/engine/resources/ResourceSnapshot.java copybot-engine/src/test/java/com/copybot/engine/resources/ResourceRegistryTest.java
git commit -m "Pause resource grants in the registry" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

(`git ls-files --eol` : `w/lf` pour les deux fichiers.)

---

### Task 2: Statuts PAUSED / CANCELLED, interruption ⇒ CANCELLED

**Files:**
- Modify: `copybot-engine/src/main/java/com/copybot/engine/pipeline/PipelineStatus.java` (CRLF)
- Modify: `copybot-engine/src/main/java/com/copybot/engine/MainExecutor.java`
- Test: `copybot-engine/src/test/java/com/copybot/engine/MainExecutorTest.java`
- Test: `copybot-engine/src/test/java/com/copybot/engine/MainExecutorResumeTest.java`

**Interfaces:**
- Consumes: —
- Produces: `PipelineStatus.PAUSED`, `PipelineStatus.CANCELLED` ; un `MainExecutor` dont le thread de phase est interrompu termine en `CANCELLED` (plus `ERROR`).

- [ ] **Step 1: Update the existing interruption tests (spec §9)**

Modifications **autorisées par la spec §9** (« les assertions `ERROR` après interruption deviennent `CANCELLED` ») :

Dans `MainExecutorTest`, renommer `interruptingTheRunReleasesEveryPermitAndReportsError` en `interruptingTheRunReleasesEveryPermitAndReportsCancelled` et remplacer

```java
        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus(), "a cancelled run is not a success");
```

par

```java
        assertEquals(PipelineStatus.CANCELLED, exec.getState().getStatus(), "an interrupted run is cancelled, not failed");
```

Dans `MainExecutorResumeTest.cancelledExecuteLeavesTheCursorUnchanged`, remplacer

```java
        assertFalse(runner.isAlive());
        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus());
        assertArrayEquals(before, Files.readAllBytes(store().getPath()), "a cancelled run never moves the cursor");
```

par

```java
        assertFalse(runner.isAlive());
        assertEquals(PipelineStatus.CANCELLED, exec.getState().getStatus());
        assertArrayEquals(before, Files.readAllBytes(store().getPath()), "a cancelled run never moves the cursor");
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -o -q -pl copybot-engine test -Dtest='MainExecutorTest,MainExecutorResumeTest'`
Expected: FAIL — compilation : `cannot find symbol: variable CANCELLED`.

- [ ] **Step 3: Implement**

`PipelineStatus.java` (CRLF, préserver les fins de ligne) devient :

```java
package com.copybot.engine.pipeline;

public enum PipelineStatus {
    NEW,
    LISTED,
    /** listed and analysed, resume resolved: waiting for execute() */
    PREPARED,
    RUNNING,
    /** paused by the caller: no new step starts until resumed, then back to RUNNING */
    PAUSED,
    ERROR,
    SUCCESS,
    /** stopped by the caller (cancel) or by an interruption of the pipeline thread; the resume cursor is not written */
    CANCELLED
}
```

Dans `MainExecutor.java`, remplacer les **trois** occurrences (blocs `catch (InterruptedException e)` de `runSinglePhase`, `prepare` et `execute`) de

```java
            state.setStatus(PipelineStatus.ERROR);
            shutdownTasks();
```

par

```java
            state.setStatus(PipelineStatus.CANCELLED);
            shutdownTasks();
```

(Edit avec `replace_all: true` ; ces deux lignes consécutives n'existent que dans ces trois blocs.) Contrôle : `grep -c "PipelineStatus.CANCELLED" copybot-engine/src/main/java/com/copybot/engine/MainExecutor.java` ⇒ `3`.

- [ ] **Step 4: Run the tests**

Run: `mvn -o -q -pl copybot-engine test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git ls-files --eol copybot-engine/src/main/java/com/copybot/engine/pipeline/PipelineStatus.java copybot-engine/src/main/java/com/copybot/engine/MainExecutor.java
git add copybot-engine/src/main/java/com/copybot/engine/pipeline/PipelineStatus.java copybot-engine/src/main/java/com/copybot/engine/MainExecutor.java copybot-engine/src/test/java/com/copybot/engine/MainExecutorTest.java copybot-engine/src/test/java/com/copybot/engine/MainExecutorResumeTest.java
git commit -m "Report an interrupted pipeline as CANCELLED, add the PAUSED status" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

(`PipelineStatus.java` : `w/crlf` ; `MainExecutor.java` : `w/lf`.)

---

### Task 3: Annulation et pause dans MainExecutor

**Files:**
- Modify: `copybot-engine/src/main/java/com/copybot/engine/MainExecutor.java`
- Modify: `copybot-engine/src/main/java/com/copybot/engine/pipeline/PipelineState.java` (CRLF)
- Create: `copybot-engine/src/test/java/com/copybot/engine/ControlFakes.java`
- Create: `copybot-engine/src/test/java/com/copybot/engine/MainExecutorControlTest.java`
- Create: `copybot-engine/src/test/java/com/copybot/engine/pipeline/PipelineStateTest.java`

**Interfaces:**
- Consumes: `ResourceRegistry.pause()/resume()/isPaused()/awaitNotPaused()` (tâche 1) ; `PipelineStatus.PAUSED/CANCELLED` (tâche 2).
- Produces:
  - `public void MainExecutor.cancel()` — lève la pause, interrompt la phase en cours, statut final `CANCELLED`, curseur non écrit ; une phase pas encore démarrée se termine `CANCELLED` dès son démarrage ; idempotent ; sans effet si `SUCCESS`/`ERROR`/`CANCELLED`. L'interruption qu'il provoque n'est jamais laissée sur le thread appelant de `prepare()`.
  - `public void MainExecutor.pause()` / `public void MainExecutor.resume()` — `RUNNING` ⇄ `PAUSED` ; sans effet sur un pipeline terminé ou en cours d'annulation.
  - `public synchronized boolean PipelineState.compareAndSetStatus(PipelineStatus expected, PipelineStatus next)` ; `setStatus` devient `synchronized`.
  - Fakes de test (package `com.copybot.engine`, classe `ControlFakes`, membres statiques) : `FakeAction`, `DatedIn(Path dir, int count, Runnable afterFirst)`, `BlockingIn` (champ `CountDownLatch started`), `NoopAnalyze`, `GatedOut(Set<String> resources, CountDownLatch gate)` (champs `CountDownLatch firstEntered`, `AtomicInteger started`), `CopybotConfig config()`, `ResourceRegistry registry(Map<String,Integer>)`, `PipelineStepConfig emptyConfig()`, `MainExecutor singlePhase(IInAction, IOutAction, ResourceRegistry)`, `MainExecutor withResume(IInAction, IOutAction, ResourceRegistry, ResumeStateStore)`, `ItemKey day(int)`, `int used(ResourceRegistry, String)`, `boolean allReleased(ResourceRegistry)`, `void awaitTrue(BooleanSupplier)`, `void awaitQuietly(CountDownLatch)`.

- [ ] **Step 1: Create the shared fakes**

`copybot-engine/src/test/java/com/copybot/engine/ControlFakes.java` :

```java
package com.copybot.engine;

import com.copybot.config.CopybotConfig;
import com.copybot.engine.pipeline.PipelineStep;
import com.copybot.engine.pipeline.PipelineStepConfig;
import com.copybot.engine.resources.ResourceRegistry;
import com.copybot.engine.resources.ResourceSettings;
import com.copybot.engine.resources.ResourceSnapshot;
import com.copybot.engine.resume.ItemKey;
import com.copybot.engine.resume.ResumeContext;
import com.copybot.engine.resume.ResumeMode;
import com.copybot.engine.resume.ResumeStateStore;
import com.copybot.plugin.api.action.IAction;
import com.copybot.plugin.api.action.IAnalyzeAction;
import com.copybot.plugin.api.action.IInAction;
import com.copybot.plugin.api.action.IOutAction;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import com.copybot.plugin.api.action.WorkStatus;
import com.copybot.plugin.api.definition.IPlugin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fakes shared by the cancel / pause / engine instance tests. */
final class ControlFakes {

    private ControlFakes() {
    }

    abstract static class FakeAction implements IAction {
        @Override
        public void setStatusWatcher(Consumer<WorkStatus> watcher) {
        }

        @Override
        public void setPlugin(IPlugin plugin) {
        }
    }

    /**
     * Emits IMG_01.JPG, IMG_02.JPG... created in dir and dated 2026-09-01, 2026-09-02... through lastModified.
     * afterFirst (may be null) runs in the listing thread right after the first item was emitted.
     */
    static final class DatedIn extends FakeAction implements IInAction {
        final Path dir;
        final int count;
        final Runnable afterFirst;

        DatedIn(Path dir, int count, Runnable afterFirst) {
            this.dir = dir;
            this.count = count;
            this.afterFirst = afterFirst;
        }

        @Override
        public void listFiles(Consumer<WorkItem> consumer) {
            for (int day = 1; day <= count; day++) {
                try {
                    WorkItem wi = new WorkItem(Files.createFile(dir.resolve(String.format("IMG_%02d.JPG", day))));
                    wi.getMetadatas().setTime(WorkItemMetadata.LAST_MODIFIED,
                            Instant.parse(String.format("2026-09-%02dT10:00:00Z", day)));
                    consumer.accept(wi);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                if (day == 1 && afterFirst != null) {
                    afterFirst.run();
                }
            }
        }
    }

    /** A listing that blocks until it is interrupted, then returns without emitting anything. */
    static final class BlockingIn extends FakeAction implements IInAction {
        final CountDownLatch started = new CountDownLatch(1);

        @Override
        public void listFiles(Consumer<WorkItem> consumer) {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static final class NoopAnalyze extends FakeAction implements IAnalyzeAction {
        @Override
        public void doAnalyze(WorkItem item) {
        }
    }

    /**
     * Out step counting the writes that started. The first write blocks until gate opens (gate null: never
     * blocks; a gate never opened blocks until the write is interrupted, which then fails the item).
     */
    static final class GatedOut extends FakeAction implements IOutAction {
        final Set<String> resources;
        final CountDownLatch gate;
        final CountDownLatch firstEntered = new CountDownLatch(1);
        final AtomicInteger started = new AtomicInteger();

        GatedOut(Set<String> resources, CountDownLatch gate) {
            this.resources = resources;
            this.gate = gate;
        }

        @Override
        public Set<String> requiredResources(WorkItem item) {
            return resources;
        }

        @Override
        public void writeItem(WorkItem item) {
            if (started.incrementAndGet() == 1) {
                firstEntered.countDown();
                if (gate != null) {
                    try {
                        gate.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("interrupted", e);
                    }
                }
            }
        }
    }

    /** A configuration without plugins, every disk wide open. */
    static CopybotConfig config() {
        return new CopybotConfig(null, null, Map.of("disk:*", 1000), null);
    }

    static ResourceRegistry registry(Map<String, Integer> capacities) {
        return new ResourceRegistry(ResourceSettings.from(new CopybotConfig(null, null, capacities, null)));
    }

    static PipelineStepConfig emptyConfig() {
        return new PipelineStepConfig(null, null, null, null, null, null, null, null);
    }

    /** Single-phase pipeline without resume (pipelining): listing -> out. */
    static MainExecutor singlePhase(IInAction in, IOutAction out, ResourceRegistry registry) {
        return new MainExecutor(
                List.of(new PipelineStep<>(null, in, emptyConfig())),
                List.of(new PipelineStep<>(null, out, emptyConfig())),
                true, null, registry);
    }

    /** Two-phase pipeline with resume (mode state): listing -> analyse | barrier | out. */
    static MainExecutor withResume(IInAction in, IOutAction out, ResourceRegistry registry, ResumeStateStore store) {
        return new MainExecutor(
                List.of(new PipelineStep<>(null, in, emptyConfig())),
                List.of(new PipelineStep<>(null, new NoopAnalyze(), emptyConfig()), new PipelineStep<>(null, out, emptyConfig())),
                1, false, null, registry, new ResumeContext(ResumeMode.STATE, store));
    }

    /** The resume key of the item DatedIn emits for this day. */
    static ItemKey day(int d) {
        return new ItemKey(Instant.parse(String.format("2026-09-%02dT10:00:00Z", d)), String.format("IMG_%02d.JPG", d));
    }

    static int used(ResourceRegistry reg, String name) {
        return reg.snapshot().stream().filter(s -> s.name().equals(name)).mapToInt(ResourceSnapshot::used).sum();
    }

    static boolean allReleased(ResourceRegistry reg) {
        return reg.snapshot().stream().allMatch(s -> s.used() == 0 && s.waiting() == 0);
    }

    static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean()) {
            assertTrue(System.currentTimeMillis() < deadline, "condition not met within 5s");
            Thread.sleep(5);
        }
    }

    static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

- [ ] **Step 2: Write the failing tests**

`copybot-engine/src/test/java/com/copybot/engine/pipeline/PipelineStateTest.java` :

```java
package com.copybot.engine.pipeline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PipelineStateTest {

    @Test
    public void compareAndSetStatusOnlyReplacesTheExpectedStatus() {
        PipelineState state = new PipelineState(List.of());
        state.setStatus(PipelineStatus.RUNNING);

        assertTrue(state.compareAndSetStatus(PipelineStatus.RUNNING, PipelineStatus.PAUSED));
        assertEquals(PipelineStatus.PAUSED, state.getStatus());
        assertFalse(state.compareAndSetStatus(PipelineStatus.RUNNING, PipelineStatus.PAUSED));

        state.setStatus(PipelineStatus.SUCCESS);
        assertFalse(state.compareAndSetStatus(PipelineStatus.PAUSED, PipelineStatus.RUNNING),
                "a resume after the end must not revive the run");
        assertEquals(PipelineStatus.SUCCESS, state.getStatus());
    }
}
```

`copybot-engine/src/test/java/com/copybot/engine/MainExecutorControlTest.java` :

```java
package com.copybot.engine;

import com.copybot.engine.pipeline.PipelineStatus;
import com.copybot.engine.resources.ResourceRegistry;
import com.copybot.engine.resume.ResumeStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.copybot.engine.ControlFakes.*;
import static org.junit.jupiter.api.Assertions.*;

/** cancel() / pause() / resume() of a MainExecutor (spec engine-instance §3 and §4). */
public class MainExecutorControlTest {

    @TempDir
    Path tempDir;

    private ResumeStateStore store() {
        return new ResumeStateStore(tempDir.resolve("p.state.json"));
    }

    @Test
    public void pauseStopsNewStepsFromStartingAndResumeReleasesThem() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        GatedOut out = new GatedOut(Set.of("proc"), gate);
        ResourceRegistry reg = registry(Map.of("proc", 1, "disk:*", 1000));
        MainExecutor exec = singlePhase(new DatedIn(tempDir, 3, null), out, reg);
        Thread runner = Thread.ofVirtual().start(exec);
        assertTrue(out.firstEntered.await(5, TimeUnit.SECONDS), "a first step is running");

        exec.pause();
        assertEquals(PipelineStatus.PAUSED, exec.getState().getStatus());
        gate.countDown(); // the running step ends and releases "proc" normally
        awaitTrue(() -> used(reg, "proc") == 0);
        Thread.sleep(200);
        assertEquals(1, out.started.get(), "no new step starts while paused");

        exec.resume();
        runner.join(TimeUnit.SECONDS.toMillis(10));
        assertFalse(runner.isAlive());
        assertEquals(3, out.started.get(), "resume lets the waiting steps start");
        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus(), "a pause has no effect on the final status");
    }

    @Test
    public void resumeGoesBackToRunning() throws Exception {
        GatedOut out = new GatedOut(Set.of(), new CountDownLatch(1)); // never opened: the run cannot end by itself
        MainExecutor exec = singlePhase(new DatedIn(tempDir, 1, null), out, registry(Map.of("disk:*", 1000)));
        Thread runner = Thread.ofVirtual().start(exec);
        assertTrue(out.firstEntered.await(5, TimeUnit.SECONDS));

        exec.pause();
        assertEquals(PipelineStatus.PAUSED, exec.getState().getStatus());
        exec.resume();
        assertEquals(PipelineStatus.RUNNING, exec.getState().getStatus());

        exec.cancel();
        runner.join(TimeUnit.SECONDS.toMillis(20));
        assertFalse(runner.isAlive());
        assertEquals(PipelineStatus.CANCELLED, exec.getState().getStatus());
    }

    @Test
    public void pauseAlsoHoldsTheListingBeforeItEmitsTheNextItem() throws Exception {
        CountDownLatch firstEmitted = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        MainExecutor exec = singlePhase(new DatedIn(tempDir, 3, () -> {
            firstEmitted.countDown();
            awaitQuietly(proceed);
        }), new GatedOut(Set.of(), null), registry(Map.of("disk:*", 1000)));
        Thread runner = Thread.ofVirtual().start(exec);
        assertTrue(firstEmitted.await(5, TimeUnit.SECONDS));

        exec.pause();
        proceed.countDown(); // the listing goes on, but must wait before creating the next item
        Thread.sleep(200);
        assertEquals(1, exec.getState().getWorkItems().size(), "no item is emitted while paused");

        exec.resume();
        runner.join(TimeUnit.SECONDS.toMillis(10));
        assertFalse(runner.isAlive());
        assertEquals(3, exec.getState().getWorkItems().size());
        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
    }

    @Test
    public void cancelDuringThePreparationEndsCancelledWithoutLeakingTheInterrupt() throws Exception {
        BlockingIn listing = new BlockingIn();
        ResourceRegistry reg = registry(Map.of("disk:*", 1000));
        MainExecutor exec = withResume(listing, new GatedOut(Set.of(), null), reg, store());
        AtomicBoolean callerInterrupted = new AtomicBoolean(true);
        Thread caller = Thread.ofVirtual().start(() -> {
            exec.prepare(); // prepare() runs in the caller's thread
            callerInterrupted.set(Thread.currentThread().isInterrupted());
        });
        assertTrue(listing.started.await(5, TimeUnit.SECONDS));

        exec.cancel();
        caller.join(TimeUnit.SECONDS.toMillis(20));

        assertFalse(caller.isAlive());
        assertEquals(PipelineStatus.CANCELLED, exec.getState().getStatus());
        assertFalse(callerInterrupted.get(), "the interrupt used by cancel() must not leak to the caller's thread");
        assertTrue(allReleased(reg), "cancellation releases every permit, got " + reg.snapshot());
        assertFalse(Files.exists(store().getPath()), "a cancelled preparation writes no state");
    }

    @Test
    public void cancelDuringTheExecutionEndsCancelledAndKeepsTheCursor() throws Exception {
        store().writeCursor(day(1));
        byte[] before = Files.readAllBytes(store().getPath());
        GatedOut out = new GatedOut(Set.of(), new CountDownLatch(1));
        ResourceRegistry reg = registry(Map.of("disk:*", 1000));
        MainExecutor exec = withResume(new DatedIn(tempDir, 3, null), out, reg, store());
        exec.prepare();
        assertEquals(PipelineStatus.PREPARED, exec.getState().getStatus());
        Thread runner = Thread.ofVirtual().start(() -> exec.execute(null));
        assertTrue(out.firstEntered.await(5, TimeUnit.SECONDS));

        exec.cancel();
        runner.join(TimeUnit.SECONDS.toMillis(20));

        assertFalse(runner.isAlive());
        assertEquals(PipelineStatus.CANCELLED, exec.getState().getStatus());
        assertTrue(allReleased(reg), "cancellation releases every permit, got " + reg.snapshot());
        assertArrayEquals(before, Files.readAllBytes(store().getPath()), "a cancelled run never moves the cursor");
    }

    @Test
    public void cancelDuringAPauseLiftsThePauseThenCancels() throws Exception {
        GatedOut out = new GatedOut(Set.of(), new CountDownLatch(1));
        ResourceRegistry reg = registry(Map.of("disk:*", 1000));
        MainExecutor exec = singlePhase(new DatedIn(tempDir, 3, null), out, reg);
        Thread runner = Thread.ofVirtual().start(exec);
        assertTrue(out.firstEntered.await(5, TimeUnit.SECONDS));
        exec.pause();

        exec.cancel();
        runner.join(TimeUnit.SECONDS.toMillis(20));

        assertFalse(runner.isAlive());
        assertEquals(PipelineStatus.CANCELLED, exec.getState().getStatus());
        assertFalse(reg.isPaused(), "cancel lifts the pause");
        assertTrue(allReleased(reg), "got " + reg.snapshot());
    }

    @Test
    public void cancelBeforeTheStartEndsCancelledWithoutRunningAnything() {
        GatedOut out = new GatedOut(Set.of(), null);
        MainExecutor exec = singlePhase(new DatedIn(tempDir, 2, null), out, registry(Map.of("disk:*", 1000)));

        exec.cancel();
        exec.run();

        assertEquals(PipelineStatus.CANCELLED, exec.getState().getStatus());
        assertEquals(0, exec.getState().getWorkItems().size(), "nothing is listed");
        assertEquals(0, out.started.get());
        assertFalse(Thread.interrupted(), "the calling thread is not left interrupted");
    }

    @Test
    public void cancelAndPauseHaveNoEffectOnceTheRunIsOver() {
        ResourceRegistry reg = registry(Map.of("disk:*", 1000));
        MainExecutor exec = singlePhase(new DatedIn(tempDir, 2, null), new GatedOut(Set.of(), null), reg);
        exec.run();
        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());

        exec.cancel();
        exec.pause();
        exec.cancel();

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
        assertFalse(reg.isPaused());
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `mvn -o -q -pl copybot-engine test -Dtest='MainExecutorControlTest,PipelineStateTest'`
Expected: FAIL — compilation : `cannot find symbol` sur `compareAndSetStatus`, `cancel()`, `pause()`, `resume()`.

- [ ] **Step 4: Implement PipelineState**

Dans `PipelineState.java` (CRLF, préserver), remplacer

```java
    public void setStatus(PipelineStatus status) {
        this.status = status;
    }
```

par

```java
    public synchronized void setStatus(PipelineStatus status) {
        this.status = status;
    }

    /** Atomically replaces the status when it is still the expected one (pause / resume vs. the end of the run). */
    public synchronized boolean compareAndSetStatus(PipelineStatus expected, PipelineStatus next) {
        if (status != expected) {
            return false;
        }
        status = next;
        return true;
    }
```

- [ ] **Step 5: Implement MainExecutor**

Dans `MainExecutor.java` (LF) :

1. Ajouter l'import `import java.util.concurrent.CancellationException;` (après `import java.util.Set;`).

2. Après `private volatile Thread notifier;`, ajouter :

```java

    /** Guards phaseThread and the cancel / pause requests against the start and the end of a phase. */
    private final Object phaseLock = new Object();
    /** The thread running the current phase (single-phase run, prepare or execute), null between phases. */
    private Thread phaseThread;
    private volatile boolean cancelRequested;
    private volatile boolean paused;
```

3. Remplacer la méthode `runSinglePhase()` entière par :

```java
    private void runSinglePhase() {
        markRunning();
        state.setListingInProgress(true);
        startPhase();
        try {
            checkCancelled();
            // Step resolution is INSIDE the try: a missing plugin/action must be reported as a
            // failed run (status ERROR + watcher notified), not as an exception out of a state-less run.
            resolveStepsIfNeeded();
            phaseEnd = itemSteps.size();
            runListings();
            checkCancelled();
            state.setStatus(resolveFinalStatus());
        } catch (InterruptedException e) {
            // Cancellation: unblock every item still parked in acquireAll/IO and let it release its
            // permits, THEN restore the interrupt flag (awaitTermination would return immediately
            // with the flag set, leaving writers to be killed mid-stream by JVM exit).
            state.setStatus(PipelineStatus.CANCELLED);
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
```

4. Dans `prepare()`, remplacer

```java
        state.setStatus(PipelineStatus.RUNNING);
        state.setListingInProgress(true);
        startPhase();
        try {
            resolveStepsIfNeeded();
            phaseEnd = barrierIndex;
            runListings();
            if (listingFailed.get()) {
```

par

```java
        markRunning();
        state.setListingInProgress(true);
        startPhase();
        try {
            checkCancelled();
            resolveStepsIfNeeded();
            phaseEnd = barrierIndex;
            runListings();
            checkCancelled();
            if (listingFailed.get()) {
```

5. Dans `execute(ResumePoint override)`, remplacer

```java
        applyOverride(override);
        state.setStatus(PipelineStatus.RUNNING);
        startPhase();
        try {
            for (WorkItemExecution exec : orderedItems) {
                if (exec.getStatus() == ItemStatus.PENDING) {
                    submitItem(exec, barrierIndex, itemSteps.size());
                }
            }
            awaitCompletion();
```

par

```java
        applyOverride(override);
        markRunning();
        startPhase();
        try {
            checkCancelled();
            for (WorkItemExecution exec : orderedItems) {
                if (exec.getStatus() == ItemStatus.PENDING) {
                    submitItem(exec, barrierIndex, itemSteps.size());
                }
            }
            awaitCompletion();
            checkCancelled(); // a cancelled run never writes the cursor
```

6. Juste après la méthode `run()` (avant `private void runSinglePhase()`), ajouter :

```java
    /**
     * Stops the pipeline cleanly: lifts a pause, then interrupts the current phase, whose listings and
     * items are interrupted in turn and release their permits. The pipeline ends CANCELLED and the resume
     * cursor is not written. A phase that has not started yet ends CANCELLED as soon as it starts.
     * Idempotent; no effect once the pipeline has terminated. Callable from any thread.
     */
    public void cancel() {
        if (isTerminated()) {
            return;
        }
        resume();
        synchronized (phaseLock) {
            if (cancelRequested) {
                return;
            }
            cancelRequested = true;
            if (phaseThread != null) {
                phaseThread.interrupt();
            }
        }
    }

    /**
     * Stops granting resources: no new step starts and the listings wait before emitting their next item;
     * the steps already running finish normally. Status PAUSED until {@link #resume()}. No effect once
     * the pipeline has terminated or is being cancelled. Callable from any thread.
     */
    public void pause() {
        synchronized (phaseLock) {
            if (cancelRequested || isTerminated()) {
                return;
            }
            paused = true;
            registry.pause();
            state.compareAndSetStatus(PipelineStatus.RUNNING, PipelineStatus.PAUSED);
        }
    }

    /** Lifts a {@link #pause()}: back to RUNNING, the waiting steps and listings go on. */
    public void resume() {
        synchronized (phaseLock) {
            if (!paused) {
                return;
            }
            paused = false;
            registry.resume();
            state.compareAndSetStatus(PipelineStatus.PAUSED, PipelineStatus.RUNNING);
        }
    }

    /** Status at the start of a phase: RUNNING, or PAUSED when a pause was requested beforehand. */
    private void markRunning() {
        synchronized (phaseLock) {
            state.setStatus(paused ? PipelineStatus.PAUSED : PipelineStatus.RUNNING);
        }
    }

    /**
     * Turns a pending cancel() into the phase's cancellation path, including when it raced with the end
     * of the phase: after a cancel the phase must neither publish SUCCESS nor write the cursor.
     */
    private void checkCancelled() throws InterruptedException {
        if (cancelRequested) {
            throw new InterruptedException("cancelled");
        }
    }

    private boolean isTerminated() {
        PipelineStatus status = state.getStatus();
        return status == PipelineStatus.SUCCESS || status == PipelineStatus.ERROR || status == PipelineStatus.CANCELLED;
    }
```

7. Remplacer `startPhase()` et `endPhase()` :

```java
    private void startPhase() {
        taskExecutor = Executors.newVirtualThreadPerTaskExecutor();
        startNotifier();
        notifyWatcher();
    }

    private void endPhase() {
        shutdownTasks();
        stopNotifier(); // includes the final, guaranteed notification
    }
```

par

```java
    private void startPhase() {
        synchronized (phaseLock) {
            phaseThread = Thread.currentThread(); // what cancel() interrupts
        }
        taskExecutor = Executors.newVirtualThreadPerTaskExecutor();
        startNotifier();
        notifyWatcher();
    }

    private void endPhase() {
        synchronized (phaseLock) {
            phaseThread = null;
        }
        shutdownTasks();
        stopNotifier(); // includes the final, guaranteed notification
        if (cancelRequested) {
            // the interrupt came from cancel(): it must not leak to the caller of prepare() (e.g. a UI worker)
            Thread.interrupted();
        }
    }
```

8. Dans `runListing`, remplacer

```java
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            // A failing listing must not silently look like success: the whole run is marked ERROR.
```

par

```java
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (CancellationException e) {
            // interrupted while waiting for the end of a pause (see emitItem): a cancellation, not a listing failure
        } catch (Throwable t) {
            // A failing listing must not silently look like success: the whole run is marked ERROR.
```

9. Remplacer le début de `emitItem`

```java
    private void emitItem(WorkItem workItem) {
        WorkItemExecution exec = new WorkItemExecution(workItem, itemSteps);
```

par

```java
    private void emitItem(WorkItem workItem) {
        // The listing holds its disk for its whole duration, so the registry pause alone would not
        // freeze it: wait here, before creating the item.
        try {
            registry.awaitNotPaused();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("listing interrupted");
        }
        WorkItemExecution exec = new WorkItemExecution(workItem, itemSteps);
```

- [ ] **Step 6: Run the tests**

Run: `mvn -o -q -pl copybot-engine test`
Expected: PASS (dont les 8 tests de `MainExecutorControlTest` et celui de `PipelineStateTest`).

- [ ] **Step 7: Commit**

```bash
git ls-files --eol copybot-engine/src/main/java/com/copybot/engine/MainExecutor.java copybot-engine/src/main/java/com/copybot/engine/pipeline/PipelineState.java
git add copybot-engine/src/main/java/com/copybot/engine/MainExecutor.java copybot-engine/src/main/java/com/copybot/engine/pipeline/PipelineState.java copybot-engine/src/test/java/com/copybot/engine/ControlFakes.java copybot-engine/src/test/java/com/copybot/engine/MainExecutorControlTest.java copybot-engine/src/test/java/com/copybot/engine/pipeline/PipelineStateTest.java
git commit -m "Cancel and pause a running pipeline" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

(`MainExecutor.java` : `w/lf` ; `PipelineState.java` : `w/crlf`.)

---

### Task 4: Un seul style d'échec — préparation et exécution ne lèvent plus

**Files:**
- Modify: `copybot-engine/src/main/java/com/copybot/engine/MainExecutor.java`
- Test: `copybot-engine/src/test/java/com/copybot/engine/MainExecutorTest.java`
- Test: `copybot-engine/src/test/java/com/copybot/engine/MainExecutorResumeTest.java`

**Interfaces:**
- Consumes: `MainExecutor` de la tâche 3.
- Produces: `MainExecutor.run()`, `prepare()` et `execute(ResumePoint)` ne relancent plus aucune `RuntimeException`/`Error` de pipeline : `state.getFailure()` = la cause (posée **avant** le statut), `state.getStatus()` = `ERROR`. Seule la précondition de `execute` (plan non `PREPARED` ⇒ `IllegalStateException`) lève encore (spec §8 : `runSinglePhase` pose `setFailure`).

- [ ] **Step 1: Update and add the tests**

Modifications **autorisées par la spec §9** (« chaque cause d'échec ⇒ `Plan` en `ERROR` avec `failure`, sans exception ») — dans `MainExecutorResumeTest`, remplacer les deux méthodes suivantes :

```java
    @Test
    public void invalidStateFileFailsThePreparation() throws IOException {
        Files.writeString(store().getPath(), "not json");
        MainExecutor exec = executor(2, new RecordingAnalyze(), new RecordingOut(null), ResumeMode.STATE);

        assertThrows(RuntimeException.class, exec::prepare);
        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus());
        assertThrows(IllegalStateException.class, () -> exec.execute(null));
    }
```

par

```java
    @Test
    public void invalidStateFileFailsThePreparation() throws IOException {
        Files.writeString(store().getPath(), "not json");
        MainExecutor exec = executor(2, new RecordingAnalyze(), new RecordingOut(null), ResumeMode.STATE);

        assertDoesNotThrow(exec::prepare);
        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus());
        assertNotNull(exec.getState().getFailure(), "the cause of the failure is in the state");
        assertThrows(IllegalStateException.class, () -> exec.execute(null));
    }
```

et

```java
    @Test
    public void engineRefusesToExecuteAPlanThatIsNotPrepared() throws IOException {
        Files.writeString(store().getPath(), "not json");
        MainExecutor exec = executor(2, new RecordingAnalyze(), new RecordingOut(null), ResumeMode.STATE);
        assertThrows(RuntimeException.class, exec::prepare);

        assertThrows(IllegalStateException.class, () -> CopybotEngine.execute(new Plan(exec), null));
    }
```

par

```java
    @Test
    public void engineRefusesToExecuteAPlanThatIsNotPrepared() throws IOException {
        Files.writeString(store().getPath(), "not json");
        MainExecutor exec = executor(2, new RecordingAnalyze(), new RecordingOut(null), ResumeMode.STATE);
        exec.prepare();

        assertThrows(IllegalStateException.class, () -> CopybotEngine.execute(new Plan(exec), null));
    }
```

Puis ajouter à la fin de `MainExecutorResumeTest` :

```java
    @Test
    public void destinationModeWithoutTargetPathsIsAPreparationFailureNotAnException() {
        // RecordingOut does not implement resolveTarget
        MainExecutor exec = executor(2, new RecordingAnalyze(), new RecordingOut(null), ResumeMode.DESTINATION);

        assertDoesNotThrow(exec::prepare);

        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus());
        assertNotNull(exec.getState().getFailure());
    }

    @Test
    public void anUnresolvableStepIsAPreparationFailureNotAnException() {
        PipelineStepConfig unknown = new PipelineStepConfig("no.such.plugin", "file.read", null, null, null, null, null, null);
        MainExecutor exec = new MainExecutor(new PipelineConfig(List.of(unknown), null, null, null, null, null),
                null, registry(), new ResumeContext(ResumeMode.STATE, store()));

        assertDoesNotThrow(exec::prepare);

        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus());
        assertNotNull(exec.getState().getFailure());
    }

    static final class FailingIn extends FakeAction implements IInAction {
        @Override
        public void listFiles(Consumer<WorkItem> consumer) {
            throw new IllegalStateException("listing boom");
        }
    }

    @Test
    public void aListingFailureIsAPreparationFailureWithItsCause() {
        MainExecutor exec = new MainExecutor(
                List.of(new PipelineStep<>(null, new FailingIn(), emptyConfig())),
                List.of(new PipelineStep<>(null, new RecordingAnalyze(), emptyConfig()),
                        new PipelineStep<>(null, new RecordingOut(null), emptyConfig())),
                1, false, null, registry(), new ResumeContext(ResumeMode.STATE, store()));

        exec.prepare();

        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus());
        assertEquals("listing boom", exec.getState().getFailure().getMessage());
    }
```

Et à la fin de `MainExecutorTest` :

```java
    @Test
    public void anUnresolvableStepEndsInErrorWithItsCauseInsteadOfThrowing() {
        PipelineStepConfig unknown = new PipelineStepConfig("no.such.plugin", "file.read", null, null, null, null, null, null);
        MainExecutor exec = new MainExecutor(new PipelineConfig(List.of(unknown), null, null, null, null, null),
                null, registry(Map.of("disk:*", 1000)), null);

        assertDoesNotThrow(exec::run);

        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus());
        assertNotNull(exec.getState().getFailure(), "the cause is kept in the state");
    }
```

(Les imports existants suffisent : `com.copybot.engine.pipeline.*`, `com.copybot.plugin.api.action.*`, `com.copybot.engine.resume.*`, `static org.junit.jupiter.api.Assertions.*`.)

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -o -q -pl copybot-engine test -Dtest='MainExecutorTest,MainExecutorResumeTest'`
Expected: FAIL — `invalidStateFileFailsThePreparation`, `engineRefusesToExecuteAPlanThatIsNotPrepared`, `destinationModeWithoutTargetPaths…`, `anUnresolvableStepIsAPreparationFailure…` et `anUnresolvableStepEndsInError…` échouent (« Unexpected exception thrown ») ; `aListingFailureIsAPreparationFailureWithItsCause` passe déjà (garde-fou).

- [ ] **Step 3: Implement**

Dans `MainExecutor.java` :

1. Dans `runSinglePhase`, remplacer (occurrence unique)

```java
            state.setStatus(PipelineStatus.ERROR);
            throw e;
```

par

```java
            // e.g. a missing plugin/action: reported in the state, never thrown out of the pipeline thread
            state.setFailure(e);
            state.setStatus(PipelineStatus.ERROR);
```

2. Dans `prepare` et `execute`, remplacer les **deux** occurrences (`replace_all: true`) de

```java
            state.setStatus(PipelineStatus.ERROR);
            state.setFailure(e);
            throw e;
```

par

```java
            // reported in the state, never thrown out of the pipeline thread (spec engine-instance §2)
            state.setFailure(e);
            state.setStatus(PipelineStatus.ERROR);
```

3. Dans la javadoc de `prepare()`, remplacer

```java
     * A listing failure leaves the pipeline in ERROR: a partial listing would give a wrong resume point.
     *
     * @throws RuntimeException when the resume point cannot be resolved (e.g. invalid state file),
     *                          after the status has been set to ERROR
     */
```

par

```java
     * A listing failure leaves the pipeline in ERROR: a partial listing would give a wrong resume point.
     * Never throws for a pipeline failure (unresolvable step, invalid state file, "destination" mode
     * without target paths...): the pipeline ends ERROR with the cause in {@link PipelineState#getFailure()}.
     * Cancellable ({@link #cancel()}): it then ends CANCELLED.
     */
```

Contrôle : `grep -n "throw e;" copybot-engine/src/main/java/com/copybot/engine/MainExecutor.java` ⇒ seule la ligne de `submitTask` (`throw e;` après `completeTask();`) reste.

- [ ] **Step 4: Run the tests**

Run: `mvn -o -q -pl copybot-engine test`
Expected: PASS (y compris `ResumeEndToEndTest` : la CLI signale toujours un plan non `PREPARED` via `pipeline.prepare-failed`).

- [ ] **Step 5: Commit**

```bash
git ls-files --eol copybot-engine/src/main/java/com/copybot/engine/MainExecutor.java
git add copybot-engine/src/main/java/com/copybot/engine/MainExecutor.java copybot-engine/src/test/java/com/copybot/engine/MainExecutorTest.java copybot-engine/src/test/java/com/copybot/engine/MainExecutorResumeTest.java
git commit -m "Report preparation and run failures in the state instead of throwing" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Chargement des plugins thread-safe et idempotent

**Files:**
- Modify: `copybot-engine/src/main/java/com/copybot/engine/plugin/PluginEngine.java` (CRLF)
- Modify: `copybot-engine/src/main/java/com/copybot/engine/CopybotEngine.java` (CRLF)
- Modify: `copybot-engine/src/main/resources/com/copybot/engine/i18n/engineBundle.properties` (ASCII, CRLF — printf uniquement)
- Modify: `copybot-engine/src/main/resources/com/copybot/engine/i18n/engineBundle_fr.properties` (ISO-8859-1, CRLF — printf uniquement)
- Create: `copybot-engine/src/test/java/com/copybot/engine/plugin/PluginEngineTest.java`

**Interfaces:**
- Consumes: —
- Produces: `public static synchronized void PluginEngine.load(Path pluginDir, List<Path> devPluginDirs)` — le premier appel réussi charge ; les suivants sont ignorés (avertissement `plugin.load.ignored` si les répertoires diffèrent) ; `getLoadedPlugins()` renvoie toujours la même liste après le premier chargement. `CopybotEngine` n'a plus de drapeau `pluginsLoaded`.

- [ ] **Step 1: Write the failing tests**

`copybot-engine/src/test/java/com/copybot/engine/plugin/PluginEngineTest.java` :

```java
package com.copybot.engine.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PluginEngine.load runs once per JVM (JPMS layers cannot be unloaded). Another test class may already
 * have loaded the plugins: every assertion holds whichever load came first.
 */
public class PluginEngineTest {

    @TempDir
    Path tempDir;

    @Test
    public void aSecondLoadIsIgnoredWithAWarning() throws Exception {
        PluginEngine.load(Files.createDirectories(tempDir.resolve("plugins-a")), List.of());
        List<PluginDefinition> loaded = PluginEngine.getLoadedPlugins();

        Logger jul = Logger.getLogger(PluginEngine.class.getCanonicalName());
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        jul.addHandler(handler);
        try {
            PluginEngine.load(Files.createDirectories(tempDir.resolve("plugins-b")), List.of());
        } finally {
            jul.removeHandler(handler);
        }

        assertSame(loaded, PluginEngine.getLoadedPlugins(), "a second load must neither reload nor duplicate the plugins");
        assertTrue(records.stream().anyMatch(r -> r.getLevel() == Level.WARNING),
                "loading other plugin directories in the same JVM is reported");
    }

    @Test
    public void concurrentLoadsLoadEachPluginOnce() throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("plugins"));
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    PluginEngine.load(dir, List.of());
                } catch (Throwable t) {
                    failures.add(t);
                }
            }));
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join(10_000);
        }

        assertEquals(List.of(), failures);
        List<String> ids = PluginEngine.getLoadedPlugins().stream().map(p -> p.getName() + ":" + p.getVersion()).toList();
        assertFalse(ids.isEmpty(), "at least the embedded plugin is loaded");
        assertEquals(ids.size(), new HashSet<>(ids).size(), "no plugin is loaded twice: " + ids);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -o -q -pl copybot-engine test -Dtest=PluginEngineTest`
Expected: FAIL — le second `load` lève `UnsupportedOperationException` (ajout dans une liste devenue non modifiable).

- [ ] **Step 3: Add the message key (byte-safe)**

Depuis le shell **Bash**, à la racine du repo :

```bash
printf '%s\r\n' 'plugin.load.ignored=Plugins already loaded from {1}: the plugin directories {0} are ignored (restart to change them)' >> copybot-engine/src/main/resources/com/copybot/engine/i18n/engineBundle.properties
printf '%s\r\n' 'plugin.load.ignored=Plugins déjà chargés depuis {1} : les répertoires de plugins {0} sont ignorés (redémarrer pour les changer)' >> copybot-engine/src/main/resources/com/copybot/engine/i18n/engineBundle_fr.properties
file copybot-engine/src/main/resources/com/copybot/engine/i18n/engineBundle*.properties
git diff --stat -- copybot-engine/src/main/resources/com/copybot/engine/i18n/
```

Expected : `engineBundle.properties: ASCII text, with CRLF line terminators`, `engineBundle_fr.properties: ISO-8859 text, with CRLF line terminators`, et `2 files changed, 2 insertions(+)`.

- [ ] **Step 4: Implement PluginEngine**

Réécrire `PluginEngine.java` en entier, puis `unix2dos -q copybot-engine/src/main/java/com/copybot/engine/plugin/PluginEngine.java` :

```java
package com.copybot.engine.plugin;

import com.copybot.engine.pipeline.PipelineStep;
import com.copybot.engine.pipeline.PipelineStepConfig;
import com.copybot.engine.pipeline.StepType;
import com.copybot.engine.plugin.loader.PluginLoader;
import com.copybot.exception.PluginNotFoundException;
import com.copybot.logger.CopybotLogger;
import com.copybot.plugin.api.action.IAction;
import com.copybot.plugin.embedded.CBEmbeddedPlugin;
import com.copybot.utils.FileUtil;
import com.copybot.utils.VersionUtil;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class PluginEngine {

    private static final CopybotLogger LOG = CopybotLogger.getLogger(PluginEngine.class);

    // replaced as a whole, once, by load(): readers never see a partially filled list
    private static volatile List<PluginDefinition> loadedPlugins = List.of();
    private static volatile List<PluginDefinition> errorPlugins = List.of();

    /** Directories of the first (and only) load, null before it. Guarded by the class lock. */
    private static List<Path> loadedFrom;

    /**
     * Loads the plugins of these directories. Thread-safe and idempotent: only the first successful call
     * of the JVM loads (JPMS module layers cannot be unloaded); later calls are ignored, with a warning in
     * the log when they ask for other directories. A first call that failed can be retried.
     */
    public static synchronized void load(Path pluginDir, List<Path> devPluginDirs) {
        List<Path> requested = new ArrayList<>();
        requested.add(pluginDir.toAbsolutePath().normalize());
        devPluginDirs.forEach(dir -> requested.add(dir.toAbsolutePath().normalize()));
        if (loadedFrom != null) {
            if (!loadedFrom.equals(requested)) {
                LOG.warn("plugin.load.ignored", requested, loadedFrom);
            }
            return;
        }

        PluginLoader pl = new PluginLoader();

        pl.resolve(FileUtil.listDirectory(pluginDir), false);
        pl.resolve(devPluginDirs, true);

        var allPlugins = pl.load();
        allPlugins.sort(Comparator
                .comparing(PluginDefinition::getName)
                .thenComparing(PluginDefinition::getVersion, Comparator.reverseOrder()));

        List<PluginDefinition> loaded = new ArrayList<>();
        List<PluginDefinition> errors = new ArrayList<>();
        for (PluginDefinition pluginDefinition : allPlugins) {
            if (pluginDefinition.getErrorMessage() == null) {
                loaded.add(pluginDefinition);
            } else {
                errors.add(pluginDefinition);
            }
        }
        loadedPlugins = Collections.unmodifiableList(loaded);
        errorPlugins = Collections.unmodifiableList(errors);
        loadedFrom = List.copyOf(requested);
    }

    public static List<PluginDefinition> getLoadedPlugins() {
        return loadedPlugins;
    }

    public static List<PluginDefinition> getErrorPlugins() {
        return errorPlugins;
    }


    public static <A extends IAction> PipelineStep<A> resolve(PipelineStepConfig stepConfig, Class<A> actionClass) {
        StepType type = StepType.getType(actionClass);
        // if config contains version, get plugin with desired version
        // else get most recent (list is ordered with most recent first)
        var pluginDef = loadedPlugins.stream()
                .filter(p -> pluginMatch(p, stepConfig))
                .findFirst()
                .orElseThrow(() -> new PluginNotFoundException(stepConfig.getDisplayName()));

        var actionDef = pluginDef.findAction(stepConfig.action(), type);
        IAction actionInstance = actionDef.getInstance();
        actionInstance.loadConfig(stepConfig.actionConfig());
        return new PipelineStep(pluginDef.getPluginInstance(), actionInstance, stepConfig);
    }


    private static boolean pluginMatch(PluginDefinition plugin, PipelineStepConfig stepConfig) {
        String stepPluginName = stepConfig.plugin() == null || stepConfig.plugin().isBlank() ? CBEmbeddedPlugin.EMBEDDED_PLUGN_NAME : stepConfig.plugin();
        return plugin.getName().equals(stepPluginName)
                && (
                plugin.getVersion() == null // fixme embedded plugin have no version
                        || VersionUtil.isCompatible(plugin.getVersion(), stepConfig.version(), true)
        );
    }
}
```

- [ ] **Step 5: Drop the pluginsLoaded flag in CopybotEngine**

Dans `CopybotEngine.java` (CRLF, préserver), supprimer

```java
    /** PluginEngine.load is not re-entrant: load the plugins once per JVM. */
    private static boolean pluginsLoaded;

```

et remplacer

```java
        if (!pluginsLoaded) {
            PluginEngine.load(pluginPath, devPluginPaths);
            pluginsLoaded = true;
        }
```

par

```java
        PluginEngine.load(pluginPath, devPluginPaths); // idempotent: only the first load of the JVM counts
```

- [ ] **Step 6: Run the tests**

Run: `mvn -o -q -pl copybot-engine test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git ls-files --eol copybot-engine/src/main/java/com/copybot/engine/plugin/PluginEngine.java copybot-engine/src/main/java/com/copybot/engine/CopybotEngine.java copybot-engine/src/main/resources/com/copybot/engine/i18n/engineBundle.properties copybot-engine/src/main/resources/com/copybot/engine/i18n/engineBundle_fr.properties
git add copybot-engine/src/main/java/com/copybot/engine/plugin/PluginEngine.java copybot-engine/src/main/java/com/copybot/engine/CopybotEngine.java copybot-engine/src/main/resources/com/copybot/engine/i18n/engineBundle.properties copybot-engine/src/main/resources/com/copybot/engine/i18n/engineBundle_fr.properties copybot-engine/src/test/java/com/copybot/engine/plugin/PluginEngineTest.java
git commit -m "Load the plugins once per JVM, thread-safely" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

(Les quatre fichiers : `w/crlf`.)

---

### Task 6: CopybotEngine en instance et Execution (fin de l'API statique)

**Files:**
- Create: `copybot-engine/src/main/java/com/copybot/engine/Execution.java`
- Modify (réécriture): `copybot-engine/src/main/java/com/copybot/engine/CopybotEngine.java` (CRLF)
- Modify (réécriture): `copybot-engine/src/main/java/com/copybot/Copybot.java` (CRLF)
- Modify: `copybot-ui/src/main/java/com/copybot/ui/CopybotMainUi.java`
- Modify: `copybot-ui/src/main/java/com/copybot/ui/HelloController.java`
- Test: `copybot-engine/src/test/java/com/copybot/engine/MainExecutorResumeTest.java`
- Test: `copybot-engine/src/test/java/com/copybot/engine/MainTest.java` (javadoc seulement)
- Create: `copybot-engine/src/test/java/com/copybot/engine/ExecutionTest.java`
- Create: `copybot-engine/src/test/java/com/copybot/engine/CopybotEngineTest.java`

**Interfaces:**
- Consumes: `MainExecutor.cancel()/pause()/resume()` (tâche 3), échecs capturés (tâche 4), `PluginEngine.load` idempotent (tâche 5), `ControlFakes` (tâche 3).
- Produces:
  - `public final class Execution` : `PipelineState getState()`, `PipelineStatus await() throws InterruptedException`, `boolean await(long timeout, TimeUnit unit) throws InterruptedException`, `boolean isDone()`, `void cancel()`, `void pause()`, `void resume()` ; package-private `Execution(MainExecutor)`, `void markDone()`.
  - `public final class CopybotEngine implements AutoCloseable` : `static CopybotEngine create(Optional<Path> configPath)`, `Plan prepare(Path pipelinePath, Consumer<PipelineState> watcher)`, `Execution execute(Plan plan, ResumePoint override)`, `Execution run(Path pipelinePath, Consumer<PipelineState> watcher)`, `void close()` ; pour les tests (package-private) : `CopybotEngine(CopybotConfig config)` (sans chargement de plugins), `Plan prepare(MainExecutor mainExecutor)`, `Execution submit(MainExecutor mainExecutor, Runnable task)`.
  - Toute opération pendant une autre ⇒ `IllegalStateException` (vérifiée **avant** de lire le pipeline) ; après `close()` ⇒ `IllegalStateException`. `readPipeline` lève `pipeline.not-found` pour un fichier illisible et `resume.mode.unknown` pour un mode non chaîne.
  - `public static CopybotEngine CopybotMainUi.ENGINE`.

- [ ] **Step 1: Write the failing tests**

`copybot-engine/src/test/java/com/copybot/engine/ExecutionTest.java` :

```java
package com.copybot.engine;

import com.copybot.engine.pipeline.PipelineStatus;
import com.copybot.engine.resources.ResourceRegistry;
import com.copybot.engine.resume.ResumeStateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.copybot.engine.ControlFakes.*;
import static org.junit.jupiter.api.Assertions.*;

/** The Execution returned by CopybotEngine.execute / run (spec engine-instance §3). */
public class ExecutionTest {

    @TempDir
    Path tempDir;

    private CopybotEngine engine;

    @BeforeEach
    public void createEngine() {
        engine = new CopybotEngine(config());
    }

    @AfterEach
    public void closeEngine() {
        engine.close();
    }

    private ResumeStateStore store() {
        return new ResumeStateStore(tempDir.resolve("p.state.json"));
    }

    @Test
    public void awaitReturnsTheFinalStatusOnceDone() throws Exception {
        MainExecutor pipeline = singlePhase(new DatedIn(tempDir, 2, null), new GatedOut(Set.of(), null),
                registry(Map.of("disk:*", 1000)));

        Execution execution = engine.submit(pipeline, pipeline::run);

        assertEquals(PipelineStatus.SUCCESS, execution.await());
        assertTrue(execution.isDone());
        assertEquals(2, execution.getState().getWorkItems().size());
    }

    @Test
    public void cancelDuringThePreparationOfARunEndsCancelled() throws Exception {
        BlockingIn listing = new BlockingIn();
        ResourceRegistry reg = registry(Map.of("disk:*", 1000));
        MainExecutor pipeline = withResume(listing, new GatedOut(Set.of(), null), reg, store());
        Execution execution = engine.submit(pipeline, pipeline::run);
        assertTrue(listing.started.await(5, TimeUnit.SECONDS));

        execution.cancel();

        assertEquals(PipelineStatus.CANCELLED, execution.await());
        assertTrue(allReleased(reg), "got " + reg.snapshot());
        assertFalse(Files.exists(store().getPath()), "a cancelled run writes no state");
    }

    @Test
    public void cancelDuringTheExecutionOfAPlanKeepsTheCursor() throws Exception {
        store().writeCursor(day(1));
        byte[] before = Files.readAllBytes(store().getPath());
        GatedOut out = new GatedOut(Set.of(), new CountDownLatch(1));
        ResourceRegistry reg = registry(Map.of("disk:*", 1000));
        Plan plan = engine.prepare(withResume(new DatedIn(tempDir, 3, null), out, reg, store()));
        assertEquals(PipelineStatus.PREPARED, plan.getState().getStatus());

        Execution execution = engine.execute(plan, null);
        assertTrue(out.firstEntered.await(5, TimeUnit.SECONDS));
        execution.cancel();

        assertEquals(PipelineStatus.CANCELLED, execution.await());
        assertTrue(allReleased(reg), "got " + reg.snapshot());
        assertArrayEquals(before, Files.readAllBytes(store().getPath()), "the cursor is unchanged");
    }

    @Test
    public void pauseAndResumeThroughTheExecution() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        GatedOut out = new GatedOut(Set.of("proc"), gate);
        ResourceRegistry reg = registry(Map.of("proc", 1, "disk:*", 1000));
        MainExecutor pipeline = singlePhase(new DatedIn(tempDir, 3, null), out, reg);
        Execution execution = engine.submit(pipeline, pipeline::run);
        assertTrue(out.firstEntered.await(5, TimeUnit.SECONDS));

        execution.pause();
        assertEquals(PipelineStatus.PAUSED, execution.getState().getStatus());
        gate.countDown();
        awaitTrue(() -> used(reg, "proc") == 0);
        Thread.sleep(200);
        assertEquals(1, out.started.get(), "no new step starts while paused");
        assertFalse(execution.isDone());

        execution.resume();
        assertEquals(PipelineStatus.SUCCESS, execution.await());
        assertEquals(3, out.started.get());
    }

    @Test
    public void cancelWhilePausedEndsCancelled() throws Exception {
        GatedOut out = new GatedOut(Set.of(), new CountDownLatch(1));
        ResourceRegistry reg = registry(Map.of("disk:*", 1000));
        MainExecutor pipeline = singlePhase(new DatedIn(tempDir, 2, null), out, reg);
        Execution execution = engine.submit(pipeline, pipeline::run);
        assertTrue(out.firstEntered.await(5, TimeUnit.SECONDS));
        execution.pause();

        execution.cancel();

        assertEquals(PipelineStatus.CANCELLED, execution.await());
        assertFalse(reg.isPaused(), "cancel lifts the pause");
    }

    @Test
    public void cancelIsIdempotentAndHasNoEffectOnceDone() throws Exception {
        MainExecutor pipeline = singlePhase(new DatedIn(tempDir, 1, null), new GatedOut(Set.of(), null),
                registry(Map.of("disk:*", 1000)));
        Execution execution = engine.submit(pipeline, pipeline::run);
        assertEquals(PipelineStatus.SUCCESS, execution.await());

        execution.cancel();
        execution.cancel();
        execution.pause();
        execution.resume();

        assertEquals(PipelineStatus.SUCCESS, execution.getState().getStatus());
        assertTrue(execution.await(1, TimeUnit.SECONDS));
    }
}
```

`copybot-engine/src/test/java/com/copybot/engine/CopybotEngineTest.java` :

```java
package com.copybot.engine;

import com.copybot.engine.pipeline.PipelineStatus;
import com.copybot.engine.plugin.PluginDefinition;
import com.copybot.engine.plugin.PluginEngine;
import com.copybot.engine.resources.ResourceRegistry;
import com.copybot.engine.resume.ResumeStateStore;
import com.copybot.exception.CopybotException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.copybot.engine.ControlFakes.*;
import static org.junit.jupiter.api.Assertions.*;

/** One engine instance, one operation at a time (spec engine-instance §1, §2, §5). */
public class CopybotEngineTest {

    /** Relative to the module directory, the working directory of the test JVM. */
    private static final Path CONFIG = Path.of("src", "test", "resources", "com", "copybot", "engine", "config.json");

    @TempDir
    Path tempDir;

    private Path dir(String name) throws IOException {
        return Files.createDirectories(tempDir.resolve(name));
    }

    private static String json(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }

    /** A real file.read -> out pipeline; extra is appended to the root object (e.g. a resume block). */
    private Path pipeline(String name, Path in, Path out, String outAction, String extra) throws IOException {
        return Files.writeString(tempDir.resolve(name), """
                {
                  "inSteps": [ { "action": "file.read", "actionConfig": { "path": "%s" } } ],
                  "outStep": { "action": "%s", "actionConfig": { "outPattern": "%s/{name}", "overwrite": false } }%s
                }
                """.formatted(json(in), outAction, json(out), extra));
    }

    @Test
    public void aSecondOperationWhileOneIsActiveIsRefused() throws Exception {
        try (CopybotEngine engine = new CopybotEngine(config())) {
            Plan plan = engine.prepare(withResume(new DatedIn(dir("a"), 1, null), new GatedOut(Set.of(), null),
                    registry(Map.of("disk:*", 1000)), new ResumeStateStore(tempDir.resolve("a.state.json"))));
            GatedOut blocking = new GatedOut(Set.of(), new CountDownLatch(1));
            MainExecutor busy = singlePhase(new DatedIn(dir("b"), 1, null), blocking, registry(Map.of("disk:*", 1000)));
            Execution running = engine.submit(busy, busy::run);
            assertTrue(blocking.firstEntered.await(5, TimeUnit.SECONDS));

            Path any = tempDir.resolve("any.json");
            assertThrows(IllegalStateException.class, () -> engine.run(any, null));
            assertThrows(IllegalStateException.class, () -> engine.prepare(any, null));
            assertThrows(IllegalStateException.class, () -> engine.execute(plan, null));

            running.cancel();
            assertEquals(PipelineStatus.CANCELLED, running.await());
            assertEquals(PipelineStatus.SUCCESS, engine.execute(plan, null).await(),
                    "once the operation is over the engine accepts the next one");
        }
    }

    @Test
    public void aBlockingPrepareIsTheActiveOperationAndCloseCancelsIt() throws Exception {
        CopybotEngine engine = new CopybotEngine(config());
        BlockingIn listing = new BlockingIn();
        MainExecutor preparing = withResume(listing, new GatedOut(Set.of(), null), registry(Map.of("disk:*", 1000)),
                new ResumeStateStore(tempDir.resolve("p.state.json")));
        AtomicReference<Plan> plan = new AtomicReference<>();
        Thread caller = Thread.ofVirtual().start(() -> plan.set(engine.prepare(preparing)));
        assertTrue(listing.started.await(5, TimeUnit.SECONDS));

        assertThrows(IllegalStateException.class, () -> engine.run(tempDir.resolve("any.json"), null),
                "prepare() counts as the active operation for its whole duration");

        engine.close();
        caller.join(TimeUnit.SECONDS.toMillis(20));

        assertFalse(caller.isAlive());
        assertEquals(PipelineStatus.CANCELLED, plan.get().getState().getStatus());
        assertThrows(IllegalStateException.class, () -> engine.run(tempDir.resolve("any.json"), null),
                "a closed engine accepts nothing");
    }

    @Test
    public void closeCancelsTheRunningExecutionAndWaitsForIt() throws Exception {
        GatedOut out = new GatedOut(Set.of(), new CountDownLatch(1));
        ResourceRegistry reg = registry(Map.of("disk:*", 1000));
        MainExecutor pipeline = singlePhase(new DatedIn(tempDir, 2, null), out, reg);
        CopybotEngine engine = new CopybotEngine(config());
        Execution execution = engine.submit(pipeline, pipeline::run);
        assertTrue(out.firstEntered.await(5, TimeUnit.SECONDS));

        engine.close();

        assertTrue(execution.isDone(), "close() waits for the cancelled execution");
        assertEquals(PipelineStatus.CANCELLED, execution.getState().getStatus());
        assertTrue(allReleased(reg), "got " + reg.snapshot());
        engine.close(); // idempotent
    }

    @Test
    public void twoSuccessiveInstancesInOneJvmShareThePluginsLoadedOnce() throws Exception {
        Path in = dir("in");
        Files.writeString(in.resolve("alpha.txt"), "alpha");
        List<PluginDefinition> loaded;
        try (CopybotEngine first = CopybotEngine.create(Optional.of(CONFIG))) {
            loaded = PluginEngine.getLoadedPlugins();
            assertEquals(PipelineStatus.SUCCESS,
                    first.run(pipeline("one.json", in, dir("out1"), "file.write", ""), null).await());
        }
        try (CopybotEngine second = CopybotEngine.create(Optional.of(CONFIG))) {
            assertSame(loaded, PluginEngine.getLoadedPlugins(), "the plugins are loaded once per JVM");
            assertEquals(PipelineStatus.SUCCESS,
                    second.run(pipeline("two.json", in, dir("out2"), "file.write", ""), null).await());
        }
        assertTrue(Files.exists(tempDir.resolve("out2").resolve("alpha.txt")));
    }

    @Test
    public void aPreparationFailureIsAPlanInErrorNotAnException() throws Exception {
        Path in = dir("in");
        Files.writeString(in.resolve("alpha.txt"), "alpha");
        try (CopybotEngine engine = CopybotEngine.create(Optional.of(CONFIG))) {
            Plan plan = engine.prepare(pipeline("p.json", in, dir("out"), "no.such.action",
                    ",\"resume\":{\"mode\":\"state\"}"), null);

            assertEquals(PipelineStatus.ERROR, plan.getState().getStatus());
            assertNotNull(plan.getState().getFailure(), "the cause of the failure is in the state");
        }
    }

    @Test
    public void errorsBeforeAPlanExistsAreThrownAndLeaveTheEngineUsable() throws Exception {
        Path in = dir("in");
        try (CopybotEngine engine = CopybotEngine.create(Optional.of(CONFIG))) {
            assertThrows(CopybotException.class, () -> engine.prepare(tempDir.resolve("missing.json"), null));

            Path numericMode = pipeline("n.json", in, dir("out"), "file.write", ",\"resume\":{\"mode\":1}");
            CopybotException unknown = assertThrows(CopybotException.class, () -> engine.prepare(numericMode, null));
            assertTrue(unknown.getMessage().contains("\"1\""), unknown.getMessage());

            Path notJson = Files.writeString(tempDir.resolve("bad.json"), "{ not json");
            assertThrows(CopybotException.class, () -> engine.run(notJson, null),
                    "run() reads the pipeline before starting; a refused operation never keeps the engine busy");
        }
    }
}
```

Dans `MainExecutorResumeTest` (migration hors API statique), remplacer

```java
        exec.prepare();

        assertThrows(IllegalStateException.class, () -> CopybotEngine.execute(new Plan(exec), null));
    }
```

par

```java
        exec.prepare();

        try (CopybotEngine engine = new CopybotEngine(new CopybotConfig(null, null, Map.of(), null))) {
            assertThrows(IllegalStateException.class, () -> engine.execute(new Plan(exec), null));
        }
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -o -q -pl copybot-engine test -Dtest='ExecutionTest,CopybotEngineTest,MainExecutorResumeTest'`
Expected: FAIL — compilation : `cannot find symbol: class Execution`, constructeur `CopybotEngine(CopybotConfig)` inexistant, `submit` / `prepare(MainExecutor)` inexistants.

- [ ] **Step 3: Create Execution**

`copybot-engine/src/main/java/com/copybot/engine/Execution.java` (LF) :

```java
package com.copybot.engine;

import com.copybot.engine.pipeline.PipelineState;
import com.copybot.engine.pipeline.PipelineStatus;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A pipeline running on a background thread of its {@link CopybotEngine}, returned by
 * {@link CopybotEngine#execute} and {@link CopybotEngine#run}. Every method is thread-safe.
 */
public final class Execution {

    private final MainExecutor executor;
    private final CountDownLatch done = new CountDownLatch(1);

    Execution(MainExecutor executor) {
        this.executor = executor;
    }

    /** The live, mutating state: read it, do not retain it. */
    public PipelineState getState() {
        return executor.getState();
    }

    /** Waits for the end of the execution and returns its final status (SUCCESS, ERROR or CANCELLED). */
    public PipelineStatus await() throws InterruptedException {
        done.await();
        return getState().getStatus();
    }

    /** @return true when the execution ended within the delay */
    public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
        return done.await(timeout, unit);
    }

    public boolean isDone() {
        return done.getCount() == 0;
    }

    /**
     * Clean stop: the current phase is interrupted, permits are released, the resume cursor is left
     * unchanged and the final status is CANCELLED. Lifts a pause first. Idempotent; no effect once done.
     */
    public void cancel() {
        executor.cancel();
    }

    /** No new step starts (status PAUSED) until {@link #resume()}; running steps finish normally. */
    public void pause() {
        executor.pause();
    }

    public void resume() {
        executor.resume();
    }

    void markDone() {
        done.countDown();
    }
}
```

- [ ] **Step 4: Rewrite CopybotEngine**

Réécrire `CopybotEngine.java` en entier, puis `unix2dos -q copybot-engine/src/main/java/com/copybot/engine/CopybotEngine.java` :

```java
package com.copybot.engine;

import com.copybot.config.CopybotConfig;
import com.copybot.engine.pipeline.PipelineConfig;
import com.copybot.engine.pipeline.PipelineState;
import com.copybot.engine.pipeline.PipelineStatus;
import com.copybot.engine.plugin.PluginEngine;
import com.copybot.engine.resources.ResourceRegistry;
import com.copybot.engine.resources.ResourceSettings;
import com.copybot.engine.resume.ResumeContext;
import com.copybot.engine.resume.ResumeMode;
import com.copybot.engine.resume.ResumePoint;
import com.copybot.engine.resume.ResumeStateStore;
import com.copybot.exception.CopybotException;
import com.copybot.utils.GsonUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * One Copybot engine: a configuration, the plugins and at most one pipeline operation at a time
 * ({@link #prepare}, {@link #execute} or {@link #run}); several pipelines in parallel need several
 * instances. Closing it cancels what is still running and releases its threads.
 *
 * <pre>{@code
 * try (CopybotEngine engine = CopybotEngine.create(Optional.of(configPath))) {
 *     Plan plan = engine.prepare(pipelinePath, watcher);
 *     Execution run = engine.execute(plan, null);
 *     PipelineStatus status = run.await();
 * }
 * }</pre>
 */
public final class CopybotEngine implements AutoCloseable {

    /** Best-effort grace period given to a cancelled pipeline to release its resources. */
    private static final long SHUTDOWN_AWAIT_SECONDS = 5;

    private static final Path DEFAULT_CONFIG_PATH = Path.of("./config.json");

    /** Used when the config declares no pluginPath; a missing directory simply loads no plugins. */
    private static final Path DEFAULT_PLUGIN_PATH = Path.of("./plugins");

    private final CopybotConfig config;

    /**
     * Runs the executions. Its virtual threads are <em>daemon</em> threads: {@link #close()} waits for
     * them, otherwise the JVM could exit while a file write is still streaming and leave a truncated file.
     */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private final Object lock = new Object();
    /** An operation (a blocking prepare or a running execution) holds the engine. Guarded by lock. */
    private boolean busy;
    /** The pipeline of the current operation, null while idle or while it is being built. Guarded by lock. */
    private MainExecutor active;
    /** Guarded by lock. */
    private boolean closed;

    // visible for tests: an engine on an in-memory configuration, the plugins left as they are
    CopybotEngine(CopybotConfig config) {
        this.config = config;
    }

    /**
     * Reads the configuration (default {@code ./config.json}), makes sure the plugins are loaded (once
     * per JVM) and creates the engine.
     *
     * @throws CopybotException config.not-found / config.not-json
     */
    public static CopybotEngine create(Optional<Path> configPath) {
        CopybotConfig config = readConfig(configPath.orElse(DEFAULT_CONFIG_PATH));
        loadPlugins(config);
        return new CopybotEngine(config);
    }

    /**
     * Lists and analyses the pipeline input and resolves the resume point, without writing anything.
     * Blocking, runs in the calling thread (call it outside the UI thread) and counts as the active
     * operation for its whole duration. A failure during the preparation is not thrown: the plan is ERROR
     * with the cause in {@code getState().getFailure()}.
     *
     * @throws IllegalStateException when another operation is active or the engine is closed
     * @throws CopybotException      when the pipeline file is missing or invalid, or its resume mode unknown
     */
    public Plan prepare(Path pipelinePath, Consumer<PipelineState> watcher) {
        begin();
        MainExecutor mainExecutor;
        try {
            PipelineConfig pipelineConfig = readPipeline(pipelinePath);
            mainExecutor = new MainExecutor(pipelineConfig, watcher, newRegistry(), resumeContext(pipelinePath, pipelineConfig));
        } catch (RuntimeException | Error e) {
            end();
            throw e;
        }
        return prepareBegun(mainExecutor);
    }

    // visible for tests: prepares a pipeline built from pre-resolved steps
    Plan prepare(MainExecutor mainExecutor) {
        begin();
        return prepareBegun(mainExecutor);
    }

    /**
     * Executes a prepared plan on a background thread.
     *
     * @param override resume point chosen by the user, null for the proposed one
     * @throws IllegalStateException when another operation is active, the engine is closed or the plan
     *                               is not PREPARED (checked before anything is submitted)
     */
    public Execution execute(Plan plan, ResumePoint override) {
        begin();
        try {
            if (plan.getState().getStatus() != PipelineStatus.PREPARED) {
                throw new IllegalStateException("Pipeline is not prepared: " + plan.getState().getStatus());
            }
            MainExecutor mainExecutor = plan.getExecutor();
            return start(mainExecutor, () -> mainExecutor.execute(override));
        } catch (RuntimeException | Error e) {
            end();
            throw e;
        }
    }

    /**
     * Prepares then executes the pipeline on a background thread (a single phase when the pipeline has
     * no resume block). Failures end in the final state, never as an exception of the background thread.
     *
     * @param watcher optional progress observer, invoked from a background thread, at most ~10 times
     *                per second (notifications are coalesced, so the observer sees the latest state
     *                rather than every transition) plus one terminal notification at the end of each
     *                phase. Exceptions it throws are swallowed.
     * @throws IllegalStateException when another operation is active or the engine is closed
     * @throws CopybotException      when the pipeline file is missing or invalid, or its resume mode unknown
     */
    public Execution run(Path pipelinePath, Consumer<PipelineState> watcher) {
        begin();
        try {
            PipelineConfig pipelineConfig = readPipeline(pipelinePath);
            ResumeContext resume = pipelineConfig.resumeMode() == ResumeMode.NONE
                    ? null
                    : resumeContext(pipelinePath, pipelineConfig);
            MainExecutor mainExecutor = new MainExecutor(pipelineConfig, watcher, newRegistry(), resume);
            return start(mainExecutor, mainExecutor::run);
        } catch (RuntimeException | Error e) {
            end();
            throw e;
        }
    }

    // visible for tests: runs a pipeline built from pre-resolved steps
    Execution submit(MainExecutor mainExecutor, Runnable task) {
        begin();
        try {
            return start(mainExecutor, task);
        } catch (RuntimeException | Error e) {
            end();
            throw e;
        }
    }

    /**
     * Cancels the active operation, waits for it (grace period of {@value #SHUTDOWN_AWAIT_SECONDS} s),
     * then stops the executor. Idempotent. Afterwards every operation is refused.
     */
    @Override
    public void close() {
        MainExecutor toCancel;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            toCancel = active;
        }
        if (toCancel != null) {
            toCancel.cancel();
        }
        boolean interrupted = false;
        try {
            awaitIdle(TimeUnit.SECONDS.toMillis(SHUTDOWN_AWAIT_SECONDS));
        } catch (InterruptedException e) {
            interrupted = true;
        }
        executor.shutdownNow();
        try {
            executor.awaitTermination(SHUTDOWN_AWAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            interrupted = true;
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- one operation at a time ----

    private void begin() {
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("Engine is closed");
            }
            if (busy) {
                throw new IllegalStateException("An operation is already running on this engine (one pipeline at a time)");
            }
            busy = true;
        }
    }

    /** Publishes the pipeline of the current operation, so that close() can cancel it. */
    private void track(MainExecutor mainExecutor) {
        boolean closing;
        synchronized (lock) {
            active = mainExecutor;
            closing = closed;
        }
        if (closing) {
            mainExecutor.cancel(); // closed while the operation was being set up
        }
    }

    private void end() {
        synchronized (lock) {
            busy = false;
            active = null;
            lock.notifyAll();
        }
    }

    private void awaitIdle(long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        synchronized (lock) {
            while (busy) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    return;
                }
                lock.wait(left);
            }
        }
    }

    private Plan prepareBegun(MainExecutor mainExecutor) {
        try {
            track(mainExecutor);
            mainExecutor.prepare();
            return new Plan(mainExecutor);
        } finally {
            end();
        }
    }

    /** Runs the task on the engine executor; begin() was called, end() is called when the task is over. */
    private Execution start(MainExecutor mainExecutor, Runnable task) {
        Execution execution = new Execution(mainExecutor);
        track(mainExecutor);
        executor.submit(() -> {
            try {
                task.run();
            } catch (RuntimeException | Error e) {
                // MainExecutor reports pipeline failures in its state; this is only a safety net
                if (mainExecutor.getState().getFailure() == null) {
                    mainExecutor.getState().setFailure(e);
                }
                mainExecutor.getState().setStatus(PipelineStatus.ERROR);
            } finally {
                end(); // before markDone(): once await() returns the engine accepts the next operation
                execution.markDone();
            }
        });
        return execution;
    }

    // ---- reading ----

    private static CopybotConfig readConfig(Path configPath) {
        if (!configPath.toFile().canRead()) {
            throw CopybotException.ofResource("config.not-found", configPath.toAbsolutePath());
        }
        CopybotConfig config;
        try {
            config = GsonUtil.getGson().fromJson(Files.readString(configPath), CopybotConfig.class);
        } catch (IOException | JsonSyntaxException e) {
            throw CopybotException.ofResource(e, "config.not-json", configPath);
        }
        if (config == null) { // empty file
            throw CopybotException.ofResource("config.not-json", configPath);
        }
        return config;
    }

    private static void loadPlugins(CopybotConfig config) {
        Path pluginPath = config.pluginPath() != null ? config.pluginPath() : DEFAULT_PLUGIN_PATH;
        // a configured-but-missing dev directory must not break startup: dev paths reach
        // ModuleFinder directly, which throws on nonexistent paths (the main pluginPath is
        // directory-listed first and tolerates absence)
        List<Path> devPluginPaths = config.devPluginPaths() != null && Files.isDirectory(config.devPluginPaths())
                ? List.of(config.devPluginPaths())
                : List.of();
        PluginEngine.load(pluginPath, devPluginPaths); // idempotent: only the first load of the JVM counts
    }

    private ResourceRegistry newRegistry() {
        return new ResourceRegistry(ResourceSettings.from(config));
    }

    private static PipelineConfig readPipeline(Path pipelinePath) {
        if (!Files.isReadable(pipelinePath)) {
            throw CopybotException.ofResource("pipeline.not-found", pipelinePath);
        }
        JsonElement tree;
        PipelineConfig pipelineConfig;
        try (var reader = Files.newBufferedReader(pipelinePath)) {
            tree = JsonParser.parseReader(reader);
            pipelineConfig = GsonUtil.getGson().fromJson(tree, PipelineConfig.class);
        } catch (IOException | JsonParseException e) {
            throw CopybotException.ofResource(e, "pipeline.not-json", pipelinePath);
        }
        if (pipelineConfig == null) { // empty file
            throw CopybotException.ofResource("pipeline.not-json", pipelinePath);
        }
        checkResumeMode(tree, pipelineConfig);
        return pipelineConfig;
    }

    /**
     * Gson maps an unknown enum name, or a value that is not a name at all (e.g. {@code "mode": 1}),
     * to null, which would silently mean the default mode.
     */
    private static void checkResumeMode(JsonElement tree, PipelineConfig pipelineConfig) {
        if (pipelineConfig.resume() == null || pipelineConfig.resume().mode() != null) {
            return;
        }
        JsonElement resume = tree.getAsJsonObject().get("resume");
        JsonElement mode = resume != null && resume.isJsonObject() ? resume.getAsJsonObject().get("mode") : null;
        if (mode != null && !mode.isJsonNull()) {
            throw CopybotException.ofResource("resume.mode.unknown",
                    mode.isJsonPrimitive() ? mode.getAsString() : mode.toString());
        }
    }

    private static ResumeContext resumeContext(Path pipelinePath, PipelineConfig pipelineConfig) {
        return new ResumeContext(pipelineConfig.resumeMode(), ResumeStateStore.forPipeline(pipelinePath));
    }
}
```

- [ ] **Step 5: Migrate the CLI (behaviour unchanged, exit codes in task 7)**

Réécrire `Copybot.java` en entier, puis `unix2dos -q copybot-engine/src/main/java/com/copybot/Copybot.java` :

```java
package com.copybot;

import com.copybot.engine.CopybotEngine;
import com.copybot.engine.Execution;
import com.copybot.engine.Plan;
import com.copybot.engine.pipeline.ItemStatus;
import com.copybot.engine.pipeline.PipelineState;
import com.copybot.engine.pipeline.PipelineStatus;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.engine.resume.ItemKey;
import com.copybot.engine.resume.ResumePoint;
import com.copybot.exception.CopybotException;
import com.copybot.logger.CopybotLogger;
import picocli.CommandLine;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Optional;

@CommandLine.Command(name = "Copybot", version = "0.1", mixinStandardHelpOptions = true)
public class Copybot implements Runnable {

    private static final CopybotLogger LOG = CopybotLogger.getLogger(Copybot.class);

    @CommandLine.Option(names = {"-c", "--config-file"}, description = "Configuration file")
    private Path configPath;

    @CommandLine.Option(names = {"-p", "--pipeline"}, description = "Pipeline file", required = true)
    private Path pipelinePath;

    @CommandLine.Option(names = {"-dr", "--dry-run"}, description = "Dry run")
    private boolean isDryRun;

    @CommandLine.Option(names = {"--debug"}, description = "Debug")
    private boolean isDebug;

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

    @Override
    public void run() {
        try {
            doRun();
        } catch (Exception e) {
            if (isDebug) {
                throw CopybotException.wrapIfNeeded(e);
            } else {
                System.err.println(e.getMessage());
            }
        }
    }

    public void doRun() {
        try (CopybotEngine engine = CopybotEngine.create(Optional.ofNullable(configPath))) {
            Execution execution;
            if (isDryRun || resumeOverride != null) {
                Plan plan = engine.prepare(pipelinePath, null);
                if (plan.getState().getStatus() != PipelineStatus.PREPARED) {
                    if (plan.getState().getFailure() != null) {
                        System.err.println(message(plan.getState().getFailure()));
                    }
                    throw CopybotException.ofResource("pipeline.prepare-failed");
                }
                ResumePoint override = resolveOverride(plan);
                if (isDryRun) {
                    plan.preview(override);
                    PlanPrinter.print(plan, override, System.out);
                    return;
                }
                execution = engine.execute(plan, override);
            } else {
                execution = engine.run(pipelinePath, null);
            }
            if (awaitExecution(execution)) {
                report(execution.getState());
            }
        }
    }

    /** Reports on stderr the pipeline failure (listing, cursor write...) and every item in error. */
    private static void report(PipelineState state) {
        if (state.getFailure() != null) {
            System.err.println(message(state.getFailure()));
        }
        for (WorkItemExecution item : state.getWorkItems()) {
            if (item.getStatus() == ItemStatus.ERROR) {
                System.err.println("ERROR " + name(item) + "  " + message(item.getError()));
            }
        }
    }

    /** The item name as listed (the resume key name), even when a later step replaced the work item. */
    static String name(WorkItemExecution item) {
        return item.getResumeKey().map(ItemKey::name).orElseGet(() -> item.getWorkItem().getNameDisplay());
    }

    static String message(Throwable t) {
        if (t == null) {
            return "";
        }
        return t.getMessage() != null ? t.getMessage() : t.getClass().getName();
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

    /** @return false when the wait was interrupted (the execution is then cancelled) */
    private static boolean awaitExecution(Execution execution) {
        try {
            execution.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            execution.cancel();
            System.out.println("Cancelled !");
            return false;
        }
        System.out.println("Done !");
        return true;
    }


    public static void main(String... args) {
        int exitCode = doMain(args);
        System.exit(exitCode);
    }

    public static int doMain(String... args) {
        return new CommandLine(new Copybot()).execute(args);
    }
}
```

(La vérification `pipeline.not-found` de la CLI disparaît : `CopybotEngine.readPipeline` la fait désormais, pour la CLI comme pour l'UI.)

- [ ] **Step 6: Migrate the UI (spec §7)**

Dans `copybot-ui/src/main/java/com/copybot/ui/CopybotMainUi.java` (LF) :

- remplacer

```java
    public static Stage STAGE;
```

par

```java
    public static Stage STAGE;

    /** The engine of this window: created at startup, closed on exit (one pipeline at a time). */
    public static CopybotEngine ENGINE;
```

- remplacer `            CopybotEngine.init(pathArg);` par `            ENGINE = CopybotEngine.create(pathArg);`
- remplacer

```java
        CopybotEngine.destroy();
```

par

```java
        if (ENGINE != null) {
            ENGINE.close(); // cancels a running copy and waits for it to release its files
        }
```

Dans `copybot-ui/src/main/java/com/copybot/ui/HelloController.java` (LF) :

- supprimer la ligne `import com.copybot.engine.CopybotEngine;`
- remplacer `        Platform.exit(); // triggers Application.stop(): executor shutdown + engine destroy` par `        Platform.exit(); // triggers Application.stop(): executor shutdown + engine close`
- remplacer `            CopybotEngine.run(TEST_PIPELINE, state -> {` par `            CopybotMainUi.ENGINE.run(TEST_PIPELINE, state -> {`
- remplacer `            // e.g. "Engine already running" or unreadable pipeline file` par `            // e.g. an operation already running on the engine, or an unreadable pipeline file`

- [ ] **Step 7: Fix the stale MainTest javadoc (spec §8)**

Dans `copybot-engine/src/test/java/com/copybot/engine/MainTest.java`, remplacer

```java
 * <p>Ordering matters: {@code testMain} is what loads the plugins (through
 * {@code CopybotEngine.init}), and {@code PluginEngine.load} may only run once per JVM.
 */
```

par

```java
 * <p>Ordering matters: {@code outStepResolvesThroughThePluginEngine} relies on the plugins loaded by
 * {@code testMain} (through {@code CopybotEngine.create}). {@code PluginEngine.load} itself is
 * idempotent: whichever test class loads the plugins first, later loads are ignored.
 */
```

- [ ] **Step 8: Run the tests and compile the UI**

Run: `mvn -o -q -pl copybot-engine test`
Expected: PASS (dont `ExecutionTest` et `CopybotEngineTest`, et sans changement d'assertion `ResumeEndToEndTest` / `MainTest`).

Run: `mvn -o -q -pl copybot-engine,copybot-ui compile`
Expected: pas de `[ERROR]`.

Run: `grep -rn "CopybotEngine\.\(init\|run\|prepare\|execute\|waitForCompletion\|destroy\)" --include=*.java .`
Expected: aucune ligne (l'API statique a disparu partout).

- [ ] **Step 9: Commit**

```bash
git ls-files --eol copybot-engine/src/main/java/com/copybot/engine/CopybotEngine.java copybot-engine/src/main/java/com/copybot/Copybot.java copybot-engine/src/main/java/com/copybot/engine/Execution.java copybot-ui/src/main/java/com/copybot/ui/CopybotMainUi.java copybot-ui/src/main/java/com/copybot/ui/HelloController.java
git add copybot-engine/src/main/java/com/copybot/engine/Execution.java copybot-engine/src/main/java/com/copybot/engine/CopybotEngine.java copybot-engine/src/main/java/com/copybot/Copybot.java copybot-ui/src/main/java/com/copybot/ui/CopybotMainUi.java copybot-ui/src/main/java/com/copybot/ui/HelloController.java copybot-engine/src/test/java/com/copybot/engine/MainExecutorResumeTest.java copybot-engine/src/test/java/com/copybot/engine/MainTest.java copybot-engine/src/test/java/com/copybot/engine/ExecutionTest.java copybot-engine/src/test/java/com/copybot/engine/CopybotEngineTest.java
git commit -m "Turn CopybotEngine into an instance returning cancellable executions" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

(`CopybotEngine.java`, `Copybot.java` : `w/crlf` ; les autres : `w/lf`.)

---

### Task 7: Codes de sortie de la CLI et hook Ctrl+C

**Files:**
- Modify (réécriture): `copybot-engine/src/main/java/com/copybot/Copybot.java` (CRLF)
- Create: `copybot-engine/src/test/java/com/copybot/CopybotExitCodeTest.java`
- Test: `copybot-engine/src/test/java/com/copybot/engine/ResumeEndToEndTest.java`

**Interfaces:**
- Consumes: `CopybotEngine.create/prepare/execute/run`, `Execution.await()/await(long, TimeUnit)/cancel()/getState()` (tâche 6).
- Produces: `public class Copybot implements Callable<Integer>` ; constantes `EXIT_SUCCESS = 0`, `EXIT_FAILED = 1`, `EXIT_FATAL = 2`, `EXIT_CANCELLED = 130` ; package-private `Copybot(Consumer<Execution> onExecutionStarted)` (appelé avec chaque exécution dès son démarrage — annulation programmatique en test) ; package-private `static void cancelAndWait(Execution execution)` (corps du hook Ctrl+C : `cancel()` puis attente 5 s max) ; `doMain(String...)` renvoie le code.

- [ ] **Step 1: Write the failing tests**

`copybot-engine/src/test/java/com/copybot/CopybotExitCodeTest.java` :

```java
package com.copybot;

import com.copybot.engine.CopybotEngine;
import com.copybot.engine.Execution;
import com.copybot.engine.pipeline.PipelineStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** One test per CLI exit code (spec engine-instance §6). */
public class CopybotExitCodeTest {

    private static final String CONFIG = "-c=./src/test/resources/com/copybot/engine/config.json";
    private static final String STATE = ",\"resume\":{\"mode\":\"state\"}";
    /** A resource nobody can ever get: only a cancellation ends a run whose out step needs it. */
    private static final String BLOCKED_OUT = ", \"resources\": [\"blocked\"]";

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

    /** file.read -> file.write; extraOut is appended to the out step, extra to the root object. */
    private Path pipeline(String extraOut, String extra) throws IOException {
        return Files.writeString(tempDir.resolve("sd.json"), """
                {
                  "inSteps": [ { "action": "file.read", "actionConfig": { "path": "%s" } } ],
                  "outStep": { "action": "file.write", "actionConfig": { "outPattern": "%s/{name}", "overwrite": false }%s }%s
                }
                """.formatted(json(card), json(nas), extraOut, extra));
    }

    private Path blockedConfig() throws IOException {
        return Files.writeString(tempDir.resolve("blocked-config.json"), "{ \"resources\": { \"blocked\": 0 } }");
    }

    /** Runs the CLI capturing stdout and stderr; returns {exitCode, stdout, stderr}. */
    private static String[] cli(Consumer<Execution> onExecutionStarted, String... args) {
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true));
            System.setErr(new PrintStream(err, true));
            int code = new CommandLine(new Copybot(onExecutionStarted)).execute(args);
            return new String[]{String.valueOf(code), out.toString(), err.toString()};
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }

    private static String[] cli(String... args) {
        return cli(execution -> {
        }, args);
    }

    @Test
    public void aSuccessfulRunExitsZero() throws IOException {
        String[] result = cli("-p=" + pipeline("", ""), CONFIG);

        assertEquals("0", result[0], result[2]);
        assertTrue(Files.exists(nas.resolve("IMG_02.JPG")));
    }

    @Test
    public void aDryRunPreparedWithoutErrorExitsZero() throws IOException {
        String[] result = cli("-p=" + pipeline("", STATE), CONFIG, "--dry-run");

        assertEquals("0", result[0], result[2]);
        assertFalse(Files.exists(nas));
    }

    @Test
    public void anItemInErrorExitsOne() throws IOException {
        Files.createDirectories(nas);
        Files.writeString(nas.resolve("IMG_01.JPG"), "already there"); // overwrite=false: this item fails

        String[] result = cli("-p=" + pipeline("", ""), CONFIG);

        assertEquals("1", result[0]);
        assertTrue(result[2].contains("ERROR IMG_01.JPG"), result[2]);
        assertTrue(Files.exists(nas.resolve("IMG_02.JPG")), "the other items are still copied");
    }

    @Test
    public void aCursorWriteFailureExitsOne() throws IOException {
        Files.createDirectories(tempDir.resolve("sd.state.json.tmp")); // the temporary state file cannot be written

        String[] result = cli("-p=" + pipeline("", STATE), CONFIG);

        assertEquals("1", result[0]);
        assertTrue(result[2].contains("sd.state.json"), result[2]);
    }

    @Test
    public void aMissingConfigExitsTwo() throws IOException {
        String[] result = cli("-p=" + pipeline("", ""), "-c=" + tempDir.resolve("nope.json"));

        assertEquals("2", result[0]);
        assertTrue(result[2].contains("nope.json"), result[2]);
    }

    @Test
    public void aMissingPipelineExitsTwoWithOrWithoutDebug() {
        String missing = "-p=" + tempDir.resolve("missing.json");

        assertEquals("2", cli(missing, CONFIG)[0]);
        String[] debug = cli(missing, CONFIG, "--debug");
        assertEquals("2", debug[0], "--debug prints the stacktrace but keeps the exit code");
        assertTrue(debug[2].contains("missing.json"), debug[2]);
    }

    @Test
    public void aPreparationInErrorExitsTwo() throws IOException {
        Path pipeline = pipeline("", STATE);
        Files.writeString(tempDir.resolve("sd.state.json"), "not json");

        String[] result = cli("-p=" + pipeline, CONFIG, "--dry-run");

        assertEquals("2", result[0]);
        assertTrue(result[2].contains("sd.state.json"), result[2]);
    }

    @Test
    public void anUnknownFromFileExitsTwo() throws IOException {
        String[] result = cli("-p=" + pipeline("", STATE), CONFIG, "--from-file=NOPE.JPG");

        assertEquals("2", result[0]);
        assertFalse(Files.exists(nas));
    }

    @Test
    public void invalidOptionsExitTwo() throws IOException {
        assertEquals("2", cli("-p=" + pipeline("", STATE), CONFIG, "--all", "--from-date=2026-09-02")[0]);
    }

    @Test
    public void aCancelledExecutionExitsOneHundredThirty() throws IOException {
        String[] result = cli(Execution::cancel, "-p=" + pipeline(BLOCKED_OUT, ""), "-c=" + blockedConfig());

        assertEquals("130", result[0], result[2]);
        assertTrue(result[1].contains("Cancelled !"), result[1]);
        assertFalse(Files.exists(nas.resolve("IMG_01.JPG")), "nothing is written");
    }

    @Test
    public void theCtrlCHookCancelsTheExecutionAndWaitsForIt() throws Exception {
        try (CopybotEngine engine = CopybotEngine.create(Optional.of(blockedConfig()))) {
            Execution execution = engine.run(pipeline(BLOCKED_OUT, ""), null);

            Copybot.cancelAndWait(execution);

            assertTrue(execution.isDone(), "the hook waits for the cancelled execution");
            assertEquals(PipelineStatus.CANCELLED, execution.getState().getStatus());
        }
    }
}
```

Dans `ResumeEndToEndTest.allReimportsEverything`, modification **autorisée par la spec §9** (« `--all` avec un item en échec attend désormais le code 1 ») — remplacer

```java
        // IMG_02.JPG still exists and overwrite=false: that item fails, which does not stop IMG_01
        assertEquals(0, cli(pipeline, "--all"));
```

par

```java
        // IMG_02.JPG still exists and overwrite=false: that item fails (exit code 1), which does not stop IMG_01
        assertEquals(1, cli(pipeline, "--all"));
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -o -q -pl copybot-engine test -Dtest='CopybotExitCodeTest,ResumeEndToEndTest'`
Expected: FAIL — compilation : constructeur `Copybot(Consumer<Execution>)` et `cancelAndWait` inexistants.

- [ ] **Step 3: Implement**

Réécrire `Copybot.java` en entier, puis `unix2dos -q copybot-engine/src/main/java/com/copybot/Copybot.java` :

```java
package com.copybot;

import com.copybot.engine.CopybotEngine;
import com.copybot.engine.Execution;
import com.copybot.engine.Plan;
import com.copybot.engine.pipeline.ItemStatus;
import com.copybot.engine.pipeline.PipelineState;
import com.copybot.engine.pipeline.PipelineStatus;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.engine.resume.ItemKey;
import com.copybot.engine.resume.ResumePoint;
import com.copybot.exception.CopybotException;
import com.copybot.logger.CopybotLogger;
import picocli.CommandLine;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@CommandLine.Command(name = "Copybot", version = "0.1", mixinStandardHelpOptions = true)
public class Copybot implements Callable<Integer> {

    /** SUCCESS (skipped items are not errors), or a dry run prepared without error. */
    public static final int EXIT_SUCCESS = 0;
    /** The execution ended in ERROR: items in error, cursor not written, listing failure. */
    public static final int EXIT_FAILED = 1;
    /**
     * Fatal: config or pipeline missing or invalid, preparation in ERROR, unknown --from-file.
     * Invalid options end with the same code (picocli's usage error code).
     */
    public static final int EXIT_FATAL = 2;
    /** CANCELLED (Ctrl+C). */
    public static final int EXIT_CANCELLED = 130;

    /** Grace period the Ctrl+C hook gives the cancelled execution to release its files. */
    private static final long CANCEL_AWAIT_SECONDS = 5;

    private static final CopybotLogger LOG = CopybotLogger.getLogger(Copybot.class);

    @CommandLine.Option(names = {"-c", "--config-file"}, description = "Configuration file")
    private Path configPath;

    @CommandLine.Option(names = {"-p", "--pipeline"}, description = "Pipeline file", required = true)
    private Path pipelinePath;

    @CommandLine.Option(names = {"-dr", "--dry-run"}, description = "Dry run")
    private boolean isDryRun;

    @CommandLine.Option(names = {"--debug"}, description = "Debug: print the stacktraces (the exit code is unchanged)")
    private boolean isDebug;

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

    /** Receives each execution as soon as it has started (visible for tests: programmatic cancellation). */
    private final Consumer<Execution> onExecutionStarted;

    public Copybot() {
        this(execution -> {
        });
    }

    Copybot(Consumer<Execution> onExecutionStarted) {
        this.onExecutionStarted = onExecutionStarted;
    }

    @Override
    public Integer call() {
        try (CopybotEngine engine = CopybotEngine.create(Optional.ofNullable(configPath))) {
            return doRun(engine);
        } catch (InterruptedException e) {
            // the engine is closed by the try-with-resources, which cancels the execution
            Thread.currentThread().interrupt();
            System.out.println("Cancelled !");
            return EXIT_CANCELLED;
        } catch (RuntimeException e) {
            System.err.println(message(e));
            printStackTraceIfDebug(e);
            return EXIT_FATAL;
        }
    }

    private int doRun(CopybotEngine engine) throws InterruptedException {
        Execution execution;
        if (isDryRun || resumeOverride != null) {
            Plan plan = engine.prepare(pipelinePath, null);
            if (plan.getState().getStatus() != PipelineStatus.PREPARED) {
                Throwable failure = plan.getState().getFailure();
                if (failure != null) {
                    System.err.println(message(failure));
                    printStackTraceIfDebug(failure);
                }
                throw CopybotException.ofResource("pipeline.prepare-failed");
            }
            ResumePoint override = resolveOverride(plan);
            if (isDryRun) {
                plan.preview(override);
                PlanPrinter.print(plan, override, System.out);
                return EXIT_SUCCESS;
            }
            execution = engine.execute(plan, override);
        } else {
            execution = engine.run(pipelinePath, null);
        }
        onExecutionStarted.accept(execution);
        PipelineStatus status = awaitCancellingOnCtrlC(execution);
        if (status == PipelineStatus.CANCELLED) {
            System.out.println("Cancelled !");
            return EXIT_CANCELLED;
        }
        System.out.println("Done !");
        report(execution.getState());
        return status == PipelineStatus.SUCCESS ? EXIT_SUCCESS : EXIT_FAILED;
    }

    /**
     * Waits for the execution. Meanwhile a JVM shutdown (Ctrl+C) cancels it and gives it a few seconds
     * to release its files; the hook is removed once the execution is over. A preparation (blocking,
     * nothing written) is not covered: Ctrl+C simply stops it with the JVM.
     */
    private static PipelineStatus awaitCancellingOnCtrlC(Execution execution) throws InterruptedException {
        Thread hook = new Thread(() -> cancelAndWait(execution), "copybot-cancel-on-exit");
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            return execution.await();
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException e) {
                // the JVM is already shutting down: the hook is running and must be left alone
            }
        }
    }

    /** The Ctrl+C hook: cancels the execution and waits for it, {@value #CANCEL_AWAIT_SECONDS} s at most. */
    static void cancelAndWait(Execution execution) {
        execution.cancel();
        try {
            execution.await(CANCEL_AWAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Reports on stderr the pipeline failure (listing, cursor write...) and every item in error. */
    private void report(PipelineState state) {
        if (state.getFailure() != null) {
            System.err.println(message(state.getFailure()));
            printStackTraceIfDebug(state.getFailure());
        }
        for (WorkItemExecution item : state.getWorkItems()) {
            if (item.getStatus() == ItemStatus.ERROR) {
                System.err.println("ERROR " + name(item) + "  " + message(item.getError()));
            }
        }
    }

    private void printStackTraceIfDebug(Throwable t) {
        if (isDebug) {
            t.printStackTrace();
        }
    }

    /** The item name as listed (the resume key name), even when a later step replaced the work item. */
    static String name(WorkItemExecution item) {
        return item.getResumeKey().map(ItemKey::name).orElseGet(() -> item.getWorkItem().getNameDisplay());
    }

    static String message(Throwable t) {
        if (t == null) {
            return "";
        }
        return t.getMessage() != null ? t.getMessage() : t.getClass().getName();
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


    public static void main(String... args) {
        int exitCode = doMain(args);
        System.exit(exitCode);
    }

    public static int doMain(String... args) {
        return new CommandLine(new Copybot()).execute(args);
    }
}
```

Note : dans le chemin par défaut (`run`, sans `--dry-run` ni option de reprise), une préparation qui échoue au sein de `run` n'est pas distinguée d'une exécution en échec : le statut final est `ERROR` ⇒ code `1` (la ligne « 1 » de la spec y range l'échec de listing). Le code `2` « préparation en `ERROR` » s'applique au chemin `prepare` explicite (`--dry-run`, `--from-file`, `--from-date`, `--all`).

- [ ] **Step 4: Run the tests**

Run: `mvn -o -q -pl copybot-engine test`
Expected: PASS (dont les 11 tests de `CopybotExitCodeTest` ; `MainTest.testMain` attend toujours `0`).

- [ ] **Step 5: Commit**

```bash
git ls-files --eol copybot-engine/src/main/java/com/copybot/Copybot.java
git add copybot-engine/src/main/java/com/copybot/Copybot.java copybot-engine/src/test/java/com/copybot/CopybotExitCodeTest.java copybot-engine/src/test/java/com/copybot/engine/ResumeEndToEndTest.java
git commit -m "Return meaningful CLI exit codes and cancel on Ctrl+C" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

(`Copybot.java` : `w/crlf`.)

---

### Task 8: Reliquats du chantier reprise (spec §8) et vérification finale

**Files:**
- Modify: `copybot-engine/src/main/java/com/copybot/engine/pipeline/PipelineState.java` (CRLF)
- Modify: `copybot-engine/src/main/java/com/copybot/engine/MainExecutor.java`
- Modify: `copybot-engine/src/main/java/com/copybot/engine/pipeline/WorkItemExecution.java`
- Test: `copybot-engine/src/test/java/com/copybot/engine/pipeline/PipelineStateTest.java`
- Test: `copybot-engine/src/test/java/com/copybot/engine/pipeline/WorkItemExecutionTest.java`

(La javadoc périmée de `MainTest` — autre reliquat §8 — est corrigée dès la tâche 6, qui supprime le `CopybotEngine.init` qu'elle cite.)

**Interfaces:**
- Consumes: `PipelineState` de la tâche 3, `MainExecutor` des tâches 3–4.
- Produces: `public boolean PipelineState.recordFailureIfAbsent(Throwable failure)` (compareAndSet : le premier gagne, renvoie `true` s'il a été enregistré) ; `WorkItemExecution.setResumeKey(ItemKey)` figée au premier appel (y compris `null`), les appels suivants sont **ignorés**.

- [ ] **Step 1: Write the failing tests**

Dans `PipelineStateTest`, ajouter les imports

```java
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
```

puis les tests :

```java
    @Test
    public void onlyTheFirstRecordedFailureIsKept() {
        PipelineState state = new PipelineState(List.of());
        IllegalStateException first = new IllegalStateException("first");

        assertTrue(state.recordFailureIfAbsent(first));
        assertFalse(state.recordFailureIfAbsent(new IllegalStateException("second")));

        assertSame(first, state.getFailure());
    }

    @Test
    public void concurrentFailuresRecordExactlyOne() throws Exception {
        PipelineState state = new PipelineState(List.of());
        AtomicInteger recorded = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            IllegalStateException failure = new IllegalStateException("listing " + i);
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (state.recordFailureIfAbsent(failure)) {
                    recorded.incrementAndGet();
                }
            }));
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join(5000);
        }

        assertEquals(1, recorded.get(), "exactly one listing failure wins");
        assertNotNull(state.getFailure());
    }
```

Dans `WorkItemExecutionTest`, ajouter les imports

```java
import com.copybot.engine.resume.ItemKey;

import java.time.Instant;
import java.util.Optional;
```

puis les tests :

```java
    @Test
    public void theResumeKeyIsFrozenByTheFirstCall() throws IOException {
        WorkItemExecution exec = newExecution();
        ItemKey first = new ItemKey(Instant.parse("2026-09-01T10:00:00Z"), "a.jpg");

        exec.setResumeKey(first);
        exec.setResumeKey(new ItemKey(Instant.parse("2026-09-02T10:00:00Z"), "b.jpg"));

        assertEquals(Optional.of(first), exec.getResumeKey(), "a second call is ignored");
    }

    @Test
    public void aResumeKeyFrozenToNoneStaysEmpty() throws IOException {
        WorkItemExecution exec = newExecution();

        exec.setResumeKey(null); // an item without date
        exec.setResumeKey(new ItemKey(Instant.parse("2026-09-02T10:00:00Z"), "b.jpg"));

        assertTrue(exec.getResumeKey().isEmpty());
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -o -q -pl copybot-engine test -Dtest='PipelineStateTest,WorkItemExecutionTest'`
Expected: FAIL — compilation `cannot find symbol: recordFailureIfAbsent` (après l'avoir ajouté, `theResumeKeyIsFrozenByTheFirstCall` et `aResumeKeyFrozenToNoneStaysEmpty` échouent sur la valeur).

- [ ] **Step 3: Implement PipelineState (atomic first failure)**

Dans `PipelineState.java` (CRLF, préserver) :

- ajouter `import java.util.concurrent.atomic.AtomicReference;` après `import java.util.concurrent.ConcurrentLinkedQueue;`
- remplacer `    private volatile Throwable failure;` par `    private final AtomicReference<Throwable> failure = new AtomicReference<>();`
- remplacer

```java
    public Throwable getFailure() {
        return failure;
    }

    public void setFailure(Throwable failure) {
        this.failure = failure;
    }
```

par

```java
    public Throwable getFailure() {
        return failure.get();
    }

    public void setFailure(Throwable failure) {
        this.failure.set(failure);
    }

    /**
     * Records the failure only when none is recorded yet (atomically: concurrent listings may fail together).
     *
     * @return true when this failure was recorded
     */
    public boolean recordFailureIfAbsent(Throwable failure) {
        return this.failure.compareAndSet(null, failure);
    }
```

- [ ] **Step 4: Implement MainExecutor (listing failure, javadoc)**

Dans `MainExecutor.runListing`, remplacer

```java
            listingFailed.set(true);
            if (state.getFailure() == null) {
                state.setFailure(t);
            }
```

par

```java
            state.recordFailureIfAbsent(t); // the first listing failure wins, atomically
            listingFailed.set(true);
```

Dans la javadoc du constructeur public de `MainExecutor`, remplacer

```java
     *                always sees the latest state, not every single transition), plus one final
     *                guaranteed notification once the run has terminated. Exceptions it throws are
```

par

```java
     *                always sees the latest state, not every single transition), plus one terminal,
     *                guaranteed notification per phase: once for a run without resume, once after
     *                prepare() and once after execute() otherwise. Exceptions it throws are
```

- [ ] **Step 5: Implement WorkItemExecution (frozen resume key)**

Dans `WorkItemExecution.java` (LF), remplacer

```java
    private volatile ItemKey resumeKey;
```

par

```java
    private volatile ItemKey resumeKey;
    /** True once the resume key has been frozen, possibly to null (an item without date). */
    private volatile boolean resumeKeyFrozen;
```

et

```java
    public void setResumeKey(ItemKey resumeKey) {
        this.resumeKey = resumeKey;
    }
```

par

```java
    /**
     * Freezes the resume ordering key (called by {@link com.copybot.engine.resume.ResumeResolver#order}
     * at the preparation barrier). Only the first call counts, later calls are ignored: the item keeps the
     * place it had when the resume point was applied. null freezes "no key".
     */
    public synchronized void setResumeKey(ItemKey resumeKey) {
        if (resumeKeyFrozen) {
            return;
        }
        this.resumeKey = resumeKey;
        this.resumeKeyFrozen = true;
    }
```

- [ ] **Step 6: Run the tests, then the full build**

Run: `mvn -o -q -pl copybot-engine test`
Expected: PASS.

Run: `mvn -o clean install`
Expected: `BUILD SUCCESS` pour tous les modules (le `clean` évite l'échec connu de jpackage « Application destination directory … already exists »).

- [ ] **Step 7: Commit**

```bash
git ls-files --eol copybot-engine/src/main/java/com/copybot/engine/pipeline/PipelineState.java copybot-engine/src/main/java/com/copybot/engine/MainExecutor.java copybot-engine/src/main/java/com/copybot/engine/pipeline/WorkItemExecution.java
git add copybot-engine/src/main/java/com/copybot/engine/pipeline/PipelineState.java copybot-engine/src/main/java/com/copybot/engine/MainExecutor.java copybot-engine/src/main/java/com/copybot/engine/pipeline/WorkItemExecution.java copybot-engine/src/test/java/com/copybot/engine/pipeline/PipelineStateTest.java copybot-engine/src/test/java/com/copybot/engine/pipeline/WorkItemExecutionTest.java
git commit -m "Record the first listing failure atomically and freeze the resume key" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

(`PipelineState.java` : `w/crlf` ; les autres : `w/lf`.)

---

## Couverture de la spec

| Spec | Tâche |
|---|---|
| §1 API d'instance, `create`, une opération à la fois, `prepare` bloquant compté actif, `close` idempotent (annule + 5 s), fin de l'API statique | 6 |
| §2 erreurs avant plan ⇒ exception (dont `"mode": 1`) ; pendant ⇒ `Plan`/état `ERROR` + `failure` ; `run` jamais d'exception de fond | 4, 6 |
| §3 `Execution` (getState / await / isDone / cancel / pause / resume), `CANCELLED`, curseur inchangé, cancel pendant pause, interruption ⇒ `CANCELLED` | 2, 3, 6 |
| §4 pause du registre, listing gelé avant émission, snapshot `paused` | 1, 3 |
| §5 `PluginEngine.load` thread-safe / idempotent / avertissement, fin de `pluginsLoaded` | 5 |
| §6 `Callable<Integer>`, codes 0 / 1 / 2 / 130, hook Ctrl+C retiré en fin normale, `--debug`, rapport conservé | 7 |
| §7 UI : instance créée au démarrage, fermée à l'arrêt, `run` → `Execution` | 6 |
| §8 `runSinglePhase` + `setFailure` | 4 |
| §8 première erreur de listing en `compareAndSet`, `setResumeKey` figée, javadoc du constructeur `MainExecutor` | 8 |
| §8 javadoc périmée de `MainTest` | 6 |
| §9 tests `Execution`, registre, instance, préparation, CLI par code, migration des tests existants | 1, 3, 4, 5, 6, 7 |

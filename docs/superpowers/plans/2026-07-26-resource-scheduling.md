# Ordonnancement par ressources — Plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implémenter l'ordonnancement par ressources du pipeline copybot (spec : `docs/superpowers/specs/2026-07-26-resource-scheduling-design.md`) : threads virtuels + `ResourceRegistry` tout-ou-rien/first-fit/anti-famine, empreintes hybrides (plugin + moteur + config), pipelining configurable.

**Architecture:** Un nouveau package `com.copybot.engine.resources` (registre, capacités, résolution d'empreintes) ; `MainExecutor` réécrit en modèle « 1 thread virtuel par tâche » ; deux méthodes `default` ajoutées à `IAction` (rétro-compatibles). L'ancienne machinerie événementielle (`doNext()`, `RunnableCallback`, files par step) est supprimée.

**Tech Stack:** Java 25 (threads virtuels, JPMS), Maven multi-module, JUnit Jupiter 6.1.2, Gson 2.14.0 (records). Aucune nouvelle dépendance.

## Global Constraints

- Java 25 (`maven.compiler.source/target` = 25 dans le pom racine) — les threads virtuels et le pattern matching `instanceof` sont disponibles.
- Tests : JUnit Jupiter `6.1.2` (property `${junit.version}`), exécutés par Surefire 3.5.5 sur le classpath (pas de module-info de test).
- Aucune dépendance externe ajoutée : JDK + Gson déjà présents uniquement.
- API plugin rétro-compatible : n'ajouter à `IAction` que des méthodes `default` ; ne rien casser pour les plugins existants (`copybot-plugin-demo*` ne doit pas être modifié).
- Code et javadoc en anglais (convention du code existant) ; ce plan et la spec sont en français.
- Commande de test du module moteur, depuis la racine du repo : `mvn -pl copybot-engine test`
- Build complet de vérification finale : `mvn clean install` (racine).
- Défauts de comportement actés : `startProcessingWhileListing` absent ⇒ `false` (mode deux phases, comportement historique) ; capacités par défaut : `cpu` = nb cœurs, `gpu` = 1, `disk:*` = 2, autre = 1 ; anti-famine : `MAX_BYPASS = 5`, `MAX_WAIT_MILLIS = 60_000`.
- Un commit git par tâche (messages fournis) ; ne jamais inclure dans ces commits les fichiers modifiés par la montée de version encore non commités (`git add` ciblé uniquement sur les fichiers de la tâche).

## Carte des fichiers

| Fichier | Rôle |
|---|---|
| `copybot-engine/src/main/java/com/copybot/config/CopybotConfig.java` | +2 champs record : `resources`, `resourceGroups` |
| `copybot-engine/src/main/java/com/copybot/engine/resources/ResourceSettings.java` | **Créer** — capacités (exact/préfixe/défauts) + alias de groupes |
| `copybot-engine/src/main/java/com/copybot/engine/resources/ResourceRegistry.java` | **Créer** — arbitre acquireAll/releaseAll/snapshot |
| `copybot-engine/src/main/java/com/copybot/engine/resources/ResourceSnapshot.java` | **Créer** — record pour l'UI |
| `copybot-engine/src/main/java/com/copybot/engine/resources/DiskResolver.java` | **Créer** — Path → nom de ressource `disk:<volume>` |
| `copybot-engine/src/main/java/com/copybot/engine/resources/FootprintResolver.java` | **Créer** — fusion plugin + moteur + config |
| `copybot-engine/src/main/java/com/copybot/plugin/api/action/IAction.java` | +2 méthodes `default` |
| `copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileReadAction.java` | override `touchedPaths` |
| `copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileWriteAction.java` | override `touchedPaths` |
| `copybot-engine/src/main/java/com/copybot/engine/pipeline/PipelineStepConfig.java` | +champ `resources` |
| `copybot-engine/src/main/java/com/copybot/engine/pipeline/PipelineConfig.java` | +champ `startProcessingWhileListing` |
| `copybot-engine/src/main/java/com/copybot/engine/pipeline/ItemStatus.java` | **Créer** — enum d'état d'un item |
| `copybot-engine/src/main/java/com/copybot/engine/pipeline/WorkItemExecution.java` | réécrit — statut, waitingFor, error |
| `copybot-engine/src/main/java/com/copybot/engine/pipeline/PipelineStep.java` | nettoyé — supprime queue/runningCount, ajoute getConfig() |
| `copybot-engine/src/main/java/com/copybot/engine/pipeline/PipelineState.java` | +référence registre + snapshot |
| `copybot-engine/src/main/java/com/copybot/engine/MainExecutor.java` | **réécrit** — threads virtuels |
| `copybot-engine/src/main/java/com/copybot/engine/CopybotEngine.java` | câblage registre + executor virtuel |
| `copybot-engine/src/main/java/com/copybot/engine/asynch/RunnableCallback.java` | **Supprimer** (et son package) |
| `copybot-engine/src/main/java/module-info.java` | +export `com.copybot.engine.resources` vers l'UI |
| `copybot-engine/src/test/java/com/copybot/engine/resources/*Test.java` | **Créer** — tests unitaires |
| `copybot-engine/src/test/java/com/copybot/engine/MainExecutorTest.java` | **Créer** — tests d'intégration |

---

### Task 1: Configuration des ressources (`CopybotConfig` + `ResourceSettings`)

**Files:**
- Modify: `copybot-engine/src/main/java/com/copybot/config/CopybotConfig.java`
- Create: `copybot-engine/src/main/java/com/copybot/engine/resources/ResourceSettings.java`
- Test: `copybot-engine/src/test/java/com/copybot/engine/resources/ResourceSettingsTest.java`

**Interfaces:**
- Consumes: `CopybotConfig` (record existant), `GsonUtil.getGson()`.
- Produces: `ResourceSettings.from(CopybotConfig)`, `String canonical(String name)`, `int capacityFor(String name)` — utilisés par `ResourceRegistry` (Task 2). Constantes `ResourceSettings.CPU = "cpu"`, `ResourceSettings.GPU = "gpu"`, `ResourceSettings.DISK_PREFIX = "disk:"`.

- [ ] **Step 1 : Écrire le test qui échoue**

```java
package com.copybot.engine.resources;

import com.copybot.config.CopybotConfig;
import com.copybot.utils.GsonUtil;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ResourceSettingsTest {

    @Test
    public void exactCapacityWinsOverPrefix() {
        CopybotConfig config = new CopybotConfig(null, null,
                Map.of("disk:*", 4, "disk:C:\\", 1), null);
        ResourceSettings settings = ResourceSettings.from(config);
        assertEquals(1, settings.capacityFor("disk:C:\\"));
        assertEquals(4, settings.capacityFor("disk:D:\\"));
    }

    @Test
    public void builtinDefaults() {
        ResourceSettings settings = ResourceSettings.from(null);
        assertEquals(Runtime.getRuntime().availableProcessors(), settings.capacityFor("cpu"));
        assertEquals(1, settings.capacityFor("gpu"));
        assertEquals(2, settings.capacityFor("disk:X:\\"));
        assertEquals(1, settings.capacityFor("net:flickr"));
    }

    @Test
    public void groupMembersShareOneCanonicalName() {
        CopybotConfig config = new CopybotConfig(null, null, null,
                List.of(List.of("disk:D:\\", "disk:E:\\")));
        ResourceSettings settings = ResourceSettings.from(config);
        assertEquals("disk:D:\\", settings.canonical("disk:D:\\"));
        assertEquals("disk:D:\\", settings.canonical("disk:E:\\"));
        assertEquals("cpu", settings.canonical("cpu"));
    }

    @Test
    public void configRecordParsesFromJson() {
        CopybotConfig config = GsonUtil.getGson().fromJson(
                "{\"resources\":{\"cpu\":4,\"disk:*\":8},\"resourceGroups\":[[\"disk:D\",\"disk:E\"]]}",
                CopybotConfig.class);
        ResourceSettings settings = ResourceSettings.from(config);
        assertEquals(4, settings.capacityFor("cpu"));
        assertEquals(8, settings.capacityFor("disk:D"));
        assertEquals("disk:D", settings.canonical("disk:E"));
    }
}
```

- [ ] **Step 2 : Vérifier l'échec**

Run: `mvn -pl copybot-engine test -Dtest=ResourceSettingsTest`
Expected: FAIL — erreur de compilation, `ResourceSettings` n'existe pas et `CopybotConfig` n'a pas 4 composants.

- [ ] **Step 3 : Implémenter**

`CopybotConfig.java` (record complet — deux nouveaux champs, `null` toléré) :

```java
package com.copybot.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record CopybotConfig(
        Path pluginPath,

        Path devPluginPaths,

        /**
         * Resource capacities. Exact name ("disk:C:\\") or prefix pattern ("disk:*").
         */
        Map<String, Integer> resources,

        /**
         * Groups of resource names that alias to a single resource
         * (e.g. two partitions of the same physical disk).
         */
        List<List<String>> resourceGroups
) {
}
```

`ResourceSettings.java` :

```java
package com.copybot.engine.resources;

import com.copybot.config.CopybotConfig;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolved resource configuration: capacities (exact name, prefix pattern or
 * built-in default) and group aliasing (several names counted as one resource).
 */
public final class ResourceSettings {

    public static final String CPU = "cpu";
    public static final String GPU = "gpu";
    public static final String DISK_PREFIX = "disk:";

    private final Map<String, Integer> exact;
    private final Map<String, Integer> prefixes; // key stored without the trailing '*'
    private final Map<String, String> aliases;   // group member -> canonical (first of group)

    private ResourceSettings(Map<String, Integer> exact, Map<String, Integer> prefixes, Map<String, String> aliases) {
        this.exact = exact;
        this.prefixes = prefixes;
        this.aliases = aliases;
    }

    public static ResourceSettings from(CopybotConfig config) {
        Map<String, Integer> exact = new HashMap<>();
        Map<String, Integer> prefixes = new HashMap<>();
        Map<String, String> aliases = new HashMap<>();
        if (config != null && config.resources() != null) {
            config.resources().forEach((name, capacity) -> {
                if (name.endsWith("*")) {
                    prefixes.put(name.substring(0, name.length() - 1), capacity);
                } else {
                    exact.put(name, capacity);
                }
            });
        }
        if (config != null && config.resourceGroups() != null) {
            for (List<String> group : config.resourceGroups()) {
                String canonical = group.get(0);
                group.forEach(member -> aliases.put(member, canonical));
            }
        }
        return new ResourceSettings(exact, prefixes, aliases);
    }

    /** Resolves group aliasing: returns the canonical resource name. */
    public String canonical(String name) {
        return aliases.getOrDefault(name, name);
    }

    /** Capacity for a (canonical) resource name: exact > longest prefix > built-in default. */
    public int capacityFor(String name) {
        Integer capacity = exact.get(name);
        if (capacity != null) {
            return capacity;
        }
        capacity = prefixes.entrySet().stream()
                .filter(e -> name.startsWith(e.getKey()))
                .max(Comparator.comparingInt(e -> e.getKey().length()))
                .map(Map.Entry::getValue)
                .orElse(null);
        if (capacity != null) {
            return capacity;
        }
        if (CPU.equals(name)) {
            return Runtime.getRuntime().availableProcessors();
        }
        if (GPU.equals(name)) {
            return 1;
        }
        if (name.startsWith(DISK_PREFIX)) {
            return 2;
        }
        return 1;
    }
}
```

- [ ] **Step 4 : Vérifier le passage**

Run: `mvn -pl copybot-engine test -Dtest=ResourceSettingsTest`
Expected: PASS (4 tests). Vérifier aussi que `MainTest` compile toujours : `mvn -pl copybot-engine test` complet, tout vert.

- [ ] **Step 5 : Commit**

```bash
git add copybot-engine/src/main/java/com/copybot/config/CopybotConfig.java copybot-engine/src/main/java/com/copybot/engine/resources/ResourceSettings.java copybot-engine/src/test/java/com/copybot/engine/resources/ResourceSettingsTest.java
git commit -m "feat(engine): resource capacities and groups configuration"
```

---

### Task 2: `ResourceRegistry` — acquisition tout-ou-rien

**Files:**
- Create: `copybot-engine/src/main/java/com/copybot/engine/resources/ResourceRegistry.java`
- Create: `copybot-engine/src/main/java/com/copybot/engine/resources/ResourceSnapshot.java`
- Test: `copybot-engine/src/test/java/com/copybot/engine/resources/ResourceRegistryTest.java`

**Interfaces:**
- Consumes: `ResourceSettings.canonical(String)`, `ResourceSettings.capacityFor(String)` (Task 1).
- Produces: `new ResourceRegistry(ResourceSettings)`, `void acquireAll(Set<String>) throws InterruptedException`, `void releaseAll(Set<String>)`, `List<ResourceSnapshot> snapshot()` ; `record ResourceSnapshot(String name, int capacity, int used, int waiting)`. Constantes package-private testables : `MAX_BYPASS`, `MAX_WAIT_MILLIS`.

- [ ] **Step 1 : Écrire les tests qui échouent**

```java
package com.copybot.engine.resources;

import com.copybot.config.CopybotConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

public class ResourceRegistryTest {

    /** Starts a virtual thread that acquires the given resources; exposes latches to observe it. */
    static final class Acquirer {
        final CountDownLatch acquired = new CountDownLatch(1);
        final CountDownLatch released = new CountDownLatch(1);
        final CountDownLatch releaseSignal = new CountDownLatch(1);
        final Thread thread;

        Acquirer(ResourceRegistry registry, Set<String> resources) {
            thread = Thread.ofVirtual().start(() -> {
                try {
                    registry.acquireAll(resources);
                    acquired.countDown();
                    releaseSignal.await();
                    registry.releaseAll(resources);
                    released.countDown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        void release() throws InterruptedException {
            releaseSignal.countDown();
            assertTrue(released.await(5, TimeUnit.SECONDS));
        }
    }

    static ResourceRegistry registry(Map<String, Integer> capacities) {
        return new ResourceRegistry(ResourceSettings.from(new CopybotConfig(null, null, capacities, null)));
    }

    static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean()) {
            assertTrue(System.currentTimeMillis() < deadline, "condition not met within 5s");
            Thread.sleep(5);
        }
    }

    static int waiting(ResourceRegistry reg, String name) {
        return reg.snapshot().stream()
                .filter(s -> s.name().equals(name))
                .mapToInt(ResourceSnapshot::waiting).sum();
    }

    static int used(ResourceRegistry reg, String name) {
        return reg.snapshot().stream()
                .filter(s -> s.name().equals(name))
                .mapToInt(ResourceSnapshot::used).sum();
    }

    @Test
    public void capacityIsEnforced() throws Exception {
        ResourceRegistry reg = registry(Map.of("r", 1));
        Acquirer first = new Acquirer(reg, Set.of("r"));
        assertTrue(first.acquired.await(5, TimeUnit.SECONDS));

        Acquirer second = new Acquirer(reg, Set.of("r"));
        awaitTrue(() -> waiting(reg, "r") == 1);
        assertEquals(1, second.acquired.getCount(), "second acquirer must still be waiting");

        first.release();
        assertTrue(second.acquired.await(5, TimeUnit.SECONDS));
        second.release();
    }

    @Test
    public void allOrNothing_waiterHoldsNoPartialPermit() throws Exception {
        ResourceRegistry reg = registry(Map.of("a", 1, "b", 1));
        Acquirer holderOfA = new Acquirer(reg, Set.of("a"));
        assertTrue(holderOfA.acquired.await(5, TimeUnit.SECONDS));

        Acquirer wantsBoth = new Acquirer(reg, Set.of("a", "b"));
        awaitTrue(() -> waiting(reg, "a") == 1);
        // while waiting for "a", the waiter must NOT hold "b"
        assertEquals(0, used(reg, "b"));

        holderOfA.release();
        assertTrue(wantsBoth.acquired.await(5, TimeUnit.SECONDS));
        assertEquals(1, used(reg, "a"));
        assertEquals(1, used(reg, "b"));
        wantsBoth.release();
    }

    @Test
    public void groupAliasCountsAsOneResource() throws Exception {
        ResourceRegistry reg = new ResourceRegistry(ResourceSettings.from(new CopybotConfig(null, null,
                Map.of("disk:D", 1), java.util.List.of(java.util.List.of("disk:D", "disk:E")))));
        Acquirer onD = new Acquirer(reg, Set.of("disk:D"));
        assertTrue(onD.acquired.await(5, TimeUnit.SECONDS));

        Acquirer onE = new Acquirer(reg, Set.of("disk:E")); // same physical disk => must wait
        awaitTrue(() -> waiting(reg, "disk:D") == 1);
        assertEquals(1, onE.acquired.getCount());

        onD.release();
        assertTrue(onE.acquired.await(5, TimeUnit.SECONDS));
        onE.release();
    }
}
```

- [ ] **Step 2 : Vérifier l'échec**

Run: `mvn -pl copybot-engine test -Dtest=ResourceRegistryTest`
Expected: FAIL — compilation, `ResourceRegistry` et `ResourceSnapshot` n'existent pas.

- [ ] **Step 3 : Implémenter**

`ResourceSnapshot.java` :

```java
package com.copybot.engine.resources;

/** Point-in-time view of one resource, for UI display. */
public record ResourceSnapshot(String name, int capacity, int used, int waiting) {
}
```

`ResourceRegistry.java` (version complète de cette tâche : grant FIFO simple ; le first-fit et l'anti-famine arrivent en Task 3 dans `grantEligibleWaiters`) :

```java
package com.copybot.engine.resources;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Central arbiter for named resources.
 * acquireAll is all-or-nothing: a waiter never holds a permit while waiting for another,
 * so multi-resource acquisition cannot deadlock nor waste slots.
 */
public final class ResourceRegistry {

    static final int MAX_BYPASS = 5;
    static final long MAX_WAIT_MILLIS = 60_000;

    private static final class ResourceCount {
        final int capacity;
        int used;

        ResourceCount(int capacity) {
            this.capacity = capacity;
        }
    }

    private static final class Waiter {
        final Set<String> resources;
        final long since;
        int bypassCount;
        boolean granted;

        Waiter(Set<String> resources, long since) {
            this.resources = resources;
            this.since = since;
        }
    }

    private final ResourceSettings settings;
    private final Map<String, ResourceCount> counts = new HashMap<>();
    private final Deque<Waiter> waiters = new ArrayDeque<>(); // arrival order
    private final Object lock = new Object();

    public ResourceRegistry(ResourceSettings settings) {
        this.settings = settings;
    }

    /**
     * Blocks until ALL requested resources are simultaneously available, then takes
     * one permit on each. Interruptible; on interruption nothing stays acquired.
     */
    public void acquireAll(Set<String> names) throws InterruptedException {
        Set<String> canonical = canonicalize(names);
        Waiter me = new Waiter(canonical, System.currentTimeMillis());
        synchronized (lock) {
            waiters.addLast(me);
            grantEligibleWaiters();
            try {
                while (!me.granted) {
                    lock.wait(200); // periodic wake-up so age-based anti-starvation takes effect
                    if (!me.granted) {
                        grantEligibleWaiters();
                    }
                }
            } catch (InterruptedException e) {
                waiters.remove(me);
                if (me.granted) { // granted between the interrupt and the catch: give it back
                    doRelease(canonical);
                    grantEligibleWaiters();
                }
                lock.notifyAll();
                throw e;
            }
        }
    }

    /** Returns one permit on each named resource and wakes up eligible waiters. */
    public void releaseAll(Set<String> names) {
        synchronized (lock) {
            doRelease(canonicalize(names));
            grantEligibleWaiters();
            lock.notifyAll();
        }
    }

    public List<ResourceSnapshot> snapshot() {
        synchronized (lock) {
            return counts.entrySet().stream()
                    .map(e -> new ResourceSnapshot(
                            e.getKey(),
                            e.getValue().capacity,
                            e.getValue().used,
                            (int) waiters.stream().filter(w -> w.resources.contains(e.getKey())).count()))
                    .sorted(Comparator.comparing(ResourceSnapshot::name))
                    .toList();
        }
    }

    // ---- all methods below are always called while holding `lock` ----

    private void grantEligibleWaiters() {
        long now = System.currentTimeMillis();
        List<Waiter> skipped = new ArrayList<>();
        Iterator<Waiter> it = waiters.iterator();
        while (it.hasNext()) {
            Waiter waiter = it.next();
            if (fits(waiter.resources)) {
                take(waiter.resources);
                waiter.granted = true;
                it.remove();
                skipped.forEach(s -> s.bypassCount++);
                lock.notifyAll();
            } else {
                if (waiter.bypassCount >= MAX_BYPASS || now - waiter.since >= MAX_WAIT_MILLIS) {
                    break; // starved waiter: strict mode, nobody bypasses it
                }
                skipped.add(waiter);
            }
        }
    }

    private Set<String> canonicalize(Set<String> names) {
        return names.stream().map(settings::canonical).collect(Collectors.toUnmodifiableSet());
    }

    private ResourceCount countFor(String name) {
        return counts.computeIfAbsent(name, n -> new ResourceCount(settings.capacityFor(n)));
    }

    private boolean fits(Set<String> names) {
        return names.stream().allMatch(n -> countFor(n).used < countFor(n).capacity);
    }

    private void take(Set<String> names) {
        names.forEach(n -> countFor(n).used++);
    }

    private void doRelease(Set<String> names) {
        names.forEach(n -> countFor(n).used--);
    }
}
```

Note : `grantEligibleWaiters` contient déjà la logique first-fit/anti-famine (elle est simple et d'un bloc) ; la Task 3 la couvre de tests dédiés plutôt que de la réécrire.

- [ ] **Step 4 : Vérifier le passage**

Run: `mvn -pl copybot-engine test -Dtest=ResourceRegistryTest`
Expected: PASS (3 tests).

- [ ] **Step 5 : Commit**

```bash
git add copybot-engine/src/main/java/com/copybot/engine/resources/ResourceRegistry.java copybot-engine/src/main/java/com/copybot/engine/resources/ResourceSnapshot.java copybot-engine/src/test/java/com/copybot/engine/resources/ResourceRegistryTest.java
git commit -m "feat(engine): all-or-nothing ResourceRegistry"
```

---

### Task 3: `ResourceRegistry` — first-fit, anti-famine, interruption

**Files:**
- Modify (tests uniquement): `copybot-engine/src/test/java/com/copybot/engine/resources/ResourceRegistryTest.java`

**Interfaces:**
- Consumes: tout de Task 2 (y compris `Acquirer`, `registry(...)`, `awaitTrue(...)`, `waiting(...)`, `used(...)`).
- Produces: garanties comportementales vérifiées, utilisées implicitement par `MainExecutor` (Task 7).

- [ ] **Step 1 : Ajouter les tests (dans la classe existante)**

```java
    @Test
    public void firstFit_smallTaskBypassesBlockedBigTask() throws Exception {
        ResourceRegistry reg = registry(Map.of("a", 1, "b", 1));
        Acquirer holderOfA = new Acquirer(reg, Set.of("a"));
        assertTrue(holderOfA.acquired.await(5, TimeUnit.SECONDS));

        Acquirer big = new Acquirer(reg, Set.of("a", "b")); // blocked on "a"
        awaitTrue(() -> waiting(reg, "a") == 1);

        Acquirer small = new Acquirer(reg, Set.of("b")); // arrived after big, but "b" is free
        assertTrue(small.acquired.await(5, TimeUnit.SECONDS), "small task must bypass the blocked big task");

        small.release();
        holderOfA.release();
        assertTrue(big.acquired.await(5, TimeUnit.SECONDS));
        big.release();
    }

    @Test
    public void antiStarvation_afterMaxBypassesNobodyOvertakes() throws Exception {
        ResourceRegistry reg = registry(Map.of("a", 1, "b", 1));
        Acquirer holderOfA = new Acquirer(reg, Set.of("a"));
        assertTrue(holderOfA.acquired.await(5, TimeUnit.SECONDS));

        Acquirer big = new Acquirer(reg, Set.of("a", "b"));
        awaitTrue(() -> waiting(reg, "a") == 1);

        // bypass the big task MAX_BYPASS times with small tasks on "b"
        for (int i = 0; i < ResourceRegistry.MAX_BYPASS; i++) {
            Acquirer small = new Acquirer(reg, Set.of("b"));
            assertTrue(small.acquired.await(5, TimeUnit.SECONDS), "bypass #" + i + " should succeed");
            small.release();
        }

        // the big task is now starved: the next small task must NOT be granted
        Acquirer blockedSmall = new Acquirer(reg, Set.of("b"));
        awaitTrue(() -> waiting(reg, "b") >= 1);
        assertEquals(1, blockedSmall.acquired.getCount(), "starved waiter must not be bypassed anymore");

        holderOfA.release();
        assertTrue(big.acquired.await(5, TimeUnit.SECONDS), "starved big task acquires first");
        big.release();
        assertTrue(blockedSmall.acquired.await(5, TimeUnit.SECONDS));
        blockedSmall.release();
    }

    @Test
    public void interruptedWaiterLeavesNoTrace() throws Exception {
        ResourceRegistry reg = registry(Map.of("r", 1));
        Acquirer holder = new Acquirer(reg, Set.of("r"));
        assertTrue(holder.acquired.await(5, TimeUnit.SECONDS));

        Acquirer waiter = new Acquirer(reg, Set.of("r"));
        awaitTrue(() -> waiting(reg, "r") == 1);

        waiter.thread.interrupt();
        awaitTrue(() -> waiting(reg, "r") == 0);
        assertEquals(1, waiter.acquired.getCount(), "interrupted waiter must not have acquired");

        holder.release();
        assertEquals(0, used(reg, "r"));
    }
```

- [ ] **Step 2 : Vérifier**

Run: `mvn -pl copybot-engine test -Dtest=ResourceRegistryTest`
Expected: PASS (6 tests). Si `antiStarvation` échoue : vérifier que `bypassCount` n'est incrémenté que lorsqu'un waiter postérieur est réellement servi (liste `skipped` dans `grantEligibleWaiters`).

- [ ] **Step 3 : Commit**

```bash
git add copybot-engine/src/test/java/com/copybot/engine/resources/ResourceRegistryTest.java
git commit -m "test(engine): first-fit, anti-starvation and interruption coverage for ResourceRegistry"
```

---

### Task 4: API plugin — `requiredResources` / `touchedPaths` + `DiskResolver`

**Files:**
- Modify: `copybot-engine/src/main/java/com/copybot/plugin/api/action/IAction.java`
- Create: `copybot-engine/src/main/java/com/copybot/engine/resources/DiskResolver.java`
- Modify: `copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileReadAction.java`
- Modify: `copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileWriteAction.java`
- Test: `copybot-engine/src/test/java/com/copybot/engine/resources/DiskResolverTest.java`
- Test: `copybot-engine/src/test/java/com/copybot/plugin/embedded/actions/EmbeddedActionResourcesTest.java`

**Interfaces:**
- Consumes: `AbstractActionWithConfig.loadConfig(JsonElement)` (existant), `FileReadConfig.path()`, `FileWriteConfig.outPattern()`.
- Produces: `IAction.requiredResources(WorkItem)` (default `Set.of()`), `IAction.touchedPaths(WorkItem)` (default `Set.of()`, l'argument peut être `null` pour les actions IN) ; `DiskResolver.diskResource(Path)` → `String` commençant par `"disk:"`. Utilisés par `FootprintResolver` (Task 5).

- [ ] **Step 1 : Écrire les tests qui échouent**

`DiskResolverTest.java` :

```java
package com.copybot.engine.resources;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class DiskResolverTest {

    @TempDir
    Path tempDir;

    @Test
    public void samePathsOnSameVolumeGetSameName(@TempDir Path other) {
        assertEquals(DiskResolver.diskResource(tempDir), DiskResolver.diskResource(other));
    }

    @Test
    public void nonExistingPathFallsBackToExistingAncestor() {
        Path notCreatedYet = tempDir.resolve("sub").resolve("dir").resolve("file.txt");
        assertEquals(DiskResolver.diskResource(tempDir), DiskResolver.diskResource(notCreatedYet));
    }

    @Test
    public void nameIsPrefixedWithDisk() {
        assertTrue(DiskResolver.diskResource(tempDir).startsWith("disk:"));
    }
}
```

`EmbeddedActionResourcesTest.java` :

```java
package com.copybot.plugin.embedded.actions;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class EmbeddedActionResourcesTest {

    @TempDir
    Path tempDir;

    @Test
    public void fileReadDeclaresItsConfiguredRoot() {
        FileReadAction action = new FileReadAction();
        action.loadConfig(JsonParser.parseString(
                "{\"path\":\"" + tempDir.toString().replace("\\", "\\\\") + "\"}"));
        assertEquals(Set.of(tempDir), action.touchedPaths(null));
    }

    @Test
    public void fileWriteDeclaresTheStaticPrefixOfItsOutPattern() {
        FileWriteAction action = new FileWriteAction();
        String outDir = tempDir.resolve("out").toString();
        action.loadConfig(JsonParser.parseString(
                "{\"outPattern\":\"" + (outDir + "\\\\{name}").replace("\\", "\\\\") + "\",\"overwrite\":true}"));
        assertEquals(Set.of(Path.of(outDir)), action.touchedPaths(null));
    }
}
```

- [ ] **Step 2 : Vérifier l'échec**

Run: `mvn -pl copybot-engine test -Dtest=DiskResolverTest,EmbeddedActionResourcesTest`
Expected: FAIL — compilation (`DiskResolver` absent, `touchedPaths` absent).

- [ ] **Step 3 : Implémenter**

Dans `IAction.java`, ajouter les imports `java.nio.file.Path`, `java.util.Set` et les deux méthodes (avant `setStatusWatcher`) :

```java
    /**
     * Named resources this action consumes for one item (e.g. "cpu", "gpu", "net:flickr").
     * Used by the engine to bound concurrency. Empty by default.
     */
    default Set<String> requiredResources(WorkItem item) {
        return Set.of();
    }

    /**
     * Filesystem paths this action will touch for one item (the engine maps them to
     * disk resources). For IN actions this is called once with a null item before listing.
     * Empty by default.
     */
    default Set<Path> touchedPaths(WorkItem item) {
        return Set.of();
    }
```

`DiskResolver.java` :

```java
package com.copybot.engine.resources;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Maps a path to a disk resource name ("disk:<volume>"), volume-level in v1. */
public final class DiskResolver {

    private DiskResolver() {
    }

    public static String diskResource(Path path) {
        Path absolute = path.toAbsolutePath();
        Path existing = absolute;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing != null) {
            try {
                return ResourceSettings.DISK_PREFIX + Files.getFileStore(existing);
            } catch (IOException e) {
                // fall through to root-based naming
            }
        }
        Path root = absolute.getRoot();
        return ResourceSettings.DISK_PREFIX + (root != null ? root : absolute);
    }
}
```

Dans `FileReadAction.java`, ajouter (imports `java.util.Set`) :

```java
    @Override
    public Set<Path> touchedPaths(WorkItem item) {
        return Set.of(Path.of(getConfig().path()));
    }
```

Dans `FileWriteAction.java`, ajouter (imports `java.util.Set`) :

```java
    @Override
    public Set<Path> touchedPaths(WorkItem item) {
        // static prefix of the out pattern, before the first {placeholder}
        String outPattern = getConfig().outPattern();
        int firstParam = outPattern.indexOf('{');
        String prefix = firstParam < 0 ? outPattern : outPattern.substring(0, firstParam);
        return Set.of(Path.of(prefix));
    }
```

- [ ] **Step 4 : Vérifier le passage**

Run: `mvn -pl copybot-engine test -Dtest=DiskResolverTest,EmbeddedActionResourcesTest`
Expected: PASS (5 tests). Puis `mvn -pl copybot-engine test` complet pour vérifier la rétro-compatibilité.

- [ ] **Step 5 : Commit**

```bash
git add copybot-engine/src/main/java/com/copybot/plugin/api/action/IAction.java copybot-engine/src/main/java/com/copybot/engine/resources/DiskResolver.java copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileReadAction.java copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileWriteAction.java copybot-engine/src/test/java/com/copybot/engine/resources/DiskResolverTest.java copybot-engine/src/test/java/com/copybot/plugin/embedded/actions/EmbeddedActionResourcesTest.java
git commit -m "feat(plugin-api): actions declare required resources and touched paths"
```

---

### Task 5: `FootprintResolver` + champ `resources` de la config de step

**Files:**
- Modify: `copybot-engine/src/main/java/com/copybot/engine/pipeline/PipelineStepConfig.java`
- Create: `copybot-engine/src/main/java/com/copybot/engine/resources/FootprintResolver.java`
- Test: `copybot-engine/src/test/java/com/copybot/engine/resources/FootprintResolverTest.java`

**Interfaces:**
- Consumes: `IAction.requiredResources/touchedPaths` (Task 4), `DiskResolver.diskResource` (Task 4), `PipelineStep.getAction()/getConfig()` (getConfig ajouté en Task 6 — ici on passe action et config séparément pour rester découplé).
- Produces: `FootprintResolver.forListing(IAction action, PipelineStepConfig config)` et `FootprintResolver.resolve(IAction action, WorkItem item, PipelineStepConfig config, int stepIndex)` → `Set<String>`. Nouveau composant record : `PipelineStepConfig.resources()` (`List<String>`, nullable), inséré entre `priority` et `actionConfig`.

- [ ] **Step 1 : Écrire le test qui échoue**

```java
package com.copybot.engine.resources;

import com.copybot.engine.pipeline.PipelineStepConfig;
import com.copybot.plugin.api.action.IAction;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkStatus;
import com.copybot.plugin.api.definition.IPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class FootprintResolverTest {

    @TempDir
    Path tempDir;

    static final class FakeAction implements IAction {
        final Set<String> required;
        final Set<Path> touched;

        FakeAction(Set<String> required, Set<Path> touched) {
            this.required = required;
            this.touched = touched;
        }

        @Override
        public Set<String> requiredResources(WorkItem item) {
            return required;
        }

        @Override
        public Set<Path> touchedPaths(WorkItem item) {
            return touched;
        }

        @Override
        public void setStatusWatcher(Consumer<WorkStatus> watcher) {
        }

        @Override
        public void setPlugin(IPlugin plugin) {
        }
    }

    static PipelineStepConfig config(Integer maxConcurrency, List<String> resources) {
        return new PipelineStepConfig(null, null, null, null, maxConcurrency, null, resources, null);
    }

    @Test
    public void mergesActionEngineAndUserContributions() throws IOException {
        Path file = Files.createFile(tempDir.resolve("photo.jpg"));
        Path target = tempDir.resolve("out");
        WorkItem item = new WorkItem(file);
        FakeAction action = new FakeAction(Set.of("cpu"), Set.of(target));

        Set<String> footprint = FootprintResolver.resolve(action, item, config(2, List.of("gpu")), 3);

        String disk = DiskResolver.diskResource(tempDir);
        assertEquals(Set.of("cpu", "gpu", disk, "step:3"), footprint);
    }

    @Test
    public void listingFootprintUsesNullItem() {
        FakeAction action = new FakeAction(Set.of(), Set.of(tempDir));
        Set<String> footprint = FootprintResolver.forListing(action, config(null, null));
        assertEquals(Set.of(DiskResolver.diskResource(tempDir)), footprint);
    }

    @Test
    public void noConcurrencyLimitMeansNoStepResource() throws IOException {
        Path file = Files.createFile(tempDir.resolve("a.txt"));
        WorkItem item = new WorkItem(file);
        FakeAction action = new FakeAction(Set.of(), Set.of());

        Set<String> footprint = FootprintResolver.resolve(action, item, config(null, null), 0);

        assertEquals(Set.of(DiskResolver.diskResource(tempDir)), footprint);
    }
}
```

- [ ] **Step 2 : Vérifier l'échec**

Run: `mvn -pl copybot-engine test -Dtest=FootprintResolverTest`
Expected: FAIL — compilation (`FootprintResolver` absent, `PipelineStepConfig` n'a pas 8 composants).

- [ ] **Step 3 : Implémenter**

`PipelineStepConfig.java` (record complet) :

```java
package com.copybot.engine.pipeline;

import com.google.gson.JsonElement;

import java.util.List;

public record PipelineStepConfig(
        String plugin,
        String action,

        /**
         * optional discriminant if multiple version of the plugin is loaded
         */
        String version,

        String filterCondition,

        Integer maxConcurrency,
        Integer priority,

        /**
         * optional additional resource names this step consumes (e.g. ["gpu"])
         */
        List<String> resources,

        JsonElement actionConfig
) {

    public String getDisplayName() {
        return action + " => " + plugin + (version == null ? "" : ':' + version);
    }
}
```

`FootprintResolver.java` :

```java
package com.copybot.engine.resources;

import com.copybot.engine.pipeline.PipelineStepConfig;
import com.copybot.plugin.api.action.IAction;
import com.copybot.plugin.api.action.WorkItem;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * Computes the resource footprint of one (action, item) execution by merging:
 * 1. what the action declares (requiredResources, touchedPaths),
 * 2. what the engine detects (disk of the item's current location),
 * 3. what the user configured (extra resources, maxConcurrency as an implicit step resource).
 */
public final class FootprintResolver {

    private FootprintResolver() {
    }

    /** Footprint of an IN step's listing (no item yet: touchedPaths is called with null). */
    public static Set<String> forListing(IAction action, PipelineStepConfig config) {
        return merge(action, null, config, -1);
    }

    /** Footprint of one step execution for one item. */
    public static Set<String> resolve(IAction action, WorkItem item, PipelineStepConfig config, int stepIndex) {
        return merge(action, item, config, stepIndex);
    }

    private static Set<String> merge(IAction action, WorkItem item, PipelineStepConfig config, int stepIndex) {
        Set<String> footprint = new HashSet<>(action.requiredResources(item));
        for (Path path : action.touchedPaths(item)) {
            footprint.add(DiskResolver.diskResource(path));
        }
        if (item != null && item.isLocal()) {
            footprint.add(DiskResolver.diskResource(item.getLocalLocation()));
        }
        if (config != null) {
            if (config.resources() != null) {
                footprint.addAll(config.resources());
            }
            if (config.maxConcurrency() != null && stepIndex >= 0) {
                footprint.add("step:" + stepIndex);
            }
        }
        return footprint;
    }
}
```

- [ ] **Step 4 : Vérifier le passage**

Run: `mvn -pl copybot-engine test -Dtest=FootprintResolverTest`
Expected: PASS (3 tests). Puis `mvn -pl copybot-engine test` complet (le record modifié ne doit rien casser : Gson tolère le champ absent → `null`).

- [ ] **Step 5 : Commit**

```bash
git add copybot-engine/src/main/java/com/copybot/engine/pipeline/PipelineStepConfig.java copybot-engine/src/main/java/com/copybot/engine/resources/FootprintResolver.java copybot-engine/src/test/java/com/copybot/engine/resources/FootprintResolverTest.java
git commit -m "feat(engine): footprint resolution merging plugin, engine and user contributions"
```

---

### Task 6: Modèle d'état — `ItemStatus`, `WorkItemExecution`, `PipelineStep`, `PipelineState`, flag de pipelining

**Files:**
- Create: `copybot-engine/src/main/java/com/copybot/engine/pipeline/ItemStatus.java`
- Modify: `copybot-engine/src/main/java/com/copybot/engine/pipeline/WorkItemExecution.java`
- Modify: `copybot-engine/src/main/java/com/copybot/engine/pipeline/PipelineStep.java`
- Modify: `copybot-engine/src/main/java/com/copybot/engine/pipeline/PipelineState.java`
- Modify: `copybot-engine/src/main/java/com/copybot/engine/pipeline/PipelineConfig.java`
- Test: `copybot-engine/src/test/java/com/copybot/engine/pipeline/WorkItemExecutionTest.java`

**Interfaces:**
- Consumes: `ResourceRegistry.snapshot()` (Task 2).
- Produces (utilisé par Task 7) :
  - `enum ItemStatus { PENDING, WAITING_RESOURCES, RUNNING, DONE, ERROR }`
  - `WorkItemExecution(WorkItem, List<PipelineStep<?>>)` ; `getWorkItem()`, `getStatus()`, `getCurrentStepIndex()`, `getWaitingFor()`, `getError()`, `setWaitingResources(int, Set<String>)`, `setRunning(int)`, `setDone()`, `setError(Throwable)`, `replaceWorkItem(WorkItem)` (+ `getWorkStatus()/setWorkStatus(WorkStatus)` conservés)
  - `PipelineStep.getConfig()` ; champs `queue`/`runningCount` supprimés
  - `PipelineState.setRegistry(ResourceRegistry)`, `List<ResourceSnapshot> getResourceSnapshot()`
  - `PipelineConfig.startProcessingWhileListing()` (`Boolean`, nullable, dernier composant du record)

- [ ] **Step 1 : Écrire le test qui échoue**

```java
package com.copybot.engine.pipeline;

import com.copybot.plugin.api.action.WorkItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class WorkItemExecutionTest {

    @TempDir
    Path tempDir;

    private WorkItemExecution newExecution() throws IOException {
        Path file = Files.createFile(tempDir.resolve("item.txt"));
        return new WorkItemExecution(new WorkItem(file), List.of());
    }

    @Test
    public void lifecycleTransitions() throws IOException {
        WorkItemExecution exec = newExecution();
        assertEquals(ItemStatus.PENDING, exec.getStatus());

        exec.setWaitingResources(2, Set.of("cpu", "disk:X"));
        assertEquals(ItemStatus.WAITING_RESOURCES, exec.getStatus());
        assertEquals(2, exec.getCurrentStepIndex());
        assertEquals(Set.of("cpu", "disk:X"), exec.getWaitingFor());

        exec.setRunning(2);
        assertEquals(ItemStatus.RUNNING, exec.getStatus());
        assertEquals(Set.of(), exec.getWaitingFor());

        exec.setDone();
        assertEquals(ItemStatus.DONE, exec.getStatus());
    }

    @Test
    public void errorKeepsTheCause() throws IOException {
        WorkItemExecution exec = newExecution();
        IllegalStateException boom = new IllegalStateException("boom");
        exec.setError(boom);
        assertEquals(ItemStatus.ERROR, exec.getStatus());
        assertSame(boom, exec.getError());
    }

    @Test
    public void workItemCanBeReplacedByProcessSteps() throws IOException {
        WorkItemExecution exec = newExecution();
        WorkItem replacement = new WorkItem(Files.createFile(tempDir.resolve("out.txt")));
        exec.replaceWorkItem(replacement);
        assertSame(replacement, exec.getWorkItem());
    }
}
```

- [ ] **Step 2 : Vérifier l'échec**

Run: `mvn -pl copybot-engine test -Dtest=WorkItemExecutionTest`
Expected: FAIL — compilation (`ItemStatus` absent, méthodes absentes).

- [ ] **Step 3 : Implémenter**

`ItemStatus.java` :

```java
package com.copybot.engine.pipeline;

public enum ItemStatus {
    PENDING,
    WAITING_RESOURCES,
    RUNNING,
    DONE,
    ERROR
}
```

`WorkItemExecution.java` (réécriture complète) :

```java
package com.copybot.engine.pipeline;

import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkStatus;

import java.util.List;
import java.util.Set;

public class WorkItemExecution {

    private volatile WorkItem wi;
    private volatile WorkStatus ws;

    private final List<PipelineStep<?>> pipelineSteps;

    private volatile ItemStatus status = ItemStatus.PENDING;
    private volatile int currentStepIndex = -1;
    private volatile Set<String> waitingFor = Set.of();
    private volatile Throwable error;

    public WorkItemExecution(WorkItem wi, List<PipelineStep<?>> pipelineSteps) {
        this.wi = wi;
        this.pipelineSteps = List.copyOf(pipelineSteps);
    }

    public WorkItem getWorkItem() {
        return wi;
    }

    /** A process step may transform the item (e.g. transcoding produces a new file). */
    public void replaceWorkItem(WorkItem newWorkItem) {
        this.wi = newWorkItem;
    }

    public List<PipelineStep<?>> getPipelineSteps() {
        return pipelineSteps;
    }

    public ItemStatus getStatus() {
        return status;
    }

    public int getCurrentStepIndex() {
        return currentStepIndex;
    }

    public Set<String> getWaitingFor() {
        return waitingFor;
    }

    public Throwable getError() {
        return error;
    }

    public void setWaitingResources(int stepIndex, Set<String> resources) {
        this.currentStepIndex = stepIndex;
        this.waitingFor = Set.copyOf(resources);
        this.status = ItemStatus.WAITING_RESOURCES;
    }

    public void setRunning(int stepIndex) {
        this.currentStepIndex = stepIndex;
        this.waitingFor = Set.of();
        this.status = ItemStatus.RUNNING;
    }

    public void setDone() {
        this.waitingFor = Set.of();
        this.status = ItemStatus.DONE;
    }

    public void setError(Throwable error) {
        this.error = error;
        this.waitingFor = Set.of();
        this.status = ItemStatus.ERROR;
    }

    public WorkStatus getWorkStatus() {
        return ws;
    }

    public void setWorkStatus(WorkStatus ws) {
        this.ws = ws;
    }
}
```

`PipelineStep.java` (réécriture complète — suppression de `queue` et `runningCount`, ajout de `getConfig()`) :

```java
package com.copybot.engine.pipeline;

import com.copybot.plugin.api.action.IAction;
import com.copybot.plugin.api.definition.IPlugin;

public class PipelineStep<A extends IAction> {
    private final IPlugin plugin;
    private final A action;
    private final PipelineStepConfig config;

    public PipelineStep(IPlugin plugin, A action, PipelineStepConfig config) {
        this.plugin = plugin;
        this.action = action;
        this.config = config;
    }

    public IPlugin getPlugin() {
        return plugin;
    }

    public A getAction() {
        return action;
    }

    public PipelineStepConfig getConfig() {
        return config;
    }
}
```

`PipelineState.java` — ajouter le registre (imports `com.copybot.engine.resources.ResourceRegistry`, `com.copybot.engine.resources.ResourceSnapshot`) :

```java
    private ResourceRegistry registry;

    public void setRegistry(ResourceRegistry registry) {
        this.registry = registry;
    }

    public List<ResourceSnapshot> getResourceSnapshot() {
        return registry == null ? List.of() : registry.snapshot();
    }
```

`PipelineConfig.java` (record complet) :

```java
package com.copybot.engine.pipeline;

import java.util.List;

public record PipelineConfig(

        List<PipelineStepConfig> inSteps,
        List<PipelineStepConfig> analyseSteps,
        List<PipelineStepConfig> actionSteps,
        PipelineStepConfig outStep,

        /**
         * true: items are processed while listing is still running (pipelining).
         * false or absent: all listings complete before any processing starts (two phases).
         */
        Boolean startProcessingWhileListing

        ) {
}
```

Note : `MainExecutor` ne compile plus après cette tâche (il référence l'ancien constructeur de `WorkItemExecution`). Correction minimale temporaire acceptée pour garder le build vert : dans `MainExecutor.doList()`, remplacer la création par `new WorkItemExecution(workItem, List.copyOf(initialPipelineSteps))` et typer `initialPipelineSteps` en `List<PipelineStep<?>>` (le champ et `resolveOtherSteps`). Il est réécrit intégralement en Task 7.

- [ ] **Step 4 : Vérifier le passage**

Run: `mvn -pl copybot-engine test`
Expected: PASS — tous les tests, y compris `MainTest` (compilation du module entière incluse).

- [ ] **Step 5 : Commit**

```bash
git add copybot-engine/src/main/java/com/copybot/engine/pipeline copybot-engine/src/test/java/com/copybot/engine/pipeline
git commit -m "feat(engine): item execution lifecycle and pipeline state exposing resources"
```

---

### Task 7: Réécriture de `MainExecutor` (threads virtuels)

**Files:**
- Modify: `copybot-engine/src/main/java/com/copybot/engine/MainExecutor.java` (réécriture complète)
- Test: `copybot-engine/src/test/java/com/copybot/engine/MainExecutorTest.java`

**Interfaces:**
- Consumes: tout des Tasks 1–6 ; `PluginEngine.resolve(PipelineStepConfig, Class)` (existant).
- Produces: `MainExecutor(PipelineConfig, Consumer<PipelineState>, ResourceRegistry)` (public, utilisé par `CopybotEngine` en Task 8) ; constructeur package-private de test `MainExecutor(List<PipelineStep<IInAction>> inSteps, List<PipelineStep<?>> itemSteps, boolean startProcessingWhileListing, Consumer<PipelineState> watcher, ResourceRegistry registry)` ; `PipelineState getState()` (package-private). `run()` est bloquant jusqu'à la fin du pipeline.

Sémantique de `runStep` (dispatch par type d'action) :
- `IAnalyzeAction.doAnalyze(item)` → continue.
- `IProcessAction.doProcess(item)` → liste vide ou null = item filtré (statut DONE, steps suivants sautés) ; sinon le 1er item retourné remplace l'item courant, les suivants créent de nouveaux `WorkItemExecution` démarrant au step suivant.
- `IOutAction.writeItem(item)` → continue.

- [ ] **Step 1 : Écrire les tests qui échouent**

```java
package com.copybot.engine;

import com.copybot.config.CopybotConfig;
import com.copybot.engine.pipeline.*;
import com.copybot.engine.resources.ResourceRegistry;
import com.copybot.engine.resources.ResourceSettings;
import com.copybot.plugin.api.action.*;
import com.copybot.plugin.api.definition.IPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

public class MainExecutorTest {

    @TempDir
    Path tempDir;

    // ---- fakes ----

    abstract static class FakeAction implements IAction {
        @Override
        public void setStatusWatcher(Consumer<WorkStatus> watcher) {
        }

        @Override
        public void setPlugin(IPlugin plugin) {
        }
    }

    final class FakeInAction extends FakeAction implements IInAction {
        final int count;
        final Runnable beforeReturn;

        FakeInAction(int count, Runnable beforeReturn) {
            this.count = count;
            this.beforeReturn = beforeReturn;
        }

        @Override
        public void listFiles(Consumer<WorkItem> workItemConsumer) {
            for (int i = 0; i < count; i++) {
                try {
                    workItemConsumer.accept(new WorkItem(Files.createFile(tempDir.resolve("in-" + System.nanoTime() + "-" + i + ".txt"))));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            if (beforeReturn != null) {
                beforeReturn.run();
            }
        }
    }

    static final class FakeProcessAction extends FakeAction implements IProcessAction {
        final Set<String> resources;
        final Function<WorkItem, List<WorkItem>> behavior;

        FakeProcessAction(Set<String> resources, Function<WorkItem, List<WorkItem>> behavior) {
            this.resources = resources;
            this.behavior = behavior;
        }

        @Override
        public Set<String> requiredResources(WorkItem item) {
            return resources;
        }

        @Override
        public List<WorkItem> doProcess(WorkItem item) {
            return behavior.apply(item);
        }
    }

    static PipelineStepConfig emptyConfig() {
        return new PipelineStepConfig(null, null, null, null, null, null, null, null);
    }

    static ResourceRegistry registry(Map<String, Integer> capacities) {
        return new ResourceRegistry(ResourceSettings.from(new CopybotConfig(null, null, capacities, null)));
    }

    static MainExecutor executor(List<PipelineStep<IInAction>> inSteps, List<PipelineStep<?>> itemSteps,
                                 boolean pipelining, ResourceRegistry reg) {
        return new MainExecutor(inSteps, itemSteps, pipelining, null, reg);
    }

    // ---- tests ----

    @Test
    public void concurrencyNeverExceedsResourceCapacity() {
        AtomicInteger current = new AtomicInteger();
        AtomicInteger maxObserved = new AtomicInteger();
        FakeProcessAction process = new FakeProcessAction(Set.of("proc"), item -> {
            int now = current.incrementAndGet();
            maxObserved.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            current.decrementAndGet();
            return List.of(item);
        });

        // disk capacity high so only "proc" (capacity 2) limits concurrency
        ResourceRegistry reg = registry(Map.of("proc", 2, "disk:*", 1000));
        MainExecutor exec = executor(
                List.of(new PipelineStep<>(null, new FakeInAction(10, null), emptyConfig())),
                List.of(new PipelineStep<>(null, process, emptyConfig())),
                true, reg);
        exec.run();

        assertEquals(2, maxObserved.get(), "capacity 2 must be saturated but never exceeded");
        assertEquals(10, exec.getState().getWorkItems().size());
        assertTrue(exec.getState().getWorkItems().stream().allMatch(w -> w.getStatus() == ItemStatus.DONE));
    }

    @Test
    public void twoPhaseMode_noProcessingBeforeListingEnds() {
        AtomicBoolean listingFinished = new AtomicBoolean(false);
        AtomicBoolean violation = new AtomicBoolean(false);
        FakeProcessAction process = new FakeProcessAction(Set.of(), item -> {
            if (!listingFinished.get()) {
                violation.set(true);
            }
            return List.of(item);
        });

        ResourceRegistry reg = registry(Map.of("disk:*", 1000));
        MainExecutor exec = executor(
                List.of(new PipelineStep<>(null, new FakeInAction(5, () -> listingFinished.set(true)), emptyConfig())),
                List.of(new PipelineStep<>(null, process, emptyConfig())),
                false, reg);
        exec.run();

        assertFalse(violation.get(), "two-phase mode must not process before listing completes");
        assertEquals(5, exec.getState().getWorkItems().size());
    }

    @Test
    public void pipeliningMode_processingOverlapsListing() {
        CountDownLatch firstProcessed = new CountDownLatch(1);
        AtomicBoolean overlapped = new AtomicBoolean(false);
        FakeProcessAction process = new FakeProcessAction(Set.of(), item -> {
            firstProcessed.countDown();
            return List.of(item);
        });

        ResourceRegistry reg = registry(Map.of("disk:*", 1000));
        // the listing emits one item then BLOCKS until that item has been processed
        FakeInAction inAction = new FakeInAction(1, () -> {
            try {
                overlapped.set(firstProcessed.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        MainExecutor exec = executor(
                List.of(new PipelineStep<>(null, inAction, emptyConfig())),
                List.of(new PipelineStep<>(null, process, emptyConfig())),
                true, reg);
        exec.run();

        assertTrue(overlapped.get(), "pipelining mode must process items while listing is still running");
    }

    @Test
    public void processReturningEmptyFiltersTheItemOut() {
        AtomicInteger secondStepCalls = new AtomicInteger();
        FakeProcessAction filter = new FakeProcessAction(Set.of(), item -> List.of());
        FakeProcessAction after = new FakeProcessAction(Set.of(), item -> {
            secondStepCalls.incrementAndGet();
            return List.of(item);
        });

        ResourceRegistry reg = registry(Map.of("disk:*", 1000));
        MainExecutor exec = executor(
                List.of(new PipelineStep<>(null, new FakeInAction(3, null), emptyConfig())),
                List.of(new PipelineStep<>(null, filter, emptyConfig()),
                        new PipelineStep<>(null, after, emptyConfig())),
                true, reg);
        exec.run();

        assertEquals(0, secondStepCalls.get(), "filtered items must not reach later steps");
        assertTrue(exec.getState().getWorkItems().stream().allMatch(w -> w.getStatus() == ItemStatus.DONE));
    }

    @Test
    public void processReturningTwoItemsForksASecondExecution() {
        AtomicInteger secondStepCalls = new AtomicInteger();
        FakeProcessAction splitter = new FakeProcessAction(Set.of(), item -> {
            try {
                return List.of(item, new WorkItem(Files.createFile(tempDir.resolve("forked-" + System.nanoTime() + ".txt"))));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        FakeProcessAction after = new FakeProcessAction(Set.of(), item -> {
            secondStepCalls.incrementAndGet();
            return List.of(item);
        });

        ResourceRegistry reg = registry(Map.of("disk:*", 1000));
        MainExecutor exec = executor(
                List.of(new PipelineStep<>(null, new FakeInAction(1, null), emptyConfig())),
                List.of(new PipelineStep<>(null, splitter, emptyConfig()),
                        new PipelineStep<>(null, after, emptyConfig())),
                true, reg);
        exec.run();

        assertEquals(2, secondStepCalls.get(), "both the original and the forked item must reach the next step");
        assertEquals(2, exec.getState().getWorkItems().size());
    }

    @Test
    public void oneFailingItemDoesNotStopTheOthers() {
        AtomicInteger processed = new AtomicInteger();
        AtomicInteger index = new AtomicInteger();
        FakeProcessAction process = new FakeProcessAction(Set.of(), item -> {
            if (index.incrementAndGet() == 2) {
                throw new IllegalStateException("boom on item 2");
            }
            processed.incrementAndGet();
            return List.of(item);
        });

        ResourceRegistry reg = registry(Map.of("disk:*", 1000));
        MainExecutor exec = executor(
                List.of(new PipelineStep<>(null, new FakeInAction(4, null), emptyConfig())),
                List.of(new PipelineStep<>(null, process, emptyConfig())),
                false, reg); // two-phase for a deterministic item count before processing
        exec.run();

        assertEquals(3, processed.get());
        long errors = exec.getState().getWorkItems().stream().filter(w -> w.getStatus() == ItemStatus.ERROR).count();
        long done = exec.getState().getWorkItems().stream().filter(w -> w.getStatus() == ItemStatus.DONE).count();
        assertEquals(1, errors);
        assertEquals(3, done);
        assertNotNull(exec.getState().getWorkItems().stream()
                .filter(w -> w.getStatus() == ItemStatus.ERROR).findFirst().orElseThrow().getError());
    }
}
```

- [ ] **Step 2 : Vérifier l'échec**

Run: `mvn -pl copybot-engine test -Dtest=MainExecutorTest`
Expected: FAIL — compilation (le constructeur de test et `getState()` n'existent pas).

- [ ] **Step 3 : Implémenter**

`MainExecutor.java` (réécriture complète) :

```java
package com.copybot.engine;

import com.copybot.engine.pipeline.PipelineConfig;
import com.copybot.engine.pipeline.PipelineState;
import com.copybot.engine.pipeline.PipelineStatus;
import com.copybot.engine.pipeline.PipelineStep;
import com.copybot.engine.pipeline.PipelineStepConfig;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.engine.plugin.PluginEngine;
import com.copybot.engine.resources.FootprintResolver;
import com.copybot.engine.resources.ResourceRegistry;
import com.copybot.plugin.api.action.IAction;
import com.copybot.plugin.api.action.IAnalyzeAction;
import com.copybot.plugin.api.action.IInAction;
import com.copybot.plugin.api.action.IOutAction;
import com.copybot.plugin.api.action.IProcessAction;
import com.copybot.plugin.api.action.WorkItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Phaser;
import java.util.function.Consumer;

/**
 * Runs one pipeline: one virtual thread per listing and per work item,
 * concurrency bounded by the ResourceRegistry (see the design spec).
 */
public class MainExecutor implements Runnable {

    private final PipelineConfig pipelineConfig;
    private final Consumer<PipelineState> watcher;
    private final ResourceRegistry registry;
    private final PipelineState state;

    private List<PipelineStep<IInAction>> inSteps;
    private List<PipelineStep<?>> itemSteps;
    private boolean startProcessingWhileListing;

    private ExecutorService taskExecutor;
    private CountDownLatch listingGate;
    private Phaser pending;

    public MainExecutor(PipelineConfig pipelineConfig, Consumer<PipelineState> watcher, ResourceRegistry registry) {
        this.pipelineConfig = pipelineConfig;
        this.watcher = watcher;
        this.registry = registry;
        this.state = new PipelineState(List.of());
        this.state.setRegistry(registry);
    }

    // visible for tests: runs with pre-resolved steps, bypassing PluginEngine
    MainExecutor(List<PipelineStep<IInAction>> inSteps, List<PipelineStep<?>> itemSteps,
                 boolean startProcessingWhileListing, Consumer<PipelineState> watcher, ResourceRegistry registry) {
        this.pipelineConfig = null;
        this.watcher = watcher;
        this.registry = registry;
        this.inSteps = inSteps;
        this.itemSteps = itemSteps;
        this.startProcessingWhileListing = startProcessingWhileListing;
        this.state = new PipelineState(List.of());
        this.state.setRegistry(registry);
    }

    @Override
    public void run() {
        if (pipelineConfig != null) {
            inSteps = doResolveStep(pipelineConfig.inSteps(), IInAction.class);
            itemSteps = resolveOtherSteps(pipelineConfig);
            startProcessingWhileListing = Boolean.TRUE.equals(pipelineConfig.startProcessingWhileListing());
        }

        state.setStatus(PipelineStatus.RUNNING);
        state.setListingInProgress(true);
        notifyWatcher();

        taskExecutor = Executors.newVirtualThreadPerTaskExecutor();
        listingGate = new CountDownLatch(inSteps.size());
        pending = new Phaser(1); // party 0 = this thread

        try {
            for (PipelineStep<IInAction> inStep : inSteps) {
                pending.register();
                taskExecutor.submit(() -> runListing(inStep));
            }
            pending.arriveAndAwaitAdvance(); // waits for all listings AND all items
            state.setStatus(PipelineStatus.SUCCESS);
        } finally {
            state.setListingInProgress(false);
            notifyWatcher();
            taskExecutor.shutdown();
        }
    }

    private void runListing(PipelineStep<IInAction> inStep) {
        try {
            Set<String> footprint = FootprintResolver.forListing(inStep.getAction(), inStep.getConfig());
            registry.acquireAll(footprint);
            try {
                inStep.getAction().listFiles(this::emitItem);
            } finally {
                registry.releaseAll(footprint);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            listingGate.countDown();
            if (listingGate.getCount() == 0) {
                state.setListingInProgress(false);
                notifyWatcher();
            }
            pending.arriveAndDeregister();
        }
    }

    private void emitItem(WorkItem workItem) {
        WorkItemExecution exec = new WorkItemExecution(workItem, itemSteps);
        state.getWorkItems().add(exec);
        notifyWatcher();
        submitItem(exec, 0);
    }

    private void submitItem(WorkItemExecution exec, int fromStep) {
        pending.register();
        taskExecutor.submit(() -> runItem(exec, fromStep));
    }

    private void runItem(WorkItemExecution exec, int fromStep) {
        try {
            if (!startProcessingWhileListing) {
                listingGate.await();
            }
            for (int i = fromStep; i < itemSteps.size(); i++) {
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
                    break;
                }
            }
            exec.setDone();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            exec.setError(t);
        } finally {
            notifyWatcher();
            pending.arriveAndDeregister();
        }
    }

    /**
     * @return true to continue with the next step, false when the item stops here (filtered out)
     */
    private boolean runStep(WorkItemExecution exec, PipelineStep<?> step, int stepIndex) {
        IAction action = step.getAction();
        WorkItem item = exec.getWorkItem();
        if (action instanceof IAnalyzeAction analyze) {
            analyze.doAnalyze(item);
            return true;
        }
        if (action instanceof IProcessAction process) {
            List<WorkItem> produced = process.doProcess(item);
            if (produced == null || produced.isEmpty()) {
                return false; // item filtered out
            }
            exec.replaceWorkItem(produced.get(0));
            for (int i = 1; i < produced.size(); i++) {
                WorkItemExecution forked = new WorkItemExecution(produced.get(i), itemSteps);
                state.getWorkItems().add(forked);
                notifyWatcher();
                submitItem(forked, stepIndex + 1);
            }
            return true;
        }
        if (action instanceof IOutAction out) {
            out.writeItem(item);
            return true;
        }
        throw new UnsupportedOperationException("Unsupported action type: " + action.getClass());
    }

    private static List<PipelineStep<?>> resolveOtherSteps(PipelineConfig pipelineConfig) {
        List<PipelineStep<?>> steps = new ArrayList<>();
        steps.addAll(doResolveStep(pipelineConfig.analyseSteps(), IAnalyzeAction.class));
        steps.addAll(doResolveStep(pipelineConfig.actionSteps(), IProcessAction.class));
        if (pipelineConfig.outStep() != null) {
            steps.add(PluginEngine.resolve(pipelineConfig.outStep(), IOutAction.class));
        }
        return Collections.unmodifiableList(steps);
    }

    private static <A extends IAction> List<PipelineStep<A>> doResolveStep(List<PipelineStepConfig> stepConfigs, Class<A> actionClass) {
        if (stepConfigs == null || stepConfigs.isEmpty()) {
            return List.of();
        }
        List<PipelineStep<A>> steps = new ArrayList<>(stepConfigs.size());
        for (PipelineStepConfig stepConfig : stepConfigs) {
            steps.add(PluginEngine.resolve(stepConfig, actionClass));
        }
        return Collections.unmodifiableList(steps);
    }

    private void notifyWatcher() {
        if (watcher != null) {
            watcher.accept(state);
        }
    }

    PipelineState getState() {
        return state;
    }
}
```

Points d'attention :
- Le `Phaser` : chaque tâche est `register()` **avant** son submit, et `arriveAndDeregister()` dans son `finally` ; les listings enregistrent leurs items avant de se désinscrire, donc le compte ne tombe jamais à zéro prématurément. `arriveAndAwaitAdvance()` du thread principal rend `run()` bloquant jusqu'à la fin réelle.
- Nouveauté fonctionnelle actée par la spec : `outStep` est enfin résolu (l'ancien code avait un TODO) — les pipelines existants avec `outStep` exécutent maintenant leur écriture.

- [ ] **Step 4 : Vérifier le passage**

Run: `mvn -pl copybot-engine test -Dtest=MainExecutorTest`
Expected: PASS (6 tests). Puis `mvn -pl copybot-engine test` complet.

- [ ] **Step 5 : Commit**

```bash
git add copybot-engine/src/main/java/com/copybot/engine/MainExecutor.java copybot-engine/src/test/java/com/copybot/engine/MainExecutorTest.java
git commit -m "feat(engine): virtual-thread pipeline execution bounded by resource footprints"
```

---

### Task 8: Câblage `CopybotEngine`, suppression de `RunnableCallback`, module-info, build complet

**Files:**
- Modify: `copybot-engine/src/main/java/com/copybot/engine/CopybotEngine.java`
- Delete: `copybot-engine/src/main/java/com/copybot/engine/asynch/RunnableCallback.java` (et le répertoire `asynch`)
- Modify: `copybot-engine/src/main/java/module-info.java`

**Interfaces:**
- Consumes: `MainExecutor(PipelineConfig, Consumer<PipelineState>, ResourceRegistry)` (Task 7), `ResourceSettings.from(CopybotConfig)` (Task 1).
- Produces: signatures publiques de `CopybotEngine` inchangées (`init`, `run`, `destroy`, `waitForCompletion`) — l'UI et le CLI ne changent pas.

- [ ] **Step 1 : Modifier `CopybotEngine`**

Changements dans `CopybotEngine.java` :
1. Supprimer les champs statiques `inSteps`/`analyzeSteps`/`processSteps`/`outSteps` (poids mort — les steps vivent dans `MainExecutor`) et l'import `com.copybot.engine.asynch.RunnableCallback`.
2. Ajouter un champ `private static CopybotConfig config;` et dans `init(...)` affecter le résultat du parse : `config = GsonUtil.getGson().fromJson(configString, CopybotConfig.class);` (la variable locale devient le champ).
3. Remplacer `executor = Executors.newCachedThreadPool();` par `executor = Executors.newVirtualThreadPerTaskExecutor();`.
4. Réécrire `run(...)` :

```java
    public static void run(Path pipelinePath, Consumer<PipelineState> watcher) {
        PipelineConfig pipelineConfig;
        try {
            pipelineConfig = GsonUtil.getGson().fromJson(Files.newBufferedReader(pipelinePath), PipelineConfig.class);
        } catch (IOException e) {
            throw CopybotException.ofResource(e, "pipeline.not-json");
        }

        ResourceRegistry registry = new ResourceRegistry(ResourceSettings.from(config));
        MainExecutor mainExecutor = new MainExecutor(pipelineConfig, watcher, registry);

        synchronized (CopybotEngine.class) {
            if (mainTask != null) {
                throw new IllegalStateException("Engine already running");
            }
            mainTask = executor.submit(() -> {
                try {
                    mainExecutor.run();
                } finally {
                    synchronized (CopybotEngine.class) {
                        mainTask = null;
                    }
                }
            });
        }
    }
```

(imports à ajouter : `com.copybot.engine.resources.ResourceRegistry`, `com.copybot.engine.resources.ResourceSettings` ; la synchronisation passe de `executor` à `CopybotEngine.class` pour ne plus dépendre de l'ordre d'init.)

`waitForCompletion()` : garder, mais capturer la `Future` dans une variable locale pour éviter le NPE si la tâche se termine entre-temps :

```java
    public static void waitForCompletion() throws InterruptedException, ExecutionException {
        Future<?> task;
        synchronized (CopybotEngine.class) {
            task = mainTask;
        }
        if (task != null) {
            task.get();
        }
    }
```

- [ ] **Step 2 : Supprimer `RunnableCallback`**

```bash
git rm copybot-engine/src/main/java/com/copybot/engine/asynch/RunnableCallback.java
```

- [ ] **Step 3 : module-info**

Dans `copybot-engine/src/main/java/module-info.java`, ajouter après la ligne `exports com.copybot.engine.pipeline to ...` :

```java
    exports com.copybot.engine.resources to com.copybot.ui;
```

- [ ] **Step 4 : Vérifier — tests moteur puis build complet**

Run: `mvn -pl copybot-engine test`
Expected: PASS — tous les tests, y compris `MainTest` (qui traverse maintenant le vrai chemin `CopybotEngine.run` → `MainExecutor` virtuel).

Attention : l'`outStep` des pipelines est désormais réellement exécuté (c'était un TODO avant). Si `MainTest` échoue parce que `src/test/resources/com/copybot/engine/test-pipeline.json` déclare un `outStep` qui écrit vers un chemin invalide, corriger le JSON de test pour écrire sous `target/` (ne pas désactiver le test).

Run: `mvn clean install` (racine)
Expected: BUILD SUCCESS sur tous les modules du reactor (ui, engine, plugins, demo). Si `copybot-ui` échoue à la compilation JPMS, vérifier l'export du Step 3.

- [ ] **Step 5 : Commit**

```bash
git add copybot-engine/src/main/java/com/copybot/engine/CopybotEngine.java copybot-engine/src/main/java/module-info.java
git commit -m "feat(engine): wire ResourceRegistry into CopybotEngine, drop callback machinery"
```

---

## Vérification finale (fin de plan)

- [ ] `mvn clean install` complet vert (2 fois de suite pour vérifier le clean).
- [ ] Relire la spec section par section et confirmer la couverture : §1 modèle (Tasks 1–2), §2 registre (Tasks 2–3), §3 exécution (Task 7), §4 empreintes (Tasks 4–5), §5 config (Task 1), §6 observabilité (Task 6), §7 erreurs (Task 7, test `oneFailingItemDoesNotStopTheOthers`), §8 tests (Tasks 2–7).
- [ ] Signaler à l'utilisateur que la v1 est prête, avec le rappel des idées v2 de la spec.

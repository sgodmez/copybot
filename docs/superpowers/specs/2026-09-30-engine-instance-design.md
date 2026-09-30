# Moteur en instance, annulation / pause, codes de sortie

Date : 2026-09-30
Statut : validé (design, brainstorming avec l'utilisateur)

## Contexte

`CopybotEngine` est entièrement statique : config globale, un exécuteur, une tâche à la fois (« Engine already running »), plugins chargés une fois par JVM, et `destroy()` comme seul moyen d'arrêter. L'UI desktop (chantier suivant) doit embarquer le cœur proprement, arrêter ou mettre en pause une copie, et la CLI doit rendre des codes de sortie exploitables en script (aujourd'hui toujours 0).

Décisions actées :

- **Une instance = un pipeline à la fois.** Plusieurs pipelines en parallèle ⇒ plusieurs instances de l'UI, ou (plus tard) un pipeline agrégeant des sous-pipelines.
- **Annulation propre et pause / reprise** exposées à l'appelant.
- Codes de sortie CLI conventionnels.

## 1. API

```java
try (CopybotEngine engine = CopybotEngine.create(Optional.of(configPath))) {   // AutoCloseable
    Plan plan = engine.prepare(pipelinePath, watcher);          // bloquant, ne lève pas pour un échec de pipeline
    Execution run = engine.execute(plan, override);             // asynchrone
    run.pause(); run.resume(); run.cancel();
    PipelineStatus status = run.await();                        // statut final
}
Execution legacy = engine.run(pipelinePath, watcher);           // préparation + exécution (ou une phase sans reprise)
```

- `CopybotEngine.create(Optional<Path> configPath)` : lit la config (défaut `./config.json`, mêmes erreurs `config.not-found` / `config.not-json` qu'aujourd'hui), s'assure que les plugins sont chargés, crée l'exécuteur de l'instance.
- **Une opération à la fois par instance** : `prepare`, `execute` ou `run` pendant qu'une exécution de cette instance est active ⇒ `IllegalStateException` explicite. `prepare` s'exécute dans le thread appelant (l'UI l'appelle hors thread FX) et compte comme opération active pendant sa durée.
- `close()` : annule l'exécution active éventuelle, attend sa fin (délai de grâce de 5 s existant), arrête l'exécuteur. Idempotent.
- **L'API statique disparaît** (`init`, `run`, `prepare`, `execute`, `waitForCompletion`, `destroy`). CLI, UI et tests passent à l'instance.

## 2. Échecs de préparation : un seul style

- Les erreurs **avant** qu'un plan existe (fichier pipeline introuvable / JSON invalide / mode de reprise inconnu) lèvent `CopybotException`, comme aujourd'hui.
- Toute erreur **pendant** la préparation (listing, fichier d'état invalide, mode `destination` sans `resolveTarget`, étape introuvable) ne lève plus : `prepare` renvoie un `Plan` au statut `ERROR` dont `getState().getFailure()` porte la cause. `MainExecutor.prepare()` capture l'exception au lieu de la relancer.
- Même règle pour `run` : l'échec est dans l'état final, jamais une exception du thread de fond.
- Mode de reprise présent mais non chaîne (ex. `"mode": 1`) ⇒ `resume.mode.unknown` également.

## 3. `Execution`

Objet renvoyé par `execute` et `run` :

- `PipelineState getState()` ; `PipelineStatus await() throws InterruptedException` ; `boolean isDone()`.
- `cancel()` : arrêt propre — interruption des tâches de la phase en cours (items et listings), permis relâchés, curseur **inchangé**, statut final **`CANCELLED`** (nouveau, distinct de `ERROR`). Idempotent ; sans effet sur une exécution terminée.
- `pause()` / `resume()` : via le registre de ressources (§4). Statut `PAUSED` pendant la pause, retour à `RUNNING` à la reprise ; sans effet sur le statut final.
- Un `cancel()` pendant une pause lève la pause puis annule.

Interruption du thread pipeline (comportement existant de `MainExecutor.run()` interrompu) ⇒ désormais `CANCELLED` au lieu de `ERROR` : les tests existants qui attendaient `ERROR` après interruption sont mis à jour en conséquence.

## 4. Pause

- `ResourceRegistry.pause()` / `resume()` / `isPaused()` : pendant la pause, `acquireAll` n'accorde plus rien (les demandeurs restent en attente, interruptibles) ; les étapes déjà en cours se terminent et relâchent normalement. `resume()` réveille les attentes.
- Le listing acquiert son disque une seule fois pour toute sa durée : pour que la pause gèle aussi la préparation, l'émission d'un item par un listing attend la fin de la pause (`registry.awaitNotPaused()`, interruptible) avant de créer l'item.
- Le snapshot du registre expose l'état de pause (pour l'UI).

## 5. Plugins

`PluginEngine.load` devient thread-safe et idempotent : le premier appel charge, les suivants sont ignorés (les couches de modules JPMS ne se déchargent pas ; une seconde config avec d'autres chemins de plugins dans le même processus est ignorée avec un avertissement dans le log). Le drapeau `pluginsLoaded` de `CopybotEngine` disparaît.

## 6. CLI

`Copybot` passe en `Callable<Integer>` (picocli) et crée une instance (`try-with-resources`).

| Code | Cas |
|---|---|
| 0 | `SUCCESS` (les SKIPPED ne sont pas des erreurs) ; `--dry-run` préparé sans erreur |
| 1 | exécution terminée en `ERROR` (items en erreur, curseur non écrit, échec de listing) |
| 2 | erreur fatale : config / pipeline introuvable ou invalide, préparation en `ERROR`, `--from-file` inconnu, options invalides (code picocli existant) |
| 130 | `CANCELLED` (Ctrl+C) |

- Ctrl+C : un hook d'arrêt appelle `cancel()` sur l'exécution active et attend sa fin (5 s max) ; le hook est retiré à la fin normale.
- `--debug` garde son rôle (stacktrace) mais ne change plus le code de sortie.
- Le rapport d'erreurs existant (échec pipeline + une ligne `ERROR <nom>  <message>` par item) est conservé.

## 7. UI (adaptation minimale dans ce chantier)

`CopybotMainUi` crée une instance au démarrage et la ferme à l'arrêt ; `HelloController` l'utilise (`run` → `Execution`). La refonte de l'UI est le chantier suivant.

## 8. Reliquats du chantier reprise intégrés ici

- `MainExecutor.runSinglePhase` : `setFailure` sur exception, comme `prepare`/`execute`.
- Première erreur de listing enregistrée atomiquement (`compareAndSet`).
- `WorkItemExecution.setResumeKey` : figée une seule fois (un second appel est ignoré ou lève), documenté.
- Javadoc périmée de `MainTest` (chargement unique des plugins) et du constructeur de `MainExecutor` (une notification terminale par phase).

## 9. Tests

- `Execution` : `cancel` pendant la préparation et pendant l'exécution ⇒ `CANCELLED`, permis relâchés, curseur inchangé ; `pause` bloque le démarrage de nouvelles étapes (étape bloquante + compteur) et `resume` les libère ; `cancel` pendant une pause.
- `ResourceRegistry` : pause/reprise, interruption d'un demandeur pendant la pause.
- Instance : deux opérations simultanées ⇒ `IllegalStateException` ; `close` annule ; deux instances successives dans la même JVM (plugins chargés une fois).
- Préparation : chaque cause d'échec ⇒ `Plan` en `ERROR` avec `failure`, sans exception.
- CLI : un test par code de sortie (0, 1, 2, 130 via annulation programmatique si Ctrl+C n'est pas simulable).
- Les tests existants passent à l'API d'instance ; seules les assertions `ERROR` après interruption deviennent `CANCELLED`, et `--all` avec un item en échec attend désormais le code 1.

## Hors périmètre

Pipeline agrégeant des sous-pipelines ; exécutions parallèles dans une instance ; refonte de l'UI (chantier suivant).

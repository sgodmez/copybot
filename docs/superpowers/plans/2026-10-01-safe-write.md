# Écriture sûre (`file.write`) et filtres de lecture (`file.read`) — Plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rendre `file.write` sûr et idempotent : conflit à deux branches (identique / différent) avec comparaison configurable (`partialHash` par défaut), écriture via un temporaire caché puis déplacement (`tempAndRename`, ou `direct`), SHA-256 calculé au vol, vérification configurable (`size` par défaut), suppression optionnelle de la source ; faire compter comme succès, pour le curseur de reprise, un item sauté à l'écriture ; remonter les avertissements de configuration (état, dry-run, stderr, UI) ; donner à `file.read` un `recursive` effectif, des globs `include` / `exclude` et l'exclusion des fichiers cachés.

**Architecture:** Deux ajouts `default` à l'API plugin (`IOutAction.write(WorkItem, WriteContext)` → `WriteResult`, `IAction.configWarnings()`) et deux records (`WriteResult`, `WriteContext`). `MainExecutor` crée un `runId` par exécution, appelle `write` et termine en `SKIPPED` (avec la raison) un item sauté ; il collecte les `configWarnings` à la résolution des étapes dans `PipelineState.getWarnings()`. `ResumeResolver.nextCursor` compte `SKIPPED` comme un succès. `FileWriteAction` est recâblée sur quatre classes package-private testées isolément : `FileWriteSettings` (validation, défauts, compatibilité `overwrite`, avertissement), `FileComparison` (tailles, dates ±2 s, SHA-256 partiel / complet), `ConflictResolver` (skip / rename / overwrite / error) et `SafeFileWriter` (temporaire ou direct, hash au vol, vérification, orphelins). `FileReadAction` passe à `Files.walkFileTree` avec profondeur, globs et filtre « caché ».

**Tech Stack:** Java 25 (JPMS), Maven multi-module, JUnit Jupiter `${junit.version}` (6.1.2), Gson, picocli 4.7.7, JavaFX (copybot-ui). Aucune nouvelle dépendance.

**Spec:** `docs/superpowers/specs/2026-09-30-safe-write-design.md` (à lire avant chaque tâche).

**Base :** `feature/wave2` avec le plan engine-instance **terminé** (HEAD ≥ `05fa5fb` « Keep a cancelled start terminal when its task throws ») : `CopybotEngine` en instance, `Execution`, statuts `CANCELLED` / `PAUSED`, `PipelineState.getFailure()` en `AtomicReference`, `PipelineState.isPreparationFailed()`, `Copybot implements Callable<Integer>` avec codes de sortie 0 / 1 / 2 / 130. Tout le code ci-dessous a été compilé et testé contre cette base (build `mvn -o clean install` vert à la fin de la tâche 12).

## Global Constraints

- Java 25 ; code, noms et javadoc en **anglais** (convention du code existant) ; ce plan et la spec en français.
- **API plugin : seulement des ajouts compatibles** (spec §6) : méthodes `default` `IOutAction.write` et `IAction.configWarnings`, records `WriteResult` / `WriteContext`, constante `WorkItemMetadata.SHA256`. Aucune méthode existante ne change de signature ; `copybot-plugin/*` et `copybot-plugin-demo/*` ne sont **pas** modifiés et compilent tels quels.
- Les classes d'aide de `file.write` (`FileWriteSettings`, `FileComparison`, `ConflictResolver`, `SafeFileWriter`) sont **package-private** dans `com.copybot.plugin.embedded.actions` (paquet exporté : elles ne deviennent pas de l'API). Les records de configuration lus par Gson (`FileWriteConfig`, `FileWriteConfig.OnConflict`, `FileReadConfig`) restent **publics**.
- Modifications de tests existants **autorisées, et seulement celle-ci** : `ResumeEndToEndTest.allReimportsEverything` attend `0` au lieu de `1` (tâche 10) — conséquence directe de la spec §1 : avec `overwrite: false`, une cible **identique** est désormais sautée (`ifIdentical: skip`) au lieu de faire échouer l'item. Tous les autres changements de tests sont des **ajouts** de tests ou de fakes.
- Erreurs de configuration de `file.write` / `file.read` (valeur inconnue, `outPattern` absent, `overwrite` + `onConflict`, glob invalide) : `CopybotException` levée par `loadConfig`, donc à la résolution des étapes ⇒ préparation en `ERROR` (`isPreparationFailed()`), code CLI `2`. Erreurs **par item** (conflit `error`, vérification, suppression, E/S) : exception de `write` ⇒ item `ERROR`, jamais le pipeline.
- Messages utilisateur via `ResourcesEngine.getString` / `CopybotException.ofResource`, clés ajoutées à `engineBundle.properties` **et** `engineBundle_fr.properties` (spec §8), toutes dans la tâche 1. Format `MessageFormat` : apostrophe doublée ; nombres passés en `String.valueOf(...)` (pas de séparateur de milliers).
- **Règle byte-safe des bundles** : `engineBundle_fr.properties` est en **ISO-8859-1** avec fins de ligne **CRLF** (et `engineBundle.properties` en ASCII CRLF) ; ils sont **append-only via `printf`**, jamais ouverts avec Edit/Write. Dans ce Git Bash, un `é` littéral s'écrit en octal **`\134u00e9`** dans la chaîne de format de `printf` (sinon `printf` le convertit en UTF-8), l'apostrophe en **`\047`** ; le fichier EN (pur ASCII) s'ajoute avec `printf '%s\r\n' '<ligne>'`. Contrôles après ajout : `file <fichier>` ⇒ `ISO-8859 text, with CRLF line terminators` (resp. `ASCII text, with CRLF line terminators`) ; `grep -c $'\xc3' engineBundle_fr.properties` ⇒ `0` et `grep -c $'\xef\xbf\xbd' engineBundle_fr.properties` ⇒ `0` (grep sort en code 1 quand il compte 0 : attendu) ; `git diff` ne montre que les lignes ajoutées.
- **Préserver les fins de ligne de chaque fichier.** CRLF : `Copybot.java`, `engine/pipeline/PipelineState.java`, `plugin/api/action/IAction.java`, `IOutAction.java` (sans fin de ligne après la dernière `}`), `WorkItemMetadata.java`, `plugin/embedded/actions/FileWriteAction.java`, `FileWriteConfig.java`, `FileReadAction.java`, `FileReadConfig.java`, les deux `engineBundle*.properties`. LF : `PlanPrinter.java`, `MainExecutor.java`, `ItemStatus.java`, `WorkItemExecution.java`, `ResumeResolver.java`, tous les tests, les fichiers UI. Nouveaux fichiers : LF. L'outil Edit conserve le CRLF d'un fichier CRLF ; après une **réécriture complète** d'un fichier CRLF avec Write : `unix2dos -q <fichier>`. Contrôle après chaque tâche : `git ls-files --eol <fichiers modifiés>` ⇒ `w/crlf` ou `w/lf` comme avant, jamais `w/mixed` (sinon `unix2dos -q` sur un fichier CRLF).
- Créer / réécrire les fichiers Java avec l'outil **Write** (pas de heredoc Bash : dans cet environnement les `\\` d'un heredoc passé par l'outil Bash sont altérés).
- Les tests ne comparent **jamais** le texte d'un message traduit (locale FR ou EN) : présence (`!startsWith("%")`), nom de fichier ou valeur citée (`contains("photo.jpg")`), préfixe CLI non traduit (`"Warning: "`), ou chaîne littérale d'un fake.
- Toute attente d'un test est bornée : les nouveaux tests n'attendent aucun thread (exécutions synchrones `exec.run()` / CLI `Copybot.doMain`) ; aucun `await()` / `join()` sans délai n'est introduit.
- Maven **hors ligne** depuis la racine du repo : tests du module `mvn -o -q -pl copybot-engine test` ; un test `mvn -o -q -pl copybot-engine test -Dtest=<Classe>` ; compilation UI `mvn -o -q -pl copybot-engine,copybot-ui compile` ; vérification finale `mvn -o clean install`. En `-q`, un succès n'affiche que les `WARNING` `sun.misc.Unsafe` de Maven, les stacktraces imprimées par les tests CLI en `--debug` et la sortie standard des tests CLI : « PASS » = aucune ligne `[ERROR]` et code retour 0.
- Branche `feature/wave2` (plan engine-instance terminé). Un commit par tâche, `git add` ciblé sur les fichiers de la tâche uniquement (les non suivis `.superpowers/` et `copybot-ui/*.ico` / `*.png` ne sont **jamais** ajoutés). Commit : `git commit -m "<sujet>" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"`.

## Carte des fichiers

Chemins de production sous `copybot-engine/src/main/java/com/copybot/`, de test sous `copybot-engine/src/test/java/com/copybot/`, bundles sous `copybot-engine/src/main/resources/com/copybot/engine/i18n/`.

| Fichier | Rôle | Tâches |
|---|---|---|
| `engineBundle.properties`, `engineBundle_fr.properties` | + 11 messages (skip, conflit, vérification, suppression, configuration, avertissement, glob) | 1 |
| `plugin/api/action/WriteResult.java` | **Créer** — `record WriteResult(Outcome, Path target, String reason)` | 2 |
| `plugin/api/action/WriteContext.java` | **Créer** — `record WriteContext(String runId)` | 2 |
| `plugin/api/action/IOutAction.java` | + `default WriteResult write(WorkItem, WriteContext)` | 2 |
| `plugin/api/action/IAction.java` | + `default List<String> configWarnings()` | 2 |
| `plugin/api/action/WorkItemMetadata.java` | + constante `SHA256 = "sha256"` | 10 |
| `engine/MainExecutor.java` | `runId` par exécution, appel de `write`, item `SKIPPED` (3) ; collecte des `configWarnings` (5) | 3, 5 |
| `engine/pipeline/WorkItemExecution.java`, `engine/pipeline/ItemStatus.java` | javadoc de `SKIPPED` / `setSkipped` | 3 |
| `engine/resume/ResumeResolver.java` | `nextCursor` : `DONE` **ou** `SKIPPED` = succès | 4 |
| `engine/pipeline/PipelineState.java` | `getWarnings()` / `setWarnings(List)` | 5 |
| `plugin/embedded/actions/FileWriteConfig.java` | réécriture : champs optionnels, `OnConflict`, `overwrite` en `Boolean` | 6 |
| `plugin/embedded/actions/FileWriteSettings.java` | **Créer** — configuration validée, défauts, compatibilité, avertissement | 6 |
| `plugin/embedded/actions/FileComparison.java` | **Créer** — comparaison (taille, date ±2 s, SHA-256 partiel / complet) | 7 |
| `plugin/embedded/actions/ConflictResolver.java` | **Créer** — skip / rename / overwrite / error | 8 |
| `plugin/embedded/actions/SafeFileWriter.java` | **Créer** — temporaire / direct, hash au vol, vérification, orphelins | 9 |
| `plugin/embedded/actions/FileWriteAction.java` | `loadConfig` + `configWarnings` (6) ; réécriture sur les classes d'aide + `deleteSource` (10) | 6, 10 |
| `plugin/embedded/actions/FileReadConfig.java`, `FileReadAction.java` | réécriture : `recursive`, `include`, `exclude`, `includeHidden` | 11 |
| `PlanPrinter.java` | lignes `Warning:` des `configWarnings` | 12 |
| `Copybot.java` | avertissements sur stderr au démarrage | 12 |
| `copybot-ui/src/main/java/com/copybot/ui/HelloController.java`, `copybot-ui/src/main/resources/com/copybot/ui/views/hello-view.fxml` | bandeau d'avertissements | 12 |
| Tests : `resources/SafeWriteBundleTest.java` | **Créer** — clés présentes dans les deux bundles | 1 |
| Tests : `plugin/api/action/WriteApiTest.java` | **Créer** — défauts de l'API | 2 |
| Tests : `engine/MainExecutorResumeTest.java` | + fakes `SkippingOut`, `WarningIn`, `WarningAnalyze` et tests | 3, 4, 5 |
| Tests : `engine/resume/ResumeResolverTest.java` | + curseur et `SKIPPED` | 4 |
| Tests : `plugin/embedded/actions/FileWriteSettingsTest.java` | **Créer** | 6 |
| Tests : `plugin/embedded/actions/FileComparisonTest.java` | **Créer** | 7 |
| Tests : `plugin/embedded/actions/ConflictResolverTest.java` | **Créer** | 8 |
| Tests : `plugin/embedded/actions/SafeFileWriterTest.java` | **Créer** | 9 |
| Tests : `plugin/embedded/actions/FileWriteActionTest.java` | + tests de l'écriture sûre et de `deleteSource` | 10 |
| Tests : `plugin/embedded/actions/FileReadActionTest.java` | **Créer** | 11 |
| Tests : `engine/ResumeEndToEndTest.java` | + erreur de configuration (6) ; `--all` ⇒ 0, deuxième passage sauté (10) ; avertissements CLI (12) | 6, 10, 12 |

## Interprétations retenues (ambiguïtés de la spec)

1. **`runId`** : un par `MainExecutor` (une exécution = `prepare` + `execute`, qui partagent le même id ; rien n'est écrit pendant `prepare`), `UUID.randomUUID()`. `WriteContext` refuse un `runId` vide ou contenant `.`, `/` ou `\` (il doit pouvoir être relu comme dernier segment de `.<nom>.<runId>.copybot-tmp`).
2. **Temporaire « caché »** : nom commençant par un point (convention Unix / Synology). Pas d'attribut caché Windows : il suivrait le fichier au renommage.
3. **Vérification en `tempAndRename`** : faite sur le temporaire (qui est sur la destination), **avant** le déplacement : un échec (copie ou vérification) ne touche jamais une cible existante, même en `overwrite`. En `direct`, sur le fichier final.
4. **Date de la copie** : la cible reçoit la date de modification de la source (métadonnée `lastModified`, sinon le fichier local) ; sans cela `sizeAndDate` ne reconnaîtrait jamais une copie faite par Copybot.
5. **`rename`** : à chaque candidat existant, la politique `ifIdentical` / `ifDifferent` correspondante s'applique à nouveau (spec : « s'il est identique, on applique ifIdentical ») ; nom `nom (n).ext`, `nom (n)` sans extension, le point initial d'un fichier caché n'est pas une extension.
6. **`overwrite` hérité** : ne fixe que `ifDifferent` (`true` ⇒ `overwrite`, `false` ⇒ `error`) et `ifIdentical: skip` ; `compare` garde son défaut `partialHash`.
7. **Valeurs inconnues** (`verify: "readback"`…) et `outPattern` absent : erreur de configuration (jamais de retour silencieux au défaut, cf. le piège Gson des enums du mode de reprise).
8. **Concurrence** sans `replaceExisting` : le déplacement final (`Files.move` sans option) et `CREATE_NEW` en `direct` ne remplacent jamais un fichier apparu entre-temps : l'item échoue.
9. **Déplacement existant** (source temporaire ou `isDeleteAfterCompletion`, même fournisseur de FS) : conservé tel quel (conflit appliqué, ni hash ni vérification : rien n'est réécrit). `deleteSource` ne déclenche **pas** ce chemin : une source supprimée par `deleteSource` a toujours été copiée puis vérifiée.
10. **`deleteSource`** : seulement pour une source locale (`isLocal()` / temporaire) ; une source non locale (URL) n'est pas supprimée, sans erreur. Suppression par `deleteIfExists` (une source déjà disparue n'est pas une erreur).
11. **Orphelins** : seuls les fichiers `.*.copybot-tmp` du répertoire cible (non récursif) ; best effort (un fichier indélébile, par ex. ouvert par une autre copie, est ignoré).
12. **Taille attendue** d'une vérification : métadonnée `size` de l'item, sinon le nombre d'octets copiés.
13. **Globs** : insensibles à la casse en passant motif et chemin relatif en minuscules ; un motif commençant par `**/` vaut aussi au premier niveau (`**/*.NEF` prend `a.NEF` à la racine) ; `include` absent **ou vide** ⇒ tout ; le filtre « caché » ne s'applique jamais au répertoire `path` lui-même (une racine de lecteur Windows est cachée + système) ; `recursive: false` ⇒ seulement les fichiers du premier niveau.
14. **`configWarnings`** : collectés une fois à la résolution des étapes, étapes IN puis étapes d'items dans l'ordre ; un plugin qui renvoie `null` n'a rien à dire.
15. **CLI** : dry-run ⇒ lignes `Warning:` sur stdout (configuration puis reprise), rien sur stderr ; exécution réelle ⇒ `Warning: …` sur stderr **une fois**, dès que les étapes sont résolues (watcher), sur tous les chemins (`run`, `prepare` + `execute`).
16. **UI** : bandeau minimal dans la vue de dev actuelle (`hello-view.fxml`) ; l'UI de bureau complète relève de sa propre spec.
17. **Bundles** : les messages vont dans `engineBundle*` (spec §8), pas dans `pluginBundle*` ; appelés depuis `FileWriteAction`, ils sont trouvés par le repli du bundle du plugin embarqué sur celui du moteur.
18. **Curseur** : un item **sélectionné** qui finit `SKIPPED` ne peut l'être que par l'exécution (le point de reprise ne saute que les non-sélectionnés) ; il compte comme réussi, sauf si un de ses forks a échoué.
19. **`WriteResult`** : `skipped` exige une raison non vide ; un `write` qui renvoie `null` est traité comme `WRITTEN`.

---

### Task 1: Messages de l'écriture sûre et des filtres dans les bundles

**Files:**
- Modify (append-only): `copybot-engine/src/main/resources/com/copybot/engine/i18n/engineBundle.properties` (ASCII CRLF)
- Modify (append-only): `copybot-engine/src/main/resources/com/copybot/engine/i18n/engineBundle_fr.properties` (ISO-8859-1 CRLF)
- Create: `copybot-engine/src/test/java/com/copybot/resources/SafeWriteBundleTest.java`

**Interfaces:**
- Consumes: —
- Produces: clés `write.skip.identical` ({0} cible), `write.skip.exists` ({0} cible), `write.conflict.error` ({0} cible), `write.verify.size` ({0} cible, {1} taille attendue, {2} taille lue), `write.verify.hash` ({0} cible), `write.delete-source.failed` ({0} source, {1} cible), `write.config.overwrite-with-on-conflict`, `write.config.unknown-value` ({0} champ, {1} valeur, {2} valeurs attendues), `write.config.no-out-pattern`, `write.warn.delete-without-read-back` ({0} valeur de `verify`), `read.config.invalid-glob` ({0} motif).

- [ ] **Step 1: Write the failing test**

`copybot-engine/src/test/java/com/copybot/resources/SafeWriteBundleTest.java` :

```java
package com.copybot.resources;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.text.MessageFormat;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/** The messages of the safe write and the read filters exist in both engine bundles (spec safe-write §8). */
public class SafeWriteBundleTest {

    static final List<String> KEYS = List.of(
            "write.skip.identical",
            "write.skip.exists",
            "write.conflict.error",
            "write.verify.size",
            "write.verify.hash",
            "write.delete-source.failed",
            "write.config.overwrite-with-on-conflict",
            "write.config.unknown-value",
            "write.config.no-out-pattern",
            "write.warn.delete-without-read-back",
            "read.config.invalid-glob");

    /** Properties.load(InputStream) reads ISO-8859-1 and the backslash-u escapes, like ResourceBundle. */
    private static Properties bundle(String name) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = SafeWriteBundleTest.class.getResourceAsStream("/com/copybot/engine/i18n/" + name)) {
            assertNotNull(in, name);
            properties.load(in);
        }
        return properties;
    }

    @Test
    public void everyKeyIsInBothBundlesAndIsAValidMessageFormat() throws IOException {
        for (String file : List.of("engineBundle.properties", "engineBundle_fr.properties")) {
            Properties properties = bundle(file);
            for (String key : KEYS) {
                String value = properties.getProperty(key);
                assertNotNull(value, key + " in " + file);
                assertDoesNotThrow(() -> new MessageFormat(value), key + " in " + file);
                assertFalse(value.contains("�"), key + " in " + file + " was re-encoded");
            }
        }
    }

    @Test
    public void everyKeyIsResolvedByTheEngine() {
        for (String key : KEYS) {
            assertFalse(ResourcesEngine.getString(key, "a", "b", "c").startsWith("%"), key);
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -o -q -pl copybot-engine test -Dtest=SafeWriteBundleTest`
Expected: FAIL — `write.skip.identical in engineBundle.properties ==> expected: not <null>` et `%write.skip.identical`.

- [ ] **Step 3: Append the messages (shell Bash, depuis la racine du repo)**

```bash
EN=copybot-engine/src/main/resources/com/copybot/engine/i18n/engineBundle.properties
FR=copybot-engine/src/main/resources/com/copybot/engine/i18n/engineBundle_fr.properties
printf '%s\r\n' 'write.skip.identical=Identical to the destination ({0})' >> $EN
printf '%s\r\n' 'write.skip.exists=Already exists at the destination ({0})' >> $EN
printf '%s\r\n' 'write.conflict.error=The destination already exists: {0}' >> $EN
printf '%s\r\n' 'write.verify.size=Verification failed for {0}: {2} bytes instead of {1}' >> $EN
printf '%s\r\n' 'write.verify.hash=Verification failed for {0}: the content read back differs from the copied content' >> $EN
printf '%s\r\n' 'write.delete-source.failed=Copied to {1}, but the source {0} could not be deleted' >> $EN
printf '%s\r\n' 'write.config.overwrite-with-on-conflict=file.write: "overwrite" and "onConflict" cannot be used together (replace "overwrite" with "onConflict.ifDifferent")' >> $EN
printf '%s\r\n' 'write.config.unknown-value=file.write: unknown value "{1}" for "{0}" (expected: {2})' >> $EN
printf '%s\r\n' 'write.config.no-out-pattern=file.write: "outPattern" is required' >> $EN
printf '%s\r\n' 'write.warn.delete-without-read-back=file.write: the source files are deleted after a "{0}" verification only (use "verify": "readBack" to compare the whole content before deleting)' >> $EN
printf '%s\r\n' 'read.config.invalid-glob=file.read: invalid pattern "{0}"' >> $EN
printf 'write.skip.identical=Identique \134u00e0 la destination ({0})\r\n' >> $FR
printf 'write.skip.exists=Existe d\134u00e9j\134u00e0 \134u00e0 la destination ({0})\r\n' >> $FR
printf 'write.conflict.error=La destination existe d\134u00e9j\134u00e0 : {0}\r\n' >> $FR
printf 'write.verify.size=\134u00c9chec de la v\134u00e9rification de {0} : {2} octets au lieu de {1}\r\n' >> $FR
printf 'write.verify.hash=\134u00c9chec de la v\134u00e9rification de {0} : le contenu relu diff\134u00e8re du contenu copi\134u00e9\r\n' >> $FR
printf 'write.delete-source.failed=Copi\134u00e9 vers {1}, mais la source {0} n\047\047a pas pu \134u00eatre supprim\134u00e9e\r\n' >> $FR
printf 'write.config.overwrite-with-on-conflict=file.write : "overwrite" et "onConflict" ne peuvent pas \134u00eatre utilis\134u00e9s ensemble (remplacer "overwrite" par "onConflict.ifDifferent")\r\n' >> $FR
printf 'write.config.unknown-value=file.write : valeur "{1}" inconnue pour "{0}" (attendu : {2})\r\n' >> $FR
printf 'write.config.no-out-pattern=file.write : "outPattern" est obligatoire\r\n' >> $FR
printf 'write.warn.delete-without-read-back=file.write : les fichiers source sont supprim\134u00e9s apr\134u00e8s une simple v\134u00e9rification "{0}" (utiliser "verify": "readBack" pour comparer tout le contenu avant de supprimer)\r\n' >> $FR
printf 'read.config.invalid-glob=file.read : motif "{0}" invalide\r\n' >> $FR
```

Contrôles :

```bash
file $EN $FR
grep -c $'\xc3' $FR
grep -c $'\xef\xbf\xbd' $FR
git diff $FR | cat -A | grep '^+w\|^+r'
```

Expected : `ASCII text, with CRLF line terminators` / `ISO-8859 text, with CRLF line terminators` ; `0` ; `0` ; 11 lignes `+…^M$` contenant des `é` littéraux (ex. `+write.skip.exists=Existe déjà à la destination ({0})^M$`) et `n''a` doublé.

- [ ] **Step 4: Run the test**

Run: `mvn -o -q -pl copybot-engine test -Dtest=SafeWriteBundleTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add copybot-engine/src/main/resources/com/copybot/engine/i18n/engineBundle.properties copybot-engine/src/main/resources/com/copybot/engine/i18n/engineBundle_fr.properties copybot-engine/src/test/java/com/copybot/resources/SafeWriteBundleTest.java
git commit -m "Add the safe write and read filter messages to the engine bundles" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: API plugin — `write(item, context)`, `WriteResult`, `WriteContext`, `configWarnings`

**Files:**
- Create: `copybot-engine/src/main/java/com/copybot/plugin/api/action/WriteResult.java`
- Create: `copybot-engine/src/main/java/com/copybot/plugin/api/action/WriteContext.java`
- Modify: `copybot-engine/src/main/java/com/copybot/plugin/api/action/IOutAction.java` (CRLF)
- Modify: `copybot-engine/src/main/java/com/copybot/plugin/api/action/IAction.java` (CRLF)
- Create: `copybot-engine/src/test/java/com/copybot/plugin/api/action/WriteApiTest.java`

**Interfaces:**
- Consumes: —
- Produces: `public record WriteResult(WriteResult.Outcome outcome, Path target, String reason)` avec `enum Outcome { WRITTEN, SKIPPED }`, `static WriteResult written(Path target)`, `static WriteResult skipped(Path target, String reason)` (raison non vide exigée, `IllegalArgumentException`), `boolean isSkipped()` ; `public record WriteContext(String runId)` (non nul, non vide, sans `.` `/` `\`), `static WriteContext newRun()` ; `default WriteResult IOutAction.write(WorkItem workItem, WriteContext context)` (appelle `writeItem`, renvoie `WriteResult.written(null)`) ; `default List<String> IAction.configWarnings()` (vide).

- [ ] **Step 1: Write the failing test**

`copybot-engine/src/test/java/com/copybot/plugin/api/action/WriteApiTest.java` :

```java
package com.copybot.plugin.api.action;

import com.copybot.plugin.api.definition.IPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** The plugin API of the safe write (spec safe-write §6): existing out actions keep working unchanged. */
public class WriteApiTest {

    @TempDir
    Path tempDir;

    /** An out action written before write(item, context) existed: it only implements writeItem. */
    static final class LegacyOut implements IOutAction {
        final List<WorkItem> written = new ArrayList<>();

        @Override
        public void writeItem(WorkItem workItem) {
            written.add(workItem);
        }

        @Override
        public void setStatusWatcher(Consumer<WorkStatus> watcher) {
        }

        @Override
        public void setPlugin(IPlugin plugin) {
        }
    }

    @Test
    public void theDefaultWriteCallsWriteItemAndReportsWritten() throws IOException {
        LegacyOut out = new LegacyOut();
        WorkItem item = new WorkItem(Files.createFile(tempDir.resolve("a.jpg")));

        WriteResult result = out.write(item, WriteContext.newRun());

        assertEquals(List.of(item), out.written);
        assertEquals(WriteResult.Outcome.WRITTEN, result.outcome());
        assertNull(result.target(), "a legacy action cannot tell its target");
        assertFalse(result.isSkipped());
    }

    @Test
    public void factoriesBuildWrittenAndSkippedResults() {
        Path target = tempDir.resolve("a.jpg");

        assertEquals(new WriteResult(WriteResult.Outcome.WRITTEN, target, null), WriteResult.written(target));
        WriteResult skipped = WriteResult.skipped(target, "identical");
        assertTrue(skipped.isSkipped());
        assertEquals(target, skipped.target());
        assertEquals("identical", skipped.reason());
    }

    @Test
    public void aSkippedResultNeedsAReason() {
        assertThrows(IllegalArgumentException.class, () -> WriteResult.skipped(null, null));
        assertThrows(IllegalArgumentException.class, () -> WriteResult.skipped(null, " "));
        assertThrows(NullPointerException.class, () -> new WriteResult(null, null, null));
    }

    @Test
    public void theRunIdIsAFileNameFragment() {
        assertNotEquals(WriteContext.newRun().runId(), WriteContext.newRun().runId(), "one id per execution");
        assertFalse(WriteContext.newRun().runId().contains("."));
        assertThrows(NullPointerException.class, () -> new WriteContext(null));
        for (String invalid : List.of("", " ", "a.b", "a/b", "a\\b")) {
            assertThrows(IllegalArgumentException.class, () -> new WriteContext(invalid), invalid);
        }
    }

    @Test
    public void anActionHasNoConfigWarningsByDefault() {
        assertEquals(List.of(), new LegacyOut().configWarnings());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -o -q -pl copybot-engine test -Dtest=WriteApiTest`
Expected: FAIL — compilation `cannot find symbol: class WriteResult` / `class WriteContext`.

- [ ] **Step 3: Implement**

`copybot-engine/src/main/java/com/copybot/plugin/api/action/WriteResult.java` :

```java
package com.copybot.plugin.api.action;

import java.nio.file.Path;
import java.util.Objects;

/**
 * What {@link IOutAction#write} did with one item (spec safe-write §6).
 *
 * @param target where the item was written, or the existing file that made it skipped; null when the
 *               action cannot tell
 * @param reason why the item was skipped, shown to the user; null when it was written
 */
public record WriteResult(Outcome outcome, Path target, String reason) {

    public enum Outcome {
        /** The item was written: it ends DONE. */
        WRITTEN,
        /** Nothing was written (e.g. already at the destination): the item ends SKIPPED with the reason. */
        SKIPPED
    }

    public WriteResult {
        Objects.requireNonNull(outcome, "outcome");
        if (outcome == Outcome.SKIPPED && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("a skipped item needs a reason");
        }
    }

    public static WriteResult written(Path target) {
        return new WriteResult(Outcome.WRITTEN, target, null);
    }

    public static WriteResult skipped(Path target, String reason) {
        return new WriteResult(Outcome.SKIPPED, target, reason);
    }

    public boolean isSkipped() {
        return outcome == Outcome.SKIPPED;
    }
}
```

`copybot-engine/src/main/java/com/copybot/plugin/api/action/WriteContext.java` :

```java
package com.copybot.plugin.api.action;

import java.util.Objects;
import java.util.UUID;

/**
 * What the engine tells an out action about the current execution (spec safe-write §6).
 *
 * @param runId unique per execution, usable as a file name fragment (no dot, no path separator): e.g. to
 *              name temporary files and recognise the ones an earlier, crashed execution left behind
 */
public record WriteContext(String runId) {

    public WriteContext {
        Objects.requireNonNull(runId, "runId");
        if (runId.isBlank() || runId.chars().anyMatch(c -> c == '.' || c == '/' || c == '\\')) {
            throw new IllegalArgumentException("invalid runId: " + runId);
        }
    }

    /** A context with a new random run id. */
    public static WriteContext newRun() {
        return new WriteContext(UUID.randomUUID().toString());
    }
}
```

Dans `IOutAction.java` (CRLF, Edit), remplacer

```java
    void writeItem(WorkItem workItem);

    /**
```

par

```java
    void writeItem(WorkItem workItem);

    /**
     * Writes one item; the engine calls this method, not {@link #writeItem}. The default calls
     * {@link #writeItem} and reports the item as written, target unknown: existing plugins keep working
     * unchanged. Override it to report an item skipped (e.g. already at the destination) or to use the
     * execution context. A failure is thrown, as with {@link #writeItem}: the item ends ERROR.
     */
    default WriteResult write(WorkItem workItem, WriteContext context) {
        writeItem(workItem);
        return WriteResult.written(null);
    }

    /**
```

Dans `IAction.java` (CRLF, Edit), remplacer

```java
import java.nio.file.Path;
import java.util.Set;
```

par

```java
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
```

puis remplacer

```java
    default Set<Path> touchedPaths(WorkItem item) {
        return Set.of();
    }
```

par

```java
    default Set<Path> touchedPaths(WorkItem item) {
        return Set.of();
    }

    /**
     * Warnings about this action's configuration, valid but risky (e.g. sources deleted after a light
     * verification). Called once the configuration is loaded; the engine shows them before the run
     * (dry-run output, CLI start, UI banner). Empty by default.
     */
    default List<String> configWarnings() {
        return List.of();
    }
```

- [ ] **Step 4: Run the tests**

Run: `mvn -o -q -pl copybot-engine test`
Expected: PASS (les plugins de démo / metadata-extractor ne sont pas recompilés ici ; ils le sont à la tâche 12 par `mvn -o clean install`, sans modification).

- [ ] **Step 5: Commit**

```bash
git ls-files --eol copybot-engine/src/main/java/com/copybot/plugin/api/action/IOutAction.java copybot-engine/src/main/java/com/copybot/plugin/api/action/IAction.java
git add copybot-engine/src/main/java/com/copybot/plugin/api/action/WriteResult.java copybot-engine/src/main/java/com/copybot/plugin/api/action/WriteContext.java copybot-engine/src/main/java/com/copybot/plugin/api/action/IOutAction.java copybot-engine/src/main/java/com/copybot/plugin/api/action/IAction.java copybot-engine/src/test/java/com/copybot/plugin/api/action/WriteApiTest.java
git commit -m "Let out actions report skipped items and receive the run id" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

(`IOutAction.java`, `IAction.java` : `w/crlf`.)

---

### Task 3: `MainExecutor` appelle `write` avec le `runId` et termine en `SKIPPED` un item sauté

**Files:**
- Modify: `copybot-engine/src/main/java/com/copybot/engine/MainExecutor.java`
- Modify: `copybot-engine/src/main/java/com/copybot/engine/pipeline/WorkItemExecution.java` (javadoc)
- Modify: `copybot-engine/src/main/java/com/copybot/engine/pipeline/ItemStatus.java` (javadoc)
- Test: `copybot-engine/src/test/java/com/copybot/engine/MainExecutorResumeTest.java`

**Interfaces:**
- Consumes: `IOutAction.write`, `WriteResult`, `WriteContext` (tâche 2).
- Produces: `MainExecutor` possède un `WriteContext` unique (`UUID`) passé à **chaque** `write` de l'exécution ; package-private `String MainExecutor.getRunId()` ; un `WriteResult` `SKIPPED` ⇒ `WorkItemExecution.setSkipped(reason)` et les étapes suivantes ne s'exécutent pas ; un item filtré par un process step reste `DONE`.

- [ ] **Step 1: Write the failing tests**

Dans `MainExecutorResumeTest` (qui importe déjà `com.copybot.plugin.api.action.*`, `ConcurrentHashMap`, `Set`), ajouter à la fin de la classe (avant la dernière `}`) :

```java
    /** Reports the item named skipOn as skipped through write(item, context), writes the others. */
    static final class SkippingOut extends FakeAction implements IOutAction {
        final Set<String> written = ConcurrentHashMap.newKeySet();
        final Set<String> runIds = ConcurrentHashMap.newKeySet();
        final String skipOn;

        SkippingOut(String skipOn) {
            this.skipOn = skipOn;
        }

        @Override
        public void writeItem(WorkItem item) {
            throw new AssertionError("the engine calls write(item, context)");
        }

        @Override
        public WriteResult write(WorkItem item, WriteContext context) {
            runIds.add(context.runId());
            if (item.getNameDisplay().equals(skipOn)) {
                return WriteResult.skipped(null, "identical to the destination");
            }
            written.add(item.getNameDisplay());
            return WriteResult.written(null);
        }
    }

    /** DatedIn -> RecordingAnalyze -> out; a null resume runs a single phase. */
    private MainExecutor outExecutor(int days, IOutAction out, ResumeContext resume) {
        return new MainExecutor(
                List.of(new PipelineStep<>(null, new DatedIn(days), emptyConfig())),
                List.of(new PipelineStep<>(null, new RecordingAnalyze(), emptyConfig()), new PipelineStep<>(null, out, emptyConfig())),
                1, false, null, registry(), resume);
    }

    private static WorkItemExecution named(MainExecutor exec, String name) {
        return exec.getState().getWorkItems().stream()
                .filter(w -> w.getWorkItem().getNameDisplay().equals(name))
                .findFirst().orElseThrow();
    }

    @Test
    public void anItemSkippedByTheOutStepEndsSkippedWithItsReason() {
        SkippingOut out = new SkippingOut("IMG_02.JPG");
        MainExecutor exec = outExecutor(3, out, new ResumeContext(ResumeMode.NONE, store()));

        exec.run();

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus(), "a skipped item is not a failure");
        assertEquals(Set.of("IMG_01.JPG", "IMG_03.JPG"), out.written);
        WorkItemExecution skipped = named(exec, "IMG_02.JPG");
        assertEquals(ItemStatus.SKIPPED, skipped.getStatus());
        assertEquals("identical to the destination", skipped.getSkipReason());
        assertEquals(ItemStatus.DONE, named(exec, "IMG_01.JPG").getStatus());
    }

    @Test
    public void aSinglePhaseRunAlsoEndsTheSkippedItemsSkipped() {
        SkippingOut out = new SkippingOut("IMG_01.JPG");
        MainExecutor exec = outExecutor(2, out, null);

        exec.run();

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
        assertEquals(ItemStatus.SKIPPED, named(exec, "IMG_01.JPG").getStatus());
        assertEquals(ItemStatus.DONE, named(exec, "IMG_02.JPG").getStatus());
    }

    @Test
    public void theOutStepGetsTheRunIdOfItsExecution() {
        SkippingOut out = new SkippingOut(null);
        MainExecutor exec = outExecutor(3, out, new ResumeContext(ResumeMode.NONE, store()));

        exec.run();

        assertEquals(Set.of(exec.getRunId()), out.runIds, "one run id for every item of the execution");
        assertNotEquals(exec.getRunId(), outExecutor(1, new SkippingOut(null), null).getRunId(),
                "one run id per execution");
    }
```

(Le second exécuteur de `theOutStepGetsTheRunIdOfItsExecution` n'est pas lancé : `DatedIn` recréerait les mêmes fichiers dans `tempDir`.)

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -o -q -pl copybot-engine test -Dtest=MainExecutorResumeTest`
Expected: FAIL — compilation `cannot find symbol: method getRunId()`.

- [ ] **Step 3: Implement**

Dans `MainExecutor.java` (LF), remplacer

```java
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.resources.ResourcesEngine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
```

par

```java
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WriteContext;
import com.copybot.plugin.api.action.WriteResult;
import com.copybot.resources.ResourcesEngine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
```

Remplacer

```java
    private final ResourceRegistry registry;
    private final PipelineState state;

```

par

```java
    private final ResourceRegistry registry;
    private final PipelineState state;

    /**
     * Unique per pipeline run (prepare and execute share it), handed to the out step: it names its temporary
     * files and recognises the ones a crashed run left behind (spec safe-write §3).
     */
    private final WriteContext writeContext = new WriteContext(UUID.randomUUID().toString());

```

Dans `runItem`, remplacer

```java
    /** Runs the steps [fromStep, toStep); an item stopped at the barrier goes back to PENDING. */
    private void runItem(WorkItemExecution exec, int fromStep, int toStep) {
        try {
            if (!startProcessingWhileListing) {
                listingGate.await();
            }
            boolean filtered = false;
```

par

```java
    /**
     * Runs the steps [fromStep, toStep); an item stopped at the barrier goes back to PENDING, an item the
     * out step skipped ends SKIPPED with its reason.
     */
    private void runItem(WorkItemExecution exec, int fromStep, int toStep) {
        try {
            if (!startProcessingWhileListing) {
                listingGate.await();
            }
            StepOutcome outcome = StepOutcome.CONTINUE;
```

puis remplacer

```java
                boolean continueItem;
                try {
                    continueItem = runStep(exec, step, i, toStep);
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
```

par

```java
                try {
                    outcome = runStep(exec, step, i, toStep);
                } finally {
                    registry.releaseAll(footprint);
                }
                if (!outcome.continueItem()) {
                    break;
                }
            }
            if (outcome.skipReason() != null) {
                exec.setSkipped(outcome.skipReason());
            } else if (!outcome.continueItem() || toStep == itemSteps.size()) {
                exec.setDone();
            } else {
                exec.setReady();
            }
```

Remplacer l'en-tête de `runStep` et ses deux premiers `return`

```java
    /**
     * @param toStep exclusive end of the current phase: a forked item stops at the same step as its parent
     * @return true to continue with the next step, false when the item stops here (filtered out)
     */
    private boolean runStep(WorkItemExecution exec, PipelineStep<?> step, int stepIndex, int toStep) {
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
```

par

```java
    /**
     * How an item goes on after one step.
     *
     * @param continueItem false when the item stops here
     * @param skipReason   non null when the out step skipped the item: it ends SKIPPED with this reason
     */
    private record StepOutcome(boolean continueItem, String skipReason) {
        static final StepOutcome CONTINUE = new StepOutcome(true, null);
        /** Filtered out by a process step: the item ends DONE. */
        static final StepOutcome FILTERED = new StepOutcome(false, null);

        static StepOutcome skipped(String reason) {
            return new StepOutcome(false, reason);
        }
    }

    /**
     * @param toStep exclusive end of the current phase: a forked item stops at the same step as its parent
     */
    private StepOutcome runStep(WorkItemExecution exec, PipelineStep<?> step, int stepIndex, int toStep) {
        IAction action = step.getAction();
        WorkItem item = exec.getWorkItem();
        if (action instanceof IAnalyzeAction analyze) {
            analyze.doAnalyze(item);
            return StepOutcome.CONTINUE;
        }
        if (action instanceof IProcessAction process) {
            List<WorkItem> produced = process.doProcess(item);
            if (produced == null || produced.isEmpty()) {
                return StepOutcome.FILTERED;
            }
```

puis, à la fin de `runStep`, remplacer

```java
                    throw e;
                }
            }
            return true;
        }
        if (action instanceof IOutAction out) {
            out.writeItem(item);
            return true;
        }
```

par

```java
                    throw e;
                }
            }
            return StepOutcome.CONTINUE;
        }
        if (action instanceof IOutAction out) {
            WriteResult result = out.write(item, writeContext);
            if (result != null && result.isSkipped()) {
                return StepOutcome.skipped(result.reason()); // the steps after it, if any, do not run
            }
            return StepOutcome.CONTINUE;
        }
```

Enfin, à la fin de la classe, remplacer

```java
    /** True once {@link #cancel()} took effect (requested before the pipeline terminated). */
    boolean isCancelRequested() {
        return cancelRequested;
    }
}
```

par

```java
    /** True once {@link #cancel()} took effect (requested before the pipeline terminated). */
    boolean isCancelRequested() {
        return cancelRequested;
    }

    String getRunId() {
        return writeContext.runId();
    }
}
```

Dans `WorkItemExecution.java` (LF), remplacer

```java
    /** Not selected by the resume point; the reason is shown to the user. */
```

par

```java
    /** Not selected by the resume point, or skipped by the out step (e.g. already at the destination); the reason is shown to the user. */
```

Dans `ItemStatus.java` (LF), remplacer

```java
    /** not selected by the resume point: see WorkItemExecution#getSkipReason() */
```

par

```java
    /** not selected by the resume point, or skipped by the out step: see WorkItemExecution#getSkipReason() */
```

- [ ] **Step 4: Run the tests**

Run: `mvn -o -q -pl copybot-engine test`
Expected: PASS (les `RecordingOut` / `GatedOut` / `BlockingOut` existants passent par le `write` par défaut ⇒ `writeItem`).

- [ ] **Step 5: Commit**

```bash
git ls-files --eol copybot-engine/src/main/java/com/copybot/engine/MainExecutor.java copybot-engine/src/main/java/com/copybot/engine/pipeline/WorkItemExecution.java copybot-engine/src/main/java/com/copybot/engine/pipeline/ItemStatus.java
git add copybot-engine/src/main/java/com/copybot/engine/MainExecutor.java copybot-engine/src/main/java/com/copybot/engine/pipeline/WorkItemExecution.java copybot-engine/src/main/java/com/copybot/engine/pipeline/ItemStatus.java copybot-engine/src/test/java/com/copybot/engine/MainExecutorResumeTest.java
git commit -m "Run the out step through write and end skipped items SKIPPED" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

(Les trois fichiers : `w/lf`.)

---

### Task 4: Le curseur compte comme réussi un item sauté pendant l'exécution

**Files:**
- Modify: `copybot-engine/src/main/java/com/copybot/engine/resume/ResumeResolver.java`
- Test: `copybot-engine/src/test/java/com/copybot/engine/resume/ResumeResolverTest.java`
- Test: `copybot-engine/src/test/java/com/copybot/engine/MainExecutorResumeTest.java`

**Interfaces:**
- Consumes: `SkippingOut` / `outExecutor` (tâche 3).
- Produces: `ResumeResolver.nextCursor` : un item sélectionné est réussi s'il finit `DONE` **ou** `SKIPPED` et qu'aucun de ses forks n'a échoué (spec §2).

- [ ] **Step 1: Write the failing tests**

Dans `ResumeResolverTest`, ajouter à la fin de la classe :

```java
    @Test
    public void anItemSkippedDuringTheExecutionCountsAsASuccess() throws IOException {
        List<WorkItemExecution> ordered = card();
        ResumeResolver resolver = new ResumeResolver(ResumeMode.STATE, store(), null);
        ResumeProposal p = resolver.propose(ordered);
        ordered.get(0).setDone();
        ordered.get(1).setSkipped("identical to the destination"); // selected, then skipped by the out step
        ordered.get(2).setDone();

        assertEquals(Optional.of(key(ordered.get(2))), resolver.nextCursor(ordered, p.point(), p.source()));
    }

    @Test
    public void anItemSkippedDuringTheExecutionWithAFailedForkHoldsTheCursor() throws IOException {
        List<WorkItemExecution> ordered = card();
        ResumeResolver resolver = new ResumeResolver(ResumeMode.STATE, store(), null);
        ResumeProposal p = resolver.propose(ordered);
        ordered.get(0).setDone();
        ordered.get(1).setSkipped("identical to the destination");
        ordered.get(1).markForkFailed();
        ordered.get(2).setDone();

        assertEquals(Optional.of(key(ordered.get(0))), resolver.nextCursor(ordered, p.point(), p.source()));
    }
```

Dans `MainExecutorResumeTest`, ajouter à la fin de la classe :

```java
    @Test
    public void anItemSkippedByTheOutStepAdvancesTheCursor() {
        SkippingOut out = new SkippingOut("IMG_03.JPG"); // the last one: already at the destination
        MainExecutor exec = outExecutor(3, out, new ResumeContext(ResumeMode.STATE, store()));

        exec.run();

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
        assertEquals(day(3), store().readCursor().orElseThrow(), "a skipped item counts as imported");
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -o -q -pl copybot-engine test -Dtest='ResumeResolverTest,MainExecutorResumeTest'`
Expected: FAIL — `anItemSkippedDuringTheExecutionCountsAsASuccess` (clé de `A.JPG` au lieu de `C.JPG`) et `anItemSkippedByTheOutStepAdvancesTheCursor` (`day(2)` au lieu de `day(3)`) ; `anItemSkippedDuringTheExecutionWithAFailedForkHoldsTheCursor` passe déjà (garde-fou).

- [ ] **Step 3: Implement**

Dans `ResumeResolver.java` (LF), remplacer

```java
    /**
     * The cursor to persist once the run is over: the last item of the longest run of successes among
     * the selected items (in key order), never before the automatic resume point nor the previous cursor.
     *
     * @return empty when there is nothing (new) to write
     */
```

par

```java
    /**
     * The cursor to persist once the run is over: the last item of the longest run of successes among
     * the selected items (in key order), never before the automatic resume point nor the previous cursor.
     * A selected item succeeded when it ended DONE, or SKIPPED during the execution (the out step found it
     * already at the destination: spec safe-write §2), and none of its forks failed.
     *
     * @return empty when there is nothing (new) to write
     */
```

puis remplacer

```java
            if (item.getStatus() != ItemStatus.DONE || item.hasFailedFork()) {
                break;
            }
```

par

```java
            if (!succeeded(item)) {
                break;
            }
```

et remplacer

```java
    private Optional<ResumeProposal> fromState() {
```

par

```java
    /** For a selected item: SKIPPED can only come from the execution, the resume point skipped only the others. */
    private static boolean succeeded(WorkItemExecution item) {
        ItemStatus status = item.getStatus();
        return (status == ItemStatus.DONE || status == ItemStatus.SKIPPED) && !item.hasFailedFork();
    }

    private Optional<ResumeProposal> fromState() {
```

- [ ] **Step 4: Run the tests**

Run: `mvn -o -q -pl copybot-engine test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git ls-files --eol copybot-engine/src/main/java/com/copybot/engine/resume/ResumeResolver.java
git add copybot-engine/src/main/java/com/copybot/engine/resume/ResumeResolver.java copybot-engine/src/test/java/com/copybot/engine/resume/ResumeResolverTest.java copybot-engine/src/test/java/com/copybot/engine/MainExecutorResumeTest.java
git commit -m "Count items skipped during the execution as imported for the cursor" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: `configWarnings` des étapes dans `PipelineState`

**Files:**
- Modify: `copybot-engine/src/main/java/com/copybot/engine/pipeline/PipelineState.java` (CRLF)
- Modify: `copybot-engine/src/main/java/com/copybot/engine/MainExecutor.java`
- Test: `copybot-engine/src/test/java/com/copybot/engine/MainExecutorResumeTest.java`

**Interfaces:**
- Consumes: `IAction.configWarnings()` (tâche 2).
- Produces: `public List<String> PipelineState.getWarnings()` (vide avant la résolution des étapes, jamais `null`), `public void PipelineState.setWarnings(List<String>)` (copie immuable) ; `MainExecutor` les collecte dans `resolveStepsIfNeeded` (étapes IN puis étapes d'items, `null` ignoré), pour `prepare()` comme pour une exécution en une phase.

- [ ] **Step 1: Write the failing tests**

Dans `MainExecutorResumeTest`, ajouter à la fin de la classe :

```java
    /** A listing of nothing whose configuration is valid but risky. */
    static final class WarningIn extends FakeAction implements IInAction {
        @Override
        public void listFiles(Consumer<WorkItem> consumer) {
        }

        @Override
        public List<String> configWarnings() {
            return List.of("risky listing");
        }
    }

    /** An analyse step whose configuration is valid but risky. */
    static final class WarningAnalyze extends FakeAction implements IAnalyzeAction {
        @Override
        public void doAnalyze(WorkItem item) {
        }

        @Override
        public List<String> configWarnings() {
            return List.of("risky analyse");
        }
    }

    private MainExecutor warningExecutor(ResumeContext resume) {
        return new MainExecutor(
                List.of(new PipelineStep<>(null, new WarningIn(), emptyConfig())),
                List.of(new PipelineStep<>(null, new WarningAnalyze(), emptyConfig()),
                        new PipelineStep<>(null, new RecordingOut(null), emptyConfig())),
                1, false, null, registry(), resume);
    }

    @Test
    public void theConfigWarningsOfEveryStepAreInTheStateOnceThePipelineIsPrepared() {
        MainExecutor exec = warningExecutor(new ResumeContext(ResumeMode.STATE, store()));
        assertEquals(List.of(), exec.getState().getWarnings(), "nothing before the steps are resolved");

        exec.prepare();

        assertEquals(PipelineStatus.PREPARED, exec.getState().getStatus());
        assertEquals(List.of("risky listing", "risky analyse"), exec.getState().getWarnings());
    }

    @Test
    public void aSinglePhaseRunAlsoPublishesTheConfigWarnings() {
        MainExecutor exec = warningExecutor(null);

        exec.run();

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
        assertEquals(List.of("risky listing", "risky analyse"), exec.getState().getWarnings());
    }

    @Test
    public void stepsWithoutWarningsLeaveTheWarningsEmpty() {
        MainExecutor exec = executor(1, new RecordingAnalyze(), new RecordingOut(null), ResumeMode.STATE);

        exec.prepare();

        assertEquals(List.of(), exec.getState().getWarnings());
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -o -q -pl copybot-engine test -Dtest=MainExecutorResumeTest`
Expected: FAIL — compilation `cannot find symbol: method getWarnings()`.

- [ ] **Step 3: Implement**

Dans `PipelineState.java` (CRLF, Edit), remplacer

```java
    private volatile ResumeProposal resumeProposal;
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
```

par

```java
    private volatile ResumeProposal resumeProposal;
    private volatile List<String> warnings = List.of();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
```

puis remplacer

```java
    public void setResumeProposal(ResumeProposal resumeProposal) {
        this.resumeProposal = resumeProposal;
    }
```

par

```java
    public void setResumeProposal(ResumeProposal resumeProposal) {
        this.resumeProposal = resumeProposal;
    }

    /**
     * The configuration warnings of the steps ({@code IAction#configWarnings}), set once the steps are
     * resolved; empty before. Shown before the run: dry-run output, CLI start, UI banner.
     */
    public List<String> getWarnings() {
        return warnings;
    }

    public void setWarnings(List<String> warnings) {
        this.warnings = List.copyOf(warnings);
    }
```

Dans `MainExecutor.java`, remplacer

```java
            startProcessingWhileListing = Boolean.TRUE.equals(pipelineConfig.startProcessingWhileListing());
        }
        registerStepCapacities();
    }
```

par

```java
            startProcessingWhileListing = Boolean.TRUE.equals(pipelineConfig.startProcessingWhileListing());
        }
        registerStepCapacities();
        collectConfigWarnings();
    }

    /** Publishes the configuration warnings of every step, listings included (spec safe-write §6). */
    private void collectConfigWarnings() {
        List<String> warnings = new ArrayList<>();
        inSteps.forEach(step -> addWarnings(warnings, step.getAction()));
        itemSteps.forEach(step -> addWarnings(warnings, step.getAction()));
        state.setWarnings(warnings);
    }

    private static void addWarnings(List<String> warnings, IAction action) {
        List<String> actionWarnings = action.configWarnings();
        if (actionWarnings != null) { // a plugin returning null has nothing to say
            warnings.addAll(actionWarnings);
        }
    }
```

- [ ] **Step 4: Run the tests**

Run: `mvn -o -q -pl copybot-engine test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git ls-files --eol copybot-engine/src/main/java/com/copybot/engine/pipeline/PipelineState.java copybot-engine/src/main/java/com/copybot/engine/MainExecutor.java
git add copybot-engine/src/main/java/com/copybot/engine/pipeline/PipelineState.java copybot-engine/src/main/java/com/copybot/engine/MainExecutor.java copybot-engine/src/test/java/com/copybot/engine/MainExecutorResumeTest.java
git commit -m "Collect the steps' configuration warnings in the pipeline state" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

(`PipelineState.java` : `w/crlf` ; `MainExecutor.java` : `w/lf`.)

---

### Task 6: Configuration de `file.write` — défauts, validation, `overwrite` hérité, avertissement

**Files:**
- Modify (réécriture): `copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileWriteConfig.java` (CRLF)
- Create: `copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileWriteSettings.java`
- Modify: `copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileWriteAction.java` (CRLF)
- Create: `copybot-engine/src/test/java/com/copybot/plugin/embedded/actions/FileWriteSettingsTest.java`
- Test: `copybot-engine/src/test/java/com/copybot/engine/ResumeEndToEndTest.java`

**Interfaces:**
- Consumes: clés `write.config.*`, `write.warn.delete-without-read-back` (tâche 1) ; `IAction.configWarnings` (tâche 2).
- Produces: `public record FileWriteConfig(String outPattern, Boolean overwrite, FileWriteConfig.OnConflict onConflict, String writeMode, String verify, Boolean deleteSource)` avec `public record OnConflict(String compare, String ifIdentical, String ifDifferent)` ; package-private `record FileWriteSettings(String outPattern, Compare compare, Policy ifIdentical, Policy ifDifferent, WriteMode writeMode, Verify verify, boolean deleteSource)`, enums `Compare { SIZE, SIZE_AND_DATE, PARTIAL_HASH, FULL_HASH }`, `Policy { SKIP, RENAME, OVERWRITE, ERROR }`, `WriteMode { TEMP_AND_RENAME, DIRECT }`, `Verify { NONE, SIZE, READ_BACK }` (chacun `jsonName()`), `static FileWriteSettings of(FileWriteConfig)` (lève `CopybotException`), `List<String> warnings()` ; `FileWriteAction.loadConfig` valide (lève), `FileWriteAction.configWarnings()` = `settings.warnings()`. L'algorithme d'écriture reste l'ancien jusqu'à la tâche 10 (`overwrite` ⇔ `ifDifferent == OVERWRITE`).

- [ ] **Step 1: Write the failing tests**

`copybot-engine/src/test/java/com/copybot/plugin/embedded/actions/FileWriteSettingsTest.java` :

```java
package com.copybot.plugin.embedded.actions;

import com.copybot.exception.CopybotException;
import com.copybot.plugin.embedded.actions.FileWriteSettings.Compare;
import com.copybot.plugin.embedded.actions.FileWriteSettings.Policy;
import com.copybot.plugin.embedded.actions.FileWriteSettings.Verify;
import com.copybot.plugin.embedded.actions.FileWriteSettings.WriteMode;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The file.write configuration (spec safe-write §1, §5). */
public class FileWriteSettingsTest {

    private static FileWriteSettings settings(String actionConfig) {
        return FileWriteSettings.of(new Gson().fromJson(actionConfig, FileWriteConfig.class));
    }

    @Test
    public void everythingButTheOutPatternHasADefault() {
        FileWriteSettings settings = settings("{\"outPattern\":\"nas/{name}\"}");

        assertEquals("nas/{name}", settings.outPattern());
        assertEquals(Compare.PARTIAL_HASH, settings.compare());
        assertEquals(Policy.SKIP, settings.ifIdentical());
        assertEquals(Policy.RENAME, settings.ifDifferent());
        assertEquals(WriteMode.TEMP_AND_RENAME, settings.writeMode());
        assertEquals(Verify.SIZE, settings.verify());
        assertFalse(settings.deleteSource());
    }

    @Test
    public void everyValueCanBeSet() {
        FileWriteSettings settings = settings("""
                {"outPattern":"nas/{name}",
                 "onConflict":{"compare":"sizeAndDate","ifIdentical":"overwrite","ifDifferent":"error"},
                 "writeMode":"direct","verify":"readBack","deleteSource":true}""");

        assertEquals(Compare.SIZE_AND_DATE, settings.compare());
        assertEquals(Policy.OVERWRITE, settings.ifIdentical());
        assertEquals(Policy.ERROR, settings.ifDifferent());
        assertEquals(WriteMode.DIRECT, settings.writeMode());
        assertEquals(Verify.READ_BACK, settings.verify());
        assertTrue(settings.deleteSource());
    }

    @Test
    public void aPartialOnConflictKeepsTheOtherDefaults() {
        FileWriteSettings settings = settings("{\"outPattern\":\"x\",\"onConflict\":{\"compare\":\"fullHash\"}}");

        assertEquals(Compare.FULL_HASH, settings.compare());
        assertEquals(Policy.SKIP, settings.ifIdentical());
        assertEquals(Policy.RENAME, settings.ifDifferent());
    }

    @Test
    public void legacyOverwriteTrueOverwritesADifferentTargetAndSkipsAnIdenticalOne() {
        FileWriteSettings settings = settings("{\"outPattern\":\"x\",\"overwrite\":true}");

        assertEquals(Policy.OVERWRITE, settings.ifDifferent());
        assertEquals(Policy.SKIP, settings.ifIdentical());
    }

    @Test
    public void legacyOverwriteFalseFailsOnADifferentTargetAndSkipsAnIdenticalOne() {
        FileWriteSettings settings = settings("{\"outPattern\":\"x\",\"overwrite\":false}");

        assertEquals(Policy.ERROR, settings.ifDifferent());
        assertEquals(Policy.SKIP, settings.ifIdentical());
    }

    @Test
    public void overwriteAndOnConflictTogetherAreRefused() {
        assertThrows(CopybotException.class,
                () -> settings("{\"outPattern\":\"x\",\"overwrite\":true,\"onConflict\":{\"ifDifferent\":\"rename\"}}"));
    }

    @Test
    public void anUnknownValueIsRefusedAndQuoted() {
        CopybotException e = assertThrows(CopybotException.class,
                () -> settings("{\"outPattern\":\"x\",\"verify\":\"readback\"}"));
        assertTrue(e.getMessage().contains("readback"), e.getMessage());

        assertThrows(CopybotException.class, () -> settings("{\"outPattern\":\"x\",\"writeMode\":\"atomic\"}"));
        assertThrows(CopybotException.class, () -> settings("{\"outPattern\":\"x\",\"onConflict\":{\"compare\":\"md5\"}}"));
        assertThrows(CopybotException.class, () -> settings("{\"outPattern\":\"x\",\"onConflict\":{\"ifIdentical\":\"keep\"}}"));
        assertThrows(CopybotException.class, () -> settings("{\"outPattern\":\"x\",\"onConflict\":{\"ifDifferent\":\"merge\"}}"));
    }

    @Test
    public void theOutPatternIsRequired() {
        assertThrows(CopybotException.class, () -> settings("{}"));
        assertThrows(CopybotException.class, () -> settings("{\"outPattern\":\" \"}"));
        assertThrows(CopybotException.class, () -> FileWriteSettings.of(null));
    }

    @Test
    public void deletingTheSourcesWithoutReadBackIsWarned() {
        assertEquals(1, settings("{\"outPattern\":\"x\",\"deleteSource\":true}").warnings().size());
        assertEquals(1, settings("{\"outPattern\":\"x\",\"deleteSource\":true,\"verify\":\"none\"}").warnings().size());
        assertEquals(List.of(), settings("{\"outPattern\":\"x\",\"deleteSource\":true,\"verify\":\"readBack\"}").warnings());
        assertEquals(List.of(), settings("{\"outPattern\":\"x\"}").warnings());
    }

    @Test
    public void theActionRefusesAnInvalidConfigurationAndReportsItsWarnings() {
        FileWriteAction action = new FileWriteAction();
        assertThrows(CopybotException.class, () -> action.loadConfig(JsonParser.parseString("{\"verify\":\"size\"}")));

        action.loadConfig(JsonParser.parseString("{\"outPattern\":\"x\",\"deleteSource\":true}"));
        assertEquals(1, action.configWarnings().size());
        assertFalse(action.configWarnings().getFirst().startsWith("%"), "a translated message");
    }
}
```

Dans `ResumeEndToEndTest`, ajouter à la fin de la classe (le helper sert aussi aux tâches 10 et 12) :

```java
    /** file.read -> file.write to nas/{name}; outConfig is appended to the write actionConfig, mode null: no resume. */
    private Path pipelineWithOut(String mode, String outConfig) throws IOException {
        String resume = mode == null ? "" : ",\"resume\":{\"mode\":\"" + mode + "\"}";
        return Files.writeString(tempDir.resolve("sd.json"), """
                {
                  "inSteps": [ { "action": "file.read", "actionConfig": { "path": "%s" } } ],
                  "outStep": { "action": "file.write", "actionConfig": { "outPattern": "%s/{name}"%s } }%s
                }
                """.formatted(json(card), json(nas), outConfig, resume));
    }

    @Test
    public void overwriteWithOnConflictIsAConfigurationErrorAndCopiesNothing() throws IOException {
        Path pipeline = pipelineWithOut(null, ", \"overwrite\": true, \"onConflict\": { \"ifDifferent\": \"rename\" }");

        String[] result = capture(pipeline);

        assertEquals("2", result[0], result[2]);
        assertFalse(Files.exists(nas), "nothing is copied");
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -o -q -pl copybot-engine test -Dtest='FileWriteSettingsTest,ResumeEndToEndTest'`
Expected: FAIL — compilation `cannot find symbol: class FileWriteSettings`.

- [ ] **Step 3: Implement**

Réécrire `FileWriteConfig.java` en entier (Write), puis `unix2dos -q copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileWriteConfig.java` :

```java
package com.copybot.plugin.embedded.actions;

/**
 * The "actionConfig" of file.write, as written in the pipeline (spec safe-write §1). Every field but
 * outPattern is optional: {@link FileWriteSettings#of} validates the values and applies the defaults.
 *
 * @param overwrite    legacy: true means onConflict.ifDifferent "overwrite", false means "error"
 *                     (ifIdentical "skip" in both cases); refused together with onConflict
 * @param onConflict   what to do when the target already exists
 * @param writeMode    "tempAndRename" (default) or "direct" (with "overwrite", the original is lost as soon
 *                     as the write starts)
 * @param verify       "none", "size" (default) or "readBack"
 * @param deleteSource delete the source once written and verified (default false)
 */
public record FileWriteConfig(
        String outPattern,

        Boolean overwrite,

        OnConflict onConflict,

        String writeMode,

        String verify,

        Boolean deleteSource
) {

    /**
     * @param compare     "size", "sizeAndDate", "partialHash" (default) or "fullHash"
     * @param ifIdentical "skip" (default), "rename", "overwrite" or "error"
     * @param ifDifferent "skip", "rename" (default), "overwrite" or "error"
     */
    public record OnConflict(String compare, String ifIdentical, String ifDifferent) {
    }
}
```

`copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileWriteSettings.java` :

```java
package com.copybot.plugin.embedded.actions;

import com.copybot.exception.CopybotException;
import com.copybot.resources.ResourcesEngine;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/** The validated file.write configuration, defaults applied (spec safe-write §1 to §5). */
record FileWriteSettings(
        String outPattern,
        Compare compare,
        Policy ifIdentical,
        Policy ifDifferent,
        WriteMode writeMode,
        Verify verify,
        boolean deleteSource) {

    /** A configuration value, as written in the pipeline. */
    interface Named {
        String jsonName();
    }

    /** How an existing target is recognised as identical to the item; the size is always compared first. */
    enum Compare implements Named {
        SIZE("size"), SIZE_AND_DATE("sizeAndDate"), PARTIAL_HASH("partialHash"), FULL_HASH("fullHash");

        private final String jsonName;

        Compare(String jsonName) {
            this.jsonName = jsonName;
        }

        @Override
        public String jsonName() {
            return jsonName;
        }
    }

    /** What to do with an item whose target already exists. */
    enum Policy implements Named {
        SKIP("skip"), RENAME("rename"), OVERWRITE("overwrite"), ERROR("error");

        private final String jsonName;

        Policy(String jsonName) {
            this.jsonName = jsonName;
        }

        @Override
        public String jsonName() {
            return jsonName;
        }
    }

    enum WriteMode implements Named {
        /** Written to a hidden temporary file of the target directory, then moved to the target. */
        TEMP_AND_RENAME("tempAndRename"),
        /** Written under the final name; with "overwrite" the original is lost as soon as the write starts. */
        DIRECT("direct");

        private final String jsonName;

        WriteMode(String jsonName) {
            this.jsonName = jsonName;
        }

        @Override
        public String jsonName() {
            return jsonName;
        }
    }

    enum Verify implements Named {
        /** Bytes written = expected source size. */
        NONE("none"),
        /** Size of the written file, read back from the destination. */
        SIZE("size"),
        /** Whole written file read back and compared to the SHA-256 computed during the copy. */
        READ_BACK("readBack");

        private final String jsonName;

        Verify(String jsonName) {
            this.jsonName = jsonName;
        }

        @Override
        public String jsonName() {
            return jsonName;
        }
    }

    /**
     * @throws CopybotException write.config.no-out-pattern, write.config.overwrite-with-on-conflict,
     *                          write.config.unknown-value
     */
    static FileWriteSettings of(FileWriteConfig config) {
        if (config == null || config.outPattern() == null || config.outPattern().isBlank()) {
            throw CopybotException.ofResource("write.config.no-out-pattern");
        }
        FileWriteConfig.OnConflict onConflict = config.onConflict();
        if (config.overwrite() != null && onConflict != null) {
            throw CopybotException.ofResource("write.config.overwrite-with-on-conflict");
        }
        Compare compare = Compare.PARTIAL_HASH;
        Policy ifIdentical = Policy.SKIP;
        Policy ifDifferent;
        if (onConflict != null) {
            compare = parse("onConflict.compare", onConflict.compare(), Compare.PARTIAL_HASH, Compare.class);
            ifIdentical = parse("onConflict.ifIdentical", onConflict.ifIdentical(), Policy.SKIP, Policy.class);
            ifDifferent = parse("onConflict.ifDifferent", onConflict.ifDifferent(), Policy.RENAME, Policy.class);
        } else if (config.overwrite() != null) {
            ifDifferent = config.overwrite() ? Policy.OVERWRITE : Policy.ERROR; // legacy "overwrite"
        } else {
            ifDifferent = Policy.RENAME;
        }
        return new FileWriteSettings(config.outPattern(), compare, ifIdentical, ifDifferent,
                parse("writeMode", config.writeMode(), WriteMode.TEMP_AND_RENAME, WriteMode.class),
                parse("verify", config.verify(), Verify.SIZE, Verify.class),
                Boolean.TRUE.equals(config.deleteSource()));
    }

    /** Spec safe-write §5: deleting the sources after a verification lighter than readBack is allowed, but warned. */
    List<String> warnings() {
        if (deleteSource && verify != Verify.READ_BACK) {
            return List.of(ResourcesEngine.getString("write.warn.delete-without-read-back", verify.jsonName()));
        }
        return List.of();
    }

    private static <E extends Enum<E> & Named> E parse(String field, String value, E defaultValue, Class<E> type) {
        if (value == null) {
            return defaultValue;
        }
        for (E constant : type.getEnumConstants()) {
            if (constant.jsonName().equals(value)) {
                return constant;
            }
        }
        String expected = Arrays.stream(type.getEnumConstants()).map(Named::jsonName).collect(Collectors.joining(", "));
        throw CopybotException.ofResource("write.config.unknown-value", field, value, expected);
    }
}
```

Dans `FileWriteAction.java` (CRLF, Edit) — adaptation minimale, l'algorithme est réécrit à la tâche 10 — remplacer

```java
import com.copybot.plugin.api.action.WorkStatus;

import java.io.IOException;
```

par

```java
import com.copybot.plugin.api.action.WorkStatus;
import com.google.gson.JsonElement;

import java.io.IOException;
```

remplacer

```java
import java.nio.file.StandardOpenOption;
import java.util.Optional;
```

par

```java
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
```

remplacer

```java
    @Override
    protected Class<FileWriteConfig> getConfigClass() {
        return FileWriteConfig.class;
    }
```

par

```java
    private volatile FileWriteSettings settings;

    @Override
    protected Class<FileWriteConfig> getConfigClass() {
        return FileWriteConfig.class;
    }

    /** @throws com.copybot.exception.CopybotException when the configuration is invalid */
    @Override
    public void loadConfig(JsonElement config) {
        super.loadConfig(config);
        settings = FileWriteSettings.of(getConfig());
    }

    @Override
    public List<String> configWarnings() {
        return settings.warnings();
    }
```

puis remplacer `            if (getConfig().overwrite()) {` par `            if (settings.ifDifferent() == FileWriteSettings.Policy.OVERWRITE) {` et `            StandardOpenOption[] copyOptions = getConfig().overwrite()` par `            StandardOpenOption[] copyOptions = settings.ifDifferent() == FileWriteSettings.Policy.OVERWRITE` (les deux seuls usages de `overwrite()`, devenu un `Boolean`).

- [ ] **Step 4: Run the tests**

Run: `mvn -o -q -pl copybot-engine test`
Expected: PASS (dont `FileWriteSettingsTest` : 10 tests ; `overwriteWithOnConflictIsAConfigurationErrorAndCopiesNothing` : préparation en échec ⇒ `2` ; les anciens tests d'écriture inchangés).

- [ ] **Step 5: Commit**

```bash
git ls-files --eol copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileWriteConfig.java copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileWriteAction.java
git add copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileWriteConfig.java copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileWriteSettings.java copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileWriteAction.java copybot-engine/src/test/java/com/copybot/plugin/embedded/actions/FileWriteSettingsTest.java copybot-engine/src/test/java/com/copybot/engine/ResumeEndToEndTest.java
git commit -m "Validate the file.write configuration with defaults and legacy overwrite" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

(`FileWriteConfig.java`, `FileWriteAction.java` : `w/crlf` ; `FileWriteSettings.java` : `w/lf`.)

---

### Task 7: Comparaison d'une cible existante et SHA-256

**Files:**
- Create: `copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileComparison.java`
- Create: `copybot-engine/src/test/java/com/copybot/plugin/embedded/actions/FileComparisonTest.java`

**Interfaces:**
- Consumes: `FileWriteSettings.Compare` (tâche 6).
- Produces: package-private `final class FileComparison` : `static boolean identical(WorkItem item, Path target, Compare compare) throws IOException` (tailles d'abord, sans lire si elles diffèrent) ; `static Path localSource(WorkItem)` (null si non local) ; `static long sourceSize(WorkItem)` (métadonnée, sinon fichier local, sinon lecture) ; `static Optional<Instant> sourceDate(WorkItem)` (métadonnée `lastModified`, sinon fichier local) ; `static byte[] partialSha256(InputStream, long size)` ; `static byte[] sha256(InputStream)` ; `static MessageDigest newSha256()` ; `static String hex(byte[])` ; constantes `PARTIAL_CHUNK = 64 * 1024`, `DATE_TOLERANCE = 2 s`.

- [ ] **Step 1: Write the failing test**

`copybot-engine/src/test/java/com/copybot/plugin/embedded/actions/FileComparisonTest.java` :

```java
package com.copybot.plugin.embedded.actions;

import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import com.copybot.plugin.embedded.actions.FileWriteSettings.Compare;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** Conflict comparison and hashes (spec safe-write §2, §4). */
public class FileComparisonTest {

    private static final Instant SHOT = Instant.parse("2026-09-01T10:00:00Z");

    @TempDir
    Path tempDir;

    private WorkItem source(String name, byte[] content) throws IOException {
        Path file = Files.write(tempDir.resolve(name), content);
        WorkItem item = new WorkItem(file);
        item.getMetadatas().setSize(content.length);
        item.getMetadatas().setTime(WorkItemMetadata.LAST_MODIFIED, SHOT);
        return item;
    }

    private Path target(String name, byte[] content, Instant lastModified) throws IOException {
        Path file = Files.write(tempDir.resolve(name), content);
        Files.setLastModifiedTime(file, FileTime.from(lastModified));
        return file;
    }

    private static byte[] bytes(int size, int seed) {
        byte[] content = new byte[size];
        for (int i = 0; i < size; i++) {
            content[i] = (byte) (i * 31 + seed);
        }
        return content;
    }

    private static byte[] alteredAt(byte[] content, int index) {
        byte[] copy = Arrays.copyOf(content, content.length);
        copy[index] ^= 0x55;
        return copy;
    }

    @Test
    public void aDifferentSizeIsNeverIdenticalAndTheSourceIsNotRead() throws IOException {
        WorkItem neverRead = new WorkItem(tempDir.toUri().toURL(), () -> {
            throw new AssertionError("different sizes: nothing is read");
        });
        neverRead.getMetadatas().setSize(10);
        Path target = target("t.bin", bytes(11, 0), SHOT);

        for (Compare compare : Compare.values()) {
            assertFalse(FileComparison.identical(neverRead, target, compare), compare.name());
        }
    }

    @Test
    public void sizeOnlyComparesTheSizes() throws IOException {
        WorkItem item = source("s.bin", bytes(100, 1));
        Path target = target("t.bin", bytes(100, 2), SHOT.plusSeconds(3600));

        assertTrue(FileComparison.identical(item, target, Compare.SIZE));
        assertFalse(FileComparison.identical(item, target, Compare.PARTIAL_HASH));
    }

    @Test
    public void sizeAndDateToleratesTwoSecondsOnly() throws IOException {
        WorkItem item = source("s.bin", bytes(100, 1));

        assertTrue(FileComparison.identical(item, target("a.bin", bytes(100, 2), SHOT.plusSeconds(2)), Compare.SIZE_AND_DATE));
        assertTrue(FileComparison.identical(item, target("b.bin", bytes(100, 2), SHOT.minusSeconds(2)), Compare.SIZE_AND_DATE));
        assertFalse(FileComparison.identical(item, target("c.bin", bytes(100, 2), SHOT.plusSeconds(3)), Compare.SIZE_AND_DATE));
    }

    @Test
    public void partialHashReadsTheStartAndTheEndOfALargeFile() throws IOException {
        byte[] content = bytes(300 * 1024, 7);
        WorkItem item = source("s.bin", content);

        Path middleChanged = target("m.bin", alteredAt(content, 150 * 1024), SHOT);
        assertTrue(FileComparison.identical(item, middleChanged, Compare.PARTIAL_HASH), "the middle is not read");
        assertFalse(FileComparison.identical(item, middleChanged, Compare.FULL_HASH));

        assertFalse(FileComparison.identical(item, target("e.bin", alteredAt(content, content.length - 1), SHOT), Compare.PARTIAL_HASH));
        assertFalse(FileComparison.identical(item, target("b.bin", alteredAt(content, 0), SHOT), Compare.PARTIAL_HASH));
    }

    @Test
    public void partialHashReadsEverythingUpTo128KiB() throws IOException {
        byte[] content = bytes(128 * 1024, 3);
        WorkItem item = source("s.bin", content);

        assertFalse(FileComparison.identical(item, target("m.bin", alteredAt(content, 64 * 1024), SHOT), Compare.PARTIAL_HASH));
        assertTrue(FileComparison.identical(item, target("same.bin", content, SHOT), Compare.PARTIAL_HASH));
    }

    @Test
    public void fullHashRecognisesTheSameContent() throws IOException {
        byte[] content = bytes(200 * 1024, 5);

        assertTrue(FileComparison.identical(source("s.bin", content), target("t.bin", content, SHOT.plusSeconds(3600)), Compare.FULL_HASH));
    }

    @Test
    public void aSourceWithoutMetadataUsesItsLocalFile() throws IOException {
        Path file = Files.write(tempDir.resolve("s.bin"), bytes(100, 1));
        Files.setLastModifiedTime(file, FileTime.from(SHOT));
        WorkItem item = new WorkItem(file);

        assertEquals(100, FileComparison.sourceSize(item));
        assertEquals(SHOT, FileComparison.sourceDate(item).orElseThrow());
    }

    @Test
    public void sha256IsHexEncoded() throws IOException {
        byte[] digest = FileComparison.sha256(new ByteArrayInputStream("abc".getBytes(StandardCharsets.US_ASCII)));

        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", FileComparison.hex(digest));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -o -q -pl copybot-engine test -Dtest=FileComparisonTest`
Expected: FAIL — compilation `cannot find symbol: variable FileComparison`.

- [ ] **Step 3: Implement**

`copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileComparison.java` :

```java
package com.copybot.plugin.embedded.actions;

import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import com.copybot.plugin.embedded.actions.FileWriteSettings.Compare;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Recognises an existing target identical to the item being written (spec safe-write §2) and computes
 * the SHA-256 of a content (§4). The sizes are always compared first: different sizes are never read.
 */
final class FileComparison {

    /** partialHash: this many bytes at the start and at the end of the file. */
    static final int PARTIAL_CHUNK = 64 * 1024;

    /** sizeAndDate: FAT (memory cards) stores modification times with a 2 s resolution. */
    static final Duration DATE_TOLERANCE = Duration.ofSeconds(2);

    private static final int BUFFER_SIZE = 8192;

    private FileComparison() {
    }

    static boolean identical(WorkItem item, Path target, Compare compare) throws IOException {
        long size = sourceSize(item);
        if (size != Files.size(target)) {
            return false;
        }
        return switch (compare) {
            case SIZE -> true;
            case SIZE_AND_DATE -> {
                Optional<Instant> sourceDate = sourceDate(item);
                yield sourceDate.isPresent()
                        && Duration.between(sourceDate.get(), Files.getLastModifiedTime(target).toInstant()).abs()
                        .compareTo(DATE_TOLERANCE) <= 0;
            }
            case PARTIAL_HASH -> {
                try (InputStream source = item.openInputStream(); InputStream written = Files.newInputStream(target)) {
                    yield Arrays.equals(partialSha256(source, size), partialSha256(written, size));
                }
            }
            case FULL_HASH -> {
                try (InputStream source = item.openInputStream(); InputStream written = Files.newInputStream(target)) {
                    yield Arrays.equals(sha256(source), sha256(written));
                }
            }
        };
    }

    /** The local file of the item, null when it is not a local file (e.g. a URL). */
    static Path localSource(WorkItem item) {
        return item.isLocal() || item.isTempFile() ? item.getLocalLocation() : null;
    }

    /** The size of the item: from its metadata, else from its local file, else by reading it. */
    static long sourceSize(WorkItem item) throws IOException {
        Long size = item.getMetadatas().getSize();
        if (size != null) {
            return size;
        }
        Path local = localSource(item);
        if (local != null) {
            return Files.size(local);
        }
        try (InputStream in = item.openInputStream()) {
            return in.transferTo(OutputStream.nullOutputStream());
        }
    }

    /** The modification date of the item: from its metadata, else from its local file; empty when unknown. */
    static Optional<Instant> sourceDate(WorkItem item) throws IOException {
        Optional<Instant> date = item.getMetadatas().getTime(WorkItemMetadata.LAST_MODIFIED);
        if (date.isPresent()) {
            return date;
        }
        Path local = localSource(item);
        return local == null ? Optional.empty() : Optional.of(Files.getLastModifiedTime(local).toInstant());
    }

    /** SHA-256 of the first and last {@value #PARTIAL_CHUNK} bytes, of everything up to twice that size. */
    static byte[] partialSha256(InputStream in, long size) throws IOException {
        if (size <= 2L * PARTIAL_CHUNK) {
            return sha256(in);
        }
        MessageDigest digest = newSha256();
        digest.update(in.readNBytes(PARTIAL_CHUNK));
        in.skipNBytes(size - 2L * PARTIAL_CHUNK);
        digest.update(in.readNBytes(PARTIAL_CHUNK));
        return digest.digest();
    }

    static byte[] sha256(InputStream in) throws IOException {
        MessageDigest digest = newSha256();
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            digest.update(buffer, 0, read);
        }
        return digest.digest();
    }

    static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required on every Java platform", e);
        }
    }

    static String hex(byte[] digest) {
        return HexFormat.of().formatHex(digest);
    }
}
```

- [ ] **Step 4: Run the test**

Run: `mvn -o -q -pl copybot-engine test -Dtest=FileComparisonTest`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileComparison.java copybot-engine/src/test/java/com/copybot/plugin/embedded/actions/FileComparisonTest.java
git commit -m "Compare an existing target by size, date or SHA-256" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: Résolution des conflits — skip / rename / overwrite / error

**Files:**
- Create: `copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/ConflictResolver.java`
- Create: `copybot-engine/src/test/java/com/copybot/plugin/embedded/actions/ConflictResolverTest.java`

**Interfaces:**
- Consumes: `FileWriteSettings` (tâche 6), `FileComparison.identical` (tâche 7), clés `write.skip.identical`, `write.skip.exists`, `write.conflict.error` (tâche 1).
- Produces: package-private `final class ConflictResolver` : `ConflictResolver(FileWriteSettings)`, `Decision resolve(WorkItem item, Path target) throws IOException` (lève `CopybotException` pour `error`), `record Decision(Path target, boolean replaceExisting, String skipReason, boolean identical)` avec `boolean isSkip()`, `static Path numbered(Path target, int n)`.

- [ ] **Step 1: Write the failing test**

`copybot-engine/src/test/java/com/copybot/plugin/embedded/actions/ConflictResolverTest.java` :

```java
package com.copybot.plugin.embedded.actions;

import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.WorkItem;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** What happens when the target already exists (spec safe-write §2). */
public class ConflictResolverTest {

    @TempDir
    Path tempDir;

    private ConflictResolver resolver(String onConflict) {
        String json = "{\"outPattern\":\"x\"" + (onConflict == null ? "" : ",\"onConflict\":" + onConflict) + "}";
        return new ConflictResolver(FileWriteSettings.of(new Gson().fromJson(json, FileWriteConfig.class)));
    }

    private WorkItem item(String content) throws IOException {
        Path file = Files.writeString(Files.createDirectories(tempDir.resolve("card")).resolve("photo.jpg"), content);
        WorkItem item = new WorkItem(file);
        item.getMetadatas().setSize(content.length());
        return item;
    }

    private Path existing(String name, String content) throws IOException {
        return Files.writeString(Files.createDirectories(tempDir.resolve("nas")).resolve(name), content);
    }

    private Path target() {
        return tempDir.resolve("nas").resolve("photo.jpg");
    }

    @Test
    public void aFreeTargetIsWritten() throws IOException {
        ConflictResolver.Decision decision = resolver(null).resolve(item("new"), target());

        assertEquals(target(), decision.target());
        assertFalse(decision.isSkip());
        assertFalse(decision.replaceExisting());
    }

    @Test
    public void anIdenticalTargetIsSkippedWithItsReasonByDefault() throws IOException {
        existing("photo.jpg", "same");

        ConflictResolver.Decision decision = resolver(null).resolve(item("same"), target());

        assertTrue(decision.isSkip());
        assertTrue(decision.identical());
        assertEquals(target(), decision.target());
        assertTrue(decision.skipReason().contains("photo.jpg"), decision.skipReason());
    }

    @Test
    public void aDifferentTargetIsRenamedByDefault() throws IOException {
        existing("photo.jpg", "other content");

        ConflictResolver.Decision decision = resolver(null).resolve(item("new"), target());

        assertEquals(target().resolveSibling("photo (1).jpg"), decision.target());
        assertFalse(decision.isSkip());
        assertFalse(decision.replaceExisting());
    }

    @Test
    public void renameGoesOnWhileTheNumberedCandidatesAreDifferent() throws IOException {
        existing("photo.jpg", "other content");
        existing("photo (1).jpg", "yet another content");

        assertEquals(target().resolveSibling("photo (2).jpg"), resolver(null).resolve(item("new"), target()).target());
    }

    @Test
    public void renameFindsAnIdenticalCopyAlreadyNumbered() throws IOException {
        existing("photo.jpg", "other content");
        existing("photo (1).jpg", "new");

        ConflictResolver.Decision decision = resolver(null).resolve(item("new"), target());

        assertTrue(decision.isSkip(), "no photo (2).jpg duplicate");
        assertTrue(decision.identical());
        assertEquals(target().resolveSibling("photo (1).jpg"), decision.target());
    }

    @Test
    public void overwriteReplacesTheTarget() throws IOException {
        existing("photo.jpg", "other content");

        ConflictResolver.Decision decision = resolver("{\"ifDifferent\":\"overwrite\"}").resolve(item("new"), target());

        assertEquals(target(), decision.target());
        assertTrue(decision.replaceExisting());
        assertFalse(decision.isSkip());
    }

    @Test
    public void errorFailsTheItemAndQuotesTheTarget() throws IOException {
        existing("photo.jpg", "other content");

        CopybotException e = assertThrows(CopybotException.class,
                () -> resolver("{\"ifDifferent\":\"error\"}").resolve(item("new"), target()));
        assertTrue(e.getMessage().contains("photo.jpg"), e.getMessage());
        assertThrows(CopybotException.class,
                () -> resolver("{\"ifIdentical\":\"error\"}").resolve(item("other content"), target()));
    }

    @Test
    public void skipOnADifferentTargetIsNotAnIdenticalSkip() throws IOException {
        existing("photo.jpg", "other content");

        ConflictResolver.Decision decision = resolver("{\"ifDifferent\":\"skip\"}").resolve(item("new"), target());

        assertTrue(decision.isSkip());
        assertFalse(decision.identical());
        assertTrue(decision.skipReason().contains("photo.jpg"), decision.skipReason());
    }

    @Test
    public void legacyOverwriteFalseSkipsAnIdenticalTargetAndFailsOnADifferentOne() throws IOException {
        existing("photo.jpg", "same");
        ConflictResolver legacy = new ConflictResolver(FileWriteSettings.of(
                new Gson().fromJson("{\"outPattern\":\"x\",\"overwrite\":false}", FileWriteConfig.class)));

        assertTrue(legacy.resolve(item("same"), target()).isSkip());
        assertThrows(CopybotException.class, () -> legacy.resolve(item("different"), target()));
    }

    @Test
    public void numberedNamesKeepTheExtension() {
        Path dir = tempDir;

        assertEquals(dir.resolve("IMG_01 (1).JPG"), ConflictResolver.numbered(dir.resolve("IMG_01.JPG"), 1));
        assertEquals(dir.resolve("archive.tar (2).gz"), ConflictResolver.numbered(dir.resolve("archive.tar.gz"), 2));
        assertEquals(dir.resolve("README (1)"), ConflictResolver.numbered(dir.resolve("README"), 1));
        assertEquals(dir.resolve(".hidden (1)"), ConflictResolver.numbered(dir.resolve(".hidden"), 1));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -o -q -pl copybot-engine test -Dtest=ConflictResolverTest`
Expected: FAIL — compilation `cannot find symbol: class ConflictResolver`.

- [ ] **Step 3: Implement**

`copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/ConflictResolver.java` :

```java
package com.copybot.plugin.embedded.actions;

import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.embedded.actions.FileWriteSettings.Policy;
import com.copybot.resources.ResourcesEngine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Decides where an item goes when its target may already exist (spec safe-write §2). */
final class ConflictResolver {

    /**
     * @param target          where to write, or the existing file that makes the item skipped
     * @param replaceExisting the target exists and is overwritten
     * @param skipReason      non null when nothing is written: the reason shown to the user
     * @param identical       the existing target was found identical to the item
     */
    record Decision(Path target, boolean replaceExisting, String skipReason, boolean identical) {
        boolean isSkip() {
            return skipReason != null;
        }
    }

    private final FileWriteSettings settings;

    ConflictResolver(FileWriteSettings settings) {
        this.settings = settings;
    }

    /**
     * Free target: write it. Existing target: compared, then ifIdentical or ifDifferent applies; "rename"
     * tries "name (1).ext", "name (2).ext"... each existing candidate being compared in turn, so an
     * identical copy already renamed is found again instead of piling up duplicates.
     *
     * @throws CopybotException write.conflict.error when the policy is "error"
     */
    Decision resolve(WorkItem item, Path target) throws IOException {
        Path candidate = target;
        for (int n = 1; ; n++) {
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return new Decision(candidate, false, null, false);
            }
            boolean identical = FileComparison.identical(item, candidate, settings.compare());
            Policy policy = identical ? settings.ifIdentical() : settings.ifDifferent();
            switch (policy) {
                case SKIP -> {
                    String reason = ResourcesEngine.getString(identical ? "write.skip.identical" : "write.skip.exists", candidate);
                    return new Decision(candidate, false, reason, identical);
                }
                case OVERWRITE -> {
                    return new Decision(candidate, true, null, identical);
                }
                case ERROR -> throw CopybotException.ofResource("write.conflict.error", candidate);
                case RENAME -> candidate = numbered(target, n);
            }
        }
    }

    /** "name (n).ext", or "name (n)" without extension; the leading dot of a hidden file is not an extension. */
    static Path numbered(Path target, int n) {
        String name = target.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String numbered = dot > 0
                ? name.substring(0, dot) + " (" + n + ")" + name.substring(dot)
                : name + " (" + n + ")";
        return target.resolveSibling(numbered);
    }
}
```

- [ ] **Step 4: Run the test**

Run: `mvn -o -q -pl copybot-engine test -Dtest=ConflictResolverTest`
Expected: PASS (10 tests).

- [ ] **Step 5: Commit**

```bash
git add copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/ConflictResolver.java copybot-engine/src/test/java/com/copybot/plugin/embedded/actions/ConflictResolverTest.java
git commit -m "Resolve file.write conflicts: skip, rename, overwrite or error" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 9: Écriture sûre — temporaire ou direct, hash au vol, vérification, orphelins

**Files:**
- Create: `copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/SafeFileWriter.java`
- Create: `copybot-engine/src/test/java/com/copybot/plugin/embedded/actions/SafeFileWriterTest.java`

**Interfaces:**
- Consumes: `FileWriteSettings` (tâche 6), `FileComparison.newSha256/sha256/hex/sourceDate` (tâche 7), clés `write.verify.size`, `write.verify.hash` (tâche 1).
- Produces: package-private `final class SafeFileWriter` : `SafeFileWriter(FileWriteSettings)`, `Written write(WorkItem item, Path target, boolean replaceExisting, String runId, IntConsumer percent) throws IOException` (crée le répertoire cible, nettoie les orphelins au premier passage du run, lève `FileAlreadyExistsException` si la cible existe sans `replaceExisting`, `CopybotException` si la vérification échoue), `record Written(long size, String sha256)`, `void setBeforeVerify(Consumer<Path>)` (hook de test), `static String runIdOf(String tempName)`, `static final String TEMP_SUFFIX = ".copybot-tmp"`. Temporaire : `<dir>/.<nom>.<runId>.copybot-tmp`.

- [ ] **Step 1: Write the failing test**

`copybot-engine/src/test/java/com/copybot/plugin/embedded/actions/SafeFileWriterTest.java` :

```java
package com.copybot.plugin.embedded.actions;

import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Temporary file, direct write, orphans and verification (spec safe-write §3, §4). */
public class SafeFileWriterTest {

    private static final String RUN = "run1";

    @TempDir
    Path tempDir;

    private Path nas() throws IOException {
        return Files.createDirectories(tempDir.resolve("nas"));
    }

    private static SafeFileWriter writer(String actionConfig) {
        return new SafeFileWriter(FileWriteSettings.of(
                new Gson().fromJson("{\"outPattern\":\"x\"" + actionConfig + "}", FileWriteConfig.class)));
    }

    private WorkItem local(String content) throws IOException {
        Path file = Files.writeString(Files.createDirectories(tempDir.resolve("card")).resolve("photo.jpg"), content);
        WorkItem item = new WorkItem(file);
        item.getMetadatas().setSize(content.length());
        return item;
    }

    /** A card pulled out mid-copy: failAfter zero bytes, then an IOException. */
    private static InputStream failingAfter(int failAfter) {
        return new InputStream() {
            private int served;

            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (served >= failAfter) {
                    throw new IOException("card removed");
                }
                int n = Math.min(len, failAfter - served);
                Arrays.fill(b, off, off + n, (byte) 0);
                served += n;
                return n;
            }
        };
    }

    private WorkItem failing(int size, int failAfter) throws IOException {
        WorkItem item = new WorkItem(tempDir.toUri().toURL(), () -> failingAfter(failAfter));
        item.getMetadatas().setSize(size);
        return item;
    }

    private static List<String> names(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    public void tempAndRenameLeavesOnlyTheTargetAndReturnsTheSha256() throws IOException {
        Path target = nas().resolve("photo.jpg");

        SafeFileWriter.Written written = writer("").write(local("abc"), target, false, RUN, p -> {
        });

        assertEquals("abc", Files.readString(target));
        assertEquals(List.of("photo.jpg"), names(nas()), "no temporary file left");
        assertEquals(3, written.size());
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", written.sha256());
    }

    @Test
    public void theWriteCreatesTheTargetDirectoryAndKeepsTheSourceDate() throws IOException {
        Path target = tempDir.resolve("nas").resolve("2026").resolve("photo.jpg");
        WorkItem item = local("abc");
        Instant shot = Instant.parse("2026-09-01T10:00:00Z");
        item.getMetadatas().setTime(WorkItemMetadata.LAST_MODIFIED, shot);

        writer("").write(item, target, false, RUN, p -> {
        });

        assertEquals(shot, Files.getLastModifiedTime(target).toInstant());
    }

    @Test
    public void aFailingStreamLeavesNeitherTemporaryNorTarget() throws IOException {
        Path target = nas().resolve("photo.jpg");

        assertThrows(IOException.class, () -> writer("").write(failing(40000, 20000), target, false, RUN, p -> {
        }));

        assertEquals(List.of(), names(nas()));
    }

    @Test
    public void aFailedOverwriteThroughATemporaryKeepsTheOriginal() throws IOException {
        Path target = Files.writeString(nas().resolve("photo.jpg"), "original");

        assertThrows(IOException.class, () -> writer("").write(failing(40000, 20000), target, true, RUN, p -> {
        }));

        assertEquals("original", Files.readString(target));
        assertEquals(List.of("photo.jpg"), names(nas()));
    }

    @Test
    public void directDeletesThePartialFileItCreated() throws IOException {
        Path target = nas().resolve("photo.jpg");

        assertThrows(IOException.class, () -> writer(",\"writeMode\":\"direct\"").write(failing(40000, 20000), target, false, RUN, p -> {
        }));

        assertEquals(List.of(), names(nas()));
    }

    @Test
    public void directOverwriteLeavesTheFileItDidNotCreate() throws IOException {
        Path target = Files.writeString(nas().resolve("photo.jpg"), "original");

        assertThrows(IOException.class, () -> writer(",\"writeMode\":\"direct\"").write(failing(40000, 20000), target, true, RUN, p -> {
        }));

        assertTrue(Files.exists(target), "the original was lost when the write started, the partial file stays");
    }

    @Test
    public void directWritesUnderTheFinalName() throws IOException {
        Path target = nas().resolve("photo.jpg");

        writer(",\"writeMode\":\"direct\"").write(local("abc"), target, false, RUN, p -> {
        });

        assertEquals("abc", Files.readString(target));
        assertEquals(List.of("photo.jpg"), names(nas()));
    }

    @Test
    public void anExistingTargetIsNeverReplacedWithoutReplaceExisting() throws IOException {
        Path target = Files.writeString(nas().resolve("photo.jpg"), "original");

        for (String mode : List.of("", ",\"writeMode\":\"direct\"")) {
            assertThrows(FileAlreadyExistsException.class, () -> writer(mode).write(local("abc"), target, false, RUN, p -> {
            }), mode);
            assertEquals("original", Files.readString(target), mode);
            assertEquals(List.of("photo.jpg"), names(nas()), mode);
        }
    }

    @Test
    public void orphansOfAnotherRunAreDeletedThoseOfTheCurrentRunNever() throws IOException {
        Path orphan = Files.writeString(nas().resolve(".old.jpg.crashed" + SafeFileWriter.TEMP_SUFFIX), "partial");
        Path ours = Files.writeString(nas().resolve(".other.jpg." + RUN + SafeFileWriter.TEMP_SUFFIX), "in progress");
        Path unrelated = Files.writeString(nas().resolve(".keep.tmp"), "not ours");

        writer("").write(local("abc"), nas().resolve("photo.jpg"), false, RUN, p -> {
        });

        assertFalse(Files.exists(orphan));
        assertTrue(Files.exists(ours), "a temporary file of the current run is never deleted");
        assertTrue(Files.exists(unrelated));
    }

    @Test
    public void onlyTheFirstWriteOfTheRunInADirectoryCleansIt() throws IOException {
        SafeFileWriter writer = writer("");
        writer.write(local("abc"), nas().resolve("a.jpg"), false, RUN, p -> {
        });
        Path lateOrphan = Files.writeString(nas().resolve(".old.jpg.crashed" + SafeFileWriter.TEMP_SUFFIX), "partial");

        writer.write(local("abc"), nas().resolve("b.jpg"), false, RUN, p -> {
        });
        assertTrue(Files.exists(lateOrphan), "already cleaned for this run");

        writer.write(local("abc"), nas().resolve("c.jpg"), false, "run2", p -> {
        });
        assertFalse(Files.exists(lateOrphan), "the next run cleans again");
    }

    @Test
    public void theRunIdIsTheLastSegmentOfATemporaryName() {
        assertEquals("run1", SafeFileWriter.runIdOf(".IMG.0001.JPG.run1" + SafeFileWriter.TEMP_SUFFIX));
    }

    /** Test hook: rewrites the written file with other bytes of the same length before the verification. */
    private static void alter(Path file) {
        try {
            byte[] content = Files.readAllBytes(file);
            content[0] ^= 0x55;
            Files.write(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void append(Path file) {
        try {
            Files.writeString(file, "!", StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    public void readBackDetectsAnAlteredTargetAndDeletesIt() throws IOException {
        for (String mode : List.of("", ",\"writeMode\":\"direct\"")) {
            SafeFileWriter writer = writer(",\"verify\":\"readBack\"" + mode);
            writer.setBeforeVerify(SafeFileWriterTest::alter);

            assertThrows(CopybotException.class, () -> writer.write(local("abc"), nas().resolve("photo.jpg"), false, RUN, p -> {
            }), mode);

            assertEquals(List.of(), names(nas()), mode);
        }
    }

    @Test
    public void sizeDetectsAWrongSizeButNotAnAlteredContent() throws IOException {
        SafeFileWriter wrongSize = writer("");
        wrongSize.setBeforeVerify(SafeFileWriterTest::append);
        assertThrows(CopybotException.class, () -> wrongSize.write(local("abc"), nas().resolve("a.jpg"), false, RUN, p -> {
        }));
        assertFalse(Files.exists(nas().resolve("a.jpg")));

        SafeFileWriter altered = writer("");
        altered.setBeforeVerify(SafeFileWriterTest::alter);
        altered.write(local("abc"), nas().resolve("b.jpg"), false, RUN, p -> {
        });
        assertTrue(Files.exists(nas().resolve("b.jpg")), "only readBack reads the content again");
    }

    @Test
    public void noneOnlyChecksTheBytesWritten() throws IOException {
        SafeFileWriter none = writer(",\"verify\":\"none\"");
        none.setBeforeVerify(SafeFileWriterTest::append);
        none.write(local("abc"), nas().resolve("a.jpg"), false, RUN, p -> {
        });
        assertTrue(Files.exists(nas().resolve("a.jpg")), "the destination is not read again");

        WorkItem announcedLonger = local("abc");
        announcedLonger.getMetadatas().setSize(999); // the listing announced 999 bytes, the copy got 3
        assertThrows(CopybotException.class, () -> none.write(announcedLonger, nas().resolve("b.jpg"), false, RUN, p -> {
        }));
        assertFalse(Files.exists(nas().resolve("b.jpg")));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -o -q -pl copybot-engine test -Dtest=SafeFileWriterTest`
Expected: FAIL — compilation `cannot find symbol: class SafeFileWriter`.

- [ ] **Step 3: Implement**

`copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/SafeFileWriter.java` :

```java
package com.copybot.plugin.embedded.actions;

import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.embedded.actions.FileWriteSettings.WriteMode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Writes one item to its final target (spec safe-write §3, §4): through a hidden temporary file of the
 * target directory then a move (tempAndRename), or straight under the final name (direct); the SHA-256
 * is computed on the fly, then the written file is verified. A failure never leaves a temporary file,
 * nor a partial file this write created, nor a file that failed its verification.
 */
final class SafeFileWriter {

    static final String TEMP_SUFFIX = ".copybot-tmp";

    private static final int BUFFER_SIZE = 8192;

    /** What was copied: the number of bytes and their SHA-256, hex-encoded. */
    record Written(long size, String sha256) {
    }

    private record CleanedDir(String runId, Path dir) {
    }

    private final FileWriteSettings settings;

    /** The directories already cleaned of orphan temporary files, per run. */
    private final Set<CleanedDir> cleanedDirs = ConcurrentHashMap.newKeySet();

    /** Visible for tests: receives the written file right before it is verified (e.g. to alter it). */
    private volatile Consumer<Path> beforeVerify = path -> {
    };

    SafeFileWriter(FileWriteSettings settings) {
        this.settings = settings;
    }

    void setBeforeVerify(Consumer<Path> beforeVerify) {
        this.beforeVerify = beforeVerify;
    }

    /**
     * @param replaceExisting overwrite the target; otherwise an existing target is never replaced (the
     *                        write fails with FileAlreadyExistsException)
     * @param runId           the execution: names the temporary file, spares its own temporary files
     * @param percent         receives the progress of the copy, when the item size is known
     * @throws CopybotException write.verify.size / write.verify.hash when the verification fails
     */
    Written write(WorkItem item, Path target, boolean replaceExisting, String runId, IntConsumer percent) throws IOException {
        Path dir = target.toAbsolutePath().normalize().getParent();
        Files.createDirectories(dir);
        cleanOrphans(dir, runId);
        return settings.writeMode() == WriteMode.TEMP_AND_RENAME
                ? writeThroughTemp(item, target, dir.resolve("." + target.getFileName() + "." + runId + TEMP_SUFFIX),
                replaceExisting, percent)
                : writeDirect(item, target, replaceExisting, percent);
    }

    /** The temporary file is verified before the move: a failed write never touches an existing target. */
    private Written writeThroughTemp(WorkItem item, Path target, Path temp, boolean replaceExisting,
                                     IntConsumer percent) throws IOException {
        try {
            Written written;
            try (OutputStream out = Files.newOutputStream(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                written = copy(item, out, percent);
            }
            preserveDate(item, temp);
            verify(item, temp, target, written);
            moveIntoPlace(temp, target, replaceExisting);
            return written;
        } catch (Throwable e) {
            deleteQuietly(temp, e);
            throw e;
        }
    }

    /** A failed copy deletes the file only when this write created it; a failed verification always does. */
    private Written writeDirect(WorkItem item, Path target, boolean replaceExisting, IntConsumer percent) throws IOException {
        boolean existed = replaceExisting && Files.exists(target);
        OutputStream out = replaceExisting
                ? Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
                : Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        boolean complete = false;
        try {
            Written written;
            try (out) {
                written = copy(item, out, percent);
            }
            complete = true;
            preserveDate(item, target);
            verify(item, target, target, written);
            return written;
        } catch (Throwable e) {
            if (!existed || complete) {
                deleteQuietly(target, e);
            }
            throw e;
        }
    }

    private static Written copy(WorkItem item, OutputStream out, IntConsumer percent) throws IOException {
        MessageDigest digest = FileComparison.newSha256();
        Long size = item.getMetadatas().getSize();
        long transferred = 0;
        try (InputStream in = item.openInputStream()) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer, 0, BUFFER_SIZE)) >= 0) {
                out.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                transferred += read;
                if (size != null && size > 0) {
                    percent.accept((int) (transferred * 100 / size)); // multiply first: size > transferred would floor to 0
                }
            }
        }
        return new Written(transferred, FileComparison.hex(digest.digest()));
    }

    /**
     * @param file   the file written (the temporary file in tempAndRename)
     * @param target the final target, quoted in the messages
     */
    private void verify(WorkItem item, Path file, Path target, Written written) throws IOException {
        beforeVerify.accept(file);
        Long expected = item.getMetadatas().getSize();
        long expectedSize = expected != null ? expected : written.size();
        switch (settings.verify()) {
            case NONE -> checkSize(target, expectedSize, written.size());
            case SIZE -> checkSize(target, expectedSize, Files.size(file));
            case READ_BACK -> {
                checkSize(target, expectedSize, Files.size(file));
                String readBack;
                try (InputStream in = Files.newInputStream(file)) {
                    readBack = FileComparison.hex(FileComparison.sha256(in));
                }
                if (!readBack.equals(written.sha256())) {
                    throw CopybotException.ofResource("write.verify.hash", target);
                }
            }
        }
    }

    private static void checkSize(Path target, long expected, long actual) {
        if (actual != expected) {
            throw CopybotException.ofResource("write.verify.size", target, String.valueOf(expected), String.valueOf(actual));
        }
    }

    /** The copy keeps the source modification date: otherwise "sizeAndDate" would never recognise it. */
    private static void preserveDate(WorkItem item, Path file) throws IOException {
        Optional<Instant> date = FileComparison.sourceDate(item);
        if (date.isPresent()) {
            Files.setLastModifiedTime(file, FileTime.from(date.get()));
        }
    }

    private static void moveIntoPlace(Path temp, Path target, boolean replaceExisting) throws IOException {
        if (!replaceExisting) {
            Files.move(temp, target); // fails if a file appeared meanwhile: never replaced silently
            return;
        }
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * First write of this run into dir: deletes the temporary files that other runs left behind (a crash),
     * never those of the current run. Best effort: a file that cannot be deleted is left for a next run.
     */
    private void cleanOrphans(Path dir, String runId) throws IOException {
        if (!cleanedDirs.add(new CleanedDir(runId, dir))) {
            return;
        }
        try (DirectoryStream<Path> temps = Files.newDirectoryStream(dir, ".*" + TEMP_SUFFIX)) {
            for (Path temp : temps) {
                if (!runId.equals(runIdOf(temp.getFileName().toString()))) {
                    try {
                        Files.deleteIfExists(temp);
                    } catch (IOException e) {
                        // e.g. still open by another running copy: not this write's business
                    }
                }
            }
        }
    }

    /** ".name.runId.copybot-tmp" gives runId (a run id has no dot, a name may have several). */
    static String runIdOf(String tempName) {
        String stem = tempName.substring(0, tempName.length() - TEMP_SUFFIX.length());
        return stem.substring(stem.lastIndexOf('.') + 1);
    }

    private static void deleteQuietly(Path file, Throwable failure) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            failure.addSuppressed(e);
        }
    }
}
```

(`catch (Throwable e) { …; throw e; }` compile grâce au *precise rethrow* : seul `IOException` est vérifié dans le `try`.)

- [ ] **Step 4: Run the test**

Run: `mvn -o -q -pl copybot-engine test -Dtest=SafeFileWriterTest`
Expected: PASS (14 tests).

- [ ] **Step 5: Commit**

```bash
git add copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/SafeFileWriter.java copybot-engine/src/test/java/com/copybot/plugin/embedded/actions/SafeFileWriterTest.java
git commit -m "Write through a temporary file, hash on the fly and verify" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 10: `file.write` recâblée — conflit, écriture sûre, SHA-256, `deleteSource`

**Files:**
- Modify (réécriture): `copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileWriteAction.java` (CRLF)
- Modify: `copybot-engine/src/main/java/com/copybot/plugin/api/action/WorkItemMetadata.java` (CRLF)
- Test: `copybot-engine/src/test/java/com/copybot/plugin/embedded/actions/FileWriteActionTest.java`
- Test: `copybot-engine/src/test/java/com/copybot/engine/ResumeEndToEndTest.java`

**Interfaces:**
- Consumes: `FileWriteSettings` (6), `FileComparison` (7), `ConflictResolver` (8), `SafeFileWriter` (9), `WriteResult` / `WriteContext` (2), clé `write.delete-source.failed` (1), `pipelineWithOut` (6).
- Produces: `public static final String WorkItemMetadata.SHA256 = "sha256"` ; `FileWriteAction.write(WorkItem, WriteContext)` : `WriteResult.skipped(cible, raison)` sur conflit `skip`, `WriteResult.written(cible effective)` sinon, `raw["sha256"]` = SHA-256 hex de la copie ; `deleteSource` après écriture + vérification, ou après un skip identique en `fullHash` ; échec de suppression ⇒ `CopybotException` (item `ERROR`) ; `writeItem(item)` = `write(item, WriteContext.newRun())`.

- [ ] **Step 1: Write the failing tests**

Dans `FileWriteActionTest`, ajouter les imports

```java
import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.WriteContext;
import com.copybot.plugin.api.action.WriteResult;
import java.io.UncheckedIOException;
```

(les deux premiers avec les imports `com.copybot…`, le dernier après `import java.io.IOException;`), puis, avant le test `resolveTargetAppliesTheOutPatternWithoutWriting` :

```java
    // ---- safe write (spec safe-write) ----

    /** file.write to outFile; extra is appended to the actionConfig object (e.g. ",\"deleteSource\":true"). */
    private FileWriteAction action(Path outFile, String extra) {
        FileWriteAction action = new FileWriteAction();
        action.loadConfig(JsonParser.parseString(
                "{\"outPattern\":\"" + outFile.toString().replace("\\", "\\\\") + "\"" + extra + "}"));
        return action;
    }

    private Path nasFile(String name, String content) throws IOException {
        return Files.writeString(Files.createDirectories(tempDir.resolve("nas")).resolve(name), content);
    }

    @Test
    public void overwriteFalseSkipsAnIdenticalTarget() throws IOException {
        Path target = nasFile("same.bin", "same");
        WorkItem item = itemWithContent("src6.bin", "same".getBytes());

        WriteResult result = action(target, false).write(item, WriteContext.newRun());

        assertTrue(result.isSkipped(), "legacy overwrite=false: identical means skip, not error");
        assertEquals(target, result.target());
        assertTrue(result.reason().contains("same.bin"), result.reason());
    }

    @Test
    public void aDifferentTargetIsRenamedByDefaultAndTheResultTellsWhere() throws IOException {
        Path target = nasFile("photo.jpg", "other content");
        WorkItem item = itemWithContent("src7.bin", "abc".getBytes());

        WriteResult result = action(target, "").write(item, WriteContext.newRun());

        Path renamed = target.resolveSibling("photo (1).jpg");
        assertEquals(WriteResult.written(renamed), result);
        assertEquals("abc", Files.readString(renamed));
        assertEquals("other content", Files.readString(target));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                item.getMetadatas().raw().get(WorkItemMetadata.SHA256), "hash computed during the copy");
    }

    @Test
    public void deleteSourceRemovesTheSourceOnceWrittenAndVerified() throws IOException {
        Path target = tempDir.resolve("nas").resolve("photo.jpg");
        WorkItem item = itemWithContent("src8.bin", "abc".getBytes());

        action(target, ",\"deleteSource\":true,\"verify\":\"readBack\"").write(item, WriteContext.newRun());

        assertEquals("abc", Files.readString(target));
        assertFalse(Files.exists(tempDir.resolve("src8.bin")));
        assertTrue(item.isDeleted());
    }

    @Test
    public void deleteSourceKeepsTheSourceWhenTheWriteFails() throws IOException {
        Path target = nasFile("photo.jpg", "other content");
        WorkItem item = itemWithContent("src9.bin", "abc".getBytes());
        FileWriteAction action = action(target, ",\"deleteSource\":true,\"onConflict\":{\"ifDifferent\":\"error\"}");

        assertThrows(CopybotException.class, () -> action.write(item, WriteContext.newRun()));

        assertTrue(Files.exists(tempDir.resolve("src9.bin")));
        assertFalse(item.isDeleted());
    }

    @Test
    public void anIdenticalSkipDeletesTheSourceOnlyWhenComparedWithFullHash() throws IOException {
        Path target = nasFile("photo.jpg", "abc");
        WorkItem fullHash = itemWithContent("src10.bin", "abc".getBytes());
        WorkItem partialHash = itemWithContent("src11.bin", "abc".getBytes());

        assertTrue(action(target, ",\"deleteSource\":true,\"onConflict\":{\"compare\":\"fullHash\"}")
                .write(fullHash, WriteContext.newRun()).isSkipped());
        assertTrue(action(target, ",\"deleteSource\":true").write(partialHash, WriteContext.newRun()).isSkipped());

        assertFalse(Files.exists(tempDir.resolve("src10.bin")), "fullHash proved the destination holds everything");
        assertTrue(Files.exists(tempDir.resolve("src11.bin")), "partialHash did not read the whole content");
    }

    @Test
    public void aSkippedDifferentTargetNeverDeletesTheSource() throws IOException {
        Path target = nasFile("photo.jpg", "other content");
        WorkItem item = itemWithContent("src12.bin", "abc".getBytes());

        WriteResult result = action(target, ",\"deleteSource\":true,\"onConflict\":{\"compare\":\"fullHash\",\"ifDifferent\":\"skip\"}")
                .write(item, WriteContext.newRun());

        assertTrue(result.isSkipped());
        assertTrue(Files.exists(tempDir.resolve("src12.bin")));
    }

    @Test
    public void theEngineRunIdNamesTheTemporaryFile() throws IOException {
        Path target = tempDir.resolve("nas").resolve("photo.jpg");
        WorkItem item = itemWithContent("src13.bin", "abc".getBytes());
        FileWriteAction action = action(target, ",\"verify\":\"readBack\"");
        List<Path> temps = new CopyOnWriteArrayList<>();
        action.setStatusWatcher(status -> {
            if (!Files.isDirectory(target.getParent())) {
                return; // the first status comes before the target directory is created
            }
            try (var files = Files.list(target.getParent())) {
                files.filter(p -> p.getFileName().toString().endsWith(SafeFileWriter.TEMP_SUFFIX)).forEach(temps::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });

        action.write(item, new WriteContext("engine-run-42"));

        assertEquals(List.of(target.resolveSibling(".photo.jpg.engine-run-42" + SafeFileWriter.TEMP_SUFFIX)),
                temps.stream().distinct().toList(), "the copy went through a temporary file named after the run");
        assertFalse(Files.exists(temps.getFirst()));
    }
```

Dans `ResumeEndToEndTest.allReimportsEverything`, modification **autorisée** (Global Constraints) — remplacer

```java
        // IMG_02.JPG still exists and overwrite=false: that item fails (exit code 1), which does not stop IMG_01
        assertEquals(1, cli(pipeline, "--all"));
```

par

```java
        // IMG_02.JPG is still there and identical: overwrite=false now skips it (spec safe-write §1), IMG_01 is copied again
        assertEquals(0, cli(pipeline, "--all"));
```

puis ajouter à la fin de la classe :

```java
    @Test
    public void aSecondRunSkipsTheFilesAlreadyThereAndSucceeds() throws IOException {
        Path pipeline = pipelineWithOut(null, "");
        assertEquals(0, cli(pipeline));

        String[] again = capture(pipeline);

        assertEquals("0", again[0], again[2]);
        assertFalse(again[2].contains("ERROR"), again[2]);
        assertFalse(Files.exists(nas.resolve("IMG_01 (1).JPG")), "an identical file is not copied twice");
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -o -q -pl copybot-engine test -Dtest='FileWriteActionTest,ResumeEndToEndTest'`
Expected: FAIL — compilation `cannot find symbol: variable SHA256` (après ajout de la constante seule : les nouveaux tests échouent sur `CREATE_NEW`, `allReimportsEverything` attend `0` et obtient `1`, `aSecondRunSkipsTheFilesAlreadyThereAndSucceeds` obtient `1`).

- [ ] **Step 3: Implement**

Dans `WorkItemMetadata.java` (CRLF, Edit), remplacer

```java
    public static final String LAST_MODIFIED = "lastModified";
```

par

```java
    public static final String LAST_MODIFIED = "lastModified";
    /** raw key: SHA-256 of the content (hex), computed by file.write during the copy. */
    public static final String SHA256 = "sha256";
```

Réécrire `FileWriteAction.java` en entier (Write), puis `unix2dos -q copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileWriteAction.java` :

```java
package com.copybot.plugin.embedded.actions;

import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.AbstractActionWithConfig;
import com.copybot.plugin.api.action.IOutAction;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import com.copybot.plugin.api.action.WorkStatus;
import com.copybot.plugin.api.action.WriteContext;
import com.copybot.plugin.api.action.WriteResult;
import com.copybot.plugin.embedded.actions.FileWriteSettings.Compare;
import com.google.gson.JsonElement;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Writes the items under outPattern (spec safe-write): an existing target is compared then skipped,
 * renamed, overwritten or refused; the copy goes through a temporary file (or not), is hashed and
 * verified; the source is deleted afterwards when asked.
 */
public class FileWriteAction extends AbstractActionWithConfig<FileWriteConfig> implements IOutAction {
    private static final Pattern PARAM_PATTERN = Pattern.compile("\\{(.*?)\\}");

    private volatile FileWriteSettings settings;
    private volatile ConflictResolver conflicts;
    private volatile SafeFileWriter writer;

    @Override
    protected Class<FileWriteConfig> getConfigClass() {
        return FileWriteConfig.class;
    }

    /** @throws CopybotException when the configuration is invalid */
    @Override
    public void loadConfig(JsonElement config) {
        super.loadConfig(config);
        settings = FileWriteSettings.of(getConfig());
        conflicts = new ConflictResolver(settings);
        writer = new SafeFileWriter(settings);
    }

    @Override
    public List<String> configWarnings() {
        return settings.warnings();
    }

    @Override
    public Set<Path> touchedPaths(WorkItem item) {
        // static prefix of the out pattern, before the first {placeholder}
        String outPattern = settings.outPattern();
        int firstParam = outPattern.indexOf('{');
        String prefix = firstParam < 0 ? outPattern : outPattern.substring(0, firstParam);
        return Set.of(Path.of(prefix));
    }

    /** Direct use outside the engine: a write under a new run id; a skipped item simply returns. */
    @Override
    public void writeItem(WorkItem workItem) {
        write(workItem, WriteContext.newRun());
    }

    @Override
    public WriteResult write(WorkItem workItem, WriteContext context) {
        Path target = Path.of(resolveFileName(workItem));
        try {
            return doWrite(workItem, target, context.runId());
        } catch (IOException e) {
            throw CopybotException.ofResource(e, "plugin.embedded.file.write.error.io", target);
        }
    }

    @Override
    public Optional<Path> resolveTarget(WorkItem workItem) {
        return Optional.of(Path.of(resolveFileName(workItem)));
    }

    private String resolveFileName(WorkItem workItem) {
        return PARAM_PATTERN.matcher(settings.outPattern())
                .replaceAll(m -> workItem.getMetadatas().display().getOrDefault(m.group(1), m.group(0)).toString());
    }

    private WriteResult doWrite(WorkItem workItem, Path target, String runId) throws IOException {
        updateStatus(new WorkStatus("Copy file " + workItem.getSourceLocationDisplay(), -1));

        ConflictResolver.Decision decision = conflicts.resolve(workItem, target);
        if (decision.isSkip()) {
            // only a full comparison proves the destination holds the whole content (spec safe-write §5)
            if (decision.identical() && settings.compare() == Compare.FULL_HASH) {
                deleteSource(workItem, decision.target());
            }
            return WriteResult.skipped(decision.target(), decision.skipReason());
        }

        if (canBeMoved(workItem, decision.target())) {
            move(workItem, decision);
            return WriteResult.written(decision.target());
        }
        SafeFileWriter.Written written = writer.write(workItem, decision.target(), decision.replaceExisting(), runId,
                this::updatePercent);
        workItem.getMetadatas().raw().put(WorkItemMetadata.SHA256, written.sha256());
        deleteSource(workItem, decision.target());
        return WriteResult.written(decision.target());
    }

    /** The existing fast path (temporary source or "delete after", same file system): not rewritten, not hashed. */
    private static void move(WorkItem workItem, ConflictResolver.Decision decision) throws IOException {
        Path parent = decision.target().toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (decision.replaceExisting()) {
            Files.move(workItem.getLocalLocation(), decision.target(), StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.move(workItem.getLocalLocation(), decision.target());
        }
        workItem.setDeleted(!workItem.isTempFile());
    }

    /** deleteSource: once written and verified, or already at the destination and compared with fullHash. */
    private void deleteSource(WorkItem workItem, Path target) {
        Path source = FileComparison.localSource(workItem);
        if (!settings.deleteSource() || workItem.isDeleted() || source == null) {
            return; // nothing asked, already gone, or not a local file Copybot could delete
        }
        try {
            Files.deleteIfExists(source);
            workItem.setDeleted(true);
        } catch (IOException e) {
            // the copy is done: the next run finds it identical, and retries the deletion with fullHash
            throw CopybotException.ofResource(e, "write.delete-source.failed", source, target);
        }
    }

    private static boolean canBeMoved(WorkItem workItem, Path outPath) {
        // can be moved if either is a temp file or the original file with delete option enabled
        if ((workItem.isLocal() && workItem.isDeleteAfterCompletion()) || workItem.isTempFile()) {
            // can be moved if on the same disk
            return workItem.getLocalLocation().getFileSystem().provider() == outPath.getFileSystem().provider();
        }
        return false;
    }

}
```

- [ ] **Step 4: Run the tests**

Run: `mvn -o -q -pl copybot-engine test`
Expected: PASS — y compris les tests d'écriture existants (`overwriteTrue…`, `overwriteFalseFailsOnAnExistingTargetFile` : contenu différent ⇒ `error`, `progressPercentsAreProportionalNotZero` : 50 puis 100), `MainTest`, `CopybotEngineTest`, `CopybotExitCodeTest` (`anItemInErrorExitsOne` : contenu différent ⇒ toujours `1`).

- [ ] **Step 5: Commit**

```bash
git ls-files --eol copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileWriteAction.java copybot-engine/src/main/java/com/copybot/plugin/api/action/WorkItemMetadata.java
git add copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileWriteAction.java copybot-engine/src/main/java/com/copybot/plugin/api/action/WorkItemMetadata.java copybot-engine/src/test/java/com/copybot/plugin/embedded/actions/FileWriteActionTest.java copybot-engine/src/test/java/com/copybot/engine/ResumeEndToEndTest.java
git commit -m "Wire the safe write and source deletion into file.write" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

(Les deux fichiers de production : `w/crlf`.)

---

### Task 11: Filtres de lecture de `file.read`

**Files:**
- Modify (réécriture): `copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileReadConfig.java` (CRLF)
- Modify (réécriture): `copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileReadAction.java` (CRLF)
- Create: `copybot-engine/src/test/java/com/copybot/plugin/embedded/actions/FileReadActionTest.java`

**Interfaces:**
- Consumes: clé `read.config.invalid-glob` (tâche 1).
- Produces: `public record FileReadConfig(String path, Boolean recursive, List<String> include, List<String> exclude, Boolean includeHidden)` avec `boolean isRecursive()` (défaut `true`) et `boolean isIncludeHidden()` (défaut `false`) ; `FileReadAction.loadConfig` compile les globs (lève `CopybotException` si invalide) ; package-private `static boolean FileReadAction.isHidden(Path, BasicFileAttributes)`.

- [ ] **Step 1: Write the failing test**

`copybot-engine/src/test/java/com/copybot/plugin/embedded/actions/FileReadActionTest.java` :

```java
package com.copybot.plugin.embedded.actions;

import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.WorkItem;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** file.read: recursive, include / exclude, hidden files (spec safe-write §7). */
public class FileReadActionTest {

    @TempDir
    Path tempDir;

    Path card;

    @BeforeEach
    public void createCard() throws IOException {
        card = Files.createDirectories(tempDir.resolve("card"));
        file("a.jpg");
        file("b.NEF");
        file("notes.tmp");
        file("sub/c.JPG");
        file("sub/deep/d.nef");
        file("sub/e.tmp");
    }

    private Path file(String relative) throws IOException {
        Path file = card.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, relative);
    }

    /** Lists the card with this extra actionConfig; relative paths with '/', sorted. */
    private List<String> list(String extra) {
        FileReadAction action = new FileReadAction();
        action.loadConfig(JsonParser.parseString(
                "{\"path\":\"" + card.toString().replace("\\", "\\\\") + "\"" + extra + "}"));
        List<String> listed = new ArrayList<>();
        action.listFiles((WorkItem item) -> listed.add(card.relativize(item.getLocalLocation()).toString().replace('\\', '/')));
        return listed.stream().sorted().toList();
    }

    @Test
    public void thePathAloneListsTheWholeTree() {
        assertEquals(List.of("a.jpg", "b.NEF", "notes.tmp", "sub/c.JPG", "sub/deep/d.nef", "sub/e.tmp"), list(""));
    }

    @Test
    public void recursiveFalseListsTheFirstLevelOnly() {
        assertEquals(List.of("a.jpg", "b.NEF", "notes.tmp"), list(",\"recursive\":false"));
        assertEquals(6, list(",\"recursive\":true").size());
    }

    @Test
    public void includeIsCaseInsensitiveAndAlsoMatchesTheFirstLevel() {
        assertEquals(List.of("a.jpg", "b.NEF", "sub/c.JPG", "sub/deep/d.nef"),
                list(",\"include\":[\"**/*.NEF\",\"**/*.jpg\"]"));
        assertEquals(List.of("sub/c.JPG"), list(",\"include\":[\"sub/*.jpg\"]"));
    }

    @Test
    public void excludeWinsOverInclude() {
        assertEquals(List.of("a.jpg", "b.NEF", "sub/c.JPG", "sub/deep/d.nef"),
                list(",\"include\":[\"**/*\"],\"exclude\":[\"**/*.TMP\"]"));
        assertEquals(List.of("a.jpg", "b.NEF", "notes.tmp", "sub/c.JPG", "sub/e.tmp"),
                list(",\"exclude\":[\"sub/deep/**\"]"));
    }

    @Test
    public void hiddenFilesAndFoldersAreSkippedByDefault() throws IOException {
        file(".hidden.jpg");
        file("._a.jpg");
        file(".thumbnails/t.jpg");
        file("System Volume Information/IndexerVolumeGuid");
        file("$RECYCLE.BIN/r.jpg");

        assertEquals(6, list("").size(), "only the visible files");
        assertEquals(11, list(",\"includeHidden\":true").size());
    }

    @Test
    public void theWindowsHiddenAndSystemAttributesHideAFile() throws IOException {
        assumeTrue(Files.getFileStore(card).supportsFileAttributeView(DosFileAttributeView.class), "a DOS file system");
        Files.setAttribute(file("hidden.jpg"), "dos:hidden", true);
        Files.setAttribute(file("system.jpg"), "dos:system", true);

        assertFalse(list("").contains("hidden.jpg"));
        assertFalse(list("").contains("system.jpg"));
        assertTrue(list(",\"includeHidden\":true").containsAll(List.of("hidden.jpg", "system.jpg")));
    }

    @Test
    public void anInvalidGlobIsAConfigurationErrorQuotingIt() {
        CopybotException e = assertThrows(CopybotException.class, () -> list(",\"include\":[\"[a\"]"));
        assertTrue(e.getMessage().contains("[a"), e.getMessage());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -o -q -pl copybot-engine test -Dtest=FileReadActionTest`
Expected: FAIL — le test compile (il ne passe que du JSON) ; 6 échecs (`recursive`, `include`, `exclude`, cachés, attributs DOS, glob invalide) ; `thePathAloneListsTheWholeTree` passe déjà (rétro-compatibilité).

- [ ] **Step 3: Implement**

Réécrire `FileReadConfig.java` en entier (Write), puis `unix2dos -q copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileReadConfig.java` :

```java
package com.copybot.plugin.embedded.actions;

import java.util.List;

/**
 * The "actionConfig" of file.read (spec safe-write §7).
 *
 * @param recursive     the whole tree (default), or the first level only when false
 * @param include       globs relative to path, case-insensitive; absent or empty: every file
 * @param exclude       globs relative to path, case-insensitive; they win over include
 * @param includeHidden hidden files and directories are skipped unless true
 */
public record FileReadConfig(
        String path,

        Boolean recursive,

        List<String> include,

        List<String> exclude,

        Boolean includeHidden
) {

    public boolean isRecursive() {
        return recursive == null || recursive;
    }

    public boolean isIncludeHidden() {
        return Boolean.TRUE.equals(includeHidden);
    }
}
```

Réécrire `FileReadAction.java` en entier (Write), puis `unix2dos -q copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileReadAction.java` :

```java
package com.copybot.plugin.embedded.actions;

import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.AbstractActionWithConfig;
import com.copybot.plugin.api.action.IInAction;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import com.google.gson.JsonElement;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.PatternSyntaxException;

/** Lists the files under path (spec safe-write §7): recursive or not, include / exclude globs, hidden files skipped. */
public class FileReadAction extends AbstractActionWithConfig<FileReadConfig> implements IInAction {

    private volatile List<PathMatcher> includes = List.of();
    private volatile List<PathMatcher> excludes = List.of();

    @Override
    protected Class<FileReadConfig> getConfigClass() {
        return FileReadConfig.class;
    }

    /** @throws CopybotException read.config.invalid-glob */
    @Override
    public void loadConfig(JsonElement config) {
        super.loadConfig(config);
        includes = matchers(getConfig().include());
        excludes = matchers(getConfig().exclude());
    }

    @Override
    public Set<Path> touchedPaths(WorkItem item) {
        return Set.of(Path.of(getConfig().path()));
    }

    @Override
    public void listFiles(Consumer<WorkItem> workItemConsumer) {
        FileReadConfig config = getConfig();
        Path root = Path.of(config.path());
        try {
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class),
                    config.isRecursive() ? Integer.MAX_VALUE : 1, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            // the listed directory itself is never filtered (a drive root is hidden and system on Windows)
                            boolean skipped = !dir.equals(root) && !config.isIncludeHidden() && isHidden(dir, attrs);
                            return skipped ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                            // with recursive=false the sub-directories arrive here: not regular files
                            if (attrs.isRegularFile() && (config.isIncludeHidden() || !isHidden(file, attrs))
                                    && selected(root.relativize(file))) {
                                WorkItem wi = new WorkItem(file);
                                extractMetadata(file, wi.getMetadatas());
                                workItemConsumer.accept(wi);
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException | UncheckedIOException e) {
            throw CopybotException.ofResource(e, "plugin.embedded.file.read.error.io", e.getLocalizedMessage());
        }
    }

    /**
     * A leading dot (Unix, macOS "._" files), the Windows hidden or system attribute, the Windows
     * "System Volume Information" and "$RECYCLE.BIN" folders.
     */
    static boolean isHidden(Path path, BasicFileAttributes attrs) {
        Path fileName = path.getFileName();
        if (fileName == null) {
            return false;
        }
        String name = fileName.toString();
        if (name.startsWith(".") || name.equalsIgnoreCase("System Volume Information") || name.equalsIgnoreCase("$RECYCLE.BIN")) {
            return true;
        }
        return attrs instanceof DosFileAttributes dos && (dos.isHidden() || dos.isSystem());
    }

    private boolean selected(Path relative) {
        Path lowerCase = Path.of(relative.toString().toLowerCase(Locale.ROOT));
        boolean included = includes.isEmpty() || includes.stream().anyMatch(m -> m.matches(lowerCase));
        return included && excludes.stream().noneMatch(m -> m.matches(lowerCase));
    }

    /**
     * Case-insensitive globs: patterns and paths are lower-cased. "**&#47;x" also matches x at the first
     * level ("**&#47;" alone requires a directory).
     */
    private static List<PathMatcher> matchers(List<String> globs) {
        if (globs == null) {
            return List.of();
        }
        List<PathMatcher> matchers = new ArrayList<>();
        for (String glob : globs) {
            String pattern = glob.toLowerCase(Locale.ROOT);
            try {
                matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + pattern));
                if (pattern.startsWith("**/")) {
                    matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + pattern.substring(3)));
                }
            } catch (PatternSyntaxException e) {
                throw CopybotException.ofResource(e, "read.config.invalid-glob", glob);
            }
        }
        return List.copyOf(matchers);
    }

    private void extractMetadata(Path path, WorkItemMetadata metadatas) throws IOException {
        // file metadatas
        metadatas.display().put("name", path.getFileName().toString());

        BasicFileAttributes attr = Files.readAttributes(path, BasicFileAttributes.class);
        metadatas.setSize(attr.size());
        metadatas.setTime("creation", attr.creationTime().toInstant());
        metadatas.setTime(WorkItemMetadata.LAST_MODIFIED, attr.lastModifiedTime().toInstant());
        metadatas.setTime("lastAccess", attr.lastAccessTime().toInstant());
    }
}
```

(Dans la javadoc de `matchers`, `**&#47;` évite la séquence `*/` qui fermerait le commentaire.)

- [ ] **Step 4: Run the tests**

Run: `mvn -o -q -pl copybot-engine test`
Expected: PASS (dont `FileReadActionTest` : 7 tests, `theWindowsHiddenAndSystemAttributesHideAFile` exécuté sous Windows, sauté ailleurs ; `EmbeddedActionResourcesTest` et les pipelines `path` seul inchangés).

- [ ] **Step 5: Commit**

```bash
git ls-files --eol copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileReadConfig.java copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileReadAction.java
git add copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileReadConfig.java copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileReadAction.java copybot-engine/src/test/java/com/copybot/plugin/embedded/actions/FileReadActionTest.java
git commit -m "Add recursive, include/exclude and hidden-file filters to file.read" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

(Les deux fichiers de production : `w/crlf`.)

---

### Task 12: Avertissements de configuration — dry-run, stderr, bandeau UI ; vérification finale

**Files:**
- Modify: `copybot-engine/src/main/java/com/copybot/PlanPrinter.java`
- Modify: `copybot-engine/src/main/java/com/copybot/Copybot.java` (CRLF)
- Modify: `copybot-ui/src/main/java/com/copybot/ui/HelloController.java`
- Modify: `copybot-ui/src/main/resources/com/copybot/ui/views/hello-view.fxml`
- Test: `copybot-engine/src/test/java/com/copybot/engine/ResumeEndToEndTest.java`

**Interfaces:**
- Consumes: `PipelineState.getWarnings()` (tâche 5), `FileWriteAction.configWarnings()` (tâches 6, 10), `pipelineWithOut` (tâche 6).
- Produces: dry-run : une ligne `Warning: <avertissement>` par `configWarning` (avant ceux de la reprise) sur stdout ; exécution réelle : `Warning: <avertissement>` sur stderr, une seule fois, dès que les étapes sont résolues (watcher passé à `prepare` / `run`) ; UI : label `warningBanner` visible seulement s'il y a des avertissements.

- [ ] **Step 1: Write the failing tests**

Dans `ResumeEndToEndTest`, ajouter à la fin de la classe :

```java
    private static boolean hasWarning(String output) {
        return output.lines().anyMatch(line -> line.startsWith("Warning: "));
    }

    @Test
    public void dryRunPrintsTheConfigurationWarningsAndDeletesNothing() throws IOException {
        Path pipeline = pipelineWithOut("state", ", \"deleteSource\": true");

        String[] result = capture(pipeline, "--dry-run");

        assertEquals("0", result[0], result[2]);
        assertTrue(hasWarning(result[1]), result[1]);
        assertTrue(Files.exists(card.resolve("IMG_01.JPG")), "a dry run deletes nothing");
    }

    @Test
    public void aRunPrintsTheConfigurationWarningsOnStderrThenMovesTheFiles() throws IOException {
        Path pipeline = pipelineWithOut(null, ", \"deleteSource\": true");

        String[] result = capture(pipeline);

        assertEquals("0", result[0], result[2]);
        assertEquals(1, result[2].lines().filter(line -> line.startsWith("Warning: ")).count(), result[2]);
        assertTrue(Files.exists(nas.resolve("IMG_01.JPG")));
        assertFalse(Files.exists(card.resolve("IMG_01.JPG")), "deleteSource: the source is gone once copied and verified");
    }

    @Test
    public void aSafeConfigurationPrintsNoWarning() throws IOException {
        Path pipeline = pipelineWithOut("state", ", \"deleteSource\": true, \"verify\": \"readBack\"");

        String[] dryRun = capture(pipeline, "--dry-run");
        String[] run = capture(pipeline);

        assertFalse(hasWarning(dryRun[1]), dryRun[1]);
        assertFalse(hasWarning(run[2]), run[2]);
        assertEquals("0", run[0], run[2]);
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -o -q -pl copybot-engine test -Dtest=ResumeEndToEndTest`
Expected: FAIL — `dryRunPrintsTheConfigurationWarningsAndDeletesNothing` (aucune ligne `Warning:`) et `aRunPrintsTheConfigurationWarningsOnStderrThenMovesTheFiles` (`expected: <1> but was: <0>`) ; `aSafeConfigurationPrintsNoWarning` passe déjà (garde-fou).

- [ ] **Step 3: Implement the CLI**

Dans `PlanPrinter.java` (LF), remplacer

```java
/** Dry-run output: the resume point, its origin, the warnings, then one line per item. */
```

par

```java
/**
 * Dry-run output: the resume point, its origin, the warnings (the steps' configuration first, then the
 * resume ones), then one line per item.
 */
```

puis remplacer

```java
        proposal.warnings().forEach(w -> out.println("Warning: " + w));
```

par

```java
        plan.getState().getWarnings().forEach(w -> out.println("Warning: " + w));
        proposal.warnings().forEach(w -> out.println("Warning: " + w));
```

Dans `Copybot.java` (CRLF, Edit), remplacer

```java
import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
```

par

```java
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
```

remplacer

```java
        Execution execution;
        if (isDryRun || resumeOverride != null) {
            Plan plan = engine.prepare(pipelinePath, null);
```

par

```java
        Execution execution;
        // a dry run prints the warnings with the plan (stdout), a real run on stderr as soon as they are known
        Consumer<PipelineState> watcher = isDryRun ? null : warningsOnStderrOnce();
        if (isDryRun || resumeOverride != null) {
            Plan plan = engine.prepare(pipelinePath, watcher);
```

remplacer

```java
            execution = engine.run(pipelinePath, null);
        }
```

par

```java
            execution = engine.run(pipelinePath, watcher);
        }
```

et remplacer

```java
    /**
     * Waits for the execution. Meanwhile a JVM shutdown (Ctrl+C) cancels it and gives it a few seconds
```

par

```java
    /**
     * A watcher printing the configuration warnings of the steps on stderr, once, as soon as the steps are
     * resolved (spec safe-write §5): the first notifications come before, the terminal one never misses them.
     */
    private static Consumer<PipelineState> warningsOnStderrOnce() {
        AtomicBoolean printed = new AtomicBoolean();
        return state -> {
            List<String> warnings = state.getWarnings();
            if (!warnings.isEmpty() && printed.compareAndSet(false, true)) {
                warnings.forEach(w -> System.err.println("Warning: " + w));
            }
        };
    }

    /**
     * Waits for the execution. Meanwhile a JVM shutdown (Ctrl+C) cancels it and gives it a few seconds
```

(La notification terminale d'une phase est livrée avant la fin de `prepare()` et avant `Execution.await()` : l'avertissement est toujours imprimé avant que la CLI ne rende la main.)

- [ ] **Step 4: Implement the UI banner**

Dans `HelloController.java` (LF), remplacer

```java
    @FXML
    private Label fileCount;
```

par

```java
    @FXML
    private Label warningBanner;

    @FXML
    private Label fileCount;
```

remplacer

```java
        fileCount.setText("");
    }
```

par

```java
        fileCount.setText("");
        showWarnings(List.of());
    }
```

remplacer

```java
    @FXML
    protected void onExitClick() {
```

par

```java
    /** The configuration warnings of the pipeline (spec safe-write §5), hidden when there is none. */
    private void showWarnings(List<String> warnings) {
        warningBanner.setText(String.join("\n", warnings));
        boolean visible = !warnings.isEmpty();
        warningBanner.setVisible(visible);
        warningBanner.setManaged(visible);
    }

    @FXML
    protected void onExitClick() {
```

remplacer

```java
                List<WorkItemExecution> list = List.copyOf(state.getWorkItems()); // snapshot outside the FX thread; the queue may evolve concurrently
```

par

```java
                List<WorkItemExecution> list = List.copyOf(state.getWorkItems()); // snapshot outside the FX thread; the queue may evolve concurrently
                List<String> warnings = state.getWarnings();
```

et remplacer

```java
                    fileCount.setText(summary);
```

par

```java
                    fileCount.setText(summary);
                    showWarnings(warnings);
```

Dans `hello-view.fxml` (LF), remplacer

```xml
      <children>
         <Label fx:id="fileCount">
```

par

```xml
      <children>
         <Label fx:id="warningBanner" maxWidth="Infinity" wrapText="true" style="-fx-background-color: #fff3cd; -fx-text-fill: #664d03; -fx-padding: 6;">
            <VBox.margin>
               <Insets bottom="5.0" />
            </VBox.margin>
         </Label>
         <Label fx:id="fileCount">
```

- [ ] **Step 5: Run the tests, the UI compilation, then the full build**

Run: `mvn -o -q -pl copybot-engine test`
Expected: PASS.

Run: `mvn -o -q -pl copybot-engine,copybot-ui compile`
Expected: PASS (aucune ligne `[ERROR]`).

Run: `mvn -o clean install`
Expected: `BUILD SUCCESS` pour tous les modules (plugins de démo et metadata-extractor compilés sans modification contre la nouvelle API ; le `clean` évite l'échec connu de jpackage « Application destination directory … already exists »).

- [ ] **Step 6: Commit**

```bash
git ls-files --eol copybot-engine/src/main/java/com/copybot/Copybot.java copybot-engine/src/main/java/com/copybot/PlanPrinter.java copybot-ui/src/main/java/com/copybot/ui/HelloController.java copybot-ui/src/main/resources/com/copybot/ui/views/hello-view.fxml
git add copybot-engine/src/main/java/com/copybot/PlanPrinter.java copybot-engine/src/main/java/com/copybot/Copybot.java copybot-ui/src/main/java/com/copybot/ui/HelloController.java copybot-ui/src/main/resources/com/copybot/ui/views/hello-view.fxml copybot-engine/src/test/java/com/copybot/engine/ResumeEndToEndTest.java
git commit -m "Show configuration warnings in the dry-run, on stderr and in the UI" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

(`Copybot.java` : `w/crlf` ; les autres : `w/lf`.)

---

## Couverture de la spec

| Spec | Tâche |
|---|---|
| §1 configuration `file.write`, défauts, `outPattern` seul obligatoire, `overwrite` hérité (`true` ⇒ `overwrite`, `false` ⇒ `error`, `ifIdentical: skip`), `overwrite` + `onConflict` ⇒ erreur de configuration | 6 (validation, test CLI code 2), 8 (compatibilité appliquée), 10 (écriture, `--all` ⇒ 0) |
| §2 `compare` (`size`, `sizeAndDate` ±2 s, `partialHash` 64 + 64 Kio ou tout ≤ 128 Kio, `fullHash`), taille comparée d'abord sans lire | 7 |
| §2 `skip` (raisons identique / existe), `rename` `(1)`, `(2)`… avec comparaison de chaque candidat, `overwrite`, `error` | 8, 10 |
| §2 un `SKIPPED` d'exécution compte comme succès pour `nextCursor` | 3 (statut), 4 (curseur) |
| §3 `tempAndRename` (`.<nom>.<runId>.copybot-tmp`, déplacement atomique sinon remplacement, temporaire supprimé en cas d'échec), `direct` (partiel supprimé s'il a été créé), orphelins d'un autre `runId` supprimés au premier passage, jamais ceux du run courant | 9 |
| §3 `runId` unique par exécution, fourni par le moteur | 2 (`WriteContext`), 3 (`MainExecutor`), 10 (nom du temporaire) |
| §3 déplacement existant conservé | 10 (`canBeMoved` / `move` inchangés, conflit appliqué) |
| §4 SHA-256 au vol dans `raw["sha256"]` (hex), `verify` `none` / `size` / `readBack`, échec ⇒ cible supprimée, item `ERROR` | 9, 10 |
| §5 `deleteSource` après écriture + vérification ; skip identique ⇒ suppression seulement en `fullHash` ; échec de suppression ⇒ `ERROR` | 10 |
| §5 avertissement `deleteSource` sans `readBack` : dry-run `Warning:`, stderr au démarrage, bandeau UI | 6 (`warnings()`), 5 (état), 12 (CLI, UI) |
| §6 `IOutAction.write` par défaut, `WriteResult` / `WriteContext`, `MainExecutor` appelle `write`, `SKIPPED` ⇒ item `SKIPPED` avec raison, étapes suivantes non exécutées ; `IAction.configWarnings`, `PipelineState.getWarnings()`, ajoutés au dry-run | 2, 3, 5, 12 |
| §7 `recursive` effectif (défaut `true`), `include` / `exclude` insensibles à la casse (`exclude` l'emporte), `includeHidden` (point, attributs caché / système, `System Volume Information`, `$RECYCLE.BIN`, `._*`), rétro-compatibilité `path` seul | 11 |
| §8 messages EN / FR (ISO-8859-1, `\uXXXX`, octet par octet) : skip, vérification, suppression, conflit `error`, `overwrite` + `onConflict`, avertissement | 1 |
| §9 conflits (chaque `compare` dont ±2 s, chaque politique, `rename` retrouvant un identique en `(1)`, compatibilité `overwrite`) | 6, 7, 8, 10 |
| §9 écriture (aucun temporaire laissé, flux qui lève ⇒ ni temporaire ni cible, `direct` supprime son partiel, orphelins d'un autre `runId` supprimés, ceux du run courant jamais) | 9 |
| §9 vérification (`readBack` détecte une cible altérée via le hook, `size` une taille fausse) | 9 |
| §9 `deleteSource` (supprimée après succès, conservée après échec, skip identique + `fullHash` ⇒ supprimée sinon conservée, avertissement sans `readBack`) | 6, 10, 12 |
| §9 moteur (un `SKIPPED` d'écriture fait avancer le curseur ; `configWarnings` dans l'état et le dry-run) | 4, 5, 12 |
| §9 lecture (`recursive: false`, include / exclude, cachés exclus par défaut) | 11 |

# Correction du fuseau horaire depuis le plan - plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** dans le plan préparé, l'utilisateur choisit le fuseau du lieu de prise de vue pour une sélection de lignes ; copybot ré-analyse ces fichiers, recalcule leur date de prise de vue (DST compris), leur cible, leurs conflits, et en reprise `everyFile` leur verdict « déjà à destination ».

**Architecture:** le choix voyage dans les métadonnées brutes du `WorkItem` (clés publiques de `WorkItemMetadata`) ; le moteur ne fait que les poser, remettre les éléments « à analyser » et réutiliser `analyseDeferred` ; le plugin metadata-extractor fait tout le calcul ; l'UI ajoute deux entrées de menu et une fenêtre.

**Tech Stack:** Java 25 (JDK `C:\Program Files\Eclipse Adoptium\jdk-25*`), JavaFX 27, JUnit 5, Maven 3.8.8, metadata-extractor 2.21.0.

**Spec:** `docs/superpowers/specs/2026-10-25-timezone-correction-design.md`

## Global Constraints

- Sans correction : préparation, copie, reprise, CLI, streaming **strictement identiques** (tous les tests existants verts, sans les modifier).
- Le moteur ne calcule ni EXIF ni DST : il transporte `ZoneId`s.
- La correction vaut pour cette copie seulement : rien n'est persisté.
- Textes UI dans `uiBundle.properties`, `_fr`, `_it` (parité vérifiée par `UiBundleTest`), écrits en échappements `\uXXXX` (voir la mémoire : écrire le texte brut puis convertir avec un script perl, une séquence `\u` tapée arrive décodée).
- Fichiers Java en LF dans git ; certains fichiers sont CRLF dans le worktree : éditer avec Edit, jamais `sed -i`, vérifier `git diff --stat`.
- Maven : toujours `-am` avec `-pl` (`mvn -pl copybot-ui -am ...`) ; build complet `mvn verify`.

## Review Focus

1. Analyse arrêtée en cours de correction : les éléments gardent leurs clés et restent « à analyser » ; la copie les analyse avec la correction (test Task 2).
2. Photo sans décalage dont l'heure tombe dans un trou ou un chevauchement DST de l'horloge choisie (2026-03-29 02:30 Paris) : pas d'exception, `atZone` standard de java.time (test Task 1).
3. Recorriger une ligne déjà corrigée avec un autre fuseau : le dernier choix gagne (test Task 2).
4. Ligne ignorée corrigée : reste ignorée après la ré-analyse (test Task 2).
5. Ligne sans `captureDate` (fichier non image) : pas corrigeable, le compteur du menu l'exclut (test Task 3).

---

### Task 1: Clés de correction et calcul dans le plugin

**Files:**
- Modify: `copybot-engine/src/main/java/com/copybot/plugin/api/action/WorkItemMetadata.java`
- Modify: `copybot-plugin/copybot-plugin-metadata-extractor/src/main/java/com/copybot/plugin/metadataextractor/actions/ExtractMetadata.java`
- Test: `copybot-plugin/copybot-plugin-metadata-extractor/src/test/java/com/copybot/plugin/metadataextractor/actions/ExtractMetadataTest.java`

**Interfaces:**
- Produces (dans `WorkItemMetadata`) :
  - `public static final String CAPTURE_ZONE = "captureZone";` valeur brute `ZoneId`, posée par le moteur : fuseau du lieu de prise de vue.
  - `public static final String CAMERA_CLOCK = "cameraClock";` valeur brute `ZoneId` (peut être un `ZoneOffset`), optionnelle : réglage de l'horloge de l'appareil.
  - `public static final String CAPTURE_CLOCK_UNKNOWN = "captureClockUnknown";` valeur brute `Boolean`, posée par le plugin à chaque analyse qui trouve une date : `true` pour une photo EXIF sans décalage noté.
  - `public Optional<ZoneId> getZone(String key)` (vide si absent ou pas un `ZoneId`).
- Produces (dans `ExtractMetadata`, package-private) :
  - `record Capture(ZonedDateTime date, boolean clockUnknown)` ; `captureDate(...)` existant renvoie désormais `Optional<Capture>` (mêmes paramètres).
  - `static ZonedDateTime corrected(Capture capture, ZoneId place, ZoneId clock /* nullable */)` :
    `clockUnknown` ? `date.toLocalDateTime().atZone(clock != null ? clock : place).withZoneSameInstant(place)` : `date.withZoneSameInstant(place)`.
- `doAnalyze` : pose `CAPTURE_CLOCK_UNKNOWN`, puis `setTime(CAPTURE_DATE, place présent ? corrected(...) : capture.date())`.

- [ ] **Step 1: Write the failing tests** (dans `ExtractMetadataTest`, adapter les tests existants à `Capture`)

```java
// Sony sans heure d'été, décalage noté +01:00, juillet, lieu Paris -> 14:00 Paris
correctedShowsARecordedOffsetInThePlaceZone:
  corrected(new Capture(ZonedDateTime.parse("2026-07-10T13:00:00+01:00"), false), PARIS, null)
  == ZonedDateTime.parse("2026-07-10T14:00:00+02:00[Europe/Paris]")
// sans décalage, horloge "Paris sans heure d'été" = +01:00 fixe
correctedReadsAnUnknownClockInTheCameraClock:
  corrected(new Capture(ZonedDateTime.parse("2026-07-10T13:00:00+02:00[Europe/Paris]"), true), PARIS, ZoneOffset.ofHours(1))
  == ZonedDateTime.parse("2026-07-10T14:00:00+02:00[Europe/Paris]")
// boîtier resté à l'heure de Paris au Mexique, sans décalage, horloge Paris donnée
correctedMovesAParisClockToMexico:
  corrected(new Capture(ZonedDateTime.parse("2026-10-25T20:00:00+01:00[Europe/Paris]"), true), MEXICO, PARIS)
  == ZonedDateTime.parse("2026-10-25T13:00:00-06:00[America/Mexico_City]")
// sans horloge, une heure sans décalage est lue dans le fuseau du lieu
correctedReadsAnUnknownClockInThePlaceByDefault:
  corrected(new Capture(ZonedDateTime.parse("2026-10-25T20:00:00+01:00[Europe/Paris]"), true), MEXICO, null)
  == ZonedDateTime.parse("2026-10-25T20:00:00-06:00[America/Mexico_City]")
// Review Focus 2 : trou DST, pas d'exception
correctedSurvivesADstGap:
  assertDoesNotThrow(() -> corrected(new Capture(ZonedDateTime.parse("2026-03-29T02:30:00+01:00"), true), PARIS, PARIS))
// doAnalyze : clé posée sur un JPEG sans décalage -> true ; avec décalage -> false
analyzeTellsWhetherTheClockIsUnknown
// doAnalyze avec CAPTURE_ZONE = MEXICO sur le JPEG "2026:10:25 23:30:00" "+01:00" -> captureDate.D == "25", .m == "10"
// (instant 22:30Z -> 16:30 Mexico)
analyzeAppliesTheCaptureZone
// sans CAPTURE_ZONE : résultat identique à aujourd'hui (les tests existants le couvrent)
```

- [ ] **Step 2: Run, expect compile failure / FAIL**

Run: `mvn -pl copybot-plugin/copybot-plugin-metadata-extractor -am test -Dtest=ExtractMetadataTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation error (`Capture`, `corrected`, `CAPTURE_ZONE` absents).

- [ ] **Step 3: Implement** les clés et `getZone` dans `WorkItemMetadata`, `Capture` / `corrected` / `doAnalyze` dans `ExtractMetadata` (la vidéo n'est jamais `clockUnknown` : son UTC est fiable).

- [ ] **Step 4: Run, expect PASS** (même commande) ; puis `mvn -pl copybot-engine test -Dtest=WorkItemMetadataTest` vert.

- [ ] **Step 5: Commit** `Time zone correction: the metadata plugin applies a chosen capture zone and camera clock`

---

### Task 2: Moteur - corriger, ré-analyser, revérifier la destination

**Files:**
- Modify: `copybot-engine/src/main/java/com/copybot/engine/Plan.java` (près de `ignore`, :225)
- Modify: `copybot-engine/src/main/java/com/copybot/engine/MainExecutor.java` (`setIgnored` :809, `analyseDeferred` :627)
- Modify: `copybot-engine/src/main/java/com/copybot/engine/resume/ResumeResolver.java`
- Test: create `copybot-engine/src/test/java/com/copybot/engine/ZoneCorrectionTest.java` (fixtures : `ManualPointTest` `RecordingOut`/`CountingAnalyze`, `MainExecutorResumeTest.destination(...)` avec `new ResumeContext(mode, store, DestinationCheck.EVERY_FILE, DestinationMatch.FILE)`)

**Interfaces:**
- Consumes: `WorkItemMetadata.CAPTURE_ZONE`, `CAMERA_CLOCK` (Task 1).
- Produces:
  - `Plan.correctZone(Collection<WorkItemExecution> items, ZoneId place, ZoneId clock /* nullable */, ResumePoint override)` ; `Plan.clearZone(Collection<WorkItemExecution> items, ResumePoint override)`. `IllegalStateException` (`engine.not-prepared`) hors PREPARED, comme `ignore`. Ensuite l'appelant lance `CopybotEngine.analyse(plan)` (les éléments sont dans `plan.toAnalyse()`).
  - `ResumeResolver.recheck(Collection<WorkItemExecution> items)` : pour chaque élément, `target` → `checkedPath` → `atDestination` : `put` si présent, `remove` sinon. Sans effet de bord sur `seenDirectories` ni les warnings.
- Comportement de `MainExecutor.setCaptureZone(items, place, clock, override)` (place null = annuler) :
  - éléments retenus : `PENDING` ou `SKIPPED`, non `isAnalysisDeferred()` (déjà analysés) ;
  - pose / retire `CAPTURE_ZONE` et `CAMERA_CLOCK` dans `getWorkItem().getMetadatas().raw()` ;
  - `markAnalysisDeferred()`, `setProjection(null)`, `setTargetCheck(null)` ; les SKIPPED vont dans `requestedAnalysis` (comme `requestAnalysis`) ;
  - mémorise ces éléments dans un nouvel ensemble `zoneRecheck` et `override` dans un champ `recheckOverride`.
- Fin de `analyseDeferred` (après la restauration des raisons de saut, seulement si l'analyse n'a pas été arrêtée et `zoneRecheck` non vide) : si le point en vigueur (`recheckOverride` sinon `proposal.point()`) est de type `NOT_AT_DESTINATION`, `resolver.recheck(zoneRecheck)` puis `resolver.apply(point, source, orderedItems)` comme `setIgnored` (:821-827) ; vider `zoneRecheck` dans tous les cas où l'analyse s'est terminée. En `lastFound` (ex-dichotomie) : rien (le verdict est une frontière sur la date de modification, inchangée).

- [ ] **Step 1: Write the failing tests** (`ZoneCorrectionTest`)

```java
correctZoneAsksForTheAnalysisOfTheseFilesAgain:   // PENDING analysé -> dans plan.toAnalyse(); après analyseDeferred, analyse comptée 2 fois, cible recalculée
correctZoneSetsTheZoneKeysAndClearZoneRemovesThem
aSkippedFileCorrectedStaysSkippedForItsReason      // SKIPPED par le curseur : même raison après ré-analyse
anIgnoredFileCorrectedStaysIgnored                 // Review Focus 4
theLastCorrectionWins                              // Review Focus 3 : Paris puis Mexico -> CAPTURE_ZONE == Mexico
aStoppedReanalysisLeavesTheFilesToTheCopy          // Review Focus 1 : cancelAnalysis pendant l'analyse -> isAnalysisDeferred(), clés présentes ; execute() analyse
correctZoneRequiresAPreparedPlan                   // IllegalStateException
everyFile_aCorrectedFileWhoseNewTargetIsFreeIsSelected        // ancienne cible présente, nouvelle absente : SKIPPED at-destination -> PENDING
everyFile_aCorrectedFileWhoseNewTargetExistsIsSkipped         // l'inverse : PENDING -> SKIPPED "resume.skip.at-destination"
everyFile_clearZoneBringsBackTheFirstVerdict
lastFound_aCorrectionChangesNoStatus
```

Pour faire varier la cible avec la zone sans le plugin réel : une analyse de test qui lit `CAPTURE_ZONE` et pose `captureDate` (ou un `RecordingOut` dont la cible dépend d'une clé d'affichage) ; garder la cible `nas/<jour>/<nom>` des fixtures existantes.

- [ ] **Step 2: Run, expect compile failure**

Run: `mvn -pl copybot-engine test -Dtest=ZoneCorrectionTest -Dsurefire.failIfNoSpecifiedTests=false`

- [ ] **Step 3: Implement** `Plan.correctZone` / `clearZone`, `MainExecutor.setCaptureZone`, la fin de `analyseDeferred`, `ResumeResolver.recheck` (réutiliser les helpers privés `target`, `checkedPath`, `atDestination`).

- [ ] **Step 4: Run, expect PASS** ; puis `mvn -pl copybot-engine test` complet vert (non-régression).

- [ ] **Step 5: Commit** `Time zone correction: the plan corrects the zone of files, analyses them again and rechecks their destination`

---

### Task 3: Interface - menu, fenêtre, marque

**Files:**
- Modify: `copybot-ui/src/main/java/com/copybot/ui/model/PlanViewModel.java` (près de `ignorable` :615, `detailText` :816)
- Modify: `copybot-ui/src/main/java/com/copybot/ui/PlanController.java` (`rowWithMenu` :368, cellule cible :134-146)
- Create: `copybot-ui/src/main/java/com/copybot/ui/ZoneCorrectionDialog.java` (modèle : `ResumeDialog.create`, filtre texte comme `PatternHelper` :363-405)
- Modify: `copybot-ui/src/main/resources/com/copybot/ui/i18n/uiBundle.properties`, `_fr`, `_it`
- Test: `copybot-ui/src/test/java/com/copybot/ui/model/PlanViewModelTest.java`

**Interfaces:**
- Consumes: `Plan.correctZone` / `clearZone` (Task 2), `WorkItemMetadata.CAPTURE_ZONE`, `CAPTURE_CLOCK_UNKNOWN`, `getZone`, `getTime(CAPTURE_DATE)` (Task 1).
- Produces:
  - `PlanViewModel.correctable(List<WorkItemExecution> rows)` : `phase == PREPARED`, `!isAnalysisDeferred()`, `PENDING` ou `SKIPPED`, `getTime(CAPTURE_DATE)` présent.
  - `PlanViewModel.uncorrectable(rows)` : `phase == PREPARED`, `getZone(CAPTURE_ZONE)` présent.
  - `static long clockUnknownCount(rows)` : lignes avec `CAPTURE_CLOCK_UNKNOWN == TRUE`.
  - `static Optional<String> correctionText(WorkItemExecution item)` : `plan.zone.corrected` = « Fuseau corrigé : {0}, prise de vue {1} » (`{1}` = `captureDate` dans ce fuseau, `yyyy-MM-dd HH:mm`) ; vide sans correction. Utilisé par l'infobulle de la cible et ajouté comme ligne par `detailText` après la ligne de statut.
  - `ZoneCorrectionDialog.create(ZoneId initial, long clockUnknownCount)` → `Dialog<ZoneChoice>`, `record ZoneChoice(ZoneId place, ZoneId clock /* nullable */)` : liste filtrable de `ZoneId.getAvailableZoneIds()` triés (filtre minuscule `contains`, OK grisé sans sélection) ; si `clockUnknownCount > 0` : texte « {0} fichiers sans fuseau enregistré », liste « L'appareil était réglé sur » (défaut : le lieu), case « sans heure d'été » → `clock = zone.getRules().getStandardOffset(Instant.now())`. Lieu initial : le fuseau commun déjà corrigé de la sélection, sinon `ZoneId.systemDefault()`.
- `PlanController` : entrées « Corriger le fuseau horaire ({0})… » (`plan.menu.zone`) et « Annuler la correction ({0}) » (`plan.menu.zone-clear`), comptées par `setCountedItem` (0 si `ops.busy()`), placées après `analyse`. OK → `plan.correctZone(items, place, clock, model().override())`, puis comme `analyse(items)` (:657) : `ops.analyse(plan.toAnalyse(), null)`. Annuler → `plan.clearZone(...)` puis même chemin. `RuntimeException` → `PopinUtil.showError`. Cellule cible : si `correctionText` présent, classe CSS `zone-corrected` (un repère discret, ex. italique) et infobulle = ce texte (avant l'infobulle multi-dossiers existante si les deux).

- [ ] **Step 1: Write the failing tests** (`PlanViewModelTest`, sur le modèle de `ignorable` :680-697)

```java
correctableKeepsAnalysedFilesWithACaptureDate      // + Review Focus 5 : sans captureDate -> exclu ; différé -> exclu ; hors PREPARED -> vide
uncorrectableKeepsTheCorrectedFiles
clockUnknownCountCountsThePhotosWithoutOffset
correctionTextTellsTheZoneAndTheCorrectedDate      // CAPTURE_ZONE=America/Mexico_City, captureDate 2026-10-26T05:30Z -> "... America/Mexico_City ... 2026-10-25 23:30"
detailTextShowsTheCorrection
```

- [ ] **Step 2: Run, expect compile failure**

Run: `mvn -pl copybot-ui -am test -Dtest=PlanViewModelTest -Dsurefire.failIfNoSpecifiedTests=false`

- [ ] **Step 3: Implement** le modèle, la fenêtre, le menu, la cellule, les clés en/fr/it (`plan.menu.zone`, `plan.menu.zone-clear`, `plan.zone.corrected`, `zone.dialog.title`, `zone.dialog.place`, `zone.dialog.filter`, `zone.dialog.clock`, `zone.dialog.clock-files`, `zone.dialog.no-dst`).

- [ ] **Step 4: Run, expect PASS** ; puis `mvn -pl copybot-ui -am test` (dont `UiBundleTest`) vert.

- [ ] **Step 5: Vérifier dans l'app** (`CopybotMainUiDev`, après `mvn package` du plugin) : préparer un pipeline sur la carte, corriger une photo DSC07217 en `America/Mexico_City`, cible au 25/10 ; annuler, cible d'origine. Si l'environnement ne permet pas de lancer l'UI, le dire dans le rapport.

- [ ] **Step 6: Commit** `Time zone correction: the plan menu corrects the time zone of the selected files`

---

### Task 4: Vérification finale

- [ ] `mvn verify` complet : tous les modules verts, comptes de tests reportés.
- [ ] Revue de code de toute la branche (superpowers:requesting-code-review), corrections, `mvn verify` à nouveau.
- [ ] Squash par fonction sur la base d'origine (`git reset --soft a340e0b` puis commits : capture date / design+plan / correction de fuseau), `git log <base>..develop` vérifié avant intégration ; donner à Steven la commande `git merge --ff-only`.

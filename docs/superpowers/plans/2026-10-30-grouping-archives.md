# Regroupements et dépliage des archives — plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** au listage, rattacher les fichiers compagnons à leur fichier principal et déplier les archives ZIP, pour
toute source, avant l'analyse.

**Architecture:** un tuyau du cœur par étape d'entrée, entre `listFiles` et `MainExecutor.emitItem` :
`ArchiveUnfolder` (déplie) → `Grouper` (regroupe par dossier, au signal de fin de dossier) → `emitItem` inchangé.
Les compagnons voyagent sur le `WorkItem` principal ; `FileWriteAction` les écrit avec une résolution de conflit
solidaire. Les entrées d'archive sont des `WorkItem.remote` avec un effaceur de source ; `ArchiveSources` supprime
l'archive quand toutes ses entrées ont été libérées.

**Tech Stack:** Java 25 (JPMS), JUnit 5, Gson, JavaFX 25 ; `java.util.zip` (pas de nouvelle dépendance).

**Spec:** `docs/superpowers/specs/2026-10-30-grouping-archives-design.md` (à lire avant chaque tâche).

## Global Constraints

- JDK 25 (`C:\Program Files\Eclipse Adoptium\jdk-25*`) ; vérification complète : `mvn -q verify` à la racine ;
  module seul : `mvn -q verify -pl copybot-engine -am`.
- API des plugins : **1.1 → 1.2, ajouts seulement** (`copybot.plugin.api.version` dans `pom.xml`) ; régénérer
  l'empreinte avec `mvn clean verify -pl copybot-engine -Dplugin.api.update=true` (jamais `=force`).
- Fichiers Java en LF ; bundles `.properties` avec échappements `\uXXXX` (le moteur est ASCII, vérifié par test) ;
  toute nouvelle clé d'IHM en en, fr **et** it (`UiBundleTest`).
- Pas de nouvelle dépendance Maven dans le moteur.
- Valeurs fixées par la spec : profondeur d'archive max **5** (l'archive listée = 1) ; taux de compression max **×100**
  par entrée au-delà de **1 Mo** décompressé ; dossier de travail `java.io.tmpdir/copybot-archives/run-<id>/` ;
  séparateur d'emplacement `!/` (`a.zip!/x/b.zip!/y`) ; règle `folder` par défaut `{base}_files`.
- Comparaisons de noms et d'extensions sans casse (`Locale.ROOT`).
- Commits : un par tâche, message en anglais, terminé par
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. Compagnon listé **avant** son principal, et sous-dossier entrelacé avec les fichiers de son parent (ordre de
   `walkFileTree` non trié) : le groupe doit être le même que dans l'ordre inverse — test dans la tâche 4.
2. Archive corrompue, chiffrée ou tronquée au milieu de la lecture : élément d'archive en erreur, le reste du listage
   continue, aucun dossier de travail laissé — test dans la tâche 6.
3. Mode déplacement avec un compagnon dont l'écriture échoue : **aucune** source supprimée, groupe en erreur, run
   suivant qui rattrape le compagnon manquant — test dans la tâche 8.
4. Entrée d'archive au nom piégé (`../../x.jpg`, `C:\x.jpg`, nom vide d'un dossier `a/`) : jamais d'échappement de la
   cible, les dossiers ne deviennent pas des éléments — test dans la tâche 6.
5. Annulation pendant un dépliage ou pendant l'attente d'une fin de dossier : le listage s'arrête proprement
   (`PhaseStopped`), le dossier de travail est supprimé — test dans la tâche 7.

---

### Task 1: API des plugins 1.2 (compagnons, sources non locales, fin de dossier)

**Files:**
- Create: `copybot-engine/src/main/java/com/copybot/plugin/api/action/Companion.java`,
  `CompanionRole.java`, `CompanionLink.java`, `ListingSink.java`, `SourceDeleter.java` (même paquet)
- Modify: `copybot-engine/src/main/java/com/copybot/plugin/api/action/WorkItem.java`, `IInAction.java`,
  `IOutAction.java`, `pom.xml` (racine, `copybot.plugin.api.version` 1.2),
  `copybot-engine/src/test/resources/plugin-api.txt` (régénéré)
- Test: `copybot-engine/src/test/java/com/copybot/plugin/api/action/WorkItemCompanionTest.java`

**Interfaces:**
- Produces:
  - `enum CompanionRole { COPY, READ }`
  - `enum CompanionLink { BASE, NAME }` — BASE : nom final du principal sans sa dernière extension + suffixe ;
    NAME : nom final complet + suffixe.
  - `record Companion(WorkItem item, CompanionRole role, CompanionLink link, String suffix)` avec
    `String targetName(String mainTargetFileName)` (le suffixe peut contenir `/` : chemin relatif, règle `folder`).
  - `interface ListingSink { void item(WorkItem item); void folderDone(String folder); }`
  - `@FunctionalInterface interface SourceDeleter { void delete() throws IOException; }`
  - `WorkItem` : `List<Companion> getCompanions()` (non modifiable, vide par défaut) ;
    `WorkItem withCompanions(List<Companion> companions)` (copie) ; `WorkItem withoutCompanions()` ;
    `String getListedFolder()` (parent du `Path` pour un fichier ; fourni pour `remote`) ;
    `static WorkItem remote(String name, String listedFolder, String sourceDisplay, long size, Instant lastModified,
    Supplier<InputStream> content, SourceDeleter deleter)` (deleter nullable) ;
    `Optional<SourceDeleter> getSourceDeleter()`. `copyForDryRun`, `withContent`, `withContentFile` gardent
    compagnons, dossier listé et effaceur.
  - `IInAction` : `default void listFiles(ListingSink sink) { listFiles(sink::item); }`,
    `default boolean signalsFolders() { return false; }`
  - `IOutAction` : `default boolean writesCompanions() { return false; }`

- [ ] **Step 1: Write the failing tests** — `WorkItemCompanionTest` :
  - `remoteItemHasNameDisplayAndKeyMetadata` : `remote("IMG_1.jpg", "a.zip!/DCIM", "a.zip!/DCIM", 10, t, s, null)` ⇒
    `getNameDisplay()=="IMG_1.jpg"`, `display().get("name")=="IMG_1.jpg"`, taille 10, `LAST_MODIFIED==t`,
    `isLocal()==false`, `getLocalLocation()==null`, `getListedFolder()=="a.zip!/DCIM"`,
    `ItemKey.of(item)` présent avec le nom `IMG_1.jpg`.
  - `companionsSurviveCopies` : `withCompanions(List.of(c))` puis `copyForDryRun()`, `withContent(...)`,
    `withContentFile(...)` gardent `c` ; `withoutCompanions()` les vide ; l'original n'est jamais modifié.
  - `targetNameByLink` : BASE `".xmp"` sur `"IMG_1 (1).CR3"` ⇒ `"IMG_1 (1).xmp"` ; NAME `".xmp"` sur
    `"IMG_1.CR3"` ⇒ `"IMG_1.CR3.xmp"` ; BASE `"_files/img/a.png"` sur `"page.html"` ⇒ `"page_files/img/a.png"` ;
    BASE sur un nom sans extension `"README"` + `".txt"` ⇒ `"README.txt"`.
  - `fileItemListedFolderIsParent` : `new WorkItem(dir.resolve("x.jpg")).getListedFolder()==dir.toString()`.
  - `listFilesSinkDefaultsToConsumer` : un `IInAction` qui n'implémente que `listFiles(Consumer)` reçoit les éléments
    par `listFiles(ListingSink)` ; `signalsFolders()==false`.
- [ ] **Step 2: Run** `mvn -q verify -pl copybot-engine -am -Dtest=WorkItemCompanionTest -Dsurefire.failIfNoSpecifiedTests=false` — Expected: échec de compilation.
- [ ] **Step 3: Implement** les types et méthodes ci-dessus (javadoc sur le contrat : `folderDone` promet que tout
  ce qui est sous ce dossier, sous-dossiers compris, a été émis ; `writesCompanions` : l'action écrit les compagnons
  COPY après le principal, à partir de sa cible finale).
- [ ] **Step 4: Run** le test — Expected: PASS ; puis `mvn clean verify -pl copybot-engine -am -Dplugin.api.update=true`
  et vérifier que `plugin-api.txt` ne contient que des ajouts (`git diff`).
- [ ] **Step 5: Commit** `Plugin API 1.2: companions, remote items with a source deleter, end of folder signal`.

### Task 2: Fin de dossier dans `FileReadAction`

**Files:**
- Modify: `copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileReadAction.java`
- Test: `copybot-engine/src/test/java/com/copybot/plugin/embedded/actions/FileReadActionTest.java`

**Interfaces:**
- Consumes: `ListingSink`, `IInAction.signalsFolders()` (tâche 1).
- Produces: `FileReadAction.listFiles(ListingSink)` émet `folderDone(dir.toString())` dans `postVisitDirectory`
  pour chaque dossier parcouru ; `signalsFolders()==true` ; `listFiles(Consumer)` reste (délègue).

- [ ] **Step 1: Failing tests** :
  - `signalsEachFolderAfterItsSubfolders` : arbre `root/a.jpg, root/sub/b.jpg, root/sub/deep/c.jpg` ⇒ `folderDone`
    de `deep` avant celui de `sub`, avant celui de `root` ; chaque élément émis avant le `folderDone` de son dossier
    et de tous ses ancêtres.
  - `nonRecursiveSignalsRootOnlyAtTheEnd` : `recursive=false` ⇒ un seul `folderDone(root)`, après tous les fichiers.
  - `skippedSubtreeSignalsNothing` : dossier caché ou exclu par `x/**` ⇒ aucun `folderDone` pour lui.
- [ ] **Step 2: Run** `-Dtest=FileReadActionTest` — Expected: FAIL.
- [ ] **Step 3: Implement** : `visitor(...)` reçoit le `ListingSink` ; `postVisitDirectory` émet le signal (en non
  récursif, `walkFileTree` à profondeur 1 n'appelle `postVisitDirectory` que pour la racine : vérifier).
- [ ] **Step 4: Run** — Expected: PASS (tous les tests existants de la classe aussi).
- [ ] **Step 5: Commit** `file.read signals the end of each folder`.

### Task 3: Configuration du pipeline (`grouping`, `archives`) et préréglages

**Files:**
- Create: `copybot-engine/src/main/java/com/copybot/engine/pipeline/GroupingConfig.java`,
  `GroupingRuleConfig.java`, `ArchivesConfig.java`, `copybot-engine/src/main/java/com/copybot/engine/listing/GroupingPresets.java`
- Modify: `copybot-engine/src/main/java/com/copybot/engine/pipeline/PipelineConfig.java`, `PipelineChecks.java`,
  `copybot-engine/src/main/resources/...` bundle moteur (messages de validation, en/fr/it)
- Test: `copybot-engine/src/test/java/com/copybot/engine/pipeline/GroupingConfigTest.java`

**Interfaces:**
- Produces:
  - `record GroupingConfig(List<GroupingRuleConfig> rules)`
  - `record GroupingRuleConfig(String preset, String type, List<String> main, Map<String,String> companions,
    String folder, String role, String companion, String link)` — `type` ∈ `sameBase|doubleExtension|folder|regex` ;
    rôles `copy|read|ignore` ; `link` ∈ `base|name` (regex seulement, défaut `name`).
  - `record ArchivesConfig(Boolean unfold, Boolean nested, Boolean mergeFolder, List<String> include,
    List<String> exclude)` avec `isUnfold()`, `isNested()`, `isMergeFolder()` (absent ⇒ false).
  - `PipelineConfig` : deux composants ajoutés en fin, `GroupingConfig grouping`, `ArchivesConfig archives` ; les
    constructeurs existants sont gardés (nulls).
  - `GroupingPresets.expand(GroupingConfig) -> List<GroupingRuleConfig>` (préréglages remplacés par leurs règles) et
    `boolean impliesMergeFolder(GroupingConfig)` (vrai si `takeout`).
  - `PipelineChecks.groupingRefusal(PipelineConfig, IOutAction out) -> Optional<String>` : règle invalide (type
    inconnu, regex qui ne compile pas, `folder` qui ne commence pas par `{base}`, préréglage inconnu) ou compagnons
    COPY avec une sortie dont `writesCompanions()` est faux (pas de refus sans sortie).
- Préréglages (valeurs à reprendre telles quelles) :
  - `raw-jpeg-xmp` : `sameBase`, main `[cr3, cr2, nef, arw, raf, orf, rw2, dng, pef, srw, jpg, jpeg, heic]`,
    companions `{jpg: copy, jpeg: copy, heic: copy, xmp: copy}` + `doubleExtension` `{xmp: copy}` ;
  - `iphone` : `sameBase`, main `[heic, jpg, jpeg]`, companions `{aae: copy, mov: copy}` ;
  - `action-cam` : `sameBase`, main `[mp4]`, companions `{lrv: copy, thm: copy}` ;
  - `sony-xml` : `regex`, main `(.+)\.mp4`, companion `\1M01\.xml`, role `read`, link `base` ;
  - `web-page` : `folder`, main `[html, htm]`, folder `{base}_files`, role `copy` ;
  - `takeout` : `regex`, main `(.+\.(?:jpe?g|heic|png|gif|mp4|mov))`, companion
    `\1(?:\.supplemental-metadata)?\.json`, role `read`, link `name` ; implique `mergeFolder`.

- [ ] **Step 1: Failing tests** : lecture par `PipelineReader` d'un pipeline sans les blocs (⇒ nulls, comportement
  inchangé) ; lecture du bloc exemple de la spec (§ Regroupements/Configuration et `archives`) ; `expand` de chaque
  préréglage ; refus : regex `"(("`, folder `"files_{base}"`, préréglage `"nope"`, COPY avec une sortie sans
  `writesCompanions` ; pas de refus pour un pipeline sans sortie.
- [ ] **Step 2: Run** `-Dtest=GroupingConfigTest` — Expected: FAIL.
- [ ] **Step 3: Implement** (le refus est un message du bundle ; c'est la tâche 7 qui l'appelle, une fois les étapes
  résolues, et le transforme en échec de préparation comme les autres refus).
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** `Pipeline blocks grouping and archives, with presets and validation`.

### Task 4: `Grouper` — le regroupement, sans l'exécuteur

**Files:**
- Create: `copybot-engine/src/main/java/com/copybot/engine/listing/Grouper.java`,
  `copybot-engine/src/main/java/com/copybot/engine/listing/GroupingRule.java` (règle compilée)
- Test: `copybot-engine/src/test/java/com/copybot/engine/listing/GrouperTest.java`

**Interfaces:**
- Consumes: `GroupingRuleConfig`, `GroupingPresets.expand` (tâche 3), `Companion`, `ListingSink` (tâche 1).
- Produces: `final class Grouper implements ListingSink` — `Grouper(List<GroupingRuleConfig> rules,
  ListingSink downstream)` ; `item` garde l'élément dans le tampon de `getListedFolder()` ; `folderDone(folder)`
  décide le dossier et émet vers `downstream` (principaux avec `withCompanions`, éléments non rattachés tels quels,
  puis `downstream.folderDone(folder)`) ; `void finish()` décide tout ce qui reste (source sans signal, fin de
  listage). Éléments au rôle `ignore` : jamais émis.
- Règles de décision (spec § Types de règles) : règles dans l'ordre, un fichier rattaché n'est plus candidat, un
  seul niveau, compagnon à plusieurs principaux ⇒ premier selon la priorité `main`, compagnon sans principal ⇒
  élément ordinaire (quel que soit son rôle). `regex` : le nom du principal doit correspondre **entièrement** à
  `main` ; l'expression `companion` est construite en remplaçant `\1`…`\9` par `Pattern.quote` des groupes capturés,
  puis comparée au nom entier (sans casse). Suffixe : `sameBase` ⇒ après le nom sans extension, lien BASE ;
  `doubleExtension` ⇒ après le nom complet, lien NAME ; `regex` ⇒ après le texte du groupe 1, lien de la règle ;
  `folder` ⇒ `"_files/" + chemin relatif` pour `{base}_files` (le suffixe est `folder` sans `{base}` + `/` + relatif),
  lien BASE.
- Règle `folder` : un dossier `X` dont le nom correspond à `folder` pour un `<base>` possible n'est **pas** émis à
  son propre `folderDone` ni à celui de ses sous-dossiers ; ses éléments rejoignent le tampon du dossier parent et
  sont décidés à la fin du parent.

- [ ] **Step 1: Failing tests** (chaque test vérifie les éléments émis, leurs compagnons, rôles, liens, suffixes) :
  - `sameBasePrefersMainByPriority` (`IMG_1.CR3`, `IMG_1.JPG`, `IMG_1.xmp` ⇒ CR3 principal, JPG et xmp COPY) ;
  - `companionBeforeMainGivesSameGroup` (ordre inverse ⇒ même résultat) — Review Focus 1 ;
  - `interleavedSubfolderDoesNotSplitGroup` (`a/IMG_1.CR3`, `a/sub/z.jpg`, `folderDone(a/sub)`, `a/IMG_1.xmp`,
    `folderDone(a)`) — Review Focus 1 ;
  - `doubleExtension` (`IMG_1.CR3.xmp` ⇒ NAME `.xmp`) ;
  - `folderRuleTakesSubtree` (`page.html`, `page_files/a.png`, `page_files/img/b.png` avec les `folderDone` dans
    l'ordre de `walkFileTree` ⇒ un groupe, suffixes `_files/a.png`, `_files/img/b.png`) ;
  - `regexTakeout` (`IMG_1.jpg` + `IMG_1.jpg.supplemental-metadata.json` ⇒ READ, NAME) et `regexSony`
    (`C0099.MP4` + `C0099M01.XML` ⇒ READ, BASE, suffixe `M01.XML`) ;
  - `orphanCompanionIsOrdinary` (`x.xmp` seul, rôle READ ⇒ émis tel quel) ;
  - `ignoredCompanionIsDropped` ;
  - `oneLevelOnly` (un principal ne devient jamais compagnon d'un autre une fois rattaché) ;
  - `finishFlushesWithoutSignal` ;
  - `caseInsensitive` (`img_1.cr3` + `IMG_1.XMP`).
- [ ] **Step 2: Run** `-Dtest=GrouperTest` — Expected: FAIL.
- [ ] **Step 3: Implement** (le `Grouper` est appelé par un seul thread de listage : pas de verrou).
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** `Grouper: companion files attached to their main file, per folder`.

### Task 5: Gardes ZIP (chevauchement, taux de compression)

**Files:**
- Create: `copybot-engine/src/main/java/com/copybot/engine/listing/ZipLayout.java`,
  `copybot-engine/src/main/java/com/copybot/engine/listing/RatioGuardInputStream.java`
- Test: `copybot-engine/src/test/java/com/copybot/engine/listing/ZipGuardsTest.java` (ZIP construits dans le test,
  octets écrits à la main pour les cas piégés)

**Interfaces:**
- Produces:
  - `ZipLayout.checkNoOverlap(Path zip) throws IOException` : lit l'EOCD (et l'EOCD ZIP64 quand les champs valent
    `0xFFFF`/`0xFFFFFFFF`), parcourt le répertoire central, lit l'en-tête local de chaque entrée (30 octets + longueurs
    du nom et de l'extra) et lève `ZipBombException` (nouvelle, `extends IOException`) si deux plages
    `[offset en-tête local, fin des données compressées)` se chevauchent.
  - `new RatioGuardInputStream(InputStream in, long compressedSize, String entryName)` : lève `ZipBombException`
    dès que les octets lus dépassent `max(1 Mo, 100 × compressedSize)`.

- [ ] **Step 1: Failing tests** : ZIP ordinaire accepté ; ZIP64 (> 65 535 entrées vides, ou champs ZIP64 forcés)
  accepté ; deux entrées du répertoire central pointant le même en-tête local ⇒ refus ; entrée annonçant une petite
  taille mais produisant > 100× ⇒ `ZipBombException` en lecture ; entrée de 2 Mo d'octets nuls compressés (taux > 100,
  mais réel) ⇒ refus ; entrée de 900 Ko à fort taux ⇒ acceptée (plancher 1 Mo).
- [ ] **Step 2: Run** `-Dtest=ZipGuardsTest` — Expected: FAIL.
- [ ] **Step 3: Implement** avec `FileChannel` (lecture positionnelle), sans dépendance.
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** `Zip bomb guards: overlapping entries and compression ratio`.

### Task 6: `ArchiveUnfolder` et `ArchiveWorkspace`

**Files:**
- Create: `copybot-engine/src/main/java/com/copybot/engine/listing/ArchiveUnfolder.java`,
  `ArchiveWorkspace.java`, `ArchiveSources.java`, `ListingFailures.java` (interface)
- Test: `copybot-engine/src/test/java/com/copybot/engine/listing/ArchiveUnfolderTest.java`,
  `ArchiveWorkspaceTest.java`

**Interfaces:**
- Consumes: `ArchivesConfig` (tâche 3), `ZipLayout`, `RatioGuardInputStream` (tâche 5), `WorkItem.remote`,
  `SourceDeleter`, `ListingSink` (tâche 1).
- Produces:
  - `interface ListingFailures { void failed(WorkItem item, CopybotException cause); }`
  - `final class ArchiveWorkspace implements AutoCloseable` : `static ArchiveWorkspace open(Path base)` (crée
    `run-<id>/`, prend un verrou de fichier `run.lock`, supprime les `run-*` non verrouillés) ;
    `Path localCopy(WorkItem archive) throws IOException` (fichier local tel quel, sinon copie dans le dossier après
    vérification de la place libre) ; `close()` supprime le dossier. Défaut de `base` :
    `Path.of(System.getProperty("java.io.tmpdir"), "copybot-archives")`.
  - `final class ArchiveSources` : `void register(WorkItem archive, int entries)` ; l'effaceur d'une entrée appelle
    `release(archive)` ; quand toutes les entrées sont libérées, l'archive est supprimée (fichier local :
    `Files.deleteIfExists` ; sinon son propre `getSourceDeleter()` ; aucun ⇒ gardée) ;
    `List<String> keptWarnings()` : une ligne par archive avec `0 < libérées < entrées` (clé de bundle
    `archive.kept`, « archive conservée : N entrées non copiées »).
  - `final class ArchiveUnfolder implements ListingSink` : `ArchiveUnfolder(ArchivesConfig config, ArchiveWorkspace ws,
    ArchiveSources sources, ListingSink downstream, ListingFailures failures, int depth)` ; `item(i)` : si `.zip`
    (sans casse) et dépliage actif ⇒ déplie, sinon transmet ; `folderDone` transmis (avec `mergeFolder`, les entrées
    des archives d'un dossier sont émises sous `<dossier>!/…` et le `folderDone` de ce dossier virtuel suit celui du
    dossier réel) ; `finish()`.
- Entrées (spec § Entrées) : nom = dernier segment, dossier listé = `<emplacement archive>!/<dossier interne>` (sans
  `/` final ; racine ⇒ `<archive>!`), `sourceDisplay` = même chaîne, date = `ZipEntry.getLastModifiedTime()`
  (étendu UTC sinon DOS dans le fuseau système), taille = taille décompressée, contenu = `ZipFile.getInputStream`
  rouvert à chaque lecture sous `RatioGuardInputStream`, effaceur = `sources.release(archive)`. Entrées dossier
  (`a/`) ignorées ; noms vides, absolus, avec `..`, `\` ou `:` après normalisation ⇒ dernier segment seul, et
  l'entrée est refusée si ce segment est vide ou `..`. Filtre `include`/`exclude` (globs sans casse sur le chemin
  interne) appliqué avant émission ; les entrées exclues comptent dans `register` (archive gardée). Imbriquées :
  `nested` et `depth < 5` ⇒ un `ArchiveUnfolder` enfant (profondeur + 1) sur la copie locale de l'entrée ; sinon
  l'entrée est émise telle quelle (au-delà de 5 : avertissement). Échec (illisible, chiffrée, bombe, place) ⇒
  `failures.failed(archive, cause)` et rien n'est émis pour cette archive.

- [ ] **Step 1: Failing tests** : ZIP local de 3 entrées dans 2 dossiers ⇒ éléments, noms, dossiers listés,
  `folderDone` par dossier interne puis de l'archive ; dates étendues et DOS ; nom piégé `../../x.jpg` ⇒ `x.jpg`,
  dossier listé dans l'archive — Review Focus 4 ; entrée `a/` non émise ; archive corrompue, tronquée, chiffrée ⇒
  `failed`, rien d'émis, dossier de travail vide — Review Focus 2 ; `nested` faux ⇒ zip interne émis tel quel ;
  `nested` vrai ⇒ `a.zip!/x/b.zip!/y` ; profondeur 6 ⇒ émis tel quel ; `mergeFolder` : deux parties, chemins
  internes identiques ⇒ même dossier virtuel, `folderDone` après les deux ; source non locale (`WorkItem.remote`)
  copiée une fois dans le dossier de travail ; place insuffisante (simulée par une sonde injectable) ⇒ `failed` ;
  `ArchiveSources` : toutes libérées ⇒ archive supprimée, une non libérée ⇒ gardée + `keptWarnings` ;
  `ArchiveWorkspaceTest` : dossier verrouillé d'une autre instance gardé, dossier non verrouillé supprimé, `close`
  supprime tout.
- [ ] **Step 2: Run** `-Dtest=ArchiveUnfolderTest,ArchiveWorkspaceTest` — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** `Archive unfolding at the listing, with a run workspace and source tracking`.

### Task 7: Branchement dans `MainExecutor`

**Files:**
- Modify: `copybot-engine/src/main/java/com/copybot/engine/MainExecutor.java` (`runListing` :1402, `emitItem` :1435,
  début/fin de phase, `cancel`, avertissements comme `runPostProcess` :1274),
  `copybot-engine/src/main/java/com/copybot/engine/pipeline/PipelineState.java` (compteurs)
- Test: `copybot-engine/src/test/java/com/copybot/engine/MainExecutorGroupingTest.java`

**Interfaces:**
- Consumes: `Grouper` (4), `ArchiveUnfolder`, `ArchiveWorkspace`, `ArchiveSources`, `ListingFailures` (6),
  `GroupingPresets` (3).
- Produces:
  - `runListing` : sans `grouping` ni `archives.unfold` ⇒ `listFiles(this::emitItem)` comme aujourd'hui ; sinon
    `listFiles(sink)` avec `sink` = `ArchiveUnfolder` (si dépliage) → `Grouper` (si règles) → adaptateur vers
    `emitItem` ; `finish()` en fin de listage (y compris sur `PhaseStopped`, sans rien émettre de plus).
  - Une fois les étapes résolues : `PipelineChecks.groupingRefusal(config, findOutAction())` ; un refus fait échouer
    la préparation (ou l'exécution en une phase) avant tout listage, comme les refus existants.
  - `private void emitFailed(WorkItem item, CopybotException cause)` : crée l'exécution, `setError`, l'ajoute à
    l'état, notifie (pas de soumission).
  - `PipelineState` : `int getArchivesUnfolded()`, `int getArchivesToDownload()`, `long getBytesToDownload()`
    (incréments thread-safe) pour la préparation.
  - Durée de vie : un `ArchiveWorkspace` ouvert au premier listage qui en a besoin, fermé à la fin de la copie, à
    l'annulation, à l'échec de phase, et quand une nouvelle préparation remplace le plan ; `ArchiveSources.keptWarnings()`
    ajoutés aux avertissements en fin de copie (comme `runPostProcess`).
  - Mode continu (`stream`) avec regroupement et une entrée dont `signalsFolders()` est faux : avertissement d'état
    (clé `grouping.no-folder-signal`) et attente de la fin de son listage.

- [ ] **Step 1: Failing tests** (avec les doublures existantes, cf. `MainExecutorTest`, `ControlFakes`) : préparation
  d'un dossier `IMG_1.CR3 + IMG_1.xmp` ⇒ une seule exécution, compagnon attaché ; ZIP de 2 photos ⇒ 2 exécutions
  non locales, clés de reprise (date d'entrée, nom) ; ZIP corrompu ⇒ une exécution en ERROR, statut final ERROR, les
  autres fichiers traités ; point de reprise : un groupe dont la clé du principal est avant le curseur est reporté en
  entier ; annulation pendant le dépliage d'un gros ZIP ⇒ CANCELLED, dossier de travail supprimé — Review Focus 5 ;
  pipeline sans les blocs ⇒ comportement et ordre d'appels inchangés (tests existants verts).
- [ ] **Step 2: Run** `-Dtest=MainExecutorGroupingTest` — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `mvn -q verify -pl copybot-engine -am` — Expected: tout PASS.
- [ ] **Step 5: Commit** `Engine: listing pipe unfolding archives and grouping companions`.

### Task 8: Écriture des groupes dans `FileWriteAction`

**Files:**
- Modify: `copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileWriteAction.java` (`doWrite` :294,
  `deleteSource` :329, `checkTarget` :215), `ConflictResolver.java`
- Test: `copybot-engine/src/test/java/com/copybot/plugin/embedded/actions/FileWriteGroupTest.java`

**Interfaces:**
- Consumes: `Companion.targetName`, `getSourceDeleter` (1).
- Produces:
  - `FileWriteAction.writesCompanions()` ⇒ `true`.
  - `ConflictResolver.resolveGroup(WorkItem main, List<Companion> copies, Path target) -> GroupDecision` avec
    `record GroupDecision(Path mainTarget, List<Path> companionTargets, boolean replaceExisting, String skipReason,
    boolean identical, boolean sameFile, List<Integer> missingCompanions)` : pour chaque candidat (`numbered(target,n)`
    pour le principal, `targetName` du nom candidat pour chaque compagnon) — tous libres ⇒ écrire ; un membre est la
    source ⇒ sauter (même fichier) ; sinon le groupe est « identique » si chaque membre existant est identique
    (`FileComparison.identical`), et la politique `ifIdentical`/`ifDifferent` s'applique au groupe : SKIP, OVERWRITE
    (tous remplacés, absents écrits), ERROR, RENAME (candidat suivant pour **tous**). `missingCompanions` : indices des
    compagnons absents à écrire même quand le groupe est sauté **parce qu'identique**.
  - `deleteSource` : sources locales comme aujourd'hui ; élément non local avec effaceur ⇒ `deleter.delete()` puis
    `setDeleted(true)` ; pour un groupe, **après** l'écriture et la vérification de tous les membres COPY, principal
    puis compagnons ; groupe en erreur ⇒ rien.
  - `checkTarget` : le plus grave (`TargetCheck.mostSevere`) sur le principal et chaque compagnon COPY ; le message
    nomme le membre en cause.

- [ ] **Step 1: Failing tests** : groupe libre ⇒ 3 fichiers écrits (`IMG_1.CR3`, `IMG_1.JPG`, `IMG_1.xmp`) ; principal
  identique + xmp absent ⇒ SKIPPED, xmp écrit ; principal identique + xmp différent, `ifDifferent=rename` ⇒
  `IMG_1 (1).CR3` + `IMG_1 (1).xmp` ; même cas, `skip` ⇒ rien d'écrit ; `IMG_1 (1).xmp` déjà pris ⇒ `(2)` pour tout
  le groupe ; règle folder ⇒ `page_files/img/b.png` sous le nouveau nom ; principal transformé (cible `.mp4`) ⇒
  compagnon BASE sous le nom `.mp4` sans extension ; `deleteSource` : compagnon dont l'écriture échoue ⇒ aucune
  source supprimée, item en erreur — Review Focus 3 ; run suivant ⇒ principal identique, compagnon manquant écrit,
  sources supprimées (fullHash) ; entrée d'archive (non locale avec effaceur) ⇒ effaceur appelé une fois ; READ jamais
  écrit ni supprimé ; `checkTarget` : xmp différent ⇒ `DIFFERENT` avec le nom du xmp dans le message.
- [ ] **Step 2: Run** `-Dtest=FileWriteGroupTest` — Expected: FAIL.
- [ ] **Step 3: Implement** (le verrou par cible reste celui du principal).
- [ ] **Step 4: Run** `mvn -q verify -pl copybot-engine -am` — Expected: PASS.
- [ ] **Step 5: Commit** `file.write writes groups: solidary conflicts, companions after the main file`.

### Task 9: IHM du plan (compagnons, emplacements d'archive, compteurs)

**Files:**
- Modify: `copybot-ui/src/main/java/com/copybot/ui/PlanController.java` (colonnes :102-106), le FXML du plan,
  `copybot-ui/src/main/java/com/copybot/ui/model/PlanViewModel.java`, le détail d'un élément, bundles UI en/fr/it
- Test: `copybot-ui/src/test/java/com/copybot/ui/model/PlanViewModelTest.java` (et `UiBundleTest`)

**Interfaces:**
- Consumes: `WorkItem.getCompanions()`, `PipelineState.getArchivesUnfolded()/getArchivesToDownload()/getBytesToDownload()`.
- Produces : colonne « Compagnons » (nombre, vide si aucun ; infobulle : nom et rôle de chacun) ; détail de
  l'élément : liste des compagnons avec rôle et cible (`targetName` sur la cible projetée du principal) ; les entrées
  d'archive affichent `sourceDisplay` (`a.zip!/DCIM`) dans la colonne d'emplacement ; bandeau/compteur de préparation
  « archives dépliées : N — à télécharger : M (X Go) » quand N > 0 ; l'aperçu ne s'ouvre pas sur un compagnon.

- [ ] **Step 1: Failing tests** : `PlanViewModel` expose le texte de la colonne (`"2"`), l'infobulle
  (`"IMG_1.JPG (copier)\nIMG_1.xmp (copier)"`), le texte du compteur d'archives ; parité des clés.
- [ ] **Step 2: Run** `mvn -q verify -pl copybot-ui -am -Dtest=PlanViewModelTest,UiBundleTest` — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** `Plan: companions column and detail, archive locations and counters`.

### Task 10: IHM de l'éditeur (Regroupements, Archives, avertissements)

**Files:**
- Modify: `copybot-ui/src/main/java/com/copybot/ui/model/PipelineDocument.java` (comme `postProcess` :441-480),
  `copybot-ui/src/main/java/com/copybot/ui/EditorController.java` (arbre :349, formulaires comme `postProcessForm`
  :579), bundles UI en/fr/it
- Test: `copybot-ui/src/test/java/com/copybot/ui/model/PipelineDocumentTest.java`, `UiBundleTest`

**Interfaces:**
- Consumes: `GroupingRuleConfig`, `GroupingPresets` (3), `ArchivesConfig` (3).
- Produces:
  - `PipelineDocument` : `List<JsonObject> groupingRules()`, `addGroupingRule(JsonObject)`,
    `removeGroupingRule(int)`, `moveGroupingRule(int from, int to)` (bloc retiré quand la liste est vide) ;
    `ArchivesConfig archives()`, `setArchives(ArchivesConfig)` (bloc retiré quand `unfold` est faux) ;
    `List<String> companionExtensionsExcludedByFileRead()` (extensions visées par une règle et exclues par les
    `include`/`exclude` d'une entrée `file.read`) ; `void allowCompanionExtensions()` (les ajoute aux `include` non
    vides, retire les `exclude` qui les visent).
  - Éditeur : nœuds « Regroupements » et « Archives » après les entrées. Regroupements : liste des règles (libellé
    lisible), boutons « Ajouter un préréglage » (menu des 6), « Ajouter une règle » (type puis champs : extensions
    principales, extension → rôle, dossier, expressions, lien), Supprimer, Monter, Descendre. Archives : cases
    déplier / imbriquées (« jusqu'à 5 niveaux ») / fusionner le dossier, `include`/`exclude` (une ligne par motif).
  - Avertissements : extensions exclues par `file.read` (+ bouton « ajouter les extensions des compagnons » ⇒
    `allowCompanionExtensions`) ; règle `folder` avec une entrée `file.read` non récursive ; exécution `streaming`
    avec une entrée qui ne signale pas ses dossiers.

- [ ] **Step 1: Failing tests** : ajout/suppression/déplacement de règles et retrait du bloc vide ; archives
  aller-retour JSON ; `companionExtensionsExcludedByFileRead` avec `include: ["*.jpg"]` et une règle xmp ⇒ `["xmp"]` ;
  `allowCompanionExtensions` ⇒ `include: ["*.jpg", "*.xmp"]` ; parité des clés.
- [ ] **Step 2: Run** `mvn -q verify -pl copybot-ui -am -Dtest=PipelineDocumentTest,UiBundleTest` — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** `Editor: grouping rules and archive options, with their warnings`.

### Task 11: Documentation et vérification complète

**Files:**
- Modify: `README.md` (blocs `grouping` et `archives`, préréglages, limites : HTML renommé, noms Takeout tronqués,
  ZIP seulement)

- [ ] **Step 1:** écrire la section README (exemple JSON de la spec).
- [ ] **Step 2: Run** `mvn -q verify` à la racine — Expected: BUILD SUCCESS, aucun test en échec.
- [ ] **Step 3: Commit** `README: grouping of companion files and unfolding of archives`.

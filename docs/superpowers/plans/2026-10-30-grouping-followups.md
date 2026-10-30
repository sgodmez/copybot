# Suites des regroupements et archives — plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan
> task-by-task.

**Goal:** quatre suites demandées par Steven le 2026-10-30 après la livraison des regroupements et archives (cfa2769) :
retirer Takeout du cœur, découper `MainExecutor`, abandonner un plan côté moteur, traduire le moteur en italien.

**Spec:** `docs/superpowers/specs/2026-10-30-grouping-archives-design.md` (fonction d'origine) ; décisions de Steven
ci-dessous.

## Global Constraints

- JDK 25 ; `mvn -q verify` à la racine en fin de plan ; un module : `mvn -q verify -pl <module> -am`.
- API des plugins : seule la tâche 4 l'étend (`ListingSink.root`, ajout, version 1.2 inchangée car non publiée) ;
  `PluginApiFingerprintTest` vert.
- Fichiers Java en LF (garder CRLF si le fichier l'est déjà) ; bundles `.properties` en ASCII avec `\uXXXX`.
- Comportement inchangé pour un pipeline sans `grouping` ni `archives`.
- Un commit par tâche, message en anglais.

---

### Task 1: Takeout hors du cœur

Décision de Steven : Takeout est un futur plugin dédié, pas une fonction native.

**Files:** `copybot-engine/.../engine/listing/GroupingPresets.java`, `MainExecutor.java` (calcul de mergeFolder
effectif), `README.md`, la spec (section Préréglages : noter le retrait), tests (`GroupingConfigTest`,
`MainExecutorGroupingTest`, `GrouperTest` si un test utilise le préréglage), éditeur si un libellé `takeout` existe
(bundles UI en/fr/it).

- Retirer le préréglage `takeout` et `GroupingPresets.impliesMergeFolder` ; `names()` renvoie les 5 autres ;
  `mergeFolder` effectif = `archives.isMergeFolder()` seul. Un pipeline qui cite encore `"preset": "takeout"` est
  refusé par la validation existante (préréglage inconnu) : vérifier le message.
- L'option `mergeFolder` reste (générique : archives découpées en parties).
- Tests mis à jour ; un test vérifie que `takeout` est désormais refusé.
- README : retirer Takeout des préréglages ; une ligne « un plugin Takeout est prévu ».

### Task 2: Extraire la vie des archives et le tuyau de listage de `MainExecutor`

Refactorisation **sans changement de comportement** (~1800 lignes aujourd'hui).

**Files:** créer `copybot-engine/.../engine/RunArchives.java` (package-private : verrou, `ArchiveWorkspace`,
`ArchiveSources`, `archivesReleased`, `ArchiveProgress` vers `PipelineState`, ouverture/réinitialisation/libération,
config de dépliage effective, avertissements) et `ListingPipe.java` (package-private : construction du tuyau
dépliage → regroupement → `emitItem`, `finish`, avertissement `grouping.no-folder-signal`) ; modifier
`MainExecutor.java` et `CopybotEngine.java` en conséquence.

- Mêmes appels, même ordre, mêmes messages : aucun test existant ne change (sauf imports/visibilité si un test
  appelait une méthode déplacée).
- Corriger au passage, dans la classe extraite : la libération des archives a lieu **après** l'arrêt des tâches de la
  phase (`endPhase`), plus avant ; `CopybotEngine.close()` ne libère pas les archives d'une opération encore en cours
  (pas idle) ; le dossier de travail s'ouvre à la première archive rencontrée, plus à chaque listage avec dépliage.
  Un test pour chacun.
- Critère : `MainExecutor` perd au moins 200 lignes ; suite moteur verte.

### Task 3: Abandonner un plan côté moteur

Constat : l'IHM abandonne déjà un plan (`PlanOperations.dropPlan`, pipeline relu) mais n'en dit rien au moteur, qui
garde le dossier de travail des archives jusqu'à la préparation suivante.

**Files:** `copybot-engine/.../engine/Plan.java` (ou `CopybotEngine`), `RunArchives` (tâche 2),
`copybot-ui/.../model/PlanOperations.java`, tous les endroits de l'IHM qui oublient un plan préparé (fermeture de la
fenêtre du plan, changement de pipeline… : les trouver), tests moteur et IHM.

- `Plan.discard()` (public, idempotent, sans effet pendant une exécution de ce plan) : libère les ressources du plan
  (dossier de travail des archives) ; le plan devient inexécutable : `execute` sur un plan abandonné — ou remplacé par
  une préparation plus récente — est refusé avec un message clair (bundle moteur en + fr, + it à la tâche 6).
- `PlanOperations.dropPlan` et les autres abandons de l'IHM appellent `discard()` sur le plan oublié.
- Tests : discard libère le dossier ; execute après discard refusé ; execute d'un plan remplacé refusé ; dropPlan
  appelle discard (test IHM sans FX si possible).

### Task 4: Racine du listage pour la règle « dossier associé »

Décision de Steven : une source située sous un dossier `*_files` ne doit pas faire attendre le regroupement.

**Files:** `copybot-engine/.../plugin/api/action/ListingSink.java` (ajout), `FileReadAction.java`,
`engine/listing/Grouper.java`, `ArchiveUnfolder.java` (transmet le signal), `plugin-api.txt` (régénéré, ajouts
seulement, version 1.2 inchangée : pas encore publiée), tests `GrouperTest`, `FileReadActionTest`.

- `ListingSink` : `default void root(String folder) {}` — envoyé au plus une fois, avant tout élément, par une source
  qui connaît la racine de son listage ; même forme de chaîne que `getListedFolder()`.
- `FileReadAction` l'envoie (`root.toString()`).
- `Grouper` : avec une racine connue, seuls les dossiers `*_files` strictement **sous** la racine peuvent être
  retenus pour la règle `folder` (la racine elle-même et ses ancêtres, jamais : leur page sœur serait hors du
  listage) ; plus de rétention provisoire : une source sous `D:\Mes_files\Photos` est émise dossier par dossier.
  Sans racine : comportement actuel inchangé.
- Tests : source sous un ancêtre `*_files` émise à chaque `folderDone` (pas seulement à `finish`) ; règle `folder`
  inchangée à l'intérieur de la racine ; source sans `root` : comportement actuel.

### Task 5: Mode continu et regroupements : avertir et se replier

Décision de Steven : le regroupement et le mode continu sont contradictoires ; prévenir et se replier.

**Files:** `MainExecutor` / `ListingPipe` (tâche 2), `PipelineState` si besoin, bundles moteur (en + fr ; it à la
tâche 6), `PipelineDocument` + bundles UI en/fr/it (avertissement de l'éditeur), README, tests.

- Exécution en continu avec des règles de regroupement :
  - toutes les entrées signalent leurs dossiers : on reste en continu, avertissement d'information
    « avec les regroupements, le mode continu traite les fichiers dossier par dossier » (moteur et éditeur) ;
  - au moins une entrée ne signale pas ses dossiers : l'exécution se **replie en mode automatique** (préparer puis
    copier sans confirmation, comme `execution: auto`), avec un avertissement qui le dit
    (« mode continu impossible avec les regroupements pour l'entrée X : exécution en mode automatique »). Remplace
    l'actuel `grouping.no-folder-signal`.
- Éditeur : l'avertissement du mode continu reprend ces deux textes (entrée non `file.read` ⇒ texte du repli).
- Tests : continu + `file.read` + règles ⇒ continu, avertissement d'information ; continu + entrée sans signal +
  règles ⇒ déroulé auto (toutes les entrées listées avant la première copie) + avertissement de repli.

### Task 6: Messages du moteur en italien

Constat : `engineBundle` n'existe qu'en anglais et en français ; l'IHM est en en/fr/it, donc un utilisateur italien
voit les erreurs et avertissements du moteur en anglais.

**Files:** créer `copybot-engine/src/main/resources/com/copybot/engine/i18n/engineBundle_it.properties` ; un test de
parité des clés en/fr/it du moteur (comme `UiBundleTest`), s'il n'existe pas.

- Toutes les clés de `engineBundle.properties` (y compris celles des tâches 1-5), traduites en italien (registre et
  vocabulaire de `uiBundle_it`), mêmes `{0}`, apostrophes doublées comme dans les autres bundles `MessageFormat`.
- ASCII + `\uXXXX`.
- Le test de parité échoue si une clé manque dans une langue.

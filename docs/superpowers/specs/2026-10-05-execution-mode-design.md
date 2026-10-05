# Mode d'exécution d'un pipeline : plan, auto, streaming

Date : 2026-10-05
Statut : validé (décidé avec Steven)

## Contexte

Deux réglages se recouvraient : `startProcessingWhileListing` (pipeline, traiter pendant le listing, sans reprise)
et `ui.autoExecute` (copier dès la fin de la préparation). Ils sont remplacés par **un seul** champ du pipeline,
`"execution"`, à trois valeurs. Pas de compatibilité ascendante : les anciens champs sont retirés de
`PipelineConfig`, du document de l'éditeur et des bundles ; Gson ignore un ancien champ resté dans un fichier.

Le streaming avec une reprise par la destination a conduit à deux options du bloc de reprise
(`destinationCheck`, `destinationMatch`, §2).

## 1. Le champ `execution`

| Valeur | Sens | Vue plan |
|---|---|---|
| `plan` (défaut, champ absent) | plan puis confirmation | « Préparer le plan », le plan (point de reprise modifiable, ignorer / analyser), puis « Copier n fichiers ». Case « Exécution automatique » : bascule ponctuelle de la vue (non enregistrée, décochée par défaut) |
| `auto` | plan puis copie, en une étape | un seul bouton « Préparer et copier » : préparation puis exécution enchaînées, sans case à cocher |
| `streaming` | traitement pendant le listing | un seul bouton « Lancer la copie au fil de l'eau » : chaque fichier passe analyse → traitements → copie dès qu'il est listé, sans case à cocher |

- `ExecutionMode` (moteur, `com.copybot.engine.pipeline`) : `PLAN`, `AUTO`, `STREAMING` (`@SerializedName`
  `plan`, `auto`, `streaming`) ; `PipelineConfig.executionMode()` donne `PLAN` quand le champ est absent.
- Une valeur inconnue (ou non textuelle) est refusée à la lecture du pipeline : `execution.unknown`, comme
  `resume.mode.unknown` (Gson la lirait `null`, donc silencieusement `plan`).
- Dans tous les modes la vue suit l'exécution en temps réel (notifications du watcher) ; Pause / Arrêt disponibles
  pendant l'exécution.

## 2. Reprise par la destination : `destinationCheck` et `destinationMatch`

Options du bloc de reprise, utiles aux modes fondés sur la destination (`destination`, et `stateThenDestination`
quand il se rabat sur la destination faute de curseur) :

```json
"resume": { "mode": "destination", "destinationCheck": "dichotomy", "destinationMatch": "directory" }
```

### `destinationCheck` : comment la destination est parcourue

- `dichotomy` (défaut) : le comportement d'aujourd'hui (la destination est supposée remplie jusqu'à un point,
  dichotomie paresseuse : seuls les fichiers sondés sont analysés, puis ceux que le point sélectionne).
- `everyFile` : chaque fichier est analysé et décidé par **sa** cible (pas de dichotomie, pas d'hypothèse
  « remplie jusqu'à un point » : un jour manquant au milieu est réimporté). Valable en deux phases (`plan`,
  `auto` : la préparation analyse tout, puis chaque fichier est décidé) comme en streaming.
  - Nouveau genre de point `ResumePoint.Kind.NOT_AT_DESTINATION` (sans clé ; `selects` vrai pour toute clé) :
    `ResumeResolver.apply` saute (SKIPPED par le point de reprise, raison `resume.skip.at-destination` avec le
    chemin vérifié) les fichiers trouvés à la destination, mémorisés par le resolver ; les autres sont à copier.
    Source DESTINATION ; aucun fichier présent → tout (source NONE). Un point manuel le remplace comme les autres.
  - Curseur écrit à la fin comme aujourd'hui (`nextCursor` : un fichier sauté compte comme importé).

### `destinationMatch` : ce qui compte comme importé

- `directory` (défaut, aujourd'hui) : le **dossier** cible existe (supprimer des photos dedans ne les fait pas
  réimporter).
- `file` : le **fichier** cible existe. La dichotomie sonde l'existence des fichiers (le prédicat de
  `DestinationProbe`) ; le garde-fou du dossier unique ne s'applique pas (un dossier fixe va très bien : chaque
  fichier est vérifié).

### Garde-fou du dossier fixe (`directory` seulement)

Un motif dont la partie dossier n'a pas de variable (ex. `out/{name}`) sauterait tout pour toujours une fois le
dossier créé : tout est alors sélectionné avec l'avertissement `resume.warn.single-directory`.

- `dichotomy` : inchangé (première et dernière cibles dans le même dossier).
- `everyFile` : l'action de sortie le dit si elle le sait (`IOutAction.targetDirectoryVaries()`, défaut vide ;
  `file.write` : `OutPattern.directoryVaries()`, une expression avant le dernier séparateur). Si elle ne le sait
  pas : en deux phases, au moins deux cibles toutes dans le même dossier ; en streaming, un fichier n'est sauté
  qu'une fois vus au moins deux dossiers cibles différents (dans le doute on copie : la politique de conflit de la
  sortie évite les doublons).

Valeurs inconnues refusées à la lecture (`resume.destination-check.unknown`, `resume.destination-match.unknown`).

## 3. Moteur : la phase streaming

`MainExecutor.stream()` : une seule phase, toutes les étapes, chaque élément soumis dès qu'il est listé (pas
d'attente de la fin du listing). `run()` sans reprise garde la phase unique d'aujourd'hui, mais **attend la fin du
listing** avant de traiter (l'ancien défaut) : seul `stream()` traite pendant le listing. Le streaming ne se replie
jamais sur une préparation.

### Reprise

`ResumeResolver.streamingProposal()` donne le point avant le listing :

- pas de reprise (pas de bloc, ou mode `none`) : tout ;
- `state` : après le curseur ; tout sans curseur (source NONE) ;
- `stateThenDestination` avec un curseur : après le curseur ;
- `destination`, et `stateThenDestination` sans curseur :
  - sans action de sortie : comme la préparation (`destination` échoue avec `resume.error.no-target`,
    `stateThenDestination` sélectionne tout avec `resume.warn.no-target`) ;
  - sinon `NOT_AT_DESTINATION` : chaque fichier est analysé dès qu'il est listé, sauté si sa cible (dossier ou
    fichier selon `destinationMatch`) existe, sinon traité et copié aussitôt. Avec `dichotomy`, impossible en
    streaming (le point dépend de l'ensemble des fichiers) : on vérifie chaque fichier, avec l'avertissement
    `execution.streaming.dichotomy`.

Dans tous les cas :

- la proposition (point, source) est publiée dans l'état dès le début (`setResumeProposal`) : la vue montre la
  bannière de reprise ; les avertissements vont dans les avertissements de l'état (vue plan, stderr du CLI) ;
- la clé de reprise de chaque fichier est figée **au listing** (date de modification), avant tout traitement (une
  étape peut remplacer l'item) ;
- un fichier que le point (après le curseur) ne sélectionne pas est sauté au listing, sans analyse
  (`deferAnalysis`, comme la préparation) ; un fichier sans date est en erreur (`resume.item.no-date`, comme
  `ResumeResolver.apply`) ;
- à la fin, comme `execute()` : point de non-retour, statut final, puis curseur écrit avec
  `ResumeResolver.nextCursor` sur les fichiers listés (pas les fourches) triés par clé (clés déjà figées). Pas
  d'écriture si le run est annulé ou si **le listing a échoué** (un listing partiel pourrait faire sauter des
  fichiers non listés au prochain run). Un échec d'écriture du curseur met le run en ERROR (comme `execute()`).
  Un fichier d'état illisible fait échouer le run comme une préparation (`isPreparationFailed`).

### `CopybotEngine.run`

Lit le mode du pipeline : `streaming` → `stream()` ; sinon comme aujourd'hui (phase unique sans reprise,
préparation puis exécution avec reprise).

## 4. CLI

- `streaming` sans option → `engine.run` donc la phase streaming (avertissements sur stderr).
- `plan` / `auto` → comme aujourd'hui (`engine.run`) : ne traite jamais pendant le listing.
- `--dry-run`, `--all`, `--from-file`, `--from-date` : préparation puis exécution (ou aperçu), quel que soit le
  mode : un point manuel ou un aperçu exige le plan.

## 5. Interface

### Éditeur (formulaire « Pipeline »)

- Les cases « Traiter pendant le listing » et « Exécution automatique » sont remplacées par une liste
  « Exécution » à trois choix. `PipelineDocument.executionMode()` / `setExecutionMode()` : `plan` retire le champ
  (défaut), les autres l'écrivent.
- Listes « Vérification de la destination » (dichotomie / chaque fichier) et « Déjà importé quand » (le dossier
  cible existe / le fichier cible existe), actives seulement pour `destination` et `stateThenDestination`.
  `PipelineDocument.destinationCheck()` / `destinationMatch()` et leurs setters : la valeur par défaut retire le
  membre.
- Avertissement sous la liste d'exécution quand `streaming` est combiné à une reprise par la destination en
  dichotomie (chaque fichier sera vérifié).

### Vue plan

- `PlanViewModel` : `setExecutionMode`, `prepareLabel()` (« Préparer le plan » / « Préparer et copier » /
  « Lancer la copie au fil de l'eau »), `showsAutoExecuteBox()` (mode `plan`, hors exécution) ;
  `consumeAutoExecute()` vrai aussi en mode `auto` ; la case n'est plus lue du fichier.
- Mode `streaming` : le bouton lance `engine.run` (exécution directe, sans plan) ; `startStreaming()` met la vue
  en exécution d'emblée. La progression porte sur les fichiers listés que le point de reprise ne saute pas (le
  total grandit pendant le listing, barre indéterminée tant que le listing dure). Un échec de préparation (plugin
  manquant, fichier d'état illisible) affiche l'échec de préparation. Les cibles ne sont pas affichées (pas de plan
  ni de dry run).
- La bannière de reprise d'un point `NOT_AT_DESTINATION` dit « les fichiers absents de la destination »
  (`plan.resume.missing`) ; le CLI de même (`cli.plan.resume.missing`).

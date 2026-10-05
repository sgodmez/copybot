# Point de reprise choisi : un curseur à cet endroit

Date : 2026-10-05
Statut : validé (design)

## Contexte

Un point de reprise manuel ne s'applique aujourd'hui qu'à un plan **préparé** (`Plan.preview`, « changer… »,
« Reprendre d'ici ») : la préparation a déjà listé, analysé et sondé la destination selon le mode du pipeline,
puis le point recalcule les statuts (et l'analyse différée rattrape les fichiers resélectionnés). Deux cas n'ont
pas de réponse :

- choisir le point **avant** de préparer : on paie l'analyse (et en mode destination la dichotomie) de fichiers
  que le point va écarter ;
- une préparation **arrêtée** (`PREPARE_STOPPED`, commit 7c67966) : le plan partiel est visible, mais on ne peut
  rien en faire, il faut tout relister.

But : un point choisi se comporte **comme un curseur à cet endroit** : les fichiers d'avant sont écartés sans
analyse (« avant le point choisi »), ceux d'après sont analysés et vérifiés (conflits), sans sonde de la
destination. Les trois entrées convergent vers ce même chemin : le choix avant la préparation, « Reprendre d'ici »
sur une préparation arrêtée, et le choix d'un point sur une préparation arrêtée.

Non-régression : sans point choisi, la préparation, l'exécution, le streaming et le CLI sont strictement
identiques à aujourd'hui.

## 1. Préparer avec un point choisi

### Moteur

- `MainExecutor.chooseResumePoint(ResumePoint)` (avant `prepare()` / `stream()`, null = automatique).
- `prepare()` avec un point choisi :
  - `listingPoint` = le point (ALL compris : il ne diffère rien, les clés sont figées au listing), source
    **MANUAL** ; `analysisOnDemand` = false (pas de sonde) ;
  - au listing, un fichier que le point ne sélectionne pas est différé (`deferAnalysis`) avec la raison
    `resume.skip.manual` (« avant le point choisi », ou `resume.skip.manual-date` pour une date seule) ; les
    autres sont analysés au fil du listing, vérifiés (conflict-check) dès leur analyse ;
  - fin de préparation : proposition `(point, MANUAL)`, **sans** `resolver.propose` (ni curseur appliqué, ni
    sonde) ; `resolver.apply` puis `projectItems` comme aujourd'hui.
  - le curseur d'état est **lu** quand même (modes autres que `none`, `ResumeResolver.readCursor()`) : la règle
    « le curseur ne recule jamais » de `nextCursor` en dépend. Un fichier d'état illisible reste une erreur de
    préparation, comme aujourd'hui.
- `CopybotEngine.prepare(Path, watcher, started, ResumePoint chosen)` ; les surcharges existantes passent null.
- L'exécution d'un tel plan : `execute(plan, null)` prend la proposition (`MANUAL`) ; le curseur enregistré est
  le max du curseur précédent et de la dernière réussite continue (`nextCursor`, inchangé : pas de point
  automatique pour une source MANUAL).

### Streaming

Le point choisi est pris en compte (coût faible, même sémantique) : `stream()` avec un point choisi remplace
`streamingProposal` par `(point, MANUAL)`, `listingPoint` = le point (FROM accepté en plus d'AFTER), pas de
vérification fichier par fichier de la destination. `CopybotEngine.run(Path, watcher, ResumePoint chosen)` :
avec un point choisi, le contexte de reprise est construit même pour un pipeline sans bloc `resume` (mode
`none` : le point filtre, aucun curseur n'est écrit).

### CLI

- `--from-date` et `--all` sont connus avant le listing : ils passent désormais par ce chemin
  (`prepare(..., chosen)`) : les fichiers écartés ne sont plus analysés puis rattrapés (`preview` +
  `analyse`), le dry run et l'exécution prennent la proposition. Simplification et gain de temps ; la sortie
  est la même (même statut, mêmes fichiers COPY/SKIP, raison « avant le point choisi »).
- `--from-file` désigne un **nom** de fichier : il faut le listing pour trouver sa clé (date de
  modification). Il garde le chemin actuel (préparation automatique, puis `Plan.fromFile`, `preview`,
  `analyse`).

## 2. Continuer une préparation arrêtée

### Listing complet

- `PipelineState.isListingComplete()` : vrai quand **toutes** les étapes IN ont fini de lister normalement
  (ni échec, ni arrêt, ni `PhaseStopped` levé vers le plugin). Remis à faux au début de chaque
  préparation. Un plugin qui avalerait `PhaseStopped` et finirait normalement est quand même marqué incomplet
  (le drapeau est posé par `emitItem`).
- Une préparation arrêtée **pendant** le listing (listing incomplet) ne se continue pas : des fichiers non
  listés seraient omis sans le dire, et le curseur passerait ensuite au-delà d'eux.

### `MainExecutor.continuePreparation(ResumePoint point)`

Préconditions, vérifiées sous `phaseLock` (sinon `IllegalStateException` `engine.not-continuable`) : statut
CANCELLED, la dernière phase était une préparation (`prepare()` ou une continuation, pas une exécution), listing
complet.

Machine d'états :

```
prepare() ──arrêt (cancelPreparation)──▶ CANCELLED (préparation arrêtée)
    │                                         │  listing complet ?
    ▼                                         ├─ non : terminal (on prépare à nouveau)
 PREPARED                                     └─ oui : continuePreparation(point) ─▶ RUNNING
                                                        │                │
                                                        ▼                ▼ arrêt
                                                    PREPARED          CANCELLED (continuable à nouveau)
                                              (échec : ERROR, terminal)
```

- Démarrage, dans un même bloc `phaseLock` : préconditions, `cancelRequested` et `cancelInterruptSent` remis à
  faux, `preparationStoppable` vrai, statut RUNNING (PAUSED si une pause est en cours). Un `cancel()` arrivé
  avant que la phase ait un thread est donc vu au premier `checkCancelled()` (comme `prepare()`), et un
  `cancel()` sur le plan arrêté avant la continuation reste sans effet (statut terminal).
- Travail, sur le phase thread, avec le registre, les tickets et le watcher de la préparation :
  1. clés figées (`ResumeResolver.order`) ;
  2. tout fichier PENDING jamais analysé (interrompu par l'arrêt, ou différé par la sonde) est marqué
     « analyse différée » ;
  3. `resolver.apply(point, MANUAL, …)` ; les fichiers SKIPPED encore différés sont marqués préparés
     (`deferAnalysis`, raison « avant le point choisi ») ;
  4. les fichiers PENDING différés sont analysés (barrière, projection, vérification de cible) ; ceux déjà
     analysés gardent leur cible et leur vérification ;
  5. `projectItems()`, puis point de non-retour (`checkCancelled` sous `phaseLock`, `preparationStoppable`
     faux) et statut PREPARED, proposition `(point, MANUAL)`.
- Arrêt (`cancelPreparation`, `close()`) pendant la continuation : CANCELLED, de nouveau continuable (le
  listing reste complet). Échec : ERROR (`preparationFailed`), terminal.
- Le curseur d'état est lu (comme au §1) pour `nextCursor`.

### `CopybotEngine.continuePreparation(Plan, ResumePoint)` et `Plan`

- Bloquant, dans le thread appelant, opération active (une à la fois, `engine.busy` sinon ; `close()`
  l'annule) ; PREPARED → le plan redevient le `preparedPlan` de l'engine (comme `prepare`), sinon la pause est
  levée.
- `Plan.canContinue()` : statut CANCELLED d'une préparation au listing complet.
- `Plan.fromFile(Path)` (statique) : FROM la clé d'un fichier choisi sur disque (date de modification à la
  seconde, nom), la même que celle que le listing lui donnera.

### « Analyser » sur une préparation arrêtée

Disponible exactement quand la continuation l'est. Les fichiers choisis (pas encore analysés) sont analysés
comme une analyse différée : `Plan.requestAnalysis(items)` puis `CopybotEngine.analyse(plan)`, phase RUNNING
puis **retour à CANCELLED** (le plan reste une préparation arrêtée, continuable) ; un fichier écarté par le
point de listing reste écarté. But : voir la cible et la vérification de fichiers choisis avant de décider d'où
reprendre. Arrêter cette analyse (Arrêter) : CANCELLED aussi, les fichiers non faits restent « non analysés ».

## 3. Interface (vue plan)

- Ligne de reprise visible aussi **avant** la préparation (NOT_PREPARED, PREPARE_FAILED, PREPARE_STOPPED,
  PREPARING) : « Reprise : automatique » ou « Reprise : à partir de X (choisi) », avec « changer… ».
- « changer… » ouvre le même dialogue, avec en plus « Automatique (selon le pipeline) » :
  - plan préparé : inchangé (`preview`, analyse des resélectionnés) ;
  - préparation arrêtée au listing complet : le point choisi **continue** la préparation (comme « Reprendre
    d'ici ») ; « automatique » ne fait que revenir au choix automatique pour la prochaine préparation ;
  - sinon : le point est **retenu** pour la prochaine préparation (Préparer, Préparer et copier, Copier au fil
    du listing). Il reste choisi jusqu'à ce qu'on revienne à « automatique ».
  - « à partir d'un fichier » : les fichiers listés quand il y en a, sinon « Choisir un fichier… » (sélecteur de
    fichier, `Plan.fromFile(Path)`).
- Préparation arrêtée, listing complet : le menu des lignes propose « Reprendre d'ici » et « Analyser » (les
  fichiers non analysés de la sélection). Pendant la continuation : phase PREPARING (lignes conservées, barre
  de préparation, Arrêter actif) ; mode auto / exécution automatique : la copie part à la fin, comme après
  Préparer.
- Préparation arrêtée pendant le listing : ces deux entrées sont désactivées et la ligne d'état le dit :
  « Préparation arrêtée pendant le listage : N fichiers listés. Les fichiers non listés seraient omis :
  préparez à nouveau. »

## 4. Tests

- Moteur : préparation avec point choisi (fichiers écartés non analysés, raison manuelle, aucune sonde en mode
  destination, proposition MANUAL, curseur lu et jamais reculé, ALL) ; streaming avec point choisi ;
  `isListingComplete` (listing complet, arrêt pendant le listing, échec de listing) ; continuation (refus
  listing incomplet / non arrêté / exécution, statuts finaux, fichiers déjà analysés gardés, nouvel arrêt puis
  nouvelle continuation, `cancel()` concurrent, engine occupé, `close()`), analyse sur plan arrêté (retour
  CANCELLED). CLI : `--from-date` sans analyse des fichiers écartés, sortie inchangée.
- IHM : ligne de reprise avant préparation, point retenu, entrées du menu selon le listing, phase pendant la
  continuation, statuts.

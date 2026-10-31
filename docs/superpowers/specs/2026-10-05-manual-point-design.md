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
sur une préparation arrêtée, et le choix d'un point sur une préparation arrêtée ; les deux dernières ne font que
choisir le point de la prochaine préparation (§2).

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

## 2. Une préparation arrêtée

Une préparation arrêtée **n'est jamais reprise d'elle-même** : l'utilisateur a arrêté l'analyse, rien ne la
relance sans qu'il le demande. Repartir de zéro est acceptable : « Préparer » reliste la source (rapide : noms
et dates) puis n'analyse que les fichiers à partir du point choisi (§1), ce qui revient à un curseur posé là.
Une première version continuait la préparation arrêtée sans relister (`continuePreparation`) ; elle a été
retirée : une machine d'états délicate (arrêts concurrents, `close()` pendant la reprise) pour un gain que le
listing rapide rend inutile.

### Listing complet

- `PipelineState.isListingComplete()` : vrai quand **toutes** les étapes IN ont fini de lister normalement
  (ni échec, ni arrêt, ni `PhaseStopped` levé vers le plugin). Remis à faux au début de chaque
  préparation. Un plugin qui avalerait `PhaseStopped` et finirait normalement est quand même marqué incomplet
  (le drapeau est posé par `emitItem`).
- `Plan.isStoppedAfterTheListing()` : statut CANCELLED, la dernière phase était une préparation (pas une
  exécution), listing complet. Ses lignes sont alors fiables : on peut y choisir un point et en analyser.
- Une préparation arrêtée **pendant** le listing n'offre pas ses lignes : des fichiers non listés seraient
  omis sans le dire, et le curseur passerait ensuite au-delà d'eux.

### « Reprendre d'ici » et « changer… » sur une préparation arrêtée

Ils ne font que **choisir le point** de la prochaine préparation (§1), affiché sur la ligne de reprise. Le plan
reste arrêté, non copiable ; « Préparer » repart de zéro depuis ce point.

### « Analyser » sur une préparation arrêtée

Disponible exactement quand `isStoppedAfterTheListing()`. Les fichiers choisis (pas encore analysés) sont
analysés comme une analyse différée : `Plan.requestAnalysis(items)` puis `CopybotEngine.analyse(plan)`, phase
RUNNING puis **retour à CANCELLED** (le plan reste une préparation arrêtée) ; un fichier écarté par le point
de listing reste écarté. But : voir la cible et la vérification de fichiers choisis avant de décider d'où
reprendre. Arrêter cette analyse (Arrêter) : CANCELLED aussi, les fichiers non faits restent « non analysés ».

### `Plan.fromFile(Path)`

Statique : FROM la clé d'un fichier choisi sur disque (date de modification à la seconde, nom), la même que
celle que le listing lui donnera, pour choisir un fichier avant tout listing.

## 3. Interface (vue plan)

- Ligne de reprise visible aussi **avant** la préparation (NOT_PREPARED, PREPARE_FAILED, PREPARE_STOPPED,
  PREPARING) : « Reprise : automatique » ou « Reprise : à partir de X (choisi) », avec « changer… ».
- « changer… » ouvre le même dialogue, avec en plus « Automatique (selon le pipeline) » :
  - plan préparé : inchangé (`preview`, analyse des resélectionnés) ;
  - sinon, préparation arrêtée comprise : le point est **retenu** pour la prochaine préparation (Préparer, Préparer et copier, Copier au fil
    du listing). Il reste choisi jusqu'à ce qu'on revienne à « automatique ».
  - « à partir d'un fichier » : les fichiers listés quand il y en a, sinon « Choisir un fichier… » (sélecteur de
    fichier, `Plan.fromFile(Path)`).
- Préparation arrêtée, listing complet : le menu des lignes propose « Reprendre d'ici » (choisit le point de la
  prochaine préparation, rien ne repart) et « Analyser » (les fichiers non analysés de la sélection). La ligne
  d'état : « Préparation arrêtée : N fichiers listés, M analysés. Choisissez où reprendre (menu de la ligne, ou
  changer…), puis préparez à nouveau. »
- Préparation arrêtée pendant le listing : ces deux entrées sont désactivées et la ligne d'état le dit :
  « Préparation arrêtée pendant le listage : N fichiers listés. Les fichiers non listés seraient omis :
  préparez à nouveau. »

## 4. Tests

- Moteur : préparation avec point choisi (fichiers écartés non analysés, raison manuelle, aucune sonde en mode
  destination, proposition MANUAL, curseur lu et jamais reculé, ALL) ; streaming avec point choisi ;
  `isListingComplete` (listing complet, arrêt pendant le listing, échec de listing) ;
  `isStoppedAfterTheListing` (jamais préparé, préparé, exécution arrêtée : faux) ; analyse sur plan arrêté
  (retour CANCELLED ; refusée après un arrêt pendant le listing). CLI : `--from-date` sans analyse des fichiers
  écartés, sortie inchangée.
- IHM : ligne de reprise avant préparation, point retenu (aussi sur un plan arrêté, qui le reste), entrées du
  menu selon le listing, statuts.

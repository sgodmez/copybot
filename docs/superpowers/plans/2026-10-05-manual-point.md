# Plan : point de reprise choisi (spec 2026-10-05-manual-point-design)

Chaque tâche : test d'abord, puis code, puis les tests du module.

> Note, après coup : la tâche 4 (`continuePreparation`) et la continuation dans l'IHM ont été retirées ensuite.
> Une préparation arrêtée n'est jamais reprise d'elle-même : « Reprendre d'ici » y choisit le point de la
> prochaine préparation (spec §2).

## Moteur

1. `PipelineState.listingComplete` : posé par `runListing` quand la dernière étape IN finit sans échec ni arrêt,
   `emitItem` marque l'arrêt. Tests : listing complet, arrêt pendant le listing, échec de listing.
2. Point choisi à la préparation : `MainExecutor.chooseResumePoint`, `listingSource` (STATE / MANUAL) pour la
   raison des différés, pas de sonde ni de `propose`, `ResumeResolver.readCursor()`. Tests
   (`ManualPointTest`) : fichiers écartés non analysés avec raison manuelle, mode destination sans sonde, ALL,
   proposition MANUAL, curseur jamais reculé après exécution.
3. `CopybotEngine.prepare(..., chosen)` et `run(..., chosen)` (contexte `none` construit si besoin) ;
   streaming avec point choisi (FROM). Tests.
4. `continuePreparation(point)` (`MainExecutor`, `CopybotEngine`, `Plan.canContinue`) : machine d'états de la
   spec §2. Tests : refus (listing incomplet, plan préparé, exécution), continuation (fichiers gardés,
   analysés, PREPARED, exécutable), arrêt puis nouvelle continuation, `cancel` concurrent, engine occupé.
5. Analyse sur plan arrêté : `requestAnalysis` + `analyse(plan)` → retour CANCELLED. Tests.
6. `Plan.fromFile(Path)`. Test.
7. CLI : `--from-date` / `--all` par le point choisi. Tests `ResumeEndToEndTest` existants + fichier écarté non
   analysé.

## IHM

8. `PlanViewModel` : `chosenPoint`, `listingComplete`, `canContinue`, `canChooseResumePoint`, `resumeText`
   avant préparation, `analysable` / `resumePointFrom` sur plan arrêté, ligne d'état « arrêtée pendant le
   listage », `startContinuing`. Tests.
9. `PlanController` : passage du point choisi à `prepare` / `run`, continuation (« Reprendre d'ici »,
   « changer… »), « Analyser » sur plan arrêté, dialogue (automatique, sélecteur de fichier). Clés UI en/fr/it
   et `UiBundleTest`.
10. README (point de reprise avant la préparation), suites complètes.

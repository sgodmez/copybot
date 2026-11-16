# Execution Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal :** remplacer `startProcessingWhileListing` et `ui.autoExecute` par un champ `execution` (`plan`, `auto`, `streaming`), avec une phase streaming qui gère la reprise, et deux options de reprise par la destination (`destinationCheck`, `destinationMatch`).

**Spec :** `docs/superpowers/specs/2026-10-05-execution-mode-design.md`

## Contraintes

- JDK 25, `mvn -o test` à la racine ; Java LF ; bundles en `\uXXXX`, mêmes clés en/fr/it (`UiBundleTest.KEYS`).
- `copybot-ui/src/dev/test-pipeline.json` n'est pas touché.

## Tâches

### Tâche 1 : configuration

- [ ] Tests en échec : `ExecutionModeTest` (lecture, défaut `plan`, anciens champs ignorés), `ResumeConfigTest`
  (`destinationCheck`, `destinationMatch` et leurs défauts), `CopybotEngineTest` (valeurs inconnues refusées).
- [ ] `ExecutionMode`, `DestinationCheck`, `DestinationMatch`, `PipelineConfig.execution`, `ResumeConfig`,
  `ResumeContext`, contrôles à la lecture.

### Tâche 2 : reprise par la destination

- [ ] Tests en échec : `OutPatternTest` (`directoryVaries`), `ResumeResolverTest` (everyFile : chaque fichier
  décidé par sa cible, jour manquant réimporté, dossier fixe déclaré ou deviné → tout + avertissement ; match
  `file` en dichotomie et en everyFile, pas de garde-fou), `MainExecutorResumeTest` (préparation everyFile : tout
  analysé, présents sautés, curseur).
- [ ] `ResumePoint.Kind.NOT_AT_DESTINATION`, `ResumeResolver` (propose / apply / checkDestination),
  `DestinationProbe` sans garde-fou en mode fichier, `IOutAction.targetDirectoryVaries`,
  `OutPattern.directoryVaries`, `FileWriteAction`, `PlanPrinter`.

### Tâche 3 : phase streaming

- [ ] Tests en échec (`MainExecutorStreamingTest`) : sans reprise, pendant le listing ; `state` avec / sans
  curseur ; échec au milieu ; échec du listing ; fichier sans date ; annulation ; fichier d'état illisible ;
  destination everyFile (directory / file) ; destination dichotomie → everyFile + avertissement ;
  `stateThenDestination` avec curseur ; `run()` sans reprise attend la fin du listing.
- [ ] `ResumeResolver.streamingProposal()`, `MainExecutor.stream()`, `CopybotEngine.run` selon le mode.

### Tâche 4 : CLI

- [ ] Tests en échec (`ResumeEndToEndTest`) : streaming + `state` ; streaming + `destination` (avertissement
  dichotomie) ; everyFile réimporte un jour manquant ; match `file` ; `--dry-run` d'un pipeline streaming.

### Tâche 5 : interface

- [ ] Tests en échec : `PipelineDocumentTest` (`executionMode`, `destinationCheck`, `destinationMatch`),
  `PlanViewModelTest` (libellés, case visible seulement en `plan`, `auto` copie dès la préparation, streaming en
  exécution d'emblée, progression, échec de préparation, bannière `NOT_AT_DESTINATION`), `UiBundleTest`.
- [ ] `PipelineDocument`, `EditorController`, `PlanViewModel`, `PlanController`, bundles.

### Tâche 6 : vérification

- [ ] `mvn -o test` vert, un commit.

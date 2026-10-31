# Conflict Check Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal :** le plan annonce les fichiers sélectionnés dont la cible existe déjà (statut, infobulle, compteur, filtre), selon `"conflictCheck": "none" | "quick" | "full"`.

**Spec :** `docs/superpowers/specs/2026-10-05-conflict-check-design.md`

## Contraintes

- JDK 25, `mvn -o test` ; bundles en `\uXXXX`, bundles moteur ASCII, mêmes clés en/fr/it (`UiBundleTest.KEYS`).
- Fichiers CRLF du moteur : éditer sans réécrire les fins de ligne (`git diff --stat`).
- `copybot-ui/src/dev/test-pipeline.json` n'est pas touché.

## Tâches

### Tâche 1 : configuration

- [ ] Tests en échec : `CopybotEngineTest` (valeur inconnue / non textuelle refusée, `conflict-check.unknown`),
  lecture et défaut `quick`.
- [ ] `ConflictCheck`, `PipelineConfig.conflictCheck` (+ constructeur sans le champ pour les appels existants),
  `conflictCheckMode()`, contrôles de `readPipeline`, bundles moteur.

### Tâche 2 : API et `file.write`

- [ ] Tests en échec (`FileWriteActionTest` ou nouveau `FileWriteCheckTest`) : absente → FREE ; quick même taille /
  taille différente / dossier ; full identique → sauté, différent → renommé avec le nom, overwrite, error ; source
  elle-même ; clé manquante → UNKNOWN ; rien n'est écrit.
- [ ] `TargetCheck`, `IOutAction.checkTarget`, `FileWriteAction.checkTarget`, messages `write.check.*`.

### Tâche 3 : moteur

- [ ] Tests en échec (`MainExecutorResumeTest` / nouveau `ConflictCheckTest`) : préparation sans reprise → cibles
  vérifiées ; `none` → rien ; curseur → fichiers d'avant le curseur non vérifiés ; analyse différée → vérifiés ;
  fourche → le plus grave ; exception du plugin → UNKNOWN, fichier non en erreur ; sortie sans `checkTarget` → UNKNOWN.
- [ ] `WorkItemExecution.targetCheck`, `MainExecutor` (niveau, vérification après projection, empreinte de la
  sortie), `PlanPrinter` + `cli.plan.copy-existing`.

### Tâche 4 : interface

- [ ] Tests en échec : `PipelineDocumentTest` (`conflictCheck`), `PlanViewModelTest` (statut, infobulle, compteur
  quick / full, sélectionnés seulement, caché pendant la copie, filtre CONFLICTS), `UiBundleTest`.
- [ ] `PipelineDocument`, `EditorController`, `PlanViewModel`, `PlanController`, `plan-view.fxml`, bundles en/fr/it.

### Tâche 5 : vérification

- [ ] `mvn -o test` moteur, UI, plugin vert ; README (Highlights) ; commits par grande fonction.

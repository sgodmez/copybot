# Sorties secondaires — plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan
> task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** des branches à plat après l'analyse (`secondaryOutputs`), chacune avec ses traitements et sa sortie,
exécutées en séquence avant la branche principale, sur une copie de l'élément analysé.

**Architecture:** la liste à plat `itemSteps` devient `analyses, [étapes branche 1], …, [étapes branche n],
traitements principaux, sortie principale` ; un descripteur de disposition (`ItemLayout` : index de barrière, plages
des branches, plage principale) remplace les hypothèses positionnelles ; `findOutAction()` (dernière étape) reste la
sortie principale ; `ItemRunner` exécute chaque plage de branche en ligne sur une copie, puis la plage principale.

**Spec:** `docs/superpowers/specs/2026-10-30-secondary-outputs-design.md` (contraignante).

## Global Constraints

- JDK 25 ; `mvn -q verify -pl <module> -am` par module, `mvn -q verify` à la racine en fin de plan.
- API des plugins 1.2 non publiée : ajouts seulement ; `mvn clean verify -pl copybot-engine -am
  -Dplugin.api.update=force` puis vérifier que le diff de `plugin-api.txt` n'a que des lignes ajoutées.
- Bundles ASCII + `\uXXXX`, moteur en/fr/it, UI en/fr/it ; une valeur sans `{n}` n'a pas d'apostrophe droite (’).
- Un pipeline sans `secondaryOutputs` : comportement, JSON relu/réécrit et tests existants strictement inchangés.
- Un commit par tâche, message en anglais, trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Valeurs fixées : `onError` défaut `fail` ; plafond 8 branches ; noms uniques non vides (comparaison sans casse,
  espaces de bord retirés).

## Review Focus

1. Pipeline sans branche : rien ne change (projection, ressources `step:<i>`, curseur, afterItem).
2. Branche `fail` en erreur : principale non exécutée, source jamais supprimée, curseur non avancé.
3. `deleteSource` dans la principale : toutes les branches ont lu avant ; jamais dans une branche.
4. Métadonnées : la sortie d'une branche n'écrit pas dans l'élément de la principale (copies indépendantes).
5. afterItem des étapes de branche appelé une fois par élément reçu (fichiers temporaires nettoyés), même en erreur.
6. Projection du plan : les traitements des branches ne participent pas à la projection principale.

## Task 1 — API : source conservée

- [ ] `WorkItem` : champ `sourceKept`, `isSourceKept()`, `withSourceKept()` ; la marque est recopiée par
  `copyForDryRun`, `withContent`, `withContentFile`, `withCompanions`, `withoutCompanions` (tests dans
  `WorkItemCopyTest`).
- [ ] Javadoc `IOutAction` (contrat : ne jamais supprimer la source d'un élément marqué).
- [ ] `file.write` (`FileWriteAction.deleteSource`, `GroupWriter`) : ne supprime rien si l'élément est marqué ; un
  avertissement par run et par étape si `deleteSource` est coché (mécanisme d'avertissement existant de l'action, ou
  log WARNING si aucun canal d'avertissement de run n'est accessible à une sortie — le signaler dans le rapport).
- [ ] Empreinte d'API régénérée, ajouts seulement.

## Task 2 — Configuration et lecture

- [ ] Record `engine.pipeline.SecondaryOutputConfig(name, onError, actionSteps, outStep)` ; enum `OnError {FAIL,
  WARN}` lu en minuscules (`fail`/`warn`), défaut FAIL ; `actionSteps` null → vide.
- [ ] `PipelineConfig` : composant `secondaryOutputs` (null → `List.of()`), surcharge de constructeur comme pour
  `grouping`/`archives` (les appels existants compilent sans changement).
- [ ] `PipelineChecks.secondaryOutputsRefusal` appelé par `PipelineReader` : nom manquant/vide, doublon (sans casse),
  `onError` inconnu (via le contrôle d'enum existant), sortie manquante, plus de 8 branches. Messages moteur en/fr/it
  avec le nom ou le rang de la branche.
- [ ] `StepResolver` / `MainExecutor.resolveSteps` : résolution des étapes de branche avec les types attendus (PROCESS,
  OUT) ; erreur de résolution localisée avec le nom de la branche.
- [ ] `CopybotEngine.pipelinePaths` (et tout autre lecteur de `outStep()` trouvé) : inclure les sorties des branches
  là où c'est pertinent (chemins à vérifier/créer).
- [ ] Tests : `PipelineReaderTest`, `PipelineChecksTest`, aller-retour JSON.

## Task 3 — Moteur : exécution des branches

- [ ] `ItemLayout` (ou équivalent) : barrière, plages `[from,to)` de chaque branche (nom, onError, index de sa
  sortie), plage principale ; construit par `StepResolver.itemSteps` dans l'ordre analyses, branches, principale.
  Remplacer les usages positionnels qui supposent « tout ce qui suit la barrière est la principale » :
  `processSteps()` (projection du plan : traitements principaux seulement), `beginActions`/`endActions` (couvrent
  tout), ressources `step:<i>` (inchangées, index uniques).
- [ ] `ItemRunner.run` : à l'entrée d'une plage de branche, exécuter la branche en ligne sur
  `item.copyForDryRun().withoutCompanions().withSourceKept()` : traitements (produits multiples → chacun jusqu'à la
  sortie, en séquence ; liste vide → rien), puis `out.write` avec le même `WriteContext` (fichiers et dossiers
  remontent au bilan). Ressources et beforeEach/afterEach comme toute étape. afterItem de chaque étape de branche pour
  chaque élément reçu, à la fin de la branche, ordre inverse, même en erreur.
- [ ] Erreurs : `fail` → l'élément passe en ERREUR avec `« <nom> » : <message>` (masquage des secrets comme les
  erreurs d'étape), arrêt de l'élément (ni branches suivantes ni principale) ; `warn` → avertissement du run (masqué),
  suite. Annulation/exceptions de contrôle : non converties.
- [ ] `WorkItemExecution` : résultats par branche (nom, issue écrit/ignoré/filtré/erreur/avertissement, cibles,
  message) + accesseur pour le détail.
- [ ] Reprise : `succeeded()` inchangé de fait (l'élément est en ERREUR si une branche `fail` échoue) — test qui le
  prouve sur le curseur.
- [ ] Mode continu : même déroulé (test).
- [ ] Tests (`MainExecutorSecondaryOutputsTest`, modèle `MainExecutorGroupingTest`) : ordre branches puis principale ;
  copie indépendante des métadonnées ; pas de compagnons dans la branche ; source marquée ; produits multiples ;
  filtre ; `fail` bloque la principale et le curseur ; `warn` donne un avertissement ; afterItem appelé ; fichiers des
  branches dans `writtenFiles` ; pipeline sans branche inchangé ; bout en bout JSON avec deux `file.write` et
  `deleteSource` dans la principale (la branche a écrit, la source est supprimée après).

## Task 4 — Plan : cibles des branches dans le détail

- [ ] `Plan.detailOf` : pour chaque branche, dry-run de ses traitements sur une copie puis `resolveTarget` de sa
  sortie → liste `(nom, TargetProjection)` dans `ItemDetail` (ajout de composant interne au moteur ; adapter les
  appelants).
- [ ] Après la copie, le détail montre l'issue réelle de chaque branche (`WorkItemExecution`).
- [ ] Tests `PlanTest`.

## Task 5 — Éditeur : modèle

- [ ] `PipelineDocument` : lecture/écriture de `secondaryOutputs` sans perte (champs inconnus conservés comme
  ailleurs) ; adressage des étapes de branche (record d'adresse : section + index de branche + index, ou équivalent
  cohérent avec `Problem` / `UnknownSecret` / `advancedFields`) ; ajouter / supprimer / renommer / déplacer une
  branche ; ajouter / déplacer / supprimer ses traitements ; choisir/retirer sa sortie ; `onError`.
- [ ] Problèmes : nom vide ou en double, sortie absente (bloquants) ; avertissement `file.write` + `deleteSource`
  dans une branche ; secrets inconnus dans les étapes de branche (même mécanisme).
- [ ] `PipelineSummary` : « + N sorties secondaires » (clé UI en/fr/it).
- [ ] Tests `PipelineDocumentTest` (aller-retour, opérations, problèmes) + aller-retour moteur (`PipelineReader` lit ce
  que l'éditeur écrit), modèle `RunEndRoundTripTest`.

## Task 6 — Éditeur et plan : IHM

- [ ] `EditorController` : nœud « Sorties secondaires » après la sortie, enfants par branche, sous-enfants
  traitements + sortie ; boutons ajouter/supprimer/monter/descendre selon le nœud ; formulaire de branche (nom,
  en cas d'erreur) ; formulaires d'étape réutilisés (`stepForm`) avec l'adresse de branche.
- [ ] Détail d'un élément (`ItemDetailDialog`) : section « Sorties secondaires » (nom, cible projetée ou issue).
- [ ] Clés UI en/fr/it ; tests FX si le module en a pour l'arbre (sinon tests du modèle + vérification manuelle
  décrite dans le rapport).

## Task 7 — README et vérification

- [ ] README : section « Sorties secondaires » (exemple JSON : original + miniatures, `onError`, ordre, source
  conservée, limites §9 de la spec).
- [ ] `mvn -q verify` à la racine ; écarts éventuels notés dans la spec (« Écarts à la réalisation »).

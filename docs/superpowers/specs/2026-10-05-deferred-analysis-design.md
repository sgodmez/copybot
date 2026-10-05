# Analyse des fichiers resélectionnés par un point de reprise manuel

Date : 2026-10-05
Statut : validé (design)

## Contexte

Depuis le commit « Resume: key on the file modification date, skip old files at the listing », la
préparation saute dès le listing les fichiers antérieurs au curseur d'état (modes `state` et
`state-then-destination` avec un curseur) : `WorkItemExecution.deferAnalysis` les met SKIPPED, préparés, sans
analyse ni projection (dry run). Un point de reprise manuel (`Plan.preview` → `MainExecutor.applyOverride`) qui
les resélectionne les laisse sans cible ni détail dans le plan : ils ne sont analysés qu'à `execute()` (soumis
depuis l'étape 0 au lieu de la barrière).

But : quand un point manuel resélectionne de tels fichiers, les analyser **avant** l'exécution (étapes
`[0, barrierIndex)` puis dry run des étapes process), pour que le plan montre leurs cibles et leur détail.

Exigence de non-régression : quand cette analyse n'a pas lieu, le comportement est **strictement identique** à
aujourd'hui (aucun fichier différé, point manuel qui ne resélectionne aucun fichier différé, analyse annulée,
`execute()` lancé alors que des fichiers sélectionnés sont encore différés).

## 1. Moteur

### `MainExecutor.analyseDeferred()`

- Préalable : statut PREPARED (sinon `IllegalStateException` `engine.not-prepared`).
- Les fichiers visés : PENDING et `isAnalysisDeferred()` (resélectionnés par le point en vigueur). Aucun → retour
  immédiat, **sans phase** : ni changement de statut, ni notification du watcher.
- Un `cancel()` déjà demandé sur le plan (avant l'analyse) : retour immédiat, l'annulation reste en attente et
  annulera l'exécution comme aujourd'hui.
- Sinon une phase comme la préparation : statut RUNNING (PAUSED si une pause était demandée), registre de
  ressources, threads virtuels, watcher (notifications coalescées + notification terminale), chaque fichier
  soumis sur `[0, barrierIndex)` avec `finalPhase = false` : il s'arrête PENDING à la barrière (l'analyse ne
  sort jamais du point de reprise), est projeté dès qu'il est analysé (`projectEarly`), et un échec d'analyse le
  met ERROR comme à la préparation. `projectItems()` complète à la fin.
- Fin (normale, annulée ou interrompue) : statut de nouveau **PREPARED** (publié avant la notification terminale).
- `markPrepared()` (barrière atteinte ou échec avant elle) efface désormais aussi `analysisDeferred` : un fichier
  analysé n'est plus différé, `execute()` le reprend à la barrière. Sans effet à la préparation (aucun fichier
  différé n'y passe par `runItem`).
- Pendant l'analyse, `isPreparing()` est vrai : un fichier sans projection n'est pas encore analysé
  (`Plan.projectionOf` → NONE), comme pendant la préparation.

Statut RUNNING plutôt qu'un drapeau dédié : tout ce qui exige PREPARED (`execute`, `setIgnored`, l'IHM)
refuse naturellement pendant l'analyse, et le modèle de l'IHM sait déjà qu'un statut non terminal hors
exécution est un travail en cours.

### Annulation

- `cancelAnalysis()` (et `Plan.cancelAnalysis()`) : n'agit **que pendant** une analyse (sinon sans effet : un
  clic tardif ne doit pas annuler le plan préparé). Interrompt l'analyse ; les fichiers interrompus repassent
  PENDING en restant différés (toujours sélectionnés), ceux déjà analysés gardent leur projection.
- `cancel()` pendant une analyse (ex. `CopybotEngine.close()`) a le même effet : il arrête l'analyse, le plan
  reste PREPARED. La demande d'annulation est effacée à la fin de l'analyse.
- `execute()` traite comme aujourd'hui les fichiers encore différés : soumis depuis l'étape 0.

### `CopybotEngine.analyse(Plan)` et `Plan`

- `CopybotEngine.analyse(Plan plan)` : bloquant, dans le thread appelant, compte comme l'opération active
  (une à la fois, `engine.busy` sinon ; `close()` l'annule). Sans fichier à analyser : rien de plus que la prise
  et le relâchement de l'engine.
- `Plan.toAnalyse()` : les fichiers sélectionnés dont l'analyse est différée (liste vide dans le cas courant).
- `Plan.cancelAnalysis()` : voir ci-dessus, appelable de tout thread.

## 2. IHM (`PlanController`, `PlanViewModel`)

- `applyResumePoint` : inchangé (`plan.preview(point)` synchrone sur le thread JavaFX), puis si
  `plan.toAnalyse()` n'est pas vide, l'analyse est lancée en arrière-plan comme Prepare (jeton d'opération
  inchangé, `hold()`, notifications du watcher de la préparation → rafraîchissement). Liste vide : rien d'autre.
- Nouvelle phase `ANALYSING` du modèle (`startAnalysing(items)`) : tant que le statut est RUNNING/PAUSED hors
  exécution ; PREPARED la termine. Moteur occupé (`isActive`) : Copier visible mais désactivé, Préparer, Retour,
  Éditer, menus d'ignorance désactivés ; ligne de reprise affichée ; barre « Analyse des fichiers
  resélectionnés… n / N » sur les seuls fichiers visés (analysé = plus différé) ; bouton Arrêter →
  `plan.cancelAnalysis()`.
- Changer de point pendant l'analyse : **interdit** (« changer… » et « Reprendre d'ici » désactivés, comme
  pendant toute opération). Appliquer un point pendant que des fichiers passent RUNNING/PENDING ferait
  échapper au point les fichiers en cours ; l'utilisateur arrête l'analyse (Arrêter) puis choisit un autre
  point, ce qui relance l'analyse des fichiers alors resélectionnés. Option la plus simple qui reste correcte.
- Une analyse en échec inattendu (exception) : le plan reste PREPARED, l'erreur est affichée.

## 3. CLI (`com.copybot.Copybot`)

En dry run avec `--all` / `--from-file` / `--from-date` : `plan.preview(override)` puis `engine.analyse(plan)`
avant d'imprimer (un fichier resélectionné en échec d'analyse s'imprime en erreur). Exécution réelle : inchangée
(`execute()` analyse les fichiers différés).

Note : `PlanPrinter` n'imprime pas de cible par fichier ; l'ajout d'une colonne cible au dry run CLI changerait
la sortie de tous les dry runs et n'est pas fait ici.

## 4. Tests

- Moteur (`MainExecutorResumeTest`, `PlanTest`, `CopybotEngineTest`) : analyse des resélectionnés (analysés,
  PENDING, projetés, plus différés, statut PREPARED, watcher), échec d'analyse → ERROR, annulation (statut
  PREPARED, fichiers restants différés puis analysés à `execute()`), `cancelAnalysis` hors analyse sans effet,
  non-régression : sans fichier différé ou point qui n'en resélectionne aucun → aucune phase (statut et watcher
  inchangés), `execute()` avec fichiers encore différés inchangé (test existant).
- IHM (`PlanViewModelTest`) : phase ANALYSING, boutons, progression, fin sur PREPARED.
- CLI (`ResumeEndToEndTest`) : dry run `--all` avec curseur → fichiers resélectionnés imprimés COPY, rien écrit.

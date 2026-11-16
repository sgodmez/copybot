# Sorties secondaires — conception

Date : 2026-10-30. Statut : décidé (Steven a délégué les choix ; questions réservées aux décisions qui lui
reviennent). Origine : discussion « fork de pipeline intermédiaire » (branches à plat après l'analyse), à livrer avant
le plugin ffmpeg pour qu'une transcodification ne remplace jamais l'original.

## 1. Besoin

Un même fichier lu une seule fois doit pouvoir partir vers plusieurs destinations, chacune avec ses propres
traitements : l'original copié tel quel dans la photothèque, une version transcodée (ffmpeg) ou une miniature ailleurs.
Aujourd'hui un pipeline n'a qu'une suite de traitements et une seule sortie (`actionSteps` + `outStep`).

## 2. Modèle

```
IN → (regroupement / dépliage) → ANALYZE → barrière
     → branche secondaire 1 : traitements + sortie
     → branche secondaire 2 : traitements + sortie
     → branche principale  : traitements + sortie   (inchangée : actionSteps + outStep)
```

- La branche principale reste celle d'aujourd'hui (mêmes champs JSON, même comportement) ; un pipeline sans
  `secondaryOutputs` se comporte exactement comme avant.
- Nouveau champ de pipeline, liste ordonnée :

```json
"secondaryOutputs": [
  { "name": "Miniatures", "onError": "fail",
    "actionSteps": [ { "plugin": "...", "action": "..." } ],
    "outStep": { "plugin": "copybot", "action": "file.write", "actionConfig": { } } }
]
```

  - `name` : obligatoire, non vide, unique dans le pipeline (sert dans les messages, le détail d'un élément et
    l'éditeur).
  - `onError` : `fail` (défaut) ou `warn`.
  - `actionSteps` : 0..n traitements ; `outStep` : obligatoire (une branche sans sortie est refusée à la lecture).
  - Plafond : 8 branches secondaires (refus au-delà, message clair).
- Pas de branche dans une branche, pas de branche avant l'analyse (le regroupement reste juste après le listage).

## 3. Déroulé pour un élément

1. L'élément passe IN, regroupement, ANALYZE comme aujourd'hui (une seule lecture, une seule analyse).
2. Après la dernière analyse (en mode planifié : pendant la copie, après la barrière), chaque branche secondaire, dans
   l'ordre, reçoit **sa propre copie** de l'élément analysé :
   - métadonnées copiées (cartes indépendantes : la sortie de la branche peut y écrire `SHA256` sans toucher les
     autres) ; même source, même contenu ;
   - **sans compagnons** : une miniature ou une vidéo transcodée n'emporte pas le XMP ni les fichiers liés ; seule la
     branche principale écrit les groupes ;
   - marquée **source conservée** (§5) : aucune sortie secondaire ne supprime la source.
3. Dans une branche, les traitements s'enchaînent comme dans la principale. Un traitement qui produit plusieurs
   éléments (ex. plusieurs tailles de miniature) : chaque produit continue seul jusqu'à la sortie de la branche, en
   séquence dans la même tâche (pas de nouvel élément dans la file, pas d'arbre de fork). Un traitement qui filtre
   (liste vide) : la branche n'écrit rien pour cet élément, ce n'est pas une erreur.
4. La branche principale passe **en dernier** : si sa sortie supprime la source (`deleteSource`, mode déplacement),
   toutes les branches ont déjà lu le fichier.
5. Les branches d'un même élément s'exécutent en séquence ; les éléments restent parallèles entre eux. Les ressources
   (concurrence par étape, ressources déclarées) s'appliquent à chaque étape de branche comme à toute étape.
6. `afterItem` de chaque étape de branche est appelé pour chaque élément qu'elle a reçu, une fois la branche terminée
   pour cet élément (succès ou erreur), dans l'ordre inverse des étapes — même contrat que pour la principale (les
   fichiers temporaires d'un traitement de branche sont donc nettoyés).
7. `beforeAll` / `afterAll` couvrent aussi les étapes des branches (même phase que les traitements principaux).

### Erreur dans une branche

- `onError: fail` (défaut) : l'erreur de la branche met l'élément en ERREUR **et la branche principale n'est pas
  exécutée** pour cet élément (ni copie, ni suppression de la source) ; les branches suivantes non plus. L'élément
  n'est pas réussi : le curseur de reprise ne le dépasse pas, il est retenté au prochain run (les sorties des branches
  déjà écrites appliquent alors leur propre règle de conflit, typiquement « ignorer si identique »). Message :
  `« <nom de la branche> » : <erreur>`.
- `onError: warn` : l'erreur devient un avertissement du run (`« <nom> » : <erreur> (<élément>)`), la suite continue,
  l'élément peut réussir. Coût : l'élément ne sera pas retenté pour cette branche.
- Une annulation (Ctrl+C, bouton) arrête l'élément quelle que soit la branche, comme aujourd'hui.

## 4. Résultat, rapport, reprise

- L'issue d'un élément (ÉCRIT / IGNORÉ / FILTRÉ / ERREUR) reste celle de la branche principale, sauf l'erreur d'une
  branche `fail` qui l'emporte.
- `WorkItemExecution` garde en plus un résultat par branche secondaire (nom, issue, cibles écrites, erreur) pour le
  détail d'un élément.
- Le bilan de fin de run (`RunReport`) : `writtenFiles` contient aussi les fichiers des branches (via
  `WriteContext.fileWritten`), `createdDirectories` aussi ; `RunItem` reste celui de la branche principale ; les
  erreurs `warn` sont dans `warnings`. Pas de changement d'API du bilan.
- Reprise : curseur unique par pipeline, un élément « réussi » exige la réussite de la principale **et** de toutes les
  branches `fail`. La vérification « déjà à destination » (NOT_AT_DESTINATION) ne regarde que la sortie principale.
- Limite assumée : ajouter une branche à un pipeline existant ne la rejoue pas sur les éléments déjà copiés (relancer
  avec « tout » si besoin) — pas de curseur par branche.

## 5. API des plugins (1.2 non publiée, ajouts seulement)

- `WorkItem.isSourceKept()` (défaut faux) et `WorkItem.withSourceKept()` (copie marquée ; la marque suit les copies
  faites par `copyForDryRun`, `withContent`, `withContentFile`, `withCompanions`, `withoutCompanions`).
- Contrat documenté dans `IOutAction` : une sortie ne supprime jamais la source d'un élément marqué. `file.write` le
  respecte (`deleteSource` ignoré, une fois par run un avertissement si `deleteSource` est coché dans une branche
  secondaire). L'éditeur affiche aussi un avertissement sur une sortie secondaire `file.write` avec `deleteSource`.
- `WorkItem.copyForBranch()` n'est pas ajouté : le moteur utilise `copyForDryRun()` puis `withoutCompanions()` et
  `withSourceKept()`.

## 6. Mode planifié, mode continu

- Plan : la projection, la cible et le contrôle de conflit affichés restent ceux de la branche principale. Le détail
  d'un élément liste en plus, par branche secondaire, la cible projetée (dry-run des traitements de la branche +
  `resolveTarget` de sa sortie) ou « inconnue ». Pas de contrôle de conflit des branches avant la copie : leurs
  sorties appliquent leur règle de conflit au moment d'écrire.
- Mode continu : chaque élément passe toutes ses branches au listage, comme les autres étapes. Rien de spécial.
- Le motif de sortie propre à un élément (« outPattern » modifié dans le plan) ne concerne que la sortie principale ;
  une branche utilise le motif de sa propre sortie.

## 7. Vérifications à la lecture du pipeline

- Champs obligatoires, noms uniques, `onError` connu, plafond 8, étapes résolues (plugin, action, version) comme les
  autres étapes ; type attendu : PROCESS pour `actionSteps`, OUT pour `outStep`.
- Le refus « le pipeline ne fait rien » (`doesNothing`) ne tient compte que de la principale (une branche seule sans
  sortie principale reste refusée : la principale porte la reprise et le déplacement).
- Le contrôle des compagnons COPY (`groupingRefusal`) ne concerne que la sortie principale (les branches ne reçoivent
  pas de compagnons).

## 8. Éditeur

- Arbre : après « Sortie », un nœud « Sorties secondaires » ; un enfant par branche (son nom) ; sous chaque branche,
  ses traitements puis sa sortie.
- Ajouter / supprimer / renommer / monter / descendre une branche ; ajouter, déplacer, supprimer les traitements d'une
  branche et choisir sa sortie avec les mêmes formulaires que la principale (même catalogue, mêmes champs secrets,
  mêmes problèmes signalés).
- Formulaire de branche : nom, « En cas d'erreur : faire échouer l'élément / avertir seulement ».
- Avertissements : sortie secondaire `file.write` avec `deleteSource` (§5) ; branche sans sortie (problème bloquant).
- Résumé du pipeline (liste) : « + N sorties secondaires ».

## 9. Hors périmètre

Curseur par branche, branche dans une branche, branches en parallèle pour un même élément, contrôle de conflit des
branches dans le plan, compagnons dans les branches, branche conditionnelle (un filtre en tête de branche suffit :
`filterCondition` existe déjà sur toute étape).

## 10. Écarts à la réalisation

- §6 : le détail d'un élément (`ItemDetail.BranchDetail`) garde aussi les **fichiers** cibles projetés de chaque
  branche (`targets`), pas seulement les dossiers ; l'affichage les liste comme les cibles de la sortie principale.
- §8 : un `onError` inconnu dans le JSON n'est pas présenté comme « faire échouer » dans le formulaire de branche :
  la liste n'a pas de sélection tant que la valeur est inconnue (le problème bloquant est affiché), et choisir
  « faire échouer » ou « avertir » l'écrit.
- §8 : le formulaire en lecture seule d'une étape de branche dont le plugin manque affiche aussi les problèmes de la
  branche. Les guillemets des messages de l'éditeur suivent la langue (`editor.secondary.quoted`) ; les messages
  repris du moteur (`secondary.*`) gardent la mise en forme du moteur.
- Aucune autre divergence constatée lors de la vérification finale.

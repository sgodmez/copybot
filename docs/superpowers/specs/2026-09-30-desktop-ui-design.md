# UI desktop : accueil, vue du plan, éditeur générique de pipeline

Date : 2026-09-30
Statut : validé (design, brainstorming avec l'utilisateur, maquettes validées : accueil, plan « C1 », éditeur « A »)
Prérequis : specs « moteur en instance » et « écriture sûre » implémentées.

## Contexte

`copybot-ui` (JavaFX, JPMS) est un banc de test : menu, dialogue de préférences (langue), un `TableView` de `WorkItemExecution` (Nom / Emplacement / Taille / Statut, localisé) rempli par un pipeline codé en dur. Usage cible : ouvrir l'UI → choisir un pipeline récent → préparer le plan de la carte → copier (ou copie automatique) ; plus un éditeur générique de pipeline.

## 1. Écran d'accueil

- Liste des **pipelines récents** (10 max, persistés dans les préférences UI : chemin, nom affiché = nom de fichier sans extension, date et résumé de la dernière exécution : statut, copiés, ignorés, erreurs).
- Actions : « Ouvrir un pipeline… » (sélecteur de fichier `.json`), « Nouveau… » (ouvre l'éditeur sur un pipeline vide), retrait d'un récent (clic droit).
- **Rien n'est lancé à l'ouverture de l'application.** Un clic sur un pipeline ouvre la vue du plan de ce pipeline, tableau vide.
- Un pipeline récent dont le fichier n'existe plus est affiché grisé avec « introuvable ».

## 2. Vue du plan (variante C1 : tableau à plat)

En-tête :
- « ← Pipelines » (retour à l'accueil ; interdit pendant une exécution active — bouton désactivé) ;
- nom du pipeline, résumé : étapes (noms affichés), pour `file.read` le chemin source et pour `file.write` le pattern de sortie, mode de reprise, dernière exécution ;
- bandeau d'**avertissements** (avertissements de configuration des étapes, avertissements de la proposition de reprise) ;
- ligne de **reprise** après préparation : « Reprise : après DSC_4821 (28/09 17:42) [curseur] » + lien « changer… » (dialogue : tout / à partir d'une date / à partir d'un fichier choisi dans le plan).

Tableau (le `TableView` existant, enrichi) :
- colonnes : Nom, Date (clé de reprise), Cible (répertoire cible résolu), Taille, Statut ;
- statut localisé et enrichi : « À copier », « Ignoré — <raison> », « Copie 42 % », « Copié », « Erreur — <message> » ;
- **placeholder** tant que le plan n'est pas préparé : « Plan non préparé — cliquez sur *Préparer le plan* pour lister la source » ;
- le tableau se remplit au fil du listing pendant la préparation ;
- **filtre** : Tous / À copier / Ignorés / Erreurs ;
- clic droit sur une ligne : « Reprendre à partir d'ici » (point de reprise manuel `from(clé)`, statuts recalculés via `Plan.preview`).

Barre d'actions :
- « Préparer le plan » (avant préparation, ou pour re-préparer) ;
- pendant la préparation : une barre de progression par étape — listing (barre indéterminée, « Listing de la source… N fichiers »), analyse (fichiers analysés / listés, « Analyse des fichiers… X / N »), calcul du point de reprise (barre indéterminée) ;
- après préparation : « Copier N fichiers (X Go) » ; case **« exécution automatique »** à côté, initialisée avec `ui.autoExecute` du pipeline JSON (défaut `false`) ; changer la case n'affecte que la session (le fichier n'est modifié que par l'éditeur). Si la case est cochée, la copie démarre dès que le plan est prêt (sauf plan en erreur) ;
- pendant l'exécution : « Pause » / « Reprendre », « Stop » ; une barre de progression (fichiers et octets traités / sélectionnés).
- À la fin : récapitulatif (copiés / ignorés / erreurs), enregistré dans le récent du pipeline.

Threading : `prepare` dans une tâche de fond (jamais sur le thread FX) ; le watcher (déjà coalescé à ~10 Hz) publie via `Platform.runLater`. Une préparation ou une exécution en cours est annulée proprement à la fermeture de la fenêtre (`CopybotEngine.close`).

## 3. Éditeur générique (variante A : maître / détail)

Fenêtre séparée ouverte par « Éditer… » (vue du plan) ou « Nouveau… » (accueil).

- **Gauche** : arbre par section — Pipeline (général), Entrées, Analyses, Traitements, Sortie ; boutons ajouter (dans la section sélectionnée), monter, descendre, supprimer. La section Sortie contient au plus une étape.
- **Ajouter une étape** : liste des actions des plugins chargés compatibles avec la section (type d'étape), avec nom et description localisés.
- **Droite** : formulaire de l'élément sélectionné :
  - Pipeline : `startProcessingWhileListing`, `resume.mode`, `ui.autoExecute` ;
  - étape : formulaire **généré** depuis le schéma de config de l'action (§4), puis une section repliée « Avancé » : `maxConcurrency`, `resources`, `priority`, `version`.
- « Enregistrer », « Enregistrer sous… », « Voir le JSON » (lecture seule). Validation des champs requis avant enregistrement. Un enregistrement réussi ferme l'éditeur (il reste ouvert en cas d'échec) ; la vue du plan recharge alors le pipeline (le plan préparé est invalidé), ou suit le nouveau fichier après « Enregistrer sous… ».
- **Fidélité** : l'éditeur travaille sur l'arbre JSON (`JsonObject`) et ne réécrit que les champs qu'il connaît ; les champs inconnus du schéma sont conservés tels quels. Une étape dont le plugin n'est pas chargé est affichée en lecture seule (« plugin introuvable ») et conservée ; de même (« plugin en erreur ») pour une étape résolue, comme par le moteur, vers une version chargée dont les actions ne peuvent pas être listées.

## 4. Schéma de configuration par introspection (moteur)

- Nouveau `ConfigSchema` dans l'API plugin : liste de champs `{ name, kind, required, defaultValue, hints, labelKey, descriptionKey, children | enumValues | elementSchema }` avec `kind` ∈ `STRING, BOOLEAN, INTEGER, DECIMAL, PATH, ENUM, RECORD, LIST`.
- Construit par introspection des **composants du record** de config : `AbstractActionWithConfig` fournit `configSchema()` à partir de `getConfigClass()` ; `IAction` a `default Optional<ConfigSchema> configSchema()` (vide) — une action peut le surcharger si l'introspection ne suffit pas.
- Annotations (package API plugin) : `@DirectoryPath`, `@FilePath`, `@PatternField` (l'éditeur affiche les variables de pattern connues : `name`, `size`, et pour chaque date `creation`, `lastModified`, `captureDate` : `.Y .y .m .D`), `@DefaultValue("…")`, `@Required`.
- Libellés i18n : `plugin.<plugin>.<action>.config.<champ>.name` / `.description`, champs imbriqués `…config.<champ>.<sous-champ>.name` ; clé absente ⇒ nom du champ.
- **Catalogue** : `PluginEngine` expose les actions chargées (plugin, version, code, type d'étape, nom et description localisés, schéma).
- Les configs embarquées (`FileReadConfig`, `FileWriteConfig` et leurs sous-records de l'écriture sûre) sont annotées et ont leurs libellés EN/FR.

## 5. Support moteur supplémentaire

- `Plan.targetOf(WorkItemExecution)` : répertoire cible résolu via `IOutAction.resolveTarget` (vide si non résolvable), pour la colonne Cible.
- `Plan` expose les compteurs (sélectionnés, ignorés, erreurs, octets sélectionnés) pour le bouton « Copier N fichiers (X Go) ».

## 6. i18n et préférences

- Tous les textes UI dans les bundles UI (EN/FR) ; si un bundle est encodé ISO-8859-1, mêmes règles d'édition octet par octet et échappements `\uXXXX` que pour le moteur.
- Préférences UI (`UiPreferences`) : langue (existant), récents, dernière exécution par pipeline.

## 7. Structure et tests

- Logique testable hors JavaFX : `RecentPipelines` (persistance, ordre, taille max, introuvables), `PlanViewModel` (filtre, compteurs, libellé du bouton, états des boutons selon le statut), `PipelineDocument` (chargement / modification / enregistrement d'un `JsonObject`, préservation des champs inconnus, ajout / déplacement / suppression d'étapes, validation des requis), `ConfigSchema` (introspection de records de test : types, enum, imbriqués, listes, annotations, clés i18n).
- Contrôleurs JavaFX minces branchés sur ces modèles ; pas de tests d'IHM automatisés dans ce chantier (vérification manuelle en lançant l'application).
- Le banc de test actuel (`HelloController`, `hello-view*.fxml`, boutons de test) est remplacé par les nouvelles vues ; le lanceur de dev `CopybotMainUiDev` est conservé.

## Hors périmètre

Vues alternatives du plan (groupée par répertoire, etc.), détection d'insertion de carte, transcodage, exécutions parallèles.

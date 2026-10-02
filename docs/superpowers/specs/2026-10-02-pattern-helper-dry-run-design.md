# Aide à l'expression de sortie, dry run des traitements

Date : 2026-10-02
Statut : validé (design, brainstorming avec l'utilisateur)

## Contexte

Dans l'éditeur de pipeline, le champ `outPattern` de `file.write` n'affiche qu'une liste fixe de variables (`ConfigSchema.PATTERN_VARIABLES` : `name`, `size`, `creation|lastModified|captureDate` × `.Y .y .m .D`), identique quelles que soient les étapes du pipeline. Aucun plugin ne déclare les métadonnées qu'il produit. Le pattern est une substitution plate `{clé}` dans `WorkItemMetadata.display()` (`FileWriteAction.resolveFileName`) ; une clé absente reste **telle quelle, accolades comprises**, dans le chemin écrit (ex. un dossier `{captureDate.Y}` créé sur le NAS pour une vidéo sans EXIF), sans erreur.

Par ailleurs, « Préparer » (= `--dry-run` de la CLI) s'arrête à la barrière (après les analyses) : l'effet des `actionSteps` (`IProcessAction.doProcess` : modification de métadonnées, transformation, dédoublement, filtrage) n'est visible nulle part avant la copie.

Décisions actées avec l'utilisateur :

- Les métadonnées affichées viennent d'un **échantillon réel** lu depuis la source configurée (pas de déclaration côté plugin) : un « mini-préparer » (`PipelineSampler`), indépendant du moteur, chargé automatiquement à l'affichage du formulaire de sortie, une seule fois tant que les étapes ne changent pas. Pas de réutilisation d'un plan préparé (trop compliqué), et l'échantillon n'est jamais réutilisé par un vrai « Préparer ».
- Échantillon varié et plafonné (200 fichiers listés ou 2 s, 10 retenus en alternant les extensions).
- L'aide affiche les clés avec valeurs (insertion par clic) **et** l'aperçu du chemin calculé par fichier.
- Syntaxe de repli `{a|b|'fixe'}` ; option `onMissingKey` = `error` (défaut) | `skip` | `literal` dans la config de `file.write`.
- Les traitements gagnent un **dry run** (`IProcessAction.dryRun`) : effet prévu sans rien écrire ni calcul lourd, utilisé par le sampler **et** par « Préparer ». Tout dans ce chantier.

## 1. `OutPattern` : syntaxe et résolution

Nouvelle classe `com.copybot.plugin.api.pattern.OutPattern` (API plugin : réutilisable par un plugin de sortie tiers). `file.write` et l'aide de l'éditeur utilisent le même code : l'aperçu ne peut pas diverger de l'exécution.

Syntaxe :

- Le texte hors accolades est recopié tel quel (pas d'échappement des accolades).
- `{captureDate.Y}` : une clé de `display` (noms inchangés).
- `{captureDate.Y|lastModified.Y}` : repli, la première alternative qui a une valeur l'emporte, de gauche à droite.
- `{captureDate.Y|'sans-date'}` : valeur fixe entre apostrophes (a toujours une valeur) ; une apostrophe dans une valeur fixe se double : `'l''été'`.
- Espaces autour de `|` (et en début / fin d'expression) ignorés.
- Une clé présente mais vide (`""` ou blanche) compte comme absente.

Erreurs de syntaxe (refusées au chargement de la config, `CopybotException` `pattern.syntax` avec la position, 1-based, et la raison) : accolade non fermée, `}` sans `{`, `{}` vide, alternative vide (`{a||b}`, `{a|}`), apostrophe non fermée, texte après une valeur fixe dans la même alternative (`{'a'b}`).

API :

- `static OutPattern parse(String pattern)` — lève l'erreur de syntaxe ;
- `List<String> keys()` — les clés utilisées, dans l'ordre, sans doublon (pas les valeurs fixes) ;
- `String staticPrefix()` — le texte avant la première `{` (remplace le calcul de `FileWriteAction.touchedPaths`) ;
- `Resolution resolve(Map<String, String> display)` — `record Resolution(String text, List<String> missing)` : `text` est le résultat, une expression sans valeur y étant recopiée **telle qu'écrite** (accolades comprises, comme aujourd'hui) ; `missing` liste ces expressions telles qu'écrites (ex. `"{captureDate.Y|lastModified.Y}"`), vide si tout est résolu.

Hors périmètre : nettoyer une valeur qui contient `/`, `\` ou `:` (comportement actuel inchangé).

## 2. `onMissingKey` dans `file.write`

Nouveau champ de `FileWriteConfig` (affiché par l'éditeur comme les autres) :

```java
@AllowedValues({"error", "skip", "literal"}) @DefaultValue("error")
String onMissingKey
```

`FileWriteSettings` le parse (enum `MissingKey`, valeur inconnue ⇒ `write.config.unknown-value`) et parse l'`OutPattern` (une seule fois, au chargement de la config).

Quand une expression n'a aucune valeur (`Resolution.missing` non vide) :

- `error` : l'item échoue, `CopybotException` `write.pattern.missing-key` (« Aucune valeur pour {0} : {1} », expressions jointes par `, ` puis nom du fichier). Les autres items continuent ; le run finit en erreur.
- `skip` : l'item est ignoré (`WriteResult.skipped`) avec la raison `write.skip.missing-key` (« clé absente : {0} »), compté dans les ignorés.
- `literal` : comportement actuel, le texte résolu (avec l'expression laissée telle quelle) est utilisé.

Le contrôle a lieu **avant** tout le reste dans l'écriture (avant la gestion des conflits, avant de créer un dossier) : avec `error` ou `skip`, rien n'est touché sur la destination et la source n'est jamais supprimée (`deleteSource`).

`resolveTarget` (utilisé par « Préparer » et la reprise `destination`) : avec `error` **ou** `skip`, lève l'erreur `write.pattern.missing-key` (le message est affiché dans la projection, §4) ; avec `literal`, rend le chemin.

Reprise `destination` (`ResumeResolver`, qui appelle aujourd'hui `resolveTarget` sans attraper d'erreur) : un item dont `resolveTarget` lève une `CopybotException` est **exclu de la sonde**, comme un item sans clé de reprise (il reste sélectionné et échouera ou sera ignoré à l'exécution selon `onMissingKey`) ; la préparation n'échoue pas pour autant. Toute autre exception garde le comportement actuel.

## 3. `PipelineSampler` : le mini-préparer

Package `com.copybot.engine.sample`. Indépendant de `CopybotEngine` et de son verrou « busy » : il fonctionne pendant une préparation ou une copie.

### 3.1 Résolution des étapes

La résolution des étapes (aujourd'hui `MainExecutor.doResolveStep` / `resolveOtherSteps`, privés) est extraite dans une classe partagée `com.copybot.engine.StepResolver`, utilisée par `MainExecutor` et par le sampler. Aucun changement de comportement.

### 3.2 Session

Entrée : un `PipelineConfig` construit en mémoire depuis le JSON du document édité (non enregistré). Seuls `inSteps`, `analyseSteps` et `actionSteps` sont utilisés ; `outStep` est ignoré (il peut être incomplet). Ni curseur de reprise, ni fichier d'état.

`SampleSession` (créée par `PipelineSampler.open(...)`), deux opérations bloquantes, appelées par l'UI sur un thread d'arrière-plan :

1. **`list(PipelineConfig)`** : résout et configure les `inSteps`, appelle `listFiles` de chacun l'un après l'autre sur un thread démon dédié. Arrêt dès 200 items reçus au total (exception privée `StopSampling` lancée depuis le consommateur) ou au bout de 2 s : le thread de listage est alors interrompu et n'est plus attendu (démon), les items reçus sont gardés. Puis **sélection** : au plus 10 items, en alternant les extensions (minuscules ; sans extension = une extension « vide ») dans l'ordre de première apparition : le 1er de chaque extension, puis le 2e de chaque, etc., jusqu'à 10. La session garde une copie intacte (`WorkItem.copyForDryRun()`) des items retenus. Un listage qui lève (source introuvable, config invalide) ⇒ échec global.
2. **`analyse(PipelineConfig)`** : sur une nouvelle copie des items retenus, résout et configure les `analyseSteps` et les `actionSteps`, puis, item par item, en séquence : les `doAnalyze`, puis le dry run des traitements (§4.2). Pas d'ordonnanceur de ressources ni de parallélisme. Une analyse qui lève sur un item ⇒ erreur notée pour cet item (ses métadonnées sont celles d'avant l'analyse fautive, pas de dry run des traitements), les autres continuent. Une étape impossible à résoudre ou à configurer ⇒ échec global.

Résultat `Sample` :

- `items` : `List<SampleItem>`, un par item **produit** (après dry run : un item dédoublé donne plusieurs `SampleItem`, un item filtré n'en donne aucun mais une note) — `SampleItem(String sourceName, String name, Map<String, String> display, Optional<String> error)` (`display` copié, immuable) ;
- `listed` : nombre d'items reçus du listage, `truncated` : listage coupé par un plafond ;
- `notes` : `List<String>` localisées (traitement sans dry run, item filtré par un traitement, échec de dry run, erreur d'analyse) ;
- `failure` : `Optional<String>`, l'échec global (message localisé) — les autres champs sont alors vides.

`cancel()` : interrompt l'opération en cours (le thread appelant reçoit un `Sample` en échec « annulé », jamais une exception). L'éditeur l'appelle à sa fermeture et avant de relancer.

Limite acceptée : le sampler lit sans passer par l'ordonnanceur de ressources (10 fichiers).

## 4. Dry run des traitements

### 4.1 API

```java
public interface IProcessAction extends IAction {
    List<WorkItem> doProcess(WorkItem item);

    /**
     * The expected effect of doProcess, without writing anything nor any heavy work.
     * Empty: this action does not support the dry run; an empty list: the item is filtered out.
     */
    default Optional<List<WorkItem>> dryRun(WorkItem item) {
        return Optional.empty();
    }
}
```

- Le moteur passe une **copie** (`WorkItem.copyForDryRun()` : même source, tables `raw` et `display` copiées). Le plugin peut la modifier (ex. `display.name = DSC_1.jpg`), en créer d'autres copies (dédoublement), ou rendre une liste vide (filtré). Le vrai item n'est jamais touché : `doProcess` tournera dessus à l'exécution.
- `Optional.empty()` signifie « ne gère pas le dry run », jamais « aucun effet ».
- Un `null` rendu est traité comme `Optional.empty()`.

### 4.2 Enchaînement (commun au sampler et à « Préparer »)

`DryRunner` (package `com.copybot.engine`) : à partir d'une copie de l'item, applique les `dryRun` des `actionSteps` en séquence, sur chaque item produit par l'étape précédente. Résultat, une **projection** :

- `Projected(List<WorkItem> items)` : un ou plusieurs items produits ;
- `Filtered(String action)` : une étape a rendu une liste vide (nom localisé de l'action) ;
- `Unsupported(String action, List<WorkItem> before)` : une étape ne gère pas le dry run ; `before` = les items d'avant cette étape ;
- `Failed(String action, String message)` : un `dryRun` a levé.

Sans `actionSteps`, la projection est `Projected([copie])`.

### 4.3 Dans « Préparer »

- À la fin de `MainExecutor.prepare()`, après la résolution de la reprise et avant le statut `PREPARED`, pour chaque item sans erreur (sélectionné ou non), le moteur calcule la projection (§4.2) et la stocke sur le `WorkItemExecution` (`getProjection()`). Une reprise manuelle choisie ensuite (`Plan.preview`) n'a rien à recalculer.
- Un échec de dry run ne met **pas** l'item en erreur (c'est un aperçu).
- `Plan.targetOf` devient `Plan.projectionOf(item) → TargetProjection`, sealed :
  - `Targets(List<Path> directories)` — répertoires (absolus, parents de `resolveTarget`) de chaque item produit ;
  - `Filtered(String action)` ;
  - `Unknown(String action)` — un traitement ne gère pas le dry run ;
  - `Failed(String message)` — échec de dry run, ou `resolveTarget` qui lève (clé absente : message `write.pattern.missing-key`) ;
  - `None` — pas d'étape de sortie, ou elle ne sait pas résoudre.
- Vue du plan, colonne cible : `Targets` ⇒ le premier répertoire, suivi de « (+N) » s'il y en a plusieurs, avec une infobulle qui les liste ; `Filtered` ⇒ « filtré par X » ; `Unknown` ⇒ « inconnue : X ne gère pas le dry run » ; `Failed` ⇒ le message ; `None` ⇒ vide.
- Les compteurs (`Plan.Counts`) ne changent pas : ils comptent les fichiers source.
- Inchangés : l'exécution (`doProcess` réel, la projection n'influence rien) ; la reprise `destination`, qui calcule la cible **avant** les traitements sur l'item réel (spec reprise §4) ; le format du `--dry-run` de la CLI.

## 5. Aide dans l'éditeur

Sous tout champ `@PatternField` (aujourd'hui `outPattern` de `file.write`), elle remplace la ligne de variables fixes.

De haut en bas :

1. **État** : « Lecture de l'échantillon… » (indicateur + bouton Annuler) ; « Échantillon : 10 fichiers sur 200 listés (listage coupé) » ; ou l'échec avec « Réessayer » et la liste fixe (`ConfigSchema.PATTERN_VARIABLES`, conservée pour ce secours).
2. **Notes** de l'échantillon (§3.2).
3. **Clés disponibles** : clé | jusqu'à 3 valeurs d'exemple distinctes | présence (« 8/10 »). Tri : présentes partout d'abord (ordre alphabétique), puis partielles (orange). Clic ⇒ insère `{clé}` à la position du curseur du champ (le champ reste le seul endroit où l'expression est écrite dans le document).
4. **Aperçu** : une ligne par `SampleItem` : nom → chemin résolu avec `OutPattern` sur son `display` ; une expression sans valeur en rouge suivie de l'effet de `onMissingKey` (valeur courante de l'étape) : « → élément en erreur » / « → ignoré » / « → laissé tel quel ». Un item en erreur d'analyse affiche son erreur. Expression invalide ⇒ erreur de syntaxe (position) sous le champ, aperçu masqué.

Liste et aperçu se recalculent à chaque frappe, en mémoire.

Relances, évaluées à chaque affichage du formulaire de l'étape de sortie :

- premier affichage ⇒ `list` puis `analyse`, en arrière-plan (l'éditeur reste utilisable) ;
- JSON des `inSteps` différent de celui de l'échantillon ⇒ `list` puis `analyse` ;
- seul le JSON des `analyseSteps` ou des `actionSteps` diffère ⇒ `analyse` seule ;
- rien n'a changé ⇒ rien ;
- fermeture de l'éditeur ⇒ `cancel()`.

Organisation, sur le modèle de `PlanViewModel` :

- `PatternHelperModel` (copybot-ui, `model`, testable sans JavaFX) : décision de relance à partir des JSON, agrégation des clés, lignes d'aperçu ;
- composant JavaFX `PatternHelper` : affichage seul ;
- `EditorController` possède la `SampleSession` et le thread d'arrière-plan ; `ConfigForm` reçoit, via son `Access`, de quoi construire l'aide pour un champ `PATTERN` (valeur courante de `onMissingKey` comprise).

Textes dans `uiBundle` (EN, FR, IT) ; messages moteur dans `engineBundle` (EN, FR — FR en ISO-8859-1 avec échappements `\uXXXX`).

## 6. Tests

- `OutPattern` : syntaxe valide (texte seul, clé, repli multiple, valeur fixe, apostrophe doublée, espaces), erreurs avec position, clé vide = absente, `missing`, `staticPrefix`, `keys`.
- `file.write` : `onMissingKey` `error` (échec, rien créé), `skip` (ignoré, raison), `literal` (comportement actuel), source jamais supprimée sur clé absente, défaut `error`, `resolveTarget` lève en `error` et `skip` ; reprise `destination` avec un item sans valeur : exclu de la sonde, préparation réussie ; tests existants verts.
- Dry run dans « Préparer » (faux traitements) : métadonnées / nom modifiés, dédoublement (plusieurs cibles), filtrage, sans dry run, échec (item pas en erreur), item réel intact, exécution qui appelle toujours `doProcess`, reprise `destination` calculée avant traitements, `preview` sans recalcul.
- `StepResolver` : tests existants de `MainExecutor` verts.
- `PipelineSampler` (faux plugins) : plafond 200, délai avec un plugin qui avale l'exception d'arrêt, sélection par extension, erreur d'analyse par item, échec global, `analyse` sans relister, annulation, dry run des traitements (dédoublement, filtré, non géré).
- `PatternHelperModel` : décisions de relance, agrégation (exemples, présence, tri), aperçu (rouge, effet `onMissingKey`), erreur de syntaxe.
- Bundles : nouvelles clés présentes dans toutes les langues (`UiBundleTest`, `SafeWriteBundleTest`).
- Vérification manuelle de l'éditeur par l'utilisateur (liste fournie à la fin).

## Hors périmètre

- Déclaration par les plugins des métadonnées produites.
- Réutilisation d'un plan préparé par l'éditeur, et de l'échantillon par « Préparer ».
- Nettoyage des valeurs de métadonnées contenant des séparateurs de chemin.
- Cibles projetées dans la sortie `--dry-run` de la CLI.
- Ordonnancement des ressources pour le sampler.

# Conflits annoncés par le plan : `conflictCheck`

Date : 2026-10-05
Statut : validé (décidé avec Steven)

## Contexte

Le plan calcule la cible de chaque fichier (spec pattern-helper §4.3) mais ne regarde jamais si elle existe : tout
fichier sélectionné est « À copier », et un conflit n'apparaît qu'à l'écriture (`ConflictResolver` de `file.write` :
identique → sauté, différent → renommé, selon `onConflict`). Le plan annonce désormais les fichiers dont la cible
existe déjà, avec un compteur et un filtre, selon un niveau réglable par pipeline.

## 1. Le champ `conflictCheck`

```json
"conflictCheck": "quick"
```

| Valeur | Ce que fait le plan | Coût |
|---|---|---|
| `none` | aucune vérification (comportement d'avant) | aucun |
| `quick` (défaut, champ absent) | existence et taille de la cible : « existe déjà, même taille » / « taille différente » | un accès disque par cible, sans lire de contenu |
| `full` | la comparaison de la sortie elle-même (`onConflict.compare` de `file.write`) : le plan dit exactement ce que fera la copie (sauté car identique, renommé en `x (1).jpg`, écrasé, erreur) | avec `partialHash`, ~128 Ko lus par cible existante (`fullHash` : tout) |

- `ConflictCheck` (moteur, `com.copybot.engine.pipeline`) : `NONE`, `QUICK`, `FULL` (`@SerializedName` `none`,
  `quick`, `full`) ; `PipelineConfig.conflictCheckMode()` donne `QUICK` quand le champ est absent.
- Valeur inconnue ou non textuelle refusée à la lecture du pipeline : `conflict-check.unknown` (comme
  `execution.unknown`).

## 2. Quand la vérification a lieu

Une cible n'est connue qu'une fois le fichier analysé (et ses traitements simulés) : la vérification suit
immédiatement la projection de la cible du fichier (`MainExecutor.projectEarly`, et `projectItems` pour les fichiers
projetés en fin de préparation), sur le fil du fichier, donc en parallèle des autres. Pas de passe supplémentaire.

- **Curseur** (`state`, `stateThenDestination` avec curseur) : les fichiers d'avant le curseur sont écartés au listing
  sans analyse, donc sans vérification ; ceux analysés pendant le listing sont ceux qui seront copiés.
- **Destination** (dichotomie paresseuse) : seuls les fichiers sondés puis ceux que le point sélectionne sont
  analysés, donc vérifiés. `everyFile` analyse tout, donc vérifie tout.
- **Sans reprise** : tout est sélectionné, tout est vérifié.
- **Point manuel** : les fichiers repris sont vérifiés lors de leur analyse différée (`analyseDeferred`). Un fichier
  vérifié puis écarté ne coûte qu'un accès.
- **Streaming**, `run()` sans reprise, exécution : pas de plan, pas de vérification (les conflits restent traités à
  l'écriture).

Le résultat est ce qu'on sait **au moment du plan** : une cible créée entre le plan et la copie est traitée à
l'écriture, comme avant.

## 3. API des plugins : `IOutAction.checkTarget`

```java
default TargetCheck checkTarget(WorkItem workItem, boolean compareContent)  // TargetCheck.UNKNOWN par défaut
```

- Sans effet de bord (rien n'est créé ni écrit), thread-safe, appelable pendant la copie d'autres éléments (comme
  `resolveTarget`). `compareContent` faux : `quick` ; vrai : `full`.
- Les plugins existants n'ont rien à faire : « je ne sais pas » (`UNKNOWN`), rien n'est annoncé.
- `TargetCheck(Kind kind, Path existing, String message)` (`com.copybot.plugin.api.action`) :
  - `UNKNOWN` (la sortie ne sait pas, ou la cible n'a pas pu être résolue), `FREE` (cible absente),
    `SAME_SIZE` / `DIFFERENT_SIZE` (`quick`), `IDENTICAL` / `DIFFERENT` (`full`) ;
  - `existing` : la cible existante (null pour `UNKNOWN` / `FREE`) ; `message` : ce que fera la copie, dans la langue
    courante, pour l'infobulle (null quand rien n'existe).
  - gravité, pour les fourches : `UNKNOWN` < `FREE` < `SAME_SIZE` = `IDENTICAL` < `DIFFERENT_SIZE` = `DIFFERENT`.

### `file.write`

- Cible résolue comme `resolveTarget` ; une clé manquante (hors `literal`) → `UNKNOWN` (la colonne cible dit déjà
  pourquoi).
- Absente → `FREE`. La cible est le fichier source lui-même → `IDENTICAL`, « déjà en place, jamais réécrit ».
- `quick` : un seul accès à la cible (existence, taille), la taille de la source venant de ses métadonnées. Un dossier
  à la place de la cible compte comme `DIFFERENT_SIZE`. Message : à taille égale, « comparé à la copie
  (`partialHash`) : sauté si identique, renommé sinon » (d'après `compare`, `ifIdentical`, `ifDifferent`) ; à taille
  différente, ce que fait `ifDifferent`.
- `full` : la même décision que la copie (`FileComparison.identical` puis la politique), sans rien écrire : `skip` →
  « sera sauté (identique) » / « sera sauté (existe déjà) », `overwrite` → « sera écrasé », `error` → « la copie
  échouera : la cible existe », `rename` → le nom que la copie prendrait (`ConflictResolver.resolve`, qui ne fait que
  lire).
- Une erreur d'accès pendant la vérification n'est jamais une erreur du fichier : `UNKNOWN`, journalisé en debug.

## 4. Moteur

- `MainExecutor` lit le niveau du pipeline (`QUICK` pour les exécuteurs de test sans configuration, réglable).
- Pour chaque fichier projeté (`Projection.Projected`), chaque élément produit est vérifié ; le plus grave est gardé
  dans `WorkItemExecution.getTargetCheck()` (null tant que rien n'est vérifié). Filtré, non simulable ou en échec :
  pas de vérification.
- Ressources : la vérification prend l'empreinte de l'étape de sortie pour ce fichier (`FootprintResolver` : son disque
  de destination, son `maxConcurrency`), le temps de l'accès ; une pause la retient, un arrêt l'interrompt.
- Une exception d'un plugin est attrapée : `UNKNOWN`.

## 5. Interface

### Vue plan

- Colonne statut d'un fichier sélectionné dont la cible existe : « À copier — existe déjà (même taille) » /
  « (taille différente) » / « (identique) » / « (différent) » ; l'infobulle donne le message de la sortie.
- Compteur, sous la bannière de reprise, tant que la copie n'a pas commencé (préparation, plan, analyse, préparation
  arrêtée) : « ⚠ 42 fichiers existent déjà à la destination (40 de même taille, 2 de taille différente) » en `quick`,
  « (40 identiques, 2 différents) » en `full`. Seuls les fichiers **sélectionnés** comptent ; caché à zéro.
- Filtre « Conflits » (après « Erreurs ») : les fichiers sélectionnés dont la cible existe.

### Éditeur (formulaire « Pipeline »)

- Liste « Conflits dans le plan » : aucune vérification / rapide (existence et taille) / complète (même comparaison
  que la copie). `PipelineDocument.conflictCheck()` / `setConflictCheck()` : `quick` (défaut) retire le membre.

### CLI

- `--dry-run` : la ligne d'un fichier à copier dont la cible existe porte le message de la sortie.

## 6. Textes

- Moteur (en, fr, ASCII avec `\u`) : `conflict-check.unknown`, `write.check.*`, `cli.plan.copy-existing`.
- Interface (en, fr, it) : `item.status.PENDING.exists.*`, `plan.conflicts.quick`, `plan.conflicts.full`,
  `plan.filter.CONFLICTS`, `editor.conflict-check` et ses trois valeurs.

## Hors périmètre

- Une cible apparue entre le plan et la copie : traitée à l'écriture.
- Le compteur ne couvre que les fichiers sélectionnés ; « Copier n fichiers » compte toujours tous les sélectionnés,
  y compris ceux qu'un `full` annonce sautés.
- Pas de vérification en streaming ni pendant l'exécution.

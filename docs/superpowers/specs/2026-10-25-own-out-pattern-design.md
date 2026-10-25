# Pattern de sortie propre à des fichiers choisis dans le plan

Date : 2026-10-25
Statut : validé (design)

## Contexte

En copiant des photos, l'utilisateur veut ranger à la main certains fichiers ailleurs que là où le pattern du
pipeline les envoie, par exemple tout un séjour sous `D:/Photos/vacances-dans-le-sud/{captureDate.Y}/{name}`.
Aujourd'hui la cible d'un fichier ne dépend que du pattern de `file.write` et des métadonnées du fichier
(`FileWriteAction.resolution`) ; la colonne Target du plan est en lecture seule.

But : dans la vue Plan, **après l'analyse**, sélectionner des fichiers et leur donner un **pattern de sortie
propre**, saisi dans une boîte préremplie avec le pattern du pipeline (on le modifie sans rater un morceau).
Ces fichiers sont alors projetés, vérifiés (conflits) et écrits avec ce pattern.

Décisions de l'utilisateur :

- dans le cas nominal rien ne change : pas de répertoire ni de variable en plus dans le pattern du pipeline ;
- la surcharge est le **pattern complet**, pas une variable (`{album}`) ;
- préremplie avec le pattern par défaut ; disponible seulement une fois le fichier analysé.

Non-régression : sans pattern propre, préparation, exécution, streaming, reprise et CLI sont strictement
identiques à aujourd'hui.

Hors périmètre : règles réutilisables par dossier (plugin « album »), commande CLI pour poser un pattern,
regroupement par dossier source dans le plan, autre out step que `file.write` (l'API le permet, §1).

## 1. API des plugins

- `WorkItem` : `Optional<String> getOutPattern()` / `setOutPattern(String)` (null = celui du pipeline).
  `copyForDryRun()` le recopie. Ce n'est pas une métadonnée : il n'apparaît pas dans les clés `{…}`.
- `IOutAction.defaultOutPattern()` : `Optional<String>`, par défaut vide. Le pattern que l'out step applique à un
  fichier sans pattern propre ; **vide = l'out step ne gère pas de pattern propre** (l'entrée de menu est absente).
  `file.write` rend son `outPattern`.
- `file.write` :
  - `resolution(item)` prend le pattern propre de l'item s'il en a un, sinon `settings.outPattern()` ; les patterns
    propres sont parsés une fois (cache par texte, `OutPattern` est immuable). `onMissingKey`, `onConflict`,
    `verify`… restent ceux du pipeline. Toutes les cibles (resolveTarget, checkTarget, write) passent par là.
  - `touchedPaths(item)` : le préfixe fixe (`staticPrefix()`) du pattern propre de l'item s'il en a un.
    `touchedPaths(null)` (fenêtre ressources, `pipelinePaths`) ne change pas.
  - `targetDirectoryVaries()` ne change pas (il parle du pattern du pipeline, voir §2 pour la reprise).
- Un pattern propre syntaxiquement invalide est refusé quand on le pose (§2) ou quand on lit le fichier d'état
  (modifiable à la main : `resume.state.invalid`), jamais à l'écriture.

## 2. Moteur

### Poser / retirer

`Plan.setOutPattern(Collection<WorkItemExecution> items, String pattern)` :

- `pattern` null, blanc ou égal à `defaultOutPattern()` = retirer le pattern propre ;
- refus `IllegalStateException` si le plan n'est pas PREPARED (ni préparation arrêtée après le listing), ou si
  l'out step ne gère pas de pattern propre ; `CopybotException` (message de syntaxe d'`OutPattern`) si invalide ;
- pose un pattern sur les items **analysés** seulement (ils ont une projection), les autres sont ignorés (l'UI ne
  les propose pas) ; le retrait vaut pour tous les items donnés, analysés ou non (un fichier restauré au listing
  mais non analysé, avant le curseur, doit pouvoir le perdre) ;
- pose le pattern sur `item.getWorkItem()` **et** sur les items projetés (`Projection.Projected.items()`), pour que
  `projectionOf` / `detailOf` le voient ;
- refait le contrôle de la cible (conflict-check) des items analysés quand le mode n'est pas `none`, comme à la
  préparation (`ItemRunner.checkTarget`, footprint `forTargets`) ; appel bloquant, hors thread JavaFX ;
- enregistre les patterns dans le fichier d'état (ci-dessous).

### Process steps

`ItemRunner` (exécution) et `DryRunner` : un item produit par `doProcess` / `dryRun` sans pattern propre
hérite de celui de l'item d'origine.

### Persistance : le fichier d'état

Les patterns propres doivent survivre à une nouvelle préparation, à une copie interrompue et à la reprise depuis la
destination (sinon la reprise chercherait les fichiers à l'emplacement par défaut et les y recopierait). Ils vont
dans le fichier d'état du pipeline, à côté du curseur :

```json
{ "cursor": { "date": "…", "name": "…" },
  "outPatterns": [ { "date": "2026-07-03T10:12:00Z", "name": "IMG_0001.JPG", "pattern": "D:/Photos/sud/{name}" } ] }
```

- clé = `ItemKey` (date de modification à la seconde + nom), celle de la reprise ; un fichier sans date n'a pas de
  clé : son pattern propre vaut pour le plan en mémoire seulement ; deux fichiers de même nom modifiés dans la même
  seconde partagent une clé, donc un pattern propre ;
- `ResumeStateStore` : `readOutPatterns()` / `writeOutPatterns(Map<ItemKey,String>)` ; `writeCursor` **conserve**
  `outPatterns` (et réciproquement), écriture atomique comme aujourd'hui ; fichier illisible = erreur de
  préparation, comme le curseur ; une écriture sur un fichier qui ne peut être lu échoue
  (`resume.state.write-error`) et le laisse tel quel, au lieu d'effacer les patterns propres ;
- utilisé **quel que soit le mode de reprise** (le fichier est créé au besoin) ;
- lu au début de `prepare()` / `stream()` ; au listing, un item dont la clé correspond reçoit son pattern avant
  toute analyse, projection, sonde ou contrôle de cible ;
- jamais purgé automatiquement (une carte recopiée plus tard doit retrouver les mêmes cibles) : seul « Revenir au
  pattern du pipeline » retire une entrée.

### Reprise

`ResumeResolver` : le raccourci « répertoire de destination fixe ⇒ resélectionner » (`everyFile` et
`checkDestination`) se décide fichier par fichier, selon le pattern qu'il suit : pour un pattern propre,
`OutPattern.parse(propre).directoryVaries()` ; s'il varie, le fichier est sondé sur son propre répertoire (présent ⇒
ignoré), s'il est fixe (`D:/sud/{name}`), il reste sélectionné avec l'avertissement, comme un répertoire fixe du
pipeline. Recherche depuis la fin (`lastFound`) : la garde « un seul répertoire » compare les fichiers qui suivent le
pattern du pipeline seulement, et un fichier à pattern propre de répertoire fixe n'est pas sondé (il ne fixe pas le
point). Les sondes passent déjà par `resolveTarget`, donc par le pattern propre.

Un fichier écarté par la décision de reprise garde cette décision quand il reçoit un pattern propre : le point de
reprise ne décide à nouveau qu'à la préparation suivante.

### CLI

Rien à faire : le CLI lit le fichier d'état, ses dry runs et ses exécutions appliquent les patterns propres posés
depuis l'UI.

## 3. UI (vue Plan)

- Menu contextuel, à la suite de « Ignorer… » (sur la sélection, avec le nombre comme les autres entrées) :
  - « Pattern de sortie… (n) » : les items sélectionnés analysés ; désactivé si occupé (`ops.busy()`), si
    l'out step ne gère pas de pattern propre, ou si n = 0 ;
  - « Revenir au pattern du pipeline (n) » : les items sélectionnés qui ont un pattern propre.
- Boîte « Pattern de sortie » :
  - champ prérempli : le pattern propre commun à la sélection s'ils en ont tous un identique, sinon
    `defaultOutPattern()` ; rappel du pattern du pipeline en dessous ;
  - bouton {+} : choix d'une clé parmi celles des métadonnées affichées des fichiers sélectionnés, insérée au curseur
    (réutiliser le sélecteur de `PatternHelper` / `PatternHelperModel.insert` autant que possible) ;
  - aperçu en direct : la cible de 3 fichiers de la sélection au plus, les clés manquantes (avec la conséquence
    `onMissingKey`) et l'erreur de syntaxe ; OK désactivé sur erreur de syntaxe ; aperçu et clés {+} portent sur
    le premier item projeté de chaque fichier (ce que `file.write` écrit, comme la colonne Target), sinon le fichier ;
  - OK : `setOutPattern` en tâche de fond (comme analyse/ignore), puis rafraîchissement des lignes (Target, statut
    de conflit, compteurs).
- Marquage : la cellule Target d'un item à pattern propre porte un petit signe (dessiné, pas un emoji) et une
  info-bulle « Pattern propre à ce fichier : … ».
- Libellés dans `uiBundle` en/fr/it (parité vérifiée par `UiBundleTest`), messages moteur dans les bundles moteur
  (échappés `\u`).

## 4. Tests

- `FileWriteAction` : cible, checkTarget, write et touchedPaths avec pattern propre ; sans, inchangés ; cache.
- `ResumeStateStore` : aller-retour des patterns, `writeCursor` les conserve, fichier ancien sans `outPatterns`.
- `Plan.setOutPattern` : pose/retrait, items projetés, refus (état, syntaxe, out step sans support), conflict-check
  refait, persistance ; une nouvelle préparation les retrouve.
- `ItemRunner` / `DryRunner` : héritage par les items produits.
- `ResumeResolver` : pattern du pipeline fixe + un item à pattern propre ⇒ pas de « tout resélectionner ».
- UI : le modèle de la boîte (préremplissage, aperçu, OK désactivé) et les comptes du menu, sans JavaFX.

## Laissé au builder

Noms exacts des classes UI, disposition de la boîte, forme du signe ; découpage en commits.

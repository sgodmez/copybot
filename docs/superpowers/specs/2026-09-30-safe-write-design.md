# Écriture sûre (`file.write`) et filtres de lecture (`file.read`)

Date : 2026-09-30
Statut : validé (design, brainstorming avec l'utilisateur)

## Contexte

`file.write` n'a qu'un `overwrite: true|false` : en `false`, un fichier cible existant fait échouer l'item ; l'écriture se fait directement sous le nom final (un crash laisse un fichier tronqué) ; rien n'est vérifié. La reprise après échec (spec reprise §7) retente des fichiers déjà copiés : il faut savoir reconnaître « déjà là et identique ». Côté lecture, `file.read` n'a ni filtre ni option `recursive` effective.

Décisions actées avec l'utilisateur : conflit à deux branches (identique / différent), comparaison configurable (`partialHash` par défaut), mode d'écriture configurable (`tempAndRename` par défaut, `direct` possible — crainte d'une mauvaise interaction avec l'indexation Synology lors d'un renommage), vérification configurable (`size` par défaut), suppression de la source optionnelle avec simple avertissement si la vérification n'est pas `readBack`. Fichiers liés (sidecars) : **pas** dans ce chantier.

## 1. Configuration `file.write`

```json
"actionConfig": {
  "outPattern": "//nas/photo/{captureDate.Y}-{captureDate.m}-{captureDate.D}/{name}",
  "onMissingKey": "error",
  "onConflict": { "compare": "partialHash", "ifIdentical": "skip", "ifDifferent": "rename" },
  "writeMode": "tempAndRename",
  "verify": "size",
  "deleteSource": false
}
```

Tous les champs sauf `outPattern` sont optionnels (défauts ci-dessus). L'ancien booléen `overwrite` est supprimé, sans compatibilité (le produit n'est pas encore sorti) : `onConflict` le remplace. Syntaxe de `outPattern` (repli `|`, valeurs fixes) et `onMissingKey` : voir la spec pattern-helper (2026-10-02).

## 2. Conflit : la cible existe déjà

`compare` :

| Valeur | « Identique » si |
|---|---|
| `size` | même taille |
| `sizeAndDate` | même taille et dates de modification à ±2 s (résolution FAT des cartes) |
| `partialHash` | même taille et même SHA-256 des 64 premiers Kio + 64 derniers Kio (tout le fichier s'il fait ≤ 128 Kio) |
| `fullHash` | même taille et même SHA-256 complet |

La taille est toujours comparée en premier (différente ⇒ différent, sans lire).

`ifIdentical` / `ifDifferent` : `skip` | `rename` | `overwrite` | `error`.

- `skip` : l'item n'est pas écrit ; il finit `SKIPPED` avec la raison « identique à la destination (<cible>) » ou « existe déjà à la destination (<cible>) ».
- `rename` : cible `nom (1).ext`, puis `(2)`… ; chaque candidat existant est lui-même comparé : s'il est identique, on applique `ifIdentical` (pas d'empilement de doublons).
- `overwrite` : remplace la cible.
- `error` : l'item finit `ERROR`.

Un item `SKIPPED` **pendant l'exécution** (il avait été sélectionné par le point de reprise) compte comme un **succès** pour l'avancement du curseur : `ResumeResolver.nextCursor` traite comme réussi tout item sélectionné qui finit `DONE` ou `SKIPPED`.

## 3. Mode d'écriture

- `tempAndRename` (défaut) : écriture dans `.<nom>.<runId>.copybot-tmp` (fichier caché, même répertoire ; au-delà de 255 octets, `<nom>` est raccourci en un préfixe suivi de `~` et d'une empreinte du nom complet), puis déplacement vers la cible (atomique si le système le permet, sinon remplacement). En cas d'échec, le temporaire est supprimé.
- `direct` : écriture sous le nom final. En cas d'échec, le fichier partiel est supprimé **s'il a été créé par cette écriture** (en `overwrite` direct, l'original est perdu dès le début de l'écriture — documenté dans le libellé de l'option).
- Temporaires orphelins (crash précédent) : la première fois qu'une exécution écrit dans un répertoire cible, les fichiers `.*.copybot-tmp` dont le `runId` n'est pas celui de l'exécution courante sont supprimés. Le `runId` est un identifiant unique par exécution, fourni par le moteur à l'action.
- Le déplacement existant (source temporaire ou « supprimer après », même système de fichiers) reste possible et reste le chemin le plus rapide.

## 4. Hash et vérification

- Le SHA-256 du contenu est calculé **au vol** pendant la copie et stocké dans les métadonnées de l'item (`raw["sha256"]`, hexadécimal).
- `verify` :
  - `none` : nombre d'octets écrits = taille source attendue ;
  - `size` (défaut) : taille du fichier final relue sur la destination ;
  - `readBack` : relecture complète de la cible et comparaison au hash calculé pendant la copie.
- Échec de vérification ⇒ la cible écrite est supprimée, l'item finit `ERROR`.

## 5. Suppression de la source

- `deleteSource: true` : après écriture **et** vérification réussies, la source est supprimée. Si l'item est `SKIPPED` pour cause de fichier identique, la source n'est supprimée que si `compare` vaut `fullHash`.
- Avec `verify` différent de `readBack` : **avertissement** (pas de blocage), visible dans le plan (CLI : ligne `Warning:` — `Avertissement :` en français — du dry-run et sur stderr au démarrage ; UI : bandeau).
- Échec de suppression ⇒ item `ERROR` (la copie est faite ; au passage suivant le fichier sera identique ⇒ skip ⇒ nouvelle tentative de suppression si `fullHash`).

## 6. API plugin

- `IOutAction` gagne `default WriteResult write(WorkItem item, WriteContext context)` ; l'implémentation par défaut appelle `writeItem(item)` et renvoie `WriteResult.written(null)`. Les plugins existants ne changent pas.
  - `record WriteResult(Outcome outcome, Path target, String reason)` avec `Outcome { WRITTEN, SKIPPED }` et fabriques `written(target)`, `skipped(target, reason)`.
  - `record WriteContext(String runId)`.
- `MainExecutor` appelle `write(...)` ; un `SKIPPED` termine l'item en `SKIPPED` avec la raison (les étapes suivantes éventuelles ne s'exécutent pas).
- `IAction` gagne `default List<String> configWarnings()` (vide) : les avertissements de configuration de chaque étape sont collectés à la résolution des étapes et exposés dans `PipelineState.getWarnings()` (et ajoutés aux avertissements affichés par le dry-run).

## 7. Filtres de lecture `file.read`

```json
"actionConfig": {
  "path": "E:/DCIM",
  "recursive": true,
  "include": ["**/*.NEF", "**/*.JPG", "**/*.MP4"],
  "exclude": ["**/*.tmp"],
  "includeHidden": false
}
```

- `recursive` (défaut `true`, désormais effectif) : `false` ⇒ seulement le premier niveau.
- `include` / `exclude` : globs (`PathMatcher` « glob: ») relatifs au répertoire `path`, insensibles à la casse ; `include` absent ⇒ tout ; `exclude` l'emporte.
- `includeHidden` (défaut `false`) : exclut les fichiers et répertoires cachés (nom commençant par `.`, attribut caché ou système Windows), `System Volume Information`, `$RECYCLE.BIN`, et les `._*` macOS.
- Rétro-compatibilité : les pipelines existants (`path` seul) listent désormais récursivement, comme le faisait l'implémentation actuelle (qui ignorait `recursive`).

## 8. Erreurs et i18n

Nouveaux messages dans `engineBundle.properties` / `engineBundle_fr.properties` (FR en ISO-8859-1 avec échappements `\uXXXX`, édition octet par octet) : raisons de skip, échec de vérification, échec de suppression, conflit `error`, avertissement `deleteSource` sans `readBack`.

## 9. Tests

- Conflits : chaque `compare` (dont la tolérance ±2 s), chaque politique, `rename` qui retrouve un identique en `(1)`.
- Écriture : `tempAndRename` ne laisse aucun temporaire ; échec simulé en cours de copie (flux qui lève) ⇒ ni temporaire ni cible ; `direct` supprime le partiel qu'il a créé ; orphelins d'un autre `runId` supprimés, ceux du run courant jamais.
- Vérification : `readBack` détecte une cible altérée (hook de test) ; `size` détecte une taille fausse.
- `deleteSource` : supprimée après succès ; conservée après échec ; skip identique + `fullHash` ⇒ supprimée, sinon conservée ; avertissement sans `readBack`.
- Moteur : un item `SKIPPED` à l'écriture fait avancer le curseur ; `configWarnings` remontés dans l'état et le dry-run.
- Lecture : `recursive: false`, include/exclude, cachés exclus par défaut.

## Hors périmètre

Fichiers liés / sidecars (décidé : plus tard), copie parallèle d'un même fichier, reprise d'une copie partielle au milieu d'un fichier.

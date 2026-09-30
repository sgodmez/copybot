# Reprise d'import : curseur et détection du point d'arrêt

Date : 2026-09-30
Statut : validé (design)

## Contexte

Cas d'usage cible : importer les photos d'une carte SD vers un NAS, rangées par date (`{captureDate.Y}-{captureDate.m}-{captureDate.D}/{name}`), en ne recopiant **que ce qui n'a pas encore été importé** — y compris quand des photos importées ont été supprimées du NAS depuis (tri a posteriori). La carte n'est pas formatée entre deux imports : elle accumule, on importe le delta.

Choix structurants actés :

- **Léger** : pas de base de données ni de journal par fichier. Un **curseur** (date de prise de vue + nom) par pipeline, dans un fichier d'état JSON à côté du pipeline.
- **Un curseur par pipeline**. Plusieurs boîtiers ⇒ plusieurs pipelines. Horloge d'un même matériel supposée cohérente dans le temps.
- La détection automatique ne fait que **proposer** un point de reprise ; l'utilisateur peut toujours le remplacer à la main (fichier de départ, date, tout).
- Écriture sûre, politique de conflit, transcodage, détection de carte, UI : **hors périmètre** (voir fin).

## 1. Clé d'ordre d'un item

`ItemKey(Instant date, String name)`, ordre : `date` (tronquée à la seconde) puis `name` (ordre lexicographique). Le nom ne sert qu'à départager les photos d'une même seconde (rafales) ; la numérotation `DSC_9999 → DSC_0001` n'est donc pas un problème.

Date de l'item, par convention de métadonnées (`WorkItemMetadata.raw`) :

1. `captureDate` (`Instant`) si une étape d'analyse l'a fourni (EXIF `DateTimeOriginal`, ou date de création vidéo) ;
2. sinon `lastModified` (`Instant`, posé par `file.read`) — sur une carte SD, c'est l'appareil qui l'a écrit.

Un item sans aucune des deux dates passe en `ERROR` (« aucune date pour la reprise ») au moment de la résolution : jamais ignoré silencieusement.

L'EXIF est une heure locale sans fuseau : elle est interprétée dans le fuseau système (`TimeZone.getDefault()`), comme le fait l'OS pour les dates FAT. Seule la cohérence entre imports compte.

## 2. Modes de reprise

Configuration au niveau du pipeline :

```json
"resume": { "mode": "stateThenDestination" }
```

| Mode | Point de reprise proposé |
|---|---|
| `none` | aucun : tout est sélectionné (comportement historique) |
| `state` | après le curseur du fichier d'état ; pas de fichier d'état ⇒ tout |
| `destination` | après le dernier item dont le **répertoire cible existe** (§4) |
| `stateThenDestination` | `state` si le fichier d'état contient un curseur, sinon `destination` |

- Bloc `resume` **absent** ⇒ `none` (rétro-compatibilité : les pipelines existants ne changent pas de comportement et n'écrivent aucun fichier d'état).
- Bloc `resume` présent sans `mode` ⇒ `stateThenDestination` (défaut recommandé).
- `mode` inconnu (ex. faute de frappe `"stat"`) ⇒ erreur explicite au chargement du pipeline (`resume.mode.unknown`), jamais le mode par défaut.

## 3. Fichier d'état

`<nom du pipeline sans extension>.state.json`, dans le même répertoire que le pipeline JSON. Lisible et éditable à la main :

```json
{ "cursor": { "date": "2026-09-28T15:42:10Z", "name": "DSC_4821.NEF" } }
```

- Fichier absent ⇒ pas de curseur. Fichier illisible / JSON invalide ⇒ **erreur explicite** (on ne repart pas silencieusement de zéro, ce qui recopierait tout).
- Écriture atomique : fichier temporaire dans le même répertoire puis `move` avec remplacement.

## 4. Mode `destination` (par répertoire)

Principe : un item est considéré importé si **le répertoire parent de son chemin cible existe**. Cohérent avec un rangement « un répertoire par jour » : supprimer des photos en fin de série sur le NAS ne fait pas reculer la reprise, tant que le répertoire du jour existe.

- Le chemin cible est demandé à l'étape de sortie via une nouvelle méthode optionnelle de l'API plugin : `IOutAction.resolveTarget(WorkItem) → Optional<Path>` (défaut : vide). `file.write` l'implémente avec la même résolution de pattern que l'écriture.
- Les items sont triés par `ItemKey` ; on cherche par **dichotomie** le dernier item dont le répertoire cible existe (hypothèse : la destination est « pleine jusqu'à un point » ; les trous ne sont pas détectés — c'est une heuristique de reprise, pas une synchronisation). Point de reprise = après cet item ; aucun ⇒ tout.
- Garde-fou : si tous les items (≥ 2) ont le **même** répertoire cible, le pattern n'a pas de répertoire variable ⇒ le mode ne s'applique pas : tout est sélectionné et un **avertissement** est émis.
- Sortie sans `resolveTarget` : mode `destination` explicite ⇒ erreur ; mode `stateThenDestination` ⇒ tout sélectionné + avertissement.
- Le chemin cible est calculé sur l'item **avant** les `actionSteps` (un futur transcodage peut changer l'extension, pas le répertoire daté).

Limites assumées, à afficher : une deuxième copie le même jour est ignorée (reprise manuelle) ; un répertoire du dernier jour entièrement supprimé est recopié.

## 5. Point de reprise et sélection

`ResumePoint`, trois formes :

- `after(key)` — sélectionne les items de clé **strictement supérieure** (curseur d'état, destination) ;
- `from(key)` — sélectionne les items de clé **supérieure ou égale** (choix manuel d'un fichier ou d'une date) ;
- `all()` — tout.

La proposition (`ResumeProposal`) porte : le point, sa **source** (`STATE`, `DESTINATION`, `NONE`), et une liste d'avertissements. Un point choisi à la main a la source `MANUAL`.

Application : chaque item préparé sans erreur est soit sélectionné (reste `PENDING`), soit `SKIPPED` avec une raison lisible, ex. « déjà importé (curseur : DSC_4821.NEF, 28/09/2026 17:42:10) » / « répertoire cible existant (…) ».

Surcharge manuelle :

- CLI, options mutuellement exclusives : `--from-file <nom>` (⇒ `from(clé de ce fichier)`, erreur si aucun item listé ne porte ce nom), `--from-date <AAAA-MM-JJ>` (⇒ `from(début de ce jour, fuseau système, nom vide)`), `--all`.
- API : le plan expose les items ordonnés ; l'appelant (future UI) peut construire un `from(key)` à partir de n'importe quel item.

## 6. Préparation / exécution et barrière

Le pipeline gagne une **barrière** entre `analyseSteps` et `actionSteps` :

```
inSteps → analyseSteps ──[ barrière : résolution de la reprise ]──→ actionSteps → outStep
```

- La barrière est active dès que `resume.mode ≠ none`, ou quand on demande une préparation seule (dry-run). Sans barrière, le comportement actuel est inchangé (y compris `startProcessingWhileListing` qui chevauche traitement et listing).
- Avec barrière, `startProcessingWhileListing` s'applique à la phase de préparation (analyses pendant le listing).
- **Préparation** : listings + analyses de tous les items ; à la fin, les items sans erreur repassent `PENDING`, le pipeline passe au nouveau statut `PREPARED`, la proposition de reprise est calculée et appliquée. Le résultat est un **plan** (`Plan`) : l'état du pipeline (items et statuts), la proposition, les items ordonnés.
- **Exécution** : `execute(plan, reprise choisie ou null)` — si une reprise manuelle est fournie, elle est réappliquée (les statuts `SKIPPED`/`PENDING` sont recalculés) ; puis seuls les items `PENDING` exécutent les étapes restantes (`actionSteps`, `outStep`). Les traitements coûteux ne tournent jamais sur un item ignoré.
- API moteur (`CopybotEngine`, reste statique dans cette itération) :
  - `Plan prepare(Path pipeline, Consumer<PipelineState> watcher)` — bloquant ;
  - `void execute(Plan plan, ResumePoint override)` — asynchrone, joint par `waitForCompletion()` ;
  - `run(Path, watcher)` conservé = préparation + exécution avec la reprise proposée.
- CLI : `--dry-run` (déjà déclaré, jusqu'ici ignoré) = préparation seule ; affiche le point de reprise, sa source, les avertissements, puis une ligne par item (`COPY` / `SKIP <raison>` / `ERROR <message>`). Aucun fichier d'état écrit.

`SKIPPED` ne compte pas comme un échec : statut final `SUCCESS` si aucun item n'est en `ERROR` et aucun listing n'a échoué.

## 7. Avancement du curseur

Uniquement **à la toute fin** d'une exécution qui se termine normalement (pas en dry-run, pas si `mode = none`) :

1. On considère les items **sélectionnés** pour cette exécution (y compris ceux qui ont échoué pendant la préparation, dès lors qu'ils ont une clé et auraient été sélectionnés), triés par clé.
2. On prend le plus long préfixe d'items `DONE` ; `candidat` = clé du dernier item de ce préfixe (aucun ⇒ pas de candidat). Tout item après le premier échec reste donc « à importer » au prochain passage.
3. `point automatique` = le point proposé s'il vient de `STATE` ou `DESTINATION` (forme `after`) ; aucun pour `MANUAL` et `NONE`.
4. Nouveau curseur = `max(ancien curseur d'état, point automatique, candidat)` en ignorant les termes absents ; écrit seulement s'il existe et diffère de l'ancien. Le curseur ne recule jamais (une réimportation manuelle d'anciennes photos ne le fait pas régresser).

Exécution annulée (interruption) ou plantée (exception hors item) : curseur inchangé.

Conséquence : après un échec, la reprise retente des fichiers qui ont pu être déjà copiés (entre le premier échec et la fin). Cela suppose une politique de conflit « ignorer si identique » dans le module d'écriture — **hors périmètre**, voir la dernière section.

## 8. Métadonnées : corrections nécessaires

- `WorkItemMetadata.setTime(key, time)` stocke aujourd'hui `raw.put("key", time)` (clé littérale) : corrigé en `raw.put(key, time)` ; ajout d'un accesseur `getTime(key) → Optional<Instant>`.
- `ExtractMetadata` (plugin metadata-extractor) n'alimente rien (il affiche les tags sur la sortie standard) : il pose `captureDate` depuis `ExifSubIFDDirectory.getDateOriginal(TimeZone.getDefault())`, à défaut depuis la date de création QuickTime / MP4 (déjà en UTC), et n'écrit plus sur la sortie standard. Fichier sans date exploitable : pas de `captureDate` (repli sur `lastModified`), pas d'erreur.
- `FileReadAction` : suppression du `Thread.sleep(5)` de debug par fichier (pénalisant sur une carte de milliers de fichiers).

## 9. Composants

Nouveau package `com.copybot.engine.resume` :

| Composant | Rôle |
|---|---|
| `ItemKey` | clé d'ordre, extraction depuis un `WorkItem` |
| `ResumeMode`, `ResumeConfig` | configuration pipeline (`PipelineConfig.resume`) |
| `ResumePoint`, `ResumeSource`, `ResumeProposal` | point de reprise, provenance, avertissements |
| `ResumeStateStore` | lecture / écriture atomique du fichier d'état |
| `DestinationProbe` | dichotomie « dernier item dont le répertoire cible existe » + garde-fou |
| `ResumeResolver` | calcul de la proposition selon le mode, application (SKIPPED/raisons), calcul du nouveau curseur |

Modifiés : `MainExecutor` (phases préparation/exécution, barrière), `CopybotEngine` (`prepare`/`execute`, `Plan`), `Copybot` (CLI), `PipelineConfig`, `PipelineStatus` (+`PREPARED`), `ItemStatus` (+`SKIPPED`), `WorkItemExecution` (`setSkipped(reason)`, `getSkipReason()`, retour à `PENDING`), `IOutAction` (+`resolveTarget` par défaut), `FileWriteAction`, `WorkItemMetadata`, `FileReadAction`, `ExtractMetadata`. Messages (raisons, avertissements, erreurs) dans `engineBundle.properties` / `engineBundle_fr.properties`.

## 10. Erreurs

- Fichier d'état invalide ⇒ la préparation échoue (statut `ERROR`, message explicite avec le chemin).
- `--from-file` inconnu ⇒ erreur explicite avant exécution.
- Mode `destination` explicite sans `resolveTarget` ⇒ erreur de préparation.
- Item sans date ⇒ item `ERROR`, les autres continuent.
- Échec d'écriture du fichier d'état en fin d'exécution ⇒ statut `ERROR` avec message (les fichiers sont copiés, mais la prochaine reprise ne le saura pas).

## 11. Tests

- **Unitaires** : ordre `ItemKey` (seconde puis nom, troncature) ; sélection `after`/`from`/`all` ; `ResumeStateStore` (absent, aller-retour, JSON invalide ⇒ erreur, écriture atomique) ; `DestinationProbe` (rien d'importé, tout, point intermédiaire, garde-fou répertoire unique) ; `ResumeResolver` (chaque mode, repli `stateThenDestination`, calcul du curseur : tout OK, échec au milieu, aucun succès, reprise manuelle en arrière sans régression) ; `WorkItemMetadata.setTime`/`getTime`.
- **`MainExecutor`** : avec barrière, les analyses tournent pour tous les items et les étapes post-barrière seulement pour les items sélectionnés ; sans barrière, comportement inchangé (tests existants verts) ; préparation seule n'exécute aucune étape post-barrière.
- **Bout en bout (CLI)** : pipeline `state` sur un répertoire temporaire avec dates de modification fixées — 1er run copie tout et écrit le curseur ; ajout d'un fichier plus récent ⇒ 2e run ne copie que lui ; suppression d'un fichier copié ⇒ 3e run ne recopie rien ; `--all` recopie ; `--dry-run` n'écrit rien. Pipeline `destination` avec pattern daté : reprise après le dernier jour présent.
- **Plugin EXIF** : mapping métadonnées → `captureDate` testé sur un objet `Metadata` construit en mémoire.

## Hors périmètre → suite

- **Module d'écriture** : politique de conflit (`skipIfIdentical` taille/hash, renommer, écraser), copie vers temporaire + renommage, vérification de hash. Prérequis pratique de la reprise après échec (§7).
- **UI** : écran « dernier pipeline », vue du plan groupée par répertoire cible, choix du fichier de reprise, bouton d'exécution / exécution automatique.
- `CopybotEngine` en instance (non statique), notion de profil.
- Transcodage, détection d'insertion de carte.

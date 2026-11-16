# Regroupements et dépliage des archives — conception

## Besoin

Deux manques du listage, discutés et tranchés avec Steven le 2026-10-30 :

1. **Fichiers compagnons.** Chaque fichier traverse aujourd'hui le pipeline seul. Un `.xmp`, un `.AAE`, un `.THM` n'a pas
   d'EXIF : il est classé par sa date de modification et peut atterrir dans un autre répertoire que sa photo. Une page
   web enregistrée (`page.html` + `page_files/`) est éclatée. Seul cas traité, en dur : `ExtractMetadata` lit le XML
   Sony à côté d'une vidéo (`sonySidecarOffset`, chemin local obligatoire).
2. **Archives.** Un ZIP est copié tel quel. Un export Google Takeout (photos + un JSON par photo, découpé en
   `takeout-001.zip`, `takeout-002.zip`…) est inexploitable ; un ZIP posé sur un FTP ou un S3 (plugins à venir) aussi.

Le cœur gagne deux fonctions du **listage**, réglées par pipeline, qui s'appliquent à toutes les sources (fichiers,
et demain FTP, SFTP, S3) :

- **Regroupements** : rattacher des compagnons à un fichier principal, qui les emporte à travers le pipeline ;
- **Dépliage des archives** : remplacer une archive listée par les fichiers qu'elle contient, listés comme des
  fichiers ordinaires (regroupés, analysés, planifiés, vérifiés pour les conflits).

## Décisions de Steven

- Le regroupement est une étape **du cœur**, placée **uniquement juste après le listage** : pas une action de plugin,
  pas dans l'analyse (elle traite un fichier à la fois et est différée avant le curseur), pas déplaçable entre deux
  actions. Les cas plus complexes relèveront d'autres idées (« sorties secondaires », hors de cette spec).
- Modèle « pièces jointes » : les compagnons sont attachés au principal ; une ligne de plan par groupe.
- Compagnon sans principal ⇒ fichier ordinaire, quel que soit son rôle.
- Une page HTML dont le principal est renommé : le dossier associé suit, les liens internes ne sont **pas** réécrits
  (limite assumée ; un plugin dédié le jour où il y a un besoin).
- Le dépliage se fait **au listage** (pas en étape de traitement : ses entrées passeraient après l'analyse) ; il n'y
  a donc plus d'option « lire pendant Préparer ». Option « ne pas déplier » = l'archive est copiée telle quelle.
- Archives imbriquées : **option dédiée**, décochée par défaut ; profondeur maximale **5**.
- Anti zip bomb : profondeur (ci-dessus), détection des entrées qui se chevauchent, taux de compression maximal **×100**
  par entrée, place disque vérifiée. **Pas** de limite de nombre de fichiers, de taille de fichier ni de taille totale
  (les valeurs ClamAV bloqueraient des vidéos et des Takeout ordinaires).
- Takeout découpé : option « fusionner les archives d'un même dossier ».

Non-objectifs : réécriture de liens HTML ; regroupement après une étape de traitement ; formats autres que ZIP en v1
(7z, tar.gz — Takeout propose aussi `.tgz` — viendront de plugins) ; lecture partielle d'une archive distante
(lister via le répertoire central par requêtes de plage) : optimisation ultérieure ; regroupement par contenu
(rafales, doublons).

## Où ça se place

Aujourd'hui (`MainExecutor`) : chaque étape d'entrée tourne dans sa tâche (`runListing`, plusieurs entrées en
parallèle) et appelle `listFiles(this::emitItem)` ; `emitItem` applique motif propre et correction de fuseau, crée la
`WorkItemExecution`, décide du report d'analyse avant le point de reprise (`deferAtTheListing`, clé `ItemKey` gelée),
puis soumet l'élément.

Nouveau : entre `listFiles` et `emitItem`, **par étape d'entrée**, un tuyau du cœur :

```
listFiles ─► dépliage des archives ─► regroupement ─► emitItem (inchangé)
```

- Dépliage d'abord : les entrées d'une archive passent par le regroupement (Takeout : photo + JSON).
- Le regroupement ne mélange jamais deux étapes d'entrée (deux sources) ; il travaille par **dossier** d'une source.
- Pipeline sans regroupement ni dépliage : le tuyau est absent, rien ne change (ni attente, ni coût).

## API (plugin API 1.1 → 1.2, ajouts seulement)

### Fin de dossier

`IInAction` gagne une variante de listage qui signale la fin d'un dossier :

```java
interface ListingSink { void item(WorkItem item); void folderDone(String folder); }
default void listFiles(ListingSink sink) { listFiles(sink::item); }   // sans signal
default boolean signalsFolders() { return false; }
```

`folder` est l'identifiant du dossier dans la source (chemin local, chemin distant), le même que le **dossier parent**
que le cœur lit sur chaque élément (nouveau `WorkItem.getListedFolder()`, rempli par les constructeurs : parent du
`Path`, ou fourni pour une URL). Le signal promet que tout ce qui est sous ce dossier, **sous-dossiers compris**, a été
émis.

`FileReadAction` doit l'ajouter (il ne surcharge pas `postVisitDirectory` aujourd'hui) : signal dans
`postVisitDirectory` de son `walkFileTree` (un dossier est terminé quand son `postVisit` arrive, après ses
sous-dossiers). En mode non récursif (profondeur 1), seul le dossier racine est signalé, à la fin du parcours ; les
sous-arbres sautés (cachés, exclus : `SKIP_SUBTREE`) n'ont rien émis et n'ont pas besoin de signal. Une règle `folder`
(`page_files/`) ne peut rien rattacher en non récursif : l'éditeur le signale. Une source qui ne le signale pas : le regroupement attend la fin de son
listage (correct en préparation, où le listage complet précède déjà les étapes). En continu avec des règles, les
fichiers sont traités dossier par dossier (avertissement d'information) ; une entrée qui ne signale pas ses dossiers
fait replier l'exécution en mode automatique (préparer puis copier sans confirmation), avec un avertissement qui le
dit — l'éditeur affiche le texte correspondant à côté de l'option.

### Compagnons sur le WorkItem

```java
record Companion(WorkItem item, CompanionRole role, String suffix) {}   // suffix : voir « Cibles »
enum CompanionRole { COPY, READ }        // IGNORE ne produit pas de compagnon : le fichier est écarté
List<Companion> WorkItem.getCompanions() // vide par défaut, copié par copyForDryRun / withContent*
```

- L'analyse et les traitements reçoivent le principal ; un plugin d'analyse peut lire un compagnon `READ` ou `COPY`
  (`companion.item().openInputStream()`), ce qui marche aussi dans une archive ou sur un FTP. `ExtractMetadata` passera
  par là pour le XML Sony (avec repli sur la lecture locale actuelle) — adaptation du plugin, hors de cette spec.
- Un traitement qui produit un nouveau contenu (`withContent*`) garde les compagnons ; un traitement qui forke décide
  pour ses enfants (par défaut, chaque enfant les garde — les compagnons suivent tous les produits du principal) ; il
  peut les retirer avec `WorkItem.withoutCompanions()` (copie sans compagnons). *Choix de conception.*

### Éléments qui ne sont pas des fichiers locaux

Le seul constructeur hors fichier, `WorkItem(URL, Supplier)`, laisse le nom vide (clé de reprise au nom `""`, pas de
`name` pour les motifs) ; `withContent` copie un élément existant en gardant son chemin source et `isLocal` (une entrée
dérivée de l'archive ferait supprimer l'archive par `deleteSource` dès la première entrée, et comparer l'entrée à
l'archive dans `ConflictResolver.isSource`). L'API 1.2 ajoute donc une **fabrique** pour les sources non locales,
utilisée par le dépliage et par les futurs plugins FTP/SFTP/S3 :

```java
static WorkItem remote(String name, String listedFolder, String sourceDisplay, long size,
                       Instant lastModified, Supplier<InputStream> content)
```

non locale (`getLocalLocation()` nul : jamais supprimée par `deleteSource`, jamais « la source » d'un conflit), qui
remplit `nameDisplay`, la métadonnée d'affichage `name`, la taille et `LAST_MODIFIED` comme `FileReadAction.workItemOf`
le fait pour un fichier. La suppression de la source d'un élément non local passe par son action d'entrée (hors de
cette spec, sauf l'archive : voir « Déplacement »).

### Écriture des compagnons

`IOutAction` gagne :

```java
default boolean writesCompanions() { return false; }
```

Une action de sortie qui renvoie `true` écrit elle-même les compagnons `COPY` de l'élément, **après** le principal et
à partir de sa cible finale (après renommage éventuel) ; `FileWriteAction` le fait (dans `doWrite`, après
`conflicts.resolve`, même `SafeFileWriter`, même suppression de source en mode déplacement). Un pipeline dont un
regroupement produit des compagnons `COPY` et dont la sortie ne les écrit pas est **refusé à la validation**
(message : la sortie X ne sait pas écrire les compagnons). Un pipeline sans sortie (analyse seule) n'est pas refusé.
Les futurs plugins FTP/SFTP/S3 les écriront.

## Regroupements

### Configuration

Bloc facultatif du pipeline (Gson ignore les champs inconnus : un ancien Copybot lit le pipeline sans regrouper ;
absent ⇒ pas de regroupement) :

```json
"grouping": {
  "rules": [
    { "preset": "raw-jpeg-xmp" },
    { "type": "sameBase", "main": ["cr3", "nef", "arw", "jpg"], "companions": { "xmp": "copy", "thm": "copy" } },
    { "type": "doubleExtension", "companions": { "xmp": "copy" } },
    { "type": "folder", "main": ["html", "htm"], "folder": "{base}_files", "role": "copy" },
    { "type": "regex", "main": "(.+\\.(?:jpe?g|heic|mp4))", "companion": "\\1(?:\\.supplemental-metadata)?\\.json",
      "role": "read" }
  ]
}
```

Comparaisons de noms et d'extensions **sans casse**. Les règles s'appliquent dans l'ordre ; un fichier déjà rattaché
n'est plus candidat. Un groupe a **un seul niveau** (un compagnon n'a pas de compagnons).

### Types de règles

| Type | Rattache | Principal |
|---|---|---|
| `sameBase` | même dossier, même nom sans extension (`IMG_1.CR3`, `IMG_1.xmp`) | le premier présent dans la liste `main` (priorité) |
| `doubleExtension` | `IMG_1.CR3.xmp` à `IMG_1.CR3` | le fichier au nom sans la dernière extension |
| `folder` | tout le contenu de `<base>_files/` (récursif) à `<base>.html` | le fichier de la liste `main` |
| `regex` | expression sur le nom du compagnon, références aux groupes du principal | celui que l'expression du principal capture |

Rôles : `copy` (écrit à côté du principal), `read` (lisible par l'analyse, jamais écrit), `ignore` (écarté : ni copié
ni ligne de plan).

Plusieurs principaux possibles pour un même compagnon (un `IMG_1.xmp` à côté de `IMG_1.CR3` **et** `IMG_1.JPG`) : il
va au premier selon la priorité `main` ; RAW+JPEG eux-mêmes ne sont regroupés que si une règle le dit (préréglage
`raw-jpeg-xmp` : JPEG compagnon `copy` du RAW).

### Préréglages

Livrés par le cœur, choisis dans l'éditeur : `raw-jpeg-xmp` (RAW principal, JPEG et XMP copiés), `iphone` (HEIC/JPG
principal, AAE copié, MOV de Live Photo copié), `action-cam` (MP4 principal, LRV et THM copiés), `sony-xml` (MP4
principal, `<base>M01.XML` en lecture), `web-page` (`folder`).

> Note du 2026-10-30 : le préréglage `takeout` (`regex` JSON en lecture, qui activait la fusion des archives du
> dossier ; les noms tronqués à 46 caractères n'étaient pas tous couverts) a été retiré du cœur : Takeout deviendra un
> plugin dédié. L'option `mergeFolder` reste, générique (archives découpées en parties).

### Moment du regroupement

Les éléments d'une source sont gardés **par dossier** jusqu'à `folderDone` de ce dossier (ou la fin du listage), puis
regroupés et émis. Pour une règle `folder`, un dossier `<base>_files` n'est pas émis à sa propre fin : il attend la fin
de son dossier parent, où `<base>.html` est décidé. Les éléments non rattachés sont émis tels quels.

### Ce que le groupe partage

- **Clé de reprise, ordre, report d'analyse, motif propre, correction de fuseau** : ceux du principal (les compagnons
  n'ont pas d'exécution à eux, `emitItem` ne voit que le principal).
- **Conflits : groupe solidaire** (décision de Steven). Le plan (`checkTarget`) et l'écriture comparent chaque membre
  `COPY` à sa cible ; le groupe prend le résultat **le plus grave** (comme pour les forks), et les règles « si
  identique / si différent » du pipeline s'appliquent au **groupe entier** : ignorer ⇒ tout le groupe ignoré, écraser ⇒
  tous écrasés, erreur ⇒ groupe en erreur, renommer ⇒ tout le groupe vers le premier `(n)` libre **pour tous ses
  membres** (`IMG_1 (1).CR3` + `IMG_1 (1).xmp`). Le plan et le détail disent quel membre cause le conflit. Conséquence
  assumée : un xmp retouché ailleurs avec une photo identique rend le groupe « différent » (renommer duplique la photo,
  ignorer laisse le xmp de la destination). Un compagnon **absent** de la destination est écrit, même quand le principal est ignoré parce qu'identique (un compagnon en échec au run précédent
  est ainsi rattrapé). *Choix de conception.*
- **Erreur** : un compagnon dont l'écriture échoue met le groupe en erreur (le curseur s'arrête avant, comme pour un
  fork en échec) ; le principal déjà écrit reste.
- **Déplacement** : **aucune** source du groupe n'est supprimée avant que le principal et tous ses compagnons `COPY`
  soient écrits et vérifiés ; ensuite les sources du principal et des `COPY` sont supprimées (mêmes conditions
  qu'aujourd'hui pour un principal ignoré parce qu'identique) ; les `READ` ne le sont jamais. Groupe en erreur ⇒
  rien n'est supprimé. *Choix de conception (le code actuel supprime la source juste après l'écriture).*

### Cibles des compagnons

Le `suffix` d'un compagnon est la partie de son nom qui suit le nom de référence du principal, calculé au regroupement :
`sameBase` ⇒ suffixe après le nom sans extension (`.xmp`, `M01.XML`) ; `doubleExtension` ⇒ après le nom complet
(`.xmp`) ; `regex` ⇒ ce qui suit le texte capturé. À l'écriture : cible du compagnon = dossier de la cible finale du
principal + (nom final du principal **sans son extension** pour `sameBase`, **complet** pour `doubleExtension`/`regex`)
+ `suffix`. Le principal transformé (`.MOV` → `.mp4`) garde donc ses compagnons sous son nouveau nom. Règle `folder` :
`<nom final sans extension>_files/` + chemin relatif d'origine.

## Dépliage des archives

### Configuration

```json
"archives": { "unfold": true, "nested": false, "mergeFolder": false }
```

Absent ⇒ pas de dépliage. Reconnaissance par l'extension (`.zip`, sans casse) puis la signature ; un fichier `.zip`
illisible devient un élément **en erreur** (message : archive illisible, chiffrée…) et rien n'est déplié.

Le filtrage de l'entrée (motifs inclure/exclure de `FileReadAction`) s'applique à l'archive elle-même : l'utilisateur
doit laisser passer `*.zip`. Les entrées dépliées ne repassent pas par ces motifs (le cœur ne les connaît pas) ;
le bloc peut porter ses propres `include`/`exclude` (globs sur le chemin dans l'archive, défaut : tout).

### Entrées

Chaque entrée devient un `WorkItem` ordinaire :

- nom = dernier segment du chemin dans l'archive (jamais de `..` ni de séparateur : pas d'échappement de dossier) ;
- dossier listé = `<archive>!/<dossier dans l'archive>` (pour le regroupement, l'affichage et `folderDone`) ;
- date de modification = l'horodatage étendu de l'entrée (NTFS ou Unix, en UTC) quand il existe, sinon l'heure DOS
  interprétée dans le fuseau du système (ce que fait `ZipEntry.getLastModifiedTime`) ; la correction de fuseau existante
  s'applique ensuite comme pour un fichier. Taille = taille décompressée ; contenu = flux de l'entrée, rouvert à chaque
  lecture ; élément créé par `WorkItem.remote` (ci-dessus). *Choix de conception.*
- clé de reprise = `ItemKey(date de l'entrée, nom)`, comme un fichier (pas de chemin dans la clé : même limite qu'aujourd'hui
  pour deux homonymes de même seconde). Les entrées Takeout portent souvent la date de l'export : l'ordre de reprise
  suit alors les noms ; la date de prise de vue vient de l'analyse (EXIF, JSON `read`).

L'archive elle-même n'est pas une ligne de plan quand elle est dépliée ; le plan montre ses entrées avec leur
emplacement `archive.zip!/dossier`. En mode déplacement, l'archive source n'est supprimée que si **chacune** de ses
entrées a été, **dans ce run**, écrite ou trouvée identique à la destination (principal ou compagnon `COPY`). Toute
autre entrée la garde : écartée par `include`/`exclude`, compagnon `READ` ou `ignore`, filtrée par un traitement,
reportée par le point de reprise, en erreur, archive imbriquée copiée telle quelle… L'archive gardée donne un
avertissement (« archive conservée : N entrées non copiées »). Aucun état entre deux runs : un run repris ne supprime
l'archive que s'il en a lui-même tout copié. Le suivi est fait par le cœur (compteur par archive). *Choix de
conception : en cas de doute, on garde.*

Un dossier `folderDone` de l'archive est émis à la fin de chaque dossier interne ; la fin de l'archive termine tout son
contenu.

### Archives distantes ou dans une archive

`ZipFile` demande un accès aléatoire : une archive qui n'est pas un fichier local (FTP, S3, entrée d'archive) est
d'abord **copiée en local**, une fois, et réutilisée jusqu'à la copie. Dossier de travail comme `FileBufferAction` :
`java.io.tmpdir/copybot-archives/run-<id>/`. Sa durée de vie est celle de l'exécution (préparation **et** copie,
`MainExecutor`) : supprimé à la fin de la copie, à l'annulation, ou quand le plan est abandonné sans copier (fermeture
du plan, nouvelle préparation). Le dossier tient un verrou de fichier tant que son exécution vit ; au démarrage d'une
exécution, les dossiers `run-*` **non verrouillés** sont supprimés (crash), quel que soit leur âge — un plan resté
ouvert dans une autre instance n'est donc jamais touché. *Choix de conception.* Avant de copier, le cœur vérifie la place libre (taille de l'archive) et la préparation
affiche « archives distantes à télécharger : N, X Go ». Place insuffisante ⇒ l'archive est en erreur, le reste continue.

### Archives imbriquées

`nested: false` ⇒ un `.zip` trouvé dans une archive est une entrée ordinaire (copiée telle quelle). `nested: true` ⇒
déplié à son tour, copié en local comme ci-dessus, jusqu'à la profondeur **5** (l'archive listée = 1) ; au-delà,
copiée telle quelle avec un avertissement. Emplacement affiché : `a.zip!/x/b.zip!/y`.

### Protection contre les zip bombs

Par archive dépliée :

- **Entrées qui se chevauchent** (deux entrées dont les données partagent des octets, la technique des bombes les plus
  efficaces) ⇒ archive refusée (en erreur). Demande l'offset des données de chaque entrée : Apache Commons Compress
  `ZipFile` l'expose (`getDataOffset`), `java.util.zip` non — choix de la bibliothèque laissé au constructeur.
- **Taux de compression > ×100** pour une entrée (taille décompressée / compressée, au-delà d'un plancher de 1 Mo
  décompressé pour ne pas viser les petits fichiers) ⇒ entrée en erreur. Le taux est contrôlé **pendant la lecture**
  (octets réellement produits), pas seulement sur les tailles annoncées, qui peuvent mentir.
- **Place disque** : vérifiée avant chaque copie locale d'archive (ci-dessus) ; l'écriture normale des entrées vérifie
  déjà la sienne.

### Fusion des archives d'un même dossier (Takeout découpé)

`mergeFolder: true` : les archives d'un même dossier listé forment **un seul arbre** (`<dossier>!/…`) ; une entrée
`Takeout/Google Photos/Album/IMG_1.jpg` de la partie 1 et `…/IMG_1.jpg.supplemental-metadata.json` de la partie 2 sont
dans le même dossier virtuel et se regroupent. Le dossier virtuel n'est terminé qu'à `folderDone` du dossier réel
(toutes les parties lues). Deux entrées de même chemin dans deux parties : la seconde est une entrée ordinaire en
conflit (comportement normal des conflits). Emplacement affiché : celui de l'archive réelle de chaque entrée.

## IHM

- **Éditeur** : entrée « Regroupements » dans l'arbre, après les entrées (l'ordre de l'exécution) : liste des règles,
  ajout par préréglage ou par type, rôle par extension. Entrée « Archives » à côté : déplier, imbriquées (avec la
  profondeur 5 rappelée), fusionner le dossier, inclure/exclure. En continu avec des règles : avertissement
  d'information (dossier par dossier), ou de repli en mode automatique quand une entrée n'est pas `file.read`.
- **Plan** : une ligne par groupe ; nouvelle colonne « Compagnons » (nombre, infobulle avec leurs noms et rôles) et
  détail de l'élément listant compagnons et cibles. Pas de lignes dépliables (la table reste plate). Les entrées
  d'archive affichent leur emplacement `archive.zip!/…`. Compteur « archives dépliées / à télécharger ».
- **Aperçu** : un compagnon ne s'ouvre pas seul ; l'aperçu du principal est inchangé.
- Textes en, fr, it (parité vérifiée par `UiBundleTest`).

### Filtres de l'entrée

Les filtres d'une action d'entrée s'appliquent **avant** le regroupement : un `include: ["*.jpg"]` de
`FileReadAction` écarte les `.xmp`, qui ne peuvent plus être rattachés. Le cœur ne voit pas les filtres d'un plugin ;
c'est à l'utilisateur de les régler (décision de Steven). Pour l'entrée fichier intégrée, l'éditeur avertit quand une
règle vise une extension que ses filtres excluent, avec un bouton « ajouter les extensions des compagnons » (aux
`include`, ou retrait de l'`exclude` correspondant).

## Laissé au constructeur

Découpage interne du tuyau (classes, threads) ; bibliothèque ZIP (Commons Compress conseillé pour l'offset des
données) ; forme exacte des préréglages dans le code ; libellés ; détail du refus de validation ; tests (dont : walk
entrelacé dossier/sous-dossier, compagnon arrivé avant son principal, zip slip, bombe à entrées chevauchantes, taux
mensonger, Takeout réparti sur deux parties, archive imbriquée au-delà de 5, mode continu avec et sans signal de
dossier).

# Exécutable externe et ffmpeg (vidéo, HEIC → JPEG, miniatures) — conception

Date : 2026-10-31. Statut : décidé (Steven a délégué les choix ; décisions de Steven : ffmpeg livré sans binaire avec
possibilité de le télécharger, GPU prévu, défaut H.265 / MP4). Usage visé : une sortie secondaire (spec
2026-10-30-secondary-outputs) transcode ou réduit pendant que la sortie principale copie l'original tel quel.

> **Révision 2026-10-31 : ffmpeg dans un plugin séparé.** Demande de Steven après la 0.8.0-beta.1 : les trois
> actions `ffmpeg.*` quittent le plugin `exec` (qui passe en 2.0.0, avec le seul `exec.process`) pour un plugin
> `copybot-plugin-ffmpeg` (module `com.copybot.plugin.ffmpeg`, code `ffmpeg`, 1.0.0, avec l'avis GPL). Le plugin
> ffmpeg **dépend** du plugin exec : `exec` exporte son paquet `com.copybot.plugin.exec.process` (`ProcessRunner`,
> `ProgramResolver`, `WorkFolder`, `ItemFiles`), `ffmpeg` le `requires` (dépendance Maven `provided`, donc exec
> n'est pas dans son archive) et le moteur charge sa couche au-dessus de celle d'exec. Le catalogue porte la
> dépendance (`requires`), et la fenêtre des plugins installe ou met à jour exec avec ffmpeg. Le dossier de données
> devient `plugin-data/ffmpeg` (une copie téléchargée dans `plugin-data/exec` par la bêta n'est pas reprise). Le
> reste de cette conception (§2 à §7) vaut tel quel pour le plugin ffmpeg ; « le plugin » y désigne exec pour §2–§3
> et ffmpeg pour §4–§7.

## 1. Un plugin, quatre actions de traitement

Module `copybot-plugin/copybot-plugin-exec`, code `exec`, version 1.0.0, bundles en/fr/it, `catalog.json`.

| Action            | Rôle                                                                 |
|-------------------|----------------------------------------------------------------------|
| `exec.process`    | lance un programme quelconque sur le fichier, le résultat remplace le contenu |
| `ffmpeg.video`    | transcode une vidéo (défaut H.265 / MP4, encodeur GPU au choix)      |
| `ffmpeg.image`    | convertit une image en JPEG (HEIC → JPEG, mais aussi PNG, WebP…)     |
| `ffmpeg.thumbnail`| miniature JPEG d'une image ou d'une vidéo                           |

ffmpeg est un « préréglage » de l'exécutable externe : même moteur d'exécution, arguments construits par le plugin.

## 2. Exécution commune (`ProcessRunner`, interne au plugin)

- Entrée : si l'élément a un fichier local dont le contenu n'a pas été remplacé, ce fichier est passé tel quel ;
  sinon le contenu est recopié dans un fichier de travail (même extension que le nom affiché).
- Sortie : un fichier de travail dans un dossier `run-<uuid>` du dossier temporaire du plugin (même schéma que
  `file.buffer` : supprimé dans `afterItem`, dossier du run supprimé dans `afterAll`, dossiers de plus d'un jour
  supprimés dans `beforeAll`). L'élément produit est `withContentFile(sortie)` avec la taille mise à jour
  (`setSize`) et le nom affiché changé d'extension (`display().put("name", …)`).
- Le processus : stdin fermé, stdout ignoré, stderr gardé (les 8 derniers Kio) pour le message d'erreur ; code de
  retour hors des codes de succès, sortie absente ou vide → erreur de l'élément avec la fin de stderr.
- Délai par élément (`timeoutSeconds`, défaut 3600 pour la vidéo, 600 sinon, 0 = sans limite) : au-delà, arbre de
  processus tué, erreur « délai dépassé ».
- Annulation (interruption du fil) : arbre de processus tué (`ProcessHandle.descendants` puis le processus,
  `destroy` puis `destroyForcibly` après 5 s), l'interruption est propagée (jamais convertie en erreur).
- Journaux : jamais les arguments (ils peuvent contenir des secrets résolus) ; seulement le programme et le nombre
  d'arguments.
- Progression : `ffmpeg` est lancé avec `-progress pipe:2 -nostats` ; le plugin lit `out_time_us` et la durée
  (`ffprobe` non requis : durée lue dans l'en-tête stderr `Duration:`) et appelle `updatePercent` (le `ItemProgress`
  est capturé avant de lancer le fil de lecture).

## 3. `exec.process`

Configuration :

- `program` (`@FilePath`, requis) : chemin de l'exécutable, ou nom cherché dans le `PATH`.
- `arguments` (liste de chaînes) : variables `{in}`, `{out}`, `{name}` (nom sans extension), `{ext}` ; les
  références `${secret:…}`/`${env:…}` marchent comme partout.
- `outputExtension` (texte, ex. `jpg`) : extension du fichier produit ; vide = celle d'origine.
- `successCodes` (liste d'entiers, défaut `[0]`).
- `timeoutSeconds` (défaut 600).
- `dryRun` : renvoie l'élément avec le nom changé d'extension (le plan montre la bonne cible), sans lancer le
  programme.
- `{out}` absent des arguments → avertissement de configuration (le programme est censé écrire ce fichier).

## 4. Trouver ffmpeg

Ordre : champ `ffmpegPath` de l'action (`@FilePath`, facultatif) → copie téléchargée par le plugin
(`<dossier de données du plugin>/ffmpeg/<version>/bin/ffmpeg(.exe)`) → `ffmpeg` du `PATH`. La version est lue une
fois par run (`ffmpeg -version`) ; HEIC exige ffmpeg ≥ 7.1 (sinon erreur claire sur les éléments HEIC).

Téléchargement (la « bouton » demandé devient une case, l'API ne permettant pas à un plugin d'ajouter un bouton) :

- Champ booléen `downloadFfmpeg` (« Télécharger ffmpeg s'il est introuvable », défaut faux).
- Windows x64 uniquement : archive ZIP « gpl » des builds FFmpeg de BtbN (GitHub, release `latest`, branche stable
  `n7.1`) ; l'empreinte SHA-256 est lue dans le fichier `checksums.sha256` de la même release et vérifiée avant
  extraction ; extraction dans un dossier temporaire puis déplacement atomique dans `ffmpeg/<version>/`. Les URL et
  noms d'archive sont des constantes vérifiées à la réalisation sur la page de la release. Licence GPL : le
  `catalog.json` porte une notice (le binaire n'est pas livré par Copybot, il est téléchargé par l'utilisateur).
- Fait dans `beforeAll` (un seul téléchargement même si plusieurs étapes ffmpeg), progression dans le statut,
  annulable ; échec → toutes les étapes ffmpeg du run échouent avec le message.
- Linux / macOS : pas de téléchargement ; message « installez ffmpeg (apt install ffmpeg / brew install ffmpeg) ou
  indiquez son chemin ».
- Avant le run (`configWarnings`) : ffmpeg introuvable et téléchargement non coché → avertissement avec la marche à
  suivre ; introuvable et coché → avertissement « sera téléchargé (≈ 100 Mo) au début du run ».

Dossier de données d'un plugin : ajout d'API 1.2 `PluginFolders.dataDirectory(String pluginCode)` (statique,
renseigné par le moteur au démarrage : `<dossier de données de Copybot>/plugin-data/<code>`, créé à la demande ;
repli sur `java.io.tmpdir/copybot-plugin-data/<code>` hors moteur).

## 5. `ffmpeg.video`

- `codec` : `h265` (défaut) ou `h264` ; conteneur MP4 (`.mp4`), `-tag:v hvc1` en H.265 (lecture Apple),
  `-movflags +faststart`, métadonnées et date de création conservées (`-map_metadata 0`).
- `quality` : `high` / `medium` (défaut) / `small` → CRF 22/26/30 (x265, x264 −4) ou équivalent par encodeur
  (`-cq` nvenc, `-global_quality` qsv, `-qp_i/-qp_p` amf, `-q:v` videotoolbox, `-qp` vaapi).
- `maxHeight` (entier facultatif, ex. 1080) : réduction en gardant le ratio, jamais d'agrandissement.
- `audio` : `aac` (défaut, 160 kb/s) ou `copy`.
- `encoder` : `auto` (défaut), `cpu`, `nvenc`, `qsv`, `amf`, `videotoolbox`, `vaapi`. `auto` essaie, une fois par
  run, dans cet ordre nvenc, qsv, amf, videotoolbox, vaapi un encodage d'essai d'une image
  (`-f lavfi -i color=black:s=256x256 -frames:v 1 -c:v <enc> -f null -`) et prend le premier qui marche, sinon cpu.
  Un encodeur explicite qui échoue à l'essai → avertissement et repli cpu.
- Ressources : `requiredResources` = `gpu` si l'encodeur retenu est matériel, `cpu` sinon (capacités réglables dans
  `config.json`, `gpu` = 1 par défaut).
- Un élément qui n'est pas une vidéo (pas de flux vidéo) passe inchangé (une miniature d'image ou une image n'est
  pas une erreur) ; détection par extension (liste) puis par l'échec « no video stream ».
- `dryRun` : nom en `.mp4`.

## 6. `ffmpeg.image` (HEIC → JPEG)

- `quality` 1–100 (défaut 90) → `-q:v` 2–31 ; `maxSize` (côté le plus long, facultatif) ; sortie `.jpg`.
- Entrées traitées : extensions image connues de ffmpeg (heic, heif, avif, png, webp, tif, tiff, bmp, jpg, jpeg) ;
  `onlyExtensions` (liste, défaut `["heic","heif"]`) restreint aux extensions voulues ; les autres éléments passent
  inchangés.
- Orientation : appliquée (ffmpeg ≥ 7.1 applique la rotation HEIF ; sinon `-autorotate`).
- Limite assumée : les métadonnées EXIF/XMP ne sont pas recopiées dans le JPEG (ffmpeg ne les écrit pas) ; la date du
  fichier produit est la date de prise de vue analysée si elle est connue (`setLastModified` du fichier de travail
  via la métadonnée de date de l'élément), sinon celle de la source. L'original reste copié par la sortie
  principale.

## 7. `ffmpeg.thumbnail`

- `size` (côté le plus long, défaut 320), JPEG qualité 85, suffixe de nom `-thumb` (`photo.jpg` → `photo-thumb.jpg`).
- Image : réduction directe. Vidéo : image à 10 % de la durée (au plus 10 s), `-frames:v 1`.
- Éléments ni image ni vidéo : filtrés (liste vide → la branche n'écrit rien).

## 8. Tests

- `ProcessRunner` avec un faux programme (un petit `java` lancé avec la classe de test, ou un script écrit dans un
  dossier temporaire) : succès, code d'échec, sortie absente, délai dépassé, annulation (le fils est tué), stderr
  tronqué, arguments jamais journalisés.
- Construction des arguments ffmpeg (tests unitaires par option et par encodeur), choix `auto` avec un faux ffmpeg.
- Téléchargement : faux serveur HTTP local (empreinte bonne/mauvaise, archive valide, annulation), pas de réseau.
- Tests d'intégration réels sautés si `ffmpeg` n'est pas dans le `PATH` (`Assumptions`).

## 9. Hors périmètre

Bouton dans l'éditeur (nouvelle API d'outils de plugin), téléchargement Linux/macOS, copie des métadonnées EXIF
(exiftool), encodage AV1, choix des pistes audio/sous-titres, ffprobe.

## 10. Écarts à la réalisation

Ce qui a été fait autrement que décrit plus haut (les sections 1 à 9 restent la conception de départ) :

- **§4, version.** Le téléchargement prend la plus haute branche stable `nX.Y` offerte par la release `latest` de BtbN
  (la branche `n7.1` n'y est plus), sans borne supérieure : une future version majeure sera installée sans avoir été
  essayée. Les versions de développement (builds git datés) ne sont pas des versions stables.
- **§4, moment du téléchargement.** Pas dans `beforeAll` : au premier élément qui a besoin d'ffmpeg (un run sans
  fichier concerné ne télécharge rien) ; cet élément attend le téléchargement (environ 200 Mo, et non 100 Mo),
  annulable. L'empreinte vient de la même origine HTTPS que l'archive : pas de protection contre une release GitHub
  compromise.
- **§4, version lue une fois.** `FfmpegSetup` cherche ffmpeg et lit sa version une fois par phase du run
  (préparation, exécution : l'API n'a pas d'identité de run) et les partage entre les étapes ffmpeg de mêmes réglages ;
  l'extraction refuse le zip-slip, l'installation est atomique, un déplacement refusé (antivirus) est retenté,
  l'espace libre est vérifié. Un échec du téléchargement ou de `-version` est gardé pour la phase ; ffmpeg
  introuvable (simple recherche) ne l'est pas : l'élément suivant cherche de nouveau et trouve la copie qu'une autre
  étape, téléchargement coché, a installée entre-temps. Il faut donc cocher `downloadFfmpeg` sur chaque étape ffmpeg.
- **Moteur, état des éléments.** À chaque étape, l'élément passe RUNNING avant WAITING_RESOURCES : il est RUNNING
  pendant que l'action donne ses ressources (`requiredResources`, qui peut chercher ou télécharger ffmpeg et en
  montrer la progression), puis WAITING_RESOURCES en attendant ces ressources, puis RUNNING de nouveau. Une exception
  levée pendant `requiredResources` passe par le masquage des secrets de l'étape, comme une erreur de l'étape.
- **§5, essai de l'encodeur.** L'essai `auto` est fait dans `requiredResources` (il faut connaître l'encodeur pour
  savoir si la ressource est `gpu` ou `cpu`), une fois par phase. Le moteur lie donc la progression de l'élément
  pendant la résolution des ressources, pour qu'un téléchargement qui s'y déroule affiche son pourcentage. Un encodeur
  explicite qui échoue à l'essai donne seulement un avertissement dans le journal (pas d'API d'avertissement de run
  pour les actions de traitement) et le repli sur le processeur. H.265 est toujours en Main10, H.264 en 8 bits 4:2:0
  (sauf vaapi), les clés de métadonnées Apple sont gardées (`use_metadata_tags` : le titre et le commentaire iTunes
  passent en clés `mdta`). Un fichier sans flux vidéo est reconnu au texte anglais d'ffmpeg (« matches no streams »).
- **§7, nom des miniatures de vidéo.** `<nom>-video-thumb.jpg` (une image garde `<nom>-thumb.jpg`), pour qu'un Live
  Photo iPhone `IMG_1234.HEIC` + `IMG_1234.MOV` ne produise pas deux fois le même nom. Si le saut à 10 % ne produit
  aucun fichier (code 0), la miniature est retentée une fois sans `-ss` (première image).
- **§6, HEIC et orientation non vérifiés.** Le HEIC principal (grille de tuiles d'ffmpeg 7.1) et l'orientation EXIF
  des JPEG, PNG et TIFF n'ont pas pu être vérifiés (ffmpeg 6.0 disponible, aucun échantillon) : la rotation HEIC est
  appliquée à partir d'ffmpeg 7.1, celle des autres dépend de la compilation. À contrôler à la main. La date du JPEG
  produit (fichier et `lastModified` de l'élément) est la date de prise de vue en heure de l'appareil ;
  `{lastModified.D}` peut donc différer de `{captureDate.D}` près de minuit.
- **API 1.2.** `WorkItem.getContentFile()` (fichier local du contenu, s'il n'a pas été remplacé) est ajouté à l'API
  (ajout seul) ; `exec.process` déclare la ressource `cpu` (le programme lancé occupe un processeur).
- **§4, bouton.** Aucun bouton de téléchargement dans l'éditeur : la case `downloadFfmpeg` et les `configWarnings` en
  tiennent lieu.

# Réécrire la date EXIF des JPEG corrigés dans la copie

Date : 2026-10-25
Statut : validé (design)

## Contexte et intention

Morceau 2 de 3 de la correction de fuseau horaire (morceau 1 :
`2026-10-25-timezone-correction-design.md`). Après une correction de fuseau dans le plan, copybot range le fichier à
la bonne date, mais la copie garde dans son EXIF l'heure fausse de l'appareil : Lightroom, Google Photos, etc.
continuent d'afficher la mauvaise heure. Le but est que la **copie** d'un JPEG corrigé porte la bonne date.

## Buts, non-buts, anti-buts

Buts :
- une étape de traitement, fournie par le plugin **metadata-extractor** (décision de Steven), que l'utilisateur
  ajoute à son pipeline s'il la veut ;
- pour un JPEG **corrigé dans le plan** (clé `CAPTURE_ZONE` présente) : la copie reçoit l'heure locale du lieu et
  son décalage, sans recompression de l'image ;
- le plan dit, pour un fichier corrigé, si son EXIF sera réécrit ou pourquoi non.

Non-buts :
- tout format qui demanderait un programme externe (`exiftool`) : décision de Steven, « on réécrit ce qu'on peut
  sans programme externe à installer » ;
- RAW (ARW, DNG…) : réécrivable en Java en théorie (TIFF), écarté (choix de conception) : les maker notes Sony
  référencent des positions absolues, un déplacement peut rendre le RAW illisible ;
- HEIC : pas d'écrivain Java fiable ;
- vidéos : leur heure interne est l'UTC, juste ; la correction ne change que le fuseau d'affichage, déjà géré par
  le classement ;
- réécrire un fichier non corrigé ; toucher l'original.

Anti-buts :
- une étape présente qui modifie un fichier non corrigé : il doit passer **octet pour octet** ;
- une image recompressée ou des métadonnées perdues (autres tags EXIF, XMP, maker notes Sony) ;
- un échec de réécriture qui fait perdre le fichier : en cas d'erreur, le fichier est copié tel quel et le plan /
  le rapport le disent (choix de conception : la date fausse vaut mieux qu'un fichier manquant).

## 1. L'étape

- Action `IProcessAction` du plugin metadata-extractor, déclarée par `MetadataExtractorPlugin` (nouvelle liste
  d'actions de traitement, à côté de `getAnalyzeActions`), nom proposé `write-date` → `metadata-extractor.write-date`
  dans `actionSteps` du pipeline. Sans configuration.
- `doProcess(item)` :
  - pas de `CAPTURE_ZONE`, ou pas un JPEG (signature `FF D8` en tête du contenu, pas l'extension), ou pas de
    `captureDate` → renvoie l'item **inchangé** ;
  - sinon renvoie un nouvel item dont le contenu est le JPEG réécrit (en mémoire : quelques Mo), même nom, mêmes
    métadonnées, taille (`setSize`) mise à jour.
- `dryRun(item)` : renvoie l'item (même cible : la date de classement est déjà corrigée par l'analyse) et note le
  verdict pour le plan (§3).

## 2. Ce qui est écrit

Pour une date de prise de vue corrigée `t` (instant `captureDate`, fuseau `CAPTURE_ZONE`, `local = t` dans ce
fuseau, `offset` = décalage de ce fuseau à cet instant) :
- `DateTimeOriginal` = `local` au format EXIF `yyyy:MM:dd HH:mm:ss` ; `OffsetTimeOriginal` = `offset` (`+02:00`) ;
- `DateTimeDigitized` / `OffsetTimeDigitized` : même valeur décalée du même écart que l'original si présents (en
  pratique égaux à l'original sur un appareil photo) ; `DateTime` (IFD0) / `OffsetTime` idem ;
- les secondes fractionnaires (`SubSecTimeOriginal`) et tous les autres tags sont conservés.
Bibliothèque : Apache Commons Imaging (`ExifRewriter.updateExifMetadataLossless`), pure Java, Apache 2.0 ; version
et compatibilité module (JPMS, le plugin a un `module-info.java`) à vérifier par le builder. Si elle ne convient
pas, le builder le signale avant d'écrire un réécrivain à la main.

## 3. Dans le plan

- La fenêtre « Traitement prévu » d'un fichier corrigé, avec l'étape présente, dit « EXIF réécrit : 2026-10-24
  17:30 -06:00 » ou « EXIF non réécrit : format non pris en charge (ARW) ». Le canal exact (projection du dry run,
  libellé d'étape) est au choix du builder, en suivant ce que la fenêtre détail montre déjà des étapes.
- La copie d'un fichier dont la réécriture a échoué se termine copiée, avec un avertissement visible dans le
  rapport de l'item (mécanisme existant d'avertissement / raison, au choix du builder).

## 4. Interaction avec la copie

- `file.write` écrit le contenu de l'item reçu : la comparaison avec une cible existante (`ifDifferent`) et le
  SHA-256 portent sur la version réécrite. Une re-copie du même fichier corrigé de la même façon produit les mêmes
  octets (réécriture déterministe) : pas de faux conflit.
- La correction n'étant pas gardée (morceau 1), une copie ultérieure sans correction copie l'original : sa cible
  diffère (autre date) ou, si elle coïncide, le contenu diffère et `onConflict` s'applique comme aujourd'hui.

## 5. Tests attendus

- JPEG corrigé : `DateTimeOriginal` et `OffsetTimeOriginal` attendus relus avec metadata-extractor ; image
  (segments hors APP1) identique octet pour octet ; autres tags conservés (dont un maker note).
- JPEG non corrigé, ARW, vidéo, contenu non JPEG nommé `.JPG` : sortie identique à l'entrée.
- Réécriture deux fois → mêmes octets.
- JPEG corrompu dans l'EXIF : fichier transmis tel quel, avertissement.
- Bout en bout moteur : pipeline avec l'étape, correction dans le plan, copie → le fichier copié porte la date.

## 6. Précisions (relecture builder, choix de conception)

- **Contenu remplacé** : `WorkItem` n'a aujourd'hui que deux constructeurs (URL + flux, ou `Path`). Ajouter dans le
  moteur une copie à contenu remplacé (ex. `WorkItem.withContent(Supplier<InputStream>, long size)`) qui garde le
  nom, le chemin source, le caractère local et les métadonnées, et ne change que ce que `openInputStream` renvoie.
  `isSource` / `FileComparison` continuent de désigner le fichier d'origine ; la vérification après écriture compare
  la copie au contenu réécrit.
- **Suppression des sources** (décision de Steven) : un JPEG réécrit reste un élément local ; en mode suppression,
  son original est supprimé comme les autres, une fois la copie (réécrite) vérifiée.
- **Dry run** : décide sur les métadonnées (`CAPTURE_ZONE`, `captureDate`) et les premiers octets du fichier
  (signature `FF D8`) ; pas d'analyse de l'EXIF (une corruption n'est connue qu'à la copie). Le verdict passe par un
  champ texte optionnel ajouté à l'étape de projection (`Projection` → `ItemDetail` → `PlanViewModel.detailText`).
- **Échec de réécriture** : si aucun avertissement par élément n'existe, le fichier est copié tel quel, une ligne
  de log et une clé de métadonnée (montrée par le détail) le disent ; pas de nouveau mécanisme de rapport.
- **Bibliothèque** : la version courante d'Apache Commons Imaging qui fournit
  `ExifRewriter.updateExifMetadataLossless` ; maker note « conservé » = toujours lisible par metadata-extractor
  (tags Sony relus identiques), pas identité d'octets. Si la bibliothèque échoue à ce test ou au module JPMS : arrêt
  et signalement, pas de réécrivain fait main.
- **Champs** : réécriture seulement si `DateTimeOriginal` existe (sinon verdict « pas de date EXIF », fichier
  inchangé) ; les photos sans décalage noté (`CAPTURE_CLOCK_UNKNOWN`) sont réécrites aussi, l'offset venant du
  fuseau à l'instant corrigé (heure ambiguë au changement d'heure : offset de l'instant).
- **Place de l'étape** : documentée « juste avant l'écriture » (description de l'action) ; ni le moteur ni l'éditeur
  ne l'imposent. Un fichier dont la date EXIF est déjà juste peut quand même changer d'octets (ajout de l'offset).
- **Miniature EXIF** (limite acceptée) : l'écrivain sans perte de Commons Imaging replace les IFD où ils tiennent
  et peut mettre l'IFD1 (miniature) avant l'IFD0 ; metadata-extractor ne suit pas ce lien arrière et ne liste plus
  la miniature. Elle reste entière dans la copie, là où pointe son offset (relue par Commons Imaging, et par les
  lecteurs qui suivent les liens TIFF) ; pas de chirurgie d'octets pour la remettre après l'IFD0. Deux défauts de
  la bibliothèque sont corrigés par ses propres appels : le `GPSVersionID` (tag 0, ignoré à la lecture) est remis,
  et les valeurs ré-encodées (commentaire utilisateur vide) reprennent leurs octets lus.
- **Plan et copie, deux contenus** : le dry run ne réécrit rien ; la vérification de conflit du plan (cible déjà
  présente, `ifIdentical` / `ifDifferent`) compare donc le contenu **d'origine**, la copie décide sur le contenu
  **réécrit**. Un fichier vu « identique » au plan peut être vu différent à la copie, et inversement : la décision
  de la copie fait foi.
- **Mémoire** : le contenu réécrit n'est pas gardé (le plan garde chaque élément jusqu'à sa fin : une carte de
  1 000 photos de 10-25 Mo ne tiendrait pas en mémoire). L'étape calcule une fois la taille réécrite, puis la copie
  régénère son contenu depuis l'original à chaque ouverture (écriture, comparaison, relecture), en relançant la
  réécriture (déterministe : mêmes octets) et sa vérification ; un échec à ce moment (original changé) est une
  `IOException` du flux : l'écriture met l'élément en échec, la source est gardée. Règle écrite dans
  `WorkItem.withContent`.
- **Vérification après réécriture** : avant d'utiliser le contenu réécrit, l'étape vérifie que tout octet hors du
  segment APP1 Exif est celui de l'original, et que metadata-extractor relit le résultat sans nouvelle erreur avec
  le `DateTimeOriginal` / `OffsetTimeOriginal` attendus ; sinon, copie telle quelle, avertissement dans le journal
  et note « EXIF non réécrit : vérification : … ».
- **Écarts au plan (builder)** : notes au format `xxx` (`+00:00`, pas `Z`, comme l'EXIF l'exige) ; libellés de
  l'étape en anglais dans `pluginBundle.properties` et en français dans un nouveau `pluginBundle_fr.properties`.

## 7. Laissé au builder

Nom exact de l'action ; forme exacte du verdict dans le détail ; nom de la méthode de contenu remplacé.

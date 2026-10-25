# Correction du fuseau horaire depuis le plan

Date : 2026-10-25
Statut : validé (design)

## Contexte et intention

Depuis le commit « Capture date: a photo or video is filed on the day its camera showed », la date de prise de vue
(`captureDate`) suit l'horloge de l'appareil : le décalage EXIF `OffsetTimeOriginal`, la clé vidéo
`creationdate`, ou le XML Sony à côté d'une vidéo ; sans décalage, l'heure lue dans le fuseau du PC. Un appareil
**mal réglé** (heure d'été oubliée, fuseau pas changé en voyage) range donc ses fichiers à une heure fausse, voire
le mauvais jour.

C'est rare. Plutôt qu'un réglage permanent du pipeline, l'utilisateur corrige **à la main, fichier par fichier** :
après la préparation et avant de lancer la copie, il sélectionne des lignes du tableau du plan et choisit le
fuseau du lieu de prise de vue ; copybot recalcule la date (heure d'été comprise), donc la cible et les conflits.

Règle de classement visée : **l'heure murale du lieu de prise de vue, au moment de la prise** : en France l'heure
de Paris avec le bon DST, à l'étranger l'heure locale de là-bas.

C'est le morceau 1 de 3, dans cet ordre : (1) cette correction ; (2) réécrire la date dans l'EXIF de la copie
(une étape de traitement `IProcessAction` d'un plugin, design à part) ; (3) détecter un oubli d'heure d'été, et
seulement avec une info fiable (GPS → fuseau), design à part.

## Buts, non-buts, anti-buts

Buts :
- corriger le fuseau d'un groupe de fichiers choisi dans le plan préparé, avant la copie ;
- l'utilisateur ne donne qu'un fuseau (IANA, ex. `America/Mexico_City`) ; copybot déduit le DST de la date ;
- générique : tout appareil, avec ou sans décalage noté ; photo et vidéo ;
- la cible et les conflits des lignes corrigées sont recalculés comme après une analyse.

Non-buts :
- modifier les fichiers (sources ou copies) : c'est le morceau 2 ;
- détecter quoi que ce soit : morceau 3 ;
- un réglage de fuseau dans le pipeline ou par import.

Anti-buts (échec même si ça marche) :
- l'interface standard alourdie : une entrée de menu et une petite fenêtre, rien d'autre de visible sans correction ;
- sans correction, le moindre changement de comportement (préparation, copie, reprise, CLI, streaming) ;
- le moteur qui apprend l'EXIF ou le DST : il transporte un choix, le plugin calcule.

## 1. Le geste (interface)

- Menu contextuel des lignes du plan (`PlanController.rowWithMenu`) : « Corriger le fuseau horaire ({0})… »,
  compté et grisé comme « Ignorer pour cette copie » : plan préparé, pas d'opération en cours, `{0}` = lignes
  corrigeables de la sélection (§3).
- « Annuler la correction ({0}) » : les lignes corrigées de la sélection reviennent à la date d'origine
  (ré-analyse sans choix).
- Fenêtre :
  - **Fuseau du lieu de prise de vue** : une liste filtrable des fuseaux IANA (`ZoneId.getAvailableZoneIds()`,
    filtre sur le texte, ex. « paris », « mexico »), préremplie par le fuseau du PC, ou par le fuseau déjà choisi
    si toute la sélection a le même.
  - **L'horloge de l'appareil**, montré **seulement** si la sélection contient des fichiers sans décalage noté
    (§2) : « L'appareil était réglé sur » [fuseau, par défaut celui du lieu] + case « sans heure d'été ».
    Le texte dit à quels fichiers il s'applique (« N fichiers sans fuseau enregistré »).
  - OK applique, puis ré-analyse les lignes (§3) ; la progression et l'arrêt sont ceux de « Analyser ».
- Après : la colonne Cible est recalculée. La colonne Date d'une ligne **non corrigée** ne change pas : elle montre
  la date de modification du fichier (clé de reprise, `PlanViewModel.dateText`). Une ligne **corrigée** y montre une
  petite horloge dessinée puis la **date de prise de vue corrigée** (`captureDate` dans le fuseau `CAPTURE_ZONE`,
  même format que la colonne, `PlanViewModel.dateCell`), infobulle « Fuseau corrigé : America/Mexico_City — date du
  fichier : 25/10/2026 10:12 ». Le **tri** de la colonne reste sur la date du fichier (ordre de reprise, `DATE_ORDER`
  inchangé), pas sur la date affichée. La colonne Cible ne garde que le crayon du motif propre. Le traitement prévu
  (fenêtre détail) montre aussi le fuseau et la date de prise de vue corrigée.
- Textes dans `uiBundle` en/fr/it (parité vérifiée par `UiBundleTest`).

## 2. Le calcul (plugin metadata-extractor)

Le choix voyage dans les métadonnées de l'élément, sous deux clés brutes publiques de `WorkItemMetadata` (à nommer
par le builder, ex. `CAPTURE_ZONE` et `CAMERA_CLOCK`) :
- **fuseau du lieu** : un `ZoneId` ;
- **horloge de l'appareil** (optionnelle) : un `ZoneId`, éventuellement un décalage fixe (case « sans heure
  d'été » = décalage standard de ce fuseau, `rules.getStandardOffset`).

`ExtractMetadata` (analyse) les lit s'ils sont présents :

| Cas | Instant | Heure affichée |
|---|---|---|
| Photo avec `OffsetTimeOriginal` | heure EXIF − décalage noté | instant dans le **fuseau du lieu** |
| Vidéo avec décalage (clé `creationdate` ou XML Sony) | UTC du MP4 | instant dans le **fuseau du lieu** |
| Photo sans décalage | heure EXIF lue dans l'**horloge de l'appareil** (défaut : fuseau du lieu) | instant dans le **fuseau du lieu** |
| Vidéo sans décalage | UTC du MP4 | instant dans le **fuseau du lieu** |

Exemples : Sony sans heure d'été en juillet à Paris, noté `+01:00` à 13:00 → instant 12:00 UTC → 14:00 Paris.
Même boîtier sans décalage noté, horloge « Paris, sans heure d'été » → 13:00 lu en `+01:00` → 14:00 Paris.
Boîtier resté à l'heure de Paris au Mexique, sans décalage → 20:00 lu en Paris → 12:00 au Mexique.

Le DST n'est jamais saisi : il vient des règles du fuseau à la date de la prise. Une vidéo sans décalage n'a pas
besoin de l'horloge de l'appareil (l'UTC du MP4 est fiable) : la fenêtre ne compte pas ces fichiers parmi les
« sans fuseau enregistré ». Le plugin expose, après analyse, si un fichier avait un décalage noté (clé brute ou
d'affichage, au choix du builder) : la fenêtre en a besoin pour décider d'afficher le second champ.

Sans les clés : comportement d'aujourd'hui, à l'identique.

## 3. Le moteur

- `Plan` : `correctZone(Collection<WorkItemExecution>, ZoneId lieu, ZoneId horloge /* nullable */)` et
  `clearZone(Collection<WorkItemExecution>)` ; plan PREPARED sinon `IllegalStateException` (comme `ignore`).
  Ils posent ou retirent les clés sur le `WorkItem` et marquent ces éléments **à ré-analyser**.
- **Nouveau** : ré-analyser un élément **déjà analysé**. Aujourd'hui `requestAnalysis` / `analyseDeferred`
  laissent de côté les fichiers déjà analysés. La ré-analyse relance les étapes d'analyse de l'élément sur le
  même `WorkItem`, **par-dessus ses métadonnées actuelles** (pas de copie prise à la préparation : chaque analyse
  réécrit ses clés, `ExtractMetadata` écrase `captureDate` ; choix de conception), puis le dry run des étapes de traitement (projection)
  et le conflict-check, comme `analyseDeferred`. Elle passe par `CopybotEngine.analyse(plan)` (même phase,
  même arrêt, même progression), pour ne pas créer un second chemin.
- Lignes corrigeables : analysées (pas différées sans analyse), pas déjà copiées ni en erreur, avec une
  `captureDate` (sans date de prise de vue, il n'y a rien à corriger). Un fichier ignoré peut être corrigé (il
  reste ignoré). Le builder fixe la règle précise dans `PlanViewModel` (comme `ignorable`).
- La clé de reprise (`ItemKey`) repose sur la date de modification du fichier, pas sur `captureDate` : une
  correction ne change ni l'ordre ni le curseur de reprise.
- **Reprise par la destination** (modes `destination` / `state-then-destination` sans curseur) : le verdict
  « déjà à destination » (`ResumeResolver`, carte `atDestination`) est figé à la préparation sur l'ancienne cible.
  Décision (Steven) : après la ré-analyse, les éléments corrigés sont **revérifiés sur leur nouvelle cible** et
  leur statut mis à jour : nouvelle cible présente → sautés « déjà à destination » ; absente → sélectionnés
  (sauf ignorés, ou écartés par le point de reprise en vigueur). Seuls les éléments corrigés sont revérifiés ;
  le point de reprise (`lastFound`, ex-dichotomie) n'est pas recalculé. Annuler la correction les revérifie de même.
  Précision (relevé du code) : seul `destinationCheck: everyFile` donne un verdict par fichier ; avec la
  `lastFound` (défaut, ex-dichotomie), le verdict est une frontière sur la date de modification, qu'une correction ne déplace
  pas : la revérification n'y change rien, seule la vérification des conflits renseigne.
  Mode curseur d'état (celui de Steven) : rien de tout cela ne s'applique, le curseur ne regarde que la date de
  modification.
- **Gardée dans le state** (décision de Steven, 2026-10-25, cohérence avec le motif propre de 9c3c988) : une
  correction ou son annulation est écrite dans le fichier d'état à côté du curseur et des motifs propres, avec la
  même clé (date de modification + nom), comme le motif propre ; au listing suivant, quel que soit le mode de
  reprise, elle est remise sur l'élément (`CAPTURE_ZONE`, `CAMERA_CLOCK`) **avant** l'analyse : le fichier est
  analysé, rangé (et réécrit, morceau 2) avec son fuseau, sans ré-analyse ; la marque horloge s'affiche. Une
  copie ultérieure en mode destination cherche donc le fichier à sa cible corrigée. Une correction appliquée
  depuis le GPS (morceau 3) suit le même chemin. Refaire la préparation la retrouve.
- Pendant la copie, les éléments emportent leurs clés : la copie écrit à la cible corrigée.

## Limites acceptées (revue finale)

- « Sans heure d'été » prend le décalage standard **actuel** du fuseau : faux d'une heure pour une vieille photo
  d'un fuseau dont l'heure standard a changé depuis (ex. Almaty 2024, Moscou 2011-2014) ; l'infobulle montre
  l'heure obtenue, et un fuseau fixe reste possible.
- `destinationMatch: directory` : la revérification d'un fichier corrigé suit la règle du mode : si le dossier du
  nouveau jour existe déjà, le fichier est sauté « déjà à destination » même s'il n'y est pas (raison visible).
- Effet du correctif de classement (vidéos) : une vidéo de voyage prise près de minuit suit désormais le décalage
  de l'appareil et peut changer de jour par rapport à un import fait avant ce correctif ; en mode
  `destination` + `everyFile`, elle sera recopiée une fois dans le nouveau dossier. Sans effet en mode curseur d'état
  ou `lastFound` (clé de reprise = date de modification). Les photos ne changent pas de jour.

## 4. Tests attendus

- Plugin : chaque ligne du tableau §2, plus « sans clés = aujourd'hui » et la case « sans heure d'été »
  (date d'été, `+01:00` fixe).
- Moteur : en mode destination, un élément corrigé dont l'ancienne cible existe et la nouvelle non devient
  sélectionné, et l'inverse sauté « déjà à destination » ; annuler la correction rétablit le verdict d'origine ;
- Moteur : `correctZone` puis `analyse` → nouvelle `captureDate`, nouvelle cible, conflict-check refait ;
  `clearZone` → retour à l'original ; refus hors PREPARED ; non-régression sans correction (tests existants verts).
- UI : comptage et grisage du menu (`PlanViewModel`), second champ montré seulement avec des fichiers sans
  décalage ; parité des bundles.

## 5. Laissé au builder

- Noms des clés et des méthodes ; forme de l'info « décalage noté ».
- Composant de la liste filtrable des fuseaux et libellés lisibles (ex. « Europe/Paris (UTC+01:00) »).
- Forme exacte de la marque « corrigé » dans le tableau.
- Découpage en commits (moteur, plugin, UI), squashés par fonction sur develop.

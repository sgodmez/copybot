# Détecter un appareil mal réglé grâce au GPS, et proposer la correction

Date : 2026-10-25
Statut : validé (design)

## Contexte et intention

Morceau 3 de 3 (morceau 1 : `2026-10-25-timezone-correction-design.md`, la correction manuelle depuis le plan ;
morceau 2 : `2026-10-25-exif-date-rewrite-design.md`). Le morceau 1 laisse l'utilisateur corriger le fuseau d'un
groupe de fichiers ; encore faut-il savoir lesquels. Quand une photo porte une position GPS **et** le décalage noté
par l'appareil, copybot peut savoir avec certitude si l'horloge était mal réglée, quelle qu'en soit la cause (heure
d'été oubliée, fuseau resté sur Paris en voyage, mauvais pays), et proposer la correction.

Règle de Steven : on ne signale **que sur une info fiable**. Pas de détection « une heure d'écart au hasard », pas de
référence téléphone (écartée par Steven).

## Buts, non-buts, anti-buts

Buts :
- à l'analyse, pour une photo avec GPS et décalage noté : déduire le fuseau du lieu de la position, comparer le
  décalage attendu (règles de ce fuseau à l'instant de la prise) au décalage noté ;
- différent → la ligne est **signalée** dans le plan, avec le fuseau proposé ;
- l'utilisateur **valide** d'un clic droit sur la sélection (décision de Steven : copybot propose, il valide) ; la
  validation applique la correction du morceau 1 avec le fuseau du GPS.

Non-buts :
- corriger automatiquement ;
- signaler sans GPS, ou avec GPS mais sans décalage noté (le réglage de l'horloge est alors inconnu : rien n'est
  fiable) — choix de conception conforme à la règle de Steven ;
- vidéos : leur heure UTC est juste ; seul le fuseau d'affichage peut différer, sans « appareil mal réglé » à
  détecter. Une vidéo avec GPS n'est pas signalée (choix de conception) ;
- une base de fuseaux en ligne.

Anti-buts :
- un faux positif : une photo signalée alors que l'appareil était bien réglé (ex. photo prise près d'une frontière
  de fuseau, GPS imprécis) — voir §2 ;
- ralentir sensiblement l'analyse des fichiers sans GPS (la grande majorité pour le Sony de Steven) ;
- sans GPS dans l'import : aucun changement visible.

## 0. Découpage (décision de Steven)

Tout le bloc de détection vit dans un **plugin séparé et facultatif** (nom proposé `gps-zone`, module
`copybot-plugin/copybot-plugin-gps-zone`), **pas dans metadata-extractor** : la base des frontières de fuseaux
(plusieurs dizaines de Mo) n'alourdit que ceux qui installent ce plugin. Il fournit une action d'analyse
(`gps-zone.check`) à placer **après** `metadata-extractor.extract` dans le pipeline.

Choix de conception : metadata-extractor expose seulement des valeurs brutes qu'il lit déjà, sans logique de
fuseau : position GPS (latitude, longitude), heure UTC du GPS, validité (`GPS Status`), décalage noté
(`OffsetTimeOriginal`) — clés publiques de `WorkItemMetadata`, noms au choix du builder. Le plugin `gps-zone`
les lit avec `captureDate`, fait la recherche de fuseau et la comparaison, et pose la clé de suggestion.
Le marquage, le menu et le résumé du plan (§3) sont dans copybot-ui et ne lisent que la clé de suggestion : sans
le plugin, rien n'apparaît.

## 1. Le calcul (plugin gps-zone, à l'analyse)

- Lire la position (exposée par metadata-extractor depuis `GpsDirectory.getGeoLocation()`), ignorer une position absente, nulle (0,0) ou marquée invalide
  (`GPS Status = V`, comme sur les photos de test du Sony sans couplage).
- Fuseau du lieu : bibliothèque Java **hors ligne** de frontières de fuseaux (ex. timeshape). Le builder vérifie
  et rapporte : licence des données (ODbL : attribution à prévoir), taille ajoutée au plugin et à l'installeur,
  temps de chargement (chargement paresseux au premier fichier avec GPS, une fois par exécution), compatibilité
  module (JPMS). La taille est acceptée puisque le plugin est facultatif (décision de Steven).
- Décalage attendu = `zone.getRules().getOffset(instant)` ; décalage noté = `OffsetTimeOriginal`.
- Différent, et confirmé par l'heure UTC du GPS (§6) → poser une clé brute publique (nom au choix, ex. `SUGGESTED_ZONE`, valeur `ZoneId`) ; égal → ne rien
  poser. La clé n'est jamais posée si `CAPTURE_ZONE` (correction manuelle) est présente.

## 2. Fiabilité près d'une frontière

Une position à moins d'une certaine distance d'une frontière de fuseau (ou en mer, zone « Etc/GMT±n ») peut donner
le mauvais fuseau. Choix de conception : si les fuseaux trouvés à quelques kilomètres autour de la position
(4 points cardinaux, distance à fixer par le builder, ~5 km) ne sont pas tous identiques, ne rien signaler.
Le builder peut proposer mieux si la bibliothèque donne une distance à la frontière.

## 3. Dans le plan (interface)

- Ligne signalée : une marque sur la cible (distincte de la marque « corrigé » du morceau 1), infobulle :
  « Appareil mal réglé : noté -06:00, lieu Europe/Paris (+02:00, d'après le GPS) ».
- Menu contextuel : « Appliquer le fuseau proposé ({0}) » (nom générique : tout plugin peut proposer un fuseau par la même clé ; décision de Steven), **masqué** quand aucune ligne sélectionnée n'a de proposition, compté sur les lignes signalées de la sélection, actif aux
  mêmes conditions que « Corriger le fuseau horaire » ; il appelle la correction du morceau 1 (`Plan.correctZone`)
  avec, pour chaque ligne, son fuseau proposé (lignes groupées par fuseau), puis la même ré-analyse.
- Après validation, la ligne est « corrigée » comme une correction manuelle (marque, annulation identiques) ; la
  marque « signalée » disparaît (la clé n'est plus posée, §1).
- Un résumé discret dans l'en-tête du plan quand il y a des lignes signalées (« 12 photos d'un appareil mal
  réglé »), sans fenêtre qui s'ouvre toute seule. Forme au choix du builder, dans l'esprit des avertissements
  existants du plan.
- Textes en/fr/it.

## 4. Tests attendus

- Plugin : position à Paris, juillet, noté `+01:00` → proposé `Europe/Paris` ; noté `+02:00` → rien ; position à
  Mexico, noté `+02:00` → proposé `America/Mexico_City` ; sans GPS, GPS invalide `V`, position (0,0), sans décalage
  noté, vidéo → rien ; position à la frontière (ex. frontière Espagne/Portugal) → rien ; `CAPTURE_ZONE` présente →
  rien.
- UI : compteur du menu, infobulle, résumé ; validation → `correctZone` avec le bon fuseau par groupe.
- Performance : analyse d'un import sans GPS non ralentie (pas de chargement de la base).

## 5. Dépendances et ordre

- Dépend du morceau 1 (correction, ré-analyse, marques, menu) ; à coder après sa Task 3 (mêmes fichiers UI).
- Indépendant du morceau 2.

## 6. Précisions (relecture builder, choix de conception)

- **Deux causes à distinguer** : (A) horloge juste, décalage faux (heure d'été oubliée, fuseau resté sur Paris) :
  l'instant est juste, il suffit d'afficher dans le fuseau du GPS ; (B) heure murale réglée à la main sur l'heure
  locale, fuseau de l'appareil faux : l'instant est faux, un simple changement d'affichage donnerait une heure
  fausse. Règle : on ne signale **que si l'heure UTC du GPS** (`GPSDateStamp` + `GPSTimeStamp`) confirme l'instant
  (écart < 15 min ; les deux causes diffèrent d'heures entières, et un fix GPS peut dater de quelques minutes).
  Sans heure GPS, ou si elle contredit l'instant : rien n'est signalé (pas fiable). Le cas B n'est pas couvert.
- **Clés** : la clé de suggestion (`ZoneId`) et une clé du décalage noté (`ZoneOffset`, pour l'infobulle) sont
  **retirées à chaque analyse** puis reposées seulement si la règle signale. Annuler une correction GPS → la
  ré-analyse signale à nouveau la ligne.
- **Comptes** : le menu compte les lignes signalées de la sélection, mêmes conditions que « Corriger le fuseau
  horaire » (une ligne ignorée compte) ; le résumé de l'en-tête compte les lignes signalées non ignorées, ni copiées,
  ni en erreur. Les fichiers pas encore analysés ne sont pas signalés et le résumé ne le précise pas.
- **Attribution des données** (ODbL) : une ligne dans la fenêtre « À propos » et un fichier NOTICE, pas d'autre
  élément d'interface.

## 7. Laissé au builder

Nom de la clé ; bibliothèque exacte (sous les contraintes du §1) ; distance de garde à la frontière ; forme du
résumé dans l'en-tête.

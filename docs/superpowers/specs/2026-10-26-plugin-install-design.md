# Plugins officiels installables depuis l'IHM

Date : 2026-10-26
Statut : validé (décidé avec Steven ; Steven a demandé d'enchaîner sans relecture)

## Contexte

Copybot est autoportant (spec `2026-10-26-portable-data-design.md` : code et `plugins` dans le dossier de l'appli) et se
met à jour depuis les releases GitHub (spec `2026-10-26-app-update-design.md` : vérification, téléchargement contrôlé
par empreinte, script `apply-update.ps1` qui attend la fin de Copybot, copie — élevée seulement si besoin — et relance).
Copybot est livré **sans plugin** : l'utilisateur installe les plugins officiels depuis un écran Plugins, comme dans
ritornello (github.com/skerdudou/ritornello : plugins dans les releases de l'appli, une version par plugin, un catalogue).

Faits du moteur qui cadrent la conception :
- Un plugin = un sous-dossier de `plugins` contenant le jar du plugin et ses dépendances (à plat ou dans `lib/`) ; son
  nom est le nom de son module principal (ex. `com.copybot.plugin.metadataextractor`, la valeur `plugin` des
  pipelines), sa version celle du descripteur de module ; le nom du dossier est libre.
- Les plugins sont chargés une fois par JVM au démarrage ; un plugin chargé ne se décharge pas et ses jars sont
  verrouillés sous Windows : **tout changement de plugin s'applique Copybot fermé, puis Copybot redémarre**.
- Compatibilité déjà appliquée au chargement (`LayerLoader.incompatibleApi`, `VersionUtil.isCompatible(engine, builtFor,
  false)`) : un plugin compilé pour un autre majeur du moteur, ou pour un mineur plus récent, est refusé
  (`plugin.load.api-incompatible`).

## Objectifs

- Un écran Plugins liste les plugins officiels publiés, installés ou non, avec leur état, et permet d'installer, de
  mettre à jour (au moins les officiels) et de désinstaller, puis d'appliquer en redémarrant.
- La vérification quotidienne annonce aussi les mises à jour de plugins.
- Une pipeline qui utilise un plugin officiel non installé propose de l'installer.
- La CI publie les plugins officiels dans les releases de Copybot, chacun avec sa version.

## Non-objectifs

- Plugins tiers / autres dépôts (officiels uniquement, décision de Steven) ; signature ; chargement à chaud ; Linux et
  macOS pour l'application des changements (comme la mise à jour de l'appli : boutons grisés hors Windows) ; plugins
  livrés avec l'appli (Copybot est livré nu, décision de Steven) ; dépendances entre plugins officiels (aucune
  aujourd'hui).

## 1. Publication (CI)

- Chaque plugin officiel (`copybot-plugin/copybot-plugin-metadata-extractor`, `copybot-plugin/copybot-plugin-gps-zone`)
  a **sa propre version** dans son pom (`X.Y.Z`, première version `1.0.0`), indépendante de celle de l'appli ; le
  descripteur de module du jar porte cette version (à vérifier au build : c'est elle que le moteur lit).
- Archive d'un plugin : `copybot-plugin-<code>-<version>.zip` (`<code>` = `IPlugin.getPluginCode()`, ex.
  `metadata-extractor`), contenant un unique dossier racine `<code>/` avec le jar du plugin et `lib/` (ses
  dépendances, comme `target/lib` aujourd'hui) — exactement ce qui se dépose dans `plugins/`.
- Chaque plugin a dans son module un fichier source `catalog.json` (code, descriptions en/fr/it, et pour gps-zone sa
  mention de licence) que la CI reprend.
- **Chaque release de Copybot porte un `catalogue.json` complet** : tous les plugins officiels avec, pour chacun, la
  release qui héberge son archive :
  ```json
  { "format": 1,
    "plugins": [ { "module": "com.copybot.plugin.metadataextractor", "code": "metadata-extractor",
                   "version": "1.0.0", "engine": "1.0", "tag": "v1.0.0",
                   "asset": "copybot-plugin-metadata-extractor-1.0.0.zip",
                   "description": { "en": "...", "fr": "...", "it": "..." } } ] }
  ```
  `engine` = majeur.mineur de la **version d'API des plugins** pour laquelle l'archive a été compilée (propriété
  `copybot.plugin.api.version`, version du module `com.copybot.engine`, distincte de la version de l'appli : voir
  `2026-10-26-plugin-api-version-design.md`). La CI lit le `catalogue.json` de la
  dernière release **publiée** (s'il existe) : un plugin dont la version n'a pas changé garde son entrée (son `tag`,
  son `engine`) et son archive n'est pas republiée ; un plugin dont la version a changé (ou absent) est empaqueté,
  attaché à la release en cours et son entrée pointe sur elle. Une release ne republie donc que les plugins qui ont
  changé (comme ritornello).
- Licence : une entrée peut porter `"license": { "name": "ODbL", "notice": { "en": "...", "fr": "...", "it": "..." } }`
  (repris du `catalog.json` du plugin ; gps-zone l'utilise pour ses données de fuseaux) ; la mention s'affiche dans la
  ligne du plugin et dans la confirmation avant installation, sans acceptation obligatoire (*choix*).
- *Choix :* garde-fou de CI : la release échoue si un plugin garde sa version alors que des fichiers de son module ont
  changé depuis le tag de son entrée (version oubliée), et si l'`engine` d'une entrée gardée n'est plus compatible
  avec la version d'API en cours (changement de majeur de l'API : il faut une nouvelle version du plugin).
- *Choix :* entre deux releases, les poms des plugins gardent leur dernière version publiée `X.Y.Z` (pas de
  `-SNAPSHOT`) ; seule la référence au pom parent suit la version de l'appli. Le README décrit les étapes (la version
  de l'appli se change sans écraser celles des plugins).
- *Choix :* un correctif de plugin seul passe par une release de Copybot (version de l'appli augmentée d'un patch) :
  chaque release porte toujours l'appli, donc la vérification de l'appli (`releases/latest`) ne change pas.

## 2. Catalogue côté Copybot

- Lecture : `releases/latest` (même requête que la vérification de l'appli, mêmes en-têtes et délais) → asset
  `catalogue.json` téléchargé et contrôlé par empreinte (`Download.fetch`) → entrées. Pour installer une entrée :
  `GET /repos/sgodmez/copybot/releases/tags/<tag>` → asset `<asset>` → téléchargement contrôlé (asset sans `digest`
  refusé, taille et SHA-256 vérifiées, comme l'appli).
- État d'un plugin du catalogue, comparé aux plugins chargés (`CopybotEngine.pluginReport()`, par nom de module) :
  **non installé**, **installé (à jour)**, **mise à jour disponible** (version du catalogue plus récente), **installé
  mais incompatible** (refusé au chargement), **incompatible** (l'`engine` du catalogue n'est pas compatible avec la
  version d'API en cours, jamais la version de l'appli, règle `VersionUtil.isCompatible(api, engine, false)` : grisé
  avec la raison). Les plugins
  installés absents du catalogue (dev, anciens) restent affichés par le diagnostic actuel.
- Indisponible (comme la mise à jour de l'appli) hors de l'appli packagée, en `-SNAPSHOT`, hors Windows pour
  l'application : la liste du catalogue peut s'afficher, les actions sont grisées avec la raison. Hors ligne : message,
  le diagnostic reste.

## 3. Écran Plugins

- La fenêtre Plugins actuelle gagne une section « Plugins officiels » : une ligne par plugin du catalogue (nom, code,
  description dans la langue de l'IHM, version installée, version disponible, état) et un bouton selon l'état :
  Installer, Mettre à jour, Désinstaller (un plugin installé peut toujours être désinstallé). Les actions choisies
  s'accumulent (« à installer », « à mettre à jour », « à désinstaller », annulables) ; un bouton
  « Appliquer et redémarrer » les applique toutes. « Tout mettre à jour » coche toutes les mises à jour disponibles.
- « Installé, mais incompatible avec ce Copybot » seulement pour un plugin installé que le chargement refuse pour son
  API (clé `plugin.load.api-incompatible`) et sans mise à jour compatible qui le corrigerait. Un plugin installé qui
  fonctionne, dont la version la plus récente du catalogue demande un Copybot plus récent, continue de fonctionner :
  il est « À jour » pour les actions (rien à mettre à jour, pas compté dans les mises à jour annoncées) et sa ligne
  le dit (« La version Y demande un Copybot plus récent (X.Z) »).
- Le diagnostic actuel (rapport, erreurs, dossiers, copie du rapport) reste.
- Appliquer : comme la mise à jour de l'appli — refusé si une opération tourne ou si une autre instance tourne depuis
  le même dossier ; avertissement si Windows demandera l'approbation administrateur (dossier `plugins` non modifiable) ;
  confirmation des éditeurs non enregistrés avant le téléchargement, fermeture seulement après succès ;
  téléchargement de toutes les archives avec une fenêtre de progression annulable ; chaque archive est décompressée
  (zip contrôlé : pas d'entrée qui sort du dossier, un seul dossier racine `<code>/` contenant un jar) dans le
  dossier temporaire de la mise à jour (`copybot-update-*`) ; puis le script est lancé et Copybot quitte ; échec ou
  annulation : rien n'est remplacé, dossier temporaire supprimé, message.

## 4. Application par le script

- `apply-update.ps1` gagne un mode plugins (paramètres en plus, le mode appli ne change pas) : une liste de dossiers
  de plugins à installer (chacun mis en miroir sur `<appli>\plugins\<code>` par robocopy, mêmes options) et une liste
  de codes à désinstaller (`<appli>\plugins\<code>` supprimé). Tout autre dossier de `plugins` qui contient le
  **même module** (nom transmis par Copybot à partir du rapport de plugins) que celui installé est supprimé, pour ne
  pas laisser l'ancienne version côte à côte (sinon `IGNORED`). Les copies passent d'abord, les suppressions ensuite
  (sauf un dossier qui est la cible d'une copie : le miroir l'a déjà remplacé) : au premier échec de robocopy le
  script s'arrête avant toute suppression, une mise à jour ratée ne devient jamais une désinstallation. Une cible est
  un nom sûr (`^[A-Za-z0-9][A-Za-z0-9._-]*$`, sans `.` final), sinon le manifeste est refusé. Mêmes garanties :
  attente de toutes les instances, copie élevée seule si besoin, relance (avec le même `--config-file`), nettoyage
  borné, journal `update.log`. Les suppressions ne touchent que des sous-dossiers de `<appli>\plugins`.
- *Choix :* les listes (dossiers à installer et leur cible, dossiers à supprimer — les chemins réels des plugins
  chargés pris dans le rapport de plugins, pas un nom supposé) sont passées au script dans un petit fichier JSON
  écrit dans le dossier temporaire de la mise à jour (PowerShell 5.1 `-File` ne transmet pas de tableaux) ; le test
  d'écriture porte sur `plugins` (ou le dossier de l'appli s'il n'existe pas encore : Copybot est livré nu) ; la copie
  élevée reçoit le même fichier.
- *Choix :* une mise à jour de l'appli et des changements de plugins sont deux opérations distinctes : l'appli
  d'abord (le bouton du bandeau lance la mise à jour de l'appli quand il y en a une) ; après la relance, l'écran
  Plugins montre ce qui est devenu incompatible ou a une mise à jour.
- *Risque résiduel :* la copie élevée (mode appli comme mode plugins) exécute un script et lit des entrées (manifeste,
  dossiers sources) dans le dossier temporaire de l'utilisateur, modifiable par lui. L'exécution élevée revalide le
  manifeste (suppressions limitées aux sous-dossiers de `plugins`, sources limitées au dossier du script), mais un
  processus tournant sous le même utilisateur pourrait remplacer le script ou les sources entre leur écriture et
  l'exécution élevée. Accepté pour l'instant : c'est la même exposition que l'installeur téléchargé puis lancé en
  élevé depuis le dossier temporaire. Suite possible : Copybot transmet le SHA-256 du script, l'exécution élevée
  charge le script en mémoire, vérifie l'empreinte et l'exécute comme `ScriptBlock`.

## 5. Mises à jour annoncées

- La vérification quotidienne de l'appli (même date, même réglage `updateCheck`) lit aussi le catalogue (la même
  release) et compte les plugins installés qui ont une mise à jour compatible. Le bandeau dit « Copybot x.y est
  disponible », « N plugins ont une mise à jour » ou les deux ; pour les plugins, son bouton ouvre l'écran Plugins avec
  ces mises à jour cochées. La recherche manuelle des Préférences le dit aussi.

## 6. Plugin manquant dans une pipeline

- Éditeur : à la place de « Plugin introuvable : X », si X est dans le catalogue (lu en arrière-plan à l'ouverture
  de l'éditeur, ou déjà en mémoire), le message dit « Plugin X non installé » avec un bouton « Installer » qui ouvre
  l'écran Plugins avec X coché. Non chargé n'est pas toujours non installé : « non installé » seulement si X ne
  l'est pas ; un X installé mais non chargé (refusé pour son API, dépendance manquante…) a un message qui renvoie à
  l'écran Plugins, et le bouton seulement si une mise à jour compatible le corrigerait (« Mettre à jour »).
- Vue plan : une pipeline qui référence des plugins non chargés affiche un bandeau « Cette pipeline a besoin de :
  X, Y » avec « Installer » (même effet) seulement si l'écran Plugins propose d'en installer ou d'en mettre à jour
  un ; sans catalogue (hors ligne), le bandeau nomme les plugins sans bouton.

## Laissé au constructeur

- Le découpage des classes (partie sans JavaFX réutilisant `UpdateCheck`/`Download`), la mise en page de la section et
  des bandeaux, la façon d'assembler le zip au build (plugin Maven ou étape de CI), le script de CI qui fusionne les
  catalogues.

# Copybot autoportant : dossier de l'appli, dossier de données, démarrage sans config

Date : 2026-10-26
Statut : à valider (décidé avec Steven)

## Contexte

Préalable à la première release. Aujourd'hui `Copybot.exe` (image jpackage) ne démarre pas : sans `--config-file`,
le moteur lit `./config.json` relatif au dossier courant, lève `config.not-found` et l'IHM quitte
(`CopybotMainUi.start`, `System.exit(1)`). Les plugins sont cherchés dans `./plugins` (dossier courant), les logs vont
dans `%LOCALAPPDATA%\Copybot\logs`, les préférences de l'IHM dans le registre (`java.util.prefs`).

Steven veut un Copybot **autoportant** : un dossier qu'on dézippe où l'on veut et qui contient tout, mais qu'on puisse
aussi installer dans `Program Files`. La distribution (zip, installateur) et les mises à jour depuis GitHub (dont
l'installation de plugins depuis l'IHM) sont un projet suivant ; celui-ci ne fait que la place des fichiers.

## Objectifs

- Le premier lancement d'un Copybot dézippé ou installé arrive sur l'écran d'accueil normal (aucune pipeline
  récente), sans erreur ni config à écrire.
- Tout ce que Copybot écrit (config, préférences IHM, logs) vit à un seul endroit, le **dossier de données**.
- Le code (l'appli et ses plugins) vit dans le **dossier de l'appli**.
- Aucun fichier modifiable ne peut rediriger le chargement de code (plugins).

## Non-objectifs

- Format de distribution, installateur, mises à jour, installation de plugins depuis l'IHM : projet suivant.
- Reprise automatique des anciennes préférences du registre : aucune release n'existe, la seule installation est le
  poste de Steven ; la reprise est faite une fois à la main (§6), le code ne garde aucune référence au registre.
- Option de ligne de commande pour un autre dossier de plugins (`--plugin-dir`) : pas tant que personne n'en a besoin.
- Règles propres à Linux/macOS : le même code s'applique partout.

## 1. Les trois règles

1. **Le code est dans le dossier de l'appli** : l'exe et `plugins\` (toujours là, quel que soit le mode).
2. **Les données vont à côté, si possible** : `config.json`, `prefs.json` et `logs\` vivent dans le dossier de
   l'appli s'il est modifiable, sinon dans `%APPDATA%\Copybot` (cas `Program Files`).
3. **`--config-file` reste prioritaire** : les données vont alors dans le dossier de ce fichier (le lancement de dev
   IntelliJ, le CLI qui le précise).

### Dossier de l'appli

- Image jpackage : le dossier de `Copybot.exe`, connu par la propriété système `jpackage.app-path` (chemin de l'exe,
  posée par le lanceur jpackage).
- Sinon (lancement par `java`, IntelliJ, CLI, tests) : le dossier courant, comme aujourd'hui.

### Dossier de données

- `--config-file` donné : le dossier parent de ce fichier.
- Sinon, le dossier de l'appli s'il est modifiable : on le vérifie au démarrage en y créant puis supprimant un fichier
  témoin (pas de test sur le chemin `Program Files` : un dossier protégé ailleurs est traité pareil).
- Sinon `%APPDATA%\Copybot` (créé au besoin) ; hors Windows, sans `APPDATA`, `~/.copybot`.
- Le dossier choisi est résolu une fois au démarrage, par le moteur (l'IHM et le CLI s'en servent tous les deux),
  **avant** l'installation des logs (`AppLogging.install()` tourne avant `CopybotEngine.create`) : la résolution ne
  dépend pas de l'instance du moteur.
- *Choix de l'implémentation :* un `config.json` posé dans un dossier de l'appli **non modifiable** n'est pas lu (les
  données sont alors toutes dans `%APPDATA%\Copybot`) : un seul dossier de données, pas de valeurs par défaut en
  lecture seule superposées. Le projet distribution n'en livre aucun.
- *Choix de l'implémentation :* si le test d'écriture change de réponse d'un lancement à l'autre (dossier portable
  copié sur un support protégé), les données suivent sans avertissement : pas de règle de plus. Le dossier en vigueur
  se voit dans la fenêtre Plugins (chemin du fichier de config, déjà affiché).

## 2. Démarrage sans `config.json`

- Fichier absent : configuration par défaut (aucune ressource, aucun groupe), **sans erreur** et sans créer le fichier.
  Il n'est écrit que lorsque l'utilisateur enregistre une ressource (fenêtre Ressources). Aujourd'hui
  `ConfigFiles.readResources`, `rewriteLosesContent` et `rewrite` lèvent `config.not-found` sur un fichier absent (la
  fenêtre Ressources ne s'ouvrirait pas sur une installation neuve) : un fichier absent y est lu comme vide, et créé au
  premier enregistrement.
- Fichier présent mais illisible ou pas du JSON : erreur comme aujourd'hui (`config.not-json`).
- `--config-file` vers un fichier absent : erreur comme aujourd'hui (`config.not-found`) — l'utilisateur a désigné un
  fichier précis.
- `CopybotEngine.configFile()` donne le chemin du `config.json` du dossier de données, existant ou non (les fenêtres
  Ressources / Plugins l'affichent et l'écrivent).

## 3. Plugins

- `pluginPath` et `devPluginPaths` **disparaissent de `config.json`** (et de `CopybotConfig`) : un fichier modifiable
  ne doit pas pouvoir faire charger du code d'ailleurs.
- Les plugins sont chargés depuis `plugins\` du **dossier de l'appli** (absent : aucun plugin, comme aujourd'hui).
- Les dossiers de plugins de développement passent par une propriété système, posée seulement dans la config de
  lancement IntelliJ `CopybotMainUiDev` : `-Dcopybot.devPluginPaths=<chemins séparés par File.pathSeparator>`
  (mêmes dossiers `target/` qu'aujourd'hui). `copybot-ui/src/dev/config.json` perd son `devPluginPaths`.
  *Choix de l'implémentation :* la propriété est ignorée dans l'image jpackage (`jpackage.app-path` posé) : l'appli
  livrée ne charge que `plugins\`.
- Les tests de plugins qui passent aujourd'hui `devPluginPaths` dans une config de test (`WriteDateEngineTest`,
  `GpsZonePluginIT`) posent la propriété avant le premier chargement des plugins de la JVM.
- Un ancien `config.json` contenant encore ces champs : ils sont ignorés (Gson ignore les champs inconnus).
- IHM :
  - Préférences : le réglage « dossier des plugins » (champ, Parcourir, Par défaut, texte du chemin résolu, écriture
    via `ConfigFiles.writePluginPath`) est retiré.
  - Fenêtre Plugins : affiche le fichier de config et le dossier des plugins (lecture seule) ; le bandeau « le prochain
    démarrage chargera… » (`MainController.nextPluginPath`) disparaît, il n'a plus de sens.
  - `ConfigFiles.readPluginPath` / `writePluginPath` et les clés de bundles qui ne servent plus sont supprimés.
- `PluginReport` / `PluginSource` : la source « configurée » du dossier de plugins disparaît ; les dossiers de dev
  restent signalés comme tels.

## 4. Préférences IHM : `prefs.json`

- `UiPreferences` lit et écrit `prefs.json` dans le dossier de données, au lieu de `java.util.prefs`. Mêmes
  préférences qu'aujourd'hui : langue (`language`), disposition des ressources (`resourcesLayout`), pipelines récentes
  (`recentPipelines`, même contenu).
- Fichier absent ou illisible : valeurs par défaut (comme aujourd'hui une valeur absente du registre), sans erreur ;
  un fichier illisible est journalisé et n'est pas écrasé avant la prochaine modification.
- Écriture : le fichier entier, via un fichier temporaire puis remplacement (pas de fichier à moitié écrit si l'appli
  s'arrête), relu juste avant chaque écriture (une autre fenêtre Copybot ouverte ne fait pas perdre ses changements).
- Les pipelines récentes gardent leur limite actuelle d'entrées ; la limite de taille propre à `java.util.prefs`
  disparaît.
- Hors périmètre : les fichiers d'état de reprise (`.state.json`) restent à côté de chaque pipeline.
- Les tests qui injectent aujourd'hui leur nœud `Preferences` injectent un chemin de fichier.

## 5. Logs

- `AppLogging` écrit dans `logs\` du dossier de données (même rotation : 3 fichiers de 2 Mo).
- Dossier non modifiable : console seulement, comme aujourd'hui.

## 6. Reprise des préférences de Steven (une fois, à la main)

Faite par l'implémentation sur le poste de Steven, pas par le code : lire les valeurs actuelles du registre
(`HKCU\Software\JavaSoft\Prefs\com\copybot\ui\util`), écrire le `prefs.json` équivalent dans `copybot-ui/src/dev/`
(dossier de données du lancement de dev), puis supprimer la clé du registre. Le `prefs.json` et `logs\` de
`copybot-ui/src/dev/` vont dans le `.gitignore`.

## 7. Documentation

- README : où vivent la config, les préférences, les logs et les plugins (les trois règles), dézipper hors de
  `Program Files` pour un dossier autoportant.

## Laissé au constructeur

- Le nom et la place de la classe qui résout les deux dossiers (moteur, exportée vers l'IHM).
- Le format exact de `prefs.json` (objet plat avec les trois clés, `recentPipelines` en tableau d'objets).
- Le message affiché quand une écriture échoue (dossier de données devenu non modifiable) : celui des écritures de
  config actuelles (`ConfigRewrite`).

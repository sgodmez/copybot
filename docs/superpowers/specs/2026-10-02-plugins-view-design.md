# Vue des plugins chargés et dossier des plugins dans l'IHM

Date : 2026-10-02
Statut : validé (design)

## Contexte

Un `devPluginPaths` resté sur un ancien chemin faisait démarrer l'UI sans le plugin metadata-extractor, sans
aucun signal : le moteur ignore en silence un dossier inexistant. Plus généralement, l'UI ne voit des plugins
que le catalogue d'actions (`PluginCatalog`) ; le détail du chargement (dossier, modules, dépendances,
causes d'échec) reste dans `LayerLoader` / `PluginLoader`.

But : une vue de **diagnostic en lecture seule** des plugins, et l'édition du **seul `pluginPath`** de la
config. Les plugins se chargent une fois par JVM (les couches de modules ne se déchargent pas) : changer
`pluginPath` s'applique au prochain démarrage.

## 1. Modèle moteur : `PluginReport`

Package `com.copybot.engine.plugin.report`, records immuables construits par `PluginReports.of` à partir des
`PluginDefinition` du chargement (enrichies par le `PluginLoader` : source, modules, dépendances, `requires`
manquants, ignoré ou non).

```java
record PluginReport(Path configFile, Path pluginPath, boolean pluginPathConfigured,
                    List<Path> devPluginPaths, List<String> warnings, List<PluginEntry> plugins)

record PluginEntry(String name, String version, Source source, Path path, Status status, String message,
                   List<ModuleEntry> modules, List<String> pluginDependencies,
                   List<String> missingRequires, List<ActionEntry> actions)

enum Source { PLUGIN_PATH, DEV, EMBEDDED }
enum Status { LOADED, ACTIONS_FAILED, ERROR, IGNORED }

record ModuleEntry(String name, String version, Path location, boolean main, boolean automatic)
record ActionEntry(StepType type, String code, String name)
```

- `pluginPath` : absolu et normalisé ; `pluginPathConfigured` faux quand la config n'en déclare pas
  (défaut `./plugins`). `devPluginPaths` : chemins absolus configurés, existants ou non.
- `warnings` : messages traduits d'en-tête — `pluginPath` inexistant (`plugin.report.path-missing`),
  `devPluginPaths` configuré mais inexistant (`plugin.report.dev-path-missing`).
- `version` null pour le plugin embarqué ; `path` null pour lui aussi.
- `IGNORED` : doublon ou révision plus ancienne (`plugin.load.duplicate`, `plugin.load.newer-revision`).
- `ERROR` : pas de module, plusieurs modules, dépendances manquantes, module invalide, résolution JPMS,
  instanciation.
- `ACTIONS_FAILED` : chargé, mais la liste de ses actions lève une exception (cas des
  `PluginCatalog.failedPlugins`).
- `message` : cause traduite (langue du démarrage), null pour `LOADED`.
- `modules` : modules trouvés dans le dossier du plugin (vide si le dossier n'a pas pu être lu), le module
  principal marqué `main`, `location` = jar ou dossier d'où vient le module (`ModuleReference.location()`).
- `pluginDependencies` : `"nom version"` des plugins dont les couches ont servi de parents à la sienne.
- `missingRequires` : `"module:version"` (ou `"module"`) non satisfaits, pour l'erreur de dépendances.
- `actions` : par type de step dans l'ordre IN, ANALYZE, PROCESS, OUT, nom traduit (le code à défaut) ;
  vide si non chargé ou `ACTIONS_FAILED`.
- Ordre des entrées : `ERROR`, `ACTIONS_FAILED`, `IGNORED`, `LOADED`, puis par nom, puis version la plus
  récente d'abord.
- `PluginReport.toText()` : rapport texte brut complet (en-tête, avertissements, une section par plugin),
  copiable dans un ticket.

Accès : `CopybotEngine.pluginReport()`, construit à la demande sur `PluginEngine.getAllPlugins()` (toutes
les définitions du chargement) et la config de l'engine. `configFile` est le fichier effectivement lu au
démarrage (absolu, `CopybotEngine.configFile()`).

### Robustesse du chargement

Ces pannes faisaient échouer tout le chargement ; elles deviennent une entrée `ERROR` du seul plugin
concerné, les autres se chargeant normalement (fait sur `develop` par « Keep one broken plugin from
stopping the others from loading », dont ce projet reprend les clés de message) :

- `FindException` de `findAll` (jar corrompu, `module-info` invalide, module en double) →
  `plugin.load.unreadable` ;
- toute `RuntimeException` de `Configuration.resolve` / `defineModulesWithOneLoader` (`ResolutionException`,
  `LayerInstantiationException`, package en double…) → `plugin.load.layer` ;
- `ServiceConfigurationError`, `RuntimeException` ou `LinkageError` à l'instanciation d'un plugin
  (constructeur qui lève, bundle i18n déclaré mais absent du jar) → `plugin.load.instantiation`. Pour
  rattacher l'erreur au bon plugin, l'instanciation se fait couche par couche
  (`ServiceLoader.load(layer, IPlugin.class)` filtré sur les fournisseurs de la couche) plutôt que sur
  une couche fusionnée unique.

Les plugins parents sont enregistrés avant la résolution de la couche : une entrée `ERROR` de couche ou
d'instanciation liste quand même ses `pluginDependencies`. Le `System.out.println("I've found a service…")`
disparaît.

## 2. UI

### Fenêtre Plugins

Menu **Outils → Plugins…** (nouveau menu), fenêtre non modale, une seule instance (ramenée au premier plan
si déjà ouverte).

- **En-tête** : fichier de config, `pluginPath` effectif absolu (« par défaut » s'il n'est pas configuré),
  `devPluginPaths`, avertissements du rapport. Si le `pluginPath` enregistré dans le fichier diffère de
  celui du chargement : bandeau « Modifié : redémarrer Copybot pour appliquer ».
- **Liste** (gauche) : nom, version, pastille de statut (vert `LOADED`, orange `ACTIONS_FAILED`, rouge
  `ERROR`, gris `IGNORED`), badge `DEV` / `EMBARQUÉ`. Ordre du rapport.
- **Détail** (droite) : chemin du dossier + bouton « Ouvrir le dossier » (`HostServices.showDocument`),
  message ; tableaux Modules (nom, version, emplacement, principal, automatique), Dépendances (plugins
  parents ; `requires` manquants en rouge), Actions (type, code, nom).
- **Pied** : « Copier le rapport » → `PluginReport.toText()` dans le presse-papier.

### `pluginPath` dans Préférences

Sous la langue : « Dossier des plugins », champ en lecture seule, « Parcourir… » (`DirectoryChooser`),
« Par défaut » (retire la clé → `./plugins`). Un chemin relatif existant est affiché avec sa résolution
absolue.

Sur OK, si la valeur a changé : réécriture de la clé `pluginPath` dans le `config.json` lu au démarrage,
puis message « pris en compte au prochain démarrage ». Pas de redémarrage automatique. En cas d'échec
d'écriture : `PopinUtil.showError`, le dialogue reste ouvert. Si la réécriture perdrait du contenu
(`ConfigFiles.rewriteLosesContent` : commentaires, syntaxe JSON non stricte ou clés en double), une
confirmation OK/Annuler le signale d'abord ; Annuler n'écrit rien et laisse le dialogue ouvert.

Écriture : `com.copybot.config.ConfigFiles.writePluginPath(Path configFile, Path pluginPathOrNull)` —
lit le fichier en `JsonObject` Gson, pose ou retire `pluginPath` en conservant les autres champs, écrit
en joli JSON avec les fins de ligne du fichier d'origine (CRLF si le fichier en contenait), par fichier
temporaire puis renommage. Le chemin est écrit tel que choisi (absolu depuis le `DirectoryChooser`).
`ConfigFiles.readPluginPath(Path configFile)` relit la valeur enregistrée (pour le bandeau).

### i18n

Clés `menu.tools*`, `plugins.*`, `pref.plugin-path*` dans les bundles UI (en, fr, it) ; clés
`plugin.report.*`, `config.write-failed`, `config.plugin-path.not-string` dans les bundles moteur (en, fr),
à côté des `plugin.load.unreadable`, `plugin.load.layer`, `plugin.load.instantiation` de `develop`.

## 3. Tests

- **`PluginReportTest`** (moteur) : rapport sur les plugins de démo (`copybot-plugin-demo-*`, montages de
  `PluginLoaderTest`) — `LOADED` avec modules et actions, `IGNORED` (doublon / ancienne révision),
  `ERROR` (dépendances manquantes, avec `missingRequires`), source `DEV`, avertissements de chemins
  inexistants, ordre des entrées.
- **Robustesse** : jar corrompu, plugin dont le constructeur lève → `ERROR` avec le bon message, les autres
  plugins chargés.
- **`ConfigFilesTest`** : autres champs préservés, retrait de la clé, CRLF conservé, fichier en lecture
  seule → exception.
- **`toText()`** : sur un rapport construit à la main.
- **UI** : vérification manuelle avec `CopybotMainUiDev` (metadata-extractor en `DEV` ; faux `pluginPath`
  → avertissement ; changement de dossier → bandeau).

## Hors périmètre

Édition de `devPluginPaths` et des ressources (`resources`, `resourceGroups`), installation / suppression
de plugins, redémarrage automatique, packages exportés / services / graphe JPMS complet.

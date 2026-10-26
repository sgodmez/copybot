# Copybot autoportant — Plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Copybot démarre sans `config.json`, range son code (exe, `plugins\`) dans le dossier de l'appli et ses données (`config.json`, `prefs.json`, `logs\`) dans un dossier de données résolu par trois règles.

**Architecture:** Un record moteur `AppFolders` (package `com.copybot.config`, déjà exporté vers l'IHM) résout une fois les deux dossiers ; `CopybotEngine.create` le reçoit, l'IHM s'en sert aussi pour les logs et `prefs.json`. `CopybotConfig` perd `pluginPath`/`devPluginPaths` ; les dossiers de dev passent par la propriété système `copybot.devPluginPaths`. `UiPreferences` passe de `java.util.prefs` à un fichier JSON.

**Tech Stack:** Java 25 (JPMS), Maven 3.8.8, JUnit Jupiter, Gson, JavaFX.

**Spec:** `docs/superpowers/specs/2026-10-26-portable-data-design.md`

## Global Constraints

- Build/tests : JDK 25 ; `mvn -o -q verify` à la racine (toujours avec la racine ou `-am` : `-pl copybot-ui` seul prend un moteur périmé de `~/.m2`).
- Fichiers Java en LF ; bundles `.properties` avec échappements `\u` (le bundle moteur est ASCII, vérifié par un test) ; `UiBundleTest` vérifie la parité des clés en/fr/it : toute clé ajoutée ou retirée l'est dans les trois bundles et dans la liste du test.
- Code et javadoc en anglais ; plan et spec en français.
- Noms de fichiers fixés par la spec : `config.json`, `prefs.json`, `logs`, `plugins` ; dossier de repli `%APPDATA%\Copybot` (Windows), `~/.copybot` sans `APPDATA`.
- Propriété système des plugins de dev : `copybot.devPluginPaths`, chemins séparés par `File.pathSeparator`, ignorée quand `jpackage.app-path` est posé.
- Aucune référence au registre / `java.util.prefs` ne reste dans le code.
- Un commit par tâche, `git add` ciblé sur les fichiers de la tâche ; fin de message : `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.

## Review Focus

1. Le test d'écriture du dossier de l'appli ne laisse jamais de fichier témoin derrière lui, même si un fichier du même nom existe déjà ou si la suppression échoue (Task 1 : `theWritableProbeLeavesNothingBehind`).
2. `--config-file` vers un fichier absent reste une erreur `config.not-found` ; seul le `config.json` du dossier de données peut manquer (Task 2 : `aChosenConfigFileThatIsMissingIsAnError`).
3. Un `prefs.json` corrompu ne bloque pas le démarrage : valeurs par défaut, et le fichier n'est réécrit qu'à la prochaine préférence enregistrée (Task 3 : `aCorruptPrefsFileGivesTheDefaults`).
4. Installation neuve sans `config.json` : la fenêtre Ressources lit des ressources vides et l'enregistrement crée le fichier avec seulement ses clés (Task 2 : `resourcesOfAMissingFileAreEmptyAndWritingCreatesIt`).
5. Dans l'image jpackage, `copybot.devPluginPaths` est ignorée (Task 1 : `theDevPluginPathsAreIgnoredInThePackagedApp`).

---

### Task 1: `AppFolders` — dossier de l'appli, dossier de données, dossiers de dev

**Files:**
- Create: `copybot-engine/src/main/java/com/copybot/config/AppFolders.java`
- Test: `copybot-engine/src/test/java/com/copybot/config/AppFoldersTest.java`

**Interfaces:**
- Produces (utilisé par les tâches 2 et 3) :
  - `public record AppFolders(Path appDir, Path dataDir, Path configFile, boolean configChosen)` — chemins absolus normalisés ; `configChosen` vrai quand `--config-file` a été donné.
  - `public static AppFolders resolve(Optional<Path> chosenConfig)` — lit `jpackage.app-path`, `user.dir`, `APPDATA`, `user.home`.
  - `static AppFolders resolve(Optional<Path> chosenConfig, String jpackageAppPath, Path workingDir, String appData, String userHome, Predicate<Path> writable)` — pour les tests.
  - `public Path pluginsDir()` → `appDir/plugins` ; `public Path prefsFile()` → `dataDir/prefs.json` ; `public Path logsDir()` → `dataDir/logs`.
  - `public static List<Path> devPluginPaths()` et `static List<Path> devPluginPaths(String property, String jpackageAppPath)` — vide quand la propriété est absente/blanche ou quand `jpackageAppPath` est non nul.
  - `static boolean isWritable(Path dir)` — crée puis supprime un fichier témoin unique (`Files.createTempFile(dir, ".copybot-write-test", null)`), faux sur toute `IOException`/`SecurityException` ; supprime le témoin dans un `finally`.

Règles (spec §1) : `appDir` = parent de `jpackage.app-path` s'il est posé, sinon `workingDir`. `dataDir` = parent de `chosenConfig` s'il est donné ; sinon `appDir` si `writable.test(appDir)` ; sinon `appData/Copybot` si `appData` non blanc, sinon `userHome/.copybot`. `configFile` = `chosenConfig` s'il est donné, sinon `dataDir/config.json`. `resolve` ne crée aucun dossier.

- [ ] **Step 1: Write the failing tests** — `AppFoldersTest` (JUnit, `@TempDir`) :
  - `thePackagedAppKeepsItsDataBesideItWhenWritable` : `jpackageAppPath = app/Copybot.exe`, writable → `appDir == app`, `dataDir == app`, `configFile == app/config.json`, `pluginsDir == app/plugins`, `prefsFile == app/prefs.json`, `logsDir == app/logs`, `configChosen == false`.
  - `aProtectedAppFolderSendsTheDataToAppData` : writable faux, `appData = X` → `dataDir == X/Copybot`, `pluginsDir == app/plugins`.
  - `withoutAppDataTheDataGoesToTheHomeFolder` : writable faux, `appData = null` → `dataDir == home/.copybot`.
  - `aChosenConfigFileDecidesTheDataFolder` : `chosenConfig = dev/config.json`, writable faux → `dataDir == dev`, `configFile == dev/config.json`, `configChosen == true`, `appDir == workingDir` (pas de jpackage).
  - `outsideThePackagedAppTheAppFolderIsTheWorkingDirectory` : `jpackageAppPath = null` → `appDir == workingDir`.
  - `theWritableProbeLeavesNothingBehind` : `isWritable(tempDir)` vrai et `tempDir` vide ensuite ; `isWritable(tempDir/absent)` faux.
  - `theDevPluginPathsComeFromTheProperty` : `devPluginPaths("a" + File.pathSeparator + "b", null)` → `[a, b]` ; `(null, null)` et `("  ", null)` → vide.
  - `theDevPluginPathsAreIgnoredInThePackagedApp` : `devPluginPaths("a", "C:/x/Copybot.exe")` → vide.
- [ ] **Step 2: Run to verify they fail** — `mvn -o -q -pl copybot-engine test -Dtest=AppFoldersTest -Dsurefire.failIfNoSpecifiedTests=false` → échec de compilation (`AppFolders` absent).
- [ ] **Step 3: Implement `AppFolders`** selon les règles ci-dessus (javadoc : les trois règles de la spec §1).
- [ ] **Step 4: Run to verify they pass** — même commande → `Tests run: 8, Failures: 0, Errors: 0`.
- [ ] **Step 5: Commit** — `AppFolders.java`, `AppFoldersTest.java` ; message : `Self-contained Copybot: the app folder holds the code and the plugins, the data folder (beside it when writable, else APPDATA) the config, preferences and logs`.

### Task 2: Moteur — démarrage sans config, plugins du dossier de l'appli

**Files:**
- Modify: `copybot-engine/src/main/java/com/copybot/config/CopybotConfig.java` (retirer `pluginPath`, `devPluginPaths` et `PathsAdapter` s'il ne sert plus)
- Modify: `copybot-engine/src/main/java/com/copybot/config/ConfigFiles.java` (retirer `readPluginPath`, `writePluginPath`, `PLUGIN_PATH` ; fichier absent = objet vide)
- Modify: `copybot-engine/src/main/java/com/copybot/engine/CopybotEngine.java` (`create`, `readConfig`, `loadPlugins`, `pluginReport`, `updateResources`, retirer `DEFAULT_CONFIG_PATH`/`DEFAULT_PLUGIN_PATH`)
- Modify: `copybot-engine/src/main/java/com/copybot/engine/plugin/report/PluginReport.java`, `PluginReports.java`, `PluginSource.java` (javadoc de `PLUGIN_PATH` : « the plugins folder of the app folder »)
- Modify: `copybot-engine/src/main/resources/com/copybot/engine/i18n/engineBundle*.properties` (retirer `config.plugin-path.not-string`, et toute clé de plugin path qui ne sert plus)
- Modify: `copybot-engine/src/test/resources/com/copybot/engine/config.json` (→ `{}`)
- Test: `CopybotConfigTest`, `ConfigFilesTest`, `CopybotEngineTest`, `PluginReportsTest` (moteur) ; `copybot-plugin/copybot-plugin-metadata-extractor/src/test/java/com/copybot/plugin/metadataextractor/actions/WriteDateEngineTest.java`

**Interfaces:**
- Consumes: `AppFolders` (Task 1).
- Produces :
  - `public record CopybotConfig(Map<String, Integer> resources, List<List<String>> resourceGroups)`.
  - `public static CopybotEngine create(AppFolders folders)` ; `create(Optional<Path> configPath)` reste et vaut `create(AppFolders.resolve(configPath))` (le CLI et les tests l'utilisent).
  - `public Path configFile()` : `folders.configFile()`, existant ou non.
  - `public record PluginReport(Path configFile, Path pluginPath, List<Path> devPluginPaths, List<String> warnings, List<PluginEntry> plugins)` (sans `pluginPathConfigured`) ; `PluginReports.of(Path configFile, Path pluginPath, List<Path> devPluginPaths, List<PluginDefinition> definitions)`.
  - `ConfigFiles.readResources(Path)` : fichier absent → `Resources` vides ; `ConfigFiles.rewriteLosesContent(Path)` : fichier absent → `false` ; `ConfigFiles.writeResources(Path, Resources)` : fichier absent → créé (dossier parent créé au besoin) avec seulement les clés non vides.

Comportement (spec §2, §3) : `config.json` absent et `!configChosen` → `new CopybotConfig(null, null)`, sans écrire le fichier ; absent et `configChosen` → `config.not-found` (inchangé) ; illisible → `config.not-json` (inchangé). Plugins : `PluginEngine.load(folders.pluginsDir(), AppFolders.devPluginPaths() filtrés sur Files::isDirectory)`.

- [ ] **Step 1: Write the failing tests**
  - `CopybotEngineTest.aMissingConfigFileInTheDataFolderGivesTheDefaults` : `AppFolders` de test (`configChosen = false`, `configFile` absent dans `@TempDir`) → `create` réussit, `configFile()` = ce chemin, le fichier n'existe toujours pas.
  - `CopybotEngineTest.aChosenConfigFileThatIsMissingIsAnError` : `create(Optional.of(tempDir.resolve("absent.json")))` → `CopybotException` dont la clé/message est celui de `config.not-found`.
  - `ConfigFilesTest.resourcesOfAMissingFileAreEmptyAndWritingCreatesIt` : `readResources(absent)` vide ; `rewriteLosesContent(absent)` faux ; `writeResources(absent, Resources(Map.of("disk:*", 4), List.of()))` → le fichier existe, son JSON vaut exactement `{"resources":{"disk:*":4}}` (aux espaces près).
  - `ConfigFilesTest` : retirer les tests de `readPluginPath`/`writePluginPath`. `CopybotConfigTest` : retirer ceux de `devPluginPaths`, ajouter `anOldConfigWithPluginFieldsIsStillRead` (`{"pluginPath":"x","devPluginPaths":["y"],"resources":{"cpu":2}}` → `resources() == {cpu=2}`).
  - `PluginReportsTest` : adapter à la nouvelle signature (plus de `pluginPathConfigured`).
- [ ] **Step 2: Run to verify they fail** — `mvn -o -q -pl copybot-engine test` → échecs de compilation/assertions sur les nouveaux tests.
- [ ] **Step 3: Implement** les changements de la liste Files/Interfaces ; `WriteDateEngineTest` pose `System.setProperty("copybot.devPluginPaths", devPlugin().toString())` avant `CopybotEngine.create` (plus de `devPluginPaths` dans sa config de test).
- [ ] **Step 4: Run to verify they pass** — `mvn -o -q -pl copybot-engine,copybot-plugin/copybot-plugin-metadata-extractor,copybot-plugin/copybot-plugin-gps-zone -am verify` → aucun échec (l'IHM ne compile pas encore : ne pas la construire ici).
- [ ] **Step 5: Commit** — fichiers de la tâche ; message : `Engine: starts without a config file (the defaults), loads the plugins of the app folder and the dev folders of the copybot.devPluginPaths property; pluginPath and devPluginPaths leave the config`.

### Task 3: IHM — `prefs.json`, logs dans le dossier de données, démarrage

**Files:**
- Modify: `copybot-ui/src/main/java/com/copybot/ui/util/UiPreferences.java`
- Modify: `copybot-ui/src/main/java/com/copybot/ui/util/AppLogging.java`
- Modify: `copybot-ui/src/main/java/com/copybot/ui/CopybotMainUi.java` (`start`)
- Modify: `copybot-ui/pom.xml` (retirer la propriété surefire `java.util.prefs.PreferencesFactory`)
- Delete: `copybot-ui/src/test/java/com/copybot/ui/util/GuardedPreferencesFactory.java`, `MemoryPreferences.java` (s'il existe)
- Modify: `copybot-ui/src/main/java/module-info.java` si `java.prefs` y est requis
- Test: `copybot-ui/src/test/java/com/copybot/ui/util/UiPreferencesTest.java`, `AppLoggingTest.java` (s'il existe)

**Interfaces:**
- Consumes: `AppFolders.resolve(Optional<Path>)`, `prefsFile()`, `logsDir()` (Task 1) ; `CopybotEngine.create(AppFolders)` (Task 2).
- Produces :
  - `UiPreferences.use(Path prefsFile)` — appelé une fois au démarrage ; tant qu'il ne l'est pas (tests), les préférences restent en mémoire et rien n'est écrit sur disque.
  - API publique inchangée : `savedLanguage()`, `saveLanguage(Locale)`, `resourcesLayout()`, `saveResourcesLayout(ResourcesLayout)`, `recents()`, `updateRecents(Consumer<RecentPipelines>)`.
  - Surcharges de test package-private prenant le `Path` du fichier (remplacent celles qui prenaient un nœud `Preferences`).
  - `AppLogging.install(Path logsDir)` (remplace `install()` et `logDirectory(...)`).

Format de `prefs.json` : objet JSON plat `{"language": "fr", "resourcesLayout": "SIDE_PANEL", "recentPipelines": <le JSON de RecentPipelines>}` ; clé absente = défaut. Lecture : fichier absent ou invalide → objet vide (invalide : journalisé en WARNING), sans écrire. Écriture : relire le fichier, changer la clé, écrire le tout par `AtomicFiles.write` (dossier créé au besoin) ; échec d'écriture journalisé, pas d'exception vers l'IHM. Les récentes gardent `RecentPipelines.MAX` ; plus de limite de taille de `java.util.prefs`.

Démarrage (`CopybotMainUi.start`) : lire `--config-file`, `AppFolders folders = AppFolders.resolve(pathArg)`, `AppLogging.install(folders.logsDir())`, `UiPreferences.use(folders.prefsFile())`, puis bundles/langue et `ENGINE = CopybotEngine.create(folders)` ; une exception de résolution suit le chemin d'erreur existant (popup puis sortie).

- [ ] **Step 1: Write the failing tests** — `UiPreferencesTest` réécrit sur `@TempDir` :
  - `theRecentPipelinesSurviveARestart` : `updateRecents(file, …)` puis `recents(file)` → mêmes entrées.
  - `aMissingPrefsFileGivesTheDefaults` : langue vide, `PROGRESS_LINE`, aucune récente ; le fichier n'est pas créé.
  - `aCorruptPrefsFileGivesTheDefaults` : fichier `garbage {` → défauts, et son contenu est inchangé après les lectures.
  - `savingOnePreferenceKeepsTheOthers` : langue puis disposition enregistrées → les deux relues ; le fichier modifié entre-temps par un autre écrivain (clé `language` changée à la main) garde ce changement après `saveResourcesLayout`.
  - `anUnknownLayoutGivesTheDefault` : `"resourcesLayout": "OLD"` → `PROGRESS_LINE`.
  - `AppLogging` : `install(dir)` crée `dir/copybot0.log` (si un test de logs existe, l'adapter ; sinon en ajouter un).
- [ ] **Step 2: Run to verify they fail** — `mvn -o -q verify` → échecs sur les nouveaux tests.
- [ ] **Step 3: Implement** selon Interfaces ; supprimer la fabrique de préférences de test et la propriété surefire.
- [ ] **Step 4: Run to verify they pass** — `mvn -o -q verify` (racine) → aucun échec ; `grep -rn "java.util.prefs\|Preferences.user" copybot-*/src` → rien.
- [ ] **Step 5: Commit** — message : `UI: preferences in prefs.json and logs in logs of the data folder, resolved at startup (no more registry)`.

### Task 4: IHM — plus de réglage du dossier des plugins ; lancement de dev ; README

**Files:**
- Modify: `copybot-ui/src/main/java/com/copybot/ui/PreferencesController.java`, `copybot-ui/src/main/resources/com/copybot/ui/views/preferences-view.fxml` (retirer le réglage « dossier des plugins » : champ, Parcourir, Par défaut, chemin résolu, écriture `writePluginPath`)
- Modify: `copybot-ui/src/main/java/com/copybot/ui/PluginsController.java` (`init(PluginReport report)`, plus de suffixe « par défaut » ni de bandeau de redémarrage), `MainController.java` (retirer `nextPluginPath` et `PluginsInfo` s'il ne porte plus que ça)
- Modify: `copybot-ui/src/main/resources/com/copybot/ui/i18n/uiBundle*.properties` + liste de `UiBundleTest` (retirer `pref.plugin-path*`, `plugins.default`, `plugins.restart-needed` et les clés devenues inutiles)
- Modify: `copybot-ui/src/dev/config.json` (→ `{}`), `.idea/runConfigurations/CopybotMainUiDev.xml` (VM : ajouter `-Dcopybot.devPluginPaths=copybot-plugin/copybot-plugin-metadata-extractor/target;copybot-plugin/copybot-plugin-gps-zone/target`), `.gitignore` (`copybot-ui/src/dev/prefs.json`, `copybot-ui/src/dev/logs/`)
- Modify: `README.md` (où vivent code, config, préférences, logs, plugins ; dézipper hors de `Program Files` pour un dossier autoportant)
- Test: tests IHM existants des contrôleurs/vues touchés (adapter), `UiBundleTest`

**Interfaces:**
- Consumes: `PluginReport` sans `pluginPathConfigured` (Task 2).

- [ ] **Step 1: Adapt the tests** — retirer/adapter les tests du réglage du dossier des plugins et du bandeau de redémarrage ; `UiBundleTest` sans les clés retirées.
- [ ] **Step 2: Run to verify** — `mvn -o -q verify` → échecs tant que le code n'est pas adapté (compilation).
- [ ] **Step 3: Implement** les retraits ; vérifier `grep -rn "pluginPath\|devPluginPaths\|plugin-path\|restart-needed" copybot-ui/src copybot-engine/src` → seuls restent `PluginReport.pluginPath()` et ses usages d'affichage, et la propriété `copybot.devPluginPaths` dans `AppFolders`.
- [ ] **Step 4: Run to verify they pass** — `mvn -o -q verify` (racine) → aucun échec.
- [ ] **Step 5: Commit** — message : `UI: no plugin folder setting any more (the plugins of the app folder), dev plugins through copybot.devPluginPaths; README tells where Copybot keeps its files`.

### Après les tâches (fait par le coordinateur, pas par un agent)

Reprise des préférences de Steven (spec §6) : lire `HKCU\Software\JavaSoft\Prefs\com\copybot\ui\util` (`reg query`, valeurs encodées par `java.util.prefs` : `/` devant une majuscule), écrire le `prefs.json` équivalent dans `copybot-ui/src/dev/` de la copie principale, puis supprimer la clé.

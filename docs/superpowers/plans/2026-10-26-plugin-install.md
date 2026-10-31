# Plugins officiels installables depuis l'IHM — Plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** L'écran Plugins liste les plugins officiels du catalogue de la dernière release, permet d'installer / mettre à jour / désinstaller puis d'appliquer en redémarrant ; la vérification quotidienne annonce les mises à jour de plugins ; une pipeline signale un plugin manquant installable ; la CI publie les plugins et le catalogue.

**Architecture:** Package `com.copybot.ui.plugins` sans JavaFX (catalogue, états, archive, changements — Task 1) ; mode plugins du script `apply-update.ps1` piloté par un fichier manifeste (Task 2) ; build des archives de plugins et catalogue en CI (Task 3) ; IHM (Task 4). Tasks 1, 2, 3 en parallèle (fichiers disjoints), Task 4 après.

**Tech Stack:** Java 25 JPMS, JavaFX, Gson, `java.net.http`, PowerShell 5.1, Maven, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-10-26-plugin-install-design.md`

## Global Constraints

- Catalogue : asset `catalogue.json` de `https://api.github.com/repos/sgodmez/copybot/releases/latest` ; archive d'un plugin via `https://api.github.com/repos/sgodmez/copybot/releases/tags/<tag>` puis l'asset `<asset>` ; mêmes en-têtes, délais et contrôle (digest obligatoire, taille, SHA-256) que la mise à jour de l'appli (`UpdateCheck`, `Download`).
- Format du catalogue (`format: 1`) : `plugins[]` d'objets `module`, `code`, `version`, `engine` (majeur.mineur), `tag`, `asset`, `description` (`en`/`fr`/`it`), `license` optionnel (`name`, `notice` en/fr/it).
- Archive : `copybot-plugin-<code>-<version>.zip`, un seul dossier racine `<code>/` contenant le jar du plugin et `lib/`.
- Compatibilité : `VersionUtil.isCompatible(<version du moteur>, entry.engine, false)` (règle du chargement).
- Manifeste du script (JSON, écrit par Java dans le dossier temporaire de la mise à jour, lu par le script) :
  `{ "install": [ { "source": "<dossier décompressé absolu>", "target": "<nom du sous-dossier dans plugins>" } ], "remove": [ "<chemin absolu d'un sous-dossier de plugins>" ] }`. Le script refuse toute entrée `remove` qui n'est pas un sous-dossier direct de `<appli>\plugins` et toute `target` qui contient un séparateur ou `..`.
- Mêmes garanties que la mise à jour de l'appli : attente de toutes les instances, copie élevée seule si besoin, robocopy par chemin complet `/MIR /R:2 /W:1`, arrêt au premier code ≥ 8, relance avec le même `--config-file`, nettoyage borné au dossier du script, journal `update.log`.
- Build : JDK 25, `mvn -o -q verify` à la racine (jamais `-pl copybot-ui` sans `-am`). Java LF, bundles en/fr/it avec échappements `\u` (parité testée par `UiBundleTest`), code et javadoc en anglais, .ps1 ASCII PowerShell 5.1.
- Un commit par tâche ; fin de message `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.

## Review Focus

1. Un catalogue illisible ou absent (première release, hors ligne) : l'écran affiche le diagnostic et un message, sans erreur bloquante (Task 1 : `aMissingOrBadCatalogueIsAReadableError` ; Task 4).
2. Mise à jour d'un plugin dont l'ancienne version est dans un dossier au nom libre : l'ancien dossier est supprimé, rien d'autre (Task 1 : `anUpdateRemovesTheFoldersOfTheSameModule` ; Task 2 : `thePluginModeReplacesOnlyItsFolders`).
3. Manifeste piégé (`remove` hors de `plugins`, `target` avec `..`) : refusé par le script (Task 2 : `aManifestOutsideThePluginsFolderIsRefused`).
4. Zip de plugin mal formé (plusieurs racines, sans jar, entrée `../`) : refusé (Task 1 : `aBadPluginZipIsRefused`).
5. Plugin incompatible avec le moteur : grisé, jamais proposé à l'installation ni compté dans les mises à jour (Task 1 : `anIncompatibleEntryIsNeverOffered`).

---

### Task 1: Catalogue, états et préparation (sans IHM)

**Files:** Create `copybot-ui/src/main/java/com/copybot/ui/plugins/` : `PluginCatalog.java` (parse + records `Entry`, `License`), `PluginRow.java` (+ enum `PluginState`), `CatalogService.java`, `PluginArchive.java`, `PluginChanges.java` ; tests correspondants dans `copybot-ui/src/test/java/com/copybot/ui/plugins/`. Modify `copybot-ui/src/main/java/module-info.java` seulement si nécessaire (le package est interne au module).

**Interfaces (Produces, pour Task 4) :**
- `public final class PluginCatalog` : `public record Entry(String module, String code, String version, String engine, String tag, String asset, Map<String,String> description, License license)` (license nullable) ; `public record License(String name, Map<String,String> notice)` ; `public static PluginCatalog parse(String json) throws UpdateException` (clé `update.error.unreadable` ; une entrée invalide est ignorée, pas tout le catalogue) ; `public List<Entry> entries()`.
- `public enum PluginState { NOT_INSTALLED, UP_TO_DATE, UPDATE_AVAILABLE, INSTALLED_INCOMPATIBLE, INCOMPATIBLE }` ; `public record PluginRow(PluginCatalog.Entry entry, String installedVersion, List<Path> installedFolders, PluginState state)` ; `public static List<PluginRow> PluginRow.of(PluginCatalog catalog, PluginReport report, String engineVersion)` (par nom de module ; version la plus récente installée ; `INSTALLED_INCOMPATIBLE` si l'entrée du rapport est en erreur `plugin.load.api-incompatible` ou si l'engine du catalogue n'est pas compatible alors qu'il est installé ; `INCOMPATIBLE` si non installé et incompatible) ; `public static long updatesAvailable(List<PluginRow> rows)` (UPDATE_AVAILABLE et compatibles).
- `public final class CatalogService` : `public static final URI LATEST`, `public static final String TAGS` (`https://api.github.com/repos/sgodmez/copybot/releases/tags/`) ; `public CatalogService(HttpClient client, URI latest, String tagsBase, String currentVersion)` ; `public PluginCatalog fetch(Path dir) throws UpdateException, InterruptedException` (release latest → asset `catalogue.json` via `Download.fetch` → parse ; 404 → clé `update.no-release`, pas de `catalogue.json` → `plugins.error.no-catalogue`) ; `public Path download(PluginCatalog.Entry entry, Path dir, LongConsumer progress, BooleanSupplier cancelled) throws UpdateException, InterruptedException` (release du tag → asset → `Download.fetch` ; asset absent → `plugins.error.no-asset`).
- `public final class PluginArchive` : `public static Path extract(Path zip, Path into, String code) throws IOException` → `into/<code>` ; refuse une entrée qui sort de `into`, plus d'un dossier racine, une racine ≠ `code`, aucun `.jar` sous la racine.
- `public final class PluginChanges` : `public record Install(Path source, String target)` ; `public PluginChanges(List<Install> installs, List<Path> removals)` ; `public static PluginChanges plan(List<PluginRow> toInstallOrUpdate, Map<PluginRow, Path> extracted, List<PluginRow> toRemove)` (install : `target` = `code` ; removals = les `installedFolders` des lignes mises à jour ou désinstallées, sans doublon) ; `public void writeManifest(Path file) throws IOException` (format des Global Constraints, chemins absolus).

- [ ] **Step 1: Write the failing tests** — `PluginCatalogTest` (catalogue complet → entrées, licence lue ; entrée sans `module` ignorée ; JSON invalide → `update.error.unreadable`) ; `PluginRowTest` (`aMissingOrBadCatalogueIsAReadableError` côté parse ; non installé ; à jour ; `UPDATE_AVAILABLE` ; `anIncompatibleEntryIsNeverOffered` (engine `2.0` pour un moteur `1.3`) ; `INSTALLED_INCOMPATIBLE` ; `updatesAvailable` ne compte que les compatibles) ; `CatalogServiceTest` (serveur `HttpServer` local : latest → catalogue ; tag → archive téléchargée ; 404 → `update.no-release` ; pas de catalogue → `plugins.error.no-catalogue`) ; `PluginArchiveTest` (`aBadPluginZipIsRefused` : `../x`, deux racines, racine ≠ code, sans jar ; zip correct → `into/code` avec le jar et `lib/`) ; `PluginChangesTest` (`anUpdateRemovesTheFoldersOfTheSameModule` : une mise à jour retire les anciens dossiers (nom libre) ; le manifeste écrit relu en JSON a les bons chemins absolus).
- [ ] **Step 2: Run to verify they fail** — `mvn -o -q -pl copybot-ui -am test -Dtest=PluginCatalogTest,PluginRowTest,CatalogServiceTest,PluginArchiveTest,PluginChangesTest -Dsurefire.failIfNoSpecifiedTests=false`.
- [ ] **Step 3: Implement** selon Interfaces (réutiliser `UpdateCheck` / `Release` / `Download` / `UpdateException` ; `VersionUtil` du moteur pour la compatibilité si exporté vers l'IHM, sinon la même règle réimplémentée et testée).
- [ ] **Step 4: Run to verify they pass** — même commande puis `mvn -o -q verify`.
- [ ] **Step 5: Commit** — `Plugins: read the official catalogue of the latest release, compare it with the loaded plugins, download and unpack a plugin, plan the changes (no UI yet)`.

### Task 2: Mode plugins du script

**Files:** Modify `copybot-ui/src/main/resources/com/copybot/ui/update/apply-update.ps1`, `copybot-ui/src/main/java/com/copybot/ui/update/UpdateApply.java` ; Test `copybot-ui/src/test/java/com/copybot/ui/update/UpdateApplyTest.java`.

**Interfaces (Produces, pour Task 4) :** `public static List<String> pluginScriptCommand(Path script, long pid, Path appDir, Path manifest, Path exe, Path logDir, Path configFile)` (`configFile` nullable) → même début que `scriptCommand` (powershell par chemin complet, `-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File script`), puis `-ProcessId pid -AppDir appDir -PluginManifest manifest -Exe exe -LogDir logDir [-ConfigFile path]`.

Script : nouveau paramètre `-PluginManifest` ; en mode plugins `-NewDir` n'est pas requis et la copie de l'image de l'appli n'a pas lieu. Lecture du manifeste (`ConvertFrom-Json`), validation (Global Constraints : `remove` sous-dossiers directs de `<appli>\plugins`, `target` sans séparateur ni `..`) — refus = journal, relance, aucune modification ; attente des instances ; si `plugins` (ou l'appli quand `plugins` n'existe pas) n'est pas modifiable : copie élevée (`-CopyOnly` + `-PluginManifest`) ; copie : `plugins` créé au besoin, suppression des `remove`, puis pour chaque `install` `robocopy source <appli>\plugins\<target> /MIR /R:2 /W:1` (arrêt au premier code ≥ 8) ; relance ; nettoyage borné au dossier du script (le manifeste et les sources y sont).

- [ ] **Step 1: Write the failing tests** — `pluginScriptCommand` exact ; `thePluginModeReplacesOnlyItsFolders` (`@EnabledOnOs(WINDOWS)`, vrai script : appli factice avec `plugins/old-name/a.jar` (même module, à retirer), `plugins/other/b.jar` (gardé), source décompressée `<temp>/metadata-extractor/m.jar` ; après exécution : `plugins/metadata-extractor/m.jar` présent, `old-name` absent, `other` intact, `app`/`runtime` intacts, relance témoin, dossier temporaire supprimé) ; `aManifestOutsideThePluginsFolderIsRefused` (`remove` = le dossier de l'appli, ou `target` = `..\x` → rien n'est modifié, relance quand même, refus journalisé) ; un manifeste qui ne fait que retirer (désinstallation).
- [ ] **Step 2: Run to verify they fail** — `mvn -o -q -pl copybot-ui -am test -Dtest=UpdateApplyTest -Dsurefire.failIfNoSpecifiedTests=false`.
- [ ] **Step 3: Implement**.
- [ ] **Step 4: Run to verify they pass** — même commande puis `mvn -o -q verify`.
- [ ] **Step 5: Commit** — `Updates: the apply script also installs, replaces and removes plugin folders from a manifest, with the same waiting, elevation and relaunch`.

### Task 3: Archives de plugins, catalogue et CI

**Files:** Modify `copybot-plugin/copybot-plugin-metadata-extractor/pom.xml`, `copybot-plugin/copybot-plugin-gps-zone/pom.xml` (`<version>1.0.0</version>` propre, `plugin.code` en propriété), `copybot-plugin/pom.xml` (assemblage du zip au `package` : `target/copybot-plugin-<code>-<version>.zip` avec `<code>/<jar>` + `<code>/lib/*`) ; Create `copybot-plugin/copybot-plugin-metadata-extractor/catalog.json`, `copybot-plugin/copybot-plugin-gps-zone/catalog.json` (code, descriptions en/fr/it ; gps-zone : licence ODbL et sa mention, reprise de son NOTICE), `.github/scripts/plugin-catalogue.ps1` ; Modify `.github/workflows/release.yml` (job `release`), `README.md` (publication : version de l'appli sans toucher aux versions des plugins ; augmenter la version d'un plugin modifié).

- `plugin-catalogue.ps1` (pwsh, paramètres : tag en cours, version du moteur `X.Y.Z`, dossier de sortie) : télécharge le `catalogue.json` de la dernière release publiée (`gh release download --pattern catalogue.json` sur `gh release view --json tagName` de la dernière non brouillon ; aucune → catalogue vide) ; pour chaque plugin officiel (`copybot-plugin/*/catalog.json`) lit sa version du pom (`mvn help:evaluate` dans le module, argument quoté) ; version inchangée par rapport à l'entrée précédente → entrée gardée, mais **échec** si `git diff --quiet <tag de l'entrée> HEAD -- <module>` signale des changements (version oubliée) ou si `engine` gardé n'est plus compatible avec le moteur (même majeur et mineur ≤) ; sinon entrée nouvelle (`tag` = tag en cours, `engine` = majeur.mineur du moteur, `asset` = nom du zip) et le zip est copié dans le dossier de sortie ; écrit `catalogue.json` (`format: 1`).
- `release.yml` : après `mvn -Pdist package`, appel du script avec `GH_TOKEN`, et `files:` reçoit `copybot-ui/target/release/catalogue.json` et `copybot-ui/target/release/copybot-plugin-*.zip`. Le job `linux` ne change pas.

- [ ] **Step 1: Write** poms, `catalog.json`, script, workflow, README.
- [ ] **Step 2: Verify** — `mvn -o -q verify` (le zip de chaque plugin apparaît dans son `target/` : vérifier son contenu avec `jar tf` ou `unzip -l` : racine `<code>/`, jar, `lib/`) ; `jar --describe-module --file <jar du plugin>` affiche `@1.0.0` ; exécuter le script de catalogue en local en mode sans réseau si possible (paramètre pour fournir un catalogue précédent en fichier) et montrer le `catalogue.json` produit. YAML relu à la main.
- [ ] **Step 3: Commit** — `Release: official plugins get their own versions, a zip each and a catalogue.json in every release, with guards against a forgotten version bump`.

### Task 4: IHM — écran Plugins, application, annonces, plugin manquant

**Files:** Modify `PluginsController.java` + `plugins-view.fxml`, `MainController.java` (+ bandeau), `UpdateUi.java` / `UpdateService.java` (vérification quotidienne qui lit aussi le catalogue, compte des mises à jour de plugins), `EditorController.java` (plugin manquant installable), `PlanController.java` (bandeau « cette pipeline a besoin de »), bundles en/fr/it + `UiBundleTest` ; Create au besoin une orchestration sans JavaFX (`PluginService`) et ses tests.

**Interfaces :** Consumes Task 1 (`CatalogService`, `PluginCatalog`, `PluginRow`, `PluginState`, `PluginArchive`, `PluginChanges`), Task 2 (`UpdateApply.pluginScriptCommand`, `writeScript`), l'existant de la mise à jour de l'appli (`UpdateService` : refus si occupé / autre instance, avertissement admin, `deleteQuietly`, dossier temporaire `copybot-update-*`, `launch` ; `UpdateUi` : fenêtre de progression ; `CopybotMainUi.confirmExitForUpdate` / `exitForUpdate`).

Comportement : spec §2 à §6 (section « Plugins officiels » avec actions accumulées et « Appliquer et redémarrer », « Tout mettre à jour », licence affichée et rappelée à la confirmation ; application : refus si occupé ou autre instance, avertissement admin si `plugins` n'est pas modifiable, confirmation des éditeurs avant, téléchargement + décompression de toutes les archives avec progression annulable, manifeste, lancement du script, sortie ; échec = rien de remplacé, dossier temporaire supprimé, message ; vérification quotidienne : même date et réglage, bandeau « N plugins ont une mise à jour » (bouton : écran Plugins avec ces mises à jour cochées ; une mise à jour de l'appli passe d'abord) ; éditeur et vue plan : plugin manquant présent au catalogue → bouton « Installer » qui ouvre l'écran Plugins avec ce plugin coché). Indisponible hors appli packagée / `-SNAPSHOT` / hors Windows : liste visible, actions grisées avec la raison.

- [ ] **Step 1: Write the failing tests** — `PluginServiceTest` (sans JavaFX : application refusée si occupé ; avec une autre instance ; changements = installs + removals attendus ; échec de téléchargement → rien lancé, dossier temporaire supprimé ; annulation tardive → rien lancé ; vérification quotidienne → nombre de mises à jour de plugins ; plugins manquants d'une pipeline présents au catalogue) ; `UiBundleTest` avec les nouvelles clés.
- [ ] **Step 2: Run to verify they fail** — `mvn -o -q -pl copybot-ui -am test -Dtest=PluginServiceTest,UiBundleTest -Dsurefire.failIfNoSpecifiedTests=false`.
- [ ] **Step 3: Implement**.
- [ ] **Step 4: Run to verify they pass** — `mvn -o -q verify`.
- [ ] **Step 5: Commit** — `Plugins window: install, update and remove the official plugins, then apply with a restart; plugin updates announced; a pipeline offers to install a missing plugin`.

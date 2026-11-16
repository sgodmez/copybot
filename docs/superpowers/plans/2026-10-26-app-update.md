# Mise à jour de Copybot depuis GitHub — Plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Copybot vérifie au plus une fois par jour s'il existe une nouvelle release GitHub, l'annonce par un bandeau et se met à jour d'un clic, en mode installé (Inno Setup) comme en zip portable ; la CI publie l'installateur et le zip sur un tag.

**Architecture:** Un package `com.copybot.ui.update` sans JavaFX (vérification, téléchargement contrôlé — Task 1 ; application selon le mode — Task 2) ; l'IHM (Task 4) orchestre et affiche. L'installateur et le workflow de release (Task 3) sont indépendants du code Java. Tasks 1, 2, 3 se font en parallèle (fichiers disjoints) ; Task 4 après.

**Tech Stack:** Java 25 (JPMS, `java.net.http`), JavaFX, Gson, JUnit Jupiter, Inno Setup 6, GitHub Actions, PowerShell 5.1.

**Spec:** `docs/superpowers/specs/2026-10-26-app-update-design.md`

## Global Constraints

- URL de vérification (constante) : `https://api.github.com/repos/sgodmez/copybot/releases/latest` ; en-têtes `Accept: application/vnd.github+json`, `User-Agent: Copybot/<version>` ; délais connexion et requête 10 s ; abandon du téléchargement sans en-têtes en 30 s ou sans octet pendant 60 s.
- Noms des fichiers de release : `copybot-<version>-setup.exe`, `copybot-<version>-windows-x64.zip` (dossier racine `Copybot/` contenant `Copybot.exe`). Tag `v<version>`, version `X.Y.Z` (trois nombres).
- Préférences (`prefs.json`) : `updateCheck` (booléen, vrai par défaut), `updateLastCheck` (secondes epoch). Vérification due si absente, dans le futur, ou il y a ≥ 24 h.
- Mode installé ⇔ `unins000.exe` dans le dossier de l'appli (parent de `jpackage.app-path`). Arguments de l'installateur : `/SILENT /SUPPRESSMSGBOXES /NORESTART /CLOSEAPPLICATIONS /RELAUNCH`.
- Mise à jour portable : ne remplace que `app` et `runtime` (miroir) et copie les fichiers racine de la nouvelle version sans rien supprimer à la racine ; garde `plugins`, `config.json`, `prefs.json`, `logs`.
- Asset sans `digest` (`sha256:<hex>`) refusé ; taille ≠ `size` = échec ; tout échec supprime le dossier temporaire et ne remplace rien.
- Build : JDK 25, `mvn -o -q verify` à la racine (jamais `-pl copybot-ui` sans `-am`). Java en LF, bundles en/fr/it avec échappements `\u` et parité vérifiée par `UiBundleTest`. Code et javadoc en anglais.
- Un commit par tâche ; fin de message `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.

## Review Focus

1. Hors ligne / GitHub en erreur au démarrage : aucune fenêtre, aucune attente de l'IHM, la date est quand même enregistrée (Task 1 : `aFailedCheckIsStillRecordedAsDone` côté logique de date ; Task 4 : la vérification automatique tourne hors du fil JavaFX).
2. Une copie en cours : « Mettre à jour » est grisé, jamais d'interruption (Task 4 : `theUpdateIsRefusedWhileBusy`).
3. Empreinte fausse, taille fausse, asset sans digest, annulation : rien ne reste dans le dossier temporaire (Task 1 : `aWrongDigestLeavesNothing`, `anAssetWithoutDigestIsRefused`, `aCancelledDownloadLeavesNothing`).
4. Zip malveillant ou mal formé (entrée `../`, pas de `Copybot/Copybot.exe`) : refusé (Task 2 : `aZipEntryEscapingTheFolderIsRefused`, `aZipWithoutTheAppIsRefused`).
5. Portable : `plugins`, `config.json`, `prefs.json`, `logs` et un fichier inconnu à la racine survivent ; un ancien fichier sous `app` disparaît (Task 2 : `theScriptReplacesOnlyTheAppImage`, Windows seulement).

---

### Task 1: Vérification et téléchargement contrôlé (sans IHM)

**Files:**
- Create: `copybot-ui/src/main/java/com/copybot/ui/update/AppVersion.java`, `Release.java`, `UpdateCheck.java`, `Download.java`, `UpdateException.java`
- Modify: `copybot-ui/src/main/java/module-info.java` (`requires java.net.http;`), `copybot-ui/src/main/java/com/copybot/ui/util/UiPreferences.java`
- Test: `copybot-ui/src/test/java/com/copybot/ui/update/AppVersionTest.java`, `ReleaseTest.java`, `UpdateCheckTest.java`, `DownloadTest.java` ; `UiPreferencesTest.java`

**Interfaces (Produces, consommées par Task 4 ; Task 2 n'en dépend pas):**
- `public final class AppVersion` : `static int compare(String a, String b)` ; `static boolean isRelease(String version)` (faux pour `?`, vide, null, ou contenant `SNAPSHOT`).
- `public record Release(String version, URI notesUrl, List<Asset> assets)` ; `public record Asset(String name, URI url, long size, String sha256)` (`sha256` = hex minuscule sans préfixe, null sans `digest`) ; `static Release parse(String json) throws UpdateException` (version = `tag_name` sans `v` initial) ; `Optional<Asset> asset(String name)`.
- `public final class UpdateCheck` : `public static final URI LATEST` ; `public UpdateCheck(HttpClient client, URI latest, String currentVersion)` ; `public Release fetchLatest() throws UpdateException, InterruptedException` ; `public static boolean due(Instant now, Optional<Instant> lastCheck)` ; `public static HttpClient httpClient()` (proxy système, redirections, connexion 10 s).
- `public final class Download` : `public static Path fetch(HttpClient client, Asset asset, Path dir, LongConsumer progress, BooleanSupplier cancelled) throws UpdateException, InterruptedException` → `dir/<asset.name>` vérifié ; en cas d'échec le fichier partiel est supprimé (le dossier appartient à l'appelant).
- `public final class UpdateException extends Exception` : `public UpdateException(String key, Object... args)`, `String key()`, `Object[] args()` ; clés (Task 4 les met dans les bundles) : `update.error.http` (code), `update.error.network` (message), `update.error.unreadable`, `update.error.no-digest`, `update.error.digest`, `update.error.size`, `update.error.stalled`, `update.error.cancelled`.
- `UiPreferences` : `static boolean updateCheck()`, `static void saveUpdateCheck(boolean)`, `static Optional<Instant> updateLastCheck()`, `static void saveUpdateLastCheck(Instant)` (+ surcharges package-private à `Path` pour les tests, comme les préférences existantes).

- [ ] **Step 1: Write the failing tests**
  - `AppVersionTest` : `1.0.0 < 1.0.1`, `1.0 == 1.0.0`, `1.10.0 > 1.9.0`, `1.0.0-beta < 1.0.0`, `isRelease("1.0-SNAPSHOT") == false`, `isRelease("?") == false`, `isRelease("1.2.3") == true`.
  - `ReleaseTest` : JSON GitHub minimal (`tag_name: "v1.2.0"`, `html_url`, deux assets dont un avec `"digest": "sha256:ABC…"`) → version `1.2.0`, sha256 en minuscules sans préfixe, l'autre asset `sha256 == null` ; JSON invalide → `UpdateException` clé `update.error.unreadable`.
  - `UpdateCheckTest` (serveur `com.sun.net.httpserver.HttpServer` local) : 200 → `Release` ; 404 → clé `update.error.http` avec 404 ; en-têtes `Accept` et `User-Agent: Copybot/1.0.0` reçus ; `due` : absente → vrai, il y a 23 h → faux, il y a 24 h → vrai, dans le futur → vrai.
  - `DownloadTest` (même serveur) : contenu et digest justes → fichier présent, progression jusqu'à la taille ; `aWrongDigestLeavesNothing` ; `anAssetWithoutDigestIsRefused` (clé `update.error.no-digest`, aucune requête nécessaire) ; taille annoncée fausse → `update.error.size` ; `aCancelledDownloadLeavesNothing` (clé `update.error.cancelled`).
  - `UiPreferencesTest` : `updateCheck` vrai par défaut puis faux enregistré ; `updateLastCheck` vide puis relu.
- [ ] **Step 2: Run to verify they fail** — `mvn -o -q -pl copybot-ui -am test -Dtest=AppVersionTest+ReleaseTest+UpdateCheckTest+DownloadTest+UiPreferencesTest -Dsurefire.failIfNoSpecifiedTests=false` → compilation en échec.
- [ ] **Step 3: Implement** selon Interfaces (SHA-256 calculé pendant la copie du flux ; délais 30 s / 60 s par un minuteur qui interrompt la lecture ; Gson pour le JSON).
- [ ] **Step 4: Run to verify they pass** — même commande puis `mvn -o -q verify` → aucun échec.
- [ ] **Step 5: Commit** — `Updates: check the latest GitHub release and download an asset checked against its digest (no UI yet)`.

### Task 2: Application de la mise à jour (installé / portable)

**Files:**
- Create: `copybot-ui/src/main/java/com/copybot/ui/update/InstallMode.java`, `UpdateApply.java`, `copybot-ui/src/main/resources/com/copybot/ui/update/apply-update.ps1`
- Test: `copybot-ui/src/test/java/com/copybot/ui/update/InstallModeTest.java`, `UpdateApplyTest.java`

**Interfaces (Produces, consommées par Task 4):**
- `public enum InstallMode { INSTALLED, PORTABLE; public static InstallMode of(Path appDir) }` (INSTALLED ⇔ `appDir/unins000.exe` existe).
- `public final class UpdateApply` :
  - `public static List<String> setupCommand(Path setupExe)` → `[setupExe, "/SILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/CLOSEAPPLICATIONS", "/RELAUNCH"]`.
  - `public static Path extractPortable(Path zip, Path into) throws IOException` → décompresse, renvoie `into/Copybot` ; refuse (IOException avec message clair) une entrée qui sort de `into` et un zip sans `Copybot/Copybot.exe`.
  - `public static Path writeScript(Path dir) throws IOException` → copie la ressource `apply-update.ps1` dans `dir`.
  - `public static List<String> scriptCommand(Path script, long pid, Path appDir, Path newDir, Path exe, Path logDir)` → `["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script, "-ProcessId", pid, "-AppDir", appDir, "-NewDir", newDir, "-Exe", exe, "-LogDir", logDir]` (chemins en `toString()`).
- Script `apply-update.ps1` (paramètres `-ProcessId -AppDir -NewDir -Exe -LogDir`, `[switch]-Elevated`) : journal `LogDir/update.log` (créé au besoin) ; si `AppDir` n'est pas modifiable (essai d'écriture d'un fichier témoin) et pas `-Elevated` → se relance par `Start-Process powershell -Verb RunAs` avec les mêmes paramètres + `-Elevated`, puis sort ; attend la fin de `ProcessId` au plus 60 s (sinon journalise et sort sans rien toucher) ; `robocopy NewDir\app AppDir\app /MIR` et idem `runtime` ; copie les fichiers racine de `NewDir` dans `AppDir` (sans supprimer) ; code robocopy ≥ 8 journalisé ; relance `Exe` ; supprime le parent de `NewDir` (le dossier temporaire de la mise à jour) ; tout est journalisé.

- [ ] **Step 1: Write the failing tests**
  - `InstallModeTest` : dossier avec `unins000.exe` → INSTALLED, sans → PORTABLE.
  - `UpdateApplyTest` : `setupCommand` exact ; `scriptCommand` exact ; `extractPortable` d'un zip `Copybot/Copybot.exe` + `Copybot/app/x.jar` → `into/Copybot` avec les deux fichiers ; `aZipEntryEscapingTheFolderIsRefused` (`../evil.txt`) ; `aZipWithoutTheAppIsRefused` ; `writeScript` écrit un fichier non vide.
  - `theScriptReplacesOnlyTheAppImage` (`@EnabledOnOs(WINDOWS)`) : appli factice (`app/old.jar`, `app/keep.jar`, `runtime/r.dll`, `Copybot.exe` (fichier quelconque), `plugins/p.jar`, `config.json`, `prefs.json`, `logs/a.log`, `mine.txt`) ; nouvelle version (`app/keep.jar` modifié, `runtime/r.dll`, `Copybot.exe`, `Copybot.ico`) ; PID d'un processus déjà terminé ; `-Exe` = un `.cmd` factice qui écrit un témoin ; exécuter `scriptCommand` et attendre (≤ 60 s) → `app/old.jar` absent, `app/keep.jar` nouveau, `Copybot.ico` présent, `plugins/p.jar`, `config.json`, `prefs.json`, `logs/a.log`, `mine.txt` intacts, témoin de relance écrit, `update.log` non vide.
- [ ] **Step 2: Run to verify they fail** — `mvn -o -q -pl copybot-ui -am test -Dtest=InstallModeTest+UpdateApplyTest -Dsurefire.failIfNoSpecifiedTests=false`.
- [ ] **Step 3: Implement** selon Interfaces.
- [ ] **Step 4: Run to verify they pass** — même commande puis `mvn -o -q verify`.
- [ ] **Step 5: Commit** — `Updates: apply a downloaded update, through the silent installer or, for a portable copy, a script that replaces only the app image and relaunches`.

### Task 3: Installateur et release

**Files:**
- Create: `copybot-ui/src/installer/copybot.iss`, `.github/workflows/release.yml`
- Modify: `README.md` (section release : passer la version du pom à `X.Y.Z` dans tous les `pom.xml`, commit, tag `vX.Y.Z`, push du tag, publier le brouillon, revenir à la `-SNAPSHOT` suivante ; installation : installateur pour l'utilisateur seul ou pour tous, ou zip portable)

**Interfaces:** aucune avec le Java ; produit les fichiers `copybot-<v>-setup.exe` et `copybot-<v>-windows-x64.zip` attendus par Tasks 1/2/4.

- `copybot.iss` : `#define AppVersion` et `SourceDir` passés par `/D` ; `AppId={{<GUID fixe>}`, `AppName=Copybot`, `AppVersion={#AppVersion}`, `AppPublisher=com.copybot`, `PrivilegesRequired=lowest`, `PrivilegesRequiredOverridesAllowed=dialog`, `DefaultDirName={autopf}\Copybot`, `DisableProgramGroupPage=yes`, `CloseApplications=yes`, `RestartApplications=no`, `OutputBaseFilename=copybot-{#AppVersion}-setup`, `SetupIconFile` = l'icône du projet, `UninstallDisplayIcon={app}\Copybot.exe` ; `[Files]` : `{#SourceDir}\*` récursif ; `[Icons]` : menu Démarrer, bureau en tâche optionnelle ; `[Run]` : `{app}\Copybot.exe` avec `Flags: nowait postinstall skipifsilent`, et une seconde entrée `Flags: nowait` avec `Check: Relaunch` ; `[Code]` : `function Relaunch: Boolean` vraie si `WizardSilent` et le paramètre `/RELAUNCH` est présent sur la ligne de commande ; pas de `[UninstallDelete]` des données ni de `plugins`.
- `release.yml` : `on: push: tags: ['v*']` ; `permissions: contents: write` ; job `release` `runs-on: windows-latest` : checkout, setup-java Temurin 25 avec cache maven ; étape qui lit la version du pom (`mvn -q help:evaluate -Dexpression=project.version -DforceStdout`) et échoue si elle ne vaut pas `X.Y.Z` ou si le tag ≠ `v<version>` ; `mvn -B -Pdist package -DskipTests` ; `choco install innosetup -y` ; `ISCC /DAppVersion=<v> /DSourceDir=copybot-ui\target\dist\Copybot /Ocopybot-ui\target\release copybot-ui\src\installer\copybot.iss` ; zip de `copybot-ui\target\dist\Copybot` avec le dossier `Copybot` à la racine (`Compress-Archive`) en `copybot-<v>-windows-x64.zip` ; `softprops/action-gh-release@v2` avec `draft: true`, les deux fichiers, `generate_release_notes: true`.

- [ ] **Step 1: Write** `copybot.iss` et `release.yml`, la section README.
- [ ] **Step 2: Verify** — `mvn -o -q verify` (rien de Java ne change) ; si Inno Setup est installé localement (`ISCC.exe` dans `C:\Program Files (x86)\Inno Setup 6`), compiler le script sur une app image existante (`copybot-ui\target\dist\Copybot`, sinon `mvn -o -q -Pdist package -DskipTests -pl copybot-ui -am`) et vérifier que `copybot-<v>-setup.exe` sort ; sinon le dire dans le rapport. Le YAML est relu à la main (pas d'exécution locale).
- [ ] **Step 3: Commit** — `Release: an Inno Setup installer (per user or for all users) and a workflow that builds it with the portable zip into a draft release on a v* tag`.

### Task 4: IHM — vérification au démarrage, bandeau, Préférences, déroulé

**Files:**
- Create: `copybot-ui/src/main/java/com/copybot/ui/update/UpdateService.java` (orchestration, sans JavaFX autant que possible), éventuellement un contrôleur/une fenêtre de progression
- Modify: `CopybotMainUi.java` (lancer la vérification due au démarrage, en arrière-plan), `MainController.java` + `main-view.fxml` (bandeau), `PreferencesController.java` + `preferences-view.fxml` (section « Mises à jour »), bundles en/fr/it + `UiBundleTest`
- Test: `copybot-ui/src/test/java/com/copybot/ui/update/UpdateServiceTest.java`

**Interfaces:** Consumes Task 1 (`AppVersion`, `Release`, `UpdateCheck`, `Download`, `UpdateException`, préférences) et Task 2 (`InstallMode`, `UpdateApply`), `AppInfo.version()`, `AppFolders` (dossier de l'appli via `jpackage.app-path`, dossier de données pour `-LogDir` = `logsDir()`), `CopybotMainUi.requestExit`, `MainController.isBusy`.

Comportement (spec §2-§5) : vérification automatique au démarrage si `updateCheck`, `AppVersion.isRelease(AppInfo.version())`, `jpackage.app-path` posé et `UpdateCheck.due` ; date enregistrée après tout essai ; échec automatique journalisé seulement. Bandeau si `compare(release, current) > 0` : texte `update.available` (« Copybot {0} est disponible. »), boutons `update.install` / `update.notes` (via `HostServices`), croix qui masque jusqu'au prochain démarrage ; bouton d'installation grisé (`update.busy`) tant que `isBusy()`, et sous un autre OS que Windows. Au clic : passer par la sortie habituelle (confirmation d'une pipeline non enregistrée ; refus = rien) puis télécharger l'asset du mode (`copybot-<v>-setup.exe` ou `copybot-<v>-windows-x64.zip` ; absent → `update.error.no-asset`) dans `Files.createTempDirectory("copybot-update-")` avec une fenêtre de progression + Annuler ; puis INSTALLED : lancer `setupCommand` ; PORTABLE : `extractPortable`, `writeScript`, lancer `scriptCommand` (PID = `ProcessHandle.current().pid()`, exe = `jpackage.app-path`) ; puis quitter. Échec : supprimer le dossier temporaire, message (`update.error.*`), l'appli continue. Préférences : version actuelle, case `update.auto` (`updateCheck`), bouton `update.check-now` qui rapporte toujours (`update.up-to-date`, `update.available`, ou l'erreur), grisé avec la raison `update.unavailable-dev` hors conditions du §1. Clés `update.error.*` de Task 1 + `update.error.no-asset`, `update.error.zip` (extraction refusée), `update.downloading`, `update.cancel` dans les trois bundles.

- [ ] **Step 1: Write the failing tests** — `UpdateServiceTest` (sans JavaFX, `UpdateCheck` sur un serveur local) : `aDueCheckFindsANewerRelease` ; `anOlderOrEqualReleaseIsNotOffered` ; `noCheckOutsideThePackagedApp` (pas de `jpackage.app-path` ou version `-SNAPSHOT` → aucun appel réseau) ; `aFailedCheckIsStillRecordedAsDone` ; `theUpdateIsRefusedWhileBusy` ; `theAssetFollowsTheInstallMode` (INSTALLED → setup.exe, PORTABLE → zip) ; `UiBundleTest` avec les nouvelles clés.
- [ ] **Step 2: Run to verify they fail** — `mvn -o -q -pl copybot-ui -am test -Dtest=UpdateServiceTest+UiBundleTest -Dsurefire.failIfNoSpecifiedTests=false`.
- [ ] **Step 3: Implement** selon Comportement ; le réseau et le disque hors du fil JavaFX (`Background.run`).
- [ ] **Step 4: Run to verify they pass** — `mvn -o -q verify`.
- [ ] **Step 5: Commit** — `Updates in the UI: a daily check at startup, a banner to update, an Updates section in the preferences`.

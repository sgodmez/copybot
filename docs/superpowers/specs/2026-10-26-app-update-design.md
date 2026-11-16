# Mise à jour de Copybot depuis GitHub

Date : 2026-10-26
Statut : validé (décidé avec Steven ; Steven a demandé d'enchaîner sans relecture)

## Contexte

Préalable à la première release : une 1.0 sans mise à jour ne saurait jamais qu'une 1.1 existe. Le mécanisme reprend
celui que synoRight a reçu le 2026-10-26 (`PhotostationAdmin`, package `sk.synoAdmin.update`, spec
`docs/superpowers/specs/2026-10-25-mises-a-jour-design.md` de ce dépôt-là) : même déroulé, mêmes conventions, **sans
bibliothèque partagée** (Steven veut le même mécanisme dans les deux applis, recopié). synoRight sera aligné ensuite
dans son dépôt (notamment le remplacement en mode zip, qu'il n'a pas). Les plugins (publication, installation, mise à
jour depuis l'IHM) sont le projet suivant ; celui-ci sépare « vérifier / télécharger / contrôler l'empreinte » pour
qu'il les réutilise.

Copybot est autoportant depuis le 2026-10-26 (spec `2026-10-26-portable-data-design.md`) : dossier de l'appli = code
+ `plugins`, dossier de données = `config.json`, `prefs.json`, `logs` (à côté de l'exe s'il est modifiable, sinon
`%APPDATA%\Copybot`).

## Objectifs

- Au plus une vérification automatique par 24 h ; un bandeau annonce une nouvelle version, un bouton la met à jour.
- La mise à jour marche quel que soit le mode d'installation : installateur pour l'utilisateur seul, installateur pour
  tous les utilisateurs (`Program Files`, UAC), zip portable.
- Elle ne perd rien de ce qui n'appartient pas à la version : `config.json`, `prefs.json`, `logs`, `plugins`.
- Une mise à jour ratée (réseau, empreinte fausse, annulation) ne remplace rien : Copybot continue.
- La CI produit les fichiers de release (installateur, zip) sur un tag.

## Non-objectifs

- Plugins (projet suivant) ; alignement de synoRight (son dépôt) ; signature des exécutables ; Linux/macOS (Windows
  seulement pour l'application de la mise à jour ; la vérification et le bandeau ne dépendent pas de l'OS mais le
  bouton n'est proposé que sous Windows) ; canal de préversions ; commande CLI ; retour arrière automatique.

## 1. Version et releases

- La version de Copybot est celle du projet Maven (`AppInfo.version()`, ressource filtrée). Une release porte le tag
  `v<version>` (ex. `v1.0.0`) ; la CI refuse un tag différent de la version du pom.
- *Choix de l'implémentation :* publier = passer à la main la version du pom (racine et modules) à `X.Y.Z` (trois
  nombres, sans suffixe : la CI le vérifie) dans le commit tagué, puis revenir à la `-SNAPSHOT` suivante ; le README
  décrit ces étapes. jpackage reçoit alors la même version.
- Comparaison (comme `AppVersion.compare` de synoRight) : segments séparés par `.` comparés numériquement (segment
  absent = 0) ; un suffixe `-xxx` rend la version plus petite que la même sans suffixe ; un segment non numérique se
  range après les numériques.
- Pas de version connue (`?`), version en `-SNAPSHOT`, ou appli lancée hors de l'image jpackage (propriété
  `jpackage.app-path` absente, c.-à-d. dev / IntelliJ / CLI) : aucune vérification automatique, bouton de vérification
  grisé avec la raison.
- Fichiers d'une release (noms exacts) : `copybot-<version>-setup.exe` (installateur Inno Setup) et
  `copybot-<version>-windows-x64.zip` (app image dans un dossier racine `Copybot/`).

## 2. Vérification

- `GET https://api.github.com/repos/sgodmez/copybot/releases/latest` (constante), anonyme, en-têtes
  `Accept: application/vnd.github+json` et `User-Agent: Copybot/<version>`, `java.net.http.HttpClient`, délais de
  connexion et de requête de 10 s, proxy système (`java.net.useSystemProxies=true` sauf s'il est déjà posé),
  redirections suivies. `releases/latest` exclut brouillons et préversions.
- Réponse lue : `tag_name` (sans le `v` initial), `html_url` (notes de version), `assets[]` (`name`,
  `browser_download_url`, `size`, `digest` au format `sha256:<hex>`). Un statut autre que 200 ou une réponse
  illisible = échec de vérification (message lisible), sauf un 404 : aucune release publiée encore (la vérification
  manuelle le dit, l'automatique le journalise).
- Automatique : au démarrage de l'IHM, en arrière-plan, si la vérification est activée (préférence `updateCheck`,
  vrai par défaut), si les conditions du §1 sont réunies et si elle est due : dernière vérification
  (`updateLastCheck`, secondes epoch dans `prefs.json`) absente, dans le futur, ou d'il y a 24 h ou plus. La date est
  enregistrée après une vérification réussie ou non (pas de nouvel essai à chaque démarrage hors ligne). Un échec
  automatique est seulement journalisé.
- Manuelle : bouton « Rechercher les mises à jour » des Préférences, toujours possible dans les conditions du §1 ;
  dit toujours le résultat (« à jour », la nouvelle version, ou l'erreur) et enregistre la date.

## 3. Ce que voit l'utilisateur

- Bandeau dans la fenêtre principale quand une version plus récente existe : « Copybot <x> est disponible. » avec
  « Mettre à jour » et « Notes de version » (ouvre `html_url`), et une croix pour le masquer jusqu'au prochain
  démarrage.
- Préférences, section « Mises à jour » : version actuelle, case « Rechercher automatiquement les mises à jour »
  (`updateCheck`), bouton « Rechercher les mises à jour » et le résultat de la dernière recherche.
- « Mettre à jour » n'est possible que lorsqu'aucune opération ne tourne (préparation, analyse, copie) : sinon le
  bouton est grisé avec la raison, il n'interrompt jamais une copie. Il passe par la sortie habituelle de l'appli
  (`requestExit`) : une pipeline modifiée non enregistrée est proposée à l'enregistrement, un refus annule la mise à
  jour avant tout téléchargement.
- « Mettre à jour » est refusé d'emblée, avec un message, quand un autre Copybot tourne depuis le même dossier de
  l'appli (ses fichiers ne pourraient pas être remplacés) ; ce processus et son lanceur ne comptent pas.
- Dossier de l'appli non modifiable (copie portable sous `Program Files`, installation pour tous les utilisateurs) :
  avant le téléchargement, un avertissement dit que Windows demandera l'accord d'un administrateur (Annuler arrête là).
- Téléchargement : fenêtre de progression avec Annuler.
- Textes en, fr, it.

## 4. Téléchargement et contrôle

- Le fichier qui correspond au mode (§5) est téléchargé dans un dossier temporaire propre à cette mise à jour
  (`Files.createTempDirectory("copybot-update-")`), en flux, avec progression et annulation. Abandon si aucun en-tête
  n'arrive en 30 s ou si aucun octet n'arrive pendant 60 s.
- Empreinte SHA-256 du fichier comparée au `digest` de l'asset. **Un asset sans `digest` est refusé.** Taille
  différente de `size` = échec.
- Échec, annulation ou empreinte fausse : le dossier temporaire est supprimé, rien n'est remplacé, message lisible.
- Cette partie (vérification §2 + téléchargement contrôlé) ne dépend pas de l'IHM : le projet plugins la réutilisera.

## 5. Application selon le mode d'installation

Dossier de l'appli = parent de `jpackage.app-path`. Mode **installé** si `unins000.exe` y est présent, sinon
**portable**.

### Installé (installateur, pour l'utilisateur seul ou pour tous)

- Asset `copybot-<v>-setup.exe`. Copybot lance
  `copybot-<v>-setup.exe /SILENT /SUPPRESSMSGBOXES /NORESTART /CLOSEAPPLICATIONS /RELAUNCH` sans attendre, puis
  quitte. L'installateur remplace les fichiers (Inno gère les fichiers verrouillés) et relance Copybot. Installation
  précédente pour tous les utilisateurs : l'installateur reprend ce mode et demande l'UAC.
- *Choix de l'implémentation :* pas d'attente de la fin de Copybot côté installateur au-delà de
  `CloseApplications=yes` (le Restart Manager ferme Copybot s'il tourne encore), comme synoRight.

### Portable (zip)

- Asset `copybot-<v>-windows-x64.zip`, décompressé dans le dossier temporaire (refus d'une entrée qui sort du
  dossier ou d'un zip sans dossier racine `Copybot/` contenant `Copybot.exe`).
- Copybot écrit dans ce dossier temporaire un script PowerShell `apply-update.ps1` et le lance détaché
  (`%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe -NoProfile -ExecutionPolicy Bypass -File ...`, par son
  chemin complet), avec : le PID de Copybot, le dossier de l'appli, le dossier décompressé, le chemin de l'exe à
  relancer, le dossier de données en vigueur (celui qu'utilise Copybot, `AppFolders`) pour le journal, et le
  `--config-file` de Copybot s'il a été lancé avec. Puis Copybot quitte. Le script est une ressource de l'appli copiée
  dans le dossier temporaire ; le dossier décompressé doit être directement dedans.
- Le script : attend la fin du processus (au plus 60 s, sinon abandon sans rien toucher) ; remplace **seulement ce qui
  appartient à l'app image** : les dossiers `app` et `runtime` en miroir (`robocopy <nouveau>\<d> <appli>\<d> /MIR`),
  et les fichiers à la racine de la nouvelle version (`Copybot.exe`, `Copybot.ico`...) copiés sans rien supprimer à la
  racine — ainsi `plugins`, `config.json`, `prefs.json`, `logs` et tout fichier posé par l'utilisateur à la racine
  restent ; relance `Copybot.exe` ; supprime le dossier temporaire ; écrit ce qu'il fait dans `logs\update.log` du
  dossier de données.
- Détail (aligné sur le `apply-update.ps1` final de synoRight) : l'attente couvre le PID et tout processus lancé depuis
  le dossier de l'appli, dans un seul budget de 60 s (un processus nommé comme l'appli dont le chemin est illisible,
  instance élevée, compte comme en cours) ; au-delà, l'ancienne version est relancée, le dossier temporaire supprimé ;
  `app` et `runtime` par `robocopy /MIR /R:2 /W:1`, fichiers racine par `robocopy <nouveau> <appli> /LEV:1 /R:2 /W:1`,
  arrêt au premier code ≥ 8 (pas de `runtime` copié après un `app` à moitié copié) puis relance ; exécutables par
  chemin complet (`robocopy.exe` de `System32`, `powershell.exe` de `$PSHOME`, `cmd` de `%ComSpec%`) ; le dossier
  supprimé est le parent de `NewDir` seulement s'il est le dossier du script (et ni la racine d'un lecteur, ni le
  dossier de l'appli, ni un de ses parents), sinon c'est journalisé ; la relance reprend le même `--config-file`.
- Dossier de l'appli non modifiable (zip décompressé à la main dans `Program Files`) : seule la copie est lancée élevée
  (le même script avec `-CopyOnly`, `Start-Process -Verb RunAs -Wait`, une demande UAC) ; l'attente, la relance (jamais
  en administrateur : le dossier de données suivrait le dossier de l'appli) et le nettoyage restent au script non
  élevé. UAC refusée ou en échec : journalisé, l'ancienne version est relancée, le dossier temporaire supprimé.
- Échec de robocopy (code ≥ 8) : le script le journalise, arrête la copie et relance la version présente ; pas de
  retour arrière automatique (non-objectif).

### Au démarrage

- En arrière-plan, les dossiers `copybot-update-*` du dossier temporaire du système vieux de plus d'un jour sont
  supprimés (l'installateur laissé par une mise à jour en mode installé, un remplacement interrompu).

## 6. Installateur et publication

- Script Inno Setup `copybot-ui/src/installer/copybot.iss` (nouveau) : `AppId` fixe (GUID constant),
  `AppName=Copybot`, `AppVersion` passé par la CI (`/DAppVersion`), source = l'app image `target/dist/Copybot`
  (`/DSourceDir`), `PrivilegesRequired=lowest`, `PrivilegesRequiredOverridesAllowed=dialog` (choix « pour moi » / « pour
  tous » à la première installation ; une mise à jour reprend le choix précédent), `DefaultDirName={autopf}\Copybot`,
  `CloseApplications=yes`, `RestartApplications=no`, raccourci menu Démarrer (bureau en option), sortie
  `copybot-<v>-setup.exe`. `[Run]` : lancement de `Copybot.exe` en fin d'installation normale, et en silencieux
  seulement si `/RELAUNCH` est passé. `[InstallDelete]` vide `{app}\app` et `{app}\runtime` avant la copie : un
  fichier abandonné d'une version à l'autre ne reste pas (comme le miroir du mode portable). La désinstallation ne supprime pas les données (`config.json`, `prefs.json`,
  `logs`) ni `plugins`.
- *Choix de l'implémentation :* aucun fichier `.sha256` de secours : GitHub calcule le `digest` de tout fichier
  attaché à une release, brouillons compris.
- CI (`.github/workflows/release.yml`, fichier séparé, `permissions: contents: write`) : job `release` sous
  `windows-latest`, JDK 25 Temurin, déclenché par un tag `v*` :
  vérifie que le tag vaut `v<version du pom>`, `mvn -B -Pdist package -DskipTests`, installe Inno Setup
  (`choco install innosetup`), compile le script, fait le zip (`Copybot/` à la racine), puis crée une release
  **brouillon** avec les deux fichiers (`softprops/action-gh-release`), à publier à la main. Le job de build existant
  ne change pas.

## Laissé au constructeur

- Les noms de classes et le découpage du package de mise à jour (côté IHM, sans dépendance JavaFX pour la partie §2/§4).
- La mise en page du bandeau et de la section des Préférences (dans le style des bandeaux existants).
- Le GUID de l'`AppId`.

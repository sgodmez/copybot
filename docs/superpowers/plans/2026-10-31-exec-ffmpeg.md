# Exécutable externe et ffmpeg — plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan
> task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** plugin `copybot-plugin-exec` avec `exec.process`, `ffmpeg.video` (GPU), `ffmpeg.image` (HEIC → JPEG) et
`ffmpeg.thumbnail` ; ffmpeg trouvé ou téléchargé (Windows) à la demande.

**Architecture:** un `ProcessRunner` interne au plugin (délai, annulation, arbre de processus, fin de stderr,
progression) ; des constructeurs d'arguments ffmpeg purs (testables sans ffmpeg) ; un `FfmpegLocator` (chemin,
copie téléchargée, PATH, version) et un `FfmpegDownloader` (ZIP + SHA-256) ; un ajout d'API `PluginFolders`.

**Spec:** `docs/superpowers/specs/2026-10-31-exec-ffmpeg-design.md` (contraignante).

## Global Constraints

- JDK 25 ; `mvn -q verify -pl <module> -am` ; `mvn -q verify` à la racine en fin de plan.
- API 1.2 non publiée : ajouts seulement, empreinte régénérée (`-Dplugin.api.update=force`), diff = lignes ajoutées.
- Plugin : même gabarit que `copybot-plugin-notify-webhook` (pom avec `plugin.code`, version 1.0.0, assembly en
  dernier ; module-info `provides IPlugin`, `opens …i18n`, config ouverte à Gson ; `catalog.json` en/fr/it ;
  bundles en/fr/it + `PluginBundleTest`) ; module ajouté dans `copybot-plugin/pom.xml`. Aucune dépendance externe
  (JDK seulement : `java.net.http`, `java.util.zip`).
- Aucun test ne dépend du réseau ni d'un ffmpeg installé, sauf les tests d'intégration sautés en son absence.
- Jamais d'argument de processus dans les journaux ni dans les messages d'erreur (seulement le programme, le code de
  retour et la fin de stderr).
- Un commit par tâche, message en anglais, trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. Annulation et délai : le processus fils et ses descendants sont tués, aucun processus orphelin, interruption
   propagée.
2. Fichiers de travail : supprimés dans `afterItem`/`afterAll`, jamais l'original ; taille mise à jour.
3. Téléchargement : SHA-256 vérifié avant extraction, extraction sans « zip slip », installation atomique, rien en cas
   d'échec ou d'annulation.
4. Pas de fuite de secrets (arguments).
5. Choix `auto` de l'encodeur fait une fois par run, repli cpu, ressource `gpu`/`cpu` cohérente.

## Task 1 — API : dossier de données d'un plugin

- [ ] `com.copybot.plugin.api.PluginFolders.dataDirectory(String pluginCode)` (nom validé : `[a-z0-9-]+`), base
  renseignée par le moteur au démarrage (`AppFolders.dataDir()/plugin-data`), repli `java.io.tmpdir/copybot-plugin-
  data` ; création à la demande. Tests. Empreinte.

## Task 2 — Squelette du plugin + `ProcessRunner` + `exec.process`

- [ ] Module, plugin, bundles, catalogue (notice GPL ffmpeg dans `license`).
- [ ] `ProcessRunner` (spec §2) + dossier de travail du run (schéma `FileBufferAction`).
- [ ] `exec.process` (spec §3) : config, variables, dryRun, avertissement `{out}` absent.
- [ ] Tests (spec §8, partie processus).

## Task 3 — Trouver et télécharger ffmpeg

- [ ] `FfmpegLocator` (spec §4, ordre, version, cache par run), `FfmpegDownloader` (Windows, release BtbN `latest`,
  `checksums.sha256`, ZIP, installation atomique, annulation, progression), `configWarnings`.
- [ ] Vérifier sur la page de la release les noms exacts d'archive et le format de `checksums.sha256` (WebFetch) ;
  les noter dans le rapport.
- [ ] Tests avec faux serveur HTTP et faux ffmpeg.

## Task 4 — `ffmpeg.video`

- [ ] Configuration et constructeur d'arguments (spec §5), choix `auto` (essai par encodeur, une fois par run,
  thread-safe), ressources, progression, éléments non vidéo inchangés, dryRun.
- [ ] Tests unitaires d'arguments par encodeur/option ; intégration réelle sautée sans ffmpeg.

## Task 5 — `ffmpeg.image` et `ffmpeg.thumbnail`

- [ ] Spec §6 et §7 (extensions, qualité, taille, date du fichier produit, suffixe `-thumb`, image à 10 % pour une
  vidéo, filtrage des autres). Version ≥ 7.1 exigée pour HEIC.
- [ ] Tests unitaires + intégration sautée sans ffmpeg.

## Task 6 — README et vérification

- [ ] README : section plugin `exec` (exemple : original copié + branche secondaire « Vidéos compressées » avec
  `ffmpeg.video`, branche « Miniatures » avec `ffmpeg.thumbnail`, HEIC → JPEG), téléchargement, GPU, limites ; tableau
  des modules et liste des plugins.
- [ ] `mvn -q verify` à la racine ; écarts notés dans la spec.

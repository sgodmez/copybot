# Post-traitement — plan

Spec : `docs/superpowers/specs/2026-10-25-post-process-design.md`. TDD à chaque tâche, `mvn -q verify` (JDK 25) à la fin.

1. **Répertoires créés** — `WriteContext` gagne le rappel `directoryCreated(Path)` (composant `Consumer<Path>`, sans
   effet par défaut, gardé par `withProgress`) ; `SafeFileWriter.write` reçoit ce rappel et signale, du plus haut au
   plus bas, les répertoires absents avant `createDirectories` ; `FileWriteAction` le transmet.
   Tests : `WriteApiTest`, `SafeFileWriterTest`.
2. **Moteur** — record `PostProcessConfig(command, args)` dans `PipelineConfig.postProcess` (constructeur à 7
   arguments conservé) ; `PostProcess` : développe `{createdDirs}`, ne lance rien sans commande ni répertoire quand la
   variable est utilisée, lance via un `ProcessStarter` injectable (vrai : `ProcessBuilder`, répertoire du programme,
   sorties ignorées). `MainExecutor` réunit les répertoires (contexte d'écriture) et appelle le post-traitement dans
   `execute` et `runSinglePhase` avant le statut final (SUCCESS/ERROR) ; échec ⇒ avertissement `post-process.failed`.
   Tests : `PostProcessTest`, `MainExecutorPostProcessTest`, lecture JSON.
3. **IHM** — `PipelineDocument` : `postProcessCommand()/postProcessArgs()/setPostProcess(command, args)` ; éditeur,
   nœud Pipeline : section « Après la copie » (programme + Parcourir, arguments un par ligne, aide) ; clés en/fr/it.
   Tests : `PipelineDocumentTest`, `UiBundleTest`.
4. **Doc** — README (bloc `postProcess`), relecture, `mvn verify`.

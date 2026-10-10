# Ordonnancement par ressources du pipeline copybot

Date : 2026-07-26
Statut : validé (design), en attente de plan d'implémentation

## Contexte

Le moteur copybot exécute des pipelines de traitement de fichiers (inputs → analyses → traitements → output) fournis par des plugins. L'exécution actuelle est embryonnaire : les listings d'inputs sont séquentiels, `MainExecutor.doNext()` est vide, et les champs `maxConcurrency`/`priority` de `PipelineStepConfig` ne sont pas branchés. Le problème à résoudre est la **contention de ressources partagées** :

- deux steps qui lisent/écrivent le même disque physique (ou deux partitions du même disque) se pénalisent mutuellement ;
- un traitement lourd (ex. transcodage vidéo) sature le CPU/GPU et doit être limité en concurrence ;
- à l'inverse, des travaux sur des ressources indépendantes (deux disques distincts) doivent tourner en parallèle.

L'ancienne ébauche partait vers un scheduler central événementiel (callbacks, files par step, comptage manuel), naturel sous Java 17 mais complexe. Le projet est maintenant sur Java 25 : les threads virtuels changent l'équation.

## Décisions actées

1. **Déclaration hybride des contraintes** : le plugin déclare ce que son action consomme (CPU, GPU, chemins touchés), le moteur détecte automatiquement les volumes disque depuis les chemins, la config utilisateur peut surcharger.
2. **Pipelining configurable par pipeline** : flag `startProcessingWhileListing` ; le mode « deux phases » est une simple barrière devant le même ordonnanceur, pas un second chemin de code.
3. **Ressources = chaînes nommées libres** avec capacité, extensibles par les plugins sans modifier le moteur.
4. **Architecture : threads virtuels + arbitre minimal (`ResourceRegistry`)**, plutôt qu'un scheduler central. Le registre est le point de découplage : ses politiques internes peuvent évoluer (priorités…) sans toucher au code des items.

## 1. Modèle de ressources

Une ressource = un nom + une capacité (nombre de permis). Conventions :

- `disk:<disque>` — fournies par le moteur (détection automatique du disque physique, voir la fin du document) ;
- `cpu`, `gpu` — noms standards ;
- noms custom de plugins (ex. `net:flickr` pour un quota d'appels API).

Chaque tâche déclare une **empreinte** : l'ensemble des ressources qu'elle consomme (un permis chacune). **Tout est une tâche avec une empreinte, y compris le listing des inputs** : listing et copie sur le même disque se disputent les mêmes permis ; la capacité du disque décide s'ils cohabitent (SSD) ou se sérialisent (HDD). Aucun cas particulier.

## 2. ResourceRegistry

Composant unique du moteur (~80 lignes, un seul verrou) :

- `acquireAll(Set<String> names)` — bloque jusqu'à ce que **toutes** les ressources soient libres simultanément. Tout-ou-rien : on ne tient jamais un permis en attendant les autres → pas de deadlock, pas de slot gaspillé. Interruptible (annulation).
- `releaseAll(Set<String> names)` — au release, parcours des attentes dans l'ordre, on sert le **premier qui rentre** (first-fit) pour éviter le head-of-line blocking des grosses tâches multi-ressources.
- **Anti-famine** : un demandeur doublé plus de K fois (défaut : 5) ou attendant plus de X secondes (défaut : 60 ; le temps passé en pause ne compte pas) passe en mode strict — plus de dépassement possible jusqu'à ce qu'il soit servi. Constantes internes du registre en v1 (pas de config).
- Ressource inconnue à l'acquire → auto-enregistrée avec la capacité par défaut de sa famille (§5).
- `snapshot()` — capacité / utilisé / attentes par ressource, pour l'UI.

## 3. Modèle d'exécution

`Executors.newCachedThreadPool()` → `Executors.newVirtualThreadPerTaskExecutor()`. Supprimés : `doNext()`, `RunnableCallback`, `PipelineStep.queue`, `PipelineStep.runningCount`.

- **1 thread virtuel par step d'input** : acquiert `disk:<X>`, exécute `listFiles(...)`, relâche. Listings sur disques différents en parallèle.
- **1 thread virtuel par WorkItem**, créé dès l'émission par le listing. Déroulé séquentiel des steps :

```java
for (PipelineStep step : steps) {
    Set<String> footprint = resolveFootprint(item, step);
    registry.acquireAll(footprint);
    try { step.run(item); }
    finally { registry.releaseAll(footprint); }
    notifyWatcher();
}
```

- **Deux phases vs pipelining** : en mode deux phases, chaque thread d'item attend un `CountDownLatch` (fin de tous les listings) avant son premier step. Barrière conditionnelle, un seul chemin de code.
- **Annulation** : stop du moteur = interruption des threads virtuels ; `acquireAll` interruptible → libération propre.

## 4. Résolution de l'empreinte

Fusion de trois contributions pour chaque couple (item, step) :

1. **Plugin** — deux méthodes `default` ajoutées à `IAction` (rétro-compatibles, vides par défaut) :
   - `Set<String> requiredResources(WorkItem item)` → ex. `{"cpu"}`, `{"gpu"}`, `{"net:flickr"}` ;
   - `Set<Path> touchedPaths(WorkItem item)` → ex. répertoire cible de `FileWriteAction` ; converti en `disk:<volume>` par le moteur.
2. **Moteur** — ajoute automatiquement `disk:<volume>` de `item.getLocalLocation()` via `Files.getFileStore(path)`.
3. **Utilisateur** — `PipelineStepConfig.resources` (ajouts, optionnel) ; `maxConcurrency` existant enfin branché, implémenté comme ressource implicite `step:<n>` de capacité `maxConcurrency`.

## 5. Configuration des capacités

Dans `CopybotConfig` (niveau application : les capacités décrivent la machine, pas un pipeline) :

```json
"resources": {
  "cpu": 8,
  "gpu": 1,
  "disk:*": 2,
  "disk:C": 4
},
"resourceGroups": [["disk:D", "disk:E"]]
```

- Patterns par préfixe pour les défauts, valeurs exactes pour les surcharges. Défauts embarqués : `cpu` = nb de cœurs, `gpu` = 1, `disk:*` = 2.
- `resourceGroups` : les membres d'un groupe deviennent des alias d'une même ressource — c'est la réponse (déclarative, v1) au cas « deux partitions du même disque physique ».

## 6. Observabilité

- `WorkItemExecution` : statut `PENDING` → `WAITING_RESOURCES` (avec noms attendus) → `RUNNING` (step courant) → `DONE` / `ERROR`.
- `PipelineState` expose le snapshot du registre ; le `watcher` existant diffuse le tout vers l'UI (affichage « en attente de gpu », jauges par ressource).

## 7. Erreurs

Exception dans un step → item `ERROR` avec cause, ressources relâchées (`finally`), les autres items continuent. Rapport en fin de run. Pas de stop-on-error global en v1.

## 8. Tests

- **Unitaires `ResourceRegistry`** : tout-ou-rien, first-fit, anti-famine, interruption — déterministes (latches, pas de sleeps).
- **Intégration `MainExecutor`** : actions factices enregistrant la concurrence max observée par ressource ; assertion : jamais supérieure à la capacité.
- **Modes** : deux phases (aucun traitement avant fin des listings) vs pipelining (traitement démarré pendant listing).

## Hors périmètre v1 → idées v2

- **Priorités** : brancher `PipelineStepConfig.priority` dans le registre (réveil par priorité au lieu de first-fit) ; heuristique « tâches longues d'abord » pour réduire le temps de drain en fin de run.
- ~~**Auto-détection du disque physique**~~ — fait le 2026-10-10, voir ci-dessous.

## Disque physique (2026-10-10)

- `DiskResolver` nomme la ressource d'après le disque physique : `disk:PhysicalDriveN` sous Windows (FFM sur kernel32 : `GetVolumePathNameW` → `GetVolumeNameForVolumeMountPointW` → `IOCTL_VOLUME_GET_VOLUME_DISK_EXTENTS`, sans droits admin), `disk:sda` / `disk:nvme0n1` sous Linux (`st_dev` → `/sys/block`, en suivant `slaves/` quand un dm/md ne repose que sur un disque ; btrfs via `/proc/self/mountinfo`).
- Repli sur un nom de volume **stable** (plus l'étiquette de `FileStore.toString()`, qui rendait les noms de la config introuvables) : `disk:D:\`, `disk:\\serveur\partage\`, `disk:/mnt/nas`. Cas laissés au repli : partages réseau, volumes sur plusieurs disques (RAID, LVM multi-disques, volumes fractionnés, Storage Spaces), autres OS.
- Type de disque (`DiskKind`) → capacité par défaut : NVMe 8, SSD 4, HDD 2, inconnu 2. Windows : `IOCTL_STORAGE_QUERY_PROPERTY` (BusType NVMe, puis pénalité de seek) ; Linux : nom `nvme*`, puis `queue/rotational`. Un `disk:*` configuré reste prioritaire.
- Config : un nom `disk:` qui est un chemin existant (`disk:D:\`, `disk:D:`, `disk:/home`) est traduit vers son disque ; deux capacités sur le même disque → la plus petite. `resourceGroups` reste pour les cas de repli.
- Cache 30 s par volume (Windows) / par `st_dev` (Linux) : un disque amovible peut revenir sous un autre numéro. Coût mesuré identique à l'ancien `getFileStore` (~0,2 ms par chemin).
- Lancement : `--enable-native-access=com.copybot.engine` (jpackage, surefire) ; sans, la JVM avertit seulement, et si l'accès natif est refusé on retombe sur la racine du volume.
- **Ressources pondérées** : une tâche prend N permis (ex. transcodage 4K = 4 slots `cpu`) ; couvre aussi la bande passante réseau.
- **Pause / reprise** du pipeline (le registre gèle les grants).
- **Stop-on-error configurable** par pipeline.
- **Historique et métriques** par item (timing par step — TODO existant dans `WorkItem`), pour visualiser où part le temps.
- **Reprise d'un run interrompu** (persistance de l'état des items).

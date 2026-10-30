# Actions de fin de run et plugins e-mail, webhook, archive — conception

## Besoin

Aujourd'hui, une seule chose peut se passer après une copie : `postProcess` lance un programme (synoRight) sur les
répertoires créés. Steven veut être prévenu et garder une trace d'un import : un message (e-mail, notification sur le
téléphone), et une archive des fichiers copiés avec leurs empreintes. Décisions prises avec lui le 2026-10-30 :

- la fin de run est une **liste ordonnée d'actions**, chacune avec des **conditions** cochées parmi succès, erreur,
  annulé ;
- l'échec d'une action est un **avertissement** et n'empêche pas les suivantes ;
- les actions reçoivent un **bilan** du run, enrichi au fil de la liste (une action publie une valeur, les suivantes
  la lisent : le chemin de l'archive dans le mail) ;
- `postProcess` devient une **action intégrée** de cette liste ; l'ancien bloc est converti à la lecture ;
- trois plugins séparés : **e-mail**, **webhook** (préréglages ntfy, Discord, Slack, Telegram), **archive** (ZIP +
  manifeste SHA-256) ;
- les **secrets** (mot de passe SMTP, jeton) ne sont jamais écrits dans le pipeline (souvent sur le NAS) : coffre local
  (choix de Steven).

Non-objectifs : notification de bureau (l'IHM affiche déjà le statut), relance automatique d'une action échouée,
actions de fin de **préparation**, envoi des fichiers eux-mêmes (le FTP/S3 sont des sorties, pas des actions de fin).

## API des plugins (1.2, non publiée : ajouts sans changement de version)

Dans `com.copybot.plugin.api.action` :

```java
public interface IRunEndAction extends IAction {
    void runEnded(RunReport report, RunEndContext context);
}
public interface RunEndContext {
    void publish(String key, String value);   // lu par les actions suivantes : report.outputs()
    void warn(String message);                // avertissement du run, l'action continue
}
public record RunReport(String pipelineName, Path pipelinePath, RunOutcome outcome, Instant started,
                        Instant copyStarted, Instant ended,
                        RunCounts counts, List<RunItem> items, List<WrittenFile> writtenFiles,
                        List<Path> createdDirectories, List<String> warnings, String failure, String runId,
                        Map<String, String> outputs) { Duration duration(); }
public enum RunOutcome { SUCCESS, ERROR, CANCELLED }
public record RunCounts(int written, int skipped, int errors, int filtered) {}
public record RunItem(String name, String source, ItemOutcome outcome, Path target, String reason) {}
public enum ItemOutcome { WRITTEN, SKIPPED, ERROR, FILTERED }
public record WrittenFile(Path target, String sha256, long size) {}   // principal et compagnons
```

- `IRunEndAction.defaultTimeout()` : `default Duration defaultTimeout() { return Duration.ofSeconds(60); }` ;
  `Duration.ZERO` = sans limite. `post.process` : 30 s ; `email.send`, `webhook.send` : 60 s ; `archive.zip` : sans
  limite (un ZIP de plusieurs Go vers le NAS).
- `RunReport.duration()` = `ended - copyStarted` (`copyStarted` nul si la copie n'a pas commencé ⇒ `ended - started`) ;
  en mode plan, l'attente de la confirmation n'est donc pas comptée.
- `IPlugin` : `default List<ActionDefinition<? extends IRunEndAction>> getRunEndActions() { return List.of(); }`.
- `WriteContext` (un `record`) : nouveau composant rappel `fileWritten(Path target, String sha256, long size)`, sans
  effet par défaut — constructeurs existants conservés (surcharges), `withProgress` le garde ; appelé pour **chaque
  fichier écrit et vérifié** (principal et compagnons) par `FileWriteAction`/`GroupWriter` (l'empreinte est déjà
  calculée à chaque écriture) ; un plugin de sortie tiers peut l'appeler.
- Configuration : annotation `@Secret` sur un champ `String` d'un record de config, et `FieldHint.SECRET` dans le
  schéma (l'éditeur affiche alors un choix de secret).

## Moteur

### Configuration

```json
"runEnd": [
  { "action": "post.process", "when": ["success", "error"],
    "actionConfig": { "command": "D:\\progs\\synoRight\\synoRightGui.exe", "args": ["rights", "{createdDirs}"] } },
  { "plugin": "archive", "action": "archive.zip", "when": ["success"],
    "actionConfig": { "target": "\\\\NAS\\photo\\_imports\\{date}-{time}.zip" } },
  { "plugin": "notify-email", "action": "email.send", "when": ["success", "error", "cancelled"], "timeoutSeconds": 60,
    "actionConfig": { "host": "smtp.example.org", "user": "me", "password": "${secret:smtp}", "from": "…", "to": ["…"] } }
]
```

- `RunEndStepConfig(plugin, action, version, when, timeoutSeconds, actionConfig)` ; `when` absent ⇒
  `["success", "error"]` (le comportement de `postProcess`) ; valeurs inconnues ou liste vide ⇒ pipeline refusé ;
  `timeoutSeconds` absent ⇒ `defaultTimeout()` de l'action, `0` ⇒ sans limite. Résolu par `PluginEngine.resolve` via
  un `PipelineStepConfig` construit à partir de ses champs (plugin, action, version, actionConfig).
- **Migration** : `PipelineReader` réécrit l'arbre JSON avant Gson : un bloc `postProcess` avec une commande devient
  une première entrée `runEnd` `{ "action": "post.process", "when": ["success","error"], "actionConfig": {command,
  args} }` (le bloc `postProcess` est ignoré s'il y a déjà une entrée `post.process`). `PipelineConfig.postProcess`
  disparaît. L'éditeur fait la même conversion à l'ouverture, donc l'enregistrement écrit la nouvelle forme.
- `StepType.RUN_END` (ajouté partout où `StepType.getType` sert : catalogue, éditeur) ; refus si une action est
  introuvable (comme pour une étape). Les étapes de fin de run sont résolues **avant** les autres : un run qui échoue
  à résoudre une autre étape peut ainsi encore prévenir.
- `MainExecutor` reçoit de `CopybotEngine` le chemin du pipeline (nom = fichier sans extension) ; les constructeurs de
  test sans pipeline donnent un nom vide.

### Action intégrée `post.process`

Plugin `embedded`, comportement de `PostProcess` inchangé : programme lancé sans attendre, `{createdDirs}` remplacé
par un argument par répertoire créé (parents d'abord), rien lancé s'il n'y en a aucun, répertoire de travail = celui
du programme s'il est absolu ; échec de lancement ⇒ avertissement `post-process.failed`.

### Déroulé

- Quand : à la fin d'un **run lancé pour copier** — `execute` d'un plan, exécution en une phase, mode continu, mode
  automatique (`run` = préparer puis copier), CLI — quelle que soit l'issue : succès, erreur (y compris un échec
  pendant la préparation de ce run : carte absente, étape introuvable, `beforeAll` en échec ; `failure` renseigné),
  annulé (y compris avant le premier fichier). **Jamais** à la fin d'une préparation du mode plan (rien n'a été
  demandé à copier) ni d'une analyse différée.
- Où : après la sauvegarde du curseur, **avant** la publication du statut final (la CLI, l'IHM et les observateurs
  voient le statut final une fois les actions finies, avertissements compris). Le chemin d'annulation (aujourd'hui
  CANCELLED publié tout de suite) est réordonné pour cela, sans changer le comportement de l'analyse différée.
- Pendant les actions, l'état expose l'action en cours (`PipelineState.getRunEndStep()`, nom localisé, nul hors de
  cette phase) ; la barre d'état de l'IHM l'affiche (« Fin de run : Envoyer un e-mail… »).
- Les actions dont `when` contient l'issue s'exécutent **dans l'ordre**, une à une, chacune dans son propre fil avec son
  délai ; délai dépassé ⇒ fil interrompu, avertissement `run-end.timeout` ; exception ⇒ avertissement
  `run-end.failed` (nom de l'action + message) ; dans les deux cas on passe à la suivante. Un `cancel()` pendant les
  actions de fin interrompt l'action en cours et saute les suivantes (avertissement `run-end.cancelled`) ; l'issue du
  run, déjà décidée, ne change pas.
- `outputs` : ce que les actions précédentes ont publié (copie non modifiable pour chaque action).
- **Bilan** :
  - `started` : début de la première phase du run (préparation incluse) ; `ended` : avant la première action ;
  - `items` : les éléments traités dans **ce** run ; écartés : ceux sautés par le point de reprise ou ignorés dans le
    plan ; `WRITTEN` (écrit, cible), `SKIPPED` (sauté par la sortie, raison), `ERROR` (message), `FILTERED` (filtré par
    un traitement : il finit DONE sans écriture). L'exécution d'un élément conserve désormais comment il s'est terminé
    (écrit + cible, sauté par la sortie, filtré) : `ItemStatus` seul ne le dit pas (SKIPPED couvre aussi le point de
    reprise, DONE couvre aussi le filtrage) ;
  - `writtenFiles` : tous les `fileWritten` du run, dans l'ordre d'écriture ;
  - `warnings` : ceux du run à cet instant ; `pipelineName` : nom du fichier sans extension.
- Les avertissements des actions sont ajoutés au run comme les autres (IHM, CLI).

## Secrets

- Référence dans n'importe quelle valeur texte d'une configuration d'action (toutes les étapes, pas seulement la fin
  de run) : `${secret:nom}` ou `${env:NOM}`, remplacée **à la résolution de l'étape**, juste avant `loadConfig`. Le
  pipeline enregistré ne contient jamais la valeur ; la configuration résolue n'est jamais journalisée.
- Secret ou variable introuvable ⇒ préparation refusée : « le secret « nom » n'est pas défini sur cette machine »
  (« la variable d'environnement … ») ; secret présent mais indéchiffrable (autre compte Windows, autre PC) ⇒ message
  distinct « le secret « nom » ne peut pas être lu par cet utilisateur sur cette machine : saisissez-le à nouveau ».
  Toute résolution passe par là, y compris l'échantillon de l'éditeur et le plan à blanc : sans le secret, ils sont
  refusés de la même façon.
- **Coffre** : `secrets.json` **toujours dans le profil de l'utilisateur**, jamais dans le dossier de données portable
  (qui peut être sur le NAS) : Windows `%APPDATA%\Copybot\secrets.json`, autres systèmes
  `$XDG_CONFIG_HOME/copybot/secrets.json` (défaut `~/.config/copybot`). Contenu `{ "nom": "<valeur protégée en
  base64>" }`. Windows : `CryptProtectData`/`CryptUnprotectData` (DPAPI, portée utilisateur) via `java.lang.foreign`
  (déjà utilisé par `WindowsDiskProbe`) ; autres systèmes : base64, fichier créé avec les droits `rw-------`.
  Écriture atomique.
- Accès natif : `--enable-native-access=com.copybot.engine` est déjà passé (surefire, jpackage) : vérifier le lanceur
  CLI.
- **IHM** : menu Édition > Secrets… (liste des noms, ajouter, changer la valeur, supprimer ; valeur saisie masquée) ;
  un champ `@Secret` d'un formulaire d'action est une liste modifiable des noms de secrets (+ « Nouveau secret… »),
  qui écrit `${secret:nom}` ; on peut y taper `${env:NOM}`.
- **CLI** : `copybot secret set <nom>` (valeur lue sans écho), `copybot secret list`, `copybot secret delete <nom>`.
  L'option `--pipeline`, aujourd'hui obligatoire sur la commande racine, ne l'est plus pour la sous-commande
  `secret` (restructuration picocli au choix du constructeur ; les lignes de commande actuelles restent valides).

## IHM de l'éditeur

Le nœud « Après la copie » devient une **section** comme les traitements : actions de fin de run (catalogue
`RUN_END` des plugins installés + `post.process`), ajouter, supprimer, monter, descendre ; le formulaire d'une action
est le formulaire de configuration habituel, plus « Conditions » (trois cases : succès, erreur, annulé ; au moins
une) et le délai dans les réglages avancés. Textes en, fr, it.

## Plugins

Chacun est un module de `copybot-plugin` (version propre 1.0.0, `catalog.json`, bundles en/fr/it, ajouté aux modules
et aux chemins de plugins de développement) ; il ne déclare que des actions de fin de run.

### `notify-email` — action `email.send`

- Config : `host` (requis), `port` (défaut 465 avec `ssl`, 587 sinon), `security` (`starttls` | `ssl` | `none`, défaut `starttls`), `user`,
  `password` (`@Secret`), `from` (requis), `to` (liste, requis), `subject` (défaut
  `Copybot — {pipeline} : {outcome}`), `details` (`none` | `errors` | `all`, défaut `errors`).
- Message multipart texte + HTML : issue, durée, compteurs, répertoires créés, valeurs publiées (ex. chemin de
  l'archive), avertissements, puis la liste des éléments selon `details` (au plus 200 lignes, « … et N autres »).
- Bibliothèque : Jakarta Mail (implémentation Angus Mail), embarquée dans le plugin ; délai de connexion et de lecture
  bornés par le délai de l'action. Le plugin doit se charger dans l'image jlink de l'application (construite depuis
  `com.copybot.ui`) : vérifier avec `jdeps` les modules du JDK requis (activation, `java.desktop`…) ; s'il en manque,
  les ajouter à l'image (`-Pdist`), sinon changer de bibliothèque.

### `notify-webhook` — action `webhook.send`

- Config : `url` (requis ; peut contenir `${secret:…}`, ex. jeton Telegram), `preset` (`json` | `ntfy` | `discord` |
  `slack` | `telegram`, défaut `json`), `headers` (liste de `Nom: valeur`), `body` (gabarit ; vide ⇒ celui du
  préréglage).
- Variables du gabarit : `{pipeline}`, `{outcome}`, `{written}`, `{skipped}`, `{errors}`, `{duration}`, `{summary}`
  (une ligne lisible), `{output:clé}` ; échappées en JSON quand le corps est du JSON.
- Préréglages : `json` = le bilan complet en JSON (sans la liste des éléments écrits, erreurs, `outputs` et
  `createdDirectories` inclus) ; `ntfy` =
  texte brut `{summary}` + en-têtes `Title`/`Priority` (haute en cas d'erreur) ; `discord` = `{"content": …}` ;
  `slack` = `{"text": …}` ; `telegram` = `{"chat_id": "<à remplir>", "text": …}`.
- `java.net.http`, délai de l'action ; réponse hors 2xx ⇒ échec (avertissement avec le code et le début du corps).

### `archive` — action `archive.zip`

- Config : `target` (requis : chemin du fichier, variables `{date}` (aaaa-mm-jj), `{time}` (hhmmss), `{pipeline}`),
  `content` (`zip` | `manifest`, défaut `zip`), `includeSkipped` (faux), `baseFolder` (vide ⇒ répertoire commun des
  fichiers).
- `zip` : une archive des fichiers écrits du run (`writtenFiles`, + les cibles des éléments sautés si
  `includeSkipped`) avec un fichier `SHA256SUMS` (format `sha256sum` : `empreinte  chemin/relatif`) ; entrées
  stockées sans compression pour les médias (jpg, heic, raw, mp4, mov…), compressées sinon. `manifest` : le seul
  fichier `SHA256SUMS` écrit à `target`.
- Empreintes : celles calculées pendant la copie ; recalculées pour un fichier qui n'en a pas (sauté).
- Écriture sûre (fichier temporaire puis renommage), répertoires parents créés ; aucun fichier écrit ⇒ rien n'est
  produit (avertissement d'information) ; interrompue (délai, annulation) ⇒ le temporaire est supprimé, rien n'est
  publié.
- Publie `archive.path` (chemin final) pour les actions suivantes.

## Choix de conception (pris sans Steven, à sa demande)

Délais par action et « sans limite » pour l'archive ; statut final publié après les actions, action en cours
visible, annulation qui saute les actions restantes ; actions déclenchées par tout run lancé pour copier, y compris
s'il échoue en préparation, jamais par une préparation du mode plan ; étapes de fin résolues en premier ; coffre
toujours dans le profil utilisateur, message distinct pour un secret indéchiffrable ; durée = celle de la copie ;
ZIP par défaut (pas seulement le manifeste) ; préréglage webhook `json` = bilan sans la liste des fichiers écrits.

## Laissé au constructeur

Noms internes, découpage des classes, rendu exact du mail, versions des bibliothèques (les plus récentes publiées
depuis au moins deux semaines), tests (serveur SMTP de test, `com.sun.net.httpserver` pour le webhook).

## Écarts à la réalisation (2026-10-30)

Différences voulues entre cette conception et ce qui a été construit :

- Archive : les médias sont écrits en DEFLATED niveau 0 (`Deflater.NO_COMPRESSION`) et non en STORED : une seule
  passe, surcoût inférieur à 0,01 %, même intention « sans compression ».
- Archive : jamais d'écrasement ; une cible existante reçoit un suffixe « (n) » avant l'extension, le chemin réel est
  publié et un avertissement d'information est émis. `target` et `baseFolder` relatifs sont résolus par rapport au
  dossier du pipeline ; les noms d'entrées sont assainis (`.`, `..`, points et espaces finaux, noms réservés Windows) ;
  un vrai fichier `SHA256SUMS` à la racine est laissé hors du ZIP, avec un avertissement (le manifeste garde son nom).
- E-mail : délais de socket fixes de 20 s (l'API ne transmet pas `timeoutSeconds` ; le moteur interrompt l'action à
  sa limite). Une erreur de QUIT après acceptation du message est ignorée ; message distinct pour les erreurs TLS ;
  avertissement quand `none` est utilisé avec un utilisateur.
- Webhook : `url` reste une chaîne simple (pas `@Secret` : l'URL entière n'est pas un secret, `${secret:…}` peut y
  être inclus) ; jamais d'URL ni d'en-têtes dans les messages ; redirections non suivies ; lectures de réponse bornées ;
  préréglage Telegram avec `CHAT_ID` à remplir.
- Secrets : `$${` échappe un `${` littéral ; un nom inconnu reste accepté (avertissement dans l'éditeur) ; un secret de
  moins de 4 caractères masque le message entier ; la valeur n'est jamais lue avec écho ; une valeur en clair déjà
  présente dans un champ secret propose « Mettre dans le coffre ».
- `timeoutSeconds` négatif refusé à la validation ; l'API des plugins reste 1.2 (non publiée), ajouts seulement.
- `.idea/runConfigurations/CopybotMainUiDev.xml` n'est pas modifié : les dossiers `target` des trois plugins sont à
  ajouter à `copybot.devPluginPaths` à la main.

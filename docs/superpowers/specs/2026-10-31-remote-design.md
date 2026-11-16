# SFTP, FTP et S3 (entrée et sortie) — conception

Date : 2026-10-31. Statut : décidé (Steven a délégué les choix ; décisions de Steven : secrets dans le coffre local,
SFTP empreinte inconnue = confiance au premier usage avec confirmation dans l'IHM et acceptation avec avertissement
en CLI, empreinte différente ensuite refusée ; S3 en entrée et sortie ; masquer les erreurs de listage et de sortie
qui pourraient contenir des secrets).

## 1. Préalable moteur : masquage complet des secrets

Les secrets (`${secret:…}`) d'une étape sont aujourd'hui retirés de ses erreurs d'élément, de ses avertissements et
des actions de fin de run, mais pas de ces chemins, qu'il faut tous passer par le `Redactor` de l'étape concernée
(`PipelineStep.getRedactor()`) :

1. échec de `listFiles` (y compris l'empreinte de l'étape d'entrée) → `PipelineState.failure`, `RunReport.failure` ;
2. échec de `beforeAll` (et `beginAnalysis`) ;
3. échec de `afterAll` (normal et « quiet », y compris le journal) ;
4. échec de `afterItem` (journal) ;
5. échec de `checkTarget` (journal DEBUG) ;
6. échec de la projection de cible du plan (`Plan.targets`, message affiché et journal) ;
7. échec de dry-run (`DryRunner.project`, message affiché) ;
8. échantillon de l'éditeur (`SampleSession` : listage, analyse) ;
9. `Throwable` non `RuntimeException` d'une étape principale (`ItemRunner.aroundStep`) ;
10. `touchedPaths(null)` des entrées (`CopybotEngine`, journal DEBUG) ;
11. `ResumeResolver.target` (exception non `CopybotException`).

Les exceptions de contrôle (annulation, `PhaseStopped`, `RejectedExecutionException`, `InterruptedException`) ne
sont jamais transformées. Un test par chemin avec une vraie référence `${secret:…}`.

## 2. Confirmation de l'utilisateur pendant un run (API 1.2, ajout)

- `com.copybot.plugin.api.UserConfirmation.ask(String question)` → `boolean` : bloque l'appelant jusqu'à la réponse.
- Le moteur porte le gestionnaire (`CopybotEngine.setConfirmationHandler`) ; l'IHM installe un dialogue modal
  (fil FX, l'appelant attend ; annulation du run → réponse « non ») ; la CLI installe « oui » + une ligne
  d'avertissement sur stderr (décision de Steven) ; sans gestionnaire (tests, intégrations) : « non ».
- Questions sérialisées (une à la fois) ; l'échantillon de l'éditeur (`SampleSession`) répond « non » sans
  demander (on ne fait pas confiance à un hôte depuis un aperçu) et le message dit de lancer un run.

## 3. Un plugin, trois protocoles

Module `copybot-plugin/copybot-plugin-remote`, code `remote`, 1.0.0, bundles en/fr/it, `catalog.json`.

| Action        | Type | Bibliothèque                                            |
|---------------|------|---------------------------------------------------------|
| `sftp.read`   | IN   | JSch (fork `com.github.mwiede:jsch`, sans dépendance)  |
| `sftp.write`  | OUT  | idem                                                    |
| `ftp.read`    | IN   | Apache Commons Net (FTP, FTPS explicite et implicite)  |
| `ftp.write`   | OUT  | idem                                                    |
| `s3.read`     | IN   | JDK seulement : `java.net.http`, `javax.xml`, SigV4 maison |
| `s3.write`    | OUT  | idem                                                    |

Une abstraction interne `RemoteFs` (lister un dossier, ouvrir en lecture, état d'un fichier, créer les dossiers,
téléverser vers un nom, renommer, supprimer) porte toute la logique commune ; chaque protocole n'en implémente que
les primitives. Connexions : un petit réservoir par étape (taille = `maxConnections`, défaut 4), sessions fermées
dans `afterAll`. Ressource `net:<protocole>:<hôte>` (capacité réglable dans `config.json`, défaut 1 → configurer
`"net:*": 4` par exemple ; la README le dit).

### Configuration commune

- Connexion : `host` (requis, sauf S3), `port` (défaut 22 / 21 / 990 FTPS implicite), `user`, `password` (`@Secret`).
- SFTP : `privateKey` (`@FilePath`), `passphrase` (`@Secret`).
- FTP : `security` `none` / `explicit` (défaut) / `implicit`, `passive` (défaut vrai). `none` avec un mot de passe →
  avertissement de configuration. FTPS : certificat vérifié par les autorités du système et le nom de l'hôte ; à
  défaut, confiance au premier usage par empreinte de clé publique (§6), pour les NAS à certificat auto-signé.
- S3 : `endpoint` (défaut `https://s3.<region>.amazonaws.com`), `region` (requis), `bucket` (requis), `accessKey`,
  `secretKey` (`@Secret`), `pathStyle` (défaut faux ; vrai pour MinIO).
- Délais réseau : connexion 20 s, lecture 60 s (fixes).

## 4. Entrée (`*.read`)

- `folder` (dossier distant ou préfixe S3, requis), `recursive` (défaut vrai), `include` / `exclude` (globs, même
  sens et insensibles à la casse comme `file.read`), `minSize`.
- Chaque fichier devient `WorkItem.remote(nom, dossier listé, « sftp://hôte/chemin », taille, date de modification,
  contenu, suppression)` ; le contenu ouvre un nouveau flux à chaque appel (réservoir) ; la suppression supprime le
  fichier distant (mode déplacement de la sortie). Dates : SFTP mtime, FTP `MLSD` (repli `LIST` + `MDTM`), S3
  `LastModified`.
- `signalsFolders` vrai en SFTP et FTP (dossier signalé quand son parcours se termine), faux en S3 (les groupes sont
  arrêtés en fin de listage).
- Échec de connexion ou d'authentification → échec du listage (masqué, §1).

## 5. Sortie (`*.write`)

- `outPattern` (motif comme `file.write`, `OutPattern` de l'API), `onMissingKey`, `onConflict` : `skip`
  (défaut si même taille), `rename` (défaut si différent : « nom (1).ext »), `overwrite`, `error` — comparaison par
  taille (et date à 2 s près si `compare: sizeAndDate`) ; pas de comparaison de contenu distante.
- Écriture : SFTP et FTP vers `.<nom>.<runId>.copybot-tmp` puis renommage (remplacement si `overwrite`) ; S3 : PUT
  direct (atomique), multipart au-delà de 16 Mio (parts de 16 Mio, plus grandes quand 10 000 parts ne suffiraient
  pas, abandon de l'envoi en cas d'échec ou d'annulation). Un seul tampon par connexion, réutilisé d'un envoi à
  l'autre et libéré avec elle : au plus `maxConnections` × 16 Mio de mémoire par étape (amendement du 2026-10-31,
  au lieu de 64 Mio). Dossiers créés au besoin.
- Vérification : taille distante = taille envoyée (toujours). SHA-256 calculé pendant l'envoi et donné au bilan
  uniquement si la cible est locale — ici non : `fileWritten` n'est pas appelé (l'API du bilan est en `Path` local),
  limite assumée.
- Verrou par cible distante dans l'étape (deux éléments vers le même nom → le second voit le premier).
- `deleteSource` (défaut faux) : supprime la source après vérification, jamais si `isSourceKept()`.
- `writesCompanions` : faux (les compagnons COPY sont refusés à la lecture du pipeline, comme pour toute sortie qui
  ne les gère pas).
- `resolveTarget` vide (l'API attend un chemin local) : le plan affiche « cible inconnue » ; la reprise « déjà à
  destination » est refusée avec le message existant ; la reprise par curseur marche. `checkTarget` : UNKNOWN.
- `WriteResult` : `written(null)` / `skipped(null, raison)` — la raison et les messages nomment la cible distante.

## 6. SFTP : empreinte de l'hôte

- Fichier `known_hosts` du plugin (`PluginFolders.dataDirectory("remote")/known_hosts`, format OpenSSH, empreintes
  SHA-256 affichées).
- Hôte inconnu : `UserConfirmation.ask("Faire confiance à <hôte> ? Empreinte SHA256:… (algorithme)")` ; oui →
  enregistré ; non → échec « hôte non approuvé ». Empreinte différente d'une empreinte enregistrée → refus, message
  avec l'ancienne et la nouvelle empreinte et le chemin du fichier à corriger ; jamais de question.
- Algorithmes modernes uniquement (ed25519, ecdsa, rsa-sha2) ; `ssh-rsa` SHA-1 refusé.

### FTPS : certificat (amendement du 2026-10-31)

- Vérification d'abord par le système (chaîne vers une autorité de `cacerts` et nom de l'hôte). Seulement si elle
  échoue : empreinte SHA-256 de la clé publique du certificat (SubjectPublicKeyInfo) cherchée pour `hôte:port` dans
  le fichier `ftps_trust` du plugin (`PluginFolders.dataDirectory("remote")/ftps_trust`, une ligne
  `hôte:port SHA256:…` par serveur, écriture atomique). Empreinte enregistrée → accepté, même expiré ou à un autre nom.
- Inconnu : la poignée de main échoue (on ne questionne pas pendant celle-ci), puis, connexion fermée,
  `UserConfirmation.ask` donne l'empreinte, le sujet, l'émetteur, la validité et, le cas échéant, « le certificat
  nomme X, pas <hôte> » ; une question à la fois par `hôte:port` (fichier relu à son tour, « non » retenu pour
  l'étape, comme en SFTP) ; oui → enregistré, reconnexion. Empreinte différente d'une empreinte enregistrée → refus
  sans question, message avec l'ancienne et la nouvelle empreinte et le chemin du fichier.
- Connexions de données : jamais de question ; empreinte enregistrée → acceptée, autre → refusée (y compris sans
  reprise de session TLS).

## 7. S3

- SigV4 (en-têtes), charge utile signée (`x-amz-content-sha256` calculé), testée avec les vecteurs AWS.
- ListObjectsV2 paginé (`continuation-token`), GET, PUT, HEAD, DELETE, multipart (Create/UploadPart/Complete/Abort).
- Style d'adresse : virtuel (`bucket.endpoint`) ou chemin (`endpoint/bucket`).
- Erreurs S3 (XML `Code`/`Message`) traduites ; la signature et les clés n'apparaissent jamais.

## 8. Tests

- Logique commune avec un `RemoteFs` en mémoire (listage, globs, conflits, renommage, verrou, suppression, annulation).
- SFTP avec un serveur Apache MINA SSHD embarqué (portée test) : mot de passe, clé, empreinte inconnue acceptée /
  refusée, empreinte changée refusée.
- FTP avec un serveur embarqué (Apache FtpServer ou MockFtpServer, portée test) : passif, `MLSD`, FTPS explicite si
  faisable.
- S3 : vecteurs SigV4 + faux serveur `com.sun.net.httpserver` (liste paginée, PUT, multipart, erreurs).
- Aucun accès réseau externe.

## 9. Hors périmètre

Cibles distantes dans le plan et la reprise « à destination » (nécessite une API de cible non locale), SHA-256 au
bilan pour les sorties distantes, compagnons COPY en sortie distante, WebDAV, SMB, agent SSH, rôles IAM / profils AWS.

## 10. Écarts à la réalisation

Constatés à la fin de la réalisation (tâches 1 à 7) ; la README décrit le comportement réel.

- **Délais.** Connexion 20 s partout. Lecture : pas de délai fixe de 60 s par requête, qui couperait un gros
  transfert lent mais vivant. SFTP : keep-alive toutes les 20 s, lien mort constaté après 3 sans réponse (environ
  80 s). FTP : 60 s sur les sockets de contrôle et de données, plus une surveillance de l'envoi (60 s sans
  progrès). S3 : une requête, une réponse ou un téléchargement abandonné après 60 s sans progrès (corps envoyé,
  réponse reçue, lecture) ; une part peut durer plus longtemps tant qu'elle avance.
- **Dates en sortie.** `RemoteFs.upload(chemin, contenu, taille, lastModified)` et `keepsLastModified()` : la date de
  la source est donnée au fichier (SFTP `setMtime`, FTP `MFMT` sinon `SITE UTIME`, S3 métadonnée
  `x-amz-meta-mtime` en secondes comme rclone, relue par HEAD), pour que `sizeAndDate` retrouve la copie au run
  suivant. Serveur qui ne sait pas (vsftpd) : `sizeAndDate` compare les tailles seules, avec un avertissement au
  journal. Un listage S3 ne donne que `LastModified` (date d'envoi).
- **Nom temporaire** `.<nom>.<runId>-<n>.copybot-tmp` (n : compteur d'essais de l'étape) au lieu de
  `.<nom>.<runId>.copybot-tmp`.
- **Connexions inactives** fermées après 30 s, en plus de `afterAll` : l'étape d'entrée n'a pas de `afterAll` en
  phase d'exécution alors que les contenus y sont lus. Les dernières connexions restent ouvertes jusqu'à 30 s.
- **Noms cachés** (commençant par « . », fichiers et dossiers, fichiers temporaires compris) jamais listés, sans
  option.
- **FTP.** Un chemin n'est absent que sur 550 ; un 501 à `MLST` (Apache FtpServer pour un chemin absent) est vérifié
  par `LIST` du dossier parent, toute autre réponse négative échoue (rien n'est écrasé). `LIST -a` (vsftpd cache les
  noms en point, dont les fichiers temporaires), `LIST` simple si l'option est refusée. Renommage avec remplacement :
  `RNFR`/`RNTO` d'abord, la cible supprimée seulement après un refus 550/553 si la cible et la source existent
  (Apache FtpServer accepte `RNFR` d'un fichier absent).
- **FTPS** : confiance au premier usage par empreinte de clé publique (amendement de §6), pour les certificats
  auto-signés ; la CLI accepte avec un avertissement, comme en SFTP.
- **SFTP** : une clé d'un autre type que celles enregistrées pour l'hôte compte comme changée (refus) ;
  `known_hosts` et `ftps_trust` sont écrits de façon atomique mais sans verrou entre processus.
- **S3, listage récursif** : un seul ListObjectsV2 sans délimiteur (`RemoteFs.listTree`, mode arbre de `RemoteRead`),
  les éléments dans l'ordre des clés ; non récursif : avec délimiteur. Les clés sont demandées encodées et décodées
  seulement si la réponse dit `EncodingType` url.
- **S3, accès anonyme** : sans `accessKey` ni `secretKey`, requêtes non signées (compartiment public).
  `accessKey` n'est pas `@Secret` (seul `secretKey` l'est).
- **S3, style d'adresse** : sans `pathStyle`, le nom du compartiment doit pouvoir entrer dans un nom d'hôte
  (minuscules, chiffres, points, tirets, 3 à 63 caractères) ; sinon la configuration est refusée en conseillant
  `pathStyle`. Une adresse IP comme point d'accès exige `pathStyle`.
- **S3, multipart** : seuil et parts de 16 Mio, un tampon par connexion (amendement de §5). Pas de nouvel essai sur
  503 SlowDown ; vérification puis PUT non atomique (`If-None-Match` non utilisé).
- **Bibliothèques** : JSch 2.28.8 (`com.github.mwiede:jsch`, java.base seulement), Commons Net 3.13.0 et sa
  dépendance Commons IO 2.21.0, livrées dans `lib/` ; S3 sur le JDK seul (`java.net.http`, `javax.xml`).
- **SFTP, cible apparue pendant l'écriture** : avec `posix-rename`, une cible créée par un autre processus entre la
  vérification et le renommage est remplacée (et non refusée) ; le verrou par cible ne couvre que l'étape elle-même.

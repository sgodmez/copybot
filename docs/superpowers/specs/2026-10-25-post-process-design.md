# Post-traitement : lancer un programme à la fin de la copie — conception

## Besoin

Après une copie de photos sur le NAS, les nouveaux répertoires doivent recevoir leurs droits Synology Photos : Steven
veut que copybot lance alors synoRight (projet voisin) sur ces répertoires. Copybot reste générique : il lance **un
programme avec des arguments**, dont une variable donnant les répertoires créés par la copie ; le choix des
répertoires utiles (synoRight ne gère que 2 niveaux) appartient au programme lancé.

Non-objectifs : plusieurs programmes, attendre la fin du programme ou interpréter son code de sortie, lancer après une
simple préparation (plan).

## Configuration

Bloc facultatif du pipeline :

```json
"postProcess": {
  "command": "D:\\progs\\autre\\synoRight\\synoRightGui.exe",
  "args": ["-c", "D:\\progs\\autre\\synoRight", "rights", "--new-folders", "{createdDirs}"]
}
```

- `command` : le programme ; absent ou vide ⇒ pas de post-traitement.
- `args` : liste d'arguments, passés tels quels (pas de shell). Un argument valant exactement `{createdDirs}` est
  remplacé par **un argument par répertoire créé** par la copie (chemins absolus, parents avant enfants, ordre des
  chemins) ; aucun répertoire créé ⇒ le programme **n'est pas lancé** (rien à traiter). Ailleurs, le texte reste tel
  quel (pas de substitution dans une chaîne).
- `command` peut être entre guillemets (« Copier en tant que chemin » de l'Explorateur) : ils sont retirés ; les
  espaces autour d'un argument `{createdDirs}` sont ignorés.
- Le programme démarre dans le répertoire de `command` quand c'est un chemin absolu (synoRight y trouve sa
  configuration), dans le répertoire courant pour une commande trouvée dans le PATH ; sorties ignorées, entrée fermée
  aussitôt (un programme qui la lit n'attend pas).

## Répertoires créés

Ceux que l'écriture de cette exécution a créés, à tous les niveaux : l'écriture sûre (`SafeFileWriter`) cherche, avant
`createDirectories`, le premier ancêtre absent du répertoire cible, puis signale chaque répertoire créé au contexte
d'écriture (`WriteContext`, nouveau rappel `directoryCreated`, sans effet par défaut : un plugin de sortie tiers peut
s'en servir). Le moteur les réunit dans un ensemble (deux écritures concurrentes peuvent signaler le même).

## Déclenchement

À la fin d'une phase de copie (`execute`, ou exécution en une phase / en continu), après `afterAll` et la sauvegarde
du curseur, **avant** la publication du statut final : statut final SUCCESS ou ERROR (des fichiers en erreur
n'empêchent pas de traiter les répertoires créés). Pas de lancement pour une exécution annulée, interrompue ou en échec
de phase (exception), ni après une préparation.

Le programme est lancé sans attendre sa fin. Un échec du lancement (programme introuvable…) devient un
**avertissement** de l'exécution (affiché par l'IHM et la CLI) et n'en change pas le statut ; la ligne lancée est
journalisée.

## IHM

Nœud « Pipeline » de l'éditeur : section « Après la copie » avec le programme (champ + bouton Parcourir) et les
arguments (zone de texte, un argument par ligne), aide rappelant `{createdDirs}`. Vider le programme retire le bloc.

## Côté synoRight

Fait dans le projet synoRight (session dédiée, commit acd4138) : `synoRightGui.exe` (sans console, ajoute lui-même
`gui`) avec `rights --new-folders <dossiers…>` ouvre « Droits du dossier » sur les dossiers de niveau 1 et 2 sous le
dossier photos, écarte sans erreur ceux de 3e niveau et les doublons, se ferme sans fenêtre s'il ne reste rien, et
signale par une boîte d'erreur des dossiers tous hors du dossier photos (autre nom de serveur, lecteur non reconnu).
Il attend que Synology Photos indexe les nouveaux dossiers (essai toutes les 10 s, `--wait-index <minutes>`, 10 par
défaut). `-c` donne le dossier de sa configuration. La destination de la copie doit être écrite en UNC
(`\\NAS\photo\…`) : un lecteur réseau n'est reconnu que s'il est mémorisé. Détails : README de synoRight, « Folders
created by another tool ».

## Limites

- Ligne de commande Windows : 32 767 caractères, soit quelques centaines de répertoires créés.
- Un répertoire créé au même instant par un autre processus peut être compté comme créé par la copie.
- Un script `.bat`/`.cmd` passe par cmd.exe, qui développe les `%` des noms de répertoires : préférer un exécutable.

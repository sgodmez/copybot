# Mettre à jour l'appli et ses plugins en une fois

Date : 2026-10-26
Statut : validé (Steven a choisi « tout en une fois » ; il a demandé d'enchaîner sans relecture)

## Contexte

Specs `2026-10-26-app-update-design.md` (mise à jour de l'appli : installateur silencieux, ou copie portable par
`apply-update.ps1`) et `2026-10-26-plugin-install-design.md` (plugins officiels : catalogue de la dernière release,
mode plugin du même script, manifeste JSON).

Constaté sur la 0.6.0 : une 0.5.0 voit la 0.6.0 mais pas `gps-zone` 1.0.1, construit pour l'API 1.1 qu'elle n'a pas.
Une fois l'appli à jour, la 0.6.0 ne regarde pas les plugins avant le lendemain (une vérification par jour, déjà faite
par la 0.5.0). Il faut ensuite une 2e mise à jour, un 2e redémarrage. Et dans les Préférences, une mise à jour trouvée
oblige à fermer la fenêtre pour cliquer dans le bandeau.

## Objectifs

1. **Mise à jour combinée** : « Mettre à jour » installe la nouvelle appli **et** les nouvelles versions des plugins
   installés, avec un seul arrêt et une seule relance de Copybot.
2. **Vérification au premier lancement d'une nouvelle version** : la date de la dernière vérification ne vaut que pour
   la version qui l'a faite.
3. **Bouton « Mettre à jour » dans les Préférences** quand une mise à jour est proposée.

Hors champ : installer des plugins non installés, désinstaller, mettre à jour des plugins non officiels.

## 1. Mise à jour combinée

### Quels plugins

On lit le catalogue **publié avec la release installée** (son fichier `catalogue.json`, au moment de la préparation,
pas celui de la dernière release qui a pu changer depuis la vérification) : ses entrées sont toutes compatibles avec
l'API de cette release (`plugin-catalogue.ps1` refuse une entrée gardée incompatible). Pendant une mise à jour de
l'appli, on prend donc **chaque plugin officiel installé dont ce catalogue a une version plus récente**, sans regarder
l'API de la version courante (`PluginRow` : version installée connue et plus ancienne que celle du catalogue). Un plugin
installé hors du dossier `plugins` de l'appli, ou sans dossier à lui, n'est pas concerné.

Le compte des mises à jour de plugins d'une vérification suit la même règle quand une release est trouvée : le bandeau
dit « Copybot 0.7.0 disponible. 1 mise à jour de plugin disponible. », et son bouton fait les deux. Sans release, rien
ne change (mises à jour compatibles avec l'API courante, bouton qui ouvre la fenêtre Plugins).

Catalogue illisible au moment de la mise à jour : l'appli est mise à jour seule (journalisé), jamais bloquée.

### Préparation (Java)

Un seul dossier temporaire : le fichier de l'appli (installateur, ou copie portable dézippée) puis chaque archive de
plugin dézippée à côté, le manifeste du mode plugin et le script. Progression : le téléchargement de l'appli, puis
« Téléchargement de gps-zone (1/1)… ». Sans plugin à mettre à jour, la préparation et la commande restent exactement
celles d'aujourd'hui.

### Application (script)

`apply-update.ps1` accepte `-PluginManifest` en plus d'un mode appli :
- **portable** (`-NewDir` + `-PluginManifest`) : attente de la fin de Copybot, copie de l'image de l'appli, puis, si
  elle a réussi, copie des plugins ; en élevé si le dossier de l'appli ou `plugins` n'est pas inscriptible (une seule
  élévation qui fait les deux copies).
- **installé** (nouveau `-Installer <setup.exe>` + `-PluginManifest`) : attente de la fin de Copybot, installateur lancé
  par le script `/SILENT /SUPPRESSMSGBOXES /NORESTART` (sans `/RELAUNCH` : c'est le script qui relance), attendu ; s'il
  a réussi (code 0), copie des plugins (élevée si `plugins` n'est pas inscriptible, comme le mode plugin : une
  installation pour tous les utilisateurs demande donc deux approbations, celle de l'installateur et celle de la copie).
- Puis relance non élevée (avec `--config-file`) et suppression du dossier temporaire, installateur compris.

Garde-fous en plus de ceux du mode plugin : l'installateur, l'image portable et le manifeste sont dans le dossier du
script (le dossier temporaire) ; l'installateur est un fichier ordinaire directement dedans (pas d'extension imposée :
le dossier est celui que Copybot a créé, et le script ne l'élève jamais ; cela permet de tester le vrai script avec un
faux installateur). À vérifier en vrai sur une installation pour tous les utilisateurs : l'invite UAC de l'installateur,
lancé par le script caché, pourrait s'afficher en arrière-plan (bouton clignotant de la barre des tâches). Manifeste refusé : rien n'est
touché, Copybot est relancé. Appli non installée (échec de la copie, installateur en erreur, élévation refusée) : les
plugins ne sont pas touchés.

## 2. Vérification après une nouvelle version

`prefs.json` garde, avec `updateLastCheck`, la version qui a fait la vérification (`updateLastCheckVersion`). Une date
enregistrée par une autre version (ou sans version, comme celles d'avant) est ignorée : la vérification part au
démarrage. Rien ne change pour `UpdateService`, qui ne voit qu'une date absente.

## 3. Bouton dans les Préférences

Section « Mises à jour » : quand une release ou des mises à jour de plugins sont proposées (au démarrage ou par
« Vérifier maintenant »), un bouton du même libellé que celui du bandeau apparaît à côté du résultat. Il ferme les
Préférences (en enregistrant ce qui a changé, comme leur fermeture) puis fait exactement ce que fait le bouton du
bandeau. Désactivé avec la même raison que lui (une opération en cours, hors Windows).

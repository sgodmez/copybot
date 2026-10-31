# Préréglages de regroupement apportés par un plugin — conception

Date : 2026-10-31. Statut : décidé (Steven a délégué les choix). Origine : Takeout ne doit pas être natif (décision
de Steven) ; il faut qu'un plugin puisse apporter ses préréglages de regroupement (et plus tard son analyse du JSON).

## 1. Aujourd'hui

Les préréglages (`raw-jpeg-xmp`, `iphone`, `action-cam`, `sony-xml`, `web-page`) sont une table statique du moteur
(`GroupingPresets`) ; le pipeline les cite par nom (`{"preset":"iphone"}`) et le moteur les développe à chaque
usage ; l'éditeur les propose depuis la même table, libellés dans le bundle de l'IHM. Un préréglage inconnu est
refusé au démarrage d'un run (`grouping.preset.unknown`), affiché « nom (?) » dans l'éditeur.

## 2. API (1.2 non publiée, ajouts seulement)

Paquet `com.copybot.plugin.api.grouping` :

- `GroupingRule` (record) : `type` (`GroupingRuleType` : `SAME_BASE`, `DOUBLE_EXTENSION`, `FOLDER`, `REGEX`),
  `main` (liste), `companions` (map extension → `GroupingRole`), `folder`, `role` (`GroupingRole`), `companion`,
  `link` (`GroupingLink` : `NAME`, `BASE`) ; `GroupingRole` : `COPY`, `READ`, `IGNORE` (les rôles d'une règle de
  pipeline ; `CompanionRole` de l'API d'action reste `COPY`/`READ`) — mêmes sens et mêmes contrôles que les règles
  écrites dans un pipeline ; fabriques statiques par type pour l'écrire simplement. Une extension compagnon nulle ou
  vide est refusée dès la construction de la règle (le plugin devient un plugin dont les préréglages ne se lisent
  pas).
- `GroupingPreset` (record) : `code` (`[a-z0-9-]+`), `rules` (non vide).
- `IPlugin.getGroupingPresets()` : `List<GroupingPreset>`, défaut vide.
- Textes : `plugin.<code du plugin>.preset.<code>.name` et `.description` dans le bundle du plugin.

## 3. Pipeline

- Référence : `{"preset": "takeout", "plugin": "com.copybot.plugin.takeout"}` (nom de module, comme les étapes).
  Sans `plugin`, c'est un préréglage du cœur (inchangé).
- Pas de version : la version chargée du plugin fait foi.

## 4. Moteur

- Un registre des préréglages (cœur + plugins chargés) remplace l'usage statique pour le développement, la
  validation, le listage, la décision du mode continu et l'IHM ; le cœur reste disponible sans plugin.
- Validation au démarrage d'un run (comme aujourd'hui) : plugin absent → refus « le préréglage X du plugin Y n'est
  pas disponible : installez le plugin » (`grouping.preset.plugin-missing`) ; plugin présent mais préréglage
  inconnu → `grouping.preset.unknown-in-plugin` (`grouping.preset.unknown` reste celui d'un préréglage du cœur
  inconnu) ; règles d'un plugin invalides (regex qui ne compile pas, dossier sans `{base}`…) → le préréglage est
  écarté au chargement du plugin avec un avertissement journalisé, et refusé s'il est cité
  (`grouping.preset.plugin-invalid`). Une règle avec `plugin` sans `preset` est refusée
  (`grouping.rule.plugin-without-preset`).
- Un plugin en échec de chargement : ses préréglages sont absents (même message que plugin absent, cause dans les
  journaux).

## 5. Catalogue et éditeur

- `PluginCatalog` porte aussi les préréglages des plugins (plugin, code, nom et description localisés, règles) ;
  un plugin dont les préréglages ne se lisent pas devient un plugin en échec comme pour ses actions. C'est voulu :
  l'éditeur le montre en échec alors qu'à l'exécution ses actions se résolvent toujours (seule la citation d'un de
  ses préréglages est refusée, `grouping.preset.plugin-failed`, cause dans les journaux) ; à l'inverse, une version
  dont seules les actions échouent apporte quand même ses préréglages valides au catalogue.
- Menu « Ajouter un préréglage » : ceux du cœur puis ceux des plugins (libellé « nom — plugin »).
- Liste des règles : un préréglage de plugin s'affiche avec son nom localisé ; plugin absent → « nom (plugin
  absent) » et l'éditeur le compte dans les plugins manquants (bouton Installer comme pour une étape).
- Avertissements de l'éditeur fondés sur le développement (extensions compagnons, dossier + lecture non récursive,
  mode continu) : prennent en compte les règles des préréglages de plugins.

## 6. Hors périmètre

Types de règles apportés par un plugin (code de regroupement libre) ; analyse du JSON Takeout (futur plugin) ;
version minimale de plugin pour un préréglage.

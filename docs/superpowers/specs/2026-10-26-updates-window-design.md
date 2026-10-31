# Une fenêtre « Mises à jour » pour l'appli et les plugins

Date : 2026-10-26
Statut : validé (maquette validée par Steven ; il a demandé d'enchaîner spec et implémentation en agents)

## Contexte

Specs `2026-10-26-app-update-design.md`, `2026-10-26-plugin-install-design.md` et
`2026-10-26-update-with-plugins-design.md` (0.6.1 : une mise à jour de l'appli emporte tous les plugins installés qui ont
une version plus récente dans le catalogue de la release, en un seul redémarrage).

Aujourd'hui les mises à jour sont éparpillées : le bandeau (« Mettre à jour » fait tout, sans montrer quels plugins ni
permettre d'en écarter un), la section Mises à jour des Préférences, la fenêtre Plugins (mises à jour de plugins seules).
Steven veut **un parcours unifié** : une fenêtre qui montre l'appli et les plugins installés, avec leurs nouveautés, et
laisse choisir ce qu'on met à jour.

Contrainte : un plugin construit pour une API de plugins plus récente que celle de l'appli installée ne peut être
installé qu'avec la mise à jour de l'appli.

## Objectifs

- Une fenêtre **Mises à jour** : l'appli et chaque plugin officiel installé, version installée → disponible, nouveautés,
  une case par élément à mettre à jour, un bouton qui applique la sélection en un seul redémarrage.
- C'est le **seul chemin** pour mettre à jour ; le bandeau, les Préférences, le menu et la fenêtre Plugins y mènent.
- En plus (demande séparée) : *Fichier › Ouvrir une pipeline…*.

Non-objectifs : installer un plugin non installé (reste dans la fenêtre Plugins), « ignorer cette version », plugins non
officiels, mise à jour hors Windows (inchangé : refusée avec la raison).

## La fenêtre

```
┌─ Mises à jour ───────────────────────────────────────────────────────────┐
│ Copybot 0.6.1 · vérifié aujourd'hui à 09:12              [Vérifier]      │
│                                                                          │
│ ☑ Copybot              0.6.1 → 0.7.0                 ▸ Quoi de neuf     │
│                                                                          │
│ Plugins                                                                  │
│ ☑ gps-zone             1.0.1 → 1.1.0   🔗 avec Copybot 0.7.0  ▸ Quoi de neuf │
│ ☑ metadata-extractor   1.0.0 → 1.0.2                          ▸ Quoi de neuf │
│   autre-plugin         1.2.0  à jour                                     │
│                                                                          │
│ Copybot et 2 plugins · 48 Mo · un seul redémarrage                       │
│                          [Plus tard]   [Mettre à jour et redémarrer]     │
└──────────────────────────────────────────────────────────────────────────┘
```

- Fenêtre séparée (comme Plugins), non modale vis-à-vis de la fenêtre principale ; une seule instance (rouverte = mise
  au premier plan et rafraîchie).
- **En-tête** : version courante, date de la dernière vérification (« jamais » sinon), bouton *Vérifier* (vérifie tout
  de suite, comme l'ancien « Vérifier maintenant » ; pendant la vérification, « Vérification… »). Une erreur de
  vérification s'affiche sous l'en-tête. Hors appli packagée / version de dev : le message actuel
  (`update.unavailable-dev`) et rien d'autre.
- **Ligne Copybot** : seulement si une release plus récente existe ; sinon « Copybot est à jour ».
- **Lignes plugins** : chaque plugin officiel installé (présent dans le catalogue et installé dans le dossier `plugins`
  de l'appli). À jour : version en gris, sans case. Avec une version plus récente : case, `installée → disponible`.
  Plugins installés absents du catalogue : non listés.
- **« ▸ Quoi de neuf »** déplie sous la ligne le texte des nouveautés (repliable) :
  - Copybot : le texte des notes de la release GitHub (champ `body` de l'API, affiché tel quel, en texte), plus un
    lien « Voir sur GitHub ».
  - plugin : le nouveau champ `changes` de l'entrée du catalogue (par langue, comme `description`) ; absent : le lien
    n'est pas montré.
- Toutes les cases sont **cochées à l'ouverture** (et après chaque vérification).
- **Lien appli ↔ plugin (🔗)** : un plugin dont la version disponible est construite pour une API que l'appli installée
  n'accepte pas (`VersionUtil.isCompatible(apiCourante, entry.engine, false)` faux) porte « avec Copybot X » (X = la
  release offerte). Cocher ce plugin coche Copybot ; décocher Copybot décoche ces plugins et les grise avec « demande
  Copybot X ». Sans release offerte, un tel plugin est grisé « demande une version plus récente de Copybot ».
  Un plugin compatible avec l'appli actuelle se coche/décoche librement, avec ou sans l'appli.
- **Récapitulatif** au-dessus des boutons, recalculé à chaque case : « Copybot et 2 plugins · 48 Mo · un seul
  redémarrage » / « 2 plugins · … » / « Copybot · … » ; la taille = somme des tailles connues (taille de l'asset de
  l'appli, des archives de plugins quand elles sont connues ; omise si aucune n'est connue). Rien de coché : « Rien à
  mettre à jour » et bouton désactivé.
- **Mettre à jour et redémarrer** : désactivé avec la raison (opération en cours, hors Windows, autre Copybot ouvert…,
  les clés existantes). Applique la sélection en un seul redémarrage, avec les confirmations et la fenêtre de
  progression existantes :
  - Copybot coché (± plugins) : la mise à jour combinée de la 0.6.1, mais avec **exactement les plugins cochés** (plus
    de sélection automatique de tous les plugins).
  - plugins seuls : le mode plugin existant (mise à jour = installer la nouvelle version, l'ancien dossier remplacé).
- **Plus tard** ferme la fenêtre.

## Autour de la fenêtre

- **Bandeau** : « Mises à jour disponibles : Copybot 0.7.0 et 2 plugins » (ou seulement l'un des deux), boutons
  **[Voir]** (ouvre la fenêtre) et **×** (masque jusqu'au prochain démarrage, inchangé). Le lien « Notes de version » et
  le bouton d'installation directe disparaissent.
- **Menu Édition** : *Mises à jour…* à côté de *Plugins…* et *Ressources…*.
- **Préférences** : la section Mises à jour garde la case « Vérifier automatiquement » et un bouton *Mises à jour…* qui
  ferme les Préférences (en enregistrant, comme OK) et ouvre la fenêtre ; la version, « Vérifier maintenant », le
  résultat et le bouton ajouté en 0.6.1 disparaissent.
- **Fenêtre Plugins** : découvrir, installer, désinstaller. Pour un plugin installé avec une mise à jour, plus d'action
  « Mettre à jour » : la ligne dit « Mise à jour disponible » avec un lien *Mises à jour…* qui ouvre la fenêtre.
  Exception gardée : un plugin qu'une pipeline réclame et qui ne se charge pas, réparé par une mise à jour compatible
  (flux « Installer » du plan/éditeur, `PluginRow.offersInstall`), reste installable depuis la fenêtre Plugins comme
  aujourd'hui (choix de Claude : c'est une réparation demandée par la pipeline, pas une mise à jour parcourue).
- **Vérification automatique** inchangée (au démarrage, une fois par jour par version) ; le compte de plugins du bandeau
  suit les règles de la fenêtre (plugins installés avec une version plus récente dans le catalogue de la dernière
  release ; ceux qui demandent une API plus récente ne comptent que si une release est offerte).

## Données

- `Release` gagne `notes` (le `body` de l'API GitHub, chaîne vide si absent).
- Catalogue : champ optionnel `changes` par entrée (`{ "en": …, "fr": …, "it": … }`), lu par `PluginCatalog` (absent =
  vide). Source : champ `changes` du `catalog.json` de chaque plugin, recopié par `plugin-catalogue.ps1` dans l'entrée
  d'une version nouvelle ou changée (une entrée gardée garde le sien). Ajouter un `changes` à `gps-zone` 1.0.1 n'a pas
  d'effet rétroactif (entrée gardée) : il servira à la prochaine version.
- La préparation combinée prend la liste des plugins choisis (au lieu de les recalculer) : `PluginService.prepareUpdate`
  reçoit les modules choisis et ne garde, dans le catalogue de la release installée, que ceux-là (toujours limités aux
  plugins installés dans le dossier `plugins` de l'appli avec une version plus récente).

## Refonte de l'écran Plugins (maquette validée par Steven)

```
┌─ Plugins ────────────────────────────────────────────────────────────────┐
│ [Tous] [Installés] [Disponibles]                                         │
│ ┌──────────────────────────────────────────────────────────────────────┐ │
│ │ gps-zone  1.0.1          ● installé, à jour             [Désinstaller] │ │
│ │ Trouve le fuseau horaire d'une photo d'après sa position GPS…         │ │
│ │ Actions   Contrôler le fuseau horaire (analyse) · gps-zone.check      │ │
│ │ Dépend de metadata-extractor                                          │ │
│ │ Licence ODbL ▸                              ▸ Détails techniques       │ │
│ └──────────────────────────────────────────────────────────────────────┘ │
│ ┌ metadata-extractor  1.0.0   ● mise à jour 1.0.2 →  [Mises à jour…] ┐   │
│ ┌ autre  2.0.0             ○ disponible                     [Installer] ┐ │
│ ▸ Intégrés à Copybot (file.read, file.write, …)                         │
│ À appliquer : installer « autre »            [Appliquer et redémarrer]   │
└──────────────────────────────────────────────────────────────────────────┘
```

- Remplace la liste des plugins officiels et le panneau technique actuels par **une fiche par plugin**, dans une zone
  défilante : les plugins officiels du catalogue (installés ou non) et les plugins chargés qui n'y sont pas (non
  officiels, sans action d'installation).
- **En-tête de fiche** : code (ou nom du module pour un non officiel), version installée, état en couleur (installé à
  jour ; mise à jour X → renvoie à la fenêtre Mises à jour ; disponible ; incompatible ; ne se charge pas ; choisi :
  « sera installé / désinstallé » avec Annuler), et l'action principale (Installer, Désinstaller, Mises à jour…).
- **Corps** : description (catalogue) ; **Actions** fournies, une par ligne : nom traduit, type (entrée, analyse,
  traitement, sortie) et code tel qu'écrit dans les pipelines (`code-plugin.code-action`), connues seulement pour un
  plugin chargé ; **Dépend de** (les plugins de `pluginDependencies`), seulement si non vide ; **Manque** (les
  `missingRequires`), en rouge, seulement si non vide ; erreur de chargement en rouge sous l'état ; licence repliée
  (▸, le texte de la notice) comme aujourd'hui.
- **▸ Détails techniques** replié par fiche : dossier (avec « Ouvrir le dossier »), modules chargés (nom, version,
  principal, automatique, emplacement).
- **Filtres** Tous / Installés / Disponibles en haut (Tous par défaut).
- **Intégrés à Copybot** : une section repliée en bas, la même fiche pour le plugin embarqué (ses actions).
- Les informations générales du panneau actuel (fichier de config, chemins de plugins) vont dans une section repliée
  « Détails techniques » en bas de la fenêtre.
- **Barre du bas** : récapitulatif des changements choisis, « Appliquer et redémarrer » (désactivé avec la raison),
  comme aujourd'hui ; plus de « Tout mettre à jour ».
- Le comportement existant est gardé : avertissements, message de catalogue illisible, flux « Installer » d'une
  pipeline (préselection, réparation par mise à jour compatible), choix conservés entre rafraîchissements.

## Fichier › Ouvrir une pipeline…

Menu Fichier, avant *Quitter* : *Ouvrir une pipeline…* fait exactement ce que fait le bouton du même nom de l'écran
d'accueil (sélecteur de fichier `*.json`, refus d'un `.state.json`, ouverture du plan), depuis n'importe quel écran.
La logique est partagée, pas dupliquée.

## Textes

Nouveaux libellés en en/fr/it (parité vérifiée par `UiBundleTest`), échappements `\u` dans les bundles.

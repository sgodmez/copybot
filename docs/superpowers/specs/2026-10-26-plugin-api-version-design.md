# Version d'API des plugins distincte de celle de Copybot

Date : 2026-10-26
Statut : validé (demande de Steven ; choix de mise en œuvre faits en autonomie)

## Contexte

Aujourd'hui un plugin est compatible avec Copybot selon la version du module moteur (`com.copybot.engine`), qui est
celle de l'appli : javac l'inscrit dans le `requires com.copybot.engine` du plugin, et le moteur la compare au
chargement (`LayerLoader.incompatibleApi`, `VersionUtil.isCompatible(moteur, construitPour, false)` : même majeur,
mineur du plugin ≤ celui du moteur). Conséquence : tout plugin reconstruit avec Copybot 1.3 exige Copybot 1.3, même
s'il n'utilise rien de nouveau. Steven veut une version d'API des plugins distincte de celle de l'appli.

## Objectifs

- Une version d'API des plugins `X.Y` qui ne bouge que lorsque ce que les plugins voient du moteur change ; un plugin
  reste compatible avec toutes les versions de Copybot qui offrent la même API majeure et un mineur au moins égal.
- Un oubli de relever cette version est impossible : un test le détecte au build.
- Catalogue, écran Plugins et CI utilisent cette version.

## Non-objectifs

- Séparer l'API dans un module Maven à part (les packages restent dans `copybot-engine`).
- Changer la règle de compatibilité (même majeur, mineur du plugin ≤ mineur de l'API) ou le format du catalogue.

## 1. La version d'API

- Propriété `copybot.plugin.api.version` dans le pom racine, première valeur `1.0` (deux nombres).
- C'est la **version de module** de `com.copybot.engine` (descripteur de module du jar du moteur), au lieu de la
  version du projet : les plugins l'enregistrent donc à la compilation dans leur `requires com.copybot.engine`, et
  `VersionUtil.engineVersion()` la renvoie — le contrôle au chargement compare ainsi deux versions d'API sans autre
  changement. (Le constructeur vérifie comment la poser avec maven-compiler-plugin 3.16 : paramètre dédié ou argument
  `--module-version` ; elle doit apparaître dans `jar --describe-module` du moteur et dans le `requires` compilé d'un
  plugin.)
- Sémantique (la question : un plugin existant marche-t-il sans être touché ?) : **mineur** quand l'API gagne
  quelque chose de **facultatif**, que les plugins existants peuvent ignorer ; **majeur** quand quelque chose est
  retiré ou modifié, ou quand l'API ajoute une **obligation** pour les plugins : un type existant gagne un membre
  **abstrait** (méthode d'interface sans `default`, méthode abstraite, élément d'annotation sans valeur par défaut :
  un ancien plugin qui l'implémente ne l'a pas), une méthode `default` devient abstraite (l'inverse n'est pas
  cassant), ou une obligation invisible dans les signatures (clé à déclarer, appel à faire, contrat décrit seulement
  dans la javadoc), que le test ne voit pas et qui se relève à la main.
- Le plugin intégré (`file.read`, `file.write`) porte la version d'API : un `version` épinglé sur une étape
  intégrée d'un pipeline est une version d'API.
- Surface d'API = les packages exportés **sans restriction** par `copybot-engine` (`com.copybot.plugin.api.*`,
  `com.copybot.utils`, `com.copybot.resources`, `com.copybot.exception`, ce que dit le `module-info`).

## 2. Garde-fou : l'empreinte de l'API

- Un fichier versionné `copybot-engine/src/test/resources/plugin-api.txt` : première ligne `api <X.Y>`, puis une ligne
  triée par élément public ou protégé de la surface (type, champ, constructeur, méthode, avec leurs signatures).
- Un test `PluginApiFingerprintTest` calcule l'empreinte actuelle (réflexion sur les classes des packages exportés du
  module) et la compare au fichier :
  - identique et même version : vert ;
  - des lignes **retirées ou modifiées**, ou des méthodes **abstraites ajoutées à un type déjà présent** : acceptable
    seulement si la version de la propriété a un **majeur** supérieur à celui du fichier ;
  - seulement d'autres lignes **ajoutées** : acceptable seulement si la version a au moins un **mineur** supérieur
    (un majeur supérieur convient aussi) ;
  - version relevée sans changement de surface : acceptable (on peut relever volontairement) ;
  - dans les cas acceptables où le fichier diffère (version ou lignes), le test reste rouge jusqu'à la régénération :
    le fichier enregistre toujours l'API de sa version (sinon un ajout ultérieur serait comparé à une version plus
    ancienne, déjà publiée sans lui) ;
  - le message d'échec dit quoi relever et comment régénérer le fichier.
- Régénération : `mvn clean verify -pl copybot-engine -Dplugin.api.update=true` réécrit le fichier (version de la
  propriété + empreinte actuelle) seulement dans les cas acceptables et affiche les lignes retirées/ajoutées ; un
  fichier supprimé n'est pas recréé. Puis un `mvn clean verify` complet (les plugins enregistrent la nouvelle version).
- Échappatoire : `-Dplugin.api.update=force` réécrit (ou crée) le fichier quelle que soit la comparaison, en affichant
  le diff ; légitime seulement après lecture du diff, pour un changement invisible des plugins (format des signatures
  d'un autre JDK, interfaces réordonnées, méthode abstraite devenue `default`).
- Ce que l'empreinte ne voit pas se relève à la main (README) : valeur des constantes `static final` (recopiées dans
  les plugins), `@Retention`/`@Target`, `permits` d'un type scellé, comportement, montée de version de Gson.

## 3. Catalogue, écran Plugins, CI

- Le champ `engine` du catalogue (format 1 inchangé) contient désormais le majeur.mineur de l'**API** pour laquelle
  l'archive a été construite ; la spec des plugins et le README le disent.
- L'écran Plugins compare avec la version d'API en cours (`VersionUtil.engineVersion()`), jamais avec la version de
  l'appli. Les textes nomment des versions d'API, jamais des versions de Copybot : « Fait pour l'API de plugins {0},
  ce Copybot fournit l'API {1} » (incompatible) et « La version {1} demande l'API de plugins {0} : mettez Copybot à
  jour » (une version plus récente attend), en/fr/it.
- `release.yml` / `plugin-catalogue.ps1` : le paramètre de version passé au script est la version d'API (lue par
  `mvn help:evaluate "-Dexpression=copybot.plugin.api.version"`) ; les garde-fous (entrée gardée encore compatible)
  utilisent cette version. Avant de construire, la release lance `PluginApiFingerprintTest` (un relèvement oublié
  arrête la release).
- La fenêtre À propos affiche aussi la version d'API des plugins.

## Laissé au constructeur

- La façon exacte de poser la version de module du moteur ; le format précis des lignes d'empreinte ; le libellé dans
  À propos (clé en/fr/it).

# UI desktop : accueil, vue du plan, éditeur générique de pipeline — Plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remplacer le banc de test de `copybot-ui` par l'IHM de la spec : un écran d'accueil (pipelines récents, rien n'est lancé à l'ouverture), une vue du plan (tableau à plat filtrable, préparation en tâche de fond, reprise modifiable, « Copier N fichiers (X Go) », exécution automatique, pause / reprise / stop, progression, récapitulatif enregistré) et un éditeur générique maître / détail dont les formulaires sont générés depuis le schéma de configuration des actions, sans jamais perdre un champ inconnu du fichier.

**Architecture:** Côté moteur, un package d'API plugin `com.copybot.plugin.api.config` : `ConfigSchema.of(recordClass)` introspecte les composants du record de configuration (types, `enum`, records imbriqués, listes) et leurs annotations (`@DirectoryPath`, `@FilePath`, `@PatternField`, `@DefaultValue`, `@Required`, plus `@AllowedValues` pour les `String` à valeurs fermées) ; `IAction.configSchema()` (vide par défaut) est fourni par `AbstractActionWithConfig` ; `PluginEngine.catalog()` expose les actions chargées (`CatalogAction` : plugin, version, code, type d'étape, nom / description et libellés des champs localisés, schéma) ; `Plan.targetOf` et `Plan.Counts` alimentent la colonne Cible et le bouton Copier. Côté UI, toute la logique est dans `com.copybot.ui.model`, sans JavaFX et testée : `RecentPipelines`, `PipelineDocument` (l'arbre `JsonObject` édité champ par champ), `StepCatalog`, `PipelineSummary`, `PlanViewModel`. Les contrôleurs JavaFX (`MainController`, `HomeController`, `PlanController`, `EditorController` + `ConfigForm`) sont minces : ils lancent les opérations moteur, nourrissent les modèles sur le thread FX et les affichent ; ils sont vérifiés à la main.

**Tech Stack:** Java 25 (JPMS), Maven multi-module, JUnit Jupiter `${junit.version}` (6.1.2), Gson 2.14, JavaFX 25.0.1 (copybot-ui), javafx-maven-plugin 0.0.8. Aucune nouvelle dépendance (JUnit est ajouté à `copybot-ui`, qui n'avait pas de tests).

**Spec:** `docs/superpowers/specs/2026-09-30-desktop-ui-design.md` (à lire avant chaque tâche).

**Prérequis (bloquant) :** ce plan s'exécute **après** le plan `docs/superpowers/plans/2026-10-01-safe-write.md`, **terminé** (ses 12 tâches commitées, `mvn -o clean install` vert), sur sa branche (`feature/safe-write`). Il est écrit contre l'état que ce plan produit :
- `IAction.configWarnings()`, `IOutAction.write(WorkItem, WriteContext)`, `WriteResult`, `WriteContext` ;
- `PipelineState.getWarnings()` / `setWarnings(List<String>)` ;
- `FileWriteConfig(String outPattern, Boolean overwrite, OnConflict onConflict, String writeMode, String verify, Boolean deleteSource)` avec `record OnConflict(String compare, String ifIdentical, String ifDifferent)`, et `FileWriteSettings` (package-private) avec ses enums `Compare`, `Policy`, `WriteMode`, `Verify` (`jsonName()`, interface `Named`) ;
- `FileReadConfig(String path, Boolean recursive, List<String> include, List<String> exclude, Boolean includeHidden)` ;
- le bandeau d'avertissements minimal de `hello-view.fxml` / `HelloController` (supprimés ici, tâche 16).

Contrôle avant la tâche 1 (depuis la racine du repo) :

```bash
grep -c "getWarnings" copybot-engine/src/main/java/com/copybot/engine/pipeline/PipelineState.java
grep -c "includeHidden" copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileReadConfig.java
grep -c "record OnConflict" copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileWriteConfig.java
ls copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileWriteSettings.java
grep -c "configWarnings" copybot-engine/src/main/java/com/copybot/plugin/api/action/IAction.java
```

Expected : `1`, au moins `1`, `1`, le chemin, au moins `1`. Sinon : **STOP**, exécuter d'abord le plan de l'écriture sûre.

Tout le code ci-dessous a été compilé et testé (`mvn -o -q -pl copybot-engine,copybot-ui test` vert, plus le reste du réacteur en `test-compile`) contre une copie du dépôt à laquelle les éléments ci-dessus de l'écriture sûre avaient été appliqués tels que son plan les donne ; les vues JavaFX ont été chargées et une préparation + copie a été déroulée par les contrôleurs.

## Global Constraints

- Java 25 ; code, noms et javadoc en **anglais** (convention du code existant) ; ce plan et la spec en français.
- **API plugin : seulement des ajouts compatibles.** Nouveau package exporté `com.copybot.plugin.api.config` ; méthode `default` `IAction.configSchema()` ; `AbstractActionWithConfig.configSchema()` (surcharge publique). Aucune méthode existante ne change de signature ; `copybot-plugin/*` et `copybot-plugin-demo/*` ne sont **pas** modifiés et compilent tels quels. `com.copybot.engine.plugin` est exporté **à `com.copybot.ui` seulement** (comme les autres paquets moteur).
- La logique de l'IHM va dans `com.copybot.ui.model` (aucune classe JavaFX) et est testée ; les contrôleurs JavaFX restent minces et ne sont pas testés automatiquement (spec §7) : chaque tâche JavaFX finit par une **liste de vérification manuelle**.
- Aucun test existant n'est modifié. Les tests de `copybot-ui` sont nouveaux (le module n'en avait pas).
- Messages de l'IHM via `ResourcesEngine.getString(key, args...)` : **toujours** passés par `MessageFormat` (même sans argument), donc apostrophe doublée dans les valeurs FR utilisées depuis Java ; les clés utilisées en `%clé` dans le FXML (sans `MessageFormat`) n'ont pas d'apostrophe. Nombres de compteurs passés tels quels (`Integer`) ; tailles et pourcentages passés en `String`.
- **Encodage des bundles, octet par octet, append-only via `printf`, jamais ouverts avec Edit/Write :**
  - `pluginBundle_fr.properties` (moteur) : **ISO-8859-1, CRLF, sans fin de ligne finale** ; `pluginBundle.properties` : ASCII, CRLF, sans fin de ligne finale. Le premier `printf '\r\n'` termine la dernière ligne existante (le `git diff` la montre alors modifiée : seule sa fin de ligne change). Contrôles : `file` ⇒ `ISO-8859 text, with CRLF line terminators` / `ASCII text, with CRLF line terminators` ; `grep -c $'\xc3' pluginBundle_fr.properties` ⇒ `0` ; `grep -c $'\xef\xbf\xbd' pluginBundle_fr.properties` ⇒ `0` (grep sort en code 1 quand il compte 0 : attendu).
  - `uiBundle.properties` / `uiBundle_fr.properties` (UI) : **UTF-8, LF**, avec fin de ligne finale (le FR contient déjà 5 lignes aux accents UTF-8 littéraux). Les nouvelles lignes sont en **ASCII pur** (échappements `\uXXXX`), valables dans les deux encodages. Contrôles : `file` ⇒ `UTF-8 Unicode text` (pas de `CRLF`) ; `grep -c $'\xc3' uiBundle_fr.properties` ⇒ `5` (inchangé) ; `grep -c $'\xef\xbf\xbd'` ⇒ `0`. `uiBundle_it.properties` n'est pas modifié (les clés absentes y retombent sur l'anglais).
  - Dans ce Git Bash, un `é` littéral s'écrit en octal **`\134u00e9`** dans la chaîne de format de `printf` (sinon `printf` le convertit en UTF-8), l'apostrophe en **`\047`**, un `\n` littéral de properties en `\134n`, un `%` en `%%` ; les lignes anglaises (ASCII) s'ajoutent avec `printf '%s\r\n'` (moteur) ou `printf '%s\n'` (UI) et un argument entre apostrophes (aucun échappement interprété). Les blocs `printf` de ce plan s'exécutent **depuis un fichier** : les écrire tels quels avec l'outil Write dans le scratchpad (`<scratchpad>/labels.sh`), puis `bash <scratchpad>/labels.sh` depuis la racine du repo (un long bloc passé directement à l'outil Bash a été vu échouer sur le découpage des apostrophes).
- **Préserver les fins de ligne de chaque fichier.** CRLF : `IAction.java`, `AbstractActionWithConfig.java`, `PluginEngine.java`, `FileUtil.java`, `FileWriteConfig.java`, `FileReadConfig.java`, les deux `pluginBundle*.properties`. LF : les deux `module-info.java`, `Plan.java`, `MainExecutor.java`, `copybot-ui/pom.xml`, tous les fichiers de `copybot-ui` (Java, FXML, bundles), tous les tests. Nouveaux fichiers : LF. L'outil Edit conserve le CRLF d'un fichier CRLF ; après une **réécriture complète** d'un fichier CRLF avec Write : `unix2dos -q <fichier>`. Dans les fichiers CRLF existants, les ajouts restent en ASCII (pas de `§` : « desktop-ui spec, part 4 »). Contrôle après chaque tâche : `git ls-files --eol <fichiers modifiés>` ⇒ `w/crlf` ou `w/lf` comme avant, jamais `w/mixed`.
- Créer / réécrire les fichiers Java et FXML avec l'outil **Write** (pas de heredoc Bash : les `\\` d'un heredoc passé par l'outil Bash sont altérés).
- Les tests ne comparent **jamais** le texte d'un message traduit : présence (`!startsWith("%")`, non vide), valeur citée (`contains("DSC_4821.JPG")`, `contains("42")`), nombre formaté hors unité (`"1.5"`), ou chaîne littérale d'un fake. Aucun test ne dépend de la locale.
- Toute attente d'un test est bornée : les nouveaux tests n'attendent aucun thread (préparations synchrones `executor.prepare()`, modèles nourris à la main) ; aucun `await()` / `join()` sans délai.
- Les tests UI n'écrivent **pas** dans les préférences de l'utilisateur : `RecentPipelines` se teste par sa sérialisation JSON ; `UiPreferences` (registre Windows) n'est utilisé que par les contrôleurs.
- Maven **hors ligne** depuis la racine du repo. Moteur : `mvn -o -q -pl copybot-engine test [-Dtest=<Classe>]`. UI (le réacteur recompile le moteur modifié) : `mvn -o -q -pl copybot-engine,copybot-ui test -Dtest=<Classe> -Dsurefire.failIfNoSpecifiedTests=false` ; tout : `mvn -o -q -pl copybot-engine,copybot-ui test`. Vérification finale : `mvn -o clean install`. En `-q`, un succès n'affiche que les `WARNING` `sun.misc.Unsafe` de Maven, les sorties des tests CLI du moteur (lignes `ERROR IMG_…`, stacktraces `--debug`) et les `AVERTISSEMENT: Plugins déjà chargés…` des tests qui rechargent les plugins : « PASS » = aucune ligne `[ERROR]` et code retour 0.
- Lancement manuel de l'IHM : le lanceur de dev `CopybotMainUiDev` passe `--config-file=./copybot-ui/src/dev/config.json`, chemin **relatif à la racine du repo** : depuis l'IDE, configuration d'exécution de `com.copybot.ui.CopybotMainUiDev` avec la racine du repo comme répertoire de travail ; en ligne de commande (le plugin javafx n'a de `mainClass` que dans son exécution `javafx_jlink`, d'où les propriétés ; `-pl copybot-ui` seul lit le moteur dans `~/.m2`, d'où l'`install` préalable) :

  ```bash
  mvn -o -q -pl copybot-engine install -DskipTests
  mvn -o -q -pl copybot-ui compile javafx:run "-Djavafx.mainClass=com.copybot.ui/com.copybot.ui.CopybotMainUiDev" "-Djavafx.workingDirectory=$(pwd -W)"
  ```

  (`pwd -W` donne le chemin Windows de la racine dans Git Bash.) Le pipeline de dev `copybot-ui/src/dev/test-pipeline.json` lit `copybot-ui/src/dev/test-in` (3 fichiers) et écrit dans `copybot-ui/target/ui-test-out/{name}`.
- Un commit par tâche, `git add` ciblé sur les fichiers de la tâche uniquement (les non suivis `.superpowers/` et `copybot-ui/*.ico` / `*.png` ne sont **jamais** ajoutés). Commit : `git commit -m "<sujet>" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"`.

## Carte des fichiers

Moteur : production sous `copybot-engine/src/main/java/com/copybot/`, tests sous `copybot-engine/src/test/java/com/copybot/`, bundles du plugin embarqué sous `copybot-engine/src/main/resources/com/copybot/plugin/embedded/i18n/`. UI : production sous `copybot-ui/src/main/java/com/copybot/ui/`, vues et bundles sous `copybot-ui/src/main/resources/com/copybot/ui/`, tests sous `copybot-ui/src/test/java/com/copybot/ui/`.

| Fichier | Rôle | Tâches |
|---|---|---|
| `plugin/api/config/DirectoryPath.java`, `FilePath.java`, `PatternField.java`, `Required.java`, `DefaultValue.java`, `AllowedValues.java` | **Créer** — annotations de composants de record (runtime) | 1 |
| `plugin/api/config/FieldKind.java`, `FieldHint.java`, `ConfigField.java`, `ConfigSchema.java` | **Créer** — schéma par introspection, clés i18n, variables de pattern | 1 |
| `module-info.java` (moteur) | + `exports com.copybot.plugin.api.config` (1) ; + `exports com.copybot.engine.plugin to com.copybot.ui` (4) | 1, 4 |
| `plugin/api/action/IAction.java` | + `default Optional<ConfigSchema> configSchema()` | 2 |
| `plugin/api/action/AbstractActionWithConfig.java` | + `configSchema()` depuis `getConfigClass()` | 2 |
| `plugin/embedded/actions/FileWriteConfig.java`, `FileReadConfig.java` | réécriture : mêmes composants, annotés | 3 |
| `pluginBundle.properties`, `pluginBundle_fr.properties` | + 31 libellés (actions, champs) | 3 |
| `engine/plugin/CatalogAction.java` | **Créer** — une action du catalogue | 4 |
| `engine/plugin/PluginEngine.java` | + `catalog()` | 4 |
| `utils/FileUtil.java` | `toAutoUnitSize` : Go divisé par 1 Gio, Ko par 1 Kio | 5 |
| `engine/Plan.java` | + `Plan.Counts`, `counts()`, `targetOf(item)` | 6 |
| `engine/MainExecutor.java` | `findOutAction()` package-private, sûr avant la résolution | 6 |
| `copybot-ui/pom.xml`, `copybot-ui/src/main/java/module-info.java` | + JUnit (test) ; + `requires com.google.gson` | 7 |
| `ui/model/RecentPipelines.java` | **Créer** — récents, dernière exécution, JSON | 7 |
| `ui/util/UiPreferences.java` | réécriture : + `recents()`, `updateRecents(...)` | 7 |
| `uiBundle.properties`, `uiBundle_fr.properties` | + 99 textes de l'IHM | 8 |
| `ui/model/PipelineDocument.java` | **Créer** — chargement / enregistrement, champs du pipeline (9) ; étapes (11) ; champs, avancé, validation (12) | 9, 11, 12 |
| `ui/model/StepCatalog.java` | **Créer** — actions d'une section, résolution d'une étape | 10 |
| `ui/model/PipelineSummary.java` | **Créer** — en-tête de la vue du plan | 13 |
| `ui/model/PlanViewModel.java` | **Créer** — phases, boutons, filtre, compteurs, textes, reprise, progression, fin d'exécution | 14 |
| `ui/util/Views.java`, `ui/ConfigForm.java`, `ui/EditorController.java`, `views/editor-view.fxml` | **Créer** — éditeur maître / détail | 15 |
| `ui/MainController.java`, `ui/HomeController.java`, `ui/PlanController.java`, `views/main-view.fxml`, `views/home-view.fxml`, `views/plan-view.fxml` | **Créer** — fenêtre, accueil, vue du plan | 16 |
| `ui/CopybotMainUi.java` | réécriture : vue principale `main-view.fxml` | 16 |
| `ui/HelloController.java`, `ui/HelloController2.java`, `views/hello-view.fxml`, `views/hello-view2.fxml` | **Supprimer** — banc de test remplacé (spec §7) | 16 |
| Tests moteur : `plugin/api/config/ConfigSchemaTest.java` | **Créer** | 1 |
| Tests moteur : `plugin/api/action/ConfigSchemaActionTest.java` | **Créer** | 2 |
| Tests moteur : `plugin/embedded/actions/EmbeddedConfigSchemaTest.java` | **Créer** — schémas embarqués alignés sur `FileWriteSettings`, libellés EN / FR | 3 |
| Tests moteur : `engine/plugin/PluginCatalogTest.java` | **Créer** | 4 |
| Tests moteur : `utils/FileUtilTest.java` | **Créer** | 5 |
| Tests moteur : `engine/PlanTest.java` | **Créer** | 6 |
| Tests UI : `model/RecentPipelinesTest.java` | **Créer** | 7 |
| Tests UI : `model/UiBundleTest.java` | **Créer** | 8 |
| Tests UI : `model/PipelineDocumentTest.java` | **Créer** (9), + étapes (11), + champs et validation (12) | 9, 11, 12 |
| Tests UI : `model/TestCatalog.java`, `model/StepCatalogTest.java` | **Créer** — actions de catalogue faites main | 10 |
| Tests UI : `model/PipelineSummaryTest.java` | **Créer** | 13 |
| Tests UI : `model/PlanViewModelTest.java` | **Créer** | 14 |

## Interprétations retenues (ambiguïtés de la spec)

1. **ENUM d'une `String`** : les configurations de l'écriture sûre gardent des `String` (`writeMode`, `verify`, `onConflict.*`) pour signaler elles-mêmes une valeur inconnue ; une annotation de plus, `@AllowedValues({...})`, en fait un champ `ENUM` (valeurs = celles du JSON). Un vrai `enum` Java donne ses noms de constantes.
2. **Clés i18n** : l'action ne connaît pas son code ; l'introspection produit `config.<chemin>.name` / `.description` (chemin pointé des records imbriqués, composant `path` de `ConfigField`), et le catalogue les préfixe de `plugin.<code plugin>.<code action>` (`ConfigSchema.withKeyPrefix`) : `plugin.embedded.file.write.config.onConflict.compare.name`. Un élément de liste a les clés de sa liste.
3. **Types non décrits** : un composant `Map`, `JsonElement`, `Object`… ou d'un type record en cours d'introspection (cycle) est **absent** du schéma ; l'éditeur ne le montre pas et le conserve tel quel. Une liste de records est montrée en JSON, en lecture seule.
4. **Catalogue** : `PluginEngine.catalog()` liste toutes les versions chargées, dans l'ordre du moteur (plus récente d'abord) ; l'éditeur ne propose à l'ajout que la plus récente de chaque plugin ; une étape existante se résout comme `PluginEngine.resolve` (plugin absent ou vide ⇒ embarqué, `VersionUtil.isCompatible(…, strict)`). `CatalogAction.EMBEDDED_PLUGIN` expose le nom `embedded` (le paquet de `CBEmbeddedPlugin` n'est pas exporté). Textes résolus à l'appel : les vues reconstruisent le catalogue à chaque ouverture (changement de langue).
5. **Compteurs** (`Plan.Counts`) : « sélectionnés » = `PENDING`, `WAITING_RESOURCES`, `RUNNING`, `DONE` (tout sauf `SKIPPED` / `ERROR`) ; un item sans taille compte 0 octet ; `Counts.of(items)` est public pour que le modèle de vue compte sur ses instantanés. **Cible** = répertoire parent de `resolveTarget`, vide sans étape de sortie ou si le plugin lève.
6. **`FileUtil.toAutoUnitSize`** divisait les Go par 1 Kio et les Ko par 1 Gio : corrigé (tâche 5), le libellé « X Go » et la colonne Taille en dépendent.
7. **Récents** : chemin absolu normalisé ; ordre = dernière ouverture (vue du plan ouverte, « Ouvrir… », enregistrement d'un « Nouveau… ») ; enregistrer une exécution ne réordonne pas ; « introuvable » = plus un fichier régulier, un clic dessus ne fait rien (seul « Retirer » reste) ; persistés en JSON dans une seule préférence, les plus anciens retirés au-delà de `Preferences.MAX_VALUE_LENGTH` ; une entrée illisible est ignorée.
8. **Boutons pendant une opération** : « ← Pipelines », « Éditer… » et « Préparer le plan » sont désactivés pendant la préparation aussi (le moteur est occupé) ; « Stop » n'existe que pendant l'exécution (`CopybotEngine` n'annule une préparation que par `close`, à la fermeture de la fenêtre) ; le menu Préférences est désactivé pendant une opération ; un changement de langue recharge la même vue du plan, non préparée.
9. **Exécution automatique** : démarre une fois par préparation, seulement si le plan est `PREPARED` avec au moins un fichier sélectionné ; la case n'agit que sur la session (le fichier ne change que par l'éditeur).
10. **Progression** : totaux = fichiers et octets sélectionnés au clic sur Copier ; traités = `DONE`, `SKIPPED` (sauté à l'écriture) ou `ERROR` ; barre en octets, en fichiers si aucune taille n'est connue.
11. **Statuts** : en plus de la spec, `WAITING_RESOURCES` ⇒ « En attente — <ressources> » et `RUNNING` sans pourcentage ⇒ « Copie… ».
12. **Ligne de reprise** : « Reprise : après|à partir de <nom> (jj/MM HH:mm) [origine] », « à partir du <jj/MM/aaaa> » pour une date choisie, « tout » ; origine `NONE` / `STATE` / `DESTINATION` / `MANUAL` = « rien de détecté » / « curseur » / « destination » / « manuel ». Le dialogue « changer… » propose les fichiers du plan qui ont une clé de reprise. Un point manuel est appliqué par `Plan.preview` puis passé à `execute`.
13. **Éditeur** : fenêtre **modale** ; une étape d'un plugin introuvable est en lecture seule mais peut être déplacée ou supprimée ; « Avancé » = membres de l'étape elle-même ; un texte vide retire le membre (et les objets qu'il laisse vides dans `actionConfig`), la même valeur ne réécrit rien (`8` reste `8`) ; booléens et énumérés en liste déroulante avec une entrée vide « défaut : x » (absent ≠ `false`) ; `startProcessingWhileListing` et `ui.autoExecute` ne sont écrits que à `true` (retirés à `false`, le défaut) ; « sans reprise » retire le bloc `resume`, sauf s'il contient d'autres membres (son mode devient alors `none`). Le fichier est réécrit en JSON indenté (Gson, sans échappement HTML) : le contenu est préservé, pas la mise en forme.
14. **Enregistrer sous…** depuis la vue du plan : la vue suit le nouveau fichier ; depuis « Nouveau… » (accueil) : le fichier rejoint les récents. « Enregistrer » d'un nouveau pipeline = « Enregistrer sous… ».
15. **Validation** : seulement les champs requis vides (texte blanc, liste vide), d'une étape dont l'action est chargée ; un requis d'un record ne compte que si le record est présent ; une étape sans `action` est signalée ; rien d'autre n'est validé (le moteur signale le reste à la préparation).
16. **Bundles UI** : ils sont en UTF-8 (pas ISO-8859-1) : mêmes règles append-only, lignes ASCII échappées ; l'italien retombe sur l'anglais.
17. **Banc de test** : `HelloController(2)` et `hello-view(2).fxml` (dont le bandeau minimal de l'écriture sûre, repris par la vue du plan) sont supprimés ; leurs clés `test.btn*` restent dans les bundles (append-only). Le menu « Édition › Supprimer », sans action, disparaît.
18. **Dernière exécution** de l'en-tête et de l'accueil : « <jj/MM/aaaa HH:mm> — <statut> : N copiés, N ignorés, N erreurs » ; statut `SUCCESS` / `ERROR` / `CANCELLED` = « Succès » / « Échec » / « Arrêté ».

---

### Task 1: API plugin — schéma de configuration par introspection

**Files:**
- Create: `copybot-engine/src/main/java/com/copybot/plugin/api/config/DirectoryPath.java`, `FilePath.java`, `PatternField.java`, `Required.java`, `DefaultValue.java`, `AllowedValues.java`
- Create: `copybot-engine/src/main/java/com/copybot/plugin/api/config/FieldKind.java`, `FieldHint.java`, `ConfigField.java`, `ConfigSchema.java`
- Modify: `copybot-engine/src/main/java/module-info.java` (LF)
- Create: `copybot-engine/src/test/java/com/copybot/plugin/api/config/ConfigSchemaTest.java`

**Interfaces:**
- Consumes: `WorkItemMetadata.LAST_MODIFIED` / `CAPTURE_DATE` (existants).
- Produces: annotations `@DirectoryPath`, `@FilePath`, `@PatternField`, `@Required`, `@DefaultValue(String value)`, `@AllowedValues(String[] value)` (`RUNTIME`, cible `RECORD_COMPONENT`) ; `enum FieldKind { STRING, BOOLEAN, INTEGER, DECIMAL, PATH, ENUM, RECORD, LIST }` ; `enum FieldHint { DIRECTORY, FILE, PATTERN }` ; `public record ConfigField(String name, String path, FieldKind kind, boolean required, String defaultValue, Set<FieldHint> hints, String labelKey, String descriptionKey, List<ConfigField> children, List<String> enumValues, ConfigField elementSchema)` avec `boolean hasHint(FieldHint)`, `ConfigField withKeyPrefix(String)` ; `public record ConfigSchema(List<ConfigField> fields)` avec `static final List<String> PATTERN_VARIABLES`, `static ConfigSchema of(Class<? extends Record>)`, `ConfigSchema withKeyPrefix(String prefix)`, `Optional<ConfigField> field(String path)`, `List<ConfigField> allFields()`.

- [ ] **Step 1: Write the failing test**

`copybot-engine/src/test/java/com/copybot/plugin/api/config/ConfigSchemaTest.java` :

```java
package com.copybot.plugin.api.config;

import com.google.gson.JsonElement;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The configuration schema built by introspection of a record (spec desktop-ui §4). */
public class ConfigSchemaTest {

    enum Mode { FAST, SAFE }

    record Nested(@Required String inner, @DefaultValue("3") Integer count) {
    }

    record Sample(
            @Required @DirectoryPath String source,
            @FilePath Path file,
            Path anyPath,
            @PatternField String pattern,
            boolean flag,
            @DefaultValue("true") Boolean boxedFlag,
            int small,
            Long big,
            double ratio,
            BigDecimal price,
            Mode mode,
            @AllowedValues({"a", "b"}) @DefaultValue("a") String choice,
            Nested nested,
            List<String> globs,
            List raw,
            Set<Integer> numbers,
            List<Nested> items,
            Map<String, String> map,
            JsonElement json) {
    }

    record Loop(String name, Loop next) {
    }

    private static ConfigField field(ConfigSchema schema, String path) {
        return schema.field(path).orElseThrow(() -> new AssertionError("no field " + path));
    }

    @Test
    public void everyComponentGetsItsKindInComponentOrder() {
        ConfigSchema schema = ConfigSchema.of(Sample.class);

        assertEquals(List.of("source", "file", "anyPath", "pattern", "flag", "boxedFlag", "small", "big", "ratio",
                        "price", "mode", "choice", "nested", "globs", "raw", "numbers", "items"),
                schema.fields().stream().map(ConfigField::name).toList(), "map and json are left out");
        assertEquals(FieldKind.PATH, field(schema, "source").kind());
        assertEquals(FieldKind.PATH, field(schema, "file").kind());
        assertEquals(FieldKind.PATH, field(schema, "anyPath").kind());
        assertEquals(FieldKind.STRING, field(schema, "pattern").kind());
        assertEquals(FieldKind.BOOLEAN, field(schema, "flag").kind());
        assertEquals(FieldKind.BOOLEAN, field(schema, "boxedFlag").kind());
        assertEquals(FieldKind.INTEGER, field(schema, "small").kind());
        assertEquals(FieldKind.INTEGER, field(schema, "big").kind());
        assertEquals(FieldKind.DECIMAL, field(schema, "ratio").kind());
        assertEquals(FieldKind.DECIMAL, field(schema, "price").kind());
        assertEquals(FieldKind.RECORD, field(schema, "nested").kind());
        assertEquals(FieldKind.LIST, field(schema, "globs").kind());
    }

    @Test
    public void annotationsGiveHintsRequiredAndDefaults() {
        ConfigSchema schema = ConfigSchema.of(Sample.class);

        assertEquals(Set.of(FieldHint.DIRECTORY), field(schema, "source").hints());
        assertEquals(Set.of(FieldHint.FILE), field(schema, "file").hints());
        assertEquals(Set.of(), field(schema, "anyPath").hints());
        assertTrue(field(schema, "pattern").hasHint(FieldHint.PATTERN));
        assertTrue(field(schema, "source").required());
        assertFalse(field(schema, "file").required());
        assertEquals("true", field(schema, "boxedFlag").defaultValue());
        assertNull(field(schema, "flag").defaultValue());
    }

    @Test
    public void enumsAndAllowedValuesListTheirValues() {
        ConfigSchema schema = ConfigSchema.of(Sample.class);

        assertEquals(FieldKind.ENUM, field(schema, "mode").kind());
        assertEquals(List.of("FAST", "SAFE"), field(schema, "mode").enumValues());
        assertEquals(FieldKind.ENUM, field(schema, "choice").kind());
        assertEquals(List.of("a", "b"), field(schema, "choice").enumValues());
        assertEquals("a", field(schema, "choice").defaultValue());
        assertEquals(List.of(), field(schema, "pattern").enumValues());
    }

    @Test
    public void recordsHaveChildrenWithDottedPaths() {
        ConfigSchema schema = ConfigSchema.of(Sample.class);

        ConfigField nested = field(schema, "nested");
        assertEquals(List.of("nested.inner", "nested.count"), nested.children().stream().map(ConfigField::path).toList());
        assertTrue(field(schema, "nested.inner").required());
        assertEquals(FieldKind.INTEGER, field(schema, "nested.count").kind());
        assertEquals("3", field(schema, "nested.count").defaultValue());
        assertTrue(schema.allFields().indexOf(nested) < schema.allFields().indexOf(field(schema, "nested.inner")),
                "a record comes before its children");
    }

    @Test
    public void listsDescribeTheirElement() {
        ConfigSchema schema = ConfigSchema.of(Sample.class);

        assertEquals(FieldKind.STRING, field(schema, "globs").elementSchema().kind());
        assertEquals(FieldKind.STRING, field(schema, "raw").elementSchema().kind(), "a raw list holds strings");
        assertEquals(FieldKind.INTEGER, field(schema, "numbers").elementSchema().kind());
        ConfigField items = field(schema, "items");
        assertEquals(FieldKind.RECORD, items.elementSchema().kind());
        assertEquals(List.of("items.inner", "items.count"),
                items.elementSchema().children().stream().map(ConfigField::path).toList());
        assertNull(field(schema, "source").elementSchema());
    }

    @Test
    public void aCycleIsLeftOut() {
        assertEquals(List.of("name"), ConfigSchema.of(Loop.class).fields().stream().map(ConfigField::name).toList());
    }

    @Test
    public void i18nKeysFollowThePathAndTakeThePluginPrefix() {
        ConfigSchema schema = ConfigSchema.of(Sample.class);
        assertEquals("config.nested.inner.name", field(schema, "nested.inner").labelKey());
        assertEquals("config.nested.inner.description", field(schema, "nested.inner").descriptionKey());

        ConfigSchema prefixed = schema.withKeyPrefix("plugin.demo.do.it");

        assertEquals("plugin.demo.do.it.config.source.name", field(prefixed, "source").labelKey());
        assertEquals("plugin.demo.do.it.config.nested.count.description", field(prefixed, "nested.count").descriptionKey());
        assertEquals("plugin.demo.do.it.config.globs.name", field(prefixed, "globs").elementSchema().labelKey());
        assertEquals("plugin.demo.do.it.config.items.inner.name",
                field(prefixed, "items").elementSchema().children().getFirst().labelKey());
    }

    @Test
    public void thePatternVariablesAreTheOnesOfTheEmbeddedActions() {
        assertTrue(ConfigSchema.PATTERN_VARIABLES.containsAll(List.of("name", "size", "sizeHr",
                "creation.Y", "lastModified.m", "captureDate.D", "captureDate.y")));
        assertEquals(15, ConfigSchema.PATTERN_VARIABLES.size());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -o -q -pl copybot-engine test -Dtest=ConfigSchemaTest`
Expected: FAIL — compilation `package com.copybot.plugin.api.config does not exist` / `cannot find symbol: class ConfigSchema`.

- [ ] **Step 3: Implement**

`copybot-engine/src/main/java/com/copybot/plugin/api/config/DirectoryPath.java` :

```java
package com.copybot.plugin.api.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A directory: the editor offers a directory chooser (spec desktop-ui §4). */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface DirectoryPath {
}
```

`copybot-engine/src/main/java/com/copybot/plugin/api/config/FilePath.java` :

```java
package com.copybot.plugin.api.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A file: the editor offers a file chooser (spec desktop-ui §4). */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface FilePath {
}
```

`copybot-engine/src/main/java/com/copybot/plugin/api/config/PatternField.java` :

```java
package com.copybot.plugin.api.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** An output pattern: the editor lists the known pattern variables ({@link ConfigSchema#PATTERN_VARIABLES}). */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface PatternField {
}
```

`copybot-engine/src/main/java/com/copybot/plugin/api/config/Required.java` :

```java
package com.copybot.plugin.api.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The field must be set before the pipeline is saved (the editor refuses an empty value). */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Required {
}
```

`copybot-engine/src/main/java/com/copybot/plugin/api/config/DefaultValue.java` :

```java
package com.copybot.plugin.api.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The value the action uses when the field is absent, as written in the pipeline JSON (e.g. "size",
 * "true", "8"). Shown by the editor; never written into the pipeline.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface DefaultValue {
    String value();
}
```

`copybot-engine/src/main/java/com/copybot/plugin/api/config/AllowedValues.java` :

```java
package com.copybot.plugin.api.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A String field that only takes these values, as written in the pipeline JSON: its kind is
 * {@link FieldKind#ENUM} (the action keeps a String to report an unknown value itself).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface AllowedValues {
    String[] value();
}
```

`copybot-engine/src/main/java/com/copybot/plugin/api/config/FieldKind.java` :

```java
package com.copybot.plugin.api.config;

/** What a configuration field holds, i.e. which editor control it gets (spec desktop-ui §4). */
public enum FieldKind {
    STRING,
    BOOLEAN,
    INTEGER,
    DECIMAL,
    /** a file system path, see {@link FieldHint#DIRECTORY} / {@link FieldHint#FILE} */
    PATH,
    /** one of {@link ConfigField#enumValues()} */
    ENUM,
    /** a nested object, see {@link ConfigField#children()} */
    RECORD,
    /** a JSON array, see {@link ConfigField#elementSchema()} */
    LIST
}
```

`copybot-engine/src/main/java/com/copybot/plugin/api/config/FieldHint.java` :

```java
package com.copybot.plugin.api.config;

/** How the editor helps to fill a field, from its annotations. */
public enum FieldHint {
    /** {@link DirectoryPath}: a directory chooser */
    DIRECTORY,
    /** {@link FilePath}: a file chooser */
    FILE,
    /** {@link PatternField}: the known pattern variables are listed */
    PATTERN
}
```

`copybot-engine/src/main/java/com/copybot/plugin/api/config/ConfigField.java` :

```java
package com.copybot.plugin.api.config;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One field of an action configuration (spec desktop-ui §4).
 *
 * @param name           the record component name, i.e. the JSON member name
 * @param path           the dotted path from the configuration root (e.g. "onConflict.compare")
 * @param defaultValue   {@link DefaultValue}, null when none
 * @param labelKey       i18n key of the label: "config.&lt;path&gt;.name", prefixed by
 *                       {@link ConfigSchema#withKeyPrefix} with "plugin.&lt;plugin&gt;.&lt;action&gt;"
 * @param descriptionKey i18n key of the description: "config.&lt;path&gt;.description", prefixed alike
 * @param children       the fields of a {@link FieldKind#RECORD}, empty otherwise
 * @param enumValues     the values of an {@link FieldKind#ENUM}, empty otherwise
 * @param elementSchema  the element of a {@link FieldKind#LIST} (same name, path and keys), null otherwise
 */
public record ConfigField(
        String name,
        String path,
        FieldKind kind,
        boolean required,
        String defaultValue,
        Set<FieldHint> hints,
        String labelKey,
        String descriptionKey,
        List<ConfigField> children,
        List<String> enumValues,
        ConfigField elementSchema) {

    public ConfigField {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(kind, "kind");
        hints = Set.copyOf(hints);
        children = List.copyOf(children);
        enumValues = List.copyOf(enumValues);
    }

    public boolean hasHint(FieldHint hint) {
        return hints.contains(hint);
    }

    /** This field, its children and its element schema with "prefix." in front of every i18n key. */
    public ConfigField withKeyPrefix(String prefix) {
        return new ConfigField(name, path, kind, required, defaultValue, hints,
                prefix + "." + labelKey, prefix + "." + descriptionKey,
                children.stream().map(child -> child.withKeyPrefix(prefix)).toList(),
                enumValues,
                elementSchema == null ? null : elementSchema.withKeyPrefix(prefix));
    }
}
```

`copybot-engine/src/main/java/com/copybot/plugin/api/config/ConfigSchema.java` :

```java
package com.copybot.plugin.api.config;

import com.copybot.plugin.api.action.WorkItemMetadata;

import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The configuration fields of an action, for the generic pipeline editor (spec desktop-ui §4). Built by
 * introspection of the record components of the configuration class ({@link #of}); an action may also
 * build one by hand when introspection is not enough.
 */
public record ConfigSchema(List<ConfigField> fields) {

    /** The variables of an output pattern known to the embedded actions, without braces. */
    public static final List<String> PATTERN_VARIABLES = patternVariables();

    private static final Set<Class<?>> INTEGER_TYPES = Set.of(int.class, Integer.class, long.class, Long.class,
            short.class, Short.class, byte.class, Byte.class, BigInteger.class);
    private static final Set<Class<?>> DECIMAL_TYPES = Set.of(double.class, Double.class, float.class, Float.class,
            BigDecimal.class);

    public ConfigSchema {
        fields = List.copyOf(fields);
    }

    /**
     * The fields of this record, in component order: String and Path (PATH with {@link DirectoryPath} /
     * {@link FilePath}, ENUM with {@link AllowedValues}), booleans, integers, decimals, enums (constant
     * names), records (RECORD, recursively) and List / Set / Collection (LIST, of String when raw). A
     * component of another type (Map, JsonElement...) or of a record type being introspected (a cycle) is
     * left out: the editor keeps its JSON value untouched.
     */
    public static ConfigSchema of(Class<? extends Record> configClass) {
        return new ConfigSchema(recordFields(configClass, "", new HashSet<>()));
    }

    /** The same schema with "prefix." in front of every i18n key (e.g. "plugin.embedded.file.write"). */
    public ConfigSchema withKeyPrefix(String prefix) {
        return new ConfigSchema(fields.stream().map(field -> field.withKeyPrefix(prefix)).toList());
    }

    /** The field at this dotted path (e.g. "onConflict.compare"), looking into the records. */
    public Optional<ConfigField> field(String path) {
        return allFields().stream().filter(field -> field.path().equals(path)).findFirst();
    }

    /** Every field, depth first: a record field is followed by its children (list elements excluded). */
    public List<ConfigField> allFields() {
        List<ConfigField> all = new ArrayList<>();
        addAll(fields, all);
        return List.copyOf(all);
    }

    private static void addAll(List<ConfigField> fields, List<ConfigField> all) {
        for (ConfigField field : fields) {
            all.add(field);
            addAll(field.children(), all);
        }
    }

    private static List<ConfigField> recordFields(Class<?> recordClass, String parentPath, Set<Class<?>> ancestors) {
        ancestors.add(recordClass);
        List<ConfigField> fields = new ArrayList<>();
        for (RecordComponent component : recordClass.getRecordComponents()) {
            String path = parentPath.isEmpty() ? component.getName() : parentPath + "." + component.getName();
            field(component.getName(), path, component.getType(), component.getGenericType(), component, ancestors)
                    .ifPresent(fields::add);
        }
        ancestors.remove(recordClass);
        return fields;
    }

    /** @param component the annotated record component, null for a list element */
    private static Optional<ConfigField> field(String name, String path, Class<?> type, Type genericType,
                                               RecordComponent component, Set<Class<?>> ancestors) {
        Set<FieldHint> hints = EnumSet.noneOf(FieldHint.class);
        List<ConfigField> children = List.of();
        List<String> enumValues = List.of();
        ConfigField element = null;
        FieldKind kind;
        if (type == String.class || type == Path.class) {
            if (annotated(component, DirectoryPath.class)) {
                hints.add(FieldHint.DIRECTORY);
            }
            if (annotated(component, FilePath.class)) {
                hints.add(FieldHint.FILE);
            }
            if (annotated(component, PatternField.class)) {
                hints.add(FieldHint.PATTERN);
            }
            AllowedValues allowed = component == null ? null : component.getAnnotation(AllowedValues.class);
            if (allowed != null) {
                kind = FieldKind.ENUM;
                enumValues = List.of(allowed.value());
            } else if (type == Path.class || hints.contains(FieldHint.DIRECTORY) || hints.contains(FieldHint.FILE)) {
                kind = FieldKind.PATH;
            } else {
                kind = FieldKind.STRING;
            }
        } else if (type == boolean.class || type == Boolean.class) {
            kind = FieldKind.BOOLEAN;
        } else if (INTEGER_TYPES.contains(type)) {
            kind = FieldKind.INTEGER;
        } else if (DECIMAL_TYPES.contains(type)) {
            kind = FieldKind.DECIMAL;
        } else if (type.isEnum()) {
            kind = FieldKind.ENUM;
            enumValues = Arrays.stream(type.getEnumConstants()).map(constant -> ((Enum<?>) constant).name()).toList();
        } else if (type.isRecord()) {
            if (ancestors.contains(type)) {
                return Optional.empty(); // a cycle: left out
            }
            kind = FieldKind.RECORD;
            children = recordFields(type, path, ancestors);
        } else if (type == List.class || type == Set.class || type == Collection.class) {
            kind = FieldKind.LIST;
            Type elementType = genericType instanceof ParameterizedType parameterized
                    ? parameterized.getActualTypeArguments()[0]
                    : String.class;
            Class<?> elementClass = elementType instanceof Class<?> c ? c
                    : elementType instanceof ParameterizedType p && p.getRawType() instanceof Class<?> raw ? raw
                    : null;
            if (elementClass == null) {
                return Optional.empty(); // a wildcard or type variable element: left out
            }
            Optional<ConfigField> elementField = field(name, path, elementClass, elementType, null, ancestors);
            if (elementField.isEmpty()) {
                return Optional.empty();
            }
            element = elementField.get();
        } else {
            return Optional.empty(); // Map, JsonElement, Object...: left out
        }
        DefaultValue defaultValue = component == null ? null : component.getAnnotation(DefaultValue.class);
        return Optional.of(new ConfigField(name, path, kind, annotated(component, Required.class),
                defaultValue == null ? null : defaultValue.value(), hints,
                "config." + path + ".name", "config." + path + ".description",
                children, enumValues, element));
    }

    private static boolean annotated(RecordComponent component, Class<? extends Annotation> annotation) {
        return component != null && component.isAnnotationPresent(annotation);
    }

    private static List<String> patternVariables() {
        List<String> variables = new ArrayList<>(List.of("name", "size", "sizeHr"));
        for (String date : List.of("creation", WorkItemMetadata.LAST_MODIFIED, WorkItemMetadata.CAPTURE_DATE)) {
            for (String part : List.of("Y", "y", "m", "D")) {
                variables.add(date + "." + part);
            }
        }
        return List.copyOf(variables);
    }
}
```

Dans `copybot-engine/src/main/java/module-info.java` (LF, Edit), remplacer

```java
    exports com.copybot.plugin.api.definition;
```

par

```java
    exports com.copybot.plugin.api.definition;
    exports com.copybot.plugin.api.config;
```

- [ ] **Step 4: Run the tests**

Run: `mvn -o -q -pl copybot-engine test -Dtest=ConfigSchemaTest`
Expected: PASS (8 tests).

Run: `mvn -o -q -pl copybot-engine test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git ls-files --eol copybot-engine/src/main/java/module-info.java
git add copybot-engine/src/main/java/com/copybot/plugin/api/config copybot-engine/src/main/java/module-info.java copybot-engine/src/test/java/com/copybot/plugin/api/config/ConfigSchemaTest.java
git commit -m "Describe action configurations by introspecting their records" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

(`module-info.java` : `w/lf`.)

---

### Task 2: `IAction.configSchema()` et `AbstractActionWithConfig`

**Files:**
- Modify: `copybot-engine/src/main/java/com/copybot/plugin/api/action/IAction.java` (CRLF)
- Modify: `copybot-engine/src/main/java/com/copybot/plugin/api/action/AbstractActionWithConfig.java` (CRLF)
- Create: `copybot-engine/src/test/java/com/copybot/plugin/api/action/ConfigSchemaActionTest.java`

**Interfaces:**
- Consumes: `ConfigSchema.of` (tâche 1) ; `IAction.configWarnings()` (écriture sûre : ancre de l'Edit).
- Produces: `default Optional<ConfigSchema> IAction.configSchema()` (vide) ; `public Optional<ConfigSchema> AbstractActionWithConfig.configSchema()` = `ConfigSchema.of(getConfigClass())` quand la classe de configuration est un record, vide sinon ; appelable sur une instance neuve (aucune configuration chargée).

- [ ] **Step 1: Write the failing test**

`copybot-engine/src/test/java/com/copybot/plugin/api/action/ConfigSchemaActionTest.java` :

```java
package com.copybot.plugin.api.action;

import com.copybot.plugin.api.config.ConfigField;
import com.copybot.plugin.api.config.Required;
import com.copybot.plugin.api.definition.IPlugin;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Where an action's configuration schema comes from (spec desktop-ui §4). */
public class ConfigSchemaActionTest {

    record DemoConfig(@Required String target, Integer retries) {
    }

    static final class RecordConfigAction extends AbstractActionWithConfig<DemoConfig> implements IAnalyzeAction {
        @Override
        protected Class<DemoConfig> getConfigClass() {
            return DemoConfig.class;
        }

        @Override
        public void doAnalyze(WorkItem item) {
        }
    }

    /** A configuration that is a plain class: no introspection. */
    static final class PlainConfig {
        String target;
    }

    static final class PlainConfigAction extends AbstractActionWithConfig<PlainConfig> implements IAnalyzeAction {
        @Override
        protected Class<PlainConfig> getConfigClass() {
            return PlainConfig.class;
        }

        @Override
        public void doAnalyze(WorkItem item) {
        }
    }

    static final class BareAction implements IAnalyzeAction {
        @Override
        public void doAnalyze(WorkItem item) {
        }

        @Override
        public void setStatusWatcher(Consumer<WorkStatus> watcher) {
        }

        @Override
        public void setPlugin(IPlugin plugin) {
        }
    }

    @Test
    public void anActionWithARecordConfigurationDescribesItsFields() {
        List<ConfigField> fields = new RecordConfigAction().configSchema().orElseThrow().fields();

        assertEquals(List.of("target", "retries"), fields.stream().map(ConfigField::name).toList());
        assertTrue(fields.getFirst().required());
    }

    @Test
    public void theSchemaNeedsNoLoadedConfiguration() {
        RecordConfigAction action = new RecordConfigAction();

        assertTrue(action.configSchema().isPresent(), "the catalog asks a fresh instance");
        assertNull(action.getConfig());
    }

    @Test
    public void otherActionsDescribeNothing() {
        assertTrue(new PlainConfigAction().configSchema().isEmpty());
        assertTrue(new BareAction().configSchema().isEmpty());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -o -q -pl copybot-engine test -Dtest=ConfigSchemaActionTest`
Expected: FAIL — compilation `cannot find symbol: method configSchema()`.

- [ ] **Step 3: Implement**

Dans `IAction.java` (CRLF, Edit), remplacer

```java
import com.copybot.plugin.api.definition.IPlugin;
import com.google.gson.JsonElement;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
```

par

```java
import com.copybot.plugin.api.config.ConfigSchema;
import com.copybot.plugin.api.definition.IPlugin;
import com.google.gson.JsonElement;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
```

puis remplacer

```java
    default List<String> configWarnings() {
        return List.of();
    }
```

par

```java
    default List<String> configWarnings() {
        return List.of();
    }

    /**
     * The fields of this action's configuration, for the pipeline editor (desktop-ui spec, part 4). Empty by
     * default: the editor then keeps the configuration JSON as is. {@link AbstractActionWithConfig}
     * introspects its configuration record; override it when introspection is not enough.
     */
    default Optional<ConfigSchema> configSchema() {
        return Optional.empty();
    }
```

Dans `AbstractActionWithConfig.java` (CRLF, Edit), remplacer

```java
import com.google.gson.Gson;
import com.google.gson.JsonElement;

public abstract class AbstractActionWithConfig<C> extends AbstractAction {
```

par

```java
import com.copybot.plugin.api.config.ConfigSchema;
import com.google.gson.Gson;
import com.google.gson.JsonElement;

import java.util.Optional;

public abstract class AbstractActionWithConfig<C> extends AbstractAction {
```

puis remplacer

```java
    protected C getConfig() {
        return config;
    }
}
```

par

```java
    protected C getConfig() {
        return config;
    }

    /** Introspects the configuration class when it is a record (desktop-ui spec, part 4), empty otherwise. */
    @Override
    public Optional<ConfigSchema> configSchema() {
        Class<C> configClass = getConfigClass();
        return configClass.isRecord()
                ? Optional.of(ConfigSchema.of(configClass.asSubclass(Record.class)))
                : Optional.empty();
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `mvn -o -q -pl copybot-engine test`
Expected: PASS (dont `ConfigSchemaActionTest` : 3 tests).

- [ ] **Step 5: Commit**

```bash
git ls-files --eol copybot-engine/src/main/java/com/copybot/plugin/api/action/IAction.java copybot-engine/src/main/java/com/copybot/plugin/api/action/AbstractActionWithConfig.java
git add copybot-engine/src/main/java/com/copybot/plugin/api/action/IAction.java copybot-engine/src/main/java/com/copybot/plugin/api/action/AbstractActionWithConfig.java copybot-engine/src/test/java/com/copybot/plugin/api/action/ConfigSchemaActionTest.java
git commit -m "Let actions describe their configuration schema" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

(Les deux : `w/crlf`.)

---

### Task 3: Configurations embarquées annotées et leurs libellés EN / FR

**Files:**
- Modify (réécriture): `copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileWriteConfig.java` (CRLF)
- Modify (réécriture): `copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileReadConfig.java` (CRLF)
- Modify (append-only): `copybot-engine/src/main/resources/com/copybot/plugin/embedded/i18n/pluginBundle.properties` (ASCII CRLF, sans fin de ligne finale)
- Modify (append-only): `copybot-engine/src/main/resources/com/copybot/plugin/embedded/i18n/pluginBundle_fr.properties` (ISO-8859-1 CRLF, sans fin de ligne finale)
- Create: `copybot-engine/src/test/java/com/copybot/plugin/embedded/actions/EmbeddedConfigSchemaTest.java`

**Interfaces:**
- Consumes: annotations (tâche 1), `configSchema()` (tâche 2), `FileWriteSettings` et ses enums (écriture sûre).
- Produces: les mêmes records (composants, ordre, types et méthodes inchangés), annotés : `outPattern` `@Required @PatternField` ; `writeMode` `@AllowedValues({"tempAndRename","direct"}) @DefaultValue("tempAndRename")` ; `verify` `{"none","size","readBack"}` / `"size"` ; `deleteSource` `"false"` ; `OnConflict.compare` `{"size","sizeAndDate","partialHash","fullHash"}` / `"partialHash"`, `ifIdentical` / `ifDifferent` `{"skip","rename","overwrite","error"}` / `"skip"` / `"rename"` ; `path` `@Required @DirectoryPath` ; `recursive` `"true"` ; `includeHidden` `"false"`. Clés `plugin.embedded.file.read.description`, `plugin.embedded.file.write.name`, `plugin.embedded.file.write.description` et `plugin.embedded.file.<read|write>.config.<chemin>.name|.description` pour chaque champ (31 lignes par bundle).

- [ ] **Step 1: Write the failing test**

`copybot-engine/src/test/java/com/copybot/plugin/embedded/actions/EmbeddedConfigSchemaTest.java` :

```java
package com.copybot.plugin.embedded.actions;

import com.copybot.plugin.api.config.ConfigField;
import com.copybot.plugin.api.config.ConfigSchema;
import com.copybot.plugin.api.config.FieldHint;
import com.copybot.plugin.api.config.FieldKind;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/** The schemas of the embedded actions and their labels (spec desktop-ui §4). */
public class EmbeddedConfigSchemaTest {

    private static ConfigSchema writeSchema() {
        return new FileWriteAction().configSchema().orElseThrow().withKeyPrefix("plugin.embedded.file.write");
    }

    private static ConfigSchema readSchema() {
        return new FileReadAction().configSchema().orElseThrow().withKeyPrefix("plugin.embedded.file.read");
    }

    private static ConfigField field(ConfigSchema schema, String path) {
        return schema.field(path).orElseThrow(() -> new AssertionError("no field " + path));
    }

    private static List<String> jsonNames(FileWriteSettings.Named[] values) {
        return Arrays.stream(values).map(FileWriteSettings.Named::jsonName).toList();
    }

    @Test
    public void fileReadDescribesItsFilters() {
        ConfigSchema schema = readSchema();

        ConfigField path = field(schema, "path");
        assertEquals(FieldKind.PATH, path.kind());
        assertTrue(path.required());
        assertTrue(path.hasHint(FieldHint.DIRECTORY));
        assertEquals(FieldKind.BOOLEAN, field(schema, "recursive").kind());
        assertEquals("true", field(schema, "recursive").defaultValue());
        assertEquals(FieldKind.LIST, field(schema, "include").kind());
        assertEquals(FieldKind.STRING, field(schema, "include").elementSchema().kind());
        assertEquals(FieldKind.LIST, field(schema, "exclude").kind());
        assertEquals("false", field(schema, "includeHidden").defaultValue());
    }

    @Test
    public void fileWriteOffersTheValuesItAccepts() {
        ConfigSchema schema = writeSchema();

        assertTrue(field(schema, "outPattern").required());
        assertTrue(field(schema, "outPattern").hasHint(FieldHint.PATTERN));
        assertEquals(FieldKind.RECORD, field(schema, "onConflict").kind());
        assertEquals(jsonNames(FileWriteSettings.Compare.values()), field(schema, "onConflict.compare").enumValues());
        assertEquals(jsonNames(FileWriteSettings.Policy.values()), field(schema, "onConflict.ifIdentical").enumValues());
        assertEquals(jsonNames(FileWriteSettings.Policy.values()), field(schema, "onConflict.ifDifferent").enumValues());
        assertEquals(jsonNames(FileWriteSettings.WriteMode.values()), field(schema, "writeMode").enumValues());
        assertEquals(jsonNames(FileWriteSettings.Verify.values()), field(schema, "verify").enumValues());
        assertEquals(FieldKind.BOOLEAN, field(schema, "overwrite").kind());
    }

    @Test
    public void theDefaultsShownAreTheOnesApplied() {
        ConfigSchema schema = writeSchema();
        FileWriteSettings defaults = FileWriteSettings.of(new Gson().fromJson("{\"outPattern\":\"x\"}", FileWriteConfig.class));

        assertEquals(defaults.compare().jsonName(), field(schema, "onConflict.compare").defaultValue());
        assertEquals(defaults.ifIdentical().jsonName(), field(schema, "onConflict.ifIdentical").defaultValue());
        assertEquals(defaults.ifDifferent().jsonName(), field(schema, "onConflict.ifDifferent").defaultValue());
        assertEquals(defaults.writeMode().jsonName(), field(schema, "writeMode").defaultValue());
        assertEquals(defaults.verify().jsonName(), field(schema, "verify").defaultValue());
        assertEquals(String.valueOf(defaults.deleteSource()), field(schema, "deleteSource").defaultValue());
    }

    /** Properties.load(InputStream) reads ISO-8859-1 and the backslash-u escapes, like ResourceBundle. */
    private static Properties bundle(String name) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = EmbeddedConfigSchemaTest.class.getResourceAsStream("/com/copybot/plugin/embedded/i18n/" + name)) {
            assertNotNull(in, name);
            properties.load(in);
        }
        return properties;
    }

    @Test
    public void everyFieldAndActionHasALabelAndADescriptionInBothBundles() throws IOException {
        for (String file : List.of("pluginBundle.properties", "pluginBundle_fr.properties")) {
            Properties properties = bundle(file);
            for (ConfigSchema schema : List.of(readSchema(), writeSchema())) {
                for (ConfigField field : schema.allFields()) {
                    assertNotNull(properties.getProperty(field.labelKey()), field.labelKey() + " in " + file);
                    assertNotNull(properties.getProperty(field.descriptionKey()), field.descriptionKey() + " in " + file);
                }
            }
            for (String action : List.of("file.read", "file.write")) {
                for (String suffix : List.of(".name", ".description")) {
                    String key = "plugin.embedded." + action + suffix;
                    assertNotNull(properties.getProperty(key), key + " in " + file);
                    assertFalse(properties.getProperty(key).contains("�"), key + " in " + file + " was re-encoded");
                }
            }
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -o -q -pl copybot-engine test -Dtest=EmbeddedConfigSchemaTest`
Expected: FAIL — `Tests run: 4, Failures: 4` (le test compile : le schéma existe déjà, sans annotations) : `fileReadDescribesItsFilters … expected: <PATH> but was: <STRING>`, `fileWriteOffersTheValuesItAccepts … expected: <true> but was: <false>` (`outPattern` non requis), `theDefaultsShownAreTheOnesApplied … expected: <partialHash> but was: <null>`, `everyFieldAndActionHasALabelAndADescriptionInBothBundles … plugin.embedded.file.read.config.path.name in pluginBundle.properties ==> expected: not <null>`.

- [ ] **Step 3: Annotate the configuration records**

Réécrire `FileWriteConfig.java` en entier (Write), puis `unix2dos -q copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileWriteConfig.java` :

```java
package com.copybot.plugin.embedded.actions;

import com.copybot.plugin.api.config.AllowedValues;
import com.copybot.plugin.api.config.DefaultValue;
import com.copybot.plugin.api.config.PatternField;
import com.copybot.plugin.api.config.Required;

/**
 * The "actionConfig" of file.write, as written in the pipeline (spec safe-write §1). Every field but
 * outPattern is optional: {@link FileWriteSettings#of} validates the values and applies the defaults.
 * The annotations describe the fields to the pipeline editor (spec desktop-ui §4); the allowed values
 * and defaults are the ones of {@link FileWriteSettings}.
 *
 * @param overwrite    legacy: true means onConflict.ifDifferent "overwrite", false means "error"
 *                     (ifIdentical "skip" in both cases); refused together with onConflict
 * @param onConflict   what to do when the target already exists
 * @param writeMode    "tempAndRename" (default) or "direct" (with "overwrite", the original is lost as soon
 *                     as the write starts)
 * @param verify       "none", "size" (default) or "readBack"
 * @param deleteSource delete the source once written and verified (default false)
 */
public record FileWriteConfig(
        @Required @PatternField
        String outPattern,

        Boolean overwrite,

        OnConflict onConflict,

        @AllowedValues({"tempAndRename", "direct"}) @DefaultValue("tempAndRename")
        String writeMode,

        @AllowedValues({"none", "size", "readBack"}) @DefaultValue("size")
        String verify,

        @DefaultValue("false")
        Boolean deleteSource
) {

    /**
     * @param compare     "size", "sizeAndDate", "partialHash" (default) or "fullHash"
     * @param ifIdentical "skip" (default), "rename", "overwrite" or "error"
     * @param ifDifferent "skip", "rename" (default), "overwrite" or "error"
     */
    public record OnConflict(
            @AllowedValues({"size", "sizeAndDate", "partialHash", "fullHash"}) @DefaultValue("partialHash")
            String compare,

            @AllowedValues({"skip", "rename", "overwrite", "error"}) @DefaultValue("skip")
            String ifIdentical,

            @AllowedValues({"skip", "rename", "overwrite", "error"}) @DefaultValue("rename")
            String ifDifferent
    ) {
    }
}
```

Réécrire `FileReadConfig.java` en entier (Write), puis `unix2dos -q copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileReadConfig.java` :

```java
package com.copybot.plugin.embedded.actions;

import com.copybot.plugin.api.config.DefaultValue;
import com.copybot.plugin.api.config.DirectoryPath;
import com.copybot.plugin.api.config.Required;

import java.util.List;

/**
 * The "actionConfig" of file.read (spec safe-write §7). The annotations describe the fields to the
 * pipeline editor (spec desktop-ui §4).
 *
 * @param recursive     the whole tree (default), or the first level only when false
 * @param include       globs relative to path, case-insensitive; absent or empty: every file
 * @param exclude       globs relative to path, case-insensitive; they win over include
 * @param includeHidden hidden files and directories are skipped unless true
 */
public record FileReadConfig(
        @Required @DirectoryPath
        String path,

        @DefaultValue("true")
        Boolean recursive,

        List<String> include,

        List<String> exclude,

        @DefaultValue("false")
        Boolean includeHidden
) {

    public boolean isRecursive() {
        return recursive == null || recursive;
    }

    public boolean isIncludeHidden() {
        return Boolean.TRUE.equals(includeHidden);
    }
}
```

- [ ] **Step 4: Append the labels (script Bash, depuis la racine du repo)**

Écrire ce bloc tel quel avec l'outil Write dans `<scratchpad>/plugin-labels.sh`, puis `bash <scratchpad>/plugin-labels.sh` depuis la racine du repo (une seule fois : il ajoute) :

```bash
EN=copybot-engine/src/main/resources/com/copybot/plugin/embedded/i18n/pluginBundle.properties
FR=copybot-engine/src/main/resources/com/copybot/plugin/embedded/i18n/pluginBundle_fr.properties
printf '\r\n' >> $EN
printf '%s\r\n' 'plugin.embedded.file.read.description=Lists the files of a source directory, with optional filters' >> $EN
printf '%s\r\n' 'plugin.embedded.file.write.name=Write files to a destination' >> $EN
printf '%s\r\n' 'plugin.embedded.file.write.description=Copies each file, safely, to the path given by the output pattern' >> $EN
printf '%s\r\n' 'plugin.embedded.file.read.config.path.name=Source directory' >> $EN
printf '%s\r\n' 'plugin.embedded.file.read.config.path.description=The directory to list (e.g. the memory card)' >> $EN
printf '%s\r\n' 'plugin.embedded.file.read.config.recursive.name=Include sub-directories' >> $EN
printf '%s\r\n' 'plugin.embedded.file.read.config.recursive.description=List every level (default), or the first one only' >> $EN
printf '%s\r\n' 'plugin.embedded.file.read.config.include.name=Include' >> $EN
printf '%s\r\n' 'plugin.embedded.file.read.config.include.description=Patterns relative to the source directory, one per line, case-insensitive (e.g. **/*.NEF); empty: every file' >> $EN
printf '%s\r\n' 'plugin.embedded.file.read.config.exclude.name=Exclude' >> $EN
printf '%s\r\n' 'plugin.embedded.file.read.config.exclude.description=Patterns relative to the source directory, one per line; they win over Include' >> $EN
printf '%s\r\n' 'plugin.embedded.file.read.config.includeHidden.name=Include hidden files' >> $EN
printf '%s\r\n' 'plugin.embedded.file.read.config.includeHidden.description=Hidden files and directories are skipped unless checked' >> $EN
printf '%s\r\n' 'plugin.embedded.file.write.config.outPattern.name=Output pattern' >> $EN
printf '%s\r\n' 'plugin.embedded.file.write.config.outPattern.description=Target path of each file, with variables such as {name} or {captureDate.Y}' >> $EN
printf '%s\r\n' 'plugin.embedded.file.write.config.overwrite.name=Overwrite (legacy)' >> $EN
printf '%s\r\n' 'plugin.embedded.file.write.config.overwrite.description=Legacy option replaced by On conflict: never set both' >> $EN
printf '%s\r\n' 'plugin.embedded.file.write.config.onConflict.name=On conflict' >> $EN
printf '%s\r\n' 'plugin.embedded.file.write.config.onConflict.description=What to do when the target already exists' >> $EN
printf '%s\r\n' 'plugin.embedded.file.write.config.onConflict.compare.name=Comparison' >> $EN
printf '%s\r\n' 'plugin.embedded.file.write.config.onConflict.compare.description=How an existing target is recognised as identical' >> $EN
printf '%s\r\n' 'plugin.embedded.file.write.config.onConflict.ifIdentical.name=If identical' >> $EN
printf '%s\r\n' 'plugin.embedded.file.write.config.onConflict.ifIdentical.description=What to do when the target is identical to the file' >> $EN
printf '%s\r\n' 'plugin.embedded.file.write.config.onConflict.ifDifferent.name=If different' >> $EN
printf '%s\r\n' 'plugin.embedded.file.write.config.onConflict.ifDifferent.description=What to do when the target differs from the file' >> $EN
printf '%s\r\n' 'plugin.embedded.file.write.config.writeMode.name=Write mode' >> $EN
printf '%s\r\n' 'plugin.embedded.file.write.config.writeMode.description=tempAndRename writes a hidden temporary file then moves it; direct writes under the final name' >> $EN
printf '%s\r\n' 'plugin.embedded.file.write.config.verify.name=Verification' >> $EN
printf '%s\r\n' 'plugin.embedded.file.write.config.verify.description=What is checked once written: none, size, or readBack (the whole content)' >> $EN
printf '%s\r\n' 'plugin.embedded.file.write.config.deleteSource.name=Delete the source' >> $EN
printf '%s\r\n' 'plugin.embedded.file.write.config.deleteSource.description=Deletes each source file once written and verified' >> $EN
printf '\r\n' >> $FR
printf 'plugin.embedded.file.read.description=Liste les fichiers du r\134u00e9pertoire source, avec des filtres optionnels\r\n' >> $FR
printf 'plugin.embedded.file.write.name=\134u00c9crit les fichiers vers une destination\r\n' >> $FR
printf 'plugin.embedded.file.write.description=Copie chaque fichier, de fa\134u00e7on s\134u00fbre, vers le chemin donn\134u00e9 par le pattern de sortie\r\n' >> $FR
printf 'plugin.embedded.file.read.config.path.name=R\134u00e9pertoire source\r\n' >> $FR
printf 'plugin.embedded.file.read.config.path.description=Le r\134u00e9pertoire \134u00e0 lister (par exemple la carte m\134u00e9moire)\r\n' >> $FR
printf 'plugin.embedded.file.read.config.recursive.name=Inclure les sous-r\134u00e9pertoires\r\n' >> $FR
printf 'plugin.embedded.file.read.config.recursive.description=Lister tous les niveaux (par d\134u00e9faut), ou seulement le premier\r\n' >> $FR
printf 'plugin.embedded.file.read.config.include.name=Inclure\r\n' >> $FR
printf 'plugin.embedded.file.read.config.include.description=Motifs relatifs au r\134u00e9pertoire source, un par ligne, sans tenir compte de la casse (ex. **/*.NEF) ; vide : tous les fichiers\r\n' >> $FR
printf 'plugin.embedded.file.read.config.exclude.name=Exclure\r\n' >> $FR
printf 'plugin.embedded.file.read.config.exclude.description=Motifs relatifs au r\134u00e9pertoire source, un par ligne ; prioritaires sur Inclure\r\n' >> $FR
printf 'plugin.embedded.file.read.config.includeHidden.name=Inclure les fichiers cach\134u00e9s\r\n' >> $FR
printf 'plugin.embedded.file.read.config.includeHidden.description=Les fichiers et r\134u00e9pertoires cach\134u00e9s sont ignor\134u00e9s sauf si coch\134u00e9\r\n' >> $FR
printf 'plugin.embedded.file.write.config.outPattern.name=Pattern de sortie\r\n' >> $FR
printf 'plugin.embedded.file.write.config.outPattern.description=Chemin cible de chaque fichier, avec des variables comme {name} ou {captureDate.Y}\r\n' >> $FR
printf 'plugin.embedded.file.write.config.overwrite.name=\134u00c9craser (ancienne option)\r\n' >> $FR
printf 'plugin.embedded.file.write.config.overwrite.description=Ancienne option remplac\134u00e9e par En cas de conflit : ne jamais utiliser les deux\r\n' >> $FR
printf 'plugin.embedded.file.write.config.onConflict.name=En cas de conflit\r\n' >> $FR
printf 'plugin.embedded.file.write.config.onConflict.description=Que faire quand la cible existe d\134u00e9j\134u00e0\r\n' >> $FR
printf 'plugin.embedded.file.write.config.onConflict.compare.name=Comparaison\r\n' >> $FR
printf 'plugin.embedded.file.write.config.onConflict.compare.description=Comment reconna\134u00eetre une cible identique\r\n' >> $FR
printf 'plugin.embedded.file.write.config.onConflict.ifIdentical.name=Si identique\r\n' >> $FR
printf 'plugin.embedded.file.write.config.onConflict.ifIdentical.description=Que faire quand la cible est identique au fichier\r\n' >> $FR
printf 'plugin.embedded.file.write.config.onConflict.ifDifferent.name=Si diff\134u00e9rent\r\n' >> $FR
printf 'plugin.embedded.file.write.config.onConflict.ifDifferent.description=Que faire quand la cible diff\134u00e8re du fichier\r\n' >> $FR
printf 'plugin.embedded.file.write.config.writeMode.name=Mode de copie\r\n' >> $FR
printf 'plugin.embedded.file.write.config.writeMode.description=tempAndRename \134u00e9crit un temporaire cach\134u00e9 puis le d\134u00e9place ; direct \134u00e9crit sous le nom final\r\n' >> $FR
printf 'plugin.embedded.file.write.config.verify.name=V\134u00e9rification\r\n' >> $FR
printf 'plugin.embedded.file.write.config.verify.description=Ce qui est contr\134u00f4l\134u00e9 apr\134u00e8s \134u00e9criture : none, size ou readBack (tout le contenu)\r\n' >> $FR
printf 'plugin.embedded.file.write.config.deleteSource.name=Supprimer la source\r\n' >> $FR
printf 'plugin.embedded.file.write.config.deleteSource.description=Supprime chaque fichier source une fois \134u00e9crit et v\134u00e9rifi\134u00e9\r\n' >> $FR
```

Contrôles :

```bash
EN=copybot-engine/src/main/resources/com/copybot/plugin/embedded/i18n/pluginBundle.properties
FR=copybot-engine/src/main/resources/com/copybot/plugin/embedded/i18n/pluginBundle_fr.properties
file $EN $FR
grep -c $'\xc3' $FR
grep -c $'\xef\xbf\xbd' $FR
git diff $FR | cat -A | sed -n 5,12p
```

Expected : `ASCII text, with CRLF line terminators` / `ISO-8859 text, with CRLF line terminators` ; `0` ; `0` ; l'ancienne dernière ligne `-plugin.embedded.file.write.error.io=Impossible d''M-icrire le fichier "{0}"$` / `\ No newline at end of file` remplacée par la même terminée par `^M$`, puis des lignes `+…^M$` avec des `é` littéraux (ex. `+plugin.embedded.file.read.description=Liste les fichiers du répertoire source, avec des filtres optionnels^M$`) ; `git diff --stat` : `32 insertions(+), 1 deletion(-)` par bundle.

- [ ] **Step 5: Run the tests**

Run: `mvn -o -q -pl copybot-engine test`
Expected: PASS (dont `EmbeddedConfigSchemaTest` : 4 tests ; `FileWriteSettingsTest`, `FileReadActionTest` et les tests CLI inchangés : les records gardent leurs composants).

- [ ] **Step 6: Commit**

```bash
git ls-files --eol copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileWriteConfig.java copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileReadConfig.java copybot-engine/src/main/resources/com/copybot/plugin/embedded/i18n/pluginBundle.properties copybot-engine/src/main/resources/com/copybot/plugin/embedded/i18n/pluginBundle_fr.properties
git add copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileWriteConfig.java copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileReadConfig.java copybot-engine/src/main/resources/com/copybot/plugin/embedded/i18n/pluginBundle.properties copybot-engine/src/main/resources/com/copybot/plugin/embedded/i18n/pluginBundle_fr.properties copybot-engine/src/test/java/com/copybot/plugin/embedded/actions/EmbeddedConfigSchemaTest.java
git commit -m "Annotate the embedded configurations and label their fields" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

(Les quatre : `w/crlf`.)

---

### Task 4: Catalogue des actions chargées

**Files:**
- Create: `copybot-engine/src/main/java/com/copybot/engine/plugin/CatalogAction.java`
- Modify: `copybot-engine/src/main/java/com/copybot/engine/plugin/PluginEngine.java` (CRLF)
- Modify: `copybot-engine/src/main/java/module-info.java` (LF)
- Create: `copybot-engine/src/test/java/com/copybot/engine/plugin/PluginCatalogTest.java`

**Interfaces:**
- Consumes: `IAction.configSchema()` (2), libellés embarqués (3), `IPlugin.getResourceBundle()` / `getPluginCode()`, `PluginDefinition` (existants).
- Produces: `public record CatalogAction(String pluginName, String pluginCode, String pluginVersion, String actionCode, StepType stepType, String name, String description, ConfigSchema schema, Map<String, String> texts)` avec `static final String EMBEDDED_PLUGIN = "embedded"`, `boolean isEmbedded()`, `Optional<ConfigSchema> configSchema()`, `String label(ConfigField)` (nom du champ sans libellé), `String description(ConfigField)` (vide sans description) ; `public static List<CatalogAction> PluginEngine.catalog()` ; `exports com.copybot.engine.plugin to com.copybot.ui`.

- [ ] **Step 1: Write the failing test**

`copybot-engine/src/test/java/com/copybot/engine/plugin/PluginCatalogTest.java` :

```java
package com.copybot.engine.plugin;

import com.copybot.engine.pipeline.StepType;
import com.copybot.plugin.api.config.ConfigField;
import com.copybot.plugin.api.config.ConfigSchema;
import com.copybot.plugin.api.config.FieldKind;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The catalog of the loaded actions (spec desktop-ui §4). PluginEngine.load runs once per JVM: whichever
 * load came first, the embedded plugin is loaded.
 */
public class PluginCatalogTest {

    @BeforeAll
    public static void loadPlugins(@TempDir Path tempDir) throws IOException {
        PluginEngine.load(Files.createDirectories(tempDir.resolve("plugins")), List.of());
    }

    private static CatalogAction embedded(String actionCode) {
        return PluginEngine.catalog().stream()
                .filter(a -> a.isEmbedded() && a.actionCode().equals(actionCode))
                .findFirst().orElseThrow(() -> new AssertionError("no embedded " + actionCode));
    }

    @Test
    public void theEmbeddedActionsAreListedUnderTheirStepType() {
        CatalogAction read = embedded("file.read");
        CatalogAction write = embedded("file.write");

        assertEquals(StepType.IN, read.stepType());
        assertEquals(StepType.OUT, write.stepType());
        assertEquals("embedded", read.pluginName());
        assertEquals("embedded", read.pluginCode());
        assertNull(read.pluginVersion(), "the embedded plugin has no version");
    }

    @Test
    public void namesAndDescriptionsAreLocalized() {
        for (CatalogAction action : List.of(embedded("file.read"), embedded("file.write"))) {
            assertNotEquals(action.actionCode(), action.name(), "a translated name");
            assertFalse(action.name().startsWith("%"), action.name());
            assertFalse(action.description().isEmpty(), action.actionCode());
        }
    }

    @Test
    public void theSchemaKeysArePrefixedAndTheirTextsResolved() {
        CatalogAction write = embedded("file.write");
        ConfigSchema schema = write.configSchema().orElseThrow();
        ConfigField compare = schema.field("onConflict.compare").orElseThrow();

        assertEquals("plugin.embedded.file.write.config.onConflict.compare.name", compare.labelKey());
        assertNotEquals("compare", write.label(compare), "a translated label");
        assertFalse(write.description(compare).isEmpty());
        for (ConfigField field : schema.allFields()) {
            assertTrue(write.texts().containsKey(field.labelKey()), field.labelKey());
        }
    }

    @Test
    public void aFieldWithoutLabelShowsItsName() {
        CatalogAction write = embedded("file.write");
        ConfigField unknown = new ConfigField("mystery", "mystery", FieldKind.STRING,
                false, null, Set.of(), "plugin.embedded.file.write.config.mystery.name",
                "plugin.embedded.file.write.config.mystery.description", List.of(), List.of(), null);

        assertEquals("mystery", write.label(unknown));
        assertEquals("", write.description(unknown));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -o -q -pl copybot-engine test -Dtest=PluginCatalogTest`
Expected: FAIL — compilation `cannot find symbol: class CatalogAction`.

- [ ] **Step 3: Implement**

`copybot-engine/src/main/java/com/copybot/engine/plugin/CatalogAction.java` :

```java
package com.copybot.engine.plugin;

import com.copybot.engine.pipeline.StepType;
import com.copybot.plugin.api.config.ConfigField;
import com.copybot.plugin.api.config.ConfigSchema;
import com.copybot.plugin.embedded.CBEmbeddedPlugin;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One action of a loaded plugin, as offered by the pipeline editor (spec desktop-ui §4), texts resolved in
 * the language of the call to {@link PluginEngine#catalog()}.
 *
 * @param pluginName    what the "plugin" of a pipeline step names ("embedded" for the embedded actions,
 *                      which a step may leave out)
 * @param pluginCode    the plugin code of the i18n keys ("plugin.&lt;code&gt;.&lt;action&gt;.name")
 * @param pluginVersion null for the embedded plugin
 * @param name          the localized name, the action code when the plugin has none
 * @param description   the localized description, empty when the plugin has none
 * @param schema        the configuration schema, keys prefixed with "plugin.&lt;code&gt;.&lt;action&gt;";
 *                      null when the action describes none
 * @param texts         the localized label and description of every schema field that has one, by key
 */
public record CatalogAction(
        String pluginName,
        String pluginCode,
        String pluginVersion,
        String actionCode,
        StepType stepType,
        String name,
        String description,
        ConfigSchema schema,
        Map<String, String> texts) {

    /** The plugin name of the embedded actions: a pipeline step may leave its "plugin" out. */
    public static final String EMBEDDED_PLUGIN = CBEmbeddedPlugin.EMBEDDED_PLUGN_NAME;

    public CatalogAction {
        Objects.requireNonNull(pluginName, "pluginName");
        Objects.requireNonNull(actionCode, "actionCode");
        Objects.requireNonNull(stepType, "stepType");
        texts = Map.copyOf(texts);
    }

    public boolean isEmbedded() {
        return EMBEDDED_PLUGIN.equals(pluginName);
    }

    public Optional<ConfigSchema> configSchema() {
        return Optional.ofNullable(schema);
    }

    /** The localized label of the field, its name when the plugin has none (spec desktop-ui §4). */
    public String label(ConfigField field) {
        return texts.getOrDefault(field.labelKey(), field.name());
    }

    /** The localized description of the field, empty when the plugin has none. */
    public String description(ConfigField field) {
        return texts.getOrDefault(field.descriptionKey(), "");
    }
}
```

Dans `PluginEngine.java` (CRLF, Edit), remplacer

```java
import com.copybot.plugin.api.action.IAction;
import com.copybot.plugin.embedded.CBEmbeddedPlugin;
import com.copybot.utils.FileUtil;
import com.copybot.utils.VersionUtil;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
```

par

```java
import com.copybot.plugin.api.action.ActionDefinition;
import com.copybot.plugin.api.action.IAction;
import com.copybot.plugin.api.config.ConfigField;
import com.copybot.plugin.api.config.ConfigSchema;
import com.copybot.plugin.api.definition.IPlugin;
import com.copybot.plugin.embedded.CBEmbeddedPlugin;
import com.copybot.resources.CombinedResourceBundle;
import com.copybot.utils.FileUtil;
import com.copybot.utils.VersionUtil;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
```

puis remplacer

```java
    public static List<PluginDefinition> getErrorPlugins() {
        return errorPlugins;
    }
```

par

```java
    public static List<PluginDefinition> getErrorPlugins() {
        return errorPlugins;
    }

    /**
     * Every action of the loaded plugins (desktop-ui spec, part 4), in plugin order (name, then most recent
     * version first) then step type (IN, ANALYZE, PROCESS, OUT), texts in the current language. Empty
     * before {@link #load}.
     */
    public static List<CatalogAction> catalog() {
        List<CatalogAction> actions = new ArrayList<>();
        for (PluginDefinition plugin : loadedPlugins) {
            IPlugin instance = plugin.getPluginInstance();
            addActions(actions, plugin, StepType.IN, instance.getInActions());
            addActions(actions, plugin, StepType.ANALYZE, instance.getAnalyzeActions());
            addActions(actions, plugin, StepType.PROCESS, instance.getProcessActions());
            addActions(actions, plugin, StepType.OUT, instance.getOutActions());
        }
        return List.copyOf(actions);
    }

    private static void addActions(List<CatalogAction> actions, PluginDefinition plugin, StepType type,
                                   List<? extends ActionDefinition<?>> definitions) {
        IPlugin instance = plugin.getPluginInstance();
        CombinedResourceBundle bundle = instance.getResourceBundle();
        for (ActionDefinition<?> definition : definitions) {
            String keyPrefix = "plugin." + instance.getPluginCode() + "." + definition.actionCode();
            ConfigSchema schema = schemaOf(definition).map(s -> s.withKeyPrefix(keyPrefix)).orElse(null);
            Map<String, String> texts = new HashMap<>();
            if (schema != null) {
                for (ConfigField field : schema.allFields()) {
                    text(bundle, field.labelKey()).ifPresent(t -> texts.put(field.labelKey(), t));
                    text(bundle, field.descriptionKey()).ifPresent(t -> texts.put(field.descriptionKey(), t));
                }
            }
            actions.add(new CatalogAction(plugin.getName(), instance.getPluginCode(), plugin.getVersion(),
                    definition.actionCode(), type,
                    text(bundle, keyPrefix + ".name").orElse(definition.actionCode()),
                    text(bundle, keyPrefix + ".description").orElse(""),
                    schema, texts));
        }
    }

    /** The schema of a fresh instance; an action that cannot be instantiated describes nothing. */
    private static Optional<ConfigSchema> schemaOf(ActionDefinition<?> definition) {
        try {
            return definition.getInstance().configSchema();
        } catch (RuntimeException e) {
            return Optional.empty(); // the step resolution will report the failure, with its context
        }
    }

    /** The text of the key in the plugin bundle (which falls back on the engine one), empty when absent. */
    private static Optional<String> text(CombinedResourceBundle bundle, String key) {
        if (bundle == null) {
            return Optional.empty();
        }
        String value = bundle.getString(key);
        return value.startsWith("%") ? Optional.empty() : Optional.of(value); // "%key": no such key
    }
```

Dans `module-info.java` (LF, Edit), remplacer

```java
    exports com.copybot.engine.resources to com.copybot.ui;
```

par

```java
    exports com.copybot.engine.resources to com.copybot.ui;
    exports com.copybot.engine.plugin to com.copybot.ui;
```

- [ ] **Step 4: Run the tests**

Run: `mvn -o -q -pl copybot-engine test`
Expected: PASS (dont `PluginCatalogTest` : 4 tests ; une ligne `AVERTISSEMENT: Plugins déjà chargés…` sur stderr quand une autre classe de test a chargé les plugins avant : attendu).

- [ ] **Step 5: Commit**

```bash
git ls-files --eol copybot-engine/src/main/java/com/copybot/engine/plugin/PluginEngine.java copybot-engine/src/main/java/module-info.java
git add copybot-engine/src/main/java/com/copybot/engine/plugin/CatalogAction.java copybot-engine/src/main/java/com/copybot/engine/plugin/PluginEngine.java copybot-engine/src/main/java/module-info.java copybot-engine/src/test/java/com/copybot/engine/plugin/PluginCatalogTest.java
git commit -m "List the loaded actions with their localized texts and schema" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

(`PluginEngine.java` : `w/crlf` ; `module-info.java` : `w/lf`.)

---

### Task 5: `FileUtil.toAutoUnitSize` — chaque unité divise par sa puissance

**Files:**
- Modify: `copybot-engine/src/main/java/com/copybot/utils/FileUtil.java` (CRLF)
- Create: `copybot-engine/src/test/java/com/copybot/utils/FileUtilTest.java`

**Interfaces:**
- Consumes: clés `size.b` / `size.kb` / `size.mb` / `size.gb` (existantes).
- Produces: `toAutoUnitSize(long, int)` inchangée en signature : `1536` ⇒ `1.5 <Ko>`, `3 Gio` ⇒ `3.0 <Go>` (au lieu de `0.1` et `3145728.0`). Utilisée par la colonne Taille (`WorkItemMetadata.getSizeHr`) et le bouton « Copier N fichiers (X Go) ».

- [ ] **Step 1: Write the failing test**

`copybot-engine/src/test/java/com/copybot/utils/FileUtilTest.java` :

```java
package com.copybot.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Human-readable sizes: the number before the (translated) unit, rounded up (spec desktop-ui §2, §5). */
public class FileUtilTest {

    private static String number(long size, int decimals) {
        String text = FileUtil.toAutoUnitSize(size, decimals);
        return text.substring(0, text.indexOf(' '));
    }

    @Test
    public void eachUnitDividesByItsOwnPower() {
        assertEquals("999", number(999, 1));
        assertEquals("1.5", number(1536, 1));
        assertEquals("5.5", number(5 * FileUtil.ONE_MB + FileUtil.ONE_MB / 2, 1));
        assertEquals("3.0", number(3 * FileUtil.ONE_GB, 1));
        assertEquals("2.25", number(2 * FileUtil.ONE_GB + FileUtil.ONE_GB / 4, 2));
    }

    @Test
    public void theUnitsDiffer() {
        String bytes = FileUtil.toAutoUnitSize(999, 1);
        String kilo = FileUtil.toAutoUnitSize(1536, 1);
        String giga = FileUtil.toAutoUnitSize(3 * FileUtil.ONE_GB, 1);

        assertNotEquals(bytes.substring(bytes.indexOf(' ')), kilo.substring(kilo.indexOf(' ')));
        assertNotEquals(kilo.substring(kilo.indexOf(' ')), giga.substring(giga.indexOf(' ')));
        assertFalse(giga.contains("%"), giga);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -o -q -pl copybot-engine test -Dtest=FileUtilTest`
Expected: FAIL — `eachUnitDividesByItsOwnPower:18 expected: <1.5> but was: <0.1>`.

- [ ] **Step 3: Implement**

Dans `FileUtil.java` (CRLF, Edit), remplacer

```java
            displaySize = sizeBd.divide(ONE_KB_BD, decimals, BigDecimal.ROUND_CEILING) + " " + ResourcesEngine.getResourceBundle().getString("size.gb");
```

par

```java
            displaySize = sizeBd.divide(ONE_GB_BD, decimals, BigDecimal.ROUND_CEILING) + " " + ResourcesEngine.getResourceBundle().getString("size.gb");
```

puis remplacer

```java
            displaySize = new BigDecimal(size).divide(ONE_GB_BD, decimals, BigDecimal.ROUND_CEILING) + " " + ResourcesEngine.getResourceBundle().getString("size.kb");
```

par

```java
            displaySize = new BigDecimal(size).divide(ONE_KB_BD, decimals, BigDecimal.ROUND_CEILING) + " " + ResourcesEngine.getResourceBundle().getString("size.kb");
```

- [ ] **Step 4: Run the tests**

Run: `mvn -o -q -pl copybot-engine test`
Expected: PASS (dont `FileUtilTest` : 2 tests).

- [ ] **Step 5: Commit**

```bash
git ls-files --eol copybot-engine/src/main/java/com/copybot/utils/FileUtil.java
git add copybot-engine/src/main/java/com/copybot/utils/FileUtil.java copybot-engine/src/test/java/com/copybot/utils/FileUtilTest.java
git commit -m "Divide human-readable sizes by the power of their own unit" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

(`FileUtil.java` : `w/crlf`.)

---

### Task 6: `Plan.targetOf` et `Plan.Counts`

**Files:**
- Modify (réécriture): `copybot-engine/src/main/java/com/copybot/engine/Plan.java` (LF)
- Modify: `copybot-engine/src/main/java/com/copybot/engine/MainExecutor.java` (LF)
- Create: `copybot-engine/src/test/java/com/copybot/engine/PlanTest.java`

**Interfaces:**
- Consumes: `IOutAction.resolveTarget` (existant), `ControlFakes` (`FakeAction`, `day`, `emptyConfig`, `registry`, existants).
- Produces: `public record Plan.Counts(int selected, int skipped, int errors, long selectedBytes)` avec `static Counts of(Collection<WorkItemExecution>)` et `static boolean isSelected(ItemStatus)` ; `public Counts Plan.counts()` (sur `getOrderedItems()`) ; `public Optional<Path> Plan.targetOf(WorkItemExecution)` ; `IOutAction MainExecutor.findOutAction()` package-private, `null` aussi quand les étapes ne sont pas résolues. Le reste de `Plan` est inchangé.

- [ ] **Step 1: Write the failing test**

`copybot-engine/src/test/java/com/copybot/engine/PlanTest.java` :

```java
package com.copybot.engine;

import com.copybot.engine.ControlFakes.FakeAction;
import com.copybot.engine.pipeline.ItemStatus;
import com.copybot.engine.pipeline.PipelineStatus;
import com.copybot.engine.pipeline.PipelineStep;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.engine.resume.ResumeContext;
import com.copybot.engine.resume.ResumeMode;
import com.copybot.engine.resume.ResumePoint;
import com.copybot.engine.resume.ResumeStateStore;
import com.copybot.plugin.api.action.IInAction;
import com.copybot.plugin.api.action.IOutAction;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static com.copybot.engine.ControlFakes.day;
import static com.copybot.engine.ControlFakes.emptyConfig;
import static com.copybot.engine.ControlFakes.registry;
import static org.junit.jupiter.api.Assertions.*;

/** The target column and the counters of a prepared plan (spec desktop-ui §5). */
public class PlanTest {

    @TempDir
    Path tempDir;

    /** Emits IMG_01.JPG (100 bytes), IMG_02.JPG (200 bytes), IMG_03.JPG (300 bytes), dated 2026-09-01... */
    final class SizedIn extends FakeAction implements IInAction {
        @Override
        public void listFiles(Consumer<WorkItem> consumer) {
            for (int day = 1; day <= 3; day++) {
                try {
                    WorkItem wi = new WorkItem(Files.createFile(tempDir.resolve(String.format("IMG_%02d.JPG", day))));
                    wi.getMetadatas().setTime(WorkItemMetadata.LAST_MODIFIED,
                            Instant.parse(String.format("2026-09-%02dT10:00:00Z", day)));
                    wi.getMetadatas().setSize(day * 100L);
                    consumer.accept(wi);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
    }

    /** Writes nothing; its target is nas/&lt;name without extension&gt;/&lt;name&gt;, it cannot resolve failOn. */
    final class TargetOut extends FakeAction implements IOutAction {
        final String failOn;

        TargetOut(String failOn) {
            this.failOn = failOn;
        }

        @Override
        public void writeItem(WorkItem item) {
        }

        @Override
        public Optional<Path> resolveTarget(WorkItem item) {
            String name = item.getNameDisplay();
            if (name.equals(failOn)) {
                throw new IllegalStateException("no value for a pattern variable");
            }
            return Optional.of(tempDir.resolve("nas").resolve(name.substring(0, name.indexOf('.'))).resolve(name));
        }
    }

    /** SizedIn | barrier | out (mode state); the cursor, when not null, is written first. */
    private Plan prepared(IOutAction out, Integer cursorDay) {
        ResumeStateStore store = new ResumeStateStore(tempDir.resolve("p.state.json"));
        if (cursorDay != null) {
            store.writeCursor(day(cursorDay));
        }
        List<PipelineStep<?>> itemSteps = out == null ? List.of() : List.of(new PipelineStep<>(null, out, emptyConfig()));
        MainExecutor executor = new MainExecutor(
                List.of(new PipelineStep<>(null, new SizedIn(), emptyConfig())), itemSteps,
                0, false, null, registry(Map.of("disk:*", 1000)), new ResumeContext(ResumeMode.STATE, store));
        executor.prepare();
        assertEquals(PipelineStatus.PREPARED, executor.getState().getStatus());
        return new Plan(executor);
    }

    private static WorkItemExecution named(Plan plan, String name) {
        return plan.getOrderedItems().stream()
                .filter(w -> w.getWorkItem().getNameDisplay().equals(name))
                .findFirst().orElseThrow();
    }

    @Test
    public void theTargetIsTheDirectoryTheOutStepWouldWriteTo() {
        Plan plan = prepared(new TargetOut(null), null);

        assertEquals(Optional.of(tempDir.resolve("nas").resolve("IMG_02")), plan.targetOf(named(plan, "IMG_02.JPG")));
        assertFalse(Files.exists(tempDir.resolve("nas")), "resolving writes nothing");
    }

    @Test
    public void anUnresolvableTargetIsEmpty() {
        Plan plan = prepared(new TargetOut("IMG_01.JPG"), null);

        assertEquals(Optional.empty(), plan.targetOf(named(plan, "IMG_01.JPG")));
        assertTrue(plan.targetOf(named(plan, "IMG_03.JPG")).isPresent());
    }

    @Test
    public void withoutOutStepThereIsNoTarget() {
        Plan plan = prepared(null, null);

        assertEquals(Optional.empty(), plan.targetOf(named(plan, "IMG_01.JPG")));
    }

    @Test
    public void theCountersFollowTheResumePoint() {
        Plan plan = prepared(new TargetOut(null), 1);

        assertEquals(new Plan.Counts(2, 1, 0, 500), plan.counts(), "IMG_01 is before the cursor");

        plan.preview(ResumePoint.all());

        assertEquals(new Plan.Counts(3, 0, 0, 600), plan.counts());
    }

    @Test
    public void countsOfAnyItemsTellSelectedSkippedAndErrors() throws IOException {
        WorkItemExecution pending = item("a.jpg", 10L);
        WorkItemExecution done = item("b.jpg", 20L);
        done.setDone();
        WorkItemExecution skipped = item("c.jpg", 40L);
        skipped.setSkipped("identical");
        WorkItemExecution failed = item("d.jpg", 80L);
        failed.setError(new IllegalStateException("disk full"));
        WorkItemExecution noSize = item("e.jpg", null);

        assertEquals(new Plan.Counts(3, 1, 1, 30), Plan.Counts.of(List.of(pending, done, skipped, failed, noSize)));
        assertTrue(Plan.Counts.isSelected(ItemStatus.RUNNING));
        assertFalse(Plan.Counts.isSelected(ItemStatus.SKIPPED));
    }

    private WorkItemExecution item(String name, Long size) throws IOException {
        WorkItem wi = new WorkItem(tempDir.resolve(name));
        if (size != null) {
            wi.getMetadatas().setSize(size);
        }
        return new WorkItemExecution(wi, List.of());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -o -q -pl copybot-engine test -Dtest=PlanTest`
Expected: FAIL — compilation `cannot find symbol: method targetOf(…)` / `class Counts`.

- [ ] **Step 3: Implement**

Dans `MainExecutor.java` (LF, Edit), remplacer

```java
    private IOutAction findOutAction() {
        if (!itemSteps.isEmpty() && itemSteps.getLast().getAction() instanceof IOutAction out) {
```

par

```java
    /** The out step's action, null when the pipeline has none or its steps are not resolved (yet). */
    IOutAction findOutAction() {
        if (itemSteps != null && !itemSteps.isEmpty() && itemSteps.getLast().getAction() instanceof IOutAction out) {
```

Réécrire `Plan.java` en entier (Write) :

```java
package com.copybot.engine;

import com.copybot.engine.pipeline.ItemStatus;
import com.copybot.engine.pipeline.PipelineState;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.engine.resume.ItemKey;
import com.copybot.engine.resume.ResumePoint;
import com.copybot.engine.resume.ResumeProposal;
import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.IOutAction;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** A prepared pipeline: what would be imported, and from where, before anything is written. */
public final class Plan {

    /**
     * What the plan holds for the current item statuses (spec desktop-ui §5): right after the
     * preparation or a {@link #preview}, the selected items are the PENDING ones.
     *
     * @param selected      items to copy, being copied or copied (PENDING, WAITING_RESOURCES, RUNNING, DONE)
     * @param skipped       SKIPPED items
     * @param errors        ERROR items
     * @param selectedBytes total size of the selected items (an item without size counts 0)
     */
    public record Counts(int selected, int skipped, int errors, long selectedBytes) {

        public static Counts of(Collection<WorkItemExecution> items) {
            int selected = 0;
            int skipped = 0;
            int errors = 0;
            long bytes = 0;
            for (WorkItemExecution item : items) {
                switch (item.getStatus()) {
                    case SKIPPED -> skipped++;
                    case ERROR -> errors++;
                    default -> {
                        selected++;
                        Long size = item.getWorkItem().getMetadatas().getSize();
                        bytes += size == null ? 0 : size;
                    }
                }
            }
            return new Counts(selected, skipped, errors, bytes);
        }

        /** True for the statuses counted as selected. */
        public static boolean isSelected(ItemStatus status) {
            return status != ItemStatus.SKIPPED && status != ItemStatus.ERROR;
        }
    }

    private final MainExecutor executor;

    Plan(MainExecutor executor) {
        this.executor = executor;
    }

    MainExecutor getExecutor() {
        return executor;
    }

    public PipelineState getState() {
        return executor.getState();
    }

    public ResumeProposal getProposal() {
        return executor.getProposal();
    }

    /** Listed items in resume order (items without date last). */
    public List<WorkItemExecution> getOrderedItems() {
        return executor.getOrderedItems();
    }

    /** The counters of the listed items, for their current statuses. */
    public Counts counts() {
        return Counts.of(getOrderedItems());
    }

    /**
     * The directory the out step would write this item to ({@link IOutAction#resolveTarget}), without
     * writing anything; empty without out step, when the action cannot tell or fails to resolve it.
     */
    public Optional<Path> targetOf(WorkItemExecution item) {
        IOutAction out = executor.findOutAction();
        if (out == null) {
            return Optional.empty();
        }
        try {
            return out.resolveTarget(item.getWorkItem()).map(Path::getParent);
        } catch (RuntimeException e) {
            return Optional.empty(); // e.g. a pattern variable this item has no value for, in a plugin
        }
    }

    /** Re-applies a manual resume point to the item statuses, without executing anything (dry-run display). */
    public void preview(ResumePoint override) {
        executor.applyOverride(override);
    }

    /** Resume from this listed file (its name when it was listed), included. */
    public ResumePoint fromFile(String fileName) {
        return getOrderedItems().stream()
                .map(WorkItemExecution::getResumeKey)
                .flatMap(Optional::stream)
                .filter(key -> fileName.equals(key.name()))
                .findFirst()
                .map(ResumePoint::from)
                .orElseThrow(() -> CopybotException.ofResource("resume.from-file.not-found", fileName));
    }

    /** Resume from the start of this day (system time zone), included. */
    public static ResumePoint fromDate(LocalDate date) {
        return ResumePoint.from(new ItemKey(date.atStartOfDay(ZoneId.systemDefault()).toInstant(), ""));
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `mvn -o -q -pl copybot-engine test`
Expected: PASS (dont `PlanTest` : 5 tests ; `CopybotEngineTest`, `ResumeEndToEndTest` inchangés).

- [ ] **Step 5: Commit**

```bash
git ls-files --eol copybot-engine/src/main/java/com/copybot/engine/Plan.java copybot-engine/src/main/java/com/copybot/engine/MainExecutor.java
git add copybot-engine/src/main/java/com/copybot/engine/Plan.java copybot-engine/src/main/java/com/copybot/engine/MainExecutor.java copybot-engine/src/test/java/com/copybot/engine/PlanTest.java
git commit -m "Tell the target directory and the counters of a prepared plan" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

(Les deux : `w/lf`.)

---

### Task 7: Tests dans `copybot-ui` et pipelines récents

**Files:**
- Modify: `copybot-ui/pom.xml` (LF)
- Modify: `copybot-ui/src/main/java/module-info.java` (LF)
- Create: `copybot-ui/src/main/java/com/copybot/ui/model/RecentPipelines.java`
- Modify (réécriture): `copybot-ui/src/main/java/com/copybot/ui/util/UiPreferences.java` (LF)
- Create: `copybot-ui/src/test/java/com/copybot/ui/model/RecentPipelinesTest.java`

**Interfaces:**
- Consumes: `PipelineStatus` (moteur).
- Produces: `public final class RecentPipelines` : `static final int MAX = 10`, `record LastRun(Instant at, PipelineStatus status, int copied, int skipped, int errors)`, `record Entry(Path path, Instant openedAt, LastRun lastRun)` (chemin absolu normalisé, `displayName()`, `isMissing()`), `static String displayName(Path)`, `List<Entry> entries()`, `void touch(Path, Instant)`, `void recordRun(Path, LastRun)`, `void remove(Path)`, `static RecentPipelines fromJson(String)`, `String toJson()`, `String toJsonWithin(int maxChars)` ; `UiPreferences.recents()`, `UiPreferences.updateRecents(Consumer<RecentPipelines>)` (retourne la liste enregistrée) ; le module UI lit Gson (`requires com.google.gson`) et a JUnit en test.

- [ ] **Step 1: Add JUnit and Gson to the UI module**

Dans `copybot-ui/pom.xml` (Edit), remplacer

```xml
        <dependency>
            <groupId>org.kordamp.bootstrapfx</groupId>
            <artifactId>bootstrapfx-core</artifactId>
        </dependency>
    </dependencies>
```

par

```xml
        <dependency>
            <groupId>org.kordamp.bootstrapfx</groupId>
            <artifactId>bootstrapfx-core</artifactId>
        </dependency>

        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter-api</artifactId>
            <version>${junit.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter-engine</artifactId>
            <version>${junit.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>
```

Dans `copybot-ui/src/main/java/module-info.java` (Edit), remplacer

```java
    requires java.prefs;
```

par

```java
    requires java.prefs;
    requires com.google.gson;
```

(Gson arrive déjà par la dépendance moteur ; les tests passent sur le module path comme ceux du moteur, surefire ouvre les paquets de test.)

- [ ] **Step 2: Write the failing test**

`copybot-ui/src/test/java/com/copybot/ui/model/RecentPipelinesTest.java` :

```java
package com.copybot.ui.model;

import com.copybot.engine.pipeline.PipelineStatus;
import com.copybot.ui.model.RecentPipelines.Entry;
import com.copybot.ui.model.RecentPipelines.LastRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The recent pipelines of the home screen (spec desktop-ui §1, §6). */
public class RecentPipelinesTest {

    @TempDir
    Path tempDir;

    private static final Instant T0 = Instant.parse("2026-09-28T17:42:00Z");

    private static Instant at(int minutes) {
        return T0.plusSeconds(60L * minutes);
    }

    private Path pipeline(String name) throws IOException {
        return Files.writeString(tempDir.resolve(name), "{}");
    }

    private static List<String> names(RecentPipelines recents) {
        return recents.entries().stream().map(Entry::displayName).toList();
    }

    @Test
    public void theLastOpenedComesFirstAndAppearsOnce() throws IOException {
        RecentPipelines recents = new RecentPipelines();
        recents.touch(pipeline("sd-card.json"), at(0));
        recents.touch(pipeline("phone.json"), at(1));
        recents.touch(tempDir.resolve("sub/../sd-card.json"), at(2));

        assertEquals(List.of("sd-card", "phone"), names(recents));
        assertEquals(at(2), recents.entries().getFirst().openedAt());
    }

    @Test
    public void onlyTheTenLastAreKept() throws IOException {
        RecentPipelines recents = new RecentPipelines();
        for (int i = 0; i < 12; i++) {
            recents.touch(pipeline("p" + i + ".json"), at(i));
        }

        assertEquals(RecentPipelines.MAX, recents.entries().size());
        assertEquals("p11", names(recents).getFirst());
        assertFalse(names(recents).contains("p1"), "the oldest are dropped");
    }

    @Test
    public void aRunIsRecordedOnItsPipelineAndKeptWhenReopened() throws IOException {
        RecentPipelines recents = new RecentPipelines();
        Path sd = pipeline("sd-card.json");
        recents.touch(sd, at(0));
        recents.touch(pipeline("phone.json"), at(1));
        LastRun run = new LastRun(at(5), PipelineStatus.SUCCESS, 120, 3, 1);

        recents.recordRun(sd, run);

        assertEquals(List.of("phone", "sd-card"), names(recents), "recording a run does not reorder");
        assertEquals(run, recents.entries().get(1).lastRun());
        recents.touch(sd, at(9));
        assertEquals(run, recents.entries().getFirst().lastRun());
        assertNull(recents.entries().get(1).lastRun());
    }

    @Test
    public void aRunOfAPipelineNotInTheListAddsIt() throws IOException {
        RecentPipelines recents = new RecentPipelines();
        LastRun run = new LastRun(at(5), PipelineStatus.ERROR, 0, 0, 4);

        recents.recordRun(pipeline("new.json"), run);

        assertEquals(List.of("new"), names(recents));
        assertEquals(run, recents.entries().getFirst().lastRun());
    }

    @Test
    public void anEntryCanBeRemoved() throws IOException {
        RecentPipelines recents = new RecentPipelines();
        recents.touch(pipeline("a.json"), at(0));
        recents.touch(pipeline("b.json"), at(1));

        recents.remove(tempDir.resolve("a.json"));

        assertEquals(List.of("b"), names(recents));
    }

    @Test
    public void aDeletedFileIsReportedMissing() throws IOException {
        RecentPipelines recents = new RecentPipelines();
        Path gone = pipeline("gone.json");
        recents.touch(gone, at(0));
        recents.touch(pipeline("here.json"), at(1));

        Files.delete(gone);

        assertFalse(recents.entries().get(0).isMissing());
        assertTrue(recents.entries().get(1).isMissing());
    }

    @Test
    public void theDisplayNameIsTheFileNameWithoutExtension() {
        assertEquals("sd-card", RecentPipelines.displayName(Path.of("x", "sd-card.json")));
        assertEquals("a.b", RecentPipelines.displayName(Path.of("a.b.json")));
        assertEquals("noext", RecentPipelines.displayName(Path.of("noext")));
        assertEquals(".hidden", RecentPipelines.displayName(Path.of(".hidden")));
    }

    @Test
    public void theJsonRoundTripKeepsOrderDatesAndRuns() throws IOException {
        RecentPipelines recents = new RecentPipelines();
        recents.touch(pipeline("a.json"), at(0));
        recents.touch(pipeline("b.json"), at(1));
        recents.recordRun(tempDir.resolve("a.json"), new LastRun(at(3), PipelineStatus.CANCELLED, 2, 0, 0));

        RecentPipelines read = RecentPipelines.fromJson(recents.toJson());

        assertEquals(recents.entries(), read.entries());
    }

    @Test
    public void unreadablePreferencesGiveWhatCanBeRead() throws IOException {
        assertEquals(List.of(), RecentPipelines.fromJson(null).entries());
        assertEquals(List.of(), RecentPipelines.fromJson("not json {").entries());
        assertEquals(List.of(), RecentPipelines.fromJson("{\"path\":\"x\"}").entries());
        String path = pipeline("ok.json").toString().replace("\\", "\\\\");
        String json = "[{\"path\":\"" + path + "\",\"openedAt\":\"2026-09-28T17:42:00Z\"},"
                + "{\"path\":\"y.json\",\"openedAt\":\"yesterday\"},"
                + "{\"path\":\"z.json\",\"openedAt\":\"2026-09-28T17:42:00Z\",\"lastRun\":{\"status\":\"DONE?\"}},"
                + "42]";

        assertEquals(List.of("ok"), names(RecentPipelines.fromJson(json)));
    }

    @Test
    public void theJsonIsCutToFitAPreferenceValue() throws IOException {
        RecentPipelines recents = new RecentPipelines();
        for (int i = 0; i < 5; i++) {
            recents.touch(pipeline("pipeline-" + i + ".json"), at(i));
        }
        int oneEntry = RecentPipelines.fromJson(recents.toJson()).toJson().length() / 5;

        String cut = recents.toJsonWithin(oneEntry * 3);

        assertTrue(cut.length() <= oneEntry * 3, cut);
        assertEquals(List.of("pipeline-4", "pipeline-3"), names(RecentPipelines.fromJson(cut)).subList(0, 2),
                "the most recent are kept");
        assertEquals(recents.toJson(), recents.toJsonWithin(Integer.MAX_VALUE));
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `mvn -o -q -pl copybot-engine,copybot-ui test -Dtest=RecentPipelinesTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — compilation `cannot find symbol: class RecentPipelines`.

- [ ] **Step 4: Implement**

`copybot-ui/src/main/java/com/copybot/ui/model/RecentPipelines.java` :

```java
package com.copybot.ui.model;

import com.copybot.engine.pipeline.PipelineStatus;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The recent pipelines of the home screen (spec desktop-ui §1): at most {@link #MAX} entries, the last
 * opened first, each with the summary of its last execution. Persisted as JSON in the UI preferences.
 * Not thread-safe: used from the JavaFX thread.
 */
public final class RecentPipelines {

    public static final int MAX = 10;

    /** The summary of an execution (spec desktop-ui §2, end of the run). */
    public record LastRun(Instant at, PipelineStatus status, int copied, int skipped, int errors) {
        public LastRun {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(status, "status");
        }
    }

    /** @param lastRun null when the pipeline was never executed from the UI */
    public record Entry(Path path, Instant openedAt, LastRun lastRun) {
        public Entry {
            path = normalize(path);
            Objects.requireNonNull(openedAt, "openedAt");
        }

        /** The file name without its extension. */
        public String displayName() {
            return RecentPipelines.displayName(path);
        }

        /** The pipeline file no longer exists: shown greyed, "not found" (spec desktop-ui §1). */
        public boolean isMissing() {
            return !Files.isRegularFile(path);
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    /** The file name without its extension ("sd-card.json" gives "sd-card"). */
    public static String displayName(Path path) {
        Path fileName = path.getFileName();
        if (fileName == null) {
            return path.toString();
        }
        String name = fileName.toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static Path normalize(Path path) {
        return Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    }

    /** The last opened first. */
    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    /** Opened now: moved (or added) first, its last run kept; the oldest beyond {@link #MAX} are dropped. */
    public void touch(Path path, Instant now) {
        Path normalized = normalize(path);
        LastRun lastRun = find(normalized).map(Entry::lastRun).orElse(null);
        entries.removeIf(e -> e.path().equals(normalized));
        entries.addFirst(new Entry(normalized, now, lastRun));
        while (entries.size() > MAX) {
            entries.removeLast();
        }
    }

    /** Records the summary of an execution; a pipeline not in the list is added first. */
    public void recordRun(Path path, LastRun run) {
        Objects.requireNonNull(run, "run");
        Path normalized = normalize(path);
        if (find(normalized).isEmpty()) {
            touch(normalized, run.at());
        }
        entries.replaceAll(e -> e.path().equals(normalized) ? new Entry(normalized, e.openedAt(), run) : e);
    }

    public void remove(Path path) {
        Path normalized = normalize(path);
        entries.removeIf(e -> e.path().equals(normalized));
    }

    private Optional<Entry> find(Path normalized) {
        return entries.stream().filter(e -> e.path().equals(normalized)).findFirst();
    }

    // ---- persistence ----

    /** Reads what {@link #toJson()} wrote; null, invalid JSON or invalid entries give what can be read. */
    public static RecentPipelines fromJson(String json) {
        RecentPipelines recents = new RecentPipelines();
        if (json == null || json.isBlank()) {
            return recents;
        }
        JsonElement tree;
        try {
            tree = JsonParser.parseString(json);
        } catch (JsonParseException e) {
            return recents;
        }
        if (!tree.isJsonArray()) {
            return recents;
        }
        for (JsonElement element : tree.getAsJsonArray()) {
            try {
                JsonObject object = element.getAsJsonObject();
                Path path = Path.of(object.get("path").getAsString());
                Instant openedAt = Instant.parse(object.get("openedAt").getAsString());
                LastRun lastRun = object.has("lastRun") ? lastRun(object.getAsJsonObject("lastRun")) : null;
                if (recents.entries.size() < MAX && recents.find(normalize(path)).isEmpty()) {
                    recents.entries.add(new Entry(path, openedAt, lastRun));
                }
            } catch (RuntimeException e) { // IllegalState, ClassCast, NullPointer, InvalidPath, DateTimeParse...
                // an unreadable entry is dropped, the others are kept
            }
        }
        return recents;
    }

    private static LastRun lastRun(JsonObject object) {
        return new LastRun(Instant.parse(object.get("at").getAsString()),
                PipelineStatus.valueOf(object.get("status").getAsString()),
                object.get("copied").getAsInt(), object.get("skipped").getAsInt(), object.get("errors").getAsInt());
    }

    public String toJson() {
        return toJson(entries);
    }

    /**
     * The JSON of the entries, the oldest dropped until it fits in maxChars (a preference value is
     * bounded, e.g. {@code Preferences.MAX_VALUE_LENGTH}).
     */
    public String toJsonWithin(int maxChars) {
        List<Entry> kept = new ArrayList<>(entries);
        String json = toJson(kept);
        while (json.length() > maxChars && !kept.isEmpty()) {
            kept.removeLast();
            json = toJson(kept);
        }
        return json;
    }

    private static String toJson(List<Entry> entries) {
        JsonArray array = new JsonArray();
        for (Entry entry : entries) {
            JsonObject object = new JsonObject();
            object.addProperty("path", entry.path().toString());
            object.addProperty("openedAt", entry.openedAt().toString());
            if (entry.lastRun() != null) {
                JsonObject run = new JsonObject();
                run.addProperty("at", entry.lastRun().at().toString());
                run.addProperty("status", entry.lastRun().status().name());
                run.addProperty("copied", entry.lastRun().copied());
                run.addProperty("skipped", entry.lastRun().skipped());
                run.addProperty("errors", entry.lastRun().errors());
                object.add("lastRun", run);
            }
            array.add(object);
        }
        return array.toString();
    }
}
```

Réécrire `copybot-ui/src/main/java/com/copybot/ui/util/UiPreferences.java` en entier (Write) :

```java
package com.copybot.ui.util;

import com.copybot.ui.model.RecentPipelines;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.prefs.Preferences;

/** User preferences persisted between launches (backed by the OS user store, e.g. the registry on Windows). */
public final class UiPreferences {

    private static final Preferences PREFS = Preferences.userNodeForPackage(UiPreferences.class);
    private static final String LANGUAGE_KEY = "language";
    private static final String RECENTS_KEY = "recentPipelines";

    private UiPreferences() {
    }

    public static Optional<Locale> savedLanguage() {
        String tag = PREFS.get(LANGUAGE_KEY, null);
        return tag == null ? Optional.empty() : Optional.of(Locale.forLanguageTag(tag));
    }

    public static void saveLanguage(Locale locale) {
        PREFS.put(LANGUAGE_KEY, locale.toLanguageTag());
    }

    /** The recent pipelines and their last run (spec desktop-ui §6); empty when none or unreadable. */
    public static RecentPipelines recents() {
        return RecentPipelines.fromJson(PREFS.get(RECENTS_KEY, null));
    }

    /** Reads the recent pipelines, applies the change and saves them (the oldest dropped if too long). */
    public static RecentPipelines updateRecents(Consumer<RecentPipelines> change) {
        RecentPipelines recents = recents();
        change.accept(recents);
        PREFS.put(RECENTS_KEY, recents.toJsonWithin(Preferences.MAX_VALUE_LENGTH));
        return recents;
    }
}
```

- [ ] **Step 5: Run the tests**

Run: `mvn -o -q -pl copybot-engine,copybot-ui test`
Expected: PASS (dont `RecentPipelinesTest` : 10 tests ; `HelloController` compile toujours).

- [ ] **Step 6: Commit**

```bash
git ls-files --eol copybot-ui/pom.xml copybot-ui/src/main/java/module-info.java copybot-ui/src/main/java/com/copybot/ui/util/UiPreferences.java
git add copybot-ui/pom.xml copybot-ui/src/main/java/module-info.java copybot-ui/src/main/java/com/copybot/ui/model/RecentPipelines.java copybot-ui/src/main/java/com/copybot/ui/util/UiPreferences.java copybot-ui/src/test/java/com/copybot/ui/model/RecentPipelinesTest.java
git commit -m "Remember the recent pipelines and their last run" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

(Tous : `w/lf`.)

---

### Task 8: Textes de l'IHM dans les bundles UI

**Files:**
- Modify (append-only): `copybot-ui/src/main/resources/com/copybot/ui/i18n/uiBundle.properties` (UTF-8 LF)
- Modify (append-only): `copybot-ui/src/main/resources/com/copybot/ui/i18n/uiBundle_fr.properties` (UTF-8 LF)
- Create: `copybot-ui/src/test/java/com/copybot/ui/model/UiBundleTest.java`

**Interfaces:**
- Consumes: —
- Produces: les 99 clés listées par `UiBundleTest.KEYS` (accueil `home.*`, `recent.*`, `pipeline.status.*` ; vue du plan `plan.*`, `item.status.*`, `resume.dialog.*` ; éditeur `editor.*` dont `editor.section.<PIPELINE|IN|ANALYZE|PROCESS|OUT>` et `editor.advanced.<champ>[.description]`). Arguments : `recent.last-run` ({0} date, {1} statut, {2} copiés, {3} ignorés, {4} erreurs), `plan.copy` ({0} fichiers, {1} taille), `plan.progress` ({0}/{1} fichiers, {2}/{3} tailles), `plan.finished` ({0} statut, {1} copiés, {2} ignorés, {3} erreurs), `plan.resume.after` / `plan.resume.from` ({0} nom, {1} date, {2} origine), `plan.resume.from-date` ({0} jour, {1} origine), `plan.resume.all` / `plan.steps` / `plan.source` / `plan.out-pattern` / `plan.resume-mode` / `plan.last-run` / `plan.preparing` / `plan.prepare-failed` / `item.status.WAITING_RESOURCES` / `item.status.RUNNING.percent` / `item.status.SKIPPED` / `item.status.ERROR` / `editor.*` à un argument ({0}).

- [ ] **Step 1: Write the failing test**

`copybot-ui/src/test/java/com/copybot/ui/model/UiBundleTest.java` :

```java
package com.copybot.ui.model;

import com.copybot.resources.ResourcesEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/** The texts of the desktop UI exist in both UI bundles (spec desktop-ui §6). */
public class UiBundleTest {

    static final List<String> KEYS = List.of(
            "home.title", "home.open", "home.new", "home.remove", "home.missing", "home.empty", "home.file-filter",
            "recent.never-run", "recent.last-run",
            "pipeline.status.SUCCESS", "pipeline.status.ERROR", "pipeline.status.CANCELLED",
            "plan.back", "plan.edit", "plan.steps", "plan.source", "plan.out-pattern", "plan.resume-mode",
            "plan.resume-mode.absent", "plan.resume-mode.none", "plan.resume-mode.state",
            "plan.resume-mode.destination", "plan.resume-mode.stateThenDestination", "plan.last-run",
            "plan.resume.all", "plan.resume.after", "plan.resume.from", "plan.resume.from-date",
            "plan.resume.source.NONE", "plan.resume.source.STATE", "plan.resume.source.DESTINATION",
            "plan.resume.source.MANUAL", "plan.resume.change", "plan.placeholder",
            "plan.filter.ALL", "plan.filter.TO_COPY", "plan.filter.SKIPPED", "plan.filter.ERRORS",
            "plan.column.date", "plan.column.target", "plan.menu.resume-from-here", "plan.prepare", "plan.copy",
            "plan.auto-execute", "plan.pause", "plan.resume", "plan.stop", "plan.progress", "plan.preparing",
            "plan.prepare-failed", "plan.finished",
            "item.status.PENDING", "item.status.WAITING_RESOURCES", "item.status.RUNNING",
            "item.status.RUNNING.percent", "item.status.DONE", "item.status.SKIPPED", "item.status.ERROR",
            "resume.dialog.title", "resume.dialog.all", "resume.dialog.date", "resume.dialog.file",
            "editor.title", "editor.untitled", "editor.section.PIPELINE", "editor.section.IN",
            "editor.section.ANALYZE", "editor.section.PROCESS", "editor.section.OUT", "editor.add", "editor.up",
            "editor.down", "editor.remove", "editor.save", "editor.save-as", "editor.show-json",
            "editor.startProcessingWhileListing", "editor.resume-mode", "editor.autoExecute", "editor.advanced",
            "editor.advanced.maxConcurrency", "editor.advanced.maxConcurrency.description",
            "editor.advanced.resources", "editor.advanced.resources.description", "editor.advanced.priority",
            "editor.advanced.priority.description", "editor.advanced.version", "editor.advanced.version.description",
            "editor.plugin-not-found", "editor.no-schema", "editor.default", "editor.browse",
            "editor.pattern-variables", "editor.add.title", "editor.add.empty", "editor.required-missing",
            "editor.invalid-value", "editor.json.title", "editor.discard");

    @BeforeAll
    public static void registerUiBundle() {
        ResourcesEngine.registerBundle("com.copybot.ui.i18n.uiBundle");
    }

    /** The UI bundles are UTF-8 (ResourceBundle reads UTF-8 first); the new lines are ASCII escapes. */
    private static Properties bundle(String name) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = UiBundleTest.class.getResourceAsStream("/com/copybot/ui/i18n/" + name)) {
            assertNotNull(in, name);
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        }
        return properties;
    }

    @Test
    public void everyKeyIsInBothBundlesAndIsAValidMessageFormat() throws IOException {
        for (String file : List.of("uiBundle.properties", "uiBundle_fr.properties")) {
            Properties properties = bundle(file);
            for (String key : KEYS) {
                String value = properties.getProperty(key);
                assertNotNull(value, key + " in " + file);
                assertDoesNotThrow(() -> new MessageFormat(value), key + " in " + file);
                assertFalse(value.contains("�"), key + " in " + file + " was re-encoded");
            }
        }
    }

    @Test
    public void bothBundlesHaveTheSameKeys() throws IOException {
        assertEquals(bundle("uiBundle.properties").stringPropertyNames(),
                bundle("uiBundle_fr.properties").stringPropertyNames());
    }

    @Test
    public void everyKeyIsResolvedByTheEngine() {
        for (String key : KEYS) {
            assertFalse(ResourcesEngine.getString(key, "a", "b", "c", "d", "e").startsWith("%"), key);
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -o -q -pl copybot-engine,copybot-ui test -Dtest=UiBundleTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — `everyKeyIsInBothBundlesAndIsAValidMessageFormat … home.title in uiBundle.properties ==> expected: not <null>` et `everyKeyIsResolvedByTheEngine … home.title ==> expected: <false> but was: <true>` (`bothBundlesHaveTheSameKeys` passe déjà).

- [ ] **Step 3: Append the texts (script Bash, depuis la racine du repo)**

Écrire ce bloc tel quel avec l'outil Write dans `<scratchpad>/ui-labels.sh`, puis `bash <scratchpad>/ui-labels.sh` depuis la racine du repo (une seule fois) :

```bash
EN=copybot-ui/src/main/resources/com/copybot/ui/i18n/uiBundle.properties
FR=copybot-ui/src/main/resources/com/copybot/ui/i18n/uiBundle_fr.properties
printf '%s\n' '' >> $EN
printf '%s\n' 'home.title=Pipelines' >> $EN
printf '%s\n' 'home.open=Open a pipeline…' >> $EN
printf '%s\n' 'home.new=New…' >> $EN
printf '%s\n' 'home.remove=Remove from the list' >> $EN
printf '%s\n' 'home.missing=not found' >> $EN
printf '%s\n' 'home.empty=No recent pipeline: open or create one' >> $EN
printf '%s\n' 'home.file-filter=Copybot pipelines (*.json)' >> $EN
printf '%s\n' 'recent.never-run=Never run' >> $EN
printf '%s\n' 'recent.last-run={0} — {1}: {2} copied, {3} skipped, {4} errors' >> $EN
printf '%s\n' 'pipeline.status.SUCCESS=Success' >> $EN
printf '%s\n' 'pipeline.status.ERROR=Failed' >> $EN
printf '%s\n' 'pipeline.status.CANCELLED=Stopped' >> $EN
printf '%s\n' 'plan.back=← Pipelines' >> $EN
printf '%s\n' 'plan.edit=Edit…' >> $EN
printf '%s\n' 'plan.steps=Steps: {0}' >> $EN
printf '%s\n' 'plan.source=Source: {0}' >> $EN
printf '%s\n' 'plan.out-pattern=Output: {0}' >> $EN
printf '%s\n' 'plan.resume-mode=Resume: {0}' >> $EN
printf '%s\n' 'plan.resume-mode.absent=no resume' >> $EN
printf '%s\n' 'plan.resume-mode.none=none' >> $EN
printf '%s\n' 'plan.resume-mode.state=from the cursor' >> $EN
printf '%s\n' 'plan.resume-mode.destination=from the destination' >> $EN
printf '%s\n' 'plan.resume-mode.stateThenDestination=cursor, then destination' >> $EN
printf '%s\n' 'plan.last-run=Last run: {0}' >> $EN
printf '%s\n' 'plan.resume.all=Resume: everything [{0}]' >> $EN
printf '%s\n' 'plan.resume.after=Resume: after {0} ({1}) [{2}]' >> $EN
printf '%s\n' 'plan.resume.from=Resume: from {0} ({1}) [{2}]' >> $EN
printf '%s\n' 'plan.resume.from-date=Resume: from {0} [{1}]' >> $EN
printf '%s\n' 'plan.resume.source.NONE=nothing detected' >> $EN
printf '%s\n' 'plan.resume.source.STATE=cursor' >> $EN
printf '%s\n' 'plan.resume.source.DESTINATION=destination' >> $EN
printf '%s\n' 'plan.resume.source.MANUAL=manual' >> $EN
printf '%s\n' 'plan.resume.change=change…' >> $EN
printf '%s\n' 'plan.placeholder=Plan not prepared — click Prepare the plan to list the source' >> $EN
printf '%s\n' 'plan.filter.ALL=All' >> $EN
printf '%s\n' 'plan.filter.TO_COPY=To copy' >> $EN
printf '%s\n' 'plan.filter.SKIPPED=Skipped' >> $EN
printf '%s\n' 'plan.filter.ERRORS=Errors' >> $EN
printf '%s\n' 'plan.column.date=Date' >> $EN
printf '%s\n' 'plan.column.target=Target' >> $EN
printf '%s\n' 'plan.menu.resume-from-here=Resume from here' >> $EN
printf '%s\n' 'plan.prepare=Prepare the plan' >> $EN
printf '%s\n' 'plan.copy=Copy {0} files ({1})' >> $EN
printf '%s\n' 'plan.auto-execute=Automatic execution' >> $EN
printf '%s\n' 'plan.pause=Pause' >> $EN
printf '%s\n' 'plan.resume=Resume' >> $EN
printf '%s\n' 'plan.stop=Stop' >> $EN
printf '%s\n' 'plan.progress={0} / {1} files, {2} / {3}' >> $EN
printf '%s\n' 'plan.preparing=Listing the source… {0} files' >> $EN
printf '%s\n' 'plan.prepare-failed=The preparation failed: {0}' >> $EN
printf '%s\n' 'plan.finished={0}: {1} copied, {2} skipped, {3} errors' >> $EN
printf '%s\n' 'item.status.PENDING=To copy' >> $EN
printf '%s\n' 'item.status.WAITING_RESOURCES=Waiting — {0}' >> $EN
printf '%s\n' 'item.status.RUNNING=Copying…' >> $EN
printf '%s\n' 'item.status.RUNNING.percent=Copying {0} %' >> $EN
printf '%s\n' 'item.status.DONE=Copied' >> $EN
printf '%s\n' 'item.status.SKIPPED=Skipped — {0}' >> $EN
printf '%s\n' 'item.status.ERROR=Error — {0}' >> $EN
printf '%s\n' 'resume.dialog.title=Resume point' >> $EN
printf '%s\n' 'resume.dialog.all=Everything' >> $EN
printf '%s\n' 'resume.dialog.date=From a date' >> $EN
printf '%s\n' 'resume.dialog.file=From a file of the plan' >> $EN
printf '%s\n' 'editor.title=Pipeline editor — {0}' >> $EN
printf '%s\n' 'editor.untitled=new pipeline' >> $EN
printf '%s\n' 'editor.section.PIPELINE=Pipeline' >> $EN
printf '%s\n' 'editor.section.IN=Inputs' >> $EN
printf '%s\n' 'editor.section.ANALYZE=Analyses' >> $EN
printf '%s\n' 'editor.section.PROCESS=Processing' >> $EN
printf '%s\n' 'editor.section.OUT=Output' >> $EN
printf '%s\n' 'editor.add=Add' >> $EN
printf '%s\n' 'editor.up=Up' >> $EN
printf '%s\n' 'editor.down=Down' >> $EN
printf '%s\n' 'editor.remove=Remove' >> $EN
printf '%s\n' 'editor.save=Save' >> $EN
printf '%s\n' 'editor.save-as=Save as…' >> $EN
printf '%s\n' 'editor.show-json=Show the JSON' >> $EN
printf '%s\n' 'editor.startProcessingWhileListing=Process while listing' >> $EN
printf '%s\n' 'editor.resume-mode=Resume mode' >> $EN
printf '%s\n' 'editor.autoExecute=Automatic execution' >> $EN
printf '%s\n' 'editor.advanced=Advanced' >> $EN
printf '%s\n' 'editor.advanced.maxConcurrency=Maximum concurrency' >> $EN
printf '%s\n' 'editor.advanced.maxConcurrency.description=How many files this step processes at the same time' >> $EN
printf '%s\n' 'editor.advanced.resources=Resources' >> $EN
printf '%s\n' 'editor.advanced.resources.description=Additional resources this step consumes, one per line (e.g. gpu)' >> $EN
printf '%s\n' 'editor.advanced.priority=Priority' >> $EN
printf '%s\n' 'editor.advanced.priority.description=A step of higher priority gets the shared resources first' >> $EN
printf '%s\n' 'editor.advanced.version=Plugin version' >> $EN
printf '%s\n' 'editor.advanced.version.description=The plugin version to use when several are loaded (e.g. 1.2)' >> $EN
printf '%s\n' 'editor.plugin-not-found=Plugin not found: {0} (this step is kept as is)' >> $EN
printf '%s\n' 'editor.no-schema=This action does not describe its configuration: its JSON is kept as is' >> $EN
printf '%s\n' 'editor.default=default: {0}' >> $EN
printf '%s\n' 'editor.browse=Browse…' >> $EN
printf '%s\n' 'editor.pattern-variables=Variables: {0}' >> $EN
printf '%s\n' 'editor.add.title=Add a step' >> $EN
printf '%s\n' 'editor.add.empty=No loaded action fits this section' >> $EN
printf '%s\n' 'editor.required-missing=Required fields are empty:\n{0}' >> $EN
printf '%s\n' 'editor.invalid-value=Invalid value: {0}' >> $EN
printf '%s\n' 'editor.json.title=Pipeline JSON' >> $EN
printf '%s\n' 'editor.discard=Discard the unsaved changes?' >> $EN
printf '\n' >> $FR
printf 'home.title=Pipelines\n' >> $FR
printf 'home.open=Ouvrir un pipeline\134u2026\n' >> $FR
printf 'home.new=Nouveau\134u2026\n' >> $FR
printf 'home.remove=Retirer de la liste\n' >> $FR
printf 'home.missing=introuvable\n' >> $FR
printf 'home.empty=Aucun pipeline r\134u00e9cent : en ouvrir ou en cr\134u00e9er un\n' >> $FR
printf 'home.file-filter=Pipelines Copybot (*.json)\n' >> $FR
printf 'recent.never-run=Jamais ex\134u00e9cut\134u00e9\n' >> $FR
printf 'recent.last-run={0} \134u2014 {1} : {2} copi\134u00e9s, {3} ignor\134u00e9s, {4} erreurs\n' >> $FR
printf 'pipeline.status.SUCCESS=Succ\134u00e8s\n' >> $FR
printf 'pipeline.status.ERROR=\134u00c9chec\n' >> $FR
printf 'pipeline.status.CANCELLED=Arr\134u00eat\134u00e9\n' >> $FR
printf 'plan.back=\134u2190 Pipelines\n' >> $FR
printf 'plan.edit=\134u00c9diter\134u2026\n' >> $FR
printf 'plan.steps=\134u00c9tapes : {0}\n' >> $FR
printf 'plan.source=Source : {0}\n' >> $FR
printf 'plan.out-pattern=Sortie : {0}\n' >> $FR
printf 'plan.resume-mode=Reprise : {0}\n' >> $FR
printf 'plan.resume-mode.absent=sans reprise\n' >> $FR
printf 'plan.resume-mode.none=aucune\n' >> $FR
printf 'plan.resume-mode.state=depuis le curseur\n' >> $FR
printf 'plan.resume-mode.destination=depuis la destination\n' >> $FR
printf 'plan.resume-mode.stateThenDestination=curseur, puis destination\n' >> $FR
printf 'plan.last-run=Derni\134u00e8re ex\134u00e9cution : {0}\n' >> $FR
printf 'plan.resume.all=Reprise : tout [{0}]\n' >> $FR
printf 'plan.resume.after=Reprise : apr\134u00e8s {0} ({1}) [{2}]\n' >> $FR
printf 'plan.resume.from=Reprise : \134u00e0 partir de {0} ({1}) [{2}]\n' >> $FR
printf 'plan.resume.from-date=Reprise : \134u00e0 partir du {0} [{1}]\n' >> $FR
printf 'plan.resume.source.NONE=rien de d\134u00e9tect\134u00e9\n' >> $FR
printf 'plan.resume.source.STATE=curseur\n' >> $FR
printf 'plan.resume.source.DESTINATION=destination\n' >> $FR
printf 'plan.resume.source.MANUAL=manuel\n' >> $FR
printf 'plan.resume.change=changer\134u2026\n' >> $FR
printf 'plan.placeholder=Plan non pr\134u00e9par\134u00e9 \134u2014 cliquez sur Pr\134u00e9parer le plan pour lister la source\n' >> $FR
printf 'plan.filter.ALL=Tous\n' >> $FR
printf 'plan.filter.TO_COPY=\134u00c0 copier\n' >> $FR
printf 'plan.filter.SKIPPED=Ignor\134u00e9s\n' >> $FR
printf 'plan.filter.ERRORS=Erreurs\n' >> $FR
printf 'plan.column.date=Date\n' >> $FR
printf 'plan.column.target=Cible\n' >> $FR
printf 'plan.menu.resume-from-here=Reprendre \134u00e0 partir d\047\047ici\n' >> $FR
printf 'plan.prepare=Pr\134u00e9parer le plan\n' >> $FR
printf 'plan.copy=Copier {0} fichiers ({1})\n' >> $FR
printf 'plan.auto-execute=Ex\134u00e9cution automatique\n' >> $FR
printf 'plan.pause=Pause\n' >> $FR
printf 'plan.resume=Reprendre\n' >> $FR
printf 'plan.stop=Stop\n' >> $FR
printf 'plan.progress={0} / {1} fichiers, {2} / {3}\n' >> $FR
printf 'plan.preparing=Listing de la source\134u2026 {0} fichiers\n' >> $FR
printf 'plan.prepare-failed=La pr\134u00e9paration a \134u00e9chou\134u00e9 : {0}\n' >> $FR
printf 'plan.finished={0} : {1} copi\134u00e9s, {2} ignor\134u00e9s, {3} erreurs\n' >> $FR
printf 'item.status.PENDING=\134u00c0 copier\n' >> $FR
printf 'item.status.WAITING_RESOURCES=En attente \134u2014 {0}\n' >> $FR
printf 'item.status.RUNNING=Copie\134u2026\n' >> $FR
printf 'item.status.RUNNING.percent=Copie {0} %%\n' >> $FR
printf 'item.status.DONE=Copi\134u00e9\n' >> $FR
printf 'item.status.SKIPPED=Ignor\134u00e9 \134u2014 {0}\n' >> $FR
printf 'item.status.ERROR=Erreur \134u2014 {0}\n' >> $FR
printf 'resume.dialog.title=Point de reprise\n' >> $FR
printf 'resume.dialog.all=Tout\n' >> $FR
printf 'resume.dialog.date=\134u00c0 partir d\047\047une date\n' >> $FR
printf 'resume.dialog.file=\134u00c0 partir d\047\047un fichier du plan\n' >> $FR
printf 'editor.title=\134u00c9diteur de pipeline \134u2014 {0}\n' >> $FR
printf 'editor.untitled=nouveau pipeline\n' >> $FR
printf 'editor.section.PIPELINE=Pipeline\n' >> $FR
printf 'editor.section.IN=Entr\134u00e9es\n' >> $FR
printf 'editor.section.ANALYZE=Analyses\n' >> $FR
printf 'editor.section.PROCESS=Traitements\n' >> $FR
printf 'editor.section.OUT=Sortie\n' >> $FR
printf 'editor.add=Ajouter\n' >> $FR
printf 'editor.up=Monter\n' >> $FR
printf 'editor.down=Descendre\n' >> $FR
printf 'editor.remove=Supprimer\n' >> $FR
printf 'editor.save=Enregistrer\n' >> $FR
printf 'editor.save-as=Enregistrer sous\134u2026\n' >> $FR
printf 'editor.show-json=Voir le JSON\n' >> $FR
printf 'editor.startProcessingWhileListing=Traiter pendant le listing\n' >> $FR
printf 'editor.resume-mode=Mode de reprise\n' >> $FR
printf 'editor.autoExecute=Ex\134u00e9cution automatique\n' >> $FR
printf 'editor.advanced=Avanc\134u00e9\n' >> $FR
printf 'editor.advanced.maxConcurrency=Concurrence maximale\n' >> $FR
printf 'editor.advanced.maxConcurrency.description=Nombre de fichiers trait\134u00e9s en m\134u00eame temps par cette \134u00e9tape\n' >> $FR
printf 'editor.advanced.resources=Ressources\n' >> $FR
printf 'editor.advanced.resources.description=Ressources suppl\134u00e9mentaires consomm\134u00e9es par cette \134u00e9tape, une par ligne (ex. gpu)\n' >> $FR
printf 'editor.advanced.priority=Priorit\134u00e9\n' >> $FR
printf 'editor.advanced.priority.description=Une \134u00e9tape plus prioritaire obtient en premier les ressources partag\134u00e9es\n' >> $FR
printf 'editor.advanced.version=Version du plugin\n' >> $FR
printf 'editor.advanced.version.description=La version du plugin \134u00e0 utiliser quand plusieurs sont charg\134u00e9es (ex. 1.2)\n' >> $FR
printf 'editor.plugin-not-found=Plugin introuvable : {0} (cette \134u00e9tape est conserv\134u00e9e telle quelle)\n' >> $FR
printf 'editor.no-schema=Cette action ne d\134u00e9crit pas sa configuration : son JSON est conserv\134u00e9 tel quel\n' >> $FR
printf 'editor.default=d\134u00e9faut : {0}\n' >> $FR
printf 'editor.browse=Parcourir\134u2026\n' >> $FR
printf 'editor.pattern-variables=Variables : {0}\n' >> $FR
printf 'editor.add.title=Ajouter une \134u00e9tape\n' >> $FR
printf 'editor.add.empty=Aucune action charg\134u00e9e ne convient \134u00e0 cette section\n' >> $FR
printf 'editor.required-missing=Des champs obligatoires sont vides :\134n{0}\n' >> $FR
printf 'editor.invalid-value=Valeur invalide : {0}\n' >> $FR
printf 'editor.json.title=JSON du pipeline\n' >> $FR
printf 'editor.discard=Abandonner les modifications non enregistr\134u00e9es ?\n' >> $FR
```

Contrôles :

```bash
EN=copybot-ui/src/main/resources/com/copybot/ui/i18n/uiBundle.properties
FR=copybot-ui/src/main/resources/com/copybot/ui/i18n/uiBundle_fr.properties
file $EN $FR
grep -c $'\xc3' $FR
grep -c $'\xef\xbf\xbd' $FR
grep -c '' $EN $FR
grep -n 'RUNNING.percent\|required-missing\|resume-from-here' $FR
git diff --stat $EN $FR
```

Expected : `UTF-8 Unicode text` pour les deux (pas de `CRLF`) ; `5` (les accents existants, inchangés) ; `0` ; `120` lignes chacun ; `Copie {0} %`, `vides :\n{0}` (un `\n` littéral), `partir d''ici` ; `100 insertions(+)` par fichier, aucune suppression.

- [ ] **Step 4: Run the tests**

Run: `mvn -o -q -pl copybot-engine,copybot-ui test`
Expected: PASS (dont `UiBundleTest` : 3 tests).

- [ ] **Step 5: Commit**

```bash
git ls-files --eol copybot-ui/src/main/resources/com/copybot/ui/i18n/uiBundle.properties copybot-ui/src/main/resources/com/copybot/ui/i18n/uiBundle_fr.properties
git add copybot-ui/src/main/resources/com/copybot/ui/i18n/uiBundle.properties copybot-ui/src/main/resources/com/copybot/ui/i18n/uiBundle_fr.properties copybot-ui/src/test/java/com/copybot/ui/model/UiBundleTest.java
git commit -m "Add the desktop UI texts to the UI bundles" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

(Les deux : `w/lf`.)

---

### Task 9: `PipelineDocument` — chargement, enregistrement, champs du pipeline

**Files:**
- Create: `copybot-ui/src/main/java/com/copybot/ui/model/PipelineDocument.java`
- Create: `copybot-ui/src/test/java/com/copybot/ui/model/PipelineDocumentTest.java`

**Interfaces:**
- Consumes: `CopybotException.ofResource`, clés `pipeline.not-found` / `pipeline.not-json` (moteur), `StepType`.
- Produces: `public final class PipelineDocument` : `enum Section { IN("inSteps"), ANALYZE("analyseSteps"), PROCESS("actionSteps"), OUT("outStep") }` avec `jsonName()` et `stepType()` ; `static final List<String> RESUME_MODES` ; `static PipelineDocument empty()`, `static PipelineDocument load(Path)` (`CopybotException`), `static PipelineDocument parse(String)` (`IllegalArgumentException`), `String toJson()`, `void save(Path) throws IOException`, `boolean isModified()` ; `boolean startProcessingWhileListing()` / `setStartProcessingWhileListing(boolean)`, `boolean autoExecute()` / `setAutoExecute(boolean)`, `Optional<String> resumeMode()` / `setResumeMode(String)` ; `List<JsonObject> steps(Section)` (objets vivants).

- [ ] **Step 1: Write the failing test**

`copybot-ui/src/test/java/com/copybot/ui/model/PipelineDocumentTest.java` :

```java
package com.copybot.ui.model;

import com.copybot.exception.CopybotException;
import com.copybot.ui.model.PipelineDocument.Section;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** The pipeline being edited: a JSON tree of which only the known fields are rewritten (spec desktop-ui §3). */
public class PipelineDocumentTest {

    @TempDir
    Path tempDir;

    static final String PIPELINE = """
            {
              "comment": "kept as is",
              "inSteps": [
                { "action": "file.read", "filterCondition": "size > 0",
                  "actionConfig": { "path": "D:/DCIM", "future": { "x": 1 } } }
              ],
              "analyseSteps": [
                { "plugin": "com.missing", "action": "faces", "actionConfig": { "model": "big" } }
              ],
              "outStep": { "action": "file.write", "actionConfig": { "outPattern": "nas/{name}", "bufferSize": 8 } },
              "resume": { "mode": "state" },
              "ui": { "theme": "dark" }
            }
            """;

    private static JsonObject tree(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    // ---- load / save ----

    @Test
    public void savingAnUntouchedDocumentKeepsEveryMember() throws IOException {
        Path file = Files.writeString(tempDir.resolve("p.json"), PIPELINE);
        PipelineDocument document = PipelineDocument.load(file);

        document.save(file);

        assertFalse(document.isModified());
        assertEquals(tree(PIPELINE), tree(Files.readString(file)));
    }

    @Test
    public void theSavedJsonIsPrettyAndKeepsSpecialCharacters() {
        PipelineDocument document = PipelineDocument.parse(
                "{\"outStep\":{\"action\":\"file.write\",\"actionConfig\":{\"outPattern\":\"<nas>/{name}='x'\"}}}");

        String json = document.toJson();

        assertTrue(json.contains("<nas>/{name}='x'"), json);
        assertTrue(json.contains("\n  \"outStep\""), json);
        assertTrue(json.endsWith("}\n"), json);
    }

    @Test
    public void anUnreadablePipelineIsRefused() throws IOException {
        assertThrows(CopybotException.class, () -> PipelineDocument.load(tempDir.resolve("missing.json")));
        for (String json : List.of("not json {", "[1, 2]", "{\"inSteps\": [1]}", "{\"inSteps\": {}}", "{\"outStep\": []}")) {
            Path file = Files.writeString(tempDir.resolve("bad.json"), json);
            assertThrows(CopybotException.class, () -> PipelineDocument.load(file), json);
        }
    }

    @Test
    public void aNewDocumentHasNoStep() {
        PipelineDocument document = PipelineDocument.empty();

        for (Section section : Section.values()) {
            assertEquals(List.of(), document.steps(section));
        }
        assertEquals("{}\n", document.toJson());
        assertFalse(document.isModified());
    }

    @Test
    public void theStepsAreTheObjectsOfTheirSection() {
        PipelineDocument document = PipelineDocument.parse(PIPELINE);

        assertEquals("file.read", document.steps(Section.IN).getFirst().get("action").getAsString());
        assertEquals("faces", document.steps(Section.ANALYZE).getFirst().get("action").getAsString());
        assertEquals(List.of(), document.steps(Section.PROCESS));
        assertEquals("file.write", document.steps(Section.OUT).getFirst().get("action").getAsString());
    }

    // ---- pipeline fields ----

    @Test
    public void autoExecuteLivesInTheUiBlock() {
        PipelineDocument document = PipelineDocument.parse(PIPELINE);
        assertFalse(document.autoExecute());

        document.setAutoExecute(true);
        assertTrue(document.autoExecute());
        assertTrue(document.isModified());

        document.setAutoExecute(false);
        assertEquals(tree("{\"theme\":\"dark\"}"), tree(document.toJson()).getAsJsonObject("ui"), "the other ui members stay");

        PipelineDocument bare = PipelineDocument.empty();
        bare.setAutoExecute(true);
        bare.setAutoExecute(false);
        assertEquals("{}\n", bare.toJson(), "an emptied ui block is removed");
    }

    @Test
    public void startProcessingWhileListingIsWrittenOnlyWhenTrue() {
        PipelineDocument document = PipelineDocument.empty();

        document.setStartProcessingWhileListing(false);
        assertFalse(document.isModified(), "false is the default");

        document.setStartProcessingWhileListing(true);
        assertTrue(document.startProcessingWhileListing());
        assertTrue(tree(document.toJson()).get("startProcessingWhileListing").getAsBoolean());
    }

    @Test
    public void theResumeModeIsTheEffectiveOne() {
        assertEquals(Optional.empty(), PipelineDocument.empty().resumeMode());
        assertEquals(Optional.of("stateThenDestination"), PipelineDocument.parse("{\"resume\":{}}").resumeMode());
        assertEquals(Optional.of("state"), PipelineDocument.parse(PIPELINE).resumeMode());
    }

    @Test
    public void noResumeRemovesTheBlockUnlessItHoldsOtherMembers() {
        PipelineDocument document = PipelineDocument.parse(PIPELINE);
        document.setResumeMode("destination");
        assertEquals("destination", tree(document.toJson()).getAsJsonObject("resume").get("mode").getAsString());

        document.setResumeMode(null);
        assertFalse(tree(document.toJson()).has("resume"));

        PipelineDocument other = PipelineDocument.parse("{\"resume\":{\"mode\":\"state\",\"keep\":1}}");
        other.setResumeMode(null);
        assertEquals(tree("{\"mode\":\"none\",\"keep\":1}"), tree(other.toJson()).getAsJsonObject("resume"));
        assertThrows(IllegalArgumentException.class, () -> other.setResumeMode("sometimes"));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -o -q -pl copybot-engine,copybot-ui test -Dtest=PipelineDocumentTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — compilation `cannot find symbol: class PipelineDocument`.

- [ ] **Step 3: Implement**

`copybot-ui/src/main/java/com/copybot/ui/model/PipelineDocument.java` :

```java
package com.copybot.ui.model;

import com.copybot.engine.pipeline.StepType;
import com.copybot.exception.CopybotException;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A pipeline file being edited (spec desktop-ui §3): its JSON tree, of which only the fields the editor
 * knows are rewritten; every other member (unknown fields, steps of a plugin not loaded, fields outside the
 * schema) is kept as is. Not thread-safe: used from the JavaFX thread.
 */
public final class PipelineDocument {

    /** The sections of a pipeline, in the order of the editor tree. */
    public enum Section {
        IN("inSteps", StepType.IN),
        ANALYZE("analyseSteps", StepType.ANALYZE),
        PROCESS("actionSteps", StepType.PROCESS),
        /** at most one step, the "outStep" object */
        OUT("outStep", StepType.OUT);

        private final String jsonName;
        private final StepType stepType;

        Section(String jsonName, StepType stepType) {
            this.jsonName = jsonName;
            this.stepType = stepType;
        }

        public String jsonName() {
            return jsonName;
        }

        public StepType stepType() {
            return stepType;
        }
    }

    /** The resume modes, as written in the pipeline ("resume.mode"). */
    public static final List<String> RESUME_MODES = List.of("none", "state", "destination", "stateThenDestination");

    /** The mode of a "resume" block without mode (see {@code ResumeConfig.effectiveMode}). */
    private static final String DEFAULT_RESUME_MODE = "stateThenDestination";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final JsonObject root;
    private boolean modified;

    private PipelineDocument(JsonObject root) {
        this.root = root;
    }

    /** A new pipeline: no step. */
    public static PipelineDocument empty() {
        return new PipelineDocument(new JsonObject());
    }

    /** @throws CopybotException pipeline.not-found, pipeline.not-json (also for a step that is not an object) */
    public static PipelineDocument load(Path path) {
        if (!Files.isReadable(path)) {
            throw CopybotException.ofResource("pipeline.not-found", path);
        }
        try {
            return parse(Files.readString(path));
        } catch (IOException | IllegalArgumentException e) {
            throw CopybotException.ofResource(e, "pipeline.not-json", path);
        }
    }

    /** @throws IllegalArgumentException not a JSON object, or a section that is not made of objects */
    public static PipelineDocument parse(String json) {
        JsonElement tree;
        try {
            tree = JsonParser.parseString(json);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        if (!tree.isJsonObject()) {
            throw new IllegalArgumentException("not a JSON object");
        }
        JsonObject root = tree.getAsJsonObject();
        for (Section section : Section.values()) {
            JsonElement value = root.get(section.jsonName());
            boolean valid = value == null || value.isJsonNull()
                    || (section == Section.OUT ? value.isJsonObject() : isArrayOfObjects(value));
            if (!valid) {
                throw new IllegalArgumentException("\"" + section.jsonName() + "\" is not made of step objects");
            }
        }
        return new PipelineDocument(root);
    }

    private static boolean isArrayOfObjects(JsonElement value) {
        if (!value.isJsonArray()) {
            return false;
        }
        for (JsonElement element : value.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                return false;
            }
        }
        return true;
    }

    /** Pretty-printed JSON, what {@link #save} writes. */
    public String toJson() {
        return GSON.toJson(root) + "\n";
    }

    public void save(Path path) throws IOException {
        Files.writeString(path, toJson());
        modified = false;
    }

    /** Something was changed since the load or the last save. */
    public boolean isModified() {
        return modified;
    }

    // ---- pipeline ----

    public boolean startProcessingWhileListing() {
        return bool(root, "startProcessingWhileListing");
    }

    /** true is written; false removes a member that was true (false is the engine default). */
    public void setStartProcessingWhileListing(boolean value) {
        if (value == startProcessingWhileListing()) {
            return;
        }
        if (value) {
            root.addProperty("startProcessingWhileListing", true);
        } else {
            root.remove("startProcessingWhileListing");
        }
        modified = true;
    }

    /** "ui.autoExecute" (spec desktop-ui §2), false when absent. */
    public boolean autoExecute() {
        JsonElement ui = root.get("ui");
        return ui != null && ui.isJsonObject() && bool(ui.getAsJsonObject(), "autoExecute");
    }

    /** true is written; false removes it, and the "ui" block when nothing else is left in it. */
    public void setAutoExecute(boolean value) {
        if (value == autoExecute()) {
            return;
        }
        JsonElement ui = root.get("ui");
        if (value) {
            if (ui == null || !ui.isJsonObject()) {
                ui = new JsonObject();
                root.add("ui", ui);
            }
            ui.getAsJsonObject().addProperty("autoExecute", true);
        } else {
            JsonObject uiObject = ui.getAsJsonObject();
            uiObject.remove("autoExecute");
            if (uiObject.isEmpty()) {
                root.remove("ui");
            }
        }
        modified = true;
    }

    /** The effective resume mode: empty without "resume" block, stateThenDestination for a block without mode. */
    public Optional<String> resumeMode() {
        JsonElement resume = root.get("resume");
        if (resume == null || !resume.isJsonObject()) {
            return Optional.empty();
        }
        JsonElement mode = resume.getAsJsonObject().get("mode");
        return Optional.of(mode != null && mode.isJsonPrimitive() ? mode.getAsString() : DEFAULT_RESUME_MODE);
    }

    /**
     * @param mode one of {@link #RESUME_MODES}, null for no resume: the block is removed, unless it holds
     *             other members (then its mode becomes "none")
     */
    public void setResumeMode(String mode) {
        if (Objects.equals(mode, resumeMode().orElse(null))) {
            return;
        }
        JsonElement resume = root.get("resume");
        if (mode == null) {
            JsonObject block = resume.getAsJsonObject();
            block.remove("mode");
            if (block.isEmpty()) {
                root.remove("resume");
            } else {
                block.addProperty("mode", "none");
            }
        } else {
            if (!RESUME_MODES.contains(mode)) {
                throw new IllegalArgumentException(mode);
            }
            if (resume == null || !resume.isJsonObject()) {
                resume = new JsonObject();
                root.add("resume", resume);
            }
            resume.getAsJsonObject().addProperty("mode", mode);
        }
        modified = true;
    }

    private static boolean bool(JsonObject object, String member) {
        JsonElement value = object.get(member);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean();
    }

    // ---- steps ----

    /** The step objects of the section, in order (live: changing one changes the document). */
    public List<JsonObject> steps(Section section) {
        JsonElement value = root.get(section.jsonName());
        if (value == null || value.isJsonNull()) {
            return List.of();
        }
        if (section == Section.OUT) {
            return List.of(value.getAsJsonObject());
        }
        List<JsonObject> steps = new ArrayList<>();
        value.getAsJsonArray().forEach(element -> steps.add(element.getAsJsonObject()));
        return steps;
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `mvn -o -q -pl copybot-engine,copybot-ui test -Dtest=PipelineDocumentTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (9 tests).

- [ ] **Step 5: Commit**

```bash
git add copybot-ui/src/main/java/com/copybot/ui/model/PipelineDocument.java copybot-ui/src/test/java/com/copybot/ui/model/PipelineDocumentTest.java
git commit -m "Edit a pipeline as a JSON tree that keeps its unknown members" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 10: `StepCatalog` — actions d'une section, résolution d'une étape

**Files:**
- Create: `copybot-ui/src/main/java/com/copybot/ui/model/StepCatalog.java`
- Create: `copybot-ui/src/test/java/com/copybot/ui/model/TestCatalog.java`
- Create: `copybot-ui/src/test/java/com/copybot/ui/model/StepCatalogTest.java`

**Interfaces:**
- Consumes: `CatalogAction` (4), `ConfigSchema` (1), `PipelineDocument.Section` (9), `VersionUtil.isCompatible` (existant).
- Produces: `public final class StepCatalog(List<CatalogAction>)` : `List<CatalogAction> forSection(Section)` (plus récente version de chaque plugin), `Optional<CatalogAction> find(Section, JsonObject step)` ; fixtures de test `TestCatalog.READ` (embarqué, IN), `WRITE` (embarqué, OUT), `EXIF_2` / `EXIF_1` (`com.acme.exif` 2.1.0 / 1.4.0, ANALYZE), `CATALOG`, records `ReadConfig(@Required @DirectoryPath path, recursive, include)`, `Conflict(@Required compare, ifIdentical)`, `WriteConfig(@Required @PatternField outPattern, onConflict, bufferSize)`.

- [ ] **Step 1: Write the failing test**

`copybot-ui/src/test/java/com/copybot/ui/model/TestCatalog.java` :

```java
package com.copybot.ui.model;

import com.copybot.engine.pipeline.StepType;
import com.copybot.engine.plugin.CatalogAction;
import com.copybot.plugin.api.config.ConfigSchema;
import com.copybot.plugin.api.config.DirectoryPath;
import com.copybot.plugin.api.config.PatternField;
import com.copybot.plugin.api.config.Required;

import java.util.List;
import java.util.Map;

/** Hand-made catalog actions for the model tests: no plugin is loaded. */
final class TestCatalog {

    private TestCatalog() {
    }

    record ReadConfig(@Required @DirectoryPath String path, Boolean recursive, List<String> include) {
    }

    record Conflict(@Required String compare, String ifIdentical) {
    }

    record WriteConfig(@Required @PatternField String outPattern, Conflict onConflict, Integer bufferSize) {
    }

    record ExifConfig(Boolean gps) {
    }

    static final CatalogAction READ = action(CatalogAction.EMBEDDED_PLUGIN, null, "file.read", StepType.IN, ReadConfig.class);
    static final CatalogAction WRITE = action(CatalogAction.EMBEDDED_PLUGIN, null, "file.write", StepType.OUT, WriteConfig.class);
    static final CatalogAction EXIF_2 = action("com.acme.exif", "2.1.0", "exif.read", StepType.ANALYZE, ExifConfig.class);
    static final CatalogAction EXIF_1 = action("com.acme.exif", "1.4.0", "exif.read", StepType.ANALYZE, ExifConfig.class);

    /** In PluginEngine order: plugin name, most recent version first. */
    static final StepCatalog CATALOG = new StepCatalog(List.of(EXIF_2, EXIF_1, READ, WRITE));

    static CatalogAction action(String plugin, String version, String code, StepType type, Class<? extends Record> config) {
        String prefix = "plugin." + plugin + "." + code;
        return new CatalogAction(plugin, plugin, version, code, type, code + " name", code + " description",
                ConfigSchema.of(config).withKeyPrefix(prefix), Map.of());
    }
}
```

`copybot-ui/src/test/java/com/copybot/ui/model/StepCatalogTest.java` :

```java
package com.copybot.ui.model;

import com.copybot.ui.model.PipelineDocument.Section;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static com.copybot.ui.model.TestCatalog.CATALOG;
import static com.copybot.ui.model.TestCatalog.EXIF_1;
import static com.copybot.ui.model.TestCatalog.EXIF_2;
import static com.copybot.ui.model.TestCatalog.READ;
import static com.copybot.ui.model.TestCatalog.WRITE;
import static org.junit.jupiter.api.Assertions.*;

/** Which actions a section offers and which one a step resolves to (spec desktop-ui §3). */
public class StepCatalogTest {

    private static JsonObject step(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    public void aSectionOffersTheActionsOfItsStepTypeInTheirMostRecentVersion() {
        assertEquals(List.of(READ), CATALOG.forSection(Section.IN));
        assertEquals(List.of(EXIF_2), CATALOG.forSection(Section.ANALYZE));
        assertEquals(List.of(), CATALOG.forSection(Section.PROCESS));
        assertEquals(List.of(WRITE), CATALOG.forSection(Section.OUT));
    }

    @Test
    public void aStepWithoutPluginIsAnEmbeddedAction() {
        assertEquals(Optional.of(READ), CATALOG.find(Section.IN, step("{\"action\":\"file.read\"}")));
        assertEquals(Optional.of(READ), CATALOG.find(Section.IN, step("{\"plugin\":\" \",\"action\":\"file.read\"}")));
        assertEquals(Optional.of(READ), CATALOG.find(Section.IN, step("{\"plugin\":\"embedded\",\"action\":\"file.read\"}")));
    }

    @Test
    public void theVersionPicksTheCompatiblePlugin() {
        assertEquals(Optional.of(EXIF_2), CATALOG.find(Section.ANALYZE, step("{\"plugin\":\"com.acme.exif\",\"action\":\"exif.read\"}")));
        assertEquals(Optional.of(EXIF_1), CATALOG.find(Section.ANALYZE,
                step("{\"plugin\":\"com.acme.exif\",\"action\":\"exif.read\",\"version\":\"1.4\"}")));
        assertEquals(Optional.empty(), CATALOG.find(Section.ANALYZE,
                step("{\"plugin\":\"com.acme.exif\",\"action\":\"exif.read\",\"version\":\"3.0\"}")));
    }

    @Test
    public void anUnknownPluginActionOrSectionFindsNothing() {
        assertEquals(Optional.empty(), CATALOG.find(Section.ANALYZE, step("{\"plugin\":\"com.other\",\"action\":\"exif.read\"}")));
        assertEquals(Optional.empty(), CATALOG.find(Section.IN, step("{\"action\":\"file.list\"}")));
        assertEquals(Optional.empty(), CATALOG.find(Section.OUT, step("{\"action\":\"file.read\"}")), "file.read is an input");
        assertEquals(Optional.empty(), CATALOG.find(Section.IN, step("{}")));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -o -q -pl copybot-engine,copybot-ui test -Dtest=StepCatalogTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — compilation `cannot find symbol: class StepCatalog`.

- [ ] **Step 3: Implement**

`copybot-ui/src/main/java/com/copybot/ui/model/StepCatalog.java` :

```java
package com.copybot.ui.model;

import com.copybot.engine.plugin.CatalogAction;
import com.copybot.utils.VersionUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The loaded actions as the pipeline editor uses them (spec desktop-ui §3): what a section can add, and
 * which action a step of the pipeline resolves to, with the engine's rules.
 */
public final class StepCatalog {

    private final List<CatalogAction> actions;

    /** @param actions in the order of {@code PluginEngine.catalog()}: most recent plugin version first */
    public StepCatalog(List<CatalogAction> actions) {
        this.actions = List.copyOf(actions);
    }

    /** The actions a step of this section can use, the most recent version of each plugin only. */
    public List<CatalogAction> forSection(PipelineDocument.Section section) {
        List<CatalogAction> offered = new ArrayList<>();
        for (CatalogAction action : actions) {
            boolean newerOffered = offered.stream().anyMatch(o -> o.pluginName().equals(action.pluginName())
                    && o.actionCode().equals(action.actionCode()) && o.stepType() == action.stepType());
            if (action.stepType() == section.stepType() && !newerOffered) {
                offered.add(action);
            }
        }
        return List.copyOf(offered);
    }

    /**
     * The action this step resolves to, like {@code PluginEngine.resolve}: its plugin ("embedded" when
     * absent or blank), the action code in the section's step type, the most recent version compatible
     * with its "version". Empty when the plugin or the action is not loaded: the step is read-only.
     */
    public Optional<CatalogAction> find(PipelineDocument.Section section, JsonObject step) {
        String plugin = text(step, "plugin");
        String pluginName = plugin == null || plugin.isBlank() ? CatalogAction.EMBEDDED_PLUGIN : plugin;
        String action = text(step, "action");
        String version = text(step, "version");
        return actions.stream()
                .filter(a -> a.stepType() == section.stepType())
                .filter(a -> a.pluginName().equals(pluginName) && a.actionCode().equals(action))
                .filter(a -> a.pluginVersion() == null || VersionUtil.isCompatible(a.pluginVersion(), version, true))
                .findFirst();
    }

    private static String text(JsonObject object, String member) {
        JsonElement element = object.get(member);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `mvn -o -q -pl copybot-engine,copybot-ui test`
Expected: PASS (dont `StepCatalogTest` : 4 tests).

- [ ] **Step 5: Commit**

```bash
git add copybot-ui/src/main/java/com/copybot/ui/model/StepCatalog.java copybot-ui/src/test/java/com/copybot/ui/model/TestCatalog.java copybot-ui/src/test/java/com/copybot/ui/model/StepCatalogTest.java
git commit -m "Resolve pipeline steps against the catalog like the engine" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 11: `PipelineDocument` — ajouter, déplacer, supprimer des étapes

**Files:**
- Modify: `copybot-ui/src/main/java/com/copybot/ui/model/PipelineDocument.java`
- Modify: `copybot-ui/src/test/java/com/copybot/ui/model/PipelineDocumentTest.java`

**Interfaces:**
- Consumes: `CatalogAction` (4), `TestCatalog` (10).
- Produces: `boolean canAdd(Section)` (la sortie : au plus une étape), `JsonObject addStep(Section, CatalogAction)` (`plugin` omis pour une action embarquée, `action`, `actionConfig` vide ; `IllegalStateException` si la sortie est pleine), `boolean canMove(Section, int index, int delta)`, `void moveStep(Section, int index, int delta)` (sans effet s'il ne peut pas), `void removeStep(Section, int index)` (`IndexOutOfBoundsException` ; une liste vidée reste dans le fichier, `outStep` est retiré).

- [ ] **Step 1: Write the failing tests**

Dans `PipelineDocumentTest.java`, remplacer

```java
import static org.junit.jupiter.api.Assertions.*;
```

par

```java
import static com.copybot.ui.model.TestCatalog.EXIF_2;
import static com.copybot.ui.model.TestCatalog.READ;
import static com.copybot.ui.model.TestCatalog.WRITE;
import static org.junit.jupiter.api.Assertions.*;
```

puis remplacer la fin de la classe

```java
        assertThrows(IllegalArgumentException.class, () -> other.setResumeMode("sometimes"));
    }
}
```

par

```java
        assertThrows(IllegalArgumentException.class, () -> other.setResumeMode("sometimes"));
    }

    // ---- steps ----

    @Test
    public void anAddedStepNamesItsPluginUnlessEmbedded() {
        PipelineDocument document = PipelineDocument.empty();

        document.addStep(Section.IN, READ);
        document.addStep(Section.ANALYZE, EXIF_2);

        assertEquals(tree("{\"action\":\"file.read\",\"actionConfig\":{}}"), document.steps(Section.IN).getFirst());
        assertEquals(tree("{\"plugin\":\"com.acme.exif\",\"action\":\"exif.read\",\"actionConfig\":{}}"),
                document.steps(Section.ANALYZE).getFirst());
        assertTrue(document.isModified());
    }

    @Test
    public void theOutputHoldsOneStep() {
        PipelineDocument document = PipelineDocument.empty();
        assertTrue(document.canAdd(Section.OUT));

        document.addStep(Section.OUT, WRITE);

        assertFalse(document.canAdd(Section.OUT));
        assertThrows(IllegalStateException.class, () -> document.addStep(Section.OUT, WRITE));
        assertTrue(tree(document.toJson()).get("outStep").isJsonObject());
        document.removeStep(Section.OUT, 0);
        assertTrue(document.canAdd(Section.OUT));
        assertFalse(tree(document.toJson()).has("outStep"));
    }

    @Test
    public void stepsMoveWithinTheirSection() {
        PipelineDocument document = PipelineDocument.parse(
                "{\"inSteps\":[{\"action\":\"a\"},{\"action\":\"b\"},{\"action\":\"c\"}],\"after\":true}");

        document.moveStep(Section.IN, 2, -1);
        assertEquals(List.of("a", "c", "b"), actions(document));
        document.moveStep(Section.IN, 0, 1);
        assertEquals(List.of("c", "a", "b"), actions(document));

        assertFalse(document.canMove(Section.IN, 0, -1));
        assertFalse(document.canMove(Section.IN, 2, 1));
        document.moveStep(Section.IN, 0, -1);
        assertEquals(List.of("c", "a", "b"), actions(document), "no effect at the top");
        assertEquals(List.of("inSteps", "after"), List.copyOf(tree(document.toJson()).keySet()), "the list keeps its place");
    }

    @Test
    public void aRemovedStepLeavesItsListInTheFile() {
        PipelineDocument document = PipelineDocument.parse(PIPELINE);

        document.removeStep(Section.ANALYZE, 0);

        assertEquals(List.of(), document.steps(Section.ANALYZE));
        assertTrue(tree(document.toJson()).getAsJsonArray("analyseSteps").isEmpty());
        assertThrows(IndexOutOfBoundsException.class, () -> document.removeStep(Section.ANALYZE, 0));
    }

    private static List<String> actions(PipelineDocument document) {
        return document.steps(Section.IN).stream().map(s -> s.get("action").getAsString()).toList();
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -o -q -pl copybot-engine,copybot-ui test -Dtest=PipelineDocumentTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — compilation `cannot find symbol: method addStep(…)` / `canAdd` / `moveStep` / `canMove` / `removeStep`.

- [ ] **Step 3: Implement**

Dans `PipelineDocument.java` (Edit), remplacer

```java
import com.copybot.engine.pipeline.StepType;
```

par

```java
import com.copybot.engine.pipeline.StepType;
import com.copybot.engine.plugin.CatalogAction;
```

remplacer

```java
import com.google.gson.GsonBuilder;
```

par

```java
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
```

puis remplacer la fin de la classe

```java
        value.getAsJsonArray().forEach(element -> steps.add(element.getAsJsonObject()));
        return steps;
    }
}
```

par

```java
        value.getAsJsonArray().forEach(element -> steps.add(element.getAsJsonObject()));
        return steps;
    }

    /** The output holds at most one step (spec desktop-ui §3). */
    public boolean canAdd(Section section) {
        return section != Section.OUT || steps(Section.OUT).isEmpty();
    }

    /**
     * Adds a step of this action at the end of the section: "plugin" (left out for an embedded action),
     * "action" and an empty "actionConfig".
     *
     * @throws IllegalStateException the output already has a step
     */
    public JsonObject addStep(Section section, CatalogAction action) {
        if (!canAdd(section)) {
            throw new IllegalStateException("the output already has a step");
        }
        JsonObject step = new JsonObject();
        if (!action.isEmbedded()) {
            step.addProperty("plugin", action.pluginName());
        }
        step.addProperty("action", action.actionCode());
        step.add("actionConfig", new JsonObject());
        if (section == Section.OUT) {
            root.add(section.jsonName(), step);
        } else {
            JsonElement value = root.get(section.jsonName());
            if (value == null || value.isJsonNull()) {
                value = new JsonArray();
                root.add(section.jsonName(), value);
            }
            value.getAsJsonArray().add(step);
        }
        modified = true;
        return step;
    }

    /** The output step never moves; a list step moves within its list. */
    public boolean canMove(Section section, int index, int delta) {
        int size = steps(section).size();
        int target = index + delta;
        return section != Section.OUT && index >= 0 && index < size && target >= 0 && target < size;
    }

    /** Moves the step by delta places (-1: up, +1: down); no effect when it cannot move. */
    public void moveStep(Section section, int index, int delta) {
        if (delta == 0 || !canMove(section, index, delta)) {
            return;
        }
        JsonArray array = root.getAsJsonArray(section.jsonName());
        JsonElement step = array.remove(index);
        List<JsonElement> elements = new ArrayList<>(array.asList());
        elements.add(index + delta, step);
        JsonArray moved = new JsonArray();
        elements.forEach(moved::add);
        root.add(section.jsonName(), moved); // same key: keeps its place among the members
        modified = true;
    }

    /** An emptied list stays in the file; the output step is removed with its "outStep" member. */
    public void removeStep(Section section, int index) {
        if (index < 0 || index >= steps(section).size()) {
            throw new IndexOutOfBoundsException(index);
        }
        if (section == Section.OUT) {
            root.remove(section.jsonName());
        } else {
            root.getAsJsonArray(section.jsonName()).remove(index);
        }
        modified = true;
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `mvn -o -q -pl copybot-engine,copybot-ui test -Dtest=PipelineDocumentTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (13 tests).

- [ ] **Step 5: Commit**

```bash
git add copybot-ui/src/main/java/com/copybot/ui/model/PipelineDocument.java copybot-ui/src/test/java/com/copybot/ui/model/PipelineDocumentTest.java
git commit -m "Add, move and remove pipeline steps in the document" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 12: `PipelineDocument` — champs de configuration, « Avancé », validation des requis

**Files:**
- Modify: `copybot-ui/src/main/java/com/copybot/ui/model/PipelineDocument.java`
- Modify: `copybot-ui/src/test/java/com/copybot/ui/model/PipelineDocumentTest.java`

**Interfaces:**
- Consumes: `ConfigField`, `FieldKind` (1), `StepCatalog` (10), `TestCatalog` (10).
- Produces: `record PipelineDocument.Problem(Section section, int index, String fieldPath)` ; `static final List<ConfigField> ADVANCED_FIELDS` (`maxConcurrency` INTEGER, `resources` LIST de STRING, `priority` INTEGER, `version` STRING ; clés `editor.advanced.<nom>` / `.description`) ; `String configText(JsonObject step, ConfigField)`, `void setConfigText(JsonObject step, ConfigField, String)` (`IllegalArgumentException` pour un nombre / booléen invalide ou un record), `String advancedText(JsonObject step, ConfigField)`, `void setAdvancedText(JsonObject step, ConfigField, String)`, `JsonElement configValue(JsonObject step, ConfigField)`, `static String json(JsonElement)`, `List<Problem> validate(StepCatalog)`.

- [ ] **Step 1: Write the failing tests**

Dans `PipelineDocumentTest.java`, remplacer

```java
import com.copybot.exception.CopybotException;
```

par

```java
import com.copybot.engine.plugin.CatalogAction;
import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.config.ConfigField;
import com.copybot.ui.model.PipelineDocument.Problem;
```

remplacer

```java
import static com.copybot.ui.model.TestCatalog.EXIF_2;
```

par

```java
import static com.copybot.ui.model.TestCatalog.CATALOG;
import static com.copybot.ui.model.TestCatalog.EXIF_2;
```

puis remplacer la fin de la classe

```java
    private static List<String> actions(PipelineDocument document) {
        return document.steps(Section.IN).stream().map(s -> s.get("action").getAsString()).toList();
    }
}
```

par

```java
    private static List<String> actions(PipelineDocument document) {
        return document.steps(Section.IN).stream().map(s -> s.get("action").getAsString()).toList();
    }

    // ---- fields ----

    private static ConfigField field(CatalogAction action, String path) {
        return action.configSchema().orElseThrow().field(path).orElseThrow();
    }

    @Test
    public void editingAKnownFieldKeepsTheUnknownOnes() throws IOException {
        Path file = Files.writeString(tempDir.resolve("p.json"), PIPELINE);
        PipelineDocument document = PipelineDocument.load(file);

        document.setConfigText(document.steps(Section.IN).getFirst(), field(READ, "path"), "E:/DCIM");
        document.save(file);

        JsonObject expected = tree(PIPELINE);
        expected.getAsJsonArray("inSteps").get(0).getAsJsonObject().getAsJsonObject("actionConfig")
                .addProperty("path", "E:/DCIM");
        assertEquals(expected, tree(Files.readString(file)));
    }

    @Test
    public void aFieldReadsAndWritesItsText() {
        PipelineDocument document = PipelineDocument.parse(PIPELINE);
        JsonObject out = document.steps(Section.OUT).getFirst();

        assertEquals("nas/{name}", document.configText(out, field(WRITE, "outPattern")));
        assertEquals("8", document.configText(out, field(WRITE, "bufferSize")));
        assertEquals("", document.configText(out, field(WRITE, "onConflict.compare")));

        document.setConfigText(out, field(WRITE, "bufferSize"), " 16 ");

        assertEquals(16, out.getAsJsonObject("actionConfig").get("bufferSize").getAsInt());
    }

    @Test
    public void theSameValueRewritesNothing() {
        PipelineDocument document = PipelineDocument.parse(PIPELINE);
        JsonObject out = document.steps(Section.OUT).getFirst();

        document.setConfigText(out, field(WRITE, "bufferSize"), "8");
        document.setConfigText(out, field(WRITE, "onConflict.compare"), "  ");

        assertFalse(document.isModified());
        assertEquals(tree(PIPELINE), tree(document.toJson()));
    }

    @Test
    public void aNestedFieldCreatesItsRecordAndAnEmptyOneIsRemoved() {
        PipelineDocument document = PipelineDocument.parse(PIPELINE);
        JsonObject out = document.steps(Section.OUT).getFirst();

        document.setConfigText(out, field(WRITE, "onConflict.compare"), "fullHash");
        assertEquals(tree("{\"compare\":\"fullHash\"}"), out.getAsJsonObject("actionConfig").getAsJsonObject("onConflict"));

        document.setConfigText(out, field(WRITE, "onConflict.compare"), "");
        assertFalse(out.getAsJsonObject("actionConfig").has("onConflict"));
        assertTrue(out.has("actionConfig"), "actionConfig itself stays");
    }

    @Test
    public void aStepWithoutActionConfigGetsOneOnItsFirstValue() {
        PipelineDocument document = PipelineDocument.parse("{\"inSteps\":[{\"action\":\"file.read\"}]}");
        JsonObject in = document.steps(Section.IN).getFirst();

        assertEquals("", document.configText(in, field(READ, "path")));
        assertFalse(in.has("actionConfig"), "reading creates nothing");
        document.setConfigText(in, field(READ, "path"), "D:/");
        assertEquals("D:/", in.getAsJsonObject("actionConfig").get("path").getAsString());
    }

    @Test
    public void booleansAndListsHaveTheirOwnText() {
        PipelineDocument document = PipelineDocument.parse(PIPELINE);
        JsonObject in = document.steps(Section.IN).getFirst();

        document.setConfigText(in, field(READ, "recursive"), "FALSE");
        document.setConfigText(in, field(READ, "include"), "**/*.NEF\n\n  **/*.jpg  \n");

        JsonObject config = in.getAsJsonObject("actionConfig");
        assertFalse(config.get("recursive").getAsBoolean());
        assertEquals(JsonParser.parseString("[\"**/*.NEF\",\"**/*.jpg\"]"), config.get("include"));
        assertEquals("**/*.NEF\n**/*.jpg", document.configText(in, field(READ, "include")));
    }

    @Test
    public void anInvalidValueIsRefusedAndChangesNothing() {
        PipelineDocument document = PipelineDocument.parse(PIPELINE);
        JsonObject in = document.steps(Section.IN).getFirst();
        JsonObject out = document.steps(Section.OUT).getFirst();

        assertThrows(IllegalArgumentException.class, () -> document.setConfigText(out, field(WRITE, "bufferSize"), "8 MB"));
        assertThrows(IllegalArgumentException.class, () -> document.setConfigText(in, field(READ, "recursive"), "yes"));
        assertThrows(IllegalArgumentException.class, () -> document.setConfigText(out, field(WRITE, "onConflict"), "x"));

        assertFalse(document.isModified());
    }

    @Test
    public void theAdvancedFieldsAreMembersOfTheStep() {
        PipelineDocument document = PipelineDocument.parse(PIPELINE);
        JsonObject in = document.steps(Section.IN).getFirst();
        ConfigField maxConcurrency = PipelineDocument.ADVANCED_FIELDS.getFirst();
        ConfigField resources = PipelineDocument.ADVANCED_FIELDS.get(1);

        document.setAdvancedText(in, maxConcurrency, "4");
        document.setAdvancedText(in, resources, "gpu\nnet:flickr");

        assertEquals(4, in.get("maxConcurrency").getAsInt());
        assertEquals(JsonParser.parseString("[\"gpu\",\"net:flickr\"]"), in.get("resources"));
        assertEquals("size > 0", in.get("filterCondition").getAsString(), "a member outside the editor stays");
        assertEquals("4", document.advancedText(in, maxConcurrency));
        assertEquals(List.of("maxConcurrency", "resources", "priority", "version"),
                PipelineDocument.ADVANCED_FIELDS.stream().map(ConfigField::name).toList());
    }

    @Test
    public void aValueNotEditedAsTextIsShownAsJson() {
        PipelineDocument document = PipelineDocument.parse(PIPELINE);
        JsonObject in = document.steps(Section.IN).getFirst();

        String json = PipelineDocument.json(in.getAsJsonObject("actionConfig").get("future"));

        assertEquals(tree("{\"x\":1}"), tree(json));
        assertEquals("", PipelineDocument.json(null));
        assertEquals(JsonParser.parseString("\"D:/DCIM\""), document.configValue(in, field(READ, "path")));
    }

    // ---- validation ----

    @Test
    public void emptyRequiredFieldsAreReported() {
        PipelineDocument document = PipelineDocument.empty();
        document.addStep(Section.IN, READ);
        document.addStep(Section.OUT, WRITE);

        assertEquals(List.of(new Problem(Section.IN, 0, "path"), new Problem(Section.OUT, 0, "outPattern")),
                document.validate(CATALOG));

        document.setConfigText(document.steps(Section.IN).getFirst(), field(READ, "path"), "D:/");
        document.setConfigText(document.steps(Section.OUT).getFirst(), field(WRITE, "outPattern"), "nas/{name}");
        assertEquals(List.of(), document.validate(CATALOG));
    }

    @Test
    public void aRequiredFieldOfARecordCountsOnlyWhenTheRecordIsThere() {
        PipelineDocument document = PipelineDocument.parse(PIPELINE);
        JsonObject out = document.steps(Section.OUT).getFirst();
        assertEquals(List.of(), document.validate(CATALOG), "no onConflict: nothing required");

        document.setConfigText(out, field(WRITE, "onConflict.ifIdentical"), "skip");

        assertEquals(List.of(new Problem(Section.OUT, 0, "onConflict.compare")), document.validate(CATALOG));
    }

    @Test
    public void stepsOfAMissingPluginAreNotValidatedButAStepNeedsAnAction() {
        PipelineDocument document = PipelineDocument.parse(
                "{\"analyseSteps\":[{\"plugin\":\"com.missing\",\"action\":\"faces\"},{\"plugin\":\"com.acme.exif\"}]}");

        assertEquals(List.of(new Problem(Section.ANALYZE, 1, "action")), document.validate(CATALOG));
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -o -q -pl copybot-engine,copybot-ui test -Dtest=PipelineDocumentTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — compilation `cannot find symbol: method setConfigText(…)` / `class Problem` / `variable ADVANCED_FIELDS`.

- [ ] **Step 3: Implement**

Dans `PipelineDocument.java` (Edit), remplacer

```java
import com.copybot.exception.CopybotException;
```

par

```java
import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.config.ConfigField;
import com.copybot.plugin.api.config.FieldKind;
```

remplacer `import com.google.gson.JsonParser;` par

```java
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
```

remplacer `import java.io.IOException;` par

```java
import java.io.IOException;
import java.math.BigDecimal;
```

remplacer `import java.util.List;` par

```java
import java.util.List;
import java.util.Locale;
```

remplacer `import java.util.Optional;` par

```java
import java.util.Optional;
import java.util.Set;
```

puis remplacer

```java
    private static final String DEFAULT_RESUME_MODE = "stateThenDestination";
```

par

```java
    private static final String DEFAULT_RESUME_MODE = "stateThenDestination";

    /** A required field left empty: the step at index of the section, the field path in its actionConfig. */
    public record Problem(Section section, int index, String fieldPath) {
    }

    /** The "Advanced" fields of every step (spec desktop-ui §3), members of the step object itself. */
    public static final List<ConfigField> ADVANCED_FIELDS = List.of(
            advanced("maxConcurrency", FieldKind.INTEGER, null),
            advanced("resources", FieldKind.LIST, advanced("resources", FieldKind.STRING, null)),
            advanced("priority", FieldKind.INTEGER, null),
            advanced("version", FieldKind.STRING, null));
```

et enfin remplacer la fin de la classe

```java
            root.getAsJsonArray(section.jsonName()).remove(index);
        }
        modified = true;
    }
}
```

par

```java
            root.getAsJsonArray(section.jsonName()).remove(index);
        }
        modified = true;
    }

    // ---- fields ----

    /** An advanced field: labels in the UI bundle, "editor.advanced.&lt;name&gt;" and ".description". */
    private static ConfigField advanced(String name, FieldKind kind, ConfigField element) {
        return new ConfigField(name, name, kind, false, null, Set.of(),
                "editor.advanced." + name, "editor.advanced." + name + ".description", List.of(), List.of(), element);
    }

    /** The text of a schema field of the step's actionConfig: "" when absent, one line per list element. */
    public String configText(JsonObject step, ConfigField field) {
        return text(get(step, configPath(field)), field);
    }

    /**
     * Sets a schema field of the step's actionConfig from its text: blank removes it (and the objects it
     * leaves empty inside actionConfig); the same value as now rewrites nothing.
     *
     * @throws IllegalArgumentException not a number, not a boolean, a record or a list of records
     */
    public void setConfigText(JsonObject step, ConfigField field, String text) {
        setText(step, configPath(field), field, text);
    }

    /** The text of one of the {@link #ADVANCED_FIELDS} of the step. */
    public String advancedText(JsonObject step, ConfigField field) {
        return text(get(step, List.of(field.name())), field);
    }

    public void setAdvancedText(JsonObject step, ConfigField field, String text) {
        setText(step, List.of(field.name()), field, text);
    }

    /** The JSON value of a schema field of the step's actionConfig, null when absent. */
    public JsonElement configValue(JsonObject step, ConfigField field) {
        return get(step, configPath(field));
    }

    /** Pretty JSON of any element (a step, a list of records...), for a read-only display; "" for null. */
    public static String json(JsonElement element) {
        return element == null ? "" : GSON.toJson(element);
    }

    private static List<String> configPath(ConfigField field) {
        List<String> path = new ArrayList<>();
        path.add("actionConfig");
        path.addAll(List.of(field.path().split("\\.")));
        return path;
    }

    private static JsonElement get(JsonObject base, List<String> path) {
        JsonElement current = base;
        for (String member : path) {
            if (current == null || !current.isJsonObject()) {
                return null;
            }
            current = current.getAsJsonObject().get(member);
        }
        return current == null || current.isJsonNull() ? null : current;
    }

    private static String text(JsonElement value, ConfigField field) {
        if (value == null) {
            return "";
        }
        if (field.kind() == FieldKind.LIST && value.isJsonArray()) {
            List<String> lines = new ArrayList<>();
            for (JsonElement element : value.getAsJsonArray()) {
                lines.add(element.isJsonPrimitive() ? element.getAsString() : element.toString());
            }
            return String.join("\n", lines);
        }
        return value.isJsonPrimitive() ? value.getAsString() : value.toString();
    }

    private void setText(JsonObject base, List<String> path, ConfigField field, String text) {
        JsonElement value = valueOf(field, text == null ? "" : text);
        if (text(value, field).equals(text(get(base, path), field))) {
            return; // same value: nothing is rewritten ("8" stays 8, a list keeps its layout)
        }
        if (value == null) {
            remove(base, path);
        } else {
            JsonObject parent = base;
            for (String member : path.subList(0, path.size() - 1)) {
                JsonElement child = parent.get(member);
                if (child == null || !child.isJsonObject()) {
                    child = new JsonObject();
                    parent.add(member, child);
                }
                parent = child.getAsJsonObject();
            }
            parent.add(path.getLast(), value);
        }
        modified = true;
    }

    /** Removes the member and the objects it leaves empty, never the base nor its first level ("actionConfig"). */
    private static void remove(JsonObject base, List<String> path) {
        List<JsonObject> parents = new ArrayList<>();
        JsonObject parent = base;
        for (String member : path.subList(0, path.size() - 1)) {
            JsonElement child = parent.get(member);
            if (child == null || !child.isJsonObject()) {
                return;
            }
            parents.add(parent);
            parent = child.getAsJsonObject();
        }
        parent.remove(path.getLast());
        for (int i = parents.size() - 1; i >= 1 && parent.isEmpty(); i--) {
            parents.get(i).remove(path.get(i));
            parent = parents.get(i);
        }
    }

    /** The JSON value of a text, null for a blank one. */
    private static JsonElement valueOf(ConfigField field, String text) {
        if (field.kind() == FieldKind.LIST) {
            if (field.elementSchema() == null || field.elementSchema().kind() == FieldKind.RECORD
                    || field.elementSchema().kind() == FieldKind.LIST) {
                throw new IllegalArgumentException(field.path() + " cannot be edited as text");
            }
            JsonArray array = new JsonArray();
            for (String line : text.split("\\R")) {
                if (!line.isBlank()) {
                    array.add(valueOf(field.elementSchema(), line.strip()));
                }
            }
            return array.isEmpty() ? null : array;
        }
        String value = text.strip();
        if (value.isEmpty()) {
            return null;
        }
        return switch (field.kind()) {
            case BOOLEAN -> switch (value.toLowerCase(Locale.ROOT)) {
                case "true" -> new JsonPrimitive(true);
                case "false" -> new JsonPrimitive(false);
                default -> throw new IllegalArgumentException(value);
            };
            case INTEGER -> {
                try {
                    yield new JsonPrimitive(Long.parseLong(value));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(value, e);
                }
            }
            case DECIMAL -> {
                try {
                    yield new JsonPrimitive(new BigDecimal(value));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(value, e);
                }
            }
            case RECORD, LIST -> throw new IllegalArgumentException(field.path() + " cannot be edited as text");
            case STRING, PATH, ENUM -> new JsonPrimitive(value);
        };
    }

    // ---- validation ----

    /**
     * The required fields left empty (spec desktop-ui §3), for the steps whose action is loaded: a required
     * field of a record counts only when the record is present. A step without action is a problem too
     * (field path "action").
     */
    public List<Problem> validate(StepCatalog catalog) {
        List<Problem> problems = new ArrayList<>();
        for (Section section : Section.values()) {
            List<JsonObject> steps = steps(section);
            for (int i = 0; i < steps.size(); i++) {
                JsonObject step = steps.get(i);
                JsonElement action = step.get("action");
                if (action == null || !action.isJsonPrimitive() || action.getAsString().isBlank()) {
                    problems.add(new Problem(section, i, "action"));
                    continue;
                }
                int index = i;
                catalog.find(section, step).flatMap(CatalogAction::configSchema).ifPresent(schema ->
                        checkRequired(step, schema.fields(), section, index, problems));
            }
        }
        return problems;
    }

    private static void checkRequired(JsonObject step, List<ConfigField> fields, Section section, int index,
                                      List<Problem> problems) {
        for (ConfigField field : fields) {
            JsonElement value = get(step, configPath(field));
            boolean empty = value == null || (value.isJsonPrimitive() && value.getAsString().isBlank())
                    || (value.isJsonArray() && value.getAsJsonArray().isEmpty());
            if (field.required() && empty) {
                problems.add(new Problem(section, index, field.path()));
            }
            if (field.kind() == FieldKind.RECORD && value != null && value.isJsonObject()) {
                checkRequired(step, field.children(), section, index, problems);
            }
        }
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `mvn -o -q -pl copybot-engine,copybot-ui test`
Expected: PASS (dont `PipelineDocumentTest` : 25 tests).

- [ ] **Step 5: Commit**

```bash
git add copybot-ui/src/main/java/com/copybot/ui/model/PipelineDocument.java copybot-ui/src/test/java/com/copybot/ui/model/PipelineDocumentTest.java
git commit -m "Edit configuration fields and check the required ones" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 13: `PipelineSummary` — l'en-tête de la vue du plan

**Files:**
- Create: `copybot-ui/src/main/java/com/copybot/ui/model/PipelineSummary.java`
- Create: `copybot-ui/src/test/java/com/copybot/ui/model/PipelineSummaryTest.java`

**Interfaces:**
- Consumes: `PipelineDocument` (9, 11), `StepCatalog` (10), `PipelineDocumentTest.PIPELINE` (9).
- Produces: `public record PipelineSummary(List<String> stepNames, String sourcePath, String outPattern, String resumeMode)` avec `static PipelineSummary of(PipelineDocument, StepCatalog)` : noms affichés du catalogue (code de l'action pour un plugin introuvable), `path` du premier `file.read` embarqué, `outPattern` du `file.write` embarqué de sortie, mode de reprise effectif (null sans bloc `resume`).

- [ ] **Step 1: Write the failing test**

`copybot-ui/src/test/java/com/copybot/ui/model/PipelineSummaryTest.java` :

```java
package com.copybot.ui.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.copybot.ui.model.TestCatalog.CATALOG;
import static org.junit.jupiter.api.Assertions.*;

/** The header of the plan view (spec desktop-ui §2). */
public class PipelineSummaryTest {

    @Test
    public void theHeaderNamesTheStepsTheSourceTheOutputAndTheResumeMode() {
        PipelineDocument document = PipelineDocument.parse(PipelineDocumentTest.PIPELINE);

        PipelineSummary summary = PipelineSummary.of(document, CATALOG);

        assertEquals(List.of("file.read name", "faces", "file.write name"), summary.stepNames(),
                "the catalog name, the action code of a missing plugin");
        assertEquals("D:/DCIM", summary.sourcePath());
        assertEquals("nas/{name}", summary.outPattern());
        assertEquals("state", summary.resumeMode());
    }

    @Test
    public void anEmptyPipelineHasNothingToShow() {
        PipelineSummary summary = PipelineSummary.of(PipelineDocument.empty(), CATALOG);

        assertEquals(List.of(), summary.stepNames());
        assertNull(summary.sourcePath());
        assertNull(summary.outPattern());
        assertNull(summary.resumeMode());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -o -q -pl copybot-engine,copybot-ui test -Dtest=PipelineSummaryTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — compilation `cannot find symbol: class PipelineSummary`.

- [ ] **Step 3: Implement**

`copybot-ui/src/main/java/com/copybot/ui/model/PipelineSummary.java` :

```java
package com.copybot.ui.model;

import com.copybot.engine.plugin.CatalogAction;
import com.copybot.ui.model.PipelineDocument.Section;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The header of the plan view (spec desktop-ui §2): the displayed names of the steps, the source path of
 * file.read, the output pattern of file.write and the resume mode.
 *
 * @param sourcePath the "path" of the first embedded file.read step, null without one
 * @param outPattern the "outPattern" of an embedded file.write output step, null without one
 * @param resumeMode the effective resume mode, null without "resume" block
 */
public record PipelineSummary(List<String> stepNames, String sourcePath, String outPattern, String resumeMode) {

    public PipelineSummary {
        stepNames = List.copyOf(stepNames);
    }

    public static PipelineSummary of(PipelineDocument document, StepCatalog catalog) {
        List<String> names = new ArrayList<>();
        String sourcePath = null;
        String outPattern = null;
        for (Section section : Section.values()) {
            for (JsonObject step : document.steps(section)) {
                Optional<CatalogAction> action = catalog.find(section, step);
                names.add(action.map(CatalogAction::name).orElseGet(() -> actionCode(step)));
                if (action.isPresent() && action.get().isEmbedded()) {
                    String code = action.get().actionCode();
                    if (sourcePath == null && section == Section.IN && code.equals("file.read")) {
                        sourcePath = configString(step, "path");
                    }
                    if (section == Section.OUT && code.equals("file.write")) {
                        outPattern = configString(step, "outPattern");
                    }
                }
            }
        }
        return new PipelineSummary(names, sourcePath, outPattern, document.resumeMode().orElse(null));
    }

    private static String actionCode(JsonObject step) {
        JsonElement action = step.get("action");
        return action != null && action.isJsonPrimitive() ? action.getAsString() : "?";
    }

    private static String configString(JsonObject step, String member) {
        JsonElement config = step.get("actionConfig");
        if (config == null || !config.isJsonObject()) {
            return null;
        }
        JsonElement value = config.getAsJsonObject().get(member);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `mvn -o -q -pl copybot-engine,copybot-ui test -Dtest=PipelineSummaryTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add copybot-ui/src/main/java/com/copybot/ui/model/PipelineSummary.java copybot-ui/src/test/java/com/copybot/ui/model/PipelineSummaryTest.java
git commit -m "Summarize a pipeline for the plan view header" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 14: `PlanViewModel` — la vue du plan sans JavaFX

**Files:**
- Create: `copybot-ui/src/main/java/com/copybot/ui/model/PlanViewModel.java`
- Create: `copybot-ui/src/test/java/com/copybot/ui/model/PlanViewModelTest.java`

**Interfaces:**
- Consumes: `Plan.Counts` / `Plan.fromDate` (6), `PipelineState` (dont `getWarnings()`, écriture sûre), `ResumeProposal`, `ResumePoint`, `ItemKey`, `ResumeSource`, `WorkItemExecution`, `WorkStatus`, `FileUtil.toAutoUnitSize` (5), `RecentPipelines.LastRun` (7), clés UI (8).
- Produces: `public final class PlanViewModel(boolean autoExecute)` : `enum Phase { NOT_PREPARED, PREPARING, PREPARED, PREPARE_FAILED, RUNNING, PAUSED, FINISHED }`, `enum Filter { ALL, TO_COPY, SKIPPED, ERRORS }`, `record Progress(int doneFiles, int totalFiles, long doneBytes, long totalBytes)` avec `double fraction()` ; alimentation : `startPreparing()`, `startExecuting()`, `reset()`, `update(PipelineState, List<WorkItemExecution> ordered)`, `setOverride(ResumePoint)` / `override()`, `setFilter(Filter)` / `filter()`, `setAutoExecute(boolean)` / `isAutoExecute()`, `boolean consumeAutoExecute()`, `Optional<LastRun> consumeFinishedRun(Instant)` ; état : `phase()`, `isActive()`, `isExecutionActive()`, `canGoBack()`, `canPrepare()`, `canCopy()`, `canPause()`, `canResume()`, `canStop()`, `canChangeResumePoint()`, `Optional<ResumePoint> resumePointFrom(WorkItemExecution)`, `items()`, `visibleItems()`, `counts()`, `progress()`, `warnings()` ; textes : `copyLabel()`, `progressText()`, `statusLine()`, `Optional<String> resumeText()`, `static statusText(WorkItemExecution)`, `static dateText(WorkItemExecution)`, `static pipelineStatusText(PipelineStatus)`, `static lastRunText(LastRun)`, `static resumeModeText(String)`.

- [ ] **Step 1: Write the failing test**

`copybot-ui/src/test/java/com/copybot/ui/model/PlanViewModelTest.java` :

```java
package com.copybot.ui.model;

import com.copybot.engine.Plan;
import com.copybot.engine.pipeline.PipelineState;
import com.copybot.engine.pipeline.PipelineStatus;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.engine.resume.ItemKey;
import com.copybot.engine.resume.ResumePoint;
import com.copybot.engine.resume.ResumeProposal;
import com.copybot.engine.resume.ResumeSource;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import com.copybot.plugin.api.action.WorkStatus;
import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.model.PlanViewModel.Filter;
import com.copybot.ui.model.PlanViewModel.Phase;
import com.copybot.ui.model.PlanViewModel.Progress;
import com.copybot.ui.model.RecentPipelines.LastRun;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The plan view without JavaFX (spec desktop-ui §2, §7). */
public class PlanViewModelTest {

    @TempDir
    Path tempDir;

    private static final Instant SHOT = Instant.parse("2026-09-28T15:42:00Z");

    @BeforeAll
    public static void registerUiBundle() {
        ResourcesEngine.registerBundle("com.copybot.ui.i18n.uiBundle");
    }

    private WorkItemExecution item(String name, long size) throws IOException {
        WorkItem wi = new WorkItem(tempDir.resolve(name));
        wi.getMetadatas().setSize(size);
        wi.getMetadatas().setTime(WorkItemMetadata.LAST_MODIFIED, SHOT);
        return new WorkItemExecution(wi, List.of());
    }

    private static PipelineState state(PipelineStatus status, WorkItemExecution... items) {
        PipelineState state = new PipelineState(List.of());
        state.setStatus(status);
        state.getWorkItems().addAll(List.of(items));
        return state;
    }

    private static ResumeProposal proposal(ResumePoint point, ResumeSource source, String... warnings) {
        return new ResumeProposal(point, source, List.of(warnings));
    }

    /** A model with a prepared plan of these items (resume: everything). */
    private static PlanViewModel prepared(boolean autoExecute, WorkItemExecution... items) {
        PlanViewModel model = new PlanViewModel(autoExecute);
        model.startPreparing();
        PipelineState state = state(PipelineStatus.PREPARED, items);
        state.setResumeProposal(proposal(ResumePoint.all(), ResumeSource.NONE));
        model.update(state, List.of(items));
        return model;
    }

    private static void assertTranslated(String text) {
        assertFalse(text.isEmpty());
        assertFalse(text.startsWith("%"), text);
    }

    // ---- phases and buttons ----

    @Test
    public void nothingIsPreparedAtOpening() {
        PlanViewModel model = new PlanViewModel(false);

        assertEquals(Phase.NOT_PREPARED, model.phase());
        assertTrue(model.canPrepare());
        assertTrue(model.canGoBack());
        assertFalse(model.canCopy());
        assertFalse(model.canStop());
        assertEquals(List.of(), model.items());
        assertEquals(Optional.empty(), model.resumeText());
    }

    @Test
    public void thePreparationListsTheRowsAndLocksTheView() throws IOException {
        PlanViewModel model = new PlanViewModel(false);
        WorkItemExecution a = item("a.jpg", 1);

        model.startPreparing();
        model.update(state(PipelineStatus.RUNNING, a), List.of());

        assertEquals(Phase.PREPARING, model.phase());
        assertEquals(List.of(a), model.items(), "the table fills during the listing");
        assertFalse(model.canGoBack());
        assertFalse(model.canPrepare());
        assertFalse(model.canStop(), "a preparation is not stopped from the view");
        assertTrue(model.isActive());
        assertTrue(model.statusLine().contains("1"), model.statusLine());
    }

    @Test
    public void aPreparedPlanCanBeCopiedOrPreparedAgain() throws IOException {
        PlanViewModel model = prepared(false, item("a.jpg", 1));

        assertEquals(Phase.PREPARED, model.phase());
        assertTrue(model.canCopy());
        assertTrue(model.canPrepare());
        assertTrue(model.canGoBack());
        assertTrue(model.canChangeResumePoint());
        assertFalse(model.isActive());
    }

    @Test
    public void aFailedOrCancelledPreparation() throws IOException {
        PlanViewModel model = new PlanViewModel(false);
        model.startPreparing();
        PipelineState failed = state(PipelineStatus.ERROR);
        failed.setFailure(new IllegalStateException("card removed"));

        model.update(failed, List.of());

        assertEquals(Phase.PREPARE_FAILED, model.phase());
        assertTrue(model.statusLine().contains("card removed"), model.statusLine());
        assertTrue(model.canPrepare());
        assertFalse(model.canCopy());

        model.startPreparing();
        model.update(state(PipelineStatus.CANCELLED), List.of());
        assertEquals(Phase.NOT_PREPARED, model.phase());
    }

    @Test
    public void theExecutionCanBePausedResumedAndStopped() throws IOException {
        WorkItemExecution a = item("a.jpg", 1);
        PlanViewModel model = prepared(false, a);

        model.startExecuting();
        assertEquals(Phase.RUNNING, model.phase());
        assertTrue(model.canPause());
        assertTrue(model.canStop());
        assertFalse(model.canResume());
        assertFalse(model.canGoBack(), "no way back during an execution");
        assertFalse(model.canChangeResumePoint());

        model.update(state(PipelineStatus.PAUSED, a), List.of(a));
        assertEquals(Phase.PAUSED, model.phase());
        assertTrue(model.canResume());
        assertFalse(model.canPause());
        assertTrue(model.canStop());

        a.setDone();
        model.update(state(PipelineStatus.SUCCESS, a), List.of(a));
        assertEquals(Phase.FINISHED, model.phase());
        assertTrue(model.canPrepare());
        assertTrue(model.canGoBack());
        assertFalse(model.canStop());
        assertTranslated(model.statusLine());
    }

    @Test
    public void nothingToCopyDisablesTheCopy() throws IOException {
        WorkItemExecution a = item("a.jpg", 1);
        a.setSkipped("already imported");

        assertFalse(prepared(false, a).canCopy());
    }

    // ---- rows ----

    @Test
    public void theFilterKeepsTheMatchingRows() throws IOException {
        WorkItemExecution pending = item("a.jpg", 1);
        WorkItemExecution skipped = item("b.jpg", 1);
        skipped.setSkipped("already imported");
        WorkItemExecution failed = item("c.jpg", 1);
        failed.setError(new IllegalStateException("unreadable"));
        PlanViewModel model = prepared(false, pending, skipped, failed);

        assertEquals(List.of(pending, skipped, failed), model.visibleItems());
        model.setFilter(Filter.TO_COPY);
        assertEquals(List.of(pending), model.visibleItems());
        model.setFilter(Filter.SKIPPED);
        assertEquals(List.of(skipped), model.visibleItems());
        model.setFilter(Filter.ERRORS);
        assertEquals(List.of(failed), model.visibleItems());
    }

    @Test
    public void theRowsFollowTheResumeOrderThenTheForkedItems() throws IOException {
        WorkItemExecution first = item("a.jpg", 1);
        WorkItemExecution second = item("b.jpg", 1);
        WorkItemExecution forked = item("a-small.jpg", 1);
        PlanViewModel model = new PlanViewModel(false);
        model.startPreparing();

        model.update(state(PipelineStatus.PREPARED, forked, second, first), List.of(first, second));

        assertEquals(List.of(first, second, forked), model.items());
    }

    // ---- counters and texts ----

    @Test
    public void theCopyButtonTellsTheSelectedFilesAndSize() throws IOException {
        WorkItemExecution a = item("a.jpg", 1536);
        WorkItemExecution b = item("b.jpg", 1536);
        WorkItemExecution c = item("c.jpg", 1536);
        c.setSkipped("already imported");
        PlanViewModel model = prepared(false, a, b, c);

        assertEquals(new Plan.Counts(2, 1, 0, 3072), model.counts());
        String label = model.copyLabel();
        assertTranslated(label);
        assertTrue(label.contains("2"), label);
        assertTrue(label.contains("3.0"), label);
    }

    @Test
    public void everyStatusHasItsText() throws IOException {
        WorkItemExecution pending = item("a.jpg", 1);
        WorkItemExecution waiting = item("b.jpg", 1);
        waiting.setWaitingResources(0, Set.of("disk:D"));
        WorkItemExecution running = item("c.jpg", 1);
        running.setRunning(0);
        running.setWorkStatus(new WorkStatus("copy", 42));
        WorkItemExecution runningUnknown = item("d.jpg", 1);
        runningUnknown.setRunning(0);
        WorkItemExecution done = item("e.jpg", 1);
        done.setDone();
        WorkItemExecution skipped = item("f.jpg", 1);
        skipped.setSkipped("already imported (cursor)");
        WorkItemExecution failed = item("g.jpg", 1);
        failed.setError(new IllegalStateException("disk full"));

        for (WorkItemExecution item : List.of(pending, waiting, running, runningUnknown, done, skipped, failed)) {
            assertTranslated(PlanViewModel.statusText(item));
        }
        assertTrue(PlanViewModel.statusText(waiting).contains("disk:D"));
        assertTrue(PlanViewModel.statusText(running).contains("42"));
        assertTrue(PlanViewModel.statusText(skipped).contains("already imported (cursor)"));
        assertTrue(PlanViewModel.statusText(failed).contains("disk full"));
        assertNotEquals(PlanViewModel.statusText(pending), PlanViewModel.statusText(done));
    }

    @Test
    public void theDateColumnShowsTheResumeKeyOrTheFileDate() throws IOException {
        WorkItemExecution item = item("a.jpg", 1);
        String local = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").format(SHOT.atZone(ZoneId.systemDefault()));

        assertEquals(local, PlanViewModel.dateText(item), "before the preparation: the modification date");
        item.setResumeKey(new ItemKey(SHOT.plusSeconds(3600), "a.jpg"));
        assertNotEquals(local, PlanViewModel.dateText(item), "then the frozen resume key");
    }

    @Test
    public void theWarningsAreTheConfigurationOnesThenTheResumeOnes() throws IOException {
        PlanViewModel model = new PlanViewModel(false);
        model.startPreparing();
        PipelineState state = state(PipelineStatus.PREPARED, item("a.jpg", 1));
        state.setWarnings(List.of("sources deleted"));
        state.setResumeProposal(proposal(ResumePoint.all(), ResumeSource.NONE, "no target paths"));

        model.update(state, List.of());

        assertEquals(List.of("sources deleted", "no target paths"), model.warnings());
    }

    @Test
    public void theResumeLineNamesThePointAndItsOrigin() throws IOException {
        PlanViewModel model = new PlanViewModel(false);
        model.startPreparing();
        PipelineState state = state(PipelineStatus.PREPARED, item("DSC_4822.JPG", 1));
        state.setResumeProposal(proposal(ResumePoint.after(new ItemKey(SHOT, "DSC_4821.JPG")), ResumeSource.STATE));
        model.update(state, List.of());

        String proposed = model.resumeText().orElseThrow();
        assertTranslated(proposed);
        assertTrue(proposed.contains("DSC_4821.JPG"), proposed);

        model.setOverride(Plan.fromDate(LocalDate.of(2026, 9, 20)));
        String manual = model.resumeText().orElseThrow();
        assertTrue(manual.contains("20/09/2026"), manual);
        assertNotEquals(proposed, manual);

        model.setOverride(ResumePoint.all());
        assertTranslated(model.resumeText().orElseThrow());
    }

    @Test
    public void resumeFromHereNeedsAPreparedPlanAndAKey() throws IOException {
        WorkItemExecution keyed = item("a.jpg", 1);
        keyed.setResumeKey(new ItemKey(SHOT, "a.jpg"));
        WorkItemExecution noKey = item("b.jpg", 1);
        noKey.setResumeKey(null);
        PlanViewModel model = prepared(false, keyed, noKey);

        assertEquals(Optional.of(ResumePoint.from(new ItemKey(SHOT, "a.jpg"))), model.resumePointFrom(keyed));
        assertEquals(Optional.empty(), model.resumePointFrom(noKey));
        model.startExecuting();
        assertEquals(Optional.empty(), model.resumePointFrom(keyed));
    }

    // ---- automatic execution, progress, end of run ----

    @Test
    public void theAutomaticExecutionStartsOnceWhenThePlanIsReady() throws IOException {
        PlanViewModel model = prepared(true, item("a.jpg", 1));

        assertTrue(model.consumeAutoExecute());
        assertFalse(model.consumeAutoExecute(), "once");
    }

    @Test
    public void theAutomaticExecutionFollowsTheSessionBox() throws IOException {
        assertFalse(prepared(false, item("a.jpg", 1)).consumeAutoExecute());

        PlanViewModel unchecked = new PlanViewModel(true);
        unchecked.setAutoExecute(false);
        unchecked.startPreparing();
        unchecked.update(state(PipelineStatus.PREPARED, item("b.jpg", 1)), List.of());
        assertFalse(unchecked.consumeAutoExecute());
    }

    @Test
    public void noAutomaticExecutionOfAFailedOrEmptyPlan() throws IOException {
        PlanViewModel failed = new PlanViewModel(true);
        failed.startPreparing();
        failed.update(state(PipelineStatus.ERROR), List.of());
        assertFalse(failed.consumeAutoExecute());

        WorkItemExecution skipped = item("a.jpg", 1);
        skipped.setSkipped("already imported");
        assertFalse(prepared(true, skipped).consumeAutoExecute());
    }

    @Test
    public void theProgressCountsTheItemsSelectedAtTheStart() throws IOException {
        WorkItemExecution a = item("a.jpg", 100);
        WorkItemExecution b = item("b.jpg", 300);
        WorkItemExecution c = item("c.jpg", 50);
        c.setSkipped("already imported");
        PlanViewModel model = prepared(false, a, b, c);
        model.startExecuting();

        a.setDone();
        model.update(state(PipelineStatus.RUNNING, a, b, c), List.of(a, b, c));

        assertEquals(new Progress(1, 2, 100, 400), model.progress());
        assertEquals(0.25, model.progress().fraction(), 1e-9);
        assertTranslated(model.progressText());

        b.setSkipped("identical to the destination");
        assertEquals(new Progress(2, 2, 400, 400), model.progress(), "skipped by the write: processed");
    }

    @Test
    public void theEndOfTheRunIsReportedOnce() throws IOException {
        WorkItemExecution a = item("a.jpg", 1);
        WorkItemExecution b = item("b.jpg", 1);
        WorkItemExecution c = item("c.jpg", 1);
        PlanViewModel model = prepared(false, a, b, c);
        model.startExecuting();
        assertEquals(Optional.empty(), model.consumeFinishedRun(SHOT));

        a.setDone();
        b.setSkipped("identical to the destination");
        c.setError(new IllegalStateException("disk full"));
        model.update(state(PipelineStatus.ERROR, a, b, c), List.of(a, b, c));

        assertEquals(Optional.of(new LastRun(SHOT, PipelineStatus.ERROR, 1, 1, 1)), model.consumeFinishedRun(SHOT));
        assertEquals(Optional.empty(), model.consumeFinishedRun(SHOT), "once");
    }

    @Test
    public void theHeaderTexts() {
        assertTranslated(PlanViewModel.lastRunText(null));
        String run = PlanViewModel.lastRunText(new LastRun(SHOT, PipelineStatus.SUCCESS, 120, 3, 1));
        assertTranslated(run);
        assertTrue(run.contains("120"), run);
        for (String mode : List.of("none", "state", "destination", "stateThenDestination")) {
            assertTranslated(PlanViewModel.resumeModeText(mode));
        }
        assertTranslated(PlanViewModel.resumeModeText(null));
        for (PipelineStatus status : List.of(PipelineStatus.SUCCESS, PipelineStatus.ERROR, PipelineStatus.CANCELLED)) {
            assertTranslated(PlanViewModel.pipelineStatusText(status));
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -o -q -pl copybot-engine,copybot-ui test -Dtest=PlanViewModelTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — compilation `cannot find symbol: class PlanViewModel`.

- [ ] **Step 3: Implement**

`copybot-ui/src/main/java/com/copybot/ui/model/PlanViewModel.java` :

```java
package com.copybot.ui.model;

import com.copybot.engine.Plan;
import com.copybot.engine.pipeline.ItemStatus;
import com.copybot.engine.pipeline.PipelineState;
import com.copybot.engine.pipeline.PipelineStatus;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.engine.resume.ItemKey;
import com.copybot.engine.resume.ResumePoint;
import com.copybot.engine.resume.ResumeProposal;
import com.copybot.engine.resume.ResumeSource;
import com.copybot.plugin.api.action.WorkStatus;
import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.model.RecentPipelines.LastRun;
import com.copybot.utils.FileUtil;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The state of the plan view (spec desktop-ui §2), without JavaFX: the phase and the buttons it allows,
 * the rows and their filter, the counters, the texts. The controller feeds it the engine state (on the
 * JavaFX thread) and renders it. Not thread-safe.
 */
public final class PlanViewModel {

    public enum Phase {
        /** nothing prepared (at opening, after a reload or a cancelled preparation) */
        NOT_PREPARED,
        PREPARING,
        PREPARED,
        PREPARE_FAILED,
        RUNNING,
        PAUSED,
        /** the execution ended: SUCCESS, ERROR or CANCELLED */
        FINISHED
    }

    public enum Filter { ALL, TO_COPY, SKIPPED, ERRORS }

    /** Files and bytes processed (done, skipped or failed) among those selected when the copy started. */
    public record Progress(int doneFiles, int totalFiles, long doneBytes, long totalBytes) {
        /** 0 to 1, by bytes when the sizes are known, by files otherwise. */
        public double fraction() {
            if (totalBytes > 0) {
                return (double) doneBytes / totalBytes;
            }
            return totalFiles == 0 ? 0 : (double) doneFiles / totalFiles;
        }
    }

    private static final DateTimeFormatter ITEM_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter RESUME_DATE = DateTimeFormatter.ofPattern("dd/MM HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private Phase phase = Phase.NOT_PREPARED;
    private boolean executing;
    private boolean autoExecute;
    private boolean autoExecuteArmed;
    private boolean finishedReported;
    private Filter filter = Filter.ALL;
    private List<WorkItemExecution> items = List.of();
    private PipelineStatus status;
    private List<String> warnings = List.of();
    private ResumeProposal proposal;
    private ResumePoint override;
    private Throwable failure;
    private Set<WorkItemExecution> selectedAtStart = Set.of();

    /** @param autoExecute "ui.autoExecute" of the pipeline: copy as soon as the plan is ready */
    public PlanViewModel(boolean autoExecute) {
        this.autoExecute = autoExecute;
    }

    // ---- feeding ----

    /** A preparation starts: empty table, the automatic execution armed if checked. */
    public void startPreparing() {
        clear();
        phase = Phase.PREPARING;
        autoExecuteArmed = true;
    }

    /** The prepared plan starts executing: the selected items and bytes become the progress totals. */
    public void startExecuting() {
        executing = true;
        finishedReported = false;
        phase = Phase.RUNNING;
        Set<WorkItemExecution> selected = Collections.newSetFromMap(new IdentityHashMap<>());
        items.stream().filter(i -> Plan.Counts.isSelected(i.getStatus())).forEach(selected::add);
        selectedAtStart = selected;
    }

    /** Back to "not prepared" (pipeline reloaded, preparation refused before it started). */
    public void reset() {
        clear();
        phase = Phase.NOT_PREPARED;
    }

    private void clear() {
        executing = false;
        autoExecuteArmed = false;
        finishedReported = false;
        items = List.of();
        status = null;
        warnings = List.of();
        proposal = null;
        override = null;
        failure = null;
        selectedAtStart = Set.of();
    }

    /**
     * The latest engine state (watcher notification, end of the preparation, pause...).
     *
     * @param ordered the plan's items in resume order once it is prepared, empty before: the rows are
     *                then in listing order; items forked during the execution come after the ordered ones
     */
    public void update(PipelineState state, List<WorkItemExecution> ordered) {
        status = state.getStatus();
        proposal = state.getResumeProposal();
        failure = state.getFailure();
        List<String> all = new ArrayList<>(state.getWarnings());
        if (proposal != null) {
            all.addAll(proposal.warnings());
        }
        warnings = List.copyOf(all);
        List<WorkItemExecution> listed = List.copyOf(state.getWorkItems());
        if (ordered.isEmpty()) {
            items = listed;
        } else {
            Set<WorkItemExecution> known = Collections.newSetFromMap(new IdentityHashMap<>());
            known.addAll(ordered);
            List<WorkItemExecution> rows = new ArrayList<>(ordered);
            listed.stream().filter(i -> !known.contains(i)).forEach(rows::add);
            items = List.copyOf(rows);
        }
        if (executing) {
            phase = switch (status) {
                case PAUSED -> Phase.PAUSED;
                case SUCCESS, ERROR, CANCELLED -> Phase.FINISHED;
                default -> Phase.RUNNING;
            };
        } else {
            phase = switch (status) {
                case PREPARED -> Phase.PREPARED;
                case ERROR -> Phase.PREPARE_FAILED;
                case CANCELLED -> Phase.NOT_PREPARED;
                default -> Phase.PREPARING;
            };
        }
    }

    /** A manual resume point, already applied with {@code Plan.preview}: shown on the resume line and executed. */
    public void setOverride(ResumePoint override) {
        this.override = override;
    }

    public ResumePoint override() {
        return override;
    }

    public void setFilter(Filter filter) {
        this.filter = filter;
    }

    public Filter filter() {
        return filter;
    }

    /** The "automatic execution" box: this session only (spec desktop-ui §2). */
    public void setAutoExecute(boolean autoExecute) {
        this.autoExecute = autoExecute;
    }

    public boolean isAutoExecute() {
        return autoExecute;
    }

    /**
     * True once, when the plan just became ready, the box is checked and there is something to copy:
     * the controller then starts the copy.
     */
    public boolean consumeAutoExecute() {
        if (autoExecuteArmed && autoExecute && phase == Phase.PREPARED && counts().selected() > 0) {
            autoExecuteArmed = false;
            return true;
        }
        if (phase != Phase.PREPARING) {
            autoExecuteArmed = false;
        }
        return false;
    }

    /** The summary of the execution, once, when it has just finished: for the recent pipelines. */
    public Optional<LastRun> consumeFinishedRun(Instant now) {
        if (phase != Phase.FINISHED || finishedReported) {
            return Optional.empty();
        }
        finishedReported = true;
        Plan.Counts counts = counts();
        return Optional.of(new LastRun(now, status, copied(), counts.skipped(), counts.errors()));
    }

    private int copied() {
        return (int) items.stream().filter(i -> i.getStatus() == ItemStatus.DONE).count();
    }

    // ---- state ----

    public Phase phase() {
        return phase;
    }

    /** A preparation or an execution holds the engine. */
    public boolean isActive() {
        return phase == Phase.PREPARING || phase == Phase.RUNNING || phase == Phase.PAUSED;
    }

    public boolean isExecutionActive() {
        return phase == Phase.RUNNING || phase == Phase.PAUSED;
    }

    /** "← Pipelines", "Edit…" and "Prepare the plan": not while the engine is busy. */
    public boolean canGoBack() {
        return !isActive();
    }

    public boolean canPrepare() {
        return !isActive();
    }

    public boolean canCopy() {
        return phase == Phase.PREPARED && counts().selected() > 0;
    }

    public boolean canPause() {
        return phase == Phase.RUNNING;
    }

    public boolean canResume() {
        return phase == Phase.PAUSED;
    }

    public boolean canStop() {
        return isExecutionActive();
    }

    /** "change…" and "Resume from here": a prepared plan, before its execution. */
    public boolean canChangeResumePoint() {
        return phase == Phase.PREPARED && proposal != null;
    }

    /** The resume point "Resume from here" gives for this row: from its key, included. */
    public Optional<ResumePoint> resumePointFrom(WorkItemExecution item) {
        return canChangeResumePoint() ? item.getResumeKey().map(ResumePoint::from) : Optional.empty();
    }

    public List<WorkItemExecution> items() {
        return items;
    }

    /** The rows of the current filter. */
    public List<WorkItemExecution> visibleItems() {
        return items.stream().filter(i -> switch (filter) {
            case ALL -> true;
            case TO_COPY -> Plan.Counts.isSelected(i.getStatus());
            case SKIPPED -> i.getStatus() == ItemStatus.SKIPPED;
            case ERRORS -> i.getStatus() == ItemStatus.ERROR;
        }).toList();
    }

    public Plan.Counts counts() {
        return Plan.Counts.of(items);
    }

    public Progress progress() {
        int done = 0;
        long doneBytes = 0;
        long totalBytes = 0;
        for (WorkItemExecution item : selectedAtStart) {
            long size = size(item);
            totalBytes += size;
            ItemStatus s = item.getStatus();
            if (s == ItemStatus.DONE || s == ItemStatus.SKIPPED || s == ItemStatus.ERROR) {
                done++;
                doneBytes += size;
            }
        }
        return new Progress(done, selectedAtStart.size(), doneBytes, totalBytes);
    }

    private static long size(WorkItemExecution item) {
        Long size = item.getWorkItem().getMetadatas().getSize();
        return size == null ? 0 : size;
    }

    /** The configuration warnings of the steps, then the resume ones (spec desktop-ui §2). */
    public List<String> warnings() {
        return warnings;
    }

    // ---- texts ----

    /** "Copy N files (X GB)". */
    public String copyLabel() {
        Plan.Counts counts = counts();
        return ResourcesEngine.getString("plan.copy", counts.selected(), FileUtil.toAutoUnitSize(counts.selectedBytes(), 1));
    }

    /** "12 / 40 files, 1.2 GB / 3.5 GB". */
    public String progressText() {
        Progress p = progress();
        return ResourcesEngine.getString("plan.progress", p.doneFiles(), p.totalFiles(),
                FileUtil.toAutoUnitSize(p.doneBytes(), 1), FileUtil.toAutoUnitSize(p.totalBytes(), 1));
    }

    /** The status line under the table: listing, preparation failure, end of the run; empty otherwise. */
    public String statusLine() {
        return switch (phase) {
            case PREPARING -> ResourcesEngine.getString("plan.preparing", items.size());
            case PREPARE_FAILED -> ResourcesEngine.getString("plan.prepare-failed",
                    failure == null || failure.getMessage() == null ? "" : failure.getMessage());
            case FINISHED -> ResourcesEngine.getString("plan.finished", pipelineStatusText(status),
                    copied(), counts().skipped(), counts().errors());
            default -> "";
        };
    }

    /** "Resume: after DSC_4821 (28/09 17:42) [cursor]", empty before a preparation. */
    public Optional<String> resumeText() {
        if (proposal == null || phase == Phase.NOT_PREPARED || phase == Phase.PREPARING || phase == Phase.PREPARE_FAILED) {
            return Optional.empty();
        }
        ResumePoint point = override != null ? override : proposal.point();
        ResumeSource source = override != null ? ResumeSource.MANUAL : proposal.source();
        String sourceText = ResourcesEngine.getString("plan.resume.source." + source.name());
        ItemKey key = point.key();
        if (point.kind() == ResumePoint.Kind.ALL || key == null) {
            return Optional.of(ResourcesEngine.getString("plan.resume.all", sourceText));
        }
        String date = RESUME_DATE.format(key.date().atZone(ZoneId.systemDefault()));
        if (key.name().isEmpty()) {
            return Optional.of(ResourcesEngine.getString("plan.resume.from-date",
                    DAY.format(key.date().atZone(ZoneId.systemDefault())), sourceText));
        }
        String kind = point.kind() == ResumePoint.Kind.AFTER ? "plan.resume.after" : "plan.resume.from";
        return Optional.of(ResourcesEngine.getString(kind, key.name(), date, sourceText));
    }

    /** "To copy", "Skipped — reason", "Copying 42 %", "Copied", "Error — message" (spec desktop-ui §2). */
    public static String statusText(WorkItemExecution item) {
        return switch (item.getStatus()) {
            case PENDING -> ResourcesEngine.getString("item.status.PENDING");
            case WAITING_RESOURCES -> ResourcesEngine.getString("item.status.WAITING_RESOURCES",
                    String.join(", ", item.getWaitingFor()));
            case RUNNING -> {
                WorkStatus ws = item.getWorkStatus();
                yield ws != null && ws.actionPercent() >= 0
                        ? ResourcesEngine.getString("item.status.RUNNING.percent", String.valueOf(ws.actionPercent()))
                        : ResourcesEngine.getString("item.status.RUNNING");
            }
            case DONE -> ResourcesEngine.getString("item.status.DONE");
            case SKIPPED -> ResourcesEngine.getString("item.status.SKIPPED",
                    item.getSkipReason() == null ? "" : item.getSkipReason());
            case ERROR -> ResourcesEngine.getString("item.status.ERROR",
                    item.getError() == null || item.getError().getMessage() == null ? "" : item.getError().getMessage());
        };
    }

    /** The "Date" column: the resume key date, the capture or modification date before the preparation. */
    public static String dateText(WorkItemExecution item) {
        return item.getResumeKey().or(() -> ItemKey.of(item.getWorkItem()))
                .map(key -> ITEM_DATE.format(key.date().atZone(ZoneId.systemDefault())))
                .orElse("");
    }

    /** The label of a terminal pipeline status ("Success", "Failed", "Stopped"). */
    public static String pipelineStatusText(PipelineStatus status) {
        return ResourcesEngine.getString("pipeline.status." + status.name());
    }

    /** "28/09/2026 17:42 — Success: 120 copied, 3 skipped, 1 error(s)", or "Never run". */
    public static String lastRunText(LastRun run) {
        if (run == null) {
            return ResourcesEngine.getString("recent.never-run");
        }
        return ResourcesEngine.getString("recent.last-run", ITEM_DATE.format(run.at().atZone(ZoneId.systemDefault())),
                pipelineStatusText(run.status()), run.copied(), run.skipped(), run.errors());
    }

    /** The resume mode of the header: "no resume", "cursor, then destination"... */
    public static String resumeModeText(String mode) {
        return ResourcesEngine.getString("plan.resume-mode." + (mode == null ? "absent" : mode));
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `mvn -o -q -pl copybot-engine,copybot-ui test`
Expected: PASS (dont `PlanViewModelTest` : 20 tests).

- [ ] **Step 5: Commit**

```bash
git add copybot-ui/src/main/java/com/copybot/ui/model/PlanViewModel.java copybot-ui/src/test/java/com/copybot/ui/model/PlanViewModelTest.java
git commit -m "Model the plan view: phases, buttons, rows, counters and texts" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 15: Éditeur générique (maître / détail)

**Files:**
- Create: `copybot-ui/src/main/java/com/copybot/ui/util/Views.java`
- Create: `copybot-ui/src/main/java/com/copybot/ui/ConfigForm.java`
- Create: `copybot-ui/src/main/java/com/copybot/ui/EditorController.java`
- Create: `copybot-ui/src/main/resources/com/copybot/ui/views/editor-view.fxml`

**Interfaces:**
- Consumes: `PipelineDocument` (9, 11, 12), `StepCatalog` (10), `PluginEngine.catalog()` / `CatalogAction` (4), `ConfigSchema.PATTERN_VARIABLES` / `FieldHint` / `FieldKind` (1), `PlanViewModel.resumeModeText` (14), `RecentPipelines.displayName` (7), `PopinUtil.showError` (existant), clés `editor.*`, `home.file-filter`, `plan.resume-mode.*` (8).
- Produces: `Views.load(String fxml)` ⇒ `Views.Loaded<C>(Parent root, C controller)` (bundle courant, `UncheckedIOException`) ; `public static void EditorController.open(Window owner, Path pathOrNull, Consumer<Path> onSaved)` (modal ; `null` = nouveau pipeline ; `onSaved` appelé avec le fichier à chaque enregistrement) ; `ConfigForm.build(List<ConfigField>, ConfigForm.Access, Window)` (package-private). Rien ne l'appelle encore : l'accueil et la vue du plan le branchent à la tâche 16.

Pas de test automatisé (spec §7) : la logique est dans `PipelineDocument` / `StepCatalog`, testés ; la vérification manuelle de l'éditeur se fait à la tâche 16, une fois branché.

- [ ] **Step 1: Write the view loader**

`copybot-ui/src/main/java/com/copybot/ui/util/Views.java` :

```java
package com.copybot.ui.util;

import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.CopybotMainUi;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;

import java.io.IOException;
import java.io.UncheckedIOException;

/** Loads the FXML views of com/copybot/ui/views with the current resource bundle. */
public final class Views {

    private Views() {
    }

    public record Loaded<C>(Parent root, C controller) {
    }

    public static <C> Loaded<C> load(String fxml) {
        FXMLLoader loader = new FXMLLoader(CopybotMainUi.class.getResource("views/" + fxml));
        loader.setResources(ResourcesEngine.getResourceBundle());
        try {
            Parent root = loader.load();
            return new Loaded<>(root, loader.getController());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
```

- [ ] **Step 2: Write the generated form**

`copybot-ui/src/main/java/com/copybot/ui/ConfigForm.java` :

```java
package com.copybot.ui;

import com.copybot.plugin.api.config.ConfigField;
import com.copybot.plugin.api.config.ConfigSchema;
import com.copybot.plugin.api.config.FieldHint;
import com.copybot.plugin.api.config.FieldKind;
import com.copybot.resources.ResourcesEngine;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The form generated from configuration fields (spec desktop-ui §3, §4): one control per field kind, the
 * values read and written through an {@link Access} (the document), nothing kept here.
 */
final class ConfigForm {

    /** How the form reads and writes the fields (the actionConfig of a step, or the step itself). */
    interface Access {
        String text(ConfigField field);

        /** @throws IllegalArgumentException an invalid value (not a number...): the control turns red */
        void setText(ConfigField field, String text);

        String label(ConfigField field);

        String description(ConfigField field);

        /** The JSON of a value that is not edited as text (a list of records). */
        String json(ConfigField field);
    }

    private static final String INVALID_STYLE = "-fx-border-color: #d9534f;";

    private ConfigForm() {
    }

    static Node build(List<ConfigField> fields, Access access, Window owner) {
        VBox box = new VBox(10);
        for (ConfigField field : fields) {
            box.getChildren().add(field(field, access, owner));
        }
        return box;
    }

    private static Node field(ConfigField field, Access access, Window owner) {
        if (field.kind() == FieldKind.RECORD) {
            TitledPane pane = new TitledPane(access.label(field), build(field.children(), access, owner));
            pane.setExpanded(true);
            return withDescription(pane, access.description(field));
        }
        VBox box = new VBox(3);
        box.getChildren().add(new Label(access.label(field) + (field.required() ? " *" : "")));
        box.getChildren().add(input(field, access, owner));
        String description = access.description(field);
        if (!description.isEmpty()) {
            box.getChildren().add(small(description));
        }
        if (field.hasHint(FieldHint.PATTERN)) {
            String variables = ConfigSchema.PATTERN_VARIABLES.stream().map(v -> "{" + v + "}").collect(Collectors.joining(" "));
            box.getChildren().add(small(ResourcesEngine.getString("editor.pattern-variables", variables)));
        }
        return box;
    }

    private static Node withDescription(Node node, String description) {
        return description.isEmpty() ? node : new VBox(3, node, small(description));
    }

    private static Label small(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setStyle("-fx-font-size: 11px; -fx-text-fill: #555555;");
        return label;
    }

    private static Node input(ConfigField field, Access access, Window owner) {
        return switch (field.kind()) {
            case BOOLEAN -> choice(field, access, List.of("true", "false"));
            case ENUM -> choice(field, access, field.enumValues());
            case LIST -> list(field, access);
            case PATH -> path(field, access, owner);
            default -> text(new TextField(), field, access);
        };
    }

    /** "" (absent: the default applies) then the values; a value outside them is kept and shown. */
    private static Node choice(ConfigField field, Access access, List<String> values) {
        ComboBox<String> combo = new ComboBox<>();
        List<String> items = new ArrayList<>();
        items.add("");
        items.addAll(values);
        String current = access.text(field);
        if (!items.contains(current)) {
            items.add(current);
        }
        combo.getItems().setAll(items);
        combo.setConverter(new StringConverter<>() {
            @Override
            public String toString(String value) {
                if (value == null || value.isEmpty()) {
                    return field.defaultValue() == null ? "" : ResourcesEngine.getString("editor.default", field.defaultValue());
                }
                return value;
            }

            @Override
            public String fromString(String s) {
                return s; // not editable
            }
        });
        combo.setValue(current);
        combo.valueProperty().addListener((obs, old, value) -> apply(combo, field, access, value == null ? "" : value));
        return combo;
    }

    /** One element per line; a list of records is shown as JSON, read-only (kept as is). */
    private static Node list(ConfigField field, Access access) {
        TextArea area = new TextArea();
        area.setPrefRowCount(3);
        FieldKind element = field.elementSchema() == null ? FieldKind.STRING : field.elementSchema().kind();
        if (element == FieldKind.RECORD || element == FieldKind.LIST) {
            area.setText(access.json(field));
            area.setEditable(false);
            return area;
        }
        return text(area, field, access);
    }

    private static Node path(ConfigField field, Access access, Window owner) {
        TextField textField = new TextField();
        text(textField, field, access);
        Button browse = new Button(ResourcesEngine.getString("editor.browse"));
        browse.setOnAction(e -> {
            File chosen;
            if (field.hasHint(FieldHint.DIRECTORY)) {
                chosen = new DirectoryChooser().showDialog(owner);
            } else {
                chosen = new FileChooser().showOpenDialog(owner);
            }
            if (chosen != null) {
                textField.setText(chosen.getPath());
            }
        });
        HBox.setHgrow(textField, Priority.ALWAYS);
        return new HBox(6, textField, browse);
    }

    private static Node text(TextInputControl input, ConfigField field, Access access) {
        input.setText(access.text(field));
        if (field.defaultValue() != null) {
            input.setPromptText(ResourcesEngine.getString("editor.default", field.defaultValue()));
        }
        input.textProperty().addListener((obs, old, text) -> apply(input, field, access, text));
        return input;
    }

    private static void apply(Control control, ConfigField field, Access access, String text) {
        try {
            access.setText(field, text);
            control.setStyle("");
            control.setTooltip(null);
        } catch (IllegalArgumentException e) {
            control.setStyle(INVALID_STYLE);
            control.setTooltip(new Tooltip(ResourcesEngine.getString("editor.invalid-value", text)));
        }
    }
}
```

- [ ] **Step 3: Write the editor view and its controller**

`copybot-ui/src/main/resources/com/copybot/ui/views/editor-view.fxml` :

```xml
<?xml version="1.0" encoding="UTF-8"?>

<?import javafx.geometry.Insets?>
<?import javafx.scene.control.Button?>
<?import javafx.scene.control.ScrollPane?>
<?import javafx.scene.control.SplitPane?>
<?import javafx.scene.control.TreeView?>
<?import javafx.scene.layout.BorderPane?>
<?import javafx.scene.layout.HBox?>
<?import javafx.scene.layout.Region?>
<?import javafx.scene.layout.VBox?>

<BorderPane prefHeight="700.0" prefWidth="1000.0" xmlns="http://javafx.com/javafx/null" xmlns:fx="http://javafx.com/fxml/1" fx:controller="com.copybot.ui.EditorController">
   <center>
      <SplitPane dividerPositions="0.3">
         <items>
            <VBox spacing="6.0">
               <padding>
                  <Insets bottom="8.0" left="8.0" right="8.0" top="8.0" />
               </padding>
               <children>
                  <TreeView fx:id="tree" VBox.vgrow="ALWAYS" />
                  <HBox spacing="6.0">
                     <children>
                        <Button fx:id="addButton" mnemonicParsing="false" onAction="#onAddClick" text="%editor.add" />
                        <Button fx:id="upButton" mnemonicParsing="false" onAction="#onUpClick" text="%editor.up" />
                        <Button fx:id="downButton" mnemonicParsing="false" onAction="#onDownClick" text="%editor.down" />
                        <Button fx:id="removeButton" mnemonicParsing="false" onAction="#onRemoveClick" text="%editor.remove" />
                     </children>
                  </HBox>
               </children>
            </VBox>
            <ScrollPane fitToWidth="true">
               <content>
                  <VBox fx:id="formBox" spacing="10.0">
                     <padding>
                        <Insets bottom="10.0" left="10.0" right="10.0" top="10.0" />
                     </padding>
                  </VBox>
               </content>
            </ScrollPane>
         </items>
      </SplitPane>
   </center>
   <bottom>
      <HBox spacing="10.0">
         <padding>
            <Insets bottom="8.0" left="8.0" right="8.0" top="8.0" />
         </padding>
         <children>
            <Button mnemonicParsing="false" onAction="#onShowJsonClick" text="%editor.show-json" />
            <Region HBox.hgrow="ALWAYS" />
            <Button mnemonicParsing="false" onAction="#onSaveAsClick" text="%editor.save-as" />
            <Button defaultButton="true" mnemonicParsing="false" onAction="#onSaveClick" text="%editor.save" />
         </children>
      </HBox>
   </bottom>
</BorderPane>
```

`copybot-ui/src/main/java/com/copybot/ui/EditorController.java` :

```java
package com.copybot.ui;

import com.copybot.engine.plugin.CatalogAction;
import com.copybot.engine.plugin.PluginEngine;
import com.copybot.plugin.api.config.ConfigField;
import com.copybot.plugin.api.config.ConfigSchema;
import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.model.PipelineDocument;
import com.copybot.ui.model.PipelineDocument.Problem;
import com.copybot.ui.model.PipelineDocument.Section;
import com.copybot.ui.model.PlanViewModel;
import com.copybot.ui.model.RecentPipelines;
import com.copybot.ui.model.StepCatalog;
import com.copybot.ui.util.PopinUtil;
import com.copybot.ui.util.Views;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The generic pipeline editor (spec desktop-ui §3, variant A): a tree of the sections on the left, the form
 * of the selected element on the right. It edits a {@link PipelineDocument}; only the fields it shows are
 * rewritten.
 */
public class EditorController {

    enum Kind { PIPELINE, SECTION, STEP }

    /** A node of the tree: the pipeline, a section, or the step at index of a section. */
    record EditorNode(Kind kind, Section section, int index) {
    }

    private static final EditorNode PIPELINE_NODE = new EditorNode(Kind.PIPELINE, null, -1);

    @FXML private TreeView<EditorNode> tree;
    @FXML private Button addButton;
    @FXML private Button upButton;
    @FXML private Button downButton;
    @FXML private Button removeButton;
    @FXML private VBox formBox;

    private Stage stage;
    private PipelineDocument document;
    private Path path;
    private StepCatalog catalog;
    private Consumer<Path> onSaved;

    /**
     * Opens the editor, modal, on this pipeline file or on a new pipeline (path null).
     *
     * @param onSaved called with the file after each save (on the JavaFX thread)
     */
    public static void open(Window owner, Path path, Consumer<Path> onSaved) {
        PipelineDocument document;
        try {
            document = path == null ? PipelineDocument.empty() : PipelineDocument.load(path);
        } catch (RuntimeException e) {
            PopinUtil.showError(e);
            return;
        }
        Views.Loaded<EditorController> view = Views.load("editor-view.fxml");
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setScene(new Scene(view.root()));
        view.controller().init(stage, document, path, onSaved);
        stage.showAndWait();
    }

    @FXML
    public void initialize() {
        tree.setShowRoot(false);
        tree.setCellFactory(t -> new TreeCell<>() {
            @Override
            protected void updateItem(EditorNode node, boolean empty) {
                super.updateItem(node, empty);
                setText(empty || node == null ? null : label(node));
            }
        });
        tree.getSelectionModel().selectedItemProperty().addListener((obs, old, item) -> {
            showForm(item == null ? null : item.getValue());
            updateButtons();
        });
    }

    void init(Stage stage, PipelineDocument document, Path path, Consumer<Path> onSaved) {
        this.stage = stage;
        this.document = document;
        this.path = path == null ? null : path.toAbsolutePath().normalize();
        this.onSaved = onSaved;
        this.catalog = new StepCatalog(PluginEngine.catalog());
        stage.setOnCloseRequest(e -> {
            if (document.isModified() && !confirm(ResourcesEngine.getString("editor.discard"))) {
                e.consume();
            }
        });
        updateTitle();
        rebuildTree(PIPELINE_NODE);
    }

    private void updateTitle() {
        String name = path == null ? ResourcesEngine.getString("editor.untitled") : RecentPipelines.displayName(path);
        stage.setTitle(ResourcesEngine.getString("editor.title", name));
    }

    // ---- tree ----

    private void rebuildTree(EditorNode select) {
        TreeItem<EditorNode> root = new TreeItem<>();
        TreeItem<EditorNode> selected = new TreeItem<>(PIPELINE_NODE);
        root.getChildren().add(selected);
        for (Section section : Section.values()) {
            EditorNode sectionNode = new EditorNode(Kind.SECTION, section, -1);
            TreeItem<EditorNode> sectionItem = new TreeItem<>(sectionNode);
            sectionItem.setExpanded(true);
            if (sectionNode.equals(select)) {
                selected = sectionItem;
            }
            for (int i = 0; i < document.steps(section).size(); i++) {
                TreeItem<EditorNode> stepItem = new TreeItem<>(new EditorNode(Kind.STEP, section, i));
                if (stepItem.getValue().equals(select)) {
                    selected = stepItem;
                }
                sectionItem.getChildren().add(stepItem);
            }
            root.getChildren().add(sectionItem);
        }
        tree.setRoot(root);
        tree.getSelectionModel().select(selected);
    }

    private String label(EditorNode node) {
        return switch (node.kind()) {
            case PIPELINE -> ResourcesEngine.getString("editor.section.PIPELINE");
            case SECTION -> ResourcesEngine.getString("editor.section." + node.section().name());
            case STEP -> {
                JsonObject step = step(node);
                yield catalog.find(node.section(), step).map(CatalogAction::name)
                        .orElseGet(() -> member(step, "action") + " (?)");
            }
        };
    }

    private JsonObject step(EditorNode node) {
        return document.steps(node.section()).get(node.index());
    }

    private static String member(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private EditorNode selectedNode() {
        TreeItem<EditorNode> item = tree.getSelectionModel().getSelectedItem();
        return item == null ? null : item.getValue();
    }

    private void updateButtons() {
        EditorNode node = selectedNode();
        boolean step = node != null && node.kind() == Kind.STEP;
        addButton.setDisable(node == null || node.section() == null || !document.canAdd(node.section()));
        upButton.setDisable(!step || !document.canMove(node.section(), node.index(), -1));
        downButton.setDisable(!step || !document.canMove(node.section(), node.index(), 1));
        removeButton.setDisable(!step);
    }

    @FXML
    protected void onAddClick() {
        Section section = selectedNode().section();
        chooseAction(section).ifPresent(action -> {
            document.addStep(section, action);
            rebuildTree(new EditorNode(Kind.STEP, section, document.steps(section).size() - 1));
        });
    }

    @FXML
    protected void onUpClick() {
        move(-1);
    }

    @FXML
    protected void onDownClick() {
        move(1);
    }

    private void move(int delta) {
        EditorNode node = selectedNode();
        document.moveStep(node.section(), node.index(), delta);
        rebuildTree(new EditorNode(Kind.STEP, node.section(), node.index() + delta));
    }

    @FXML
    protected void onRemoveClick() {
        EditorNode node = selectedNode();
        document.removeStep(node.section(), node.index());
        rebuildTree(new EditorNode(Kind.SECTION, node.section(), -1));
    }

    /** The loaded actions of the section's step type, name and description localized. */
    private Optional<CatalogAction> chooseAction(Section section) {
        List<CatalogAction> actions = catalog.forSection(section);
        Dialog<CatalogAction> dialog = new Dialog<>();
        dialog.initOwner(stage);
        dialog.setTitle(ResourcesEngine.getString("editor.add.title"));
        ListView<CatalogAction> list = new ListView<>();
        list.getItems().setAll(actions);
        list.setPlaceholder(new Label(ResourcesEngine.getString("editor.add.empty")));
        list.setCellFactory(l -> new ListCell<>() {
            @Override
            protected void updateItem(CatalogAction action, boolean empty) {
                super.updateItem(action, empty);
                if (empty || action == null) {
                    setGraphic(null);
                    return;
                }
                Label name = new Label(action.name());
                name.setStyle("-fx-font-weight: bold;");
                Label description = new Label(action.description());
                description.setWrapText(true);
                setGraphic(new VBox(2, name, description));
            }
        });
        list.setPrefSize(460, 300);
        dialog.getDialogPane().setContent(list);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(ButtonType.OK).disableProperty()
                .bind(list.getSelectionModel().selectedItemProperty().isNull());
        dialog.setResultConverter(button -> button == ButtonType.OK ? list.getSelectionModel().getSelectedItem() : null);
        return dialog.showAndWait();
    }

    // ---- forms ----

    private void showForm(EditorNode node) {
        formBox.getChildren().clear();
        if (node == null || node.kind() == Kind.SECTION) {
            return;
        }
        if (node.kind() == Kind.PIPELINE) {
            pipelineForm();
        } else {
            stepForm(node);
        }
    }

    /** startProcessingWhileListing, resume.mode, ui.autoExecute (spec desktop-ui §3). */
    private void pipelineForm() {
        CheckBox startProcessing = new CheckBox(ResourcesEngine.getString("editor.startProcessingWhileListing"));
        startProcessing.setSelected(document.startProcessingWhileListing());
        startProcessing.setOnAction(e -> document.setStartProcessingWhileListing(startProcessing.isSelected()));

        ComboBox<String> resumeMode = new ComboBox<>();
        resumeMode.getItems().add("");
        resumeMode.getItems().addAll(PipelineDocument.RESUME_MODES);
        resumeMode.setConverter(new StringConverter<>() {
            @Override
            public String toString(String mode) {
                return PlanViewModel.resumeModeText(mode == null || mode.isEmpty() ? null : mode);
            }

            @Override
            public String fromString(String s) {
                return s; // not editable
            }
        });
        resumeMode.setValue(document.resumeMode().orElse(""));
        resumeMode.valueProperty().addListener((obs, old, mode) ->
                document.setResumeMode(mode == null || mode.isEmpty() ? null : mode));

        CheckBox autoExecute = new CheckBox(ResourcesEngine.getString("editor.autoExecute"));
        autoExecute.setSelected(document.autoExecute());
        autoExecute.setOnAction(e -> document.setAutoExecute(autoExecute.isSelected()));

        formBox.getChildren().addAll(title(ResourcesEngine.getString("editor.section.PIPELINE")), startProcessing,
                new VBox(3, new Label(ResourcesEngine.getString("editor.resume-mode")), resumeMode), autoExecute);
    }

    /** The generated form of the action's schema, then "Advanced"; read-only when the plugin is missing. */
    private void stepForm(EditorNode node) {
        JsonObject step = step(node);
        Optional<CatalogAction> action = catalog.find(node.section(), step);
        if (action.isEmpty()) {
            String plugin = member(step, "plugin");
            formBox.getChildren().addAll(title(member(step, "action")),
                    new Label(ResourcesEngine.getString("editor.plugin-not-found",
                            plugin.isEmpty() ? CatalogAction.EMBEDDED_PLUGIN : plugin)),
                    readOnly(PipelineDocument.json(step)));
            return;
        }
        CatalogAction catalogAction = action.get();
        formBox.getChildren().add(title(catalogAction.name()));
        if (!catalogAction.description().isEmpty()) {
            Label description = new Label(catalogAction.description());
            description.setWrapText(true);
            formBox.getChildren().add(description);
        }
        Optional<ConfigSchema> schema = catalogAction.configSchema();
        if (schema.isPresent()) {
            formBox.getChildren().add(ConfigForm.build(schema.get().fields(), configAccess(step, catalogAction), stage));
        } else {
            formBox.getChildren().addAll(new Label(ResourcesEngine.getString("editor.no-schema")),
                    readOnly(PipelineDocument.json(step.get("actionConfig"))));
        }
        TitledPane advanced = new TitledPane(ResourcesEngine.getString("editor.advanced"),
                ConfigForm.build(PipelineDocument.ADVANCED_FIELDS, advancedAccess(step), stage));
        advanced.setExpanded(false);
        formBox.getChildren().add(advanced);
    }

    private ConfigForm.Access configAccess(JsonObject step, CatalogAction action) {
        return new ConfigForm.Access() {
            @Override
            public String text(ConfigField field) {
                return document.configText(step, field);
            }

            @Override
            public void setText(ConfigField field, String text) {
                document.setConfigText(step, field, text);
            }

            @Override
            public String label(ConfigField field) {
                return action.label(field);
            }

            @Override
            public String description(ConfigField field) {
                return action.description(field);
            }

            @Override
            public String json(ConfigField field) {
                return PipelineDocument.json(document.configValue(step, field));
            }
        };
    }

    private ConfigForm.Access advancedAccess(JsonObject step) {
        return new ConfigForm.Access() {
            @Override
            public String text(ConfigField field) {
                return document.advancedText(step, field);
            }

            @Override
            public void setText(ConfigField field, String text) {
                document.setAdvancedText(step, field, text);
                tree.refresh(); // "version" may change which plugin the step resolves to
            }

            @Override
            public String label(ConfigField field) {
                return ResourcesEngine.getString(field.labelKey());
            }

            @Override
            public String description(ConfigField field) {
                return ResourcesEngine.getString(field.descriptionKey());
            }

            @Override
            public String json(ConfigField field) {
                return PipelineDocument.json(step.get(field.name()));
            }
        };
    }

    private static Label title(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        return label;
    }

    private static TextArea readOnly(String text) {
        TextArea area = new TextArea(text);
        area.setEditable(false);
        area.setPrefRowCount(12);
        return area;
    }

    // ---- save ----

    @FXML
    protected void onSaveClick() {
        if (path == null) {
            onSaveAsClick();
        } else {
            save(path);
        }
    }

    @FXML
    protected void onSaveAsClick() {
        FileChooser chooser = new FileChooser();
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(ResourcesEngine.getString("home.file-filter"), "*.json"));
        if (path != null) {
            chooser.setInitialDirectory(path.getParent().toFile());
            chooser.setInitialFileName(path.getFileName().toString());
        }
        File file = chooser.showSaveDialog(stage);
        if (file != null) {
            Path chosen = file.toPath();
            if (!chosen.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json")) {
                chosen = chosen.resolveSibling(chosen.getFileName() + ".json");
            }
            save(chosen.toAbsolutePath().normalize());
        }
    }

    /** Refused while a required field is empty (spec desktop-ui §3). */
    private void save(Path target) {
        List<Problem> problems = document.validate(catalog);
        if (!problems.isEmpty()) {
            List<String> lines = new ArrayList<>();
            for (Problem problem : problems) {
                lines.add(ResourcesEngine.getString("editor.section." + problem.section().name())
                        + " #" + (problem.index() + 1) + " : " + fieldLabel(problem));
            }
            Alert alert = new Alert(Alert.AlertType.WARNING,
                    ResourcesEngine.getString("editor.required-missing", String.join("\n", lines)), ButtonType.OK);
            alert.initOwner(stage);
            alert.showAndWait();
            return;
        }
        try {
            document.save(target);
        } catch (IOException e) {
            PopinUtil.showError(e);
            return;
        }
        path = target;
        updateTitle();
        onSaved.accept(target);
    }

    private String fieldLabel(Problem problem) {
        JsonObject step = document.steps(problem.section()).get(problem.index());
        return catalog.find(problem.section(), step)
                .flatMap(action -> action.configSchema()
                        .flatMap(schema -> schema.field(problem.fieldPath()))
                        .map(action::label))
                .orElse(problem.fieldPath());
    }

    @FXML
    protected void onShowJsonClick() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(stage);
        alert.setTitle(ResourcesEngine.getString("editor.json.title"));
        alert.setHeaderText(null);
        TextArea json = readOnly(document.toJson());
        json.setPrefSize(640, 480);
        alert.getDialogPane().setContent(json);
        alert.setResizable(true);
        alert.showAndWait();
    }

    private boolean confirm(String question) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, question, ButtonType.OK, ButtonType.CANCEL);
        alert.initOwner(stage);
        return alert.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
    }
}
```

- [ ] **Step 4: Compile and run the tests**

Run: `mvn -o -q -pl copybot-engine,copybot-ui test`
Expected: PASS (aucune ligne `[ERROR]` ; l'ancienne vue `hello-view.fxml` est toujours la vue principale).

- [ ] **Step 5: Commit**

```bash
git add copybot-ui/src/main/java/com/copybot/ui/util/Views.java copybot-ui/src/main/java/com/copybot/ui/ConfigForm.java copybot-ui/src/main/java/com/copybot/ui/EditorController.java copybot-ui/src/main/resources/com/copybot/ui/views/editor-view.fxml
git commit -m "Add the generic pipeline editor with schema-generated forms" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 16: Fenêtre principale, écran d'accueil et vue du plan

**Files:**
- Create: `copybot-ui/src/main/java/com/copybot/ui/MainController.java`, `copybot-ui/src/main/resources/com/copybot/ui/views/main-view.fxml`
- Create: `copybot-ui/src/main/java/com/copybot/ui/HomeController.java`, `copybot-ui/src/main/resources/com/copybot/ui/views/home-view.fxml`
- Create: `copybot-ui/src/main/java/com/copybot/ui/PlanController.java`, `copybot-ui/src/main/resources/com/copybot/ui/views/plan-view.fxml`
- Modify (réécriture): `copybot-ui/src/main/java/com/copybot/ui/CopybotMainUi.java` (LF)
- Delete: `copybot-ui/src/main/java/com/copybot/ui/HelloController.java`, `HelloController2.java`, `copybot-ui/src/main/resources/com/copybot/ui/views/hello-view.fxml`, `hello-view2.fxml`

**Interfaces:**
- Consumes: tout le modèle (7, 9–14), l'éditeur (15), `CopybotEngine.prepare` / `execute`, `Execution.pause` / `resume` / `cancel` / `getState`, `Plan.preview` / `fromFile` / `fromDate` / `targetOf` / `getOrderedItems` (6), `UiPreferences` (7), `PreferencesController` / `preferences-view.fxml` (existants, inchangés).
- Produces: `CopybotMainUi` charge `main-view.fxml` (menu + centre) ; `MainController.showHome()`, `showPlan(Path)` (le pipeline devient le plus récent), `setBusy(boolean)` ; le pipeline affiché survit à `CopybotMainUi.reloadMainView()` (changement de langue) ; l'accueil liste les récents ; la vue du plan prépare en tâche de fond (`CopybotMainUi.executor`), reçoit le watcher via `Platform.runLater`, exécute, met en pause / reprend / arrête, enregistre la dernière exécution. `CopybotMainUiDev` est inchangé.

- [ ] **Step 1: Write the main window**

`copybot-ui/src/main/resources/com/copybot/ui/views/main-view.fxml` :

```xml
<?xml version="1.0" encoding="UTF-8"?>

<?import javafx.scene.control.Menu?>
<?import javafx.scene.control.MenuBar?>
<?import javafx.scene.control.MenuItem?>
<?import javafx.scene.layout.BorderPane?>

<BorderPane fx:id="root" prefHeight="768.0" prefWidth="1024.0" xmlns="http://javafx.com/javafx/null" xmlns:fx="http://javafx.com/fxml/1" fx:controller="com.copybot.ui.MainController">
   <top>
      <MenuBar>
         <menus>
            <Menu mnemonicParsing="false" text="%menu.file">
               <items>
                  <MenuItem mnemonicParsing="false" onAction="#onExitClick" text="%menu.file.exit" />
               </items>
            </Menu>
            <Menu mnemonicParsing="false" text="%menu.edit">
               <items>
                  <MenuItem fx:id="preferencesItem" mnemonicParsing="false" onAction="#onPreferencesClick" text="%menu.edit.preferences" />
               </items>
            </Menu>
            <Menu mnemonicParsing="false" text="%menu.help">
               <items>
                  <MenuItem mnemonicParsing="false" text="%menu.help.about" />
               </items>
            </Menu>
         </menus>
      </MenuBar>
   </top>
</BorderPane>
```

`copybot-ui/src/main/java/com/copybot/ui/MainController.java` :

```java
package com.copybot.ui;

import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.util.PopinUtil;
import com.copybot.ui.util.UiPreferences;
import com.copybot.ui.util.Views;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.BorderPane;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/** The main window: the menu, and the home screen or the plan view of one pipeline in the center. */
public class MainController {

    /** The pipeline of the plan view, null on the home screen: kept when the view is reloaded (language). */
    private static Path shownPipeline;

    @FXML
    private BorderPane root;

    @FXML
    private MenuItem preferencesItem;

    @FXML
    public void initialize() {
        if (shownPipeline != null && Files.isRegularFile(shownPipeline)) {
            showPlan(shownPipeline);
        } else {
            showHome();
        }
    }

    public void showHome() {
        shownPipeline = null;
        setBusy(false);
        Views.Loaded<HomeController> home = Views.load("home-view.fxml");
        home.controller().init(this);
        root.setCenter(home.root());
    }

    /** Opens the plan view of the pipeline, nothing prepared (spec desktop-ui §1), and makes it the most recent. */
    public void showPlan(Path pipeline) {
        UiPreferences.updateRecents(recents -> recents.touch(pipeline, Instant.now()));
        shownPipeline = pipeline.toAbsolutePath().normalize();
        setBusy(false);
        Views.Loaded<PlanController> plan = Views.load("plan-view.fxml");
        plan.controller().init(this, shownPipeline);
        root.setCenter(plan.root());
    }

    /** While a preparation or a copy runs the language cannot change (the view would be rebuilt). */
    public void setBusy(boolean busy) {
        preferencesItem.setDisable(busy);
    }

    @FXML
    protected void onExitClick() {
        Platform.exit(); // triggers Application.stop(): executor shutdown + engine close
    }

    @FXML
    protected void onPreferencesClick() {
        try {
            Views.Loaded<PreferencesController> preferences = Views.load("preferences-view.fxml");
            Stage dialog = new Stage();
            dialog.setTitle(ResourcesEngine.getString("pref.title"));
            dialog.setScene(new Scene(preferences.root()));
            dialog.initModality(Modality.APPLICATION_MODAL);
            dialog.initOwner(CopybotMainUi.STAGE);
            dialog.showAndWait();
        } catch (RuntimeException e) {
            PopinUtil.showError(e);
        }
    }
}
```

- [ ] **Step 2: Write the home screen**

`copybot-ui/src/main/resources/com/copybot/ui/views/home-view.fxml` :

```xml
<?xml version="1.0" encoding="UTF-8"?>

<?import javafx.geometry.Insets?>
<?import javafx.scene.control.Button?>
<?import javafx.scene.control.Label?>
<?import javafx.scene.control.ListView?>
<?import javafx.scene.layout.HBox?>
<?import javafx.scene.layout.Region?>
<?import javafx.scene.layout.VBox?>

<VBox spacing="10.0" xmlns="http://javafx.com/javafx/null" xmlns:fx="http://javafx.com/fxml/1" fx:controller="com.copybot.ui.HomeController">
   <padding>
      <Insets bottom="15.0" left="15.0" right="15.0" top="15.0" />
   </padding>
   <children>
      <HBox alignment="CENTER_LEFT" spacing="10.0">
         <children>
            <Label style="-fx-font-size: 18px; -fx-font-weight: bold;" text="%home.title" />
            <Region HBox.hgrow="ALWAYS" />
            <Button mnemonicParsing="false" onAction="#onOpenClick" text="%home.open" />
            <Button mnemonicParsing="false" onAction="#onNewClick" text="%home.new" />
         </children>
      </HBox>
      <ListView fx:id="recentList" VBox.vgrow="ALWAYS" />
   </children>
</VBox>
```

`copybot-ui/src/main/java/com/copybot/ui/HomeController.java` :

```java
package com.copybot.ui;

import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.model.PlanViewModel;
import com.copybot.ui.model.RecentPipelines;
import com.copybot.ui.util.UiPreferences;
import javafx.fxml.FXML;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.time.Instant;

/** The home screen (spec desktop-ui §1): the recent pipelines; nothing runs until one is prepared. */
public class HomeController {

    @FXML
    private ListView<RecentPipelines.Entry> recentList;

    private MainController main;

    @FXML
    public void initialize() {
        recentList.setPlaceholder(new Label(ResourcesEngine.getString("home.empty")));
        recentList.setCellFactory(list -> new RecentCell());
    }

    void init(MainController main) {
        this.main = main;
        refresh();
    }

    private void refresh() {
        recentList.getItems().setAll(UiPreferences.recents().entries());
    }

    @FXML
    protected void onOpenClick() {
        FileChooser chooser = new FileChooser();
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(ResourcesEngine.getString("home.file-filter"), "*.json"));
        File file = chooser.showOpenDialog(CopybotMainUi.STAGE);
        if (file != null) {
            main.showPlan(file.toPath());
        }
    }

    @FXML
    protected void onNewClick() {
        EditorController.open(CopybotMainUi.STAGE, null, saved -> {
            UiPreferences.updateRecents(recents -> recents.touch(saved, Instant.now()));
            refresh();
        });
    }

    /** Name, path and last run; greyed "not found" when the file is gone; right click: remove. */
    private final class RecentCell extends ListCell<RecentPipelines.Entry> {
        private final Label name = new Label();
        private final Label path = new Label();
        private final Label lastRun = new Label();
        private final VBox box = new VBox(2, name, path, lastRun);
        private final ContextMenu menu = new ContextMenu();

        RecentCell() {
            name.setStyle("-fx-font-weight: bold;");
            path.setStyle("-fx-font-size: 11px;");
            MenuItem remove = new MenuItem(ResourcesEngine.getString("home.remove"));
            remove.setOnAction(e -> {
                if (getItem() != null) {
                    UiPreferences.updateRecents(recents -> recents.remove(getItem().path()));
                    refresh();
                }
            });
            menu.getItems().add(remove);
            setOnMouseClicked(e -> {
                RecentPipelines.Entry entry = getItem();
                if (e.getButton() == MouseButton.PRIMARY && entry != null && !entry.isMissing()) {
                    main.showPlan(entry.path());
                }
            });
        }

        @Override
        protected void updateItem(RecentPipelines.Entry entry, boolean empty) {
            super.updateItem(entry, empty);
            if (empty || entry == null) {
                setGraphic(null);
                setContextMenu(null);
                return;
            }
            boolean missing = entry.isMissing();
            name.setText(entry.displayName() + (missing ? " (" + ResourcesEngine.getString("home.missing") + ")" : ""));
            path.setText(entry.path().toString());
            lastRun.setText(PlanViewModel.lastRunText(entry.lastRun()));
            box.setOpacity(missing ? 0.5 : 1.0);
            setGraphic(box);
            setContextMenu(menu);
        }
    }
}
```

- [ ] **Step 3: Write the plan view**

`copybot-ui/src/main/resources/com/copybot/ui/views/plan-view.fxml` :

```xml
<?xml version="1.0" encoding="UTF-8"?>

<?import javafx.geometry.Insets?>
<?import javafx.scene.control.Button?>
<?import javafx.scene.control.CheckBox?>
<?import javafx.scene.control.ComboBox?>
<?import javafx.scene.control.Hyperlink?>
<?import javafx.scene.control.Label?>
<?import javafx.scene.control.ProgressBar?>
<?import javafx.scene.control.TableColumn?>
<?import javafx.scene.control.TableView?>
<?import javafx.scene.layout.HBox?>
<?import javafx.scene.layout.Region?>
<?import javafx.scene.layout.VBox?>

<VBox spacing="8.0" xmlns="http://javafx.com/javafx/null" xmlns:fx="http://javafx.com/fxml/1" fx:controller="com.copybot.ui.PlanController">
   <padding>
      <Insets bottom="10.0" left="10.0" right="10.0" top="10.0" />
   </padding>
   <children>
      <HBox alignment="CENTER_LEFT" spacing="10.0">
         <children>
            <Button fx:id="backButton" mnemonicParsing="false" onAction="#onBackClick" text="%plan.back" />
            <Label fx:id="pipelineName" style="-fx-font-size: 18px; -fx-font-weight: bold;" />
            <Region HBox.hgrow="ALWAYS" />
            <Button fx:id="editButton" mnemonicParsing="false" onAction="#onEditClick" text="%plan.edit" />
         </children>
      </HBox>
      <Label fx:id="summaryLabel" maxWidth="Infinity" wrapText="true" />
      <Label fx:id="warningBanner" managed="false" maxWidth="Infinity" style="-fx-background-color: #fff3cd; -fx-text-fill: #664d03; -fx-padding: 6;" visible="false" wrapText="true" />
      <HBox fx:id="resumeBox" alignment="CENTER_LEFT" managed="false" spacing="6.0" visible="false">
         <children>
            <Label fx:id="resumeLabel" />
            <Hyperlink fx:id="changeResumeLink" onAction="#onChangeResumeClick" text="%plan.resume.change" />
         </children>
      </HBox>
      <HBox alignment="CENTER_LEFT" spacing="6.0">
         <children>
            <ComboBox fx:id="filterCombo" />
         </children>
      </HBox>
      <TableView fx:id="itemsTable" VBox.vgrow="ALWAYS">
         <columns>
            <TableColumn fx:id="nameColumn" prefWidth="220.0" text="%table.name" />
            <TableColumn fx:id="dateColumn" prefWidth="130.0" text="%plan.column.date" />
            <TableColumn fx:id="targetColumn" prefWidth="260.0" text="%plan.column.target" />
            <TableColumn fx:id="sizeColumn" prefWidth="90.0" text="%table.size" />
            <TableColumn fx:id="statusColumn" prefWidth="240.0" text="%table.status" />
         </columns>
         <columnResizePolicy>
            <TableView fx:constant="CONSTRAINED_RESIZE_POLICY" />
         </columnResizePolicy>
      </TableView>
      <HBox fx:id="progressBox" alignment="CENTER_LEFT" managed="false" spacing="10.0" visible="false">
         <children>
            <ProgressBar fx:id="progressBar" maxWidth="Infinity" progress="0.0" HBox.hgrow="ALWAYS" />
            <Label fx:id="progressLabel" />
         </children>
      </HBox>
      <HBox alignment="CENTER_LEFT" spacing="10.0">
         <children>
            <Button fx:id="prepareButton" mnemonicParsing="false" onAction="#onPrepareClick" text="%plan.prepare" />
            <Button fx:id="copyButton" defaultButton="true" managed="false" mnemonicParsing="false" onAction="#onCopyClick" visible="false" />
            <CheckBox fx:id="autoExecuteBox" mnemonicParsing="false" onAction="#onAutoExecuteClick" text="%plan.auto-execute" />
            <Button fx:id="pauseButton" managed="false" mnemonicParsing="false" onAction="#onPauseClick" text="%plan.pause" visible="false" />
            <Button fx:id="resumeButton" managed="false" mnemonicParsing="false" onAction="#onResumeClick" text="%plan.resume" visible="false" />
            <Button fx:id="stopButton" managed="false" mnemonicParsing="false" onAction="#onStopClick" text="%plan.stop" visible="false" />
            <Label fx:id="statusLine" maxWidth="Infinity" HBox.hgrow="ALWAYS" />
         </children>
      </HBox>
   </children>
</VBox>
```

`copybot-ui/src/main/java/com/copybot/ui/PlanController.java` :

```java
package com.copybot.ui;

import com.copybot.engine.Execution;
import com.copybot.engine.Plan;
import com.copybot.engine.pipeline.PipelineState;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.engine.plugin.PluginEngine;
import com.copybot.engine.resume.ItemKey;
import com.copybot.engine.resume.ResumePoint;
import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.model.PipelineDocument;
import com.copybot.ui.model.PipelineSummary;
import com.copybot.ui.model.PlanViewModel;
import com.copybot.ui.model.PlanViewModel.Filter;
import com.copybot.ui.model.PlanViewModel.Phase;
import com.copybot.ui.model.RecentPipelines;
import com.copybot.ui.model.StepCatalog;
import com.copybot.ui.util.PopinUtil;
import com.copybot.ui.util.UiPreferences;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The plan view of one pipeline (spec desktop-ui §2), a thin layer over {@link PlanViewModel}: it starts
 * the engine operations (the preparation on a background thread), feeds the model on the JavaFX thread
 * and renders it.
 */
public class PlanController {

    @FXML private Button backButton;
    @FXML private Label pipelineName;
    @FXML private Button editButton;
    @FXML private Label summaryLabel;
    @FXML private Label warningBanner;
    @FXML private HBox resumeBox;
    @FXML private Label resumeLabel;
    @FXML private Hyperlink changeResumeLink;
    @FXML private ComboBox<Filter> filterCombo;
    @FXML private TableView<WorkItemExecution> itemsTable;
    @FXML private TableColumn<WorkItemExecution, String> nameColumn;
    @FXML private TableColumn<WorkItemExecution, String> dateColumn;
    @FXML private TableColumn<WorkItemExecution, String> targetColumn;
    @FXML private TableColumn<WorkItemExecution, String> sizeColumn;
    @FXML private TableColumn<WorkItemExecution, String> statusColumn;
    @FXML private HBox progressBox;
    @FXML private ProgressBar progressBar;
    @FXML private Label progressLabel;
    @FXML private Button prepareButton;
    @FXML private Button copyButton;
    @FXML private CheckBox autoExecuteBox;
    @FXML private Button pauseButton;
    @FXML private Button resumeButton;
    @FXML private Button stopButton;
    @FXML private Label statusLine;

    private final ObservableList<WorkItemExecution> rows = FXCollections.observableArrayList();

    private MainController main;
    private Path pipelinePath;
    private StepCatalog catalog;
    private PlanViewModel model = new PlanViewModel(false);
    private PipelineSummary summary;
    private Plan plan;
    private Execution execution;

    @FXML
    public void initialize() {
        itemsTable.setItems(rows);
        nameColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getWorkItem().getNameDisplay()));
        dateColumn.setCellValueFactory(c -> new SimpleStringProperty(PlanViewModel.dateText(c.getValue())));
        targetColumn.setCellValueFactory(c -> new SimpleStringProperty(targetText(c.getValue())));
        sizeColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getWorkItem().getMetadatas().getSizeHr()));
        statusColumn.setCellValueFactory(c -> new SimpleStringProperty(PlanViewModel.statusText(c.getValue())));
        itemsTable.setRowFactory(table -> resumeFromHereRow());

        filterCombo.getItems().setAll(Filter.values());
        filterCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(Filter filter) {
                return filter == null ? "" : ResourcesEngine.getString("plan.filter." + filter.name());
            }

            @Override
            public Filter fromString(String s) {
                return null; // not editable
            }
        });
        filterCombo.setValue(Filter.ALL);
        filterCombo.valueProperty().addListener((obs, old, filter) -> {
            model.setFilter(filter == null ? Filter.ALL : filter);
            refresh();
        });
    }

    void init(MainController main, Path pipelinePath) {
        this.main = main;
        this.pipelinePath = pipelinePath;
        pipelineName.setText(RecentPipelines.displayName(pipelinePath));
        reload();
    }

    /** (Re)reads the pipeline: the prepared plan, if any, is dropped (spec desktop-ui §3). */
    private void reload() {
        plan = null;
        execution = null;
        catalog = new StepCatalog(PluginEngine.catalog());
        PipelineDocument document = null;
        try {
            document = PipelineDocument.load(pipelinePath);
        } catch (RuntimeException e) {
            PopinUtil.showError(e); // the preparation will report it too
        }
        model = new PlanViewModel(document != null && document.autoExecute());
        model.setFilter(filterCombo.getValue() == null ? Filter.ALL : filterCombo.getValue());
        autoExecuteBox.setSelected(model.isAutoExecute());
        summary = document == null ? null : PipelineSummary.of(document, catalog);
        renderSummary();
        refresh();
    }

    /** Steps, source, output pattern, resume mode, last run. */
    private void renderSummary() {
        if (summary == null) {
            summaryLabel.setText("");
            return;
        }
        List<String> lines = new ArrayList<>();
        lines.add(ResourcesEngine.getString("plan.steps", String.join(" → ", summary.stepNames())));
        if (summary.sourcePath() != null) {
            lines.add(ResourcesEngine.getString("plan.source", summary.sourcePath()));
        }
        if (summary.outPattern() != null) {
            lines.add(ResourcesEngine.getString("plan.out-pattern", summary.outPattern()));
        }
        lines.add(ResourcesEngine.getString("plan.resume-mode", PlanViewModel.resumeModeText(summary.resumeMode())));
        RecentPipelines.LastRun lastRun = UiPreferences.recents().entries().stream()
                .filter(e -> e.path().equals(pipelinePath.toAbsolutePath().normalize()))
                .findFirst()
                .map(RecentPipelines.Entry::lastRun) // null when never run: empty
                .orElse(null);
        lines.add(ResourcesEngine.getString("plan.last-run", PlanViewModel.lastRunText(lastRun)));
        summaryLabel.setText(String.join("\n", lines));
    }

    private String targetText(WorkItemExecution item) {
        return plan == null ? "" : plan.targetOf(item).map(Path::toString).orElse("");
    }

    // ---- engine operations ----

    @FXML
    protected void onPrepareClick() {
        PlanViewModel target = model;
        plan = null;
        execution = null;
        target.startPreparing();
        refresh();
        CopybotMainUi.executor.submit(() -> {
            try {
                Plan prepared = CopybotMainUi.ENGINE.prepare(pipelinePath, state -> onState(target, state));
                Platform.runLater(() -> onPrepared(target, prepared));
            } catch (RuntimeException e) { // missing or invalid pipeline file, engine busy or closed
                Platform.runLater(() -> {
                    if (target == model) {
                        model.reset();
                        refresh();
                    }
                    PopinUtil.showError(e);
                });
            }
        });
    }

    /** Watcher notifications (background thread, coalesced ~10 Hz) of the preparation and the execution. */
    private void onState(PlanViewModel target, PipelineState state) {
        Platform.runLater(() -> {
            if (target == model) { // a reload replaced the model: the old plan is no longer shown
                model.update(state, plan == null ? List.of() : plan.getOrderedItems());
                refresh();
            }
        });
    }

    private void onPrepared(PlanViewModel target, Plan prepared) {
        if (target != model) {
            return;
        }
        plan = prepared;
        model.update(prepared.getState(), prepared.getOrderedItems());
        refresh();
        if (model.consumeAutoExecute()) {
            onCopyClick();
        }
    }

    @FXML
    protected void onCopyClick() {
        try {
            execution = CopybotMainUi.ENGINE.execute(plan, model.override()); // the preparation's watcher goes on
            model.startExecuting();
        } catch (RuntimeException e) {
            PopinUtil.showError(e);
        }
        refresh();
    }

    @FXML
    protected void onPauseClick() {
        execution.pause();
        updateNow();
    }

    @FXML
    protected void onResumeClick() {
        execution.resume();
        updateNow();
    }

    @FXML
    protected void onStopClick() {
        execution.cancel();
        updateNow();
    }

    /** A pause or resume changes the status without a watcher notification. */
    private void updateNow() {
        model.update(execution.getState(), plan.getOrderedItems());
        refresh();
    }

    @FXML
    protected void onAutoExecuteClick() {
        model.setAutoExecute(autoExecuteBox.isSelected()); // this session only: the file is not changed
    }

    @FXML
    protected void onBackClick() {
        main.showHome();
    }

    @FXML
    protected void onEditClick() {
        EditorController.open(CopybotMainUi.STAGE, pipelinePath, saved -> {
            if (saved.toAbsolutePath().normalize().equals(pipelinePath)) {
                reload();
            } else {
                main.showPlan(saved); // "save as": the plan view follows the new file
            }
        });
    }

    // ---- resume point ----

    private TableRow<WorkItemExecution> resumeFromHereRow() {
        TableRow<WorkItemExecution> row = new TableRow<>();
        MenuItem fromHere = new MenuItem(ResourcesEngine.getString("plan.menu.resume-from-here"));
        fromHere.setOnAction(e -> model.resumePointFrom(row.getItem()).ifPresent(this::applyResumePoint));
        ContextMenu menu = new ContextMenu(fromHere);
        menu.setOnShowing(e -> fromHere.setDisable(row.getItem() == null || model.resumePointFrom(row.getItem()).isEmpty()));
        row.contextMenuProperty().bind(Bindings.when(row.emptyProperty())
                .then((ContextMenu) null).otherwise(menu));
        return row;
    }

    @FXML
    protected void onChangeResumeClick() {
        resumeDialog().showAndWait().ifPresent(this::applyResumePoint);
    }

    private void applyResumePoint(ResumePoint point) {
        try {
            plan.preview(point); // the statuses are recomputed, nothing is executed
            model.setOverride(point);
            model.update(plan.getState(), plan.getOrderedItems());
        } catch (RuntimeException e) {
            PopinUtil.showError(e);
        }
        refresh();
    }

    /** Everything / from a date / from a file of the plan. */
    private Dialog<ResumePoint> resumeDialog() {
        Dialog<ResumePoint> dialog = new Dialog<>();
        dialog.initOwner(CopybotMainUi.STAGE);
        dialog.setTitle(ResourcesEngine.getString("resume.dialog.title"));
        ToggleGroup group = new ToggleGroup();
        RadioButton all = new RadioButton(ResourcesEngine.getString("resume.dialog.all"));
        RadioButton fromDate = new RadioButton(ResourcesEngine.getString("resume.dialog.date"));
        RadioButton fromFile = new RadioButton(ResourcesEngine.getString("resume.dialog.file"));
        List.of(all, fromDate, fromFile).forEach(b -> b.setToggleGroup(group));
        all.setSelected(true);
        DatePicker date = new DatePicker(LocalDate.now());
        ComboBox<String> file = new ComboBox<>();
        plan.getOrderedItems().stream()
                .map(WorkItemExecution::getResumeKey)
                .flatMap(Optional::stream)
                .map(ItemKey::name)
                .forEach(file.getItems()::add);
        if (!file.getItems().isEmpty()) {
            file.setValue(file.getItems().getFirst());
        }
        date.disableProperty().bind(fromDate.selectedProperty().not());
        file.disableProperty().bind(fromFile.selectedProperty().not());
        VBox content = new VBox(8, all, new HBox(8, fromDate, date), new HBox(8, fromFile, file));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Node ok = dialog.getDialogPane().lookupButton(ButtonType.OK);
        ok.disableProperty().bind(fromFile.selectedProperty().and(file.valueProperty().isNull())
                .or(fromDate.selectedProperty().and(date.valueProperty().isNull())));
        dialog.setResultConverter(button -> {
            if (button != ButtonType.OK) {
                return null;
            }
            if (fromDate.isSelected()) {
                return Plan.fromDate(date.getValue());
            }
            if (fromFile.isSelected()) {
                return plan.fromFile(file.getValue());
            }
            return ResumePoint.all();
        });
        return dialog;
    }

    // ---- rendering ----

    private void refresh() {
        Phase phase = model.phase();
        backButton.setDisable(!model.canGoBack());
        editButton.setDisable(!model.canGoBack());
        prepareButton.setDisable(!model.canPrepare());

        show(copyButton, phase == Phase.PREPARED);
        copyButton.setText(model.copyLabel());
        copyButton.setDisable(!model.canCopy());
        show(autoExecuteBox, !model.isExecutionActive());
        show(pauseButton, model.isExecutionActive() && !model.canResume());
        show(resumeButton, model.canResume());
        show(stopButton, model.isExecutionActive());
        pauseButton.setDisable(!model.canPause());
        stopButton.setDisable(!model.canStop());

        show(progressBox, model.isExecutionActive() || phase == Phase.FINISHED);
        progressBar.setProgress(model.progress().fraction());
        progressLabel.setText(model.progressText());
        statusLine.setText(model.statusLine());

        List<String> warnings = model.warnings();
        warningBanner.setText(String.join("\n", warnings));
        show(warningBanner, !warnings.isEmpty());
        Optional<String> resume = model.resumeText();
        resumeLabel.setText(resume.orElse(""));
        show(resumeBox, resume.isPresent());
        changeResumeLink.setDisable(!model.canChangeResumePoint());

        itemsTable.setPlaceholder(new Label(phase == Phase.NOT_PREPARED ? ResourcesEngine.getString("plan.placeholder") : ""));
        List<WorkItemExecution> visible = model.visibleItems();
        if (sameRows(visible)) {
            itemsTable.refresh(); // same rows, their status or percent evolved
        } else {
            rows.setAll(visible);
        }

        main.setBusy(model.isActive());
        model.consumeFinishedRun(Instant.now()).ifPresent(run -> {
            UiPreferences.updateRecents(recents -> recents.recordRun(pipelinePath, run));
            renderSummary(); // its "last run" line
        });
    }

    private boolean sameRows(List<WorkItemExecution> visible) {
        if (visible.size() != rows.size()) {
            return false;
        }
        for (int i = 0; i < visible.size(); i++) {
            if (visible.get(i) != rows.get(i)) {
                return false;
            }
        }
        return true;
    }

    private static void show(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }
}
```

- [ ] **Step 4: Switch the application to the new views and remove the test bench**

Réécrire `copybot-ui/src/main/java/com/copybot/ui/CopybotMainUi.java` en entier (Write) :

```java
package com.copybot.ui;

import com.copybot.engine.CopybotEngine;
import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.util.PopinUtil;
import com.copybot.ui.util.UiPreferences;
import com.copybot.ui.util.Views;
import javafx.application.Application;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CopybotMainUi extends Application {
    public static Stage STAGE;

    /** The engine of this window: created at startup, closed on exit (one pipeline at a time). */
    public static CopybotEngine ENGINE;

    public static ExecutorService executor;


    @Override
    public void start(Stage stage) {
        var params = getParameters();
        Optional<Path> pathArg = Optional.ofNullable(params.getNamed().get("config-file")).map(Path::of);

        try {
            ResourcesEngine.registerBundle("com.copybot.ui.i18n.uiBundle");
            ResourcesEngine.addSupportedLocale(Locale.ITALIAN); // the UI ships an it bundle
            UiPreferences.savedLanguage().ifPresent(ResourcesEngine::loadLanguage);
            ENGINE = CopybotEngine.create(pathArg);
        } catch (Exception e) {
            PopinUtil.showError(e);
            System.exit(1);
        }

        STAGE = stage;
        executor = Executors.newCachedThreadPool(); // the plan preparations (never on the JavaFX thread)
        Scene scene = new Scene(loadMainView()); // the home screen: nothing runs at startup (spec desktop-ui §1)
        stage.setMaximized(true);
        stage.setTitle("Copybot");
        stage.getIcons().add(new Image(CopybotMainUi.class.getResourceAsStream("Copybot.png")));
        stage.setScene(scene);
        stage.show();
    }

    /**
     * Rebuilds the main view with the current resource bundle (e.g. after a language change): the home
     * screen, or the plan view of the same pipeline, not prepared.
     */
    public static void reloadMainView() {
        try {
            STAGE.getScene().setRoot(loadMainView());
        } catch (RuntimeException e) {
            PopinUtil.showError(e);
        }
    }

    private static Parent loadMainView() {
        return Views.load("main-view.fxml").root();
    }

    @Override
    public void stop() {
        if (executor != null) {
            executor.shutdown();
        }
        if (ENGINE != null) {
            ENGINE.close(); // cancels a running copy and waits for it to release its files
        }
    }

    public static void main(String[] args) {
        launch(args);
    }


}
```

puis :

```bash
git rm -q copybot-ui/src/main/java/com/copybot/ui/HelloController.java copybot-ui/src/main/java/com/copybot/ui/HelloController2.java copybot-ui/src/main/resources/com/copybot/ui/views/hello-view.fxml copybot-ui/src/main/resources/com/copybot/ui/views/hello-view2.fxml
grep -rn "hello-view\|HelloController" copybot-ui/src
```

Expected : aucune ligne (grep sort en code 1 : attendu).

- [ ] **Step 5: Compile and run the tests**

Run: `mvn -o -q -pl copybot-engine,copybot-ui test`
Expected: PASS (aucune ligne `[ERROR]`).

- [ ] **Step 6: Manual verification**

Lancer l'IHM (voir Global Constraints : `mvn -o -q -pl copybot-engine install -DskipTests` puis `mvn -o -q -pl copybot-ui compile javafx:run "-Djavafx.mainClass=com.copybot.ui/com.copybot.ui.CopybotMainUiDev" "-Djavafx.workingDirectory=$(pwd -W)"`, ou `CopybotMainUiDev` depuis l'IDE, répertoire de travail = racine du repo), après `rm -rf copybot-ui/target/ui-test-out`. Cocher chaque point ; un écart est un bug de la tâche.

Accueil (spec §1) :
- [ ] La fenêtre s'ouvre sur « Pipelines » (liste des récents, ou « Aucun pipeline récent… ») ; **rien n'est lancé** : `copybot-ui/target/ui-test-out` n'existe pas.
- [ ] « Ouvrir un pipeline… » (filtre `*.json`) → `copybot-ui/src/dev/test-pipeline.json` : la vue du plan s'ouvre, tableau vide avec « Plan non préparé — cliquez sur Préparer le plan pour lister la source ».
- [ ] « ← Pipelines » : « test-pipeline » est en tête de liste, avec son chemin et « Jamais exécuté » ; clic → vue du plan.
- [ ] Copier `test-pipeline.json` en `copybot-ui/target/tmp-pipeline.json`, l'ouvrir, revenir, supprimer le fichier, relancer l'accueil (← Pipelines depuis un autre pipeline) : l'entrée est grisée « (introuvable) » ; un clic ne fait rien ; clic droit « Retirer de la liste » l'enlève.

Vue du plan (spec §2) :
- [ ] En-tête : nom « test-pipeline » ; « Étapes : Lit des fichiers depuis un répertoire → Écrit les fichiers vers une destination » ; « Source : ./copybot-ui/src/dev/test-in » ; « Sortie : ./copybot-ui/target/ui-test-out/{name} » ; « Reprise : sans reprise » ; « Dernière exécution : Jamais exécuté ». Pas de bandeau jaune, pas de ligne de reprise, pas de bouton Copier.
- [ ] « Préparer le plan » : 3 lignes (`alpha.txt`, `beta.txt`, `sub/gamma.txt` listé en récursif) : Nom, Date (jj/MM/aaaa HH:mm), Cible (…`ui-test-out`), Taille (`61 octets`, `72 octets`, `70 octets`), Statut « À copier » ; ligne « Reprise : tout [rien de détecté] » ; bouton « Copier 3 fichiers (203 octets) » ; case « Exécution automatique » décochée ; rien n'est écrit.
- [ ] Filtre : « À copier » ⇒ 3, « Ignorés » ⇒ 0, « Erreurs » ⇒ 0, « Tous » ⇒ 3.
- [ ] « Copier » : Pause / Stop et la barre de progression apparaissent, « ← Pipelines », « Éditer… », « Préparer » et le menu Préférences sont désactivés pendant la copie ; à la fin : « Succès : 3 copiés, 0 ignorés, 0 erreurs », barre pleine, « Dernière exécution : <date> — Succès : 3 copiés… » dans l'en-tête ; les 3 fichiers sont dans `copybot-ui/target/ui-test-out`.
- [ ] « Préparer le plan » puis « Copier » à nouveau : les 3 lignes finissent « Ignoré — Identique à la destination (…) » (écriture sûre, `overwrite: true` ⇒ `ifIdentical: skip`) ; « Succès : 0 copiés, 3 ignorés, 0 erreurs ».
- [ ] Pause / Reprendre / Stop sur une copie plus longue : ajouter un gros fichier (`truncate -s 1G copybot-ui/src/dev/test-in/big.bin`), préparer (le bouton affiche « Copier 4 fichiers (1.1 Go) » : arrondi au dixième supérieur ; les 3 petits, déjà copiés, seront sautés à l'écriture), copier, « Pause » ⇒ le bouton devient « Reprendre », la progression s'arrête après le fichier en cours ; « Reprendre » ⇒ elle repart ; « Stop » ⇒ fin « Arrêté : … » ; fermer la fenêtre pendant une autre copie ⇒ l'application se termine proprement (aucun `.copybot-tmp` laissé hors de celui du fichier en cours, supprimé au prochain passage). Puis `rm copybot-ui/src/dev/test-in/big.bin copybot-ui/target/ui-test-out/big.bin` (et `copybot-ui/target/ui-test-out/.big.bin.*` s'il y en a).

Éditeur (spec §3, §4) :
- [ ] « Éditer… » : fenêtre modale « Éditeur de pipeline — test-pipeline » ; arbre Pipeline / Entrées (1) / Analyses / Traitements / Sortie (1) ; « Pipeline » : case « Traiter pendant le listing », liste « Mode de reprise » (entrée « sans reprise » sélectionnée), case « Exécution automatique ».
- [ ] Nœud de l'entrée : titre « Lit des fichiers depuis un répertoire », description ; champs « Répertoire source * » (+ « Parcourir… » ⇒ sélecteur de **répertoire**), « Inclure les sous-répertoires » (liste « défaut : true » / true / false), « Inclure », « Exclure » (zones multi-lignes), « Inclure les fichiers cachés » ; section repliée « Avancé » (Concurrence maximale, Ressources, Priorité, Version du plugin).
- [ ] Nœud de sortie : « Pattern de sortie * » avec « Variables : {name} {size} {sizeHr} {creation.Y} … {captureDate.D} » ; « Écraser (ancienne option) » ; « En cas de conflit » (Comparaison, Si identique, Si différent : listes avec « défaut : partialHash / skip / rename ») ; Mode de copie, Vérification, Supprimer la source.
- [ ] Taper `abc` dans « Concurrence maximale » ⇒ bordure rouge et infobulle « Valeur invalide : abc » ; vider le champ ⇒ normal.
- [ ] Vider « Répertoire source » puis « Enregistrer » ⇒ avertissement « Des champs obligatoires sont vides : Entrées #1 : Répertoire source » ; rien n'est écrit ; remettre la valeur.
- [ ] « Ajouter » sur Analyses ⇒ « Aucune action chargée ne convient à cette section » (sans plugin optionnel) ; sur Sortie ⇒ bouton désactivé (déjà une étape) ; sur Entrées ⇒ la liste propose « Lit des fichiers depuis un répertoire » avec sa description ; l'ajouter, « Monter » / « Descendre » la déplacent, « Supprimer » l'enlève.
- [ ] Dans le fichier (éditeur de texte), ajouter `"comment": "keep me",` en tête et une étape `"analyseSteps": [ { "plugin": "com.missing", "action": "faces", "actionConfig": { "x": 1 } } ],` ; rouvrir l'éditeur : l'analyse s'affiche « faces (?) », en lecture seule (« Plugin introuvable : com.missing (cette étape est conservée telle quelle) » + son JSON) ; cocher « Exécution automatique » sur Pipeline, « Voir le JSON » ⇒ `comment`, l'étape `faces` et `"ui": { "autoExecute": true }` y sont ; « Enregistrer » ⇒ la vue du plan se recharge (plan invalidé, case cochée), le fichier garde `comment` et `faces`. Retirer ensuite `comment` et `faces` à la main (sinon la préparation échoue : plugin introuvable), recharger (Éditer… / Enregistrer).
- [ ] Case cochée : « Préparer le plan » ⇒ la copie démarre seule dès que le plan est prêt. Décocher la case dans la vue du plan, re-préparer ⇒ pas de copie ; « Voir le JSON » dans l'éditeur ⇒ `autoExecute` toujours `true` (la case n'agit que sur la session). Décocher dans l'éditeur, enregistrer.
- [ ] Reprise : dans l'éditeur, « Mode de reprise » = « depuis le curseur », enregistrer ; préparer ⇒ « Reprise : tout [rien de détecté] » ; copier ; re-préparer ⇒ « Reprise : après gamma.txt (jj/MM HH:mm) [curseur] » et 3 « Ignoré — Déjà importé (curseur…) », bouton « Copier 0 fichiers » désactivé ; clic droit sur la deuxième ligne (le tableau est dans l'ordre de reprise : par date) ⇒ « Reprendre à partir d'ici » ⇒ « Reprise : à partir de <son nom> (…) [manuel] », elle et les suivantes « À copier », la première toujours ignorée, le bouton « Copier 2 fichiers » ; « changer… » ⇒ « Tout » ⇒ « Reprise : tout [manuel] », 3 « À copier » ; « À partir d'une date » (aujourd'hui) et « À partir d'un fichier du plan » fonctionnent aussi. Remettre « sans reprise » et supprimer `copybot-ui/src/dev/test-pipeline.state.json` s'il a été créé.
- [ ] Avertissements : dans l'éditeur, sortie : « Supprimer la source » = true, enregistrer, **préparer sans copier** ⇒ bandeau jaune « file.write : les fichiers source sont supprimés après une simple vérification "size"… » ; remettre « défaut : false », enregistrer, re-préparer ⇒ plus de bandeau.
- [ ] « Enregistrer sous… » (`copybot-ui/target/copy-pipeline.json`) ⇒ la vue du plan passe sur « copy-pipeline » ; il apparaît en tête des récents.
- [ ] Accueil ⇒ « Nouveau… » ⇒ « Éditeur de pipeline — nouveau pipeline » : ajouter une entrée (Répertoire source = `copybot-ui/src/dev/test-in`) et une sortie (Pattern = `copybot-ui/target/ui-test-new/{name}`), « Enregistrer » ⇒ dialogue d'enregistrement (`.json` ajouté si absent) ⇒ le pipeline apparaît en tête de l'accueil.

Langue (spec §6) :
- [ ] Menu Édition › Préférences… ⇒ English ⇒ la vue courante (accueil ou vue du plan du même pipeline, non préparée) est reconstruite en anglais (« Prepare the plan », « Copy 3 files (…) », statuts « To copy » / « Copied » / « Skipped — … ») ; revenir en français. Pendant une copie, Préférences est grisé.

Nettoyage : `rm -rf copybot-ui/target/ui-test-out copybot-ui/target/ui-test-new copybot-ui/target/copy-pipeline.json copybot-ui/target/tmp-pipeline.json` ; retirer ces entrées des récents (clic droit) ; `git status --short copybot-ui/src/dev` ⇒ rien (le pipeline de dev revenu à son contenu : `git diff copybot-ui/src/dev` vide, sinon `git checkout -- copybot-ui/src/dev/test-pipeline.json` : l'éditeur l'a réindenté).

- [ ] **Step 7: Commit**

```bash
git ls-files --eol copybot-ui/src/main/java/com/copybot/ui/CopybotMainUi.java
git add copybot-ui/src/main/java/com/copybot/ui/MainController.java copybot-ui/src/main/java/com/copybot/ui/HomeController.java copybot-ui/src/main/java/com/copybot/ui/PlanController.java copybot-ui/src/main/java/com/copybot/ui/CopybotMainUi.java copybot-ui/src/main/resources/com/copybot/ui/views/main-view.fxml copybot-ui/src/main/resources/com/copybot/ui/views/home-view.fxml copybot-ui/src/main/resources/com/copybot/ui/views/plan-view.fxml
git status --short copybot-ui
git commit -m "Replace the test bench with the home screen and the plan view" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

(`git status` avant le commit : les 7 fichiers ajoutés / modifiés et les 4 suppressions `D` du banc de test, déjà indexées par `git rm` ; rien d'autre sous `copybot-ui` hors des non suivis `*.ico` / `*.png`. `CopybotMainUi.java` : `w/lf`.)

---

### Task 17: Vérification finale

**Files:** aucun (sauf correction d'un écart trouvé, dans la tâche fautive).

- [ ] **Step 1: Full build**

Run: `mvn -o clean install`
Expected: `BUILD SUCCESS` pour tous les modules (plugins de démo et metadata-extractor compilés sans modification contre la nouvelle API ; `copybot-ui` : tests du modèle, jlink et jpackage).

- [ ] **Step 2: Line endings and encodings**

```bash
git diff --name-only HEAD~16 | xargs git ls-files --eol | grep -c "w/mixed"
file copybot-engine/src/main/resources/com/copybot/plugin/embedded/i18n/pluginBundle*.properties copybot-ui/src/main/resources/com/copybot/ui/i18n/uiBundle*.properties
```

Expected : `0` (grep sort en code 1 : attendu) ; `ASCII text, with CRLF line terminators`, `ISO-8859 text, with CRLF line terminators`, `UTF-8 Unicode text` (×3, l'italien inchangé).

- [ ] **Step 3: Manual smoke test after the full build**

Relancer l'IHM (`CopybotMainUiDev`, voir Global Constraints ; `install` déjà fait par le Step 1) et refaire, de la liste de la tâche 16 : l'ouverture sur l'accueil sans rien lancer, « Préparer le plan », « Copier », « Éditer… » / « Voir le JSON » / « Enregistrer », le changement de langue. Nettoyer comme à la tâche 16.

---

## Couverture de la spec

| Spec | Tâche |
|---|---|
| §1 récents (10 max, préférences UI, nom = fichier sans extension, date et résumé de la dernière exécution), « Ouvrir un pipeline… » (`.json`), « Nouveau… », retrait (clic droit), introuvable grisé, rien lancé à l'ouverture, clic ⇒ vue du plan non préparée | 7 (`RecentPipelines`, `UiPreferences`), 14 (`lastRunText`), 16 (accueil, `MainController.showPlan`) |
| §2 en-tête : « ← Pipelines » désactivé pendant une exécution, nom, étapes, source `file.read`, pattern `file.write`, mode de reprise, dernière exécution | 13 (`PipelineSummary`), 14 (`canGoBack`, `resumeModeText`), 16 |
| §2 bandeau d'avertissements (configuration des étapes, puis proposition de reprise) | 14 (`warnings`), 16 |
| §2 ligne de reprise « après DSC_4821 (28/09 17:42) [curseur] » + « changer… » (tout / date / fichier du plan) | 14 (`resumeText`), 6 (`Plan.fromDate` / `fromFile` existants), 16 (dialogue) |
| §2 colonnes Nom, Date (clé de reprise), Cible (répertoire résolu), Taille, Statut ; statuts « À copier », « Ignoré — raison », « Copie 42 % », « Copié », « Erreur — message » | 6 (`targetOf`), 5 (tailles), 14 (`statusText`, `dateText`), 16 |
| §2 placeholder avant préparation ; remplissage au fil du listing ; filtre Tous / À copier / Ignorés / Erreurs | 14 (`update`, `visibleItems`), 16 |
| §2 clic droit « Reprendre à partir d'ici » (`from(clé)`, statuts via `Plan.preview`) | 14 (`resumePointFrom`), 16 |
| §2 « Préparer le plan » (avant ou pour re-préparer) ; « Copier N fichiers (X Go) » ; case « exécution automatique » initialisée par `ui.autoExecute` (défaut `false`), session seulement, copie dès le plan prêt sauf erreur | 6 (`Counts`), 9 (`autoExecute`), 14 (`copyLabel`, `consumeAutoExecute`), 16 |
| §2 Pause / Reprendre, Stop, progression fichiers et octets traités / sélectionnés ; récapitulatif enregistré dans le récent | 14 (`progress`, `statusLine`, `consumeFinishedRun`), 7 (`recordRun`), 16 |
| §2 threading : `prepare` en tâche de fond, watcher via `Platform.runLater`, annulation à la fermeture (`CopybotEngine.close`) | 16 (`PlanController`, `CopybotMainUi.stop` inchangé) |
| §3 fenêtre séparée depuis « Éditer… » / « Nouveau… » ; arbre Pipeline / Entrées / Analyses / Traitements / Sortie ; ajouter, monter, descendre, supprimer ; Sortie au plus une étape | 11 (`addStep`, `canAdd`, `moveStep`, `removeStep`), 15, 16 |
| §3 ajout : actions chargées compatibles avec la section, nom et description localisés | 4 (`catalog`), 10 (`forSection`), 15 |
| §3 formulaire Pipeline (`startProcessingWhileListing`, `resume.mode`, `ui.autoExecute`) ; formulaire d'étape généré + « Avancé » (`maxConcurrency`, `resources`, `priority`, `version`) | 9, 12 (`ADVANCED_FIELDS`, `configText`), 15 (`ConfigForm`) |
| §3 « Enregistrer », « Enregistrer sous… », « Voir le JSON » ; validation des requis ; rechargement de la vue du plan (plan invalidé) | 9 (`save`, `toJson`), 12 (`validate`), 15, 16 (`reload`) |
| §3 fidélité : arbre `JsonObject`, seuls les champs connus réécrits, inconnus conservés ; étape d'un plugin introuvable en lecture seule et conservée | 9, 12 (tests « unknown », « same value »), 10 (`find` vide), 15 |
| §4 `ConfigSchema` : `name, kind, required, defaultValue, hints, labelKey, descriptionKey, children / enumValues / elementSchema`, kinds `STRING … LIST` | 1 |
| §4 introspection des composants de record ; `AbstractActionWithConfig.configSchema()` ; `IAction.configSchema()` vide par défaut, surchargeable | 1, 2 |
| §4 annotations `@DirectoryPath`, `@FilePath`, `@PatternField` (variables `name`, `size`, `sizeHr`, dates `.Y .y .m .D`), `@DefaultValue`, `@Required` | 1 (+ `@AllowedValues`, interprétation 1), 15 (sélecteurs, variables) |
| §4 libellés `plugin.<plugin>.<action>.config.<champ>[.<sous-champ>].name` / `.description`, clé absente ⇒ nom du champ | 1 (`withKeyPrefix`), 4 (`label`, `description`), 3 |
| §4 catalogue : plugin, version, code, type d'étape, nom et description localisés, schéma | 4 |
| §4 configs embarquées (`FileReadConfig`, `FileWriteConfig` et ses sous-records de l'écriture sûre) annotées, libellés EN / FR | 3 |
| §5 `Plan.targetOf(WorkItemExecution)` (vide si non résolvable) ; compteurs sélectionnés / ignorés / erreurs / octets sélectionnés | 6 |
| §6 textes UI dans les bundles UI EN / FR (règles d'édition octet par octet, `\uXXXX`) ; préférences : langue, récents, dernière exécution | 8, 3 (bundles plugin ISO-8859-1), 7 |
| §7 logique testable hors JavaFX : `RecentPipelines`, `PlanViewModel`, `PipelineDocument`, `ConfigSchema` ; contrôleurs minces, vérification manuelle ; banc de test remplacé, `CopybotMainUiDev` conservé | 1, 7, 9–14 (tests), 15–16 (vérification manuelle), 16 (suppressions), 17 |
| Hors périmètre (vues alternatives, détection de carte, transcodage, exécutions parallèles) | — (non traité) |

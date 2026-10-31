# Réécriture de la date EXIF des JPEG corrigés - plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** une étape de traitement `metadata-extractor.write-date` qui, pour un JPEG corrigé dans le plan, donne à la copie la date locale du lieu et son décalage, sans toucher l'image ; tout le reste passe octet pour octet.

**Architecture:** le moteur gagne une copie d'élément à contenu remplacé et une note par étape dans la projection (vue dans « Traitement prévu ») ; le plugin metadata-extractor fournit l'action, qui réécrit l'APP1 EXIF avec Apache Commons Imaging en mémoire.

**Tech Stack:** Java 25, Maven 3.8.8, metadata-extractor 2.21.0, Apache Commons Imaging (version à confirmer, Task 2 step 1), JUnit 5.

**Spec:** `docs/superpowers/specs/2026-10-25-exif-date-rewrite-design.md`

## Global Constraints

- Sans l'étape, ou pour un fichier non corrigé (pas de `CAPTURE_ZONE`), non JPEG, sans `captureDate` ou sans `DateTimeOriginal` : sortie **identique octet pour octet**.
- Jamais de recompression ; tous les autres tags conservés (maker note relue identique par metadata-extractor).
- Échec de réécriture : copie de l'original tel quel + log + note visible dans le détail ; jamais d'élément en erreur à cause de la réécriture.
- Pas de programme externe ; si Commons Imaging ne convient pas (version, JPMS, maker note), arrêt et signalement (BLOCKED), pas de réécrivain fait main.
- Mode suppression des sources : l'original d'un JPEG réécrit est supprimé comme les autres (élément toujours local).
- Textes : bundles du plugin (EN) ; UI en/fr/it avec échappements `\uXXXX` (pièges : voir mémoire, écrire via script perl) ; fichiers CRLF : Edit, jamais `sed -i` ; Maven `-am` avec `-pl`.
- À exécuter **après** la Task 3 du plan `2026-10-25-timezone-correction.md` (même fichier `PlanViewModel.detailText`).

## Review Focus

1. JPEG dont l'APP1 EXIF est tronqué ou invalide : copié tel quel, note « EXIF non réécrit : … », élément DONE (test Task 2).
2. Réécriture deux fois du même fichier avec la même correction : mêmes octets (test Task 2).
3. Fichier `.JPG` qui n'est pas un JPEG (signature) : inchangé, note « format non pris en charge » (test Task 2).
4. Taille : la copie réécrite passe la vérification `write.verify.size` (taille mise à jour), y compris en mode suppression (test Task 2, bout en bout avec `FileWriteAction`).
5. Élément ignoré ou fork d'une étape précédente : l'étape lit les clés de l'élément qu'elle reçoit, sans rien supposer de son origine (couvert par le test « non corrigé → inchangé » sur un item copié).

---

### Task 1: Moteur - contenu remplacé et note d'étape

**Files:**
- Modify: `copybot-engine/src/main/java/com/copybot/plugin/api/action/WorkItem.java`, `WorkItemMetadata.java`
- Modify: `copybot-engine/src/main/java/com/copybot/engine/Projection.java`, `DryRunner.java`
- Modify: `copybot-ui/src/main/java/com/copybot/ui/model/PlanViewModel.java` (`detailText`)
- Test: `copybot-engine/src/test/java/com/copybot/plugin/api/action/WorkItemCopyTest.java`, `copybot-engine/src/test/java/com/copybot/engine/DryRunnerTest.java`, `copybot-ui/src/test/java/com/copybot/ui/model/PlanViewModelTest.java`

**Interfaces:**
- Produces:
  - `public WorkItem withContent(Supplier<InputStream> content, long size)` : copie comme `copyForDryRun` (métadonnées copiées, `isDeleted` copié), nom, chemin source et `isLocal` gardés, `openInputStream()` renvoie `content`, `getMetadatas().setSize(size)`.
  - `WorkItemMetadata.NOTE = "note"` : clé brute `String`, une remarque d'une étape sur l'élément (posée en dry run pour la projection, ou à l'exécution).
  - `Projection.Step(String action, List<String> produced, String note /* nullable */)` + constructeur `Step(String action, List<String> produced)` (note null) pour ne casser aucun appel existant.
  - `DryRunner.project` : après chaque `dryRun`, retire `NOTE` des éléments produits (`raw().remove`) et la met dans le `Step` (notes distinctes jointes par « ; »).
  - `PlanViewModel.detailText` : une ligne d'étape avec note devient `⚙ action → produits — note` ; si l'élément exécuté porte `NOTE` (exécution), une ligne `⚠ note` après le statut.

- [ ] **Step 1: Write the failing tests**
```java
withContentKeepsTheSourceAndReplacesTheContent: // nom, getLocalLocation, isLocal identiques ; openInputStream lit les nouveaux octets ; getSize() == size ; raw de l'original inchangé
dryRunNoteGoesToTheStep:      // FakeProcess dont dryRun pose NOTE "EXIF réécrit" -> trace == [Step("p", ["a.jpg"], "EXIF réécrit")] et l'élément projeté n'a plus NOTE
stepWithoutNoteIsUnchanged:   // Step("p", produced) == Step("p", produced, null)
detailTextShowsTheStepNote / detailTextShowsTheItemNote
```
- [ ] **Step 2: Run, expect compile failure** — `mvn -pl copybot-ui -am test -Dtest='WorkItemCopyTest,DryRunnerTest,PlanViewModelTest' -Dsurefire.failIfNoSpecifiedTests=false`
- [ ] **Step 3: Implement** les signatures ci-dessus.
- [ ] **Step 4: Run, expect PASS**, puis `mvn verify` complet vert, aucun test existant modifié sauf pour lire la note nulle si un `equals` l'exige (à dire dans le rapport).
- [ ] **Step 5: Commit** `EXIF rewrite: a process step can replace an item's content and leave a note in the planned processing`

---

### Task 2: Plugin - l'étape write-date

**Files:**
- Modify: `copybot-plugin/copybot-plugin-metadata-extractor/pom.xml`, `src/main/java/module-info.java`, `MetadataExtractorPlugin.java`, `src/main/resources/.../i18n/pluginBundle.properties`
- Create: `copybot-plugin/copybot-plugin-metadata-extractor/src/main/java/com/copybot/plugin/metadataextractor/actions/WriteDate.java`
- Test: create `.../actions/WriteDateTest.java` (réutiliser les fabriques de JPEG de `ExtractMetadataTest` : les déplacer dans une classe de test partagée `TestJpegs`)

**Interfaces:**
- Consumes: `WorkItem.withContent`, `WorkItemMetadata.NOTE` (Task 1) ; `CAPTURE_ZONE`, `getZone`, `getTime(CAPTURE_DATE)`.
- Produces: `WriteDate extends AbstractAction implements IProcessAction`, déclarée par `MetadataExtractorPlugin.getProcessActions()` = `List.of(new ActionDefinition<>("write-date", WriteDate.class))` ; `static Optional<byte[]> rewrite(byte[] jpeg, ZonedDateTime local)` (vide si pas de `DateTimeOriginal`) ; notes : `EXIF réécrit : yyyy-MM-dd HH:mm XXX`, `EXIF non réécrit : format non pris en charge`, `EXIF non réécrit : pas de date EXIF`, `EXIF non réécrit : <message>` (échec).
- Règles (spec §1, §2, §6) : décision sur `CAPTURE_ZONE` + `captureDate` + signature `FF D8` ; écrit `DateTimeOriginal` / `OffsetTimeOriginal` ; `DateTimeDigitized`/`DateTime` et leurs offsets décalés du même écart s'ils existent ; `SubSecTimeOriginal` et le reste conservés ; offset = celui du fuseau à l'instant.

- [ ] **Step 1: Spike bibliothèque** : trouver dans Maven Central la dernière `org.apache.commons:commons-imaging` qui a `ExifRewriter.updateExifMetadataLossless` ; l'ajouter (scope compile) ; `jar --describe-module --file <jar>` pour le nom de module ; `requires` dans `module-info.java` ; `mvn -pl copybot-plugin/copybot-plugin-metadata-extractor -am package` vert et le jar dans `target/lib`. Échec → BLOCKED avec le détail.
- [ ] **Step 2: Write the failing tests** (`WriteDateTest`)
```java
rewritesTheDateOfACorrectedJpeg        // JPEG "2026:10:25 01:30:00" "+02:00", CAPTURE_ZONE Mexico, captureDate 2026-10-24T23:30Z -> relu : DateTimeOriginal 2026:10:24 17:30:00, OffsetTimeOriginal -06:00
keepsTheImageAndTheOtherTags           // segments hors APP1 identiques ; Make/Model et un MakerNote synthétique relus identiques
leavesAnUncorrectedJpegUntouched        // pas de CAPTURE_ZONE -> même instance ou mêmes octets
leavesANonJpegUntouched                // ".JPG" sans FF D8 -> inchangé, NOTE "format non pris en charge"   (Review Focus 3)
leavesAJpegWithoutDateTimeOriginalUntouched
copiesAsIsWhenTheExifIsBroken          // APP1 tronqué -> octets d'origine, NOTE "EXIF non réécrit : ..."     (Review Focus 1)
rewritingTwiceGivesTheSameBytes                                                                                (Review Focus 2)
dryRunNotesTheVerdictWithoutReadingTheExif
endToEndTheCopyCarriesTheDate          // ExtractMetadata.doAnalyze (CAPTURE_ZONE posée) -> WriteDate.doProcess -> FileWriteAction.write (verify size, puis deleteSource) -> fichier copié relu ; source supprimée   (Review Focus 4)
```
- [ ] **Step 3: Run, expect FAIL** — `mvn -pl copybot-plugin/copybot-plugin-metadata-extractor -am test -Dtest=WriteDateTest -Dsurefire.failIfNoSpecifiedTests=false`
- [ ] **Step 4: Implement** `WriteDate`, la déclaration, les clés de bundle `plugin.metadata-extractor.write-date.name` / `.description` (description : « À placer juste avant l'écriture : réécrit la date EXIF des JPEG dont le fuseau a été corrigé dans le plan. »).
- [ ] **Step 5: Run, expect PASS**, puis `mvn verify` complet vert.
- [ ] **Step 6: Commit** `EXIF rewrite: the metadata plugin's write-date step gives a corrected JPEG copy its local date`

---

### Task 3: Vérification

- [ ] `mvn verify` complet, comptes reportés.
- [ ] Vérification réelle : copie de DSC07217 (carte F:) via un test jetable `doAnalyze` (CAPTURE_ZONE `Europe/Paris`) + `doProcess` dans le dossier tmp du job ; relire avec metadata-extractor tous les tags Sony de l'original et de la copie, seuls les tags de date diffèrent. Test jetable supprimé ensuite.
- [ ] Revue de toute la branche du morceau 2 (code-reviewer), puis squash par fonction avec le morceau 1.

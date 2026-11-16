# Détection d'un appareil mal réglé par GPS - plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** un plugin facultatif `gps-zone` signale, à l'analyse, les photos dont le décalage noté contredit le fuseau de leur position GPS (heure UTC du GPS concordante), et le plan propose « Appliquer le fuseau proposé (N) », qui applique la correction du morceau 1.

**Architecture:** metadata-extractor expose des valeurs GPS brutes (types JDK) ; le plugin `gps-zone` (base de frontières hors ligne, chargée paresseusement) pose une clé de suggestion ; l'UI lit cette clé (marque, menu, résumé) et appelle `Plan.correctZone`.

**Tech Stack:** Java 25, Maven 3.8.8, metadata-extractor 2.21.0, timeshape (version à confirmer, Task 2 step 1), JUnit 5, JavaFX 27.

**Spec:** `docs/superpowers/specs/2026-10-25-gps-zone-check-design.md`

## Global Constraints

- À exécuter **après** le rebase des morceaux 1 et 2 sur develop (commit « Own out pattern » 9c3c988 inclus) et l'icône d'horloge : branche empilée sur la branche du morceau 2 rebasée.
- Sans le plugin `gps-zone` dans le pipeline, ou sans GPS : rien de visible ne change (tests existants verts, non modifiés).
- On ne signale que si : GPS valide (statut ≠ `V`), position ≠ (0,0), décalage noté présent (`OffsetTimeOriginal`), heure UTC du GPS à moins de 15 min de l'instant de la photo, les 4 points à ~5 km autour donnent le même fuseau, et le décalage attendu (règles du fuseau à l'instant) ≠ décalage noté. Jamais si `CAPTURE_ZONE` est présente. Jamais pour une vidéo.
- Les clés de suggestion sont retirées à chaque analyse, puis reposées seulement si la règle signale.
- Valeurs brutes entre plugins : types JDK uniquement (`Double`, `Instant`, `Boolean`, `ZoneOffset`, `ZoneId`) — les plugins sont dans des couches de modules séparées.
- La base de fuseaux : hors ligne, chargée une fois au premier fichier avec GPS, thread-safe ; jamais chargée pour un import sans GPS.
- Textes UI en/fr/it (`\uXXXX` via script perl, voir mémoire) ; bundles du plugin en/fr ; fichiers CRLF : Edit, jamais `sed -i` ; Maven `-am` avec `-pl`.

## Review Focus

1. Import sans aucun GPS : la base n'est jamais chargée (test Task 2, compteur d'initialisation).
2. Photo près d'une frontière (Espagne/Portugal, ou en mer) : rien n'est signalé (test Task 2).
3. Re-analyse après une correction GPS validée puis annulée : la ligne est de nouveau signalée (test Task 3, modèle).
4. Sélection mêlant plusieurs fuseaux proposés : un `correctZone` par fuseau, chaque ligne reçoit le sien (test Task 3).
5. Deux plugins en dev : la configuration de dev charge metadata-extractor et gps-zone (test Task 2 sur la lecture de la config).

---

### Task 1: Valeurs GPS brutes exposées par metadata-extractor

**Files:**
- Modify: `copybot-engine/src/main/java/com/copybot/plugin/api/action/WorkItemMetadata.java` (constantes, Javadoc « raw key: … »)
- Modify: `copybot-plugin/copybot-plugin-metadata-extractor/src/main/java/com/copybot/plugin/metadataextractor/actions/ExtractMetadata.java` (`doAnalyze`, `Capture`)
- Test: `.../actions/ExtractMetadataTest.java`, `TestJpegs.java` (ajouter des entrées RATIONAL pour latitude/longitude/heure et ASCII pour Ref/Status/DateStamp)

**Interfaces:**
- Produces (`WorkItemMetadata`) : `GPS_LATITUDE = "gpsLatitude"` (Double), `GPS_LONGITUDE = "gpsLongitude"` (Double), `GPS_TIME = "gpsTime"` (Instant), `GPS_VALID = "gpsValid"` (Boolean, faux si statut `V` ou position (0,0)), `RECORDED_OFFSET = "recordedOffset"` (ZoneOffset, photo EXIF seulement), `SUGGESTED_ZONE = "suggestedZone"` (ZoneId, posée par gps-zone).
- `ExtractMetadata.doAnalyze` : retire ces clés (sauf `SUGGESTED_ZONE`, propriété de gps-zone) puis les repose depuis le `Metadata` déjà lu (aucune lecture supplémentaire) ; `Capture` gagne `Optional<ZoneOffset> recorded`.

- [ ] **Step 1: Write the failing tests** — `exposesTheGpsPositionTimeAndValidity` (JPEG avec GPS Paris, statut A, DateStamp/TimeStamp) ; `aVoidOrZeroGpsIsNotValid` ; `exposesTheRecordedOffsetOfAPhotoOnly` (vidéo : absent) ; `aReanalysisWithoutGpsRemovesTheKeys`.
- [ ] **Step 2: Run, expect FAIL** — `mvn -pl copybot-plugin/copybot-plugin-metadata-extractor -am test -Dtest=ExtractMetadataTest -Dsurefire.failIfNoSpecifiedTests=false`
- [ ] **Step 3: Implement** (`GpsDirectory.getGeoLocation()`, `GeoLocation.isZero()`, `getGpsDate()`, `getString(TAG_STATUS)`).
- [ ] **Step 4: Run, expect PASS**, puis `mvn verify` vert.
- [ ] **Step 5: Commit** `GPS check: the metadata plugin exposes the GPS position, GPS time and recorded offset`

---

### Task 2: Plugin gps-zone

**Files:**
- Create: `copybot-plugin/copybot-plugin-gps-zone/pom.xml`, `src/main/java/module-info.java`, `.../gpszone/GpsZonePlugin.java`, `.../gpszone/actions/CheckGpsZone.java`, `.../gpszone/ZoneFinder.java` (recherche + garde frontière, initialisation paresseuse), `src/main/resources/.../i18n/pluginBundle.properties` + `_fr`
- Modify: `copybot-plugin/pom.xml` (`<module>`), `copybot-engine/.../config/CopybotConfig.java` et sa lecture (`devPluginPaths` accepte une liste, une chaîne seule reste valide), `copybot-ui/src/dev/config.json` (les deux plugins), `README.md` (table des actions, sortie de build, CLI), `NOTICE` (créé, attribution ODbL de timeshape / Timezone Boundary Builder), `copybot-ui/.../MainController.java` (une ligne d'attribution dans « À propos », clés `about.*` en/fr/it)
- Test: `CheckGpsZoneTest`, `ZoneFinderTest`, test de lecture de la config (engine)

**Interfaces:**
- Consumes: clés de Task 1, `CAPTURE_ZONE`, `getTime(CAPTURE_DATE)`.
- Produces: action `gps-zone.check` (`IAnalyzeAction`), pose `SUGGESTED_ZONE` (ZoneId) selon la règle des Global Constraints ; `ZoneFinder.zoneAt(lat, lon)` → `Optional<ZoneId>`, vide si les 4 points à ~5 km diffèrent ou si la bibliothèque ne répond pas.

- [ ] **Step 1: Spike bibliothèque** : dernière `net.iakovlev:timeshape` dans Maven Central ; taille du jar et de ses dépendances ; `jar --describe-module` (nom de module, conflits de paquets) ; licence des données ; temps et mémoire de `TimeZoneEngine.initialize()` (monde) mesurés. Échec JPMS ou mémoire > 1 Go → BLOCKED avec le détail.
- [ ] **Step 2: Write the failing tests** — Paris juillet noté `+01:00`, GPS confirmé → `Europe/Paris` ; noté `+02:00` → rien ; Mexico noté `+02:00` → `America/Mexico_City` ; heure GPS à 2 h de l'instant → rien ; sans heure GPS → rien ; GPS invalide / (0,0) / sans décalage noté → rien ; frontière Espagne/Portugal (ex. 41.97, -6.5) → rien (Review Focus 2) ; `CAPTURE_ZONE` présente → rien ; clé retirée à chaque analyse ; import sans GPS → base jamais initialisée (Review Focus 1) ; config de dev à deux chemins chargée (Review Focus 5).
- [ ] **Step 3: Run, expect FAIL** — `mvn -pl copybot-plugin/copybot-plugin-gps-zone -am test`
- [ ] **Step 4: Implement**.
- [ ] **Step 5: Run, expect PASS**, puis `mvn verify` vert ; le jar et `target/lib` produits.
- [ ] **Step 6: Commit** `GPS check: an optional gps-zone plugin suggests the time zone of a photo whose camera was badly set`

---

### Task 3: Interface - marque, menu, résumé

**Files:**
- Modify: `copybot-ui/src/main/java/com/copybot/ui/model/PlanViewModel.java`, `copybot-ui/src/main/java/com/copybot/ui/PlanController.java` (cellule cible : rangée de marques crayon / horloge / alerte, chacune avec sa ligne d'infobulle ; menu ; libellé de résumé), `copybot-ui/src/main/resources/com/copybot/ui/views/plan-view.fxml` (libellé après `conflictLabel`), bundles en/fr/it
- Test: `PlanViewModelTest`

**Interfaces:**
- Consumes: `SUGGESTED_ZONE`, `RECORDED_OFFSET`, `Plan.correctZone(items, place, clock, override)`.
- Produces: `PlanViewModel.suggestible(rows)` (mêmes conditions que `correctable` + `SUGGESTED_ZONE`) ; `static Map<ZoneId, List<WorkItemExecution>> bySuggestedZone(rows)` ; `static Optional<String> suggestionText(item)` = `plan.zone.suggested` « Appareil mal réglé : noté {0}, lieu {1} ({2}, d''après le GPS) » ; `suggestionSummary()` = `plan.zone.suggested-count` « {0} photos d''un appareil mal réglé » (lignes signalées non ignorées, ni copiées, ni en erreur ; vide si 0) ; menu `plan.menu.zone-suggested` « Appliquer le fuseau proposé ({0}) », **masqué** (pas seulement grisé) quand aucune ligne de la sélection n'a de proposition, si bien que sans plugin rien n'apparaît (décision de Steven) : un `correctZone(groupe, zone, null, override)` par fuseau puis la ré-analyse habituelle.

- [ ] **Step 1: Write the failing tests** — `suggestibleKeepsTheFlaggedCorrectableRows` ; `bySuggestedZoneGroupsTheRows` (Review Focus 4) ; `suggestionTextTellsTheNotedAndExpectedOffsets` ; `suggestionSummaryCountsTheRowsStillToCopy` ; `aCancelledGpsCorrectionIsSuggestedAgain` (clé reposée après ré-analyse : modèle seulement, Review Focus 3).
- [ ] **Step 2: Run, expect FAIL** — `mvn -pl copybot-ui -am test -Dtest=PlanViewModelTest -Dsurefire.failIfNoSpecifiedTests=false`
- [ ] **Step 3: Implement**.
- [ ] **Step 4: Run, expect PASS**, puis `mvn verify` vert.
- [ ] **Step 5: Commit** `GPS check: the plan marks the photos of a badly set camera and applies the GPS time zone on demand`

---

### Task 4: Vérification

- [ ] `mvn verify` complet, comptes reportés.
- [ ] Revue de toute la branche (code-reviewer, modèle le plus capable), une vague de correction, relecture ciblée.
- [ ] Squash par fonction, rebase sur develop si besoin, commande `git merge --ff-only` pour Steven ; essai dans l'appli à lui confier (photo géolocalisée), en rappelant de recompiler les deux plugins avant de lancer.

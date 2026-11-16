# Préréglages de regroupement apportés par un plugin — plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan
> task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** un plugin déclare des préréglages de regroupement, le pipeline les cite avec le nom du plugin, le moteur
et l'éditeur les traitent comme ceux du cœur.

**Spec:** `docs/superpowers/specs/2026-10-31-plugin-grouping-presets-design.md` (contraignante).

## Global Constraints

- JDK 25 ; `mvn -q verify -pl <module> -am` ; `mvn -q verify` à la racine en fin de plan.
- API 1.2 non publiée : ajouts seulement, empreinte régénérée, diff = lignes ajoutées.
- Bundles ASCII + `\uXXXX`, moteur et IHM en/fr/it.
- Pipelines existants (préréglages du cœur, règles propres) : comportement et JSON inchangés.
- Un commit par tâche, message en anglais, trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. Un pipeline sans préréglage de plugin se comporte exactement comme avant.
2. Plugin absent ou en échec : refus clair au run, signalé dans l'éditeur, jamais de regroupement silencieusement
   vide.
3. Règles d'un plugin contrôlées comme celles d'un pipeline (regex, `{base}`, rôles).

## Task 1 — API et moteur

- [ ] Paquet API `grouping` (spec §2), `IPlugin.getGroupingPresets()`, empreinte.
- [ ] Champ `plugin` de la règle de pipeline ; registre des préréglages (cœur + plugins chargés) utilisé partout où
  `GroupingPresets` l'est (validation, listage, mode continu) ; contrôles et messages (spec §4) en/fr/it.
- [ ] Tests avec un plugin de test déclarant un préréglage (bout en bout : regroupement appliqué), plugin absent,
  préréglage inconnu, règles invalides écartées.

## Task 2 — Catalogue et éditeur

- [ ] `PluginCatalog` + préréglages (spec §5), menu, libellés, plugin absent dans les plugins manquants,
  avertissements fondés sur le développement. Clés IHM en/fr/it. Tests (modèle ; FX si le module en a pour ce
  formulaire).

## Task 3 — README et vérification

- [ ] README (section des compagnons : préréglages de plugins, JSON avec `plugin` ; section « Plugins » : comment
  déclarer un préréglage) ; `mvn -q verify` à la racine.

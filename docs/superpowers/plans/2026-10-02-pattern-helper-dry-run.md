# Output Pattern Helper and Process Dry Run Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Help the user build `file.write`'s `outPattern` in the pipeline editor from real sample metadata, with a fallback syntax, a missing-key policy, and a dry run of the process steps used by the sampler and by "Prepare".

**Architecture:** A pure `OutPattern` parser/resolver in the plugin API is shared by `file.write` and the editor. Process actions gain a `dryRun` default method; a `DryRunner` chains it and yields a projection, stored per item at the end of `MainExecutor.prepare()` and exposed by `Plan.projectionOf`. A `PipelineSampler` (engine, outside the engine's busy lock) lists a capped sample from the edited (unsaved) pipeline, runs the analyses and the dry run; the editor shows keys and a live path preview from it through a JavaFX-free `PatternHelperModel`.

**Tech Stack:** Java 25 (JPMS modules), Maven multi-module (`copybot-engine`, `copybot-ui`), JUnit 5, Gson, JavaFX.

**Spec:** `docs/superpowers/specs/2026-10-02-pattern-helper-dry-run-design.md` (read it before any task).

## Global Constraints

- Build/test: `mvn -q -B -pl copybot-engine test` (engine), `mvn -q -B -pl copybot-engine,copybot-ui install -DskipTests` then `mvn -q -B -pl copybot-ui test` (UI depends on the installed engine). Full check: `mvn -B clean install` from the repo root (a `jpackage` step runs in copybot-ui; `clean` avoids its "destination already exists" failure).
- Work in the worktree `C:\Users\Steven\IdeaProjects\copybot\.claude\worktrees\pipeline-editor-save-close`, branch `pattern-helper-dry-run`. Never `cd` elsewhere; never use `git stash`.
- Line endings are mixed (some files CRLF, some LF): edit with the Edit tool (it preserves them), never `sed -i` (Git Bash converts CRLF to LF). No Python on this machine; `perl -i` is byte-safe if a script is needed.
- `engineBundle_fr.properties` and `pluginBundle_fr.properties` are ISO-8859-1 with `\uXXXX` escapes for non-ASCII characters: write accents as escapes (`é` = `\u00e9`, `è` = `\u00e8`, `à` = `\u00e0`, `ê` = `\u00ea`, `ç` = `\u00e7`, `’`/apostrophe: plain `'` but MessageFormat needs `''` when the message has `{0}` arguments). `uiBundle*.properties` are UTF-8 (write accents directly; same `''` rule).
- UI texts go to `uiBundle.properties` (EN) and `uiBundle_fr.properties` (FR) only: `uiBundle_it.properties` is a partial bundle (20 keys, EN fallback) and is not extended (deviation from the spec's "EN, FR, IT", decided at planning time).
- Code style: match the surrounding code — English Javadoc on classes and non-trivial methods, comments explain *why*, records for value types, `CopybotException.ofResource(key, args...)` for user-facing errors, `ResourcesEngine.getString(key, args...)` for texts.
- Test names are sentences in camelCase (`aDifferentTargetIsRenamedByDefault...`), JUnit 5 `assertEquals(expected, actual, message)`.
- Commit after each task, message in English, imperative, ending with the line `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.
- Names fixed by the spec: `OutPattern`, `onMissingKey` (`error` default | `skip` | `literal`), `IProcessAction.dryRun`, `WorkItem.copyForDryRun()`, `DryRunner`, `StepResolver`, `PipelineSampler`, `SampleSession`, `Sample`, `SampleItem`, `Plan.projectionOf`, `TargetProjection`, `PatternHelperModel`, `PatternHelper`. Sampler caps: 200 listed items, 2 s listing, 10 kept.

## Review Focus

1. A video without `captureDate` in a pipeline using `resume.mode = destination` and the default `onMissingKey = error`: "Prepare" must still succeed (the item is excluded from the destination probe), not fail the whole preparation — Task 2 pins it (`ResumeResolverMissingKeyTest`).
2. An input plugin that swallows every exception thrown by the consumer (so the stop signal never ends `listFiles`): the sampler must still return within ~2 s with what it received — Task 6 pins it (`aListingThatSwallowsTheStopEndsAtTheDeadline`).
3. A `dryRun` that mutates its argument must never touch the real item later processed by `doProcess` — Task 3 pins it (`theRealItemIsNeverTouchedByTheDryRun`).
4. A pattern typed while incomplete in the editor (`{captureDate.Y|`): the preview shows the syntax error with its position, never an exception in the JavaFX thread — Task 7 pins it (`anInvalidPatternGivesTheSyntaxErrorAndNoRows`).
5. Closing the editor while the sample is being read: the background work is cancelled and no callback touches the closed window — Task 8 pins it in `EditorController` (callbacks check a `closed` flag; cancellation is unit-tested in Task 6 `cancelEndsTheOperationWithACancelledSample`).

---

### Task 1: `OutPattern` — syntax and resolution

**Files:**
- Create: `copybot-engine/src/main/java/com/copybot/plugin/api/pattern/OutPattern.java`
- Modify: `copybot-engine/src/main/java/module-info.java` (export `com.copybot.plugin.api.pattern`, next to the other `com.copybot.plugin.api.*` exports)
- Modify: `copybot-engine/src/main/resources/com/copybot/engine/i18n/engineBundle.properties`, `engineBundle_fr.properties` (key `pattern.syntax`)
- Modify: `copybot-engine/src/test/java/com/copybot/resources/SafeWriteBundleTest.java` (add `"pattern.syntax"` to `KEYS`)
- Test: `copybot-engine/src/test/java/com/copybot/plugin/api/pattern/OutPatternTest.java`

**Interfaces:**
- Produces:
  - `public final class OutPattern` with `public static OutPattern parse(String pattern)` (throws `CopybotException` key `pattern.syntax`, args: `pattern`, 1-based `position`, localized reason), `public String pattern()`, `public List<String> keys()`, `public String staticPrefix()`, `public Resolution resolve(Map<String, String> display)`.
  - `public record Resolution(String text, List<String> missing)` nested in `OutPattern`; `public boolean complete()` = `missing.isEmpty()`.

- [ ] **Step 1: Add the message keys**

`engineBundle.properties` (append near the other `write.*` keys):
```properties
pattern.syntax=Invalid output pattern "{0}" at position {1}: {2}
pattern.syntax.unclosed-brace=unclosed brace
pattern.syntax.unopened-brace=closing brace without opening brace
pattern.syntax.empty-expression=empty expression
pattern.syntax.empty-alternative=empty alternative
pattern.syntax.unclosed-quote=unclosed quote
pattern.syntax.text-after-quote=text after a fixed value
```
`engineBundle_fr.properties` (ISO-8859-1, escapes):
```properties
pattern.syntax=Expression de sortie "{0}" invalide \u00e0 la position {1} : {2}
pattern.syntax.unclosed-brace=accolade non ferm\u00e9e
pattern.syntax.unopened-brace=accolade fermante sans accolade ouvrante
pattern.syntax.empty-expression=expression vide
pattern.syntax.empty-alternative=alternative vide
pattern.syntax.unclosed-quote=apostrophe non ferm\u00e9e
pattern.syntax.text-after-quote=texte apr\u00e8s une valeur fixe
```
Add `"pattern.syntax"` to `SafeWriteBundleTest.KEYS` (the reasons are checked by `OutPatternTest` through their localized text).

- [ ] **Step 2: Write the failing tests**

```java
package com.copybot.plugin.api.pattern;

import com.copybot.exception.CopybotException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class OutPatternTest {

    private static final Map<String, String> PHOTO = Map.of("name", "DSC_1.NEF", "captureDate.Y", "2026",
            "lastModified.Y", "2025", "blank", "  ");

    private static String resolve(String pattern, Map<String, String> display) {
        return OutPattern.parse(pattern).resolve(display).text();
    }

    @Test
    public void textWithoutBracesIsCopiedAsIs() {
        assertEquals("//nas/photo/x.jpg", resolve("//nas/photo/x.jpg", PHOTO));
    }

    @Test
    public void aKeyIsReplacedByItsValue() {
        assertEquals("//nas/2026/DSC_1.NEF", resolve("//nas/{captureDate.Y}/{name}", PHOTO));
    }

    @Test
    public void theFirstAlternativeWithAValueWins() {
        assertEquals("2025", resolve("{missing|lastModified.Y|captureDate.Y}", PHOTO));
        assertEquals("2026", resolve("{captureDate.Y|lastModified.Y}", PHOTO));
    }

    @Test
    public void aFixedValueAlwaysHasAValue() {
        assertEquals("sans-date", resolve("{missing|'sans-date'}", PHOTO));
    }

    @Test
    public void aDoubledQuoteInAFixedValueIsOneQuote() {
        assertEquals("l'été", resolve("{missing|'l''été'}", PHOTO));
    }

    @Test
    public void spacesAroundTheAlternativesAreIgnored() {
        assertEquals("2025", resolve("{ missing | lastModified.Y }", PHOTO));
    }

    @Test
    public void aBlankValueCountsAsMissing() {
        assertEquals("2026", resolve("{blank|captureDate.Y}", PHOTO));
    }

    @Test
    public void anExpressionWithoutValueIsKeptAsWrittenAndReported() {
        OutPattern.Resolution resolution = OutPattern.parse("/{captureDate.Y | other}/{name}").resolve(Map.of("name", "a"));

        assertEquals("/{captureDate.Y | other}/a", resolution.text());
        assertEquals(List.of("{captureDate.Y | other}"), resolution.missing());
        assertFalse(resolution.complete());
    }

    @Test
    public void aCompleteResolutionHasNoMissingExpression() {
        assertTrue(OutPattern.parse("{name}").resolve(PHOTO).complete());
    }

    @Test
    public void keysListsTheKeysInOrderWithoutDuplicatesNorFixedValues() {
        assertEquals(List.of("captureDate.Y", "lastModified.Y", "name"),
                OutPattern.parse("{captureDate.Y|lastModified.Y|'x'}/{name}/{captureDate.Y}").keys());
    }

    @Test
    public void theStaticPrefixIsTheTextBeforeTheFirstBrace() {
        assertEquals("//nas/photo/", OutPattern.parse("//nas/photo/{captureDate.Y}/{name}").staticPrefix());
        assertEquals("//nas/x.jpg", OutPattern.parse("//nas/x.jpg").staticPrefix());
    }

    private static void assertSyntaxError(String pattern, int position, String reasonKey) {
        CopybotException e = assertThrows(CopybotException.class, () -> OutPattern.parse(pattern), pattern);
        String reason = com.copybot.resources.ResourcesEngine.getString(reasonKey);
        assertEquals(com.copybot.resources.ResourcesEngine.getString("pattern.syntax", pattern, position, reason),
                e.getMessage(), pattern);
    }

    @Test
    public void syntaxErrorsGiveTheirPosition() {
        assertSyntaxError("/a/{name", 4, "pattern.syntax.unclosed-brace");
        assertSyntaxError("/a/name}", 8, "pattern.syntax.unopened-brace");
        assertSyntaxError("/a/{}", 4, "pattern.syntax.empty-expression");
        assertSyntaxError("{ }", 1, "pattern.syntax.empty-expression");
        assertSyntaxError("{a||b}", 4, "pattern.syntax.empty-alternative");
        assertSyntaxError("{a|}", 4, "pattern.syntax.empty-alternative");
        assertSyntaxError("{a|'x}", 4, "pattern.syntax.unclosed-quote");
        assertSyntaxError("{'a'b}", 5, "pattern.syntax.text-after-quote");
        assertSyntaxError("{a{b}", 1, "pattern.syntax.unclosed-brace");
    }
}
```
Notes for the implementer: `CopybotException.ofResource(key, args...)` builds the message with `ResourcesEngine.getString(key, args)`; check how `getMessage()` is built in `copybot-engine/src/main/java/com/copybot/exception/CopybotException.java` and adapt the assertion helper if the message is localized differently (e.g. `getLocalizedMessage()`), keeping the same intent: key, pattern, position and reason. Positions are 1-based character indexes: for an unclosed brace or a nested `{`, the position of the opening `{` of the unfinished expression; for `}` without `{`, its own position; for an empty expression, the position of its `{`; for an empty alternative, the position of the `|` or `}` that ends it; for an unclosed quote, the position of the opening `'`; for text after a quote, the position of the first non-space character after the closing quote. The position argument is passed as an `Integer` — if MessageFormat would format `1000` as `1,000` that does not matter for these tests.

- [ ] **Step 3: Run the tests to verify they fail**

Run: `mvn -q -B -pl copybot-engine test -Dtest=OutPatternTest`
Expected: compilation failure (`OutPattern` does not exist).

- [ ] **Step 4: Implement `OutPattern`**

```java
package com.copybot.plugin.api.pattern;

import com.copybot.exception.CopybotException;
import com.copybot.resources.ResourcesEngine;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * An output pattern (spec pattern-helper §1): text copied as is, and {expressions} replaced by a metadata
 * value. An expression is a list of alternatives separated by "|", each a display key or a fixed value
 * between quotes ('' for a quote); the first alternative with a non blank value wins. Parsed once, resolved
 * per item; immutable and thread-safe.
 */
public final class OutPattern {

    /** A piece of the pattern: literal text, or an expression with its source text (braces included). */
    private sealed interface Part permits Text, Expression {
    }

    private record Text(String text) implements Part {
    }

    /** @param alternatives a key, or a fixed value (FixedValue) */
    private record Expression(String source, List<Object> alternatives) implements Part {
    }

    private record FixedValue(String value) {
    }

    /**
     * @param text    the resolved text; an expression without value is copied as written, braces included
     * @param missing the expressions without value, as written (e.g. "{captureDate.Y|lastModified.Y}")
     */
    public record Resolution(String text, List<String> missing) {
        public Resolution {
            missing = List.copyOf(missing);
        }

        public boolean complete() {
            return missing.isEmpty();
        }
    }

    private final String pattern;
    private final List<Part> parts;

    private OutPattern(String pattern, List<Part> parts) {
        this.pattern = pattern;
        this.parts = List.copyOf(parts);
    }

    /** @throws CopybotException pattern.syntax (the pattern, the 1-based position, the reason) */
    public static OutPattern parse(String pattern) {
        List<Part> parts = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c == '}') {
                throw syntax(pattern, i, "pattern.syntax.unopened-brace");
            }
            if (c != '{') {
                text.append(c);
                i++;
                continue;
            }
            if (!text.isEmpty()) {
                parts.add(new Text(text.toString()));
                text.setLength(0);
            }
            int end = parseExpression(pattern, i, parts);
            i = end + 1;
        }
        if (!text.isEmpty()) {
            parts.add(new Text(text.toString()));
        }
        return new OutPattern(pattern, parts);
    }

    /** Parses the expression opened at index open, adds it to parts; returns the index of its closing brace. */
    private static int parseExpression(String pattern, int open, List<Part> parts) {
        List<Object> alternatives = new ArrayList<>();
        int i = open + 1;
        while (true) {
            i = skipSpaces(pattern, i);
            if (i >= pattern.length()) {
                throw syntax(pattern, open, "pattern.syntax.unclosed-brace");
            }
            char c = pattern.charAt(i);
            if (c == '{') {
                throw syntax(pattern, open, "pattern.syntax.unclosed-brace");
            }
            if (c == '\'') {
                int quote = i;
                StringBuilder value = new StringBuilder();
                i++;
                while (true) {
                    if (i >= pattern.length()) {
                        throw syntax(pattern, quote, "pattern.syntax.unclosed-quote");
                    }
                    if (pattern.charAt(i) == '\'') {
                        if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '\'') {
                            value.append('\'');
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    value.append(pattern.charAt(i++));
                }
                alternatives.add(new FixedValue(value.toString()));
                i = skipSpaces(pattern, i);
                if (i < pattern.length() && pattern.charAt(i) != '|' && pattern.charAt(i) != '}') {
                    throw syntax(pattern, i, "pattern.syntax.text-after-quote");
                }
            } else if (c == '|' || c == '}') {
                if (alternatives.isEmpty() && c == '}') {
                    throw syntax(pattern, open, "pattern.syntax.empty-expression");
                }
                throw syntax(pattern, i, "pattern.syntax.empty-alternative");
            } else {
                int start = i;
                while (i < pattern.length() && "|}{'".indexOf(pattern.charAt(i)) < 0) {
                    i++;
                }
                alternatives.add(pattern.substring(start, i).strip());
            }
            if (i >= pattern.length()) {
                throw syntax(pattern, open, "pattern.syntax.unclosed-brace");
            }
            char separator = pattern.charAt(i);
            if (separator == '}') {
                parts.add(new Expression(pattern.substring(open, i + 1), List.copyOf(alternatives)));
                return i;
            }
            if (separator != '|') { // '{' or a quote right after a key
                throw syntax(pattern, open, "pattern.syntax.unclosed-brace");
            }
            i++;
        }
    }

    private static int skipSpaces(String pattern, int i) {
        while (i < pattern.length() && pattern.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    private static CopybotException syntax(String pattern, int index, String reasonKey) {
        return CopybotException.ofResource("pattern.syntax", pattern, index + 1, ResourcesEngine.getString(reasonKey));
    }

    public String pattern() {
        return pattern;
    }

    /** The keys used, in order, without duplicates (fixed values excluded). */
    public List<String> keys() {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (Part part : parts) {
            if (part instanceof Expression expression) {
                for (Object alternative : expression.alternatives()) {
                    if (alternative instanceof String key) {
                        keys.add(key);
                    }
                }
            }
        }
        return List.copyOf(keys);
    }

    /** The text before the first expression (the whole pattern without expression). */
    public String staticPrefix() {
        return !parts.isEmpty() && parts.getFirst() instanceof Text text ? text.text() : "";
    }

    public Resolution resolve(Map<String, String> display) {
        StringBuilder out = new StringBuilder();
        List<String> missing = new ArrayList<>();
        for (Part part : parts) {
            switch (part) {
                case Text text -> out.append(text.text());
                case Expression expression -> {
                    String value = valueOf(expression, display);
                    if (value == null) {
                        missing.add(expression.source());
                        out.append(expression.source());
                    } else {
                        out.append(value);
                    }
                }
            }
        }
        return new Resolution(out.toString(), missing);
    }

    private static String valueOf(Expression expression, Map<String, String> display) {
        for (Object alternative : expression.alternatives()) {
            if (alternative instanceof FixedValue fixed) {
                return fixed.value();
            }
            String value = display.get((String) alternative);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
```
Check the trace of `{ }` (empty expression with a space): after `skipSpaces` we are on `}` with no alternative ⇒ `empty-expression` at the `{` (position 1). `{a{b}`: the key loop stops at `{`, separator `{` ⇒ unclosed brace at the opening `{` of the unfinished expression (position 1). Make sure every case in `syntaxErrorsGiveTheirPosition` matches the positions listed in Step 2's note, run the test, and fix the code (not the expectations) for any discrepancy.

Add to `module-info.java`: `exports com.copybot.plugin.api.pattern;` beside the existing `exports com.copybot.plugin.api...;` lines.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `mvn -q -B -pl copybot-engine test -Dtest=OutPatternTest+SafeWriteBundleTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add copybot-engine/src/main/java/com/copybot/plugin/api/pattern copybot-engine/src/main/java/module-info.java copybot-engine/src/main/resources/com/copybot/engine/i18n copybot-engine/src/test/java/com/copybot/plugin/api/pattern copybot-engine/src/test/java/com/copybot/resources/SafeWriteBundleTest.java
git commit -m "Add OutPattern: output pattern with fallbacks and fixed values"
```

---

### Task 2: `file.write` uses `OutPattern`, `onMissingKey`, destination probe

**Files:**
- Modify: `copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileWriteConfig.java` (field `onMissingKey`)
- Modify: `copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileWriteSettings.java` (enum `MissingKey`, parsed `OutPattern`)
- Modify: `copybot-engine/src/main/java/com/copybot/plugin/embedded/actions/FileWriteAction.java` (resolution, `touchedPaths`, `resolveTarget`, `write`)
- Modify: `copybot-engine/src/main/java/com/copybot/engine/resume/ResumeResolver.java:140-152` (exclude items whose target throws `CopybotException`)
- Modify: `copybot-engine/src/main/resources/com/copybot/engine/i18n/engineBundle.properties`, `engineBundle_fr.properties`
- Modify: `copybot-engine/src/main/resources/com/copybot/plugin/embedded/i18n/pluginBundle.properties`, `pluginBundle_fr.properties` (field label/description)
- Modify: `copybot-engine/src/test/java/com/copybot/resources/SafeWriteBundleTest.java`
- Modify: `docs/superpowers/specs/2026-09-30-safe-write-design.md` (§1 config: `onMissingKey`, pattern syntax pointer to the new spec)
- Test: `copybot-engine/src/test/java/com/copybot/plugin/embedded/actions/FileWriteMissingKeyTest.java`, `copybot-engine/src/test/java/com/copybot/engine/resume/ResumeResolverMissingKeyTest.java`, `copybot-engine/src/test/java/com/copybot/plugin/embedded/actions/EmbeddedConfigSchemaTest.java` (new enum assertion)

**Interfaces:**
- Consumes: `OutPattern.parse`, `OutPattern.resolve`, `OutPattern.staticPrefix`, `OutPattern.Resolution` (Task 1).
- Produces: `FileWriteSettings.MissingKey { ERROR("error"), SKIP("skip"), LITERAL("literal") }`; `FileWriteSettings.outPattern()` becomes an `OutPattern` (record component type change; callers adapted); `resolveTarget` throws `CopybotException` key `write.pattern.missing-key` (args: missing expressions joined by `", "`, item name) in ERROR and SKIP modes.

- [ ] **Step 1: Add the message keys**

`engineBundle.properties`:
```properties
write.pattern.missing-key=No value for {0}: {1}
write.skip.missing-key=missing key: {0}
```
`engineBundle_fr.properties`:
```properties
write.pattern.missing-key=Aucune valeur pour {0} : {1}
write.skip.missing-key=cl\u00e9 absente : {0}
```
`pluginBundle.properties` (next to the other `plugin.embedded.file.write.config.*` keys):
```properties
plugin.embedded.file.write.config.onMissingKey.name=On missing key
plugin.embedded.file.write.config.onMissingKey.description=When an expression of the pattern has no value: error (the item fails), skip (the item is skipped) or literal (the expression is kept as written)
```
`pluginBundle_fr.properties`:
```properties
plugin.embedded.file.write.config.onMissingKey.name=Cl\u00e9 absente
plugin.embedded.file.write.config.onMissingKey.description=Quand une expression du pattern n'a pas de valeur : error (l'\u00e9l\u00e9ment \u00e9choue), skip (l'\u00e9l\u00e9ment est ignor\u00e9) ou literal (l'expression est gard\u00e9e telle quelle)
```
Add `"write.pattern.missing-key"`, `"write.skip.missing-key"` to `SafeWriteBundleTest.KEYS`. Check that an existing test enumerates the plugin bundle keys of `file.write` fields (search `EmbeddedActionResourcesTest` / `EmbeddedConfigSchemaTest` for `.name`): if one checks every schema field has a label, the new keys satisfy it.

- [ ] **Step 2: Write the failing tests**

`FileWriteMissingKeyTest.java`:
```java
package com.copybot.plugin.embedded.actions;

import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WriteContext;
import com.copybot.plugin.api.action.WriteResult;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class FileWriteMissingKeyTest {

    @TempDir
    Path tempDir;

    /** file.write to tempDir/out/{captureDate.Y}/{name}; extra is appended to the actionConfig. */
    private FileWriteAction action(String extra) {
        String out = tempDir.resolve("out").toString().replace("\\", "/") + "/{captureDate.Y}/{name}";
        FileWriteAction action = new FileWriteAction();
        action.loadConfig(JsonParser.parseString("{\"outPattern\":\"" + out + "\"" + extra + "}"));
        return action;
    }

    /** A source file without captureDate (like a video without EXIF). */
    private WorkItem video() throws IOException {
        Path source = Files.writeString(tempDir.resolve("VID_0001.MP4"), "video");
        WorkItem item = new WorkItem(source);
        item.getMetadatas().display().put("name", "VID_0001.MP4");
        item.getMetadatas().setSize(5);
        return item;
    }

    @Test
    public void byDefaultAMissingKeyFailsTheItemAndCreatesNothing() throws IOException {
        WorkItem item = video();

        CopybotException e = assertThrows(CopybotException.class, () -> action("").write(item, WriteContext.newRun()));

        assertTrue(e.getMessage().contains("{captureDate.Y}"), e.getMessage());
        assertTrue(e.getMessage().contains("VID_0001.MP4"), e.getMessage());
        assertFalse(Files.exists(tempDir.resolve("out")), "nothing is created on the destination");
    }

    @Test
    public void skipSkipsTheItemWithTheMissingExpression() throws IOException {
        WriteResult result = action(",\"onMissingKey\":\"skip\"").write(video(), WriteContext.newRun());

        assertTrue(result.isSkipped());
        assertTrue(result.reason().contains("{captureDate.Y}"), result.reason());
        assertFalse(Files.exists(tempDir.resolve("out")));
    }

    @Test
    public void literalKeepsTheExpressionAsWritten() throws IOException {
        action(",\"onMissingKey\":\"literal\"").write(video(), WriteContext.newRun());

        assertTrue(Files.exists(tempDir.resolve("out").resolve("{captureDate.Y}").resolve("VID_0001.MP4")));
    }

    @Test
    public void aFallbackAvoidsTheMissingKey() throws IOException {
        String out = tempDir.resolve("out").toString().replace("\\", "/") + "/{captureDate.Y|'sans-date'}/{name}";
        FileWriteAction action = new FileWriteAction();
        action.loadConfig(JsonParser.parseString("{\"outPattern\":\"" + out + "\"}"));

        action.write(video(), WriteContext.newRun());

        assertTrue(Files.exists(tempDir.resolve("out").resolve("sans-date").resolve("VID_0001.MP4")));
    }

    @Test
    public void theSourceIsNeverDeletedOnAMissingKey() throws IOException {
        WorkItem item = video();

        action(",\"onMissingKey\":\"skip\",\"deleteSource\":true,\"verify\":\"readBack\"").write(item, WriteContext.newRun());
        assertThrows(CopybotException.class,
                () -> action(",\"deleteSource\":true,\"verify\":\"readBack\"").write(item, WriteContext.newRun()));

        assertTrue(Files.exists(tempDir.resolve("VID_0001.MP4")));
    }

    @Test
    public void resolveTargetThrowsInErrorAndSkipModesAndResolvesInLiteralMode() throws IOException {
        WorkItem item = video();

        assertThrows(CopybotException.class, () -> action("").resolveTarget(item));
        assertThrows(CopybotException.class, () -> action(",\"onMissingKey\":\"skip\"").resolveTarget(item));
        assertTrue(action(",\"onMissingKey\":\"literal\"").resolveTarget(item).orElseThrow().toString().contains("{captureDate.Y}"));
    }

    @Test
    public void anInvalidPatternIsAConfigurationError() {
        FileWriteAction action = new FileWriteAction();
        assertThrows(CopybotException.class, () -> action.loadConfig(JsonParser.parseString("{\"outPattern\":\"/a/{name\"}")));
    }

    @Test
    public void anUnknownOnMissingKeyIsRefused() {
        FileWriteAction action = new FileWriteAction();
        CopybotException e = assertThrows(CopybotException.class,
                () -> action.loadConfig(JsonParser.parseString("{\"outPattern\":\"x\",\"onMissingKey\":\"ignore\"}")));
        assertTrue(e.getMessage().contains("ignore"), e.getMessage());
    }
}
```
In `EmbeddedConfigSchemaTest.theWriteSchemaDescribesItsFields` (the method holding the `writeMode`/`verify` assertions), add:
```java
assertEquals(jsonNames(FileWriteSettings.MissingKey.values()), field(schema, "onMissingKey").enumValues());
```
and in `theDefaultsShownAreTheOnesApplied`:
```java
assertEquals(defaults.onMissingKey().jsonName(), field(schema, "onMissingKey").defaultValue());
```

`ResumeResolverMissingKeyTest.java`: first read `copybot-engine/src/test/java/com/copybot/engine/resume/` to find the existing destination-mode test and reuse its helpers (how `ResumeResolver` is built, how items with a `ItemKey` are created, a fake `IOutAction`). Write a test with three dated items whose fake out action's `resolveTarget` returns `tempDir/<day>/<name>` for two of them and throws `CopybotException.ofResource("write.pattern.missing-key", "{captureDate.Y}", "VID.MP4")` for the third (the latest one); create the directory of the first day only. Expected: `propose(...)` does not throw, the proposal source is `DESTINATION`, its point is `after(key of day 1)`; the third item stays selected after `apply`. A second test: a `resolveTarget` that throws `IllegalStateException` still propagates (unchanged behaviour). Name them `anItemWithoutTargetIsLeftOutOfTheProbe` and `anotherFailureOfTheTargetStillPropagates`.

- [ ] **Step 3: Run the tests to verify they fail**

Run: `mvn -q -B -pl copybot-engine test -Dtest=FileWriteMissingKeyTest+ResumeResolverMissingKeyTest+EmbeddedConfigSchemaTest`
Expected: compilation failure / failures (no `onMissingKey`).

- [ ] **Step 4: Implement**

`FileWriteConfig`: add after `outPattern`:
```java
        @AllowedValues({"error", "skip", "literal"}) @DefaultValue("error")
        String onMissingKey,
```
and the Javadoc line `@param onMissingKey "error" (default), "skip" or "literal": what to do when an expression of outPattern has no value`.

`FileWriteSettings`:
- record components: `OutPattern outPattern, MissingKey onMissingKey, Compare compare, ...` (replace `String outPattern`).
- enum, same shape as `Verify`:
```java
    /** What to do when an expression of outPattern has no value for an item (spec pattern-helper §2). */
    enum MissingKey implements Named {
        ERROR("error"), SKIP("skip"), LITERAL("literal");

        private final String jsonName;

        MissingKey(String jsonName) {
            this.jsonName = jsonName;
        }

        @Override
        public String jsonName() {
            return jsonName;
        }
    }
```
- in `of`: after the blank check, `OutPattern pattern = OutPattern.parse(config.outPattern());` and `MissingKey missingKey = parse("onMissingKey", config.onMissingKey(), MissingKey.ERROR, MissingKey.class);`; pass both to the constructor; add `pattern.syntax` to the `@throws` Javadoc.
- Every use of `settings.outPattern()` as a `String` elsewhere (search the package and tests: `grep -rn "outPattern()" copybot-engine/src`) is adapted (`.pattern()` for the text).

`FileWriteAction`:
- remove `PARAM_PATTERN` and the `java.util.regex.Pattern` import.
- `touchedPaths`: `return Set.of(Path.of(settings.outPattern().staticPrefix()));`
- replace `resolveFileName` by:
```java
    /**
     * The target of the item, or why there is none (spec pattern-helper §2).
     *
     * @return the resolution; with ERROR or SKIP, check {@link OutPattern.Resolution#complete()} first
     */
    private OutPattern.Resolution resolution(WorkItem workItem) {
        return settings.outPattern().resolve(workItem.getMetadatas().display());
    }

    private CopybotException missingKey(WorkItem workItem, OutPattern.Resolution resolution) {
        return CopybotException.ofResource("write.pattern.missing-key", String.join(", ", resolution.missing()),
                workItem.getNameDisplay());
    }
```
- `resolveTarget`:
```java
    /** @throws CopybotException write.pattern.missing-key, unless onMissingKey is "literal" */
    @Override
    public Optional<Path> resolveTarget(WorkItem workItem) {
        OutPattern.Resolution resolution = resolution(workItem);
        if (!resolution.complete() && settings.onMissingKey() != FileWriteSettings.MissingKey.LITERAL) {
            throw missingKey(workItem, resolution);
        }
        return Optional.of(Path.of(resolution.text()));
    }
```
- `write`: at the very start, before computing `target`:
```java
        OutPattern.Resolution resolution = resolution(workItem);
        if (!resolution.complete()) {
            switch (settings.onMissingKey()) {
                case ERROR -> throw missingKey(workItem, resolution);
                case SKIP -> {
                    return WriteResult.skipped(null, ResourcesEngine.getString("write.skip.missing-key",
                            String.join(", ", resolution.missing())));
                }
                case LITERAL -> { } // the expression stays as written (historical behaviour)
            }
        }
        Path target = Path.of(resolution.text());
```
(import `com.copybot.resources.ResourcesEngine` and `com.copybot.plugin.api.pattern.OutPattern`). Check `WorkItem.getNameDisplay()` is non-null for items built from a `Path` (it is the file name); for URL items it may be null — then use `workItem.getSourceLocationDisplay()` as the name: `String name = workItem.getNameDisplay() != null ? workItem.getNameDisplay() : workItem.getSourceLocationDisplay();`.

`ResumeResolver` (around line 147):
```java
            Optional<Path> target;
            try {
                target = out.resolveTarget(item.getWorkItem());
            } catch (CopybotException e) {
                // e.g. no value for a pattern expression: this item cannot be probed, it stays selected and
                // fails or is skipped at the execution, by its own onMissingKey (spec pattern-helper §2)
                continue;
            }
```
(import `com.copybot.exception.CopybotException`).

Spec safe-write §1: add `"onMissingKey": "error"` to the JSON example and one sentence after the "Tous les champs sauf `outPattern`..." paragraph: « Syntaxe de `outPattern` (repli `|`, valeurs fixes) et `onMissingKey` : voir la spec pattern-helper (2026-10-02). »

- [ ] **Step 5: Run the engine tests**

Run: `mvn -q -B -pl copybot-engine test`
Expected: PASS (all, including the existing `FileWriteActionTest`, `ResumeEndToEndTest`, `CopybotExitCodeTest` which use complete patterns).

- [ ] **Step 6: Commit**

```bash
git add -A copybot-engine docs/superpowers/specs/2026-09-30-safe-write-design.md
git commit -m "file.write: fallback patterns and onMissingKey; the destination probe skips items without target"
```

---

### Task 3: Process dry run API and `DryRunner`

**Files:**
- Modify: `copybot-engine/src/main/java/com/copybot/plugin/api/action/IProcessAction.java`
- Modify: `copybot-engine/src/main/java/com/copybot/plugin/api/action/WorkItem.java` (`copyForDryRun`)
- Create: `copybot-engine/src/main/java/com/copybot/engine/DryRunner.java`
- Create: `copybot-engine/src/main/java/com/copybot/engine/Projection.java`
- Modify: engine bundles (EN/FR) — keys `dryrun.filtered`, `dryrun.unsupported`, `dryrun.failed`
- Modify: `SafeWriteBundleTest.KEYS`
- Test: `copybot-engine/src/test/java/com/copybot/engine/DryRunnerTest.java`, `copybot-engine/src/test/java/com/copybot/plugin/api/action/WorkItemCopyTest.java`

**Interfaces:**
- Produces:
  - `IProcessAction`: `default Optional<List<WorkItem>> dryRun(WorkItem item) { return Optional.empty(); }`
  - `WorkItem`: `public WorkItem copyForDryRun()` — a new `WorkItem` with the same source fields (`sourceLocationDisplay`, `nameDisplay`, `sourceLocationUrl`, `inputStreamSupplier`, `sourceLocationPath`, `tempLocation`, `isLocal`, `isDeleteAfterCompletion`) and copies of `raw` and `display` (new `HashMap`s; values are shared, they are immutable `String`/`Instant`/`Long`).
  - `public sealed interface Projection` (package `com.copybot.engine`) with records `Projected(List<WorkItem> items)`, `Filtered(String action)`, `Unsupported(String action, List<WorkItem> before)`, `Failed(String action, String message)`.
  - `public final class DryRunner` with `public static Projection project(WorkItem item, List<PipelineStep<IProcessAction>> processSteps)` and `public static String actionName(PipelineStep<?> step)` (= `step.getConfig() == null ? step.getAction().getClass().getSimpleName() : step.getConfig().action()`).

- [ ] **Step 1: Add the message keys**

EN: `dryrun.filtered=filtered out by {0}`, `dryrun.unsupported=unknown: {0} does not support the dry run`, `dryrun.failed=dry run of {0} failed: {1}`.
FR: `dryrun.filtered=filtr\u00e9 par {0}`, `dryrun.unsupported=inconnue : {0} ne g\u00e8re pas le dry run`, `dryrun.failed=\u00e9chec du dry run de {0} : {1}`.
Add the three keys to `SafeWriteBundleTest.KEYS`.

- [ ] **Step 2: Write the failing tests**

`WorkItemCopyTest`:
```java
package com.copybot.plugin.api.action;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

public class WorkItemCopyTest {

    @TempDir
    Path tempDir;

    @Test
    public void aCopyHasTheSameSourceAndIndependentMetadata() throws IOException {
        Path file = Files.writeString(tempDir.resolve("DSC_1.NEF"), "x");
        WorkItem item = new WorkItem(file);
        item.getMetadatas().display().put("name", "DSC_1.NEF");
        item.getMetadatas().setTime(WorkItemMetadata.CAPTURE_DATE, Instant.parse("2026-09-28T10:00:00Z"));

        WorkItem copy = item.copyForDryRun();
        copy.getMetadatas().display().put("name", "DSC_1.jpg");
        copy.getMetadatas().raw().put("extra", 1L);

        assertEquals(item.getLocalLocation(), copy.getLocalLocation());
        assertEquals(item.getNameDisplay(), copy.getNameDisplay());
        assertEquals("DSC_1.NEF", item.getMetadatas().display().get("name"));
        assertFalse(item.getMetadatas().raw().containsKey("extra"));
        assertEquals(item.getMetadatas().getTime(WorkItemMetadata.CAPTURE_DATE), copy.getMetadatas().getTime(WorkItemMetadata.CAPTURE_DATE));
    }
}
```
`DryRunnerTest` (fake process actions; build `PipelineStep` with `new PipelineStep<>(null, action, stepConfig("conv"))` where `stepConfig(name)` = `new PipelineStepConfig(null, name, null, null, null, null, null, null)`; fake actions extend nothing but implement `IProcessAction` — copy the minimal `IAction` stub methods from `ControlFakes.FakeAction` (same package `com.copybot.engine`: reuse `ControlFakes.FakeAction` directly, it is package-private in that package)):
```java
package com.copybot.engine;

import com.copybot.engine.pipeline.PipelineStep;
import com.copybot.engine.pipeline.PipelineStepConfig;
import com.copybot.plugin.api.action.IProcessAction;
import com.copybot.plugin.api.action.WorkItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

public class DryRunnerTest {

    @TempDir
    Path tempDir;

    /** A process action whose dry run is the given function; doProcess must never be called. */
    static final class FakeProcess extends ControlFakes.FakeAction implements IProcessAction {
        private final Function<WorkItem, Optional<List<WorkItem>>> dryRun;

        FakeProcess(Function<WorkItem, Optional<List<WorkItem>>> dryRun) {
            this.dryRun = dryRun;
        }

        @Override
        public List<WorkItem> doProcess(WorkItem item) {
            throw new AssertionError("doProcess must not run in a dry run");
        }

        @Override
        public Optional<List<WorkItem>> dryRun(WorkItem item) {
            return dryRun.apply(item);
        }
    }

    /** Converts the item to JPG (display name). */
    static FakeProcess toJpg() {
        return new FakeProcess(item -> {
            item.getMetadatas().display().put("name", item.getMetadatas().display().get("name").replace(".NEF", ".jpg"));
            return Optional.of(List.of(item));
        });
    }

    static PipelineStep<IProcessAction> step(String name, IProcessAction action) {
        return new PipelineStep<>(null, action, new PipelineStepConfig(null, name, null, null, null, null, null, null));
    }

    private WorkItem nef() throws IOException {
        WorkItem item = new WorkItem(Files.writeString(tempDir.resolve("DSC_1.NEF"), "x"));
        item.getMetadatas().display().put("name", "DSC_1.NEF");
        return item;
    }

    @Test
    public void withoutProcessStepTheProjectionIsACopyOfTheItem() throws IOException {
        WorkItem item = nef();

        Projection.Projected projected = assertInstanceOf(Projection.Projected.class, DryRunner.project(item, List.of()));

        assertEquals(1, projected.items().size());
        assertNotSame(item, projected.items().getFirst());
        assertEquals("DSC_1.NEF", projected.items().getFirst().getMetadatas().display().get("name"));
    }

    @Test
    public void theStepsAreChainedOnEveryProducedItem() throws IOException {
        FakeProcess fork = new FakeProcess(item -> {
            WorkItem thumb = item.copyForDryRun();
            thumb.getMetadatas().display().put("name", "thumb_" + item.getMetadatas().display().get("name"));
            return Optional.of(List.of(item, thumb));
        });

        Projection projection = DryRunner.project(nef(), List.of(step("fork", fork), step("conv", toJpg())));

        List<String> names = assertInstanceOf(Projection.Projected.class, projection).items().stream()
                .map(i -> i.getMetadatas().display().get("name")).toList();
        assertEquals(List.of("DSC_1.jpg", "thumb_DSC_1.jpg"), names);
    }

    @Test
    public void anEmptyListIsFilteredByThatStep() throws IOException {
        Projection projection = DryRunner.project(nef(), List.of(step("drop", new FakeProcess(i -> Optional.of(List.of())))));

        assertEquals(new Projection.Filtered("drop"), projection);
    }

    @Test
    public void aStepWithoutDryRunStopsWithTheItemsBeforeIt() throws IOException {
        Projection projection = DryRunner.project(nef(),
                List.of(step("conv", toJpg()), step("legacy", new FakeProcess(i -> Optional.empty()))));

        Projection.Unsupported unsupported = assertInstanceOf(Projection.Unsupported.class, projection);
        assertEquals("legacy", unsupported.action());
        assertEquals("DSC_1.jpg", unsupported.before().getFirst().getMetadatas().display().get("name"));
    }

    @Test
    public void aNullDryRunMeansUnsupported() throws IOException {
        assertInstanceOf(Projection.Unsupported.class,
                DryRunner.project(nef(), List.of(step("legacy", new FakeProcess(i -> null)))));
    }

    @Test
    public void aFailingDryRunGivesItsMessage() throws IOException {
        Projection projection = DryRunner.project(nef(), List.of(step("boom", new FakeProcess(i -> {
            throw new IllegalStateException("no codec");
        }))));

        assertEquals(new Projection.Failed("boom", "no codec"), projection);
    }

    @Test
    public void theRealItemIsNeverTouchedByTheDryRun() throws IOException {
        WorkItem item = nef();

        DryRunner.project(item, List.of(step("conv", toJpg())));

        assertEquals("DSC_1.NEF", item.getMetadatas().display().get("name"));
    }
}
```
If `ControlFakes.FakeAction` has abstract methods or a constructor argument, adapt `FakeProcess` to it (read `ControlFakes.java:45-58`).

- [ ] **Step 3: Run the tests to verify they fail**

Run: `mvn -q -B -pl copybot-engine test -Dtest=DryRunnerTest+WorkItemCopyTest`
Expected: compilation failure.

- [ ] **Step 4: Implement**

`IProcessAction`:
```java
package com.copybot.plugin.api.action;

import java.util.List;
import java.util.Optional;

public interface IProcessAction extends IAction {

    List<WorkItem> doProcess(WorkItem item);

    /**
     * The expected effect of {@link #doProcess}, without writing anything nor any heavy work (spec
     * pattern-helper §4): used by "Prepare" and by the editor's sample. The item is a copy
     * ({@link WorkItem#copyForDryRun()}): modify it, return it and/or other copies (a fork), or an empty
     * list (filtered out). Empty (the default): this action does not support the dry run — never read as
     * "no effect". A null result counts as empty.
     */
    default Optional<List<WorkItem>> dryRun(WorkItem item) {
        return Optional.empty();
    }
}
```
`WorkItem.copyForDryRun()`: add a private no-arg constructor `private WorkItem() { }` and:
```java
    /**
     * A copy for a dry run (spec pattern-helper §4): same source, its own metadata tables (the values, immutable,
     * are shared), so that changing the copy never changes this item.
     */
    public WorkItem copyForDryRun() {
        WorkItem copy = new WorkItem();
        copy.sourceLocationDisplay = sourceLocationDisplay;
        copy.nameDisplay = nameDisplay;
        copy.sourceLocationUrl = sourceLocationUrl;
        copy.inputStreamSupplier = inputStreamSupplier;
        copy.sourceLocationPath = sourceLocationPath;
        copy.tempLocation = tempLocation;
        copy.isLocal = isLocal;
        copy.isDeleteAfterCompletion = isDeleteAfterCompletion;
        copy.isDeleted = isDeleted;
        copy.metadatas.raw().putAll(metadatas.raw());
        copy.metadatas.display().putAll(metadatas.display());
        return copy;
    }
```
`Projection`:
```java
package com.copybot.engine;

import com.copybot.plugin.api.action.WorkItem;

import java.util.List;

/** What the process steps would produce from one item, by their dry run (spec pattern-helper §4.2). */
public sealed interface Projection {

    /** One or more produced items (copies: never the real item). */
    record Projected(List<WorkItem> items) implements Projection {
        public Projected {
            items = List.copyOf(items);
        }
    }

    /** A step returned no item. */
    record Filtered(String action) implements Projection {
    }

    /** A step does not support the dry run; before: the items before that step. */
    record Unsupported(String action, List<WorkItem> before) implements Projection {
        public Unsupported {
            before = List.copyOf(before);
        }
    }

    /** A dry run threw. */
    record Failed(String action, String message) implements Projection {
    }
}
```
`DryRunner`:
```java
package com.copybot.engine;

import com.copybot.engine.pipeline.PipelineStep;
import com.copybot.plugin.api.action.IProcessAction;
import com.copybot.plugin.api.action.WorkItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Chains the dry run of the process steps on a copy of an item (spec pattern-helper §4.2). */
public final class DryRunner {

    private DryRunner() {
    }

    /** Never throws for a plugin failure: it becomes {@link Projection.Failed}. */
    public static Projection project(WorkItem item, List<PipelineStep<IProcessAction>> processSteps) {
        List<WorkItem> current = List.of(item.copyForDryRun());
        for (PipelineStep<IProcessAction> step : processSteps) {
            List<WorkItem> next = new ArrayList<>();
            for (WorkItem produced : current) {
                Optional<List<WorkItem>> result;
                try {
                    result = step.getAction().dryRun(produced);
                } catch (RuntimeException e) {
                    return new Projection.Failed(actionName(step), e.getMessage() != null ? e.getMessage() : e.getClass().getName());
                }
                if (result == null || result.isEmpty()) {
                    return new Projection.Unsupported(actionName(step), current);
                }
                next.addAll(result.get());
            }
            if (next.isEmpty()) {
                return new Projection.Filtered(actionName(step));
            }
            current = next;
        }
        return new Projection.Projected(current);
    }

    /** The action code of the step, as written in the pipeline. */
    public static String actionName(PipelineStep<?> step) {
        return step.getConfig() == null ? step.getAction().getClass().getSimpleName() : step.getConfig().action();
    }
}
```
Note: with a fork where one branch filters, `next` is non-empty: the filtered branch simply disappears (only an all-empty step is `Filtered`). Add the Javadoc sentence: "A produced item that one step drops simply disappears; the projection is Filtered only when no item is left."

- [ ] **Step 5: Run the tests**

Run: `mvn -q -B -pl copybot-engine test -Dtest=DryRunnerTest+WorkItemCopyTest+SafeWriteBundleTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A copybot-engine
git commit -m "Add the dry run of the process steps: IProcessAction.dryRun and DryRunner"
```

---

### Task 4: Extract `StepResolver` from `MainExecutor`

**Files:**
- Create: `copybot-engine/src/main/java/com/copybot/engine/StepResolver.java`
- Modify: `copybot-engine/src/main/java/com/copybot/engine/MainExecutor.java:465-475, 811-830` (use it; delete `resolveOtherSteps` / `doResolveStep`)

**Interfaces:**
- Produces: `public final class StepResolver` with
  - `public static List<PipelineStep<IInAction>> in(PipelineConfig config)`
  - `public static List<PipelineStep<IAnalyzeAction>> analyse(PipelineConfig config)`
  - `public static List<PipelineStep<IProcessAction>> process(PipelineConfig config)`
  - `public static List<PipelineStep<?>> itemSteps(PipelineConfig config)` (analyse + process + out, as `resolveOtherSteps` did)
  All resolve through `PluginEngine.resolve(stepConfig, actionClass)`, null/empty lists ⇒ `List.of()`, unmodifiable results.

- [ ] **Step 1: Implement the class**

```java
package com.copybot.engine;

import com.copybot.engine.pipeline.PipelineConfig;
import com.copybot.engine.pipeline.PipelineStep;
import com.copybot.engine.pipeline.PipelineStepConfig;
import com.copybot.engine.plugin.PluginEngine;
import com.copybot.plugin.api.action.IAction;
import com.copybot.plugin.api.action.IAnalyzeAction;
import com.copybot.plugin.api.action.IInAction;
import com.copybot.plugin.api.action.IOutAction;
import com.copybot.plugin.api.action.IProcessAction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Resolves the steps of a pipeline to configured action instances (a new instance per call, through
 * {@link PluginEngine#resolve}). Shared by the executor and the sampler, so that both instantiate the plugins
 * the same way.
 *
 * @throws RuntimeException from every method: a plugin not found, an action not found, an invalid configuration
 */
public final class StepResolver {

    private StepResolver() {
    }

    public static List<PipelineStep<IInAction>> in(PipelineConfig config) {
        return resolve(config.inSteps(), IInAction.class);
    }

    public static List<PipelineStep<IAnalyzeAction>> analyse(PipelineConfig config) {
        return resolve(config.analyseSteps(), IAnalyzeAction.class);
    }

    public static List<PipelineStep<IProcessAction>> process(PipelineConfig config) {
        return resolve(config.actionSteps(), IProcessAction.class);
    }

    /** The steps every item goes through: analyses, processes, then the out step if any. */
    public static List<PipelineStep<?>> itemSteps(PipelineConfig config) {
        List<PipelineStep<?>> steps = new ArrayList<>();
        steps.addAll(analyse(config));
        steps.addAll(process(config));
        if (config.outStep() != null) {
            steps.add(PluginEngine.resolve(config.outStep(), IOutAction.class));
        }
        return Collections.unmodifiableList(steps);
    }

    private static <A extends IAction> List<PipelineStep<A>> resolve(List<PipelineStepConfig> stepConfigs, Class<A> actionClass) {
        if (stepConfigs == null || stepConfigs.isEmpty()) {
            return List.of();
        }
        List<PipelineStep<A>> steps = new ArrayList<>(stepConfigs.size());
        for (PipelineStepConfig stepConfig : stepConfigs) {
            steps.add(PluginEngine.resolve(stepConfig, actionClass));
        }
        return Collections.unmodifiableList(steps);
    }
}
```

- [ ] **Step 2: Use it in `MainExecutor`**

In `resolveStepsIfNeeded`: `inSteps = StepResolver.in(pipelineConfig); itemSteps = StepResolver.itemSteps(pipelineConfig);`. Delete `resolveOtherSteps` and `doResolveStep`; remove imports that become unused (`PluginEngine` only if nothing else uses it — check `grep -n PluginEngine MainExecutor.java`).

- [ ] **Step 3: Run the engine tests (the safety net)**

Run: `mvn -q -B -pl copybot-engine test`
Expected: PASS, no test changed.

- [ ] **Step 4: Commit**

```bash
git add -A copybot-engine
git commit -m "Extract StepResolver from MainExecutor (shared with the sampler)"
```

---

### Task 5: Dry run in "Prepare": projections, `Plan.projectionOf`, plan view

**Files:**
- Modify: `copybot-engine/src/main/java/com/copybot/engine/pipeline/WorkItemExecution.java` (projection field)
- Modify: `copybot-engine/src/main/java/com/copybot/engine/MainExecutor.java` (`prepare`: compute projections; keep the process steps list)
- Create: `copybot-engine/src/main/java/com/copybot/engine/TargetProjection.java`
- Modify: `copybot-engine/src/main/java/com/copybot/engine/Plan.java` (`targetOf` → `projectionOf`)
- Modify: `copybot-ui/src/main/java/com/copybot/ui/PlanController.java` (target column text + tooltip)
- Modify: `copybot-ui/src/main/java/com/copybot/ui/model/PlanViewModel.java` (`targetText(TargetProjection)`, `targetTooltip(TargetProjection)`)
- Modify: `uiBundle.properties`, `uiBundle_fr.properties` (`plan.target.more`), `UiBundleTest.KEYS`
- Test: `copybot-engine/src/test/java/com/copybot/engine/PrepareDryRunTest.java`, `copybot-ui/src/test/java/com/copybot/ui/model/PlanViewModelTest.java` (new cases)

**Interfaces:**
- Consumes: `DryRunner.project`, `Projection.*`, `DryRunner.actionName` (Task 3); `StepResolver` (Task 4).
- Produces:
  - `WorkItemExecution`: `public Projection getProjection()` (null when not computed), `public void setProjection(Projection projection)`.
  - `public sealed interface TargetProjection` (package `com.copybot.engine`): `Targets(List<Path> directories)`, `Filtered(String action)`, `Unknown(String action)`, `Failed(String message)`, `None()` — a singleton `TargetProjection.NONE = new None()`.
  - `Plan.projectionOf(WorkItemExecution item) → TargetProjection` (replaces `targetOf`; every caller adapted: `grep -rn "targetOf" copybot-*/src`).
  - `PlanViewModel.targetText(TargetProjection)` → `String`, `PlanViewModel.targetTooltip(TargetProjection)` → `String` (null when no tooltip).

- [ ] **Step 1: Write the failing engine tests**

Read `MainExecutorResumeTest` first: reuse its way of building a `MainExecutor` with pre-resolved steps and a `ResumeContext` (constructor `MainExecutor(inSteps, itemSteps, barrierIndex, false, null, registry(), new ResumeContext(ResumeMode.NONE...))` — check whether `ResumeMode.NONE` works with `prepare()`; if `prepare()` requires a mode other than NONE for the barrier, use `ResumeMode.STATE` with a temp `ResumeStateStore` like the existing tests). Item steps for these tests: `[conv (process, toJpg dry run, doProcess records the call), out]` with `barrierIndex = 0` (no analysis), where `out` is a fake `IOutAction` whose `resolveTarget(item)` returns `tempDir/out/<display name>` and throws `CopybotException.ofResource("write.pattern.missing-key", "{x}", name)` when the display name starts with `"bad"`. The in step lists `DSC_1.NEF` (and `bad.NEF` where needed) with a date (reuse `ControlFakes.DatedIn` if it fits, else a small fake `IInAction` emitting `WorkItem`s with `display.name` set).

Tests (class `PrepareDryRunTest`, package `com.copybot.engine`):
- `prepareProjectsTheProcessStepsWithoutRunningThem`: after `prepare()`, `plan.projectionOf(item)` is `Targets([tempDir/out])`… precisely: the directory of `tempDir/out/DSC_1.jpg` = `tempDir/out` absolute normalized; `doProcess` was never called.
- `aForkGivesOneTargetPerProducedItem`: a fork dry run (`DSC_1.jpg` + `thumbs/DSC_1.jpg` via `display.name = "thumbs/DSC_1.jpg"`) ⇒ `Targets` with two directories in order.
- `aFilteringStepShowsWhoFilteredIt` ⇒ `TargetProjection.Filtered("drop")`.
- `aStepWithoutDryRunMakesTheTargetUnknown` ⇒ `Unknown("legacy")`.
- `aFailingDryRunDoesNotFailTheItem` ⇒ `Failed(<message containing the exception message>)` and the item status is `PENDING`.
- `aMissingKeyShowsItsMessage` ⇒ for `bad.NEF`: `Failed` whose message contains `{x}`.
- `theExecutionStillRunsDoProcessOnTheRealItem`: `execute(null)` then the fake `doProcess` was called once with an item whose display name is still `DSC_1.NEF` (the dry run did not touch it).
- `withoutOutStepThereIsNoTarget` ⇒ `TargetProjection.NONE`.

- [ ] **Step 2: Run them to verify they fail**

Run: `mvn -q -B -pl copybot-engine test -Dtest=PrepareDryRunTest`
Expected: compilation failure (`projectionOf`, `TargetProjection`).

- [ ] **Step 3: Implement the engine part**

`WorkItemExecution`:
```java
    /** What the process steps would produce, by their dry run; set at the end of the preparation (spec pattern-helper §4.3). */
    private volatile Projection projection;

    public Projection getProjection() {
        return projection;
    }

    public void setProjection(Projection projection) {
        this.projection = projection;
    }
```
(`import com.copybot.engine.Projection;` — `WorkItemExecution` is in `com.copybot.engine.pipeline`, `Projection` in `com.copybot.engine`: allowed within the module.)

`MainExecutor`:
- new field `private List<PipelineStep<IProcessAction>> processSteps = List.of();` — set in `resolveStepsIfNeeded` from `StepResolver.process(pipelineConfig)` **without resolving twice**: derive it from `itemSteps` instead, so the test constructor (pre-resolved steps) works too:
```java
    /** The process steps among the item steps (the instances the execution will use). */
    @SuppressWarnings("unchecked")
    private List<PipelineStep<IProcessAction>> processSteps() {
        List<PipelineStep<IProcessAction>> steps = new ArrayList<>();
        for (PipelineStep<?> step : itemSteps) {
            if (step.getAction() instanceof IProcessAction) {
                steps.add((PipelineStep<IProcessAction>) step);
            }
        }
        return steps;
    }
```
- in `prepare()`, right after `resolver.apply(proposal.point(), proposal.source(), orderedItems);` and before `state.setStatus(PipelineStatus.PREPARED);`:
```java
            projectItems();
```
with
```java
    /**
     * The dry run of the process steps for every item prepared without error, selected or not, so that a manual
     * resume point chosen later needs nothing more (spec pattern-helper §4.3). A failing dry run is shown, never
     * an item error. The dry run of the same action instance as the execution: dryRun must not change its state.
     */
    private void projectItems() throws InterruptedException {
        List<PipelineStep<IProcessAction>> steps = processSteps();
        for (WorkItemExecution exec : orderedItems) {
            checkCancelled();
            if (exec.getStatus() != ItemStatus.ERROR) {
                exec.setProjection(DryRunner.project(exec.getWorkItem(), steps));
            }
        }
    }
```
Check `checkCancelled()`'s signature (it exists, used at the start of `prepare`); if it throws `InterruptedException` the `catch (InterruptedException e)` of `prepare` already handles it. If `orderedItems` excludes items without date, iterate `state.getWorkItems()` instead (read `ResumeResolver.order`: the spec says "every item without error").

`TargetProjection`:
```java
package com.copybot.engine;

import java.nio.file.Path;
import java.util.List;

/** Where the out step would write what an item becomes after the process steps (spec pattern-helper §4.3). */
public sealed interface TargetProjection {

    TargetProjection NONE = new None();

    /** The directories (absolute) of every produced item, in order. */
    record Targets(List<Path> directories) implements TargetProjection {
        public Targets {
            directories = List.copyOf(directories);
        }
    }

    record Filtered(String action) implements TargetProjection {
    }

    /** A process step does not support the dry run. */
    record Unknown(String action) implements TargetProjection {
    }

    /** A failed dry run, or a target that cannot be resolved (e.g. a missing pattern key): the message. */
    record Failed(String message) implements TargetProjection {
    }

    /** No out step, or it cannot tell. */
    record None() implements TargetProjection {
    }
}
```
`Plan.projectionOf` (replaces `targetOf`, keep its Javadoc spirit):
```java
    /**
     * Where the out step would write what this item becomes after the dry run of the process steps
     * ({@link IOutAction#resolveTarget}), without writing anything (spec pattern-helper §4.3).
     */
    public TargetProjection projectionOf(WorkItemExecution item) {
        IOutAction out = executor.findOutAction();
        if (out == null) {
            return TargetProjection.NONE;
        }
        Projection projection = item.getProjection();
        if (projection == null) { // not prepared (error before the barrier): the item as it is
            projection = new Projection.Projected(List.of(item.getWorkItem()));
        }
        return switch (projection) {
            case Projection.Filtered f -> new TargetProjection.Filtered(f.action());
            case Projection.Unsupported u -> new TargetProjection.Unknown(u.action());
            case Projection.Failed f -> new TargetProjection.Failed(ResourcesEngine.getString("dryrun.failed", f.action(), f.message()));
            case Projection.Projected p -> targets(out, p.items());
        };
    }

    private static TargetProjection targets(IOutAction out, List<WorkItem> items) {
        List<Path> directories = new ArrayList<>();
        for (WorkItem produced : items) {
            try {
                Optional<Path> target = out.resolveTarget(produced);
                if (target.isEmpty()) {
                    return TargetProjection.NONE;
                }
                // absolute, like the resume probe: a pattern without directory part writes to the current one
                directories.add(target.get().toAbsolutePath().normalize().getParent());
            } catch (RuntimeException e) {
                LOG.debug(e, "plan.target.failed", produced.getNameDisplay(), String.valueOf(e));
                return new TargetProjection.Failed(e.getMessage() != null ? e.getMessage() : e.getClass().getName());
            }
        }
        return new TargetProjection.Targets(directories);
    }
```
(imports: `com.copybot.resources.ResourcesEngine`, `com.copybot.plugin.api.action.WorkItem`, `java.util.ArrayList`.)

- [ ] **Step 4: Run the engine tests**

Run: `mvn -q -B -pl copybot-engine test`
Expected: PASS (fix any compile error from the removed `targetOf` in engine tests by switching them to `projectionOf`).

- [ ] **Step 5: Write the failing UI tests**

In `PlanViewModelTest` (read it first for its style):
```java
    @Test
    public void theTargetTextShowsTheFirstDirectoryAndHowManyMore() {
        TargetProjection two = new TargetProjection.Targets(List.of(Path.of("/nas/2026"), Path.of("/nas/thumbs")));

        assertEquals(Path.of("/nas/2026") + " " + ResourcesEngine.getString("plan.target.more", 1), PlanViewModel.targetText(two));
        assertEquals(Path.of("/nas/2026") + "\n" + Path.of("/nas/thumbs"), PlanViewModel.targetTooltip(two));
    }

    @Test
    public void theTargetTextExplainsWhyThereIsNoTarget() {
        assertEquals(ResourcesEngine.getString("dryrun.filtered", "drop"), PlanViewModel.targetText(new TargetProjection.Filtered("drop")));
        assertEquals(ResourcesEngine.getString("dryrun.unsupported", "legacy"), PlanViewModel.targetText(new TargetProjection.Unknown("legacy")));
        assertEquals("No value for {x}: a", PlanViewModel.targetText(new TargetProjection.Failed("No value for {x}: a")));
        assertEquals("", PlanViewModel.targetText(TargetProjection.NONE));
        assertNull(PlanViewModel.targetTooltip(TargetProjection.NONE));
    }

    @Test
    public void aSingleTargetHasNoTooltip() {
        assertEquals(Path.of("/nas/2026").toString(), PlanViewModel.targetText(new TargetProjection.Targets(List.of(Path.of("/nas/2026")))));
        assertNull(PlanViewModel.targetTooltip(new TargetProjection.Targets(List.of(Path.of("/nas/2026")))));
    }
```
Keys: EN `plan.target.more=(+{0})`, FR `plan.target.more=(+{0})`; add `"plan.target.more"` to `UiBundleTest.KEYS`.

- [ ] **Step 6: Implement the UI part**

`PlanViewModel`:
```java
    /** The target column (spec pattern-helper §4.3): the first directory and "(+N)", or why there is none. */
    public static String targetText(TargetProjection projection) {
        return switch (projection) {
            case TargetProjection.Targets t -> t.directories().isEmpty() ? ""
                    : t.directories().getFirst() + (t.directories().size() > 1
                    ? " " + ResourcesEngine.getString("plan.target.more", t.directories().size() - 1) : "");
            case TargetProjection.Filtered f -> ResourcesEngine.getString("dryrun.filtered", f.action());
            case TargetProjection.Unknown u -> ResourcesEngine.getString("dryrun.unsupported", u.action());
            case TargetProjection.Failed f -> f.message();
            case TargetProjection.None n -> "";
        };
    }

    /** Every directory, one per line, when there are several; null otherwise. */
    public static String targetTooltip(TargetProjection projection) {
        return projection instanceof TargetProjection.Targets t && t.directories().size() > 1
                ? t.directories().stream().map(Path::toString).collect(Collectors.joining("\n")) : null;
    }
```
`PlanController`: `targetText(item)` becomes `plan == null ? "" : PlanViewModel.targetText(plan.projectionOf(item))`; give `targetColumn` a cell factory that sets the tooltip:
```java
        targetColumn.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String text, boolean empty) {
                super.updateItem(text, empty);
                setText(empty ? null : text);
                WorkItemExecution item = empty || getTableRow() == null ? null : getTableRow().getItem();
                String tooltip = item == null || plan == null ? null : PlanViewModel.targetTooltip(plan.projectionOf(item));
                setTooltip(tooltip == null ? null : new Tooltip(tooltip));
            }
        });
```
(check `targetColumn`'s generic type in the FXML-injected field: `TableColumn<WorkItemExecution, String>`; imports `TableCell`, `Tooltip`.)

- [ ] **Step 7: Run the UI tests**

Run: `mvn -q -B -pl copybot-engine,copybot-ui install -DskipTests` then `mvn -q -B -pl copybot-ui test`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add -A copybot-engine copybot-ui
git commit -m "Prepare runs the dry run of the process steps; the plan shows the projected targets"
```

---

### Task 6: `PipelineSampler` and `SampleSession`

**Files:**
- Create: `copybot-engine/src/main/java/com/copybot/engine/sample/PipelineSampler.java`
- Create: `copybot-engine/src/main/java/com/copybot/engine/sample/SampleSession.java`
- Create: `copybot-engine/src/main/java/com/copybot/engine/sample/Sample.java`
- Create: `copybot-engine/src/main/java/com/copybot/engine/sample/SampleItem.java`
- Create: `copybot-engine/src/main/java/com/copybot/engine/sample/SampleSteps.java`
- Modify: `copybot-engine/src/main/java/module-info.java` (`exports com.copybot.engine.sample;` — check how `com.copybot.engine` is exported to `copybot-ui`, mirror it)
- Modify: engine bundles: `sample.cancelled`, `sample.analysis-failed`, `sample.filtered`, `sample.unsupported`, `sample.dryrun-failed`; `SafeWriteBundleTest.KEYS`
- Test: `copybot-engine/src/test/java/com/copybot/engine/sample/SampleSessionTest.java`

**Interfaces:**
- Consumes: `StepResolver.in/analyse/process` (Task 4), `DryRunner.project`, `Projection` (Task 3), `WorkItem.copyForDryRun` (Task 3).
- Produces:
  - `public interface SampleSteps { List<PipelineStep<IInAction>> in(PipelineConfig c); List<PipelineStep<IAnalyzeAction>> analyse(PipelineConfig c); List<PipelineStep<IProcessAction>> process(PipelineConfig c); SampleSteps PLUGINS = <delegates to StepResolver>; }`
  - `public final class PipelineSampler` with constants `public static final int MAX_LISTED = 200; public static final Duration LISTING_TIMEOUT = Duration.ofSeconds(2); public static final int MAX_KEPT = 10;` and `public static SampleSession open()` (= `new SampleSession(SampleSteps.PLUGINS, MAX_LISTED, LISTING_TIMEOUT, MAX_KEPT)`).
  - `public final class SampleSession` with public constructor `SampleSession(SampleSteps steps, int maxListed, Duration listingTimeout, int maxKept)`, `public Sample list(PipelineConfig config)` (lists, selects, then returns `analyse(config)`'s result — i.e. list + analyse), `public Sample analyse(PipelineConfig config)` (re-analyses the kept items; before any `list`, returns a failed sample "nothing listed"), `public void cancel()`. Both never throw: failures go to `Sample.failure`.
  - `public record Sample(List<SampleItem> items, int listed, boolean truncated, List<String> notes, Optional<String> failure)` with `static Sample failed(String message)`.
  - `public record SampleItem(String sourceName, String name, Map<String, String> display, Optional<String> error)` (`display` = `Map.copyOf` of non-null entries).

- [ ] **Step 1: Message keys**

EN:
```properties
sample.cancelled=Sample cancelled
sample.not-listed=No sample listed yet
sample.analysis-failed={0}: analysis {1} failed: {2}
sample.filtered={0}: filtered out by {1}
sample.unsupported={0}: {1} does not support the dry run (keys and names before this step)
sample.dryrun-failed={0}: dry run of {1} failed: {2}
```
FR:
```properties
sample.cancelled=\u00c9chantillon annul\u00e9
sample.not-listed=Aucun \u00e9chantillon list\u00e9
sample.analysis-failed={0} : l''analyse {1} a \u00e9chou\u00e9 : {2}
sample.filtered={0} : filtr\u00e9 par {1}
sample.unsupported={0} : {1} ne g\u00e8re pas le dry run (cl\u00e9s et noms d''avant cette \u00e9tape)
sample.dryrun-failed={0} : \u00e9chec du dry run de {1} : {2}
```
Add the six keys to `SafeWriteBundleTest.KEYS`.

- [ ] **Step 2: Write the failing tests**

`SampleSessionTest` (package `com.copybot.engine.sample`): fakes implement `IInAction` / `IAnalyzeAction` / `IProcessAction` with the minimal `IAction` methods (read `copybot-engine/src/main/java/com/copybot/plugin/api/action/IAction.java` for what must be implemented; `ControlFakes.FakeAction` is in another package and package-private: write a local `abstract static class Fake implements IAction` with the same stubs). A `SampleSteps` built from lambdas returns pre-built `PipelineStep`s (`new PipelineStep<>(null, action, new PipelineStepConfig(null, "<code>", null, null, null, null, null, null))`), and counts how many times `in(...)` was called. Items are `new WorkItem(Path)` on files created in `@TempDir` (names given per test) with `display.name` set to the file name. The session under test: `new SampleSession(steps, 200, Duration.ofSeconds(2), 10)` unless stated; `PipelineConfig` argument: `new PipelineConfig(List.of(), List.of(), List.of(), null, null, null)` (the fake `SampleSteps` ignores it).

Tests:
- `listingStopsAtTheCapAndKeepsTen`: an in action that emits 1 000 `.JPG` items; result: `listed == 200`, `truncated`, `items.size() == 10`, and the in action stopped emitting (its counter ≤ 201).
- `theKeptItemsAlternateTheExtensions`: emits `a.JPG, b.JPG, c.JPG, d.NEF, e.MP4, f.NEF, g` (no extension) with `maxKept = 5`; expected `sourceName`s in order: `a.JPG, d.NEF, e.MP4, g, b.JPG`.
- `aListingThatSwallowsTheStopEndsAtTheDeadline`: an in action that emits 3 items, each `consumer.accept` wrapped in `try { … } catch (RuntimeException ignored) {}`, then loops `while (true) { Thread.sleep(10); }` catching `InterruptedException` to return; session with `maxListed = 2`, timeout 300 ms; assert the call returns in < 2 s, `listed == 2` (the third accept is refused once the cap is reached: count only accepted items), `truncated`.
- `aSlowListingIsCutAtTheTimeout`: emits one item then sleeps 5 s (interruptibly): timeout 200 ms ⇒ returns in < 2 s with 1 item, `truncated`.
- `aFailingListingIsAGlobalFailure`: in action throws `IllegalStateException("no card")` ⇒ `failure` present containing `no card`, `items` empty.
- `analysesRunOnTheKeptItems`: an analyse sets `display.captureDate.Y = "2026"` ⇒ every `SampleItem.display` has it.
- `aFailingAnalysisIsNotedForThatItemOnly`: the analyse throws for `b.JPG` only ⇒ `b.JPG`'s `SampleItem.error` present, others not; `notes` contains the `sample.analysis-failed` text for `b.JPG`.
- `analyseReusesTheListedItemsWithoutListingAgain`: `list`, then change the analyse fake's behaviour (a mutable field), `analyse` ⇒ new value visible, `in(...)` called once in total, and the values set by the first analysis are **not** in the second result (fresh copies).
- `processStepsRunAsDryRun`: a process whose `dryRun` forks into `x.jpg` + `thumb_x.jpg` ⇒ two `SampleItem`s with `sourceName` `x.NEF`, names `x.jpg` and `thumb_x.jpg` (`name` = `display.get("name")`, falling back to `getNameDisplay()`); a filtering process ⇒ no `SampleItem` for it and a `sample.filtered` note; a process without dry run ⇒ the items before it, and a `sample.unsupported` note.
- `analyseBeforeListIsAFailure` ⇒ `failure` = `sample.not-listed` text.
- `cancelEndsTheOperationWithACancelledSample`: an in action blocking on a latch (interruptibly); call `list` on another thread, then `cancel()` ⇒ the returned sample's `failure` is the `sample.cancelled` text, returned within 2 s.

- [ ] **Step 3: Run them to verify they fail**

Run: `mvn -q -B -pl copybot-engine test -Dtest=SampleSessionTest`
Expected: compilation failure.

- [ ] **Step 4: Implement**

`SampleItem`, `Sample`, `SampleSteps`, `PipelineSampler` per the Interfaces block (records with compact constructors copying lists/maps; `Sample.failed(message)` = `new Sample(List.of(), 0, false, List.of(), Optional.of(message))`; `SampleSteps.PLUGINS` = an anonymous implementation calling `StepResolver.in/analyse/process`).

`SampleSession` — key parts:
```java
/**
 * A sample of a pipeline being edited (spec pattern-helper §3): list once, analyse as often as the analyse or
 * process steps change. Not thread-safe except {@link #cancel()}; its operations block and are meant for one
 * background thread. Never throws: failures are in {@link Sample#failure()}.
 */
public final class SampleSession {

    /** Thrown from the listing consumer to stop it once the cap is reached. */
    private static final class StopSampling extends RuntimeException {
        StopSampling() {
            super(null, null, false, false);
        }
    }

    private final SampleSteps steps;
    private final int maxListed;
    private final Duration listingTimeout;
    private final int maxKept;
    private volatile Thread worker;          // the thread running list/analyse, interrupted by cancel()
    private volatile boolean cancelled;
    private List<WorkItem> kept;             // pristine copies of the kept items, null before list()
    private int listed;
    private boolean truncated;

    public SampleSession(SampleSteps steps, int maxListed, Duration listingTimeout, int maxKept) { ... }

    public Sample list(PipelineConfig config) {
        begin();
        try {
            List<WorkItem> received = Collections.synchronizedList(new ArrayList<>());
            AtomicBoolean full = new AtomicBoolean();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            List<PipelineStep<IInAction>> inSteps = steps.in(config); // resolution failure: caught below
            Thread lister = Thread.ofPlatform().daemon().name("copybot-sample-listing").start(() -> {
                try {
                    for (PipelineStep<IInAction> step : inSteps) {
                        step.getAction().listFiles(item -> {
                            synchronized (received) {
                                if (received.size() >= maxListed) {
                                    full.set(true);
                                    throw new StopSampling();
                                }
                                received.add(item);
                            }
                        });
                        if (full.get()) {
                            return;
                        }
                    }
                } catch (StopSampling e) {
                    // the cap is reached: normal end
                } catch (Throwable t) {
                    if (!full.get() && !Thread.currentThread().isInterrupted()) {
                        failure.set(t);
                    }
                }
            });
            lister.join(listingTimeout.toMillis());
            boolean timedOut = lister.isAlive();
            if (timedOut) {
                lister.interrupt(); // not waited for: a daemon, it ends when the plugin gives up
            }
            if (cancelled) {
                return Sample.failed(ResourcesEngine.getString("sample.cancelled"));
            }
            if (failure.get() != null) {
                return Sample.failed(message(failure.get()));
            }
            List<WorkItem> all;
            synchronized (received) {
                all = List.copyOf(received);
            }
            listed = all.size();
            truncated = timedOut || full.get();
            kept = select(all, maxKept).stream().map(WorkItem::copyForDryRun).toList();
            return analyseKept(config);
        } catch (InterruptedException e) {
            return Sample.failed(ResourcesEngine.getString("sample.cancelled"));
        } catch (RuntimeException e) {
            return Sample.failed(message(e));
        } finally {
            end();
        }
    }
```
`full` must also be set when an accept is refused (it is, before the throw), so a plugin swallowing `StopSampling` still cannot add items; the loop over steps ends at the next step boundary or at the timeout.

`select` (static, package-private for the test of ordering if wanted):
```java
    /** At most max items, alternating the extensions in their order of first appearance (spec §3.2). */
    static List<WorkItem> select(List<WorkItem> items, int max) {
        LinkedHashMap<String, Deque<WorkItem>> byExtension = new LinkedHashMap<>();
        for (WorkItem item : items) {
            byExtension.computeIfAbsent(extension(name(item)), e -> new ArrayDeque<>()).add(item);
        }
        List<WorkItem> selected = new ArrayList<>();
        while (selected.size() < max && !byExtension.isEmpty()) {
            Iterator<Deque<WorkItem>> it = byExtension.values().iterator();
            while (it.hasNext() && selected.size() < max) {
                Deque<WorkItem> queue = it.next();
                selected.add(queue.poll());
                if (queue.isEmpty()) {
                    it.remove();
                }
            }
        }
        return selected;
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** display.name, else the name of the source. */
    static String name(WorkItem item) {
        String name = item.getMetadatas().display().get("name");
        return name != null ? name : item.getNameDisplay() != null ? item.getNameDisplay() : item.getSourceLocationDisplay();
    }
```
`analyse(config)`: `begin()`; if `kept == null` ⇒ `Sample.failed(sample.not-listed)`; else `analyseKept(config)`; `finally end()`.

`analyseKept(config)`:
```java
    private Sample analyseKept(PipelineConfig config) throws InterruptedException {
        List<PipelineStep<IAnalyzeAction>> analyses = steps.analyse(config);
        List<PipelineStep<IProcessAction>> processes = steps.process(config);
        List<SampleItem> items = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (WorkItem pristine : kept) {
            if (cancelled || Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            WorkItem item = pristine.copyForDryRun();
            String source = name(item);
            String error = null;
            for (PipelineStep<IAnalyzeAction> step : analyses) {
                try {
                    step.getAction().doAnalyze(item);
                } catch (RuntimeException e) {
                    error = ResourcesEngine.getString("sample.analysis-failed", source, DryRunner.actionName(step), message(e));
                    break;
                }
            }
            if (error != null) {
                notes.add(error);
                items.add(SampleItem.of(source, item, Optional.of(error)));
                continue;
            }
            switch (DryRunner.project(item, processes)) {
                case Projection.Projected p -> p.items().forEach(produced -> items.add(SampleItem.of(source, produced, Optional.empty())));
                case Projection.Filtered f -> notes.add(ResourcesEngine.getString("sample.filtered", source, f.action()));
                case Projection.Unsupported u -> {
                    notes.add(ResourcesEngine.getString("sample.unsupported", source, u.action()));
                    u.before().forEach(produced -> items.add(SampleItem.of(source, produced, Optional.empty())));
                }
                case Projection.Failed f -> {
                    String message = ResourcesEngine.getString("sample.dryrun-failed", source, f.action(), f.message());
                    notes.add(message);
                    items.add(SampleItem.of(source, item, Optional.of(message)));
                }
            }
        }
        return new Sample(items, listed, truncated, notes, Optional.empty());
    }
```
with `SampleItem.of(String source, WorkItem item, Optional<String> error)` = `new SampleItem(source, SampleSession.name(item), item.getMetadatas().display(), error)` (make `name` accessible: put a static `name(WorkItem)` helper in `SampleItem` instead and call it from `SampleSession`). `steps.analyse/process` resolution failures are `RuntimeException`s caught by the callers (`list`/`analyse`) ⇒ global failure.

`begin()` / `end()` / `cancel()`:
```java
    private void begin() {
        cancelled = false;
        worker = Thread.currentThread();
    }

    private void end() {
        worker = null;
        Thread.interrupted(); // a cancel() racing with the end must not leak to the caller's next blocking call
    }

    /** Interrupts the current operation: it returns a "cancelled" sample. Any thread. */
    public void cancel() {
        cancelled = true;
        Thread current = worker;
        if (current != null) {
            current.interrupt();
        }
    }
```
`message(Throwable t)` = `t.getMessage() != null ? t.getMessage() : t.getClass().getName()`. In `list`, the `cancelled` check after `join` returns the cancelled sample; `join` itself throws `InterruptedException` on `cancel()` ⇒ caught ⇒ cancelled sample (also interrupt `lister` in that path: wrap with `finally { if (lister.isAlive()) lister.interrupt(); }` — restructure so `lister` is declared before the `try`).

- [ ] **Step 5: Run the tests**

Run: `mvn -q -B -pl copybot-engine test -Dtest=SampleSessionTest+SafeWriteBundleTest`
Expected: PASS. Then the whole engine: `mvn -q -B -pl copybot-engine test`.

- [ ] **Step 6: Commit**

```bash
git add -A copybot-engine
git commit -m "Add PipelineSampler: a capped sample of the edited pipeline, analysed and dry run"
```

---

### Task 7: `PatternHelperModel` (UI, JavaFX-free)

**Files:**
- Create: `copybot-ui/src/main/java/com/copybot/ui/model/PatternHelperModel.java`
- Modify: `uiBundle.properties`, `uiBundle_fr.properties`, `UiBundleTest.KEYS`
- Test: `copybot-ui/src/test/java/com/copybot/ui/model/PatternHelperModelTest.java`

**Interfaces:**
- Consumes: `OutPattern` (Task 1), `Sample`, `SampleItem` (Task 6).
- Produces (all in `PatternHelperModel`):
  - `public enum Rerun { NONE, LIST, ANALYSE }` and `public static Rerun rerun(String sampledIn, String sampledProcessing, String currentIn, String currentProcessing)` — `sampledIn == null` (never sampled) ⇒ `LIST`; in differs ⇒ `LIST`; processing differs ⇒ `ANALYSE`; else `NONE`. The strings are the JSON of `inSteps` and of `analyseSteps`+`actionSteps` (built by the editor).
  - `public record KeyRow(String key, List<String> examples, int present, int total)` with `boolean partial()` = `present < total`; `public static List<KeyRow> keys(Sample sample)` — over the items without error: every display key, up to 3 distinct examples in item order, presence counted on non-blank values; sorted: complete first, then partial, each alphabetically.
  - `public record PreviewRow(String name, String path, List<String> missing, String effect, String error)`; `public record Preview(Optional<String> syntaxError, List<PreviewRow> rows)`; `public static Preview preview(String pattern, String onMissingKey, Sample sample)` — invalid pattern ⇒ `syntaxError` = the exception message, no rows; else one row per `SampleItem`: an item with `error` ⇒ `PreviewRow(name, null, List.of(), null, error)`; else `path` = `resolution.text()`, `missing` = `resolution.missing()`, `effect` = `null` when complete, else the localized text for `onMissingKey` (`null` or unknown value ⇒ treated as `error`).
  - `public static String insert(String text, int caret, String key)` → the text with `{key}` inserted at `caret` (clamped to `[0, text.length()]`).
  - `public static String status(Sample sample)` — `sample.failure()` present ⇒ that message; else `helper.sample` with (kept items count = number of distinct `sourceName`s, `listed`) plus `helper.truncated` appended when `truncated`.

- [ ] **Step 1: Keys**

EN:
```properties
helper.loading=Reading the sample…
helper.cancel=Cancel
helper.retry=Retry
helper.sample=Sample: {0} files out of {1} listed
helper.truncated=(listing cut)
helper.keys=Available keys (click to insert)
helper.preview=Preview
helper.effect.error=→ item in error
helper.effect.skip=→ skipped
helper.effect.literal=→ kept as written
```
FR (UTF-8):
```properties
helper.loading=Lecture de l'échantillon…
helper.cancel=Annuler
helper.retry=Réessayer
helper.sample=Échantillon : {0} fichiers sur {1} listés
helper.truncated=(listage coupé)
helper.keys=Clés disponibles (clic pour insérer)
helper.preview=Aperçu
helper.effect.error=→ élément en erreur
helper.effect.skip=→ ignoré
helper.effect.literal=→ laissé tel quel
```
(`helper.loading` has no argument: a single quote stays single.) Add all ten keys to `UiBundleTest.KEYS`.

- [ ] **Step 2: Write the failing tests**

```java
package com.copybot.ui.model;

import com.copybot.engine.sample.Sample;
import com.copybot.engine.sample.SampleItem;
import com.copybot.resources.ResourcesEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class PatternHelperModelTest {

    private static SampleItem item(String name, Map<String, String> display) {
        return new SampleItem(name, name, display, Optional.empty());
    }

    private static final Sample SAMPLE = new Sample(List.of(
            item("a.JPG", Map.of("name", "a.JPG", "captureDate.Y", "2026")),
            item("b.JPG", Map.of("name", "b.JPG", "captureDate.Y", "2025")),
            item("c.MP4", Map.of("name", "c.MP4", "captureDate.Y", " "))),
            150, true, List.of(), Optional.empty());

    @Test
    public void neverSampledOrNewInputMeansList() {
        assertEquals(PatternHelperModel.Rerun.LIST, PatternHelperModel.rerun(null, null, "[in]", "[a]"));
        assertEquals(PatternHelperModel.Rerun.LIST, PatternHelperModel.rerun("[in]", "[a]", "[in2]", "[a]"));
    }

    @Test
    public void otherAnalysesMeanAnalyseOnlyAndNothingChangedMeansNone() {
        assertEquals(PatternHelperModel.Rerun.ANALYSE, PatternHelperModel.rerun("[in]", "[a]", "[in]", "[a2]"));
        assertEquals(PatternHelperModel.Rerun.NONE, PatternHelperModel.rerun("[in]", "[a]", "[in]", "[a]"));
    }

    @Test
    public void keysAreCompleteFirstThenPartialWithExamplesAndPresence() {
        List<PatternHelperModel.KeyRow> keys = PatternHelperModel.keys(SAMPLE);

        assertEquals(List.of("name", "captureDate.Y"), keys.stream().map(PatternHelperModel.KeyRow::key).toList());
        assertEquals(List.of("a.JPG", "b.JPG", "c.MP4"), keys.get(0).examples());
        assertEquals(new PatternHelperModel.KeyRow("captureDate.Y", List.of("2026", "2025"), 2, 3), keys.get(1));
        assertTrue(keys.get(1).partial());
    }

    @Test
    public void itemsInErrorAreLeftOutOfTheKeys() {
        Sample sample = new Sample(List.of(item("a.JPG", Map.of("name", "a.JPG")),
                new SampleItem("b.JPG", "b.JPG", Map.of("name", "b.JPG", "x", "1"), Optional.of("boom"))),
                2, false, List.of(), Optional.empty());

        assertEquals(List.of(new PatternHelperModel.KeyRow("name", List.of("a.JPG"), 1, 1)), PatternHelperModel.keys(sample));
    }

    @Test
    public void thePreviewResolvesEveryItemAndShowsTheEffectOfAMissingKey() {
        PatternHelperModel.Preview preview = PatternHelperModel.preview("/nas/{captureDate.Y}/{name}", "skip", SAMPLE);

        assertTrue(preview.syntaxError().isEmpty());
        assertEquals("/nas/2026/a.JPG", preview.rows().get(0).path());
        assertNull(preview.rows().get(0).effect());
        PatternHelperModel.PreviewRow video = preview.rows().get(2);
        assertEquals(List.of("{captureDate.Y}"), video.missing());
        assertEquals(ResourcesEngine.getString("helper.effect.skip"), video.effect());
    }

    @Test
    public void anUnknownOrAbsentPolicyIsTheDefaultError() {
        assertEquals(ResourcesEngine.getString("helper.effect.error"),
                PatternHelperModel.preview("{captureDate.Y}", null, SAMPLE).rows().get(2).effect());
    }

    @Test
    public void anInvalidPatternGivesTheSyntaxErrorAndNoRows() {
        PatternHelperModel.Preview preview = PatternHelperModel.preview("/nas/{captureDate.Y|", "error", SAMPLE);

        assertTrue(preview.syntaxError().orElseThrow().contains("/nas/{captureDate.Y|"));
        assertTrue(preview.rows().isEmpty());
    }

    @Test
    public void anItemInErrorShowsItsError() {
        Sample sample = new Sample(List.of(new SampleItem("b.JPG", "b.JPG", Map.of(), Optional.of("boom"))),
                1, false, List.of(), Optional.empty());

        assertEquals("boom", PatternHelperModel.preview("{name}", "error", sample).rows().getFirst().error());
    }

    @Test
    public void insertPutsTheKeyAtTheCaret() {
        assertEquals("/nas/{name}/x", PatternHelperModel.insert("/nas//x", 5, "name"));
        assertEquals("{name}", PatternHelperModel.insert("", 3, "name"));
    }

    @Test
    public void theStatusCountsTheSourceFilesAndTellsWhenTheListingWasCut() {
        assertEquals(ResourcesEngine.getString("helper.sample", 3, 150) + " " + ResourcesEngine.getString("helper.truncated"),
                PatternHelperModel.status(SAMPLE));
        assertEquals("no card", PatternHelperModel.status(Sample.failed("no card")));
    }
}
```

- [ ] **Step 3: Run them to verify they fail**

Run: `mvn -q -B -pl copybot-engine,copybot-ui install -DskipTests` then `mvn -q -B -pl copybot-ui test -Dtest=PatternHelperModelTest`
Expected: compilation failure.

- [ ] **Step 4: Implement `PatternHelperModel`**

A final class with a private constructor and the static members of the Interfaces block. Notes:
- `keys`: iterate items without error; `LinkedHashMap<String, LinkedHashSet<String>> examples` (add while `< 3`, non-blank values only), `Map<String, Integer> present` (non-blank); `total` = number of items without error; build rows, sort with `Comparator.comparing(KeyRow::partial).thenComparing(KeyRow::key)` (`false` < `true`).
- `preview`: `try { pattern = OutPattern.parse(text); } catch (CopybotException e) { return new Preview(Optional.of(e.getMessage()), List.of()); }`; effect key = `"helper.effect." + ("skip".equals(p) || "literal".equals(p) ? p : "error")`.
- `status`: kept count = `sample.items().stream().map(SampleItem::sourceName).distinct().count()` (as `int`).

- [ ] **Step 5: Run the tests**

Run: `mvn -q -B -pl copybot-ui test -Dtest=PatternHelperModelTest+UiBundleTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A copybot-ui
git commit -m "Add PatternHelperModel: rerun decision, sample keys and pattern preview"
```

---

### Task 8: The editor's pattern helper (JavaFX) and its wiring

**Files:**
- Create: `copybot-ui/src/main/java/com/copybot/ui/PatternHelper.java`
- Modify: `copybot-ui/src/main/java/com/copybot/ui/ConfigForm.java:121-131` (PATTERN field: the helper instead of the fixed variables line; `Access.patternHelper`)
- Modify: `copybot-ui/src/main/java/com/copybot/ui/EditorController.java` (owns the session, the background thread, the reruns, the close)
- Modify: `copybot-ui/src/main/java/com/copybot/ui/model/PipelineDocument.java` (JSON accessors for the sample, if missing)
- Modify: `docs/superpowers/specs/2026-09-30-desktop-ui-design.md` (§4 known variables line ⇒ pointer to the helper)

**Interfaces:**
- Consumes: `PatternHelperModel` (Task 7), `PipelineSampler.open()`, `SampleSession`, `Sample` (Task 6), `ConfigSchema.PATTERN_VARIABLES` (fallback).
- Produces:
  - `ConfigForm.Access`: `default Node patternHelper(ConfigField field, TextInputControl input) { return null; }` — `ConfigForm` calls it for a `PATTERN` field with the field's text control; `null` ⇒ the current fixed variables line (kept for the advanced form and any other caller).
  - `final class PatternHelper extends VBox` with `PatternHelper(TextInputControl input, Supplier<String> onMissingKey, Runnable retry, Runnable cancel)`, `void showLoading()`, `void showSample(Sample sample)`, `void refresh()` (recomputes keys and preview from the input's current text and the last sample); it listens to `input.textProperty()` to call `refresh()`.
  - `PipelineDocument`: `public String samplingInJson()` (JSON of `inSteps`, `""` when absent), `public String samplingProcessingJson()` (JSON of `analyseSteps` + `actionSteps`), `public PipelineConfig samplingConfig()` (the document parsed as `PipelineConfig` with `GsonUtil.getGson().fromJson(<the document's JsonObject>, PipelineConfig.class)`; a parse failure ⇒ `IllegalArgumentException` with the message).

- [ ] **Step 1: `PipelineDocument` accessors and their tests**

Read `PipelineDocument` (root `JsonObject`, how sections are named: `inSteps`, `analyseSteps`, `actionSteps`, `outStep`). Add the three methods above and tests in `PipelineDocumentTest`:
```java
    @Test
    public void theSamplingJsonFollowsTheInputAndTheProcessingSteps() {
        PipelineDocument document = PipelineDocument.parse("{\"inSteps\":[{\"action\":\"file.read\"}],\"analyseSteps\":[],\"outStep\":{\"action\":\"file.write\"}}");
        String in = document.samplingInJson();
        String processing = document.samplingProcessingJson();

        document.setConfigText(/* change only the out step: use the existing test helpers of this class to edit a field of outStep */);

        assertEquals(in, document.samplingInJson());
        assertEquals(processing, document.samplingProcessingJson());
    }
```
Adapt the test to the real API of `PipelineDocument` (factory method name, how a field is edited in existing tests — copy one of their setups), keeping the intent: editing the out step changes neither JSON; editing an `inSteps` field changes `samplingInJson()`; adding an analyse step changes `samplingProcessingJson()` only. Also: `samplingConfig()` of a document with one `file.read` step returns a `PipelineConfig` whose `inSteps().size() == 1`.

Run: `mvn -q -B -pl copybot-ui test -Dtest=PipelineDocumentTest` ⇒ FAIL then implement ⇒ PASS.

- [ ] **Step 2: `PatternHelper` component**

Layout (VBox, spacing 4, all texts from `uiBundle`):
1. status row: `Label status` + `ProgressIndicator` (small, visible while loading) + `Button cancel` (`helper.cancel`, while loading) + `Button retry` (`helper.retry`, visible on failure);
2. `VBox notes` (one small `Label` per note, wrap text);
3. `Label` `helper.keys` + `FlowPane keys`: one `Hyperlink` per `KeyRow`, text `"{key}"`, tooltip = examples joined by `" · "` + `"  (" + present + "/" + total + ")"`, style orange (`-fx-text-fill: #c87f0a;`) when `partial()`; on action: `int caret = input.getCaretPosition(); input.setText(PatternHelperModel.insert(input.getText(), caret, key)); input.positionCaret(caret + key.length() + 2); input.requestFocus();`;
4. `Label syntaxError` (red `#d9534f`, hidden when none);
5. `Label` `helper.preview` + `VBox preview`: one row per `PreviewRow`: `TextFlow` with `Text(name + " → ")` then the path where each `missing` expression is a red `Text` (split `path` on the occurrences of the missing expressions, in order) then, if `effect != null`, a `Text(" " + effect)` in red; a row with `error` shows `name + " : " + error` in red.
On failure: status = message, `keys` shows the fixed list (`ConfigSchema.PATTERN_VARIABLES` as non-clickable `Label`s, like before), preview hidden.

`showLoading()`: status `helper.loading`, indicator and cancel visible, keys/preview kept as they were (or empty on first load). `showSample(sample)`: store it, hide indicator/cancel, `retry` visible iff failure, fill notes, then `refresh()`. `refresh()`: if no sample or failed ⇒ only the fixed list; else keys from `PatternHelperModel.keys(sample)` and preview from `PatternHelperModel.preview(input.getText(), onMissingKey.get(), sample)`.

No unit test (JavaFX); the logic is in `PatternHelperModel`.

- [ ] **Step 3: `ConfigForm` hook**

In `field(...)` (the block at lines 121-131), keep a reference to the control created by `input(field)` when it is a `TextInputControl`; for a `PATTERN` field:
```java
        if (field.hasHint(FieldHint.PATTERN)) {
            Node helper = control instanceof TextInputControl text ? access.patternHelper(field, text) : null;
            if (helper != null) {
                box.getChildren().add(helper);
            } else {
                String variables = ConfigSchema.PATTERN_VARIABLES.stream().map(v -> "{" + v + "}").collect(Collectors.joining(" "));
                box.getChildren().add(small(ResourcesEngine.getString("editor.pattern-variables", variables)));
            }
        }
```
If `input(field)` wraps the text field in an `HBox` (browse button for paths), get the `TextInputControl` the same way the method builds it — read `input(...)` and add a small private helper `textControl(Node)` that returns the field itself or the first `TextInputControl` child of an `HBox`.

- [ ] **Step 4: `EditorController` wiring**

Fields:
```java
    /** The sample of the edited pipeline (spec pattern-helper §5), opened on the first out form shown. */
    private SampleSession sampleSession;
    private Sample sample;
    /** The JSON the sample was taken from, null before the first one. */
    private String sampledIn;
    private String sampledProcessing;
    private boolean sampling;
    /** Set when the window closes: late background callbacks must not touch it. */
    private boolean closed;
    private PatternHelper patternHelper;
```
- In `configAccess(step, action)`: override `patternHelper(field, input)` **only when the step is the out step** (`node.section() == Section.OUT` — pass the node to `configAccess` or check `document.steps(Section.OUT).contains(step)`): create `patternHelper = new PatternHelper(input, () -> document.configText(step, onMissingKeyField(action)), this::resample, this::cancelSample)`, show the current `sample` if any (`patternHelper.showSample(sample)`), then call `ensureSample()`; return it. `onMissingKeyField(action)`: the `ConfigField` named `onMissingKey` from `action.configSchema()` (`schema.field("onMissingKey")`), or `null` ⇒ supplier returns `null`. Also refresh the helper when the `onMissingKey` combo changes: simplest — in `setText` of `configAccess`, after `document.setConfigText(...)`, `if (patternHelper != null) patternHelper.refresh();`.
- `ensureSample()`:
```java
    /** Starts a sample when none matches the steps being edited (spec pattern-helper §5); a no-op while one runs. */
    private void ensureSample() {
        if (sampling) {
            return;
        }
        String in = document.samplingInJson();
        String processing = document.samplingProcessingJson();
        PatternHelperModel.Rerun rerun = PatternHelperModel.rerun(sampledIn, sampledProcessing, in, processing);
        if (rerun == PatternHelperModel.Rerun.NONE) {
            return;
        }
        PipelineConfig config;
        try {
            config = document.samplingConfig();
        } catch (IllegalArgumentException e) {
            showSampleResult(Sample.failed(e.getMessage()), in, processing);
            return;
        }
        if (sampleSession == null) {
            sampleSession = PipelineSampler.open();
        }
        SampleSession session = sampleSession;
        sampling = true;
        patternHelper.showLoading();
        background(() -> rerun == PatternHelperModel.Rerun.LIST ? session.list(config) : session.analyse(config),
                result -> {
                    sampling = false;
                    if (!closed) {
                        showSampleResult(result, in, processing);
                    }
                },
                e -> {
                    sampling = false;
                    if (!closed) {
                        showSampleResult(Sample.failed(String.valueOf(e.getMessage())), in, processing);
                    }
                });
    }

    private void showSampleResult(Sample result, String in, String processing) {
        sample = result;
        // a failed sample is not remembered as taken: the next display of the out form tries again
        sampledIn = result.failure().isPresent() ? null : in;
        sampledProcessing = result.failure().isPresent() ? null : processing;
        if (patternHelper != null) {
            patternHelper.showSample(result);
        }
    }

    private void resample() {
        sampledIn = null;
        ensureSample();
    }

    private void cancelSample() {
        if (sampleSession != null) {
            sampleSession.cancel();
        }
    }
```
A failed sample is retried at the next display of the out form; note: a cancelled one too (by design: "Réessayer" or coming back to the form restarts it).
- `showForm(node)`: at its start set `patternHelper = null;` (the old one belongs to the replaced form); the out form's `configAccess` creates a new one and `ensureSample()` runs there, which covers "re-evaluated at each display of the out form".
- Close: in `stage.setOnCloseRequest`, when the close is accepted (not consumed), and in `save` success before `stage.close()`: `closed = true; cancelSample();`. Simplest: `stage.setOnHidden(e -> { closed = true; cancelSample(); });` in `init` (covers both paths).
- `background(...)` already exists (static, daemon thread, JavaFX callbacks): reuse it.

- [ ] **Step 5: Build and run all UI tests**

Run: `mvn -q -B -pl copybot-engine,copybot-ui install -DskipTests` then `mvn -q -B -pl copybot-ui test`
Expected: PASS.

- [ ] **Step 6: Spec touch-up and commit**

In `docs/superpowers/specs/2026-09-30-desktop-ui-design.md`, replace the sentence about the editor showing the known variables (`name`, `size`, dates × `.Y .y .m .D`) by: « l'éditeur affiche une aide construite sur un échantillon réel du pipeline (spec pattern-helper, 2026-10-02), la liste fixe restant le secours quand l'échantillon échoue. »

```bash
git add -A copybot-ui docs/superpowers/specs/2026-09-30-desktop-ui-design.md
git commit -m "Editor: pattern helper with sample keys and live preview of the output path"
```

---

### Task 9: Final verification

**Files:** none new (fixes only if the full build reveals something).

- [ ] **Step 1: Full build**

Run: `mvn -B clean install 2>&1 | grep -E "Tests run:|BUILD|ERROR\]|FAIL"`
Expected: `BUILD SUCCESS`, every `Tests run:` line with `Failures: 0, Errors: 0`.

- [ ] **Step 2: Leftovers check**

Run: `grep -rn "PARAM_PATTERN\|targetOf(\|doResolveStep\|resolveOtherSteps" copybot-engine/src copybot-ui/src`
Expected: no output.

- [ ] **Step 3: Commit any fix**

```bash
git add -A copybot-engine copybot-ui docs
git commit -m "Fix the full build after the pattern helper"
```
(only if something changed)

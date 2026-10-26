package com.copybot.engine.plugin;

import com.copybot.engine.plugin.PluginApiFingerprint.Verdict;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rule of the plugin API version against the committed fingerprint, without reflection. */
public class PluginApiRuleTest {

    private static final List<String> BASE = List.of("a.A method public void run()", "a.A type public interface a.A");
    private static final List<String> ADDED = List.of("a.A method public void run()", "a.A method public void stop()",
            "a.A type public interface a.A");
    private static final List<String> REMOVED = List.of("a.A type public interface a.A");
    private static final List<String> CHANGED = List.of("a.A method public void run(int)", "a.A type public interface a.A");

    private static Verdict check(String fileVersion, List<String> fileLines, String version, List<String> lines) {
        return PluginApiFingerprint.check(new PluginApiFingerprint(fileVersion, fileLines),
                new PluginApiFingerprint(version, lines));
    }

    @Test
    public void theSameSurfaceAndVersionIsUpToDate() {
        assertEquals(Verdict.Status.UP_TO_DATE, check("1.0", BASE, "1.0", BASE).status());
    }

    @Test
    public void aRemovedOrChangedElementNeedsANewMajor() {
        for (List<String> lines : List.of(REMOVED, CHANGED)) {
            Verdict verdict = check("1.2", BASE, "1.3", lines);
            assertEquals(Verdict.Status.RAISE_MAJOR, verdict.status(), "a new minor is not enough");
            assertTrue(verdict.message().contains("2.0"), verdict.message());
            assertTrue(verdict.message().contains("a.A method public void run()"), "names what went: " + verdict.message());
            assertTrue(verdict.message().contains("plugin.api.update"), "tells how to regenerate: " + verdict.message());
            assertEquals(Verdict.Status.RAISE_MAJOR, check("1.2", BASE, "1.2", lines).status());
            assertEquals(Verdict.Status.REGENERATE, check("1.2", BASE, "2.0", lines).status(), "raised: regenerate");
        }
    }

    @Test
    public void onlyAddedElementsNeedANewMinor() {
        Verdict verdict = check("1.2", BASE, "1.2", ADDED);
        assertEquals(Verdict.Status.RAISE_MINOR, verdict.status());
        assertTrue(verdict.message().contains("1.3"), verdict.message());
        assertTrue(verdict.message().contains("a.A method public void stop()"), "names what came: " + verdict.message());
        assertEquals(Verdict.Status.REGENERATE, check("1.2", BASE, "1.3", ADDED).status());
        assertEquals(Verdict.Status.REGENERATE, check("1.2", BASE, "2.0", ADDED).status(), "a new major is allowed too");
    }

    @Test
    public void anAbstractMemberAddedToAnExistingTypeBreaksTheOldPluginsAndNeedsANewMajor() {
        List<String> abstractAdded = List.of("a.A method public abstract void stop()", "a.A method public void run()",
                "a.A type public interface a.A");
        Verdict verdict = check("1.2", BASE, "1.3", abstractAdded);
        assertEquals(Verdict.Status.RAISE_MAJOR, verdict.status(), "an old plugin implementing a.A lacks it");
        assertTrue(verdict.message().contains("2.0") && verdict.message().contains("a.A method public abstract void stop()"),
                verdict.message());
        assertEquals(Verdict.Status.REGENERATE, check("1.2", BASE, "2.0", abstractAdded).status());

        List<String> annotation = List.of("a.N type public abstract @interface a.N extends java.lang.annotation.Annotation");
        assertEquals(Verdict.Status.RAISE_MAJOR, check("1.0", annotation, "1.1", List.of(annotation.getFirst(),
                "a.N method public abstract java.lang.String value()")).status(), "an element without default");
        assertEquals(Verdict.Status.REGENERATE, check("1.0", annotation, "1.1", List.of(annotation.getFirst(),
                "a.N method public abstract java.lang.String value() default \"x\"")).status(), "an element with a default");
    }

    @Test
    public void abstractMembersOfANewTypeOrDefaultMethodsAreMereAdditions() {
        List<String> newType = List.of("a.A method public void run()", "a.A type public interface a.A",
                "a.B method public abstract void go()", "a.B type public interface a.B");
        assertEquals(Verdict.Status.REGENERATE, check("1.2", BASE, "1.3", newType).status());
        List<String> defaultAdded = List.of("a.A method public default void stop()", "a.A method public void run()",
                "a.A type public interface a.A");
        assertEquals(Verdict.Status.REGENERATE, check("1.2", BASE, "1.3", defaultAdded).status());
        List<String> throwsAbstract = List.of("a.A method public abstract void stop() throws java.io.IOException",
                "a.A method public void run()", "a.A type public interface a.A");
        assertEquals(Verdict.Status.RAISE_MAJOR, check("1.2", BASE, "1.3", throwsAbstract).status());
    }

    @Test
    public void aMethodTurnedFromDefaultToAbstractIsBreaking() {
        List<String> withDefault = List.of("a.A method public default void run()", "a.A type public interface a.A");
        List<String> nowAbstract = List.of("a.A method public abstract void run()", "a.A type public interface a.A");
        assertEquals(Verdict.Status.RAISE_MAJOR, check("1.2", withDefault, "1.3", nowAbstract).status());
    }

    @Test
    public void aVersionRaisedWithoutChangeIsAllowedAndOnlyNeedsTheFileRegenerated() {
        assertEquals(Verdict.Status.REGENERATE, check("1.2", BASE, "1.3", BASE).status());
        assertEquals(Verdict.Status.REGENERATE, check("1.2", BASE, "2.0", BASE).status());
    }

    @Test
    public void aLowerVersionThanTheFileIsRefused() {
        assertEquals(Verdict.Status.VERSION_LOWERED, check("1.2", BASE, "1.1", BASE).status());
        assertEquals(Verdict.Status.VERSION_LOWERED, check("2.0", BASE, "1.9", ADDED).status());
    }

    @Test
    public void onlyTheVerdictsThatKeepTheGuardMayRewriteTheFile() {
        assertTrue(Verdict.Status.UP_TO_DATE.mayRewrite());
        assertTrue(Verdict.Status.REGENERATE.mayRewrite());
        assertTrue(!Verdict.Status.RAISE_MINOR.mayRewrite() && !Verdict.Status.RAISE_MAJOR.mayRewrite()
                && !Verdict.Status.VERSION_LOWERED.mayRewrite());
    }

    @Test
    public void forceRewritesWhateverTheVerdictAndOnlyForceRecreatesAMissingFile() {
        PluginApiFingerprint.Update checked = PluginApiFingerprint.Update.of("true");
        PluginApiFingerprint.Update force = PluginApiFingerprint.Update.of("force");
        assertEquals(PluginApiFingerprint.Update.NONE, PluginApiFingerprint.Update.of(null));
        assertTrue(!PluginApiFingerprint.Update.NONE.allows(Verdict.Status.REGENERATE));
        assertTrue(checked.allows(Verdict.Status.REGENERATE));
        assertTrue(!checked.allows(Verdict.Status.RAISE_MAJOR), "true never records an unbumped change");
        assertTrue(!checked.allows(null), "deleting the file does not bypass the guard");
        assertTrue(force.allows(Verdict.Status.RAISE_MAJOR) && force.allows(Verdict.Status.VERSION_LOWERED)
                && force.allows(null));
        assertThrows(IllegalArgumentException.class, () -> PluginApiFingerprint.Update.of("yes"));
    }

    @Test
    public void theDiffListsTheGoneAndTheNewLines() {
        String diff = PluginApiFingerprint.diff(new PluginApiFingerprint("1.0", BASE), new PluginApiFingerprint("1.1", CHANGED));
        assertEquals("- api 1.0\n+ api 1.1\n- a.A method public void run()\n+ a.A method public void run(int)\n", diff);
        assertEquals("", PluginApiFingerprint.diff(new PluginApiFingerprint("1.0", BASE), new PluginApiFingerprint("1.0", BASE)));
    }

    @Test
    public void theFileTextIsTheVersionLineThenTheSortedLines() {
        PluginApiFingerprint fingerprint = new PluginApiFingerprint("1.0", List.of("b", "a"));
        assertEquals("api 1.0\na\nb\n", fingerprint.text());
        assertEquals(fingerprint, PluginApiFingerprint.parse(fingerprint.text()));
        assertEquals(fingerprint, PluginApiFingerprint.parse("api 1.0\r\na\r\nb\r\n"), "CRLF after a checkout");
    }
}

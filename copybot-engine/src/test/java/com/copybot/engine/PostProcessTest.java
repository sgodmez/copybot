package com.copybot.engine;

import com.copybot.engine.pipeline.PostProcessConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The program launched at the end of a copy (spec post-process). */
public class PostProcessTest {

    @TempDir
    Path tempDir;

    /** Records the launches instead of starting a process. */
    static final class RecordingStarter implements PostProcess.Starter {
        final List<List<String>> commandLines = new ArrayList<>();
        final List<Path> directories = new ArrayList<>();

        @Override
        public void start(List<String> commandLine, Path directory) {
            commandLines.add(commandLine);
            directories.add(directory);
        }
    }

    @Test
    public void createdDirsGivesOneArgumentPerDirectoryParentsFirst() {
        RecordingStarter starter = new RecordingStarter();
        Path program = tempDir.resolve("progs").resolve("synoRight.exe");
        Path year = tempDir.resolve("2026");
        PostProcess postProcess = new PostProcess(
                new PostProcessConfig(program.toString(), List.of("gui", "{createdDirs}", "--end")), starter);

        Optional<String> warning = postProcess.run(Set.of(year.resolve("b"), year, year.resolve("a")));

        assertEquals(Optional.empty(), warning);
        assertEquals(List.of(List.of(program.toString(), "gui", year.toString(), year.resolve("a").toString(),
                year.resolve("b").toString(), "--end")), starter.commandLines);
        assertEquals(List.of(program.getParent()), starter.directories, "started in the directory of the program");
    }

    @Test
    public void theVariableOnlyStandsAlone() {
        RecordingStarter starter = new RecordingStarter();
        new PostProcess(new PostProcessConfig("prog", List.of("--dirs={createdDirs}")), starter)
                .run(Set.of(tempDir));

        assertEquals(List.of(List.of("prog", "--dirs={createdDirs}")), starter.commandLines);
    }

    @Test
    public void aQuotedProgramAndASpacedVariableAreUnderstood() {
        RecordingStarter starter = new RecordingStarter();
        Path program = tempDir.resolve("my progs").resolve("synoRight.exe");
        new PostProcess(new PostProcessConfig("\"" + program + "\"", List.of(" {createdDirs} ")), starter)
                .run(Set.of(tempDir));

        assertEquals(List.of(List.of(program.toString(), tempDir.toString())), starter.commandLines,
                "the quotes of Explorer's \"copy as path\" are not part of the name");
        assertEquals(List.of(program.getParent()), starter.directories);
    }

    @Test
    public void aCommandFoundOnThePathStartsInTheCurrentDirectory() {
        RecordingStarter starter = new RecordingStarter();
        new PostProcess(new PostProcessConfig("notify", List.of()), starter).run(Set.of());

        assertEquals(java.util.Collections.singletonList(null), starter.directories);
    }

    @Test
    public void aFailureWithoutMessageStillNamesItsCause() {
        PostProcess.Starter failing = (commandLine, directory) -> {
            throw new IllegalStateException();
        };

        String warning = new PostProcess(new PostProcessConfig("prog", List.of()), failing).run(Set.of()).orElseThrow();

        assertFalse(warning.contains("null"), warning);
        assertTrue(warning.contains("IllegalStateException"), warning);
    }

    @Test
    public void nothingIsLaunchedWithoutTheDirectoriesItNeeds() {
        RecordingStarter starter = new RecordingStarter();

        new PostProcess(new PostProcessConfig("prog", List.of("{createdDirs}")), starter).run(Set.of());
        new PostProcess(new PostProcessConfig(" ", List.of()), starter).run(Set.of(tempDir));
        new PostProcess(null, starter).run(Set.of(tempDir));

        assertEquals(List.of(), starter.commandLines);
    }

    @Test
    public void withoutTheVariableItRunsAfterEveryCopy() {
        RecordingStarter starter = new RecordingStarter();

        new PostProcess(new PostProcessConfig("notify", null), starter).run(Set.of());

        assertEquals(List.of(List.of("notify")), starter.commandLines);
    }

    @Test
    public void aLaunchFailureBecomesAWarning() {
        PostProcess.Starter failing = (commandLine, directory) -> {
            throw new IOException("Cannot run program");
        };

        Optional<String> warning = new PostProcess(new PostProcessConfig("missing.exe", List.of()), failing)
                .run(Set.of());

        assertTrue(warning.isPresent());
        assertTrue(warning.get().contains("missing.exe") && warning.get().contains("Cannot run program"),
                warning.get());
    }

    @Test
    public void theRealStarterReportsAMissingProgram() {
        Optional<String> warning = PostProcess.of(new PostProcessConfig(
                tempDir.resolve("no-such-program.exe").toString(), List.of())).run(Set.of());

        assertTrue(warning.isPresent());
    }
}

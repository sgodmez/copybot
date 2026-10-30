package com.copybot.plugin.embedded.actions;

import com.copybot.plugin.api.action.RunCounts;
import com.copybot.plugin.api.action.RunEndContext;
import com.copybot.plugin.api.action.RunOutcome;
import com.copybot.plugin.api.action.RunReport;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static org.junit.jupiter.api.Assertions.*;

/** The embedded run-end action post.process: the program launched at the end of a copy (formerly postProcess). */
public class PostProcessActionTest {

    @TempDir
    Path tempDir;

    /** Records the launches instead of starting a process. */
    public static final class RecordingStarter implements PostProcessAction.Starter {
        public final List<List<String>> commandLines = new ArrayList<>();
        public final List<Path> directories = new ArrayList<>();

        @Override
        public void start(List<String> commandLine, Path directory) {
            commandLines.add(commandLine);
            directories.add(directory);
        }
    }

    /** Records what the action tells the run. */
    private static final class RecordingContext implements RunEndContext {
        final List<String> warnings = new ArrayList<>();

        @Override
        public void publish(String key, String value) {
            fail("post.process publishes nothing");
        }

        @Override
        public void warn(String message) {
            warnings.add(message);
        }
    }

    private static PostProcessAction action(PostProcessAction.Starter starter, String command, List<String> args) {
        JsonObject config = new JsonObject();
        if (command != null) {
            config.addProperty("command", command);
        }
        if (args != null) {
            JsonArray array = new JsonArray();
            args.forEach(array::add);
            config.add("args", array);
        }
        PostProcessAction action = new PostProcessAction(starter);
        action.loadConfig(config);
        return action;
    }

    private static RunReport report(List<Path> createdDirectories) {
        Instant now = Instant.now();
        return new RunReport("p", null, RunOutcome.SUCCESS, now, now, now, new RunCounts(0, 0, 0, 0), List.of(),
                List.of(), createdDirectories, List.of(), null, "run", Map.of());
    }

    /** Runs the action at the end of a run that created these directories; the warnings it gave. */
    private static List<String> run(PostProcessAction action, List<Path> createdDirectories) {
        RecordingContext context = new RecordingContext();
        action.runEnded(report(createdDirectories), context);
        return context.warnings;
    }

    @Test
    public void createdDirsGivesOneArgumentPerDirectoryParentsFirst() {
        RecordingStarter starter = new RecordingStarter();
        Path program = tempDir.resolve("progs").resolve("synoRight.exe");
        Path year = tempDir.resolve("2026");

        List<String> warnings = run(action(starter, program.toString(), List.of("gui", "{createdDirs}", "--end")),
                List.of(year.resolve("b"), year, year.resolve("a")));

        assertEquals(List.of(), warnings);
        assertEquals(List.of(List.of(program.toString(), "gui", year.toString(), year.resolve("a").toString(),
                year.resolve("b").toString(), "--end")), starter.commandLines);
        assertEquals(List.of(program.getParent()), starter.directories, "started in the directory of the program");
    }

    @Test
    public void theVariableOnlyStandsAlone() {
        RecordingStarter starter = new RecordingStarter();
        run(action(starter, "prog", List.of("--dirs={createdDirs}")), List.of(tempDir));

        assertEquals(List.of(List.of("prog", "--dirs={createdDirs}")), starter.commandLines);
    }

    @Test
    public void aQuotedProgramAndASpacedVariableAreUnderstood() {
        RecordingStarter starter = new RecordingStarter();
        Path program = tempDir.resolve("my progs").resolve("synoRight.exe");
        run(action(starter, "\"" + program + "\"", List.of(" {createdDirs} ")), List.of(tempDir));

        assertEquals(List.of(List.of(program.toString(), tempDir.toString())), starter.commandLines,
                "the quotes of Explorer's \"copy as path\" are not part of the name");
        assertEquals(List.of(program.getParent()), starter.directories);
    }

    @Test
    public void aCommandFoundOnThePathStartsInTheCurrentDirectory() {
        RecordingStarter starter = new RecordingStarter();
        run(action(starter, "notify", List.of()), List.of());

        assertEquals(Collections.singletonList(null), starter.directories);
    }

    @Test
    public void aFailureWithoutMessageStillNamesItsCause() {
        PostProcessAction.Starter failing = (commandLine, directory) -> {
            throw new IllegalStateException();
        };

        List<String> warnings = run(action(failing, "prog", List.of()), List.of());

        assertEquals(1, warnings.size());
        assertFalse(warnings.getFirst().contains("null"), warnings.getFirst());
        assertTrue(warnings.getFirst().contains("IllegalStateException"), warnings.getFirst());
    }

    @Test
    public void nothingIsLaunchedWithoutTheDirectoriesItNeeds() {
        RecordingStarter starter = new RecordingStarter();

        run(action(starter, "prog", List.of("{createdDirs}")), List.of());
        run(action(starter, " ", List.of()), List.of(tempDir));
        run(action(starter, null, List.of()), List.of(tempDir));
        PostProcessAction withoutConfig = new PostProcessAction(starter);
        withoutConfig.loadConfig(null);
        run(withoutConfig, List.of(tempDir));

        assertEquals(List.of(), starter.commandLines);
    }

    @Test
    public void withoutTheVariableItRunsAfterEveryCopy() {
        RecordingStarter starter = new RecordingStarter();

        run(action(starter, "notify", null), List.of());

        assertEquals(List.of(List.of("notify")), starter.commandLines);
    }

    @Test
    public void aLaunchFailureBecomesAWarning() {
        PostProcessAction.Starter failing = (commandLine, directory) -> {
            throw new IOException("Cannot run program");
        };

        List<String> warnings = run(action(failing, "missing.exe", List.of()), List.of());

        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("missing.exe") && warnings.getFirst().contains("Cannot run program"),
                warnings.getFirst());
    }

    @Test
    public void theRealStarterReportsAMissingProgram() {
        PostProcessAction action = new PostProcessAction();
        JsonObject config = new JsonObject();
        config.addProperty("command", tempDir.resolve("no-such-program.exe").toString());
        action.loadConfig(config);

        assertEquals(1, run(action, List.of()).size());
    }

    @Test
    public void itMayTakeThirtySeconds() {
        assertEquals(Duration.ofSeconds(30), new PostProcessAction().defaultTimeout());
    }

    @Test
    public void theArgumentsNeverReachTheLogTheyMayHoldASecret() {
        Logger jul = Logger.getLogger(PostProcessAction.class.getName());
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Level level = jul.getLevel();
        jul.setLevel(Level.ALL);
        jul.addHandler(handler);
        try {
            run(action(new RecordingStarter(), "backup", List.of("--password", "hunter2")), List.of());
            run(action((commandLine, directory) -> {
                throw new IOException("cannot start");
            }, "backup", List.of("--password", "hunter2")), List.of());
        } finally {
            jul.removeHandler(handler);
            jul.setLevel(level);
        }

        assertEquals(3, records.size(), "the two launches and the failure are logged");
        for (LogRecord record : records) {
            String text = new SimpleFormatter().formatMessage(record);
            assertTrue(text.contains("backup"), text);
            assertFalse(text.contains("hunter2"), text);
        }
    }
}

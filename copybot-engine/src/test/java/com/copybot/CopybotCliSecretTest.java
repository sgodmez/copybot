package com.copybot;

import com.copybot.config.SecretStore;
import com.copybot.config.SecretStoreFakes;
import com.copybot.resources.ResourcesEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** {@code copybot secret set|list|delete}, on a store of a temporary folder. */
@Timeout(60)
public class CopybotCliSecretTest {

    private static final String CONFIG = "-c=./src/test/resources/com/copybot/engine/config.json";

    @TempDir
    Path tempDir;

    SecretStore store;

    @BeforeEach
    public void createStore() {
        store = SecretStore.at(tempDir.resolve("profile").resolve("secrets.json"));
    }

    /** Runs the CLI with this stdin; returns {exitCode, stdout, stderr}. */
    private String[] cli(String stdin, String... args) {
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        InputStream oldIn = System.in;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            System.setIn(new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
            int code = new CommandLine(Copybot.withSecrets(store)).execute(args);
            return new String[]{String.valueOf(code), out.toString(StandardCharsets.UTF_8),
                    err.toString(StandardCharsets.UTF_8)};
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
            System.setIn(oldIn);
        }
    }

    @Test
    public void secretSetReadsTheValueFromStdinWithoutPipeline() throws Exception {
        String[] result = cli("hunter2\nnext line\n", "secret", "set", "smtp");

        assertEquals("0", result[0], result[2]);
        assertEquals(Optional.of("hunter2"), store.get("smtp"), "one line, without its end");
        assertFalse(result[1].contains("hunter2") || result[2].contains("hunter2"), "never echoed");
        assertTrue(result[1].contains("smtp"), result[1]);
    }

    @Test
    public void secretListPrintsTheNamesOnlyAndDeleteRemovesOne() throws Exception {
        store.set("smtp", "hunter2");
        store.set("a-token", "t0k3n");

        String[] list = cli("", "secret", "list");
        assertEquals("0", list[0], list[2]);
        assertEquals(List.of("a-token", "smtp"), list[1].lines().toList());

        String[] delete = cli("", "secret", "delete", "smtp");
        assertEquals("0", delete[0], delete[2]);
        assertEquals(List.of("a-token"), store.names());

        String[] again = cli("", "secret", "delete", "smtp");
        assertEquals(String.valueOf(Copybot.EXIT_FAILED), again[0]);
        assertTrue(again[2].contains(ResourcesEngine.getString("secret.missing", "smtp")), again[2]);
    }

    @Test
    public void noValueOrABadNameSavesNothing() throws Exception {
        String[] empty = cli("", "secret", "set", "smtp");
        assertEquals(String.valueOf(Copybot.EXIT_FATAL), empty[0]);
        assertTrue(empty[2].contains(ResourcesEngine.getString("secret.cli.no-value")), empty[2]);

        String[] badName = cli("x\n", "secret", "set", "a b");
        assertEquals(String.valueOf(Copybot.EXIT_FATAL), badName[0]);
        assertTrue(badName[2].contains(ResourcesEngine.getString("secret.name.invalid", "a b")), badName[2]);

        assertEquals(List.of(), store.names());
        assertFalse(Files.exists(store.file()));
    }

    @Test
    public void aProtectionThatFailsIsTold() {
        store = SecretStoreFakes.withoutProtection(tempDir.resolve("unprotected").resolve("secrets.json"));

        String[] result = cli("hunter2\n", "secret", "set", "smtp");

        assertEquals(String.valueOf(Copybot.EXIT_FATAL), result[0]);
        assertTrue(result[2].contains(SecretStoreFakes.UNAVAILABLE), result[2]);
        assertFalse(result[1].contains("hunter2") || result[2].contains("hunter2"));
    }

    @Test
    public void secretWithoutSubcommandOrNameIsAUsageError() {
        assertEquals(String.valueOf(CommandLine.ExitCode.USAGE), cli("", "secret")[0]);
        assertEquals(String.valueOf(CommandLine.ExitCode.USAGE), cli("", "secret", "set")[0]);
        assertEquals(String.valueOf(CommandLine.ExitCode.USAGE), cli("", "secret", "delete")[0]);
    }

    @Test
    public void aRunStillNeedsItsPipeline() {
        String[] result = cli("", CONFIG, "--dry-run");

        assertEquals(String.valueOf(CommandLine.ExitCode.USAGE), result[0]);
        assertTrue(result[2].contains("--pipeline"), result[2]);
    }

    @Test
    public void aRunWhoseConfigurationNamesAnUndefinedVariableIsRefused() throws Exception {
        Path card = Files.createDirectories(tempDir.resolve("card"));
        String variable = "COPYBOT_TEST_UNDEFINED_" + System.nanoTime();
        Path pipeline = Files.writeString(tempDir.resolve("p.json"), """
                {
                  "inSteps": [ { "action": "file.read", "actionConfig": { "path": "%s" } } ],
                  "outStep": { "action": "file.write", "actionConfig": { "outPattern": "${env:%s}/{name}" } }
                }
                """.formatted(card.toString().replace('\\', '/'), variable));

        String[] result = cli("", "-p=" + pipeline, CONFIG, "--dry-run");

        assertEquals(String.valueOf(Copybot.EXIT_FATAL), result[0], result[1] + result[2]);
        assertTrue(result[2].contains(ResourcesEngine.getString("secret.env.missing", variable)), result[2]);
    }
}

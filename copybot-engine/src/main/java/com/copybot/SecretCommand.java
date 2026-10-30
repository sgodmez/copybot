package com.copybot;

import com.copybot.config.SecretStore;
import com.copybot.resources.ResourcesEngine;
import picocli.CommandLine;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.concurrent.Callable;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * {@code copybot secret set <name>}, {@code copybot secret list}, {@code copybot secret delete <name>}: the secrets of
 * this user on this machine (spec run-end, Secrets), which the pipelines name with {@code ${secret:name}}. No
 * pipeline needed. The value is never printed: when stdin is a terminal it is read without echo (the prompt on
 * stderr, whatever stdout is), else as the first line of stdin (scripts). Exit codes: {@value Copybot#EXIT_SUCCESS};
 * {@value Copybot#EXIT_FAILED} when the secret to delete is not defined; {@value Copybot#EXIT_FATAL} for an invalid
 * name, no value, a store that cannot be read or written, a protection of the system that fails, or a missing
 * subcommand or argument.
 */
@CommandLine.Command(name = "secret", mixinStandardHelpOptions = true,
        description = "Manage the secrets of this user on this machine, named by the pipelines")
final class SecretCommand implements Callable<Integer> {

    private static final int STD_INPUT_HANDLE = -10;

    @CommandLine.ParentCommand
    private Copybot parent;

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Missing required subcommand: set, list or delete");
    }

    @CommandLine.Command(name = "set", description = "Save a secret; its value is read without echo, or from stdin")
    int set(@CommandLine.Parameters(paramLabel = "<name>") String name) {
        if (!SecretStore.isValidName(name)) {
            System.err.println(ResourcesEngine.getString("secret.name.invalid", name));
            return Copybot.EXIT_FATAL;
        }
        SecretStore store = parent.secretStore();
        String value;
        try {
            value = readValue(name);
        } catch (IOException e) {
            value = null;
        }
        if (value == null || value.isEmpty()) {
            System.err.println(ResourcesEngine.getString("secret.cli.no-value"));
            return Copybot.EXIT_FATAL;
        }
        try {
            store.set(name, value);
        } catch (IOException e) {
            return failed(store, e);
        } catch (RuntimeException | LinkageError e) { // the protection of the system, should it fail unexpectedly
            System.err.println(ResourcesEngine.getString("secret.protection.failed", e.getClass().getName()));
            return Copybot.EXIT_FATAL;
        }
        System.out.println(ResourcesEngine.getString("secret.cli.saved", name, store.file()));
        return Copybot.EXIT_SUCCESS;
    }

    @CommandLine.Command(name = "list", description = "Print the names of the secrets")
    int list() {
        SecretStore store = parent.secretStore();
        try {
            store.names().forEach(System.out::println);
        } catch (IOException e) {
            return failed(store, e);
        }
        return Copybot.EXIT_SUCCESS;
    }

    @CommandLine.Command(name = "delete", description = "Delete a secret")
    int delete(@CommandLine.Parameters(paramLabel = "<name>") String name) {
        SecretStore store = parent.secretStore();
        try {
            if (!store.delete(name)) {
                System.err.println(ResourcesEngine.getString("secret.missing", name));
                return Copybot.EXIT_FAILED;
            }
        } catch (IOException e) {
            return failed(store, e);
        }
        System.out.println(ResourcesEngine.getString("secret.cli.deleted", name));
        return Copybot.EXIT_SUCCESS;
    }

    private static int failed(SecretStore store, IOException e) {
        System.err.println(e instanceof SecretStore.ProtectionException
                ? e.getMessage() // localised, with the diagnostic of the system
                : ResourcesEngine.getString("secret.store.failed", store.file(), e.getMessage()));
        return Copybot.EXIT_FATAL;
    }

    /**
     * Without echo when stdin is a terminal, the prompt on stderr; else the first line of stdin, without its end.
     * Null when there is none.
     */
    private static String readValue(String name) throws IOException {
        Console console = System.console();
        if (console != null && stdinIsTerminal(console)) {
            System.err.print(ResourcesEngine.getString("secret.cli.prompt", name) + " ");
            System.err.flush();
            char[] chars = console.readPassword();
            if (chars == null) {
                return null;
            }
            try {
                return new String(chars);
            } finally {
                Arrays.fill(chars, ' ');
            }
        }
        String encoding = System.getProperty("stdin.encoding");
        Charset charset = encoding != null && Charset.isSupported(encoding) ? Charset.forName(encoding)
                : Charset.defaultCharset();
        // not closed: stdin is the process's
        return new BufferedReader(new InputStreamReader(System.in, charset)).readLine();
    }

    /**
     * True when stdin itself is a terminal, whatever stdout is ({@link Console#isTerminal()} needs both): Windows
     * {@code GetConsoleMode} of the standard input, elsewhere {@code isatty(0)}. When the system cannot be asked, the
     * console's own answer.
     */
    private static boolean stdinIsTerminal(Console console) {
        try {
            Linker linker = Linker.nativeLinker();
            if (System.getProperty("os.name", "").startsWith("Windows")) {
                SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
                MethodHandle getStdHandle = linker.downcallHandle(kernel32.findOrThrow("GetStdHandle"),
                        FunctionDescriptor.of(ADDRESS, JAVA_INT));
                MethodHandle getConsoleMode = linker.downcallHandle(kernel32.findOrThrow("GetConsoleMode"),
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment handle = (MemorySegment) getStdHandle.invokeExact(STD_INPUT_HANDLE);
                    MemorySegment mode = arena.allocate(JAVA_INT);
                    return (int) getConsoleMode.invokeExact(handle, mode) != 0; // fails for a file or a pipe
                }
            }
            MethodHandle isatty = linker.downcallHandle(linker.defaultLookup().findOrThrow("isatty"),
                    FunctionDescriptor.of(JAVA_INT, JAVA_INT));
            return (int) isatty.invokeExact(0) == 1;
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            return console.isTerminal();
        }
    }
}

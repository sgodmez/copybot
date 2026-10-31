package com.copybot.plugin.exec.actions;

import com.copybot.plugin.api.action.AbstractActionWithConfig;
import com.copybot.plugin.api.action.IProcessAction;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.definition.IPlugin;
import com.copybot.plugin.exec.process.ItemFiles;
import com.copybot.plugin.exec.process.ProcessRunner;
import com.copybot.plugin.exec.process.ProgramResolver;
import com.copybot.plugin.exec.process.WorkFolder;
import com.copybot.resources.CombinedResourceBundle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * exec.process: runs a program on the file of each item; the file it writes ({out}) replaces the content (spec
 * exec-ffmpeg §3). The item keeps its source; its shown name takes the output extension.
 * <p>
 * The arguments may hold secrets (resolved by the engine): no message nor log shows them, only the program, its exit
 * code and the end of its error output.
 */
public class ExecProcess extends AbstractActionWithConfig<ExecConfig> implements IProcessAction {

    public static final String CODE = "exec.process";

    private static final String WORK_FOLDER = "copybot-exec";
    private static final Pattern VARIABLE = Pattern.compile("\\{(in|out|name|ext)}");

    private final WorkFolder work;
    private final ProgramResolver.Environment environment;

    public ExecProcess() {
        this(Path.of(System.getProperty("java.io.tmpdir"), WORK_FOLDER), ProgramResolver.Environment.current());
    }

    /** For the tests: the working files under this folder, the programs looked up in this environment. */
    ExecProcess(Path workFolder, ProgramResolver.Environment environment) {
        this.work = new WorkFolder(workFolder);
        this.environment = environment;
    }

    @Override
    protected Class<ExecConfig> getConfigClass() {
        return ExecConfig.class;
    }

    private ExecConfig config() {
        ExecConfig config = getConfig();
        return config == null ? new ExecConfig(null, null, null, null, null) : config;
    }

    /** {out} in none of the arguments: the program is expected to write that file. */
    @Override
    public List<String> configWarnings() {
        try {
            if (config().arguments().stream().noneMatch(argument -> argument.contains("{out}"))) {
                return List.of(message("exec.warning.no-out"));
            }
        } catch (RuntimeException e) {
            // must not throw
        }
        return List.of();
    }

    /** A program takes a processor; the pipeline may add its own resources (e.g. "gpu"). */
    @Override
    public Set<String> requiredResources(WorkItem item) {
        return Set.of("cpu");
    }

    @Override
    public Set<Path> touchedPaths(WorkItem item) {
        return Set.of(work.folder());
    }

    @Override
    public void beforeAll() {
        work.removeStaleRuns();
    }

    @Override
    public List<WorkItem> doProcess(WorkItem item) {
        ExecConfig config = config();
        if (config.program() == null || config.program().isBlank()) {
            throw new IllegalArgumentException(message("exec.error.program-missing"));
        }
        String program = config.program().strip();
        Path executable = ProgramResolver.resolve(program, environment)
                .orElseThrow(() -> new IllegalArgumentException(message("exec.error.not-found", program)));
        if (environment.windows() && ProgramResolver.isBatch(executable)) {
            throw new IllegalArgumentException(message("exec.error.batch", program));
        }
        String name = ItemFiles.name(item);
        String outName = ItemFiles.withExtension(name, outputExtension(config, name));
        Path itemFolder = null;
        boolean kept = false;
        try {
            itemFolder = work.newItemFolder();
            Path in = ItemFiles.input(item, itemFolder);
            Path out = itemFolder.resolve(ItemFiles.fileName(outName));
            List<String> command = new ArrayList<>();
            command.add(executable.toString());
            for (String argument : config.arguments()) {
                command.add(fill(argument, in, out, name));
            }
            ProcessRunner.Result result;
            try {
                result = ProcessRunner.run(command, Duration.ofSeconds(config.timeoutSeconds()), null);
            } catch (IOException e) {
                // the JDK's message names the program only
                throw new IllegalStateException(message("exec.error.start", program, e.getMessage()));
            }
            if (result.timedOut()) {
                throw new IllegalStateException(
                        message("exec.error.timeout", program, String.valueOf(config.timeoutSeconds())));
            }
            if (!config.successCodes().contains(result.exitCode())) {
                throw new IllegalStateException(result.stderrTail().isEmpty()
                        ? message("exec.error.code", program, String.valueOf(result.exitCode()))
                        : message("exec.error.code-stderr", program, String.valueOf(result.exitCode()),
                        result.stderrTail()));
            }
            if (!Files.isRegularFile(out) || Files.size(out) == 0) {
                throw new IllegalStateException(result.stderrTail().isEmpty()
                        ? message("exec.error.no-output", program)
                        : message("exec.error.no-output-stderr", program, result.stderrTail()));
            }
            WorkItem produced = ItemFiles.produced(item, out, outName);
            if (!in.equals(item.getContentFile().orElse(null))) {
                Files.deleteIfExists(in); // the copy of the content: no longer needed
            }
            work.keep(item, itemFolder);
            kept = true;
            return List.of(produced);
        } catch (InterruptedException e) {
            // a cancel: the program was killed; the interruption goes on to the engine, never an item error
            Thread.currentThread().interrupt();
            CancellationException cancelled = new CancellationException(program + " interrupted");
            cancelled.initCause(e);
            throw cancelled;
        } catch (IOException e) {
            throw new IllegalStateException(message("exec.error.io", program, name, e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage())));
        } finally {
            if (!kept && itemFolder != null) {
                WorkFolder.deleteTree(itemFolder);
            }
        }
    }

    /** The item named with the output extension: the plan shows the right target. Nothing is run. */
    @Override
    public Optional<List<WorkItem>> dryRun(WorkItem item) {
        String name = ItemFiles.name(item);
        item.getMetadatas().display().put("name", ItemFiles.withExtension(name, outputExtension(config(), name)));
        return Optional.of(List.of(item));
    }

    @Override
    public void afterItem(WorkItem item) {
        work.release(item);
    }

    @Override
    public void afterAll() {
        work.close();
    }

    private static String outputExtension(ExecConfig config, String name) {
        String extension = config.outputExtension();
        return extension == null || extension.isEmpty() ? ItemFiles.extension(name) : extension;
    }

    /** Replaces the variables {in}, {out}, {name} (without extension) and {ext} of one argument. */
    static String fill(String argument, Path in, Path out, String name) {
        // one pass: a value holding "{ext}" (a file name) is never replaced again
        Matcher matcher = VARIABLE.matcher(argument);
        StringBuilder filled = new StringBuilder();
        while (matcher.find()) {
            String value = switch (matcher.group(1)) {
                case "in" -> in.toString();
                case "out" -> out.toString();
                case "name" -> ItemFiles.base(name);
                default -> ItemFiles.extension(name);
            };
            matcher.appendReplacement(filled, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(filled);
        return filled.toString();
    }

    /** A localised message of the plugin; its key and arguments when the action has no plugin (built by hand). */
    private String message(String key, Object... args) {
        IPlugin plugin = getPlugin();
        CombinedResourceBundle bundle = plugin == null ? null : plugin.getResourceBundle();
        if (bundle == null) {
            return key + Arrays.toString(args);
        }
        String fullKey = "plugin.exec." + key;
        // a text without arguments is not a MessageFormat pattern: its braces ({out}) are shown as written
        return args.length == 0 ? bundle.getString(fullKey) : bundle.getString(fullKey, args);
    }
}
